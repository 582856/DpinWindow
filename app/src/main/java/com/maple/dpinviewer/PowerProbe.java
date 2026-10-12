package com.maple.dpinviewer;

import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import org.json.JSONObject;

public final class PowerProbe {
    private static final String SUPPLY = "/sys/class/power_supply/";
    private static final String BACKLIGHT = "/sys/class/backlight/panel0-backlight/";
    private static final String MODEL = "/sys/devices/platform/dee10000.pmodel/xr_pmodel/";
    private double screenOn = Double.NaN, screenFull = Double.NaN;
    private long previousEnergy = -1, previousTime;

    public static void main(String[] args) throws Exception {
        PowerProbe probe = new PowerProbe();
        probe.readScreenProfile();
        try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in))) {
            while (input.readLine() != null) {
                JSONObject sample;
                try { sample = probe.sample(); }
                catch (Exception error) { sample = new JSONObject().put("error", "功率读数不可用"); }
                System.out.println(sample);
                System.out.flush();
            }
        }
    }

    private void readScreenProfile() {
        try {
            Process profile = new ProcessBuilder("/system/bin/dumpsys", "batterystats", "--power-profile")
                    .redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(profile.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("screen.on.display0=")) screenOn = profileValue(line);
                    if (line.startsWith("screen.full.display0=")) screenFull = profileValue(line);
                }
            }
            profile.waitFor();
        } catch (Exception ignored) { }
    }

    private static double profileValue(String line) {
        return Double.parseDouble(line.substring(line.indexOf('=') + 1).trim().split("\\s+")[0]);
    }

    private JSONObject sample() throws Exception {
        JSONObject sample = new JSONObject();
        String role = read("/sys/class/typec/port0/power_role");
        boolean source = role.contains("[source]"), sink = role.contains("[sink]");
        boolean usbPresent = number(SUPPLY + "usb/present") != 0;
        double batteryVoltage = number(SUPPLY + "battery/voltage_now");
        double batteryPower = batteryVoltage * Math.abs(number(SUPPLY + "battery/current_now")) / 1e12;
        sample.put("source", source).put("sink", sink).put("usbPresent", usbPresent)
                .put("battery", batteryPower);
        if (sink) sample.put("input", Math.abs(number(SUPPLY + "usb/voltage_now")
                * number(SUPPLY + "usb/current_now")) / 1e12);
        boolean discharging = "Discharging".equals(read(SUPPLY + "battery/status"));
        sample.put("discharging", discharging);
        try {
            long energy = energy("cpu-dpm") + energy("gpu-dpm");
            sample.put("modelAvailable", Double.isFinite(screenOn) && Double.isFinite(screenFull));
            long now = SystemClock.elapsedRealtimeNanos();
            // The kernel exports nJ; nJ/ns is watts. Exclude resets and gaps after suspension.
            double seconds = (now - previousTime) / 1e9;
            if (previousEnergy >= 0 && energy >= previousEnergy && seconds >= 0.2 && seconds <= 5) {
                double soc = (double) (energy - previousEnergy) / (now - previousTime);
                double brightness = Math.max(0, Math.min(1, number(BACKLIGHT + "actual_brightness")
                        / number(BACKLIGHT + "max_brightness")));
                double screen = brightness == 0 ? 0 : (screenOn + screenFull * brightness) * batteryVoltage / 1e9;
                if (Double.isFinite(screen) && screen >= 0) sample.put("components", soc + screen);
            }
            previousEnergy = energy;
            previousTime = now;
        } catch (Exception ignored) { previousEnergy = -1; sample.put("modelAvailable", false); }
        return sample;
    }

    private static long energy(String group) throws Exception {
        File[] counters = new File(MODEL + group + "/dpms").listFiles();
        if (counters == null || counters.length == 0) throw new IllegalStateException("No energy counters");
        long total = 0;
        for (File counter : counters) {
            if (counter.isDirectory()) total += Long.parseLong(read(counter + "/total_energy").split("\\s+")[0]);
        }
        return total;
    }

    private static double number(String path) throws Exception { return Double.parseDouble(read(path)); }

    private static String read(String path) throws Exception {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String value = reader.readLine();
            if (value == null) throw new IllegalStateException("Empty power reading");
            return value.trim();
        }
    }
}
