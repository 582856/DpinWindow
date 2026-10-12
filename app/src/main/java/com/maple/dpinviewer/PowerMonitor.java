package com.maple.dpinviewer;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.Locale;
import java.util.function.Consumer;
import org.json.JSONObject;

final class PowerMonitor {
    private final Context context;
    private final Consumer<String> display;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Process process;
    private volatile PrintWriter commands;
    private volatile int generation, dpState;
    private double smooth = Double.NaN;

    PowerMonitor(Context context, Consumer<String> display) {
        this.context = context;
        this.display = display;
    }

    void state(boolean connected, boolean attached, boolean nativeMode) {
        dpState = !connected || nativeMode ? 0 : attached ? 2 : 1;
    }

    void start() {
        stop();
        int run = generation;
        new Thread(() -> collect(run), "DpinPower").start();
    }

    synchronized void stop() {
        generation++;
        PrintWriter writer = commands;
        commands = null;
        if (writer != null) writer.close();
        Process child = process;
        process = null;
        if (child != null) child.destroy();
    }

    private void collect(int run) {
        Process child = null;
        try {
            String apk = context.getApplicationInfo().sourceDir.replace("'", "'\\''");
            child = new ProcessBuilder("su", "-c", "CLASSPATH='" + apk
                    + "' exec /system/bin/app_process /system/bin com.maple.dpinviewer.PowerProbe")
                    .redirectErrorStream(true).start();
            try (PrintWriter writer = new PrintWriter(child.getOutputStream(), true);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(child.getInputStream()))) {
                synchronized (this) {
                    if (run != generation) return;
                    process = child;
                    commands = writer;
                }
                smooth = Double.NaN;
                while (run == generation) {
                    writer.println("sample");
                    String line;
                    do { line = reader.readLine(); } while (line != null && !line.startsWith("{"));
                    if (line == null) throw new IllegalStateException("Power access denied");
                    if (run != generation) return;
                    publish(run, estimate(new JSONObject(line)));
                    Thread.sleep(1000);
                }
            }
        } catch (Exception error) { publish(run, "需授权 root 或读数不可用"); }
        finally {
            if (child != null) child.destroy();
            synchronized (this) {
                if (run == generation) { commands = null; process = null; }
            }
        }
    }

    private String estimate(JSONObject sample) {
        if (sample.has("error")) return "读数不可用";
        if (sample.optBoolean("sink") && sample.optBoolean("usbPresent")) {
            return watts(sample.optDouble("input", Double.NaN), true);
        }
        if (dpState != 2) { smooth = Double.NaN; return "等待连接"; }
        if (!sample.optBoolean("discharging")) { smooth = Double.NaN; return "0.0W"; }
        // Previous calculation:
        // Keep the residual calibration, but update CPU/GPU and screen consumption for each sample.
        // Display total battery consumption now, without component deductions or calibration.
        return watts(sample.optDouble("battery", Double.NaN), false);
    }

    private String watts(double value, boolean charging) {
        if (!Double.isFinite(value) || value < 0) return "读数不可用";
        double signed = charging ? value : -value;
        if (!Double.isFinite(smooth) || Math.signum(smooth) != Math.signum(signed)) smooth = signed;
        else smooth += 0.35 * (signed - smooth);
        return String.format(Locale.ROOT, "%s%.1fW", charging ? "+" : "-", Math.abs(smooth));
    }

    private void publish(int run, String text) {
        main.post(() -> { if (run == generation) display.accept(text); });
    }
}
