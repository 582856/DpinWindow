package com.maple.dpinviewer;

import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import android.util.Log;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class AudioMixFix {
    private static final String LIBRARY = "/vendor/lib64/libaudioserviceimpl.so";
    private static final String SYMBOL = "_ZN7android8hardware8xm_audio7feature8DpinMute6sStateE";
    private static final String FEATURES = "_ZN7android8hardware8xm_audio7feature14FeatureManager11getInstanceEv";
    static final String INTERFACE = Protocol.APP + ".AudioMix";
    private final Context context;
    private Process process;
    private PrintWriter commands;
    private BufferedReader replies;
    private volatile IBinder broker;
    private ServiceConnection binding;
    private volatile boolean ready;

    AudioMixFix(Context context) { this.context = context; }

    boolean ready() { return ready; }

    synchronized void start() {
        if (ready && (broker != null && broker.isBinderAlive() || process != null && process.isAlive())) return;
        stop();
        try {
            if (Protocol.TARGET.equals(context.getPackageName())) {
                // MiLink cannot execute su in its SELinux domain; only the app launches root.
                java.util.concurrent.CountDownLatch connected = new java.util.concurrent.CountDownLatch(1);
                binding = new ServiceConnection() {
                    @Override public void onServiceConnected(ComponentName name, IBinder service) { broker = service; connected.countDown(); }
                    @Override public void onServiceDisconnected(ComponentName name) { broker = null; ready = false; }
                    @Override public void onNullBinding(ComponentName name) { connected.countDown(); }
                };
                Intent intent = new Intent().setComponent(new ComponentName(Protocol.APP, Protocol.APP + ".AudioMixService"));
                if (!context.bindService(intent, Context.BIND_AUTO_CREATE, Runnable::run, binding)) {
                    binding = null;
                    throw new IllegalStateException("Audio broker bind failed");
                }
                if (!connected.await(4000, java.util.concurrent.TimeUnit.MILLISECONDS) || broker == null || !transact(1))
                    throw new IllegalStateException("Audio broker rejected preview");
                ready = true;
                Log.i("DpinViewerHook", "Preview audio mute policy suspended until native teardown");
                return;
            }
            String apk = context.getPackageManager().getApplicationInfo(Protocol.APP, 0)
                    .sourceDir.replace("'", "'\\''");
            process = new ProcessBuilder("su", "-c", "CLASSPATH='" + apk
                    + "' exec /system/bin/app_process /system/bin com.maple.dpinviewer.AudioMixFix")
                    .redirectErrorStream(true).start();
            commands = new PrintWriter(process.getOutputStream(), true);
            replies = new BufferedReader(new InputStreamReader(process.getInputStream()));
            expect("READY", 5000);
                // Confirm the preview is still active on its UI thread before changing HAL state.
            // The bridge worker now owns this handshake before native audio starts.
            commands.println("ENABLE");
            expect("OK", 2000);
            ready = true;
            Log.i("DpinViewer", "Root audio broker ready");
        } catch (Exception error) {
            Log.w("DpinViewerHook", "Shared audio requires root access to the audio HAL", error);
            stop();
        }
    }

    synchronized boolean stop() {
        boolean wasReady = ready, restored = true;
        ready = false;
        if (broker != null) {
            try {
                if (!transact(2)) {
                    restored = false;
                    Log.w("DpinViewerHook", "Audio broker restoration was not acknowledged");
                }
            } catch (Exception error) {
                restored = false;
                Log.w("DpinViewerHook", "Audio broker disconnected", error);
            }
            broker = null;
        }
        if (binding != null) {
            context.unbindService(binding);
            binding = null;
        }
        if (commands != null) { commands.println("RESTORE"); commands.close(); commands = null; }
        if (process != null) {
            try {
                if (!process.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    restored = false;
                    Log.w("DpinViewerHook", "Audio policy restoration still pending");
                } else if (wasReady) expect("RESTORED", 100);
            } catch (Exception error) {
                restored = false;
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                Log.w("DpinViewerHook", "Audio policy restoration failed", error);
            }
            process = null;
        }
        if (replies != null) {
            try { replies.close(); } catch (java.io.IOException ignored) { }
            replies = null;
        }
        return restored;
    }

    private boolean transact(int code) throws Exception {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(INTERFACE);
            if (!broker.transact(code, data, reply, 0)) return false;
            reply.readException();
            return reply.readInt() == 1;
        } finally { data.recycle(); reply.recycle(); }
    }

    private void expect(String expected, long timeout) throws Exception {
        long end = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < end) {
            if (replies.ready()) {
                String line = replies.readLine();
                if (expected.equals(line)) return;
                if (line == null || "ERROR".equals(line)) break;
            } else if (process != null && !process.isAlive()) break;
            Thread.sleep(10);
        }
        throw new IllegalStateException("Audio helper did not acknowledge " + expected);
    }

    public static void main(String[] args) {
        try {
            long value = symbolValue(SYMBOL, false);
            Process lookup = new ProcessBuilder("/system/bin/pidof", "android.hardware.audio.service-aidl").start();
            String pid;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(lookup.getInputStream()))) {
                pid = reader.readLine();
            }
            if (lookup.waitFor() != 0 || pid == null || !pid.matches("[0-9]+"))
                throw new IllegalStateException("No unique audio HAL process");
            long base = -1;
            String maps = "/proc/" + pid + "/maps";
            try (BufferedReader reader = new BufferedReader(new FileReader(maps))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] fields = line.trim().split("\\s+");
                    if (line.endsWith(LIBRARY) && Long.parseLong(fields[2], 16) == 0) {
                        if (base != -1) throw new IllegalStateException("Ambiguous audio library mapping");
                        base = Long.parseLong(fields[0].split("-")[0], 16);
                    }
                }
            }
            if (base == -1) throw new IllegalStateException("Audio library not loaded");
            long address = base + value;
            boolean writable = false;
            try (BufferedReader reader = new BufferedReader(new FileReader(maps))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] fields = line.trim().split("\\s+");
                    String[] range = fields[0].split("-");
                    if (fields[1].startsWith("rw") && address >= Long.parseLong(range[0], 16)
                            && address + 8 <= Long.parseLong(range[1], 16)) writable = true;
                }
            }
            if (!writable) throw new IllegalStateException("Mute state is not writable");
            try (RandomAccessFile memory = new RandomAccessFile("/proc/" + pid + "/mem", "rw");
                 BufferedReader commands = new BufferedReader(new InputStreamReader(System.in))) {
                System.out.println("READY");
                System.out.flush();
                String command = commands.readLine();
                if ("INSPECT".equals(command)) {
                    long feature = featureAddress(memory, base, maps);
                    memory.seek(feature);
                    System.out.println("FEATURE=" + memory.readUnsignedByte());
                    return;
                }
                if (!"APPLY".equals(command) && !"ENABLE".equals(command)) return;
                long feature = "ENABLE".equals(command) ? featureAddress(memory, base, maps) : -1;
                if (feature != -1) {
                    memory.seek(feature);
                    int original = memory.readUnsignedByte();
                    if (original != 1) throw new IllegalStateException("Unexpected DP mute feature state");
                    try {
                        memory.seek(feature);
                        memory.write(0);
                        clearMute(memory, address);
                        System.out.println("OK");
                        System.out.flush();
                        commands.readLine();
                    } finally {
                        memory.seek(feature);
                        if (memory.readUnsignedByte() == 0) {
                            memory.seek(feature);
                            memory.write(original);
                        }
                    }
                    System.out.println("RESTORED");
                    return;
                }
                clearMute(memory, address);
                System.out.println("OK");
            }
        } catch (Exception error) { System.out.println("ERROR"); }
    }

    private static void clearMute(RandomAccessFile memory, long address) throws Exception {
                memory.seek(address);
                byte[] state = new byte[8];
                memory.readFully(state);
                // The native DP-on command sets this exported atomic state to -1 (media muted).
                // Clear only that state; DP-off and Pro keep using their original HAL commands.
                if (ByteBuffer.wrap(state).order(ByteOrder.LITTLE_ENDIAN).getLong() == -1) {
                    memory.seek(address);
                    memory.write(new byte[8]);
                }
    }

    private static long featureAddress(RandomAccessFile memory, long base, String maps) throws Exception {
        long getter = symbolValue(FEATURES, true);
        try (RandomAccessFile elf = new RandomAccessFile(LIBRARY, "r")) {
            // Decode the singleton address only for the verified AArch64 getter layout.
            ByteBuffer header = read(elf, 0, 64);
            if (header.getShort(18) != 183) throw new IllegalStateException("Unsupported HAL architecture");
            if (header.getShort(54) != 56) throw new IllegalStateException("Unsupported program headers");
            long programs = header.getLong(32), fileOffset = -1;
            for (int i = 0; i < Short.toUnsignedInt(header.getShort(56)); i++) {
                ByteBuffer program = read(elf, programs + (long) i * 56, 56);
                long start = program.getLong(16);
                if (program.getInt(0) == 1 && getter >= start && getter + 120 <= start + program.getLong(32))
                    fileOffset = program.getLong(8) + getter - start;
            }
            if (fileOffset < 0) throw new IllegalStateException("Invalid feature getter mapping");
            ByteBuffer code = read(elf, fileOffset + 28, 12);
            int page = code.getInt(0), add = code.getInt(4);
            if ((page & 0x9f00001f) != 0x90000000 || (add & 0xffc003ff) != 0x91000000
                    || code.getInt(8) != 0xf9400bf3)
                throw new IllegalStateException("Unsupported feature getter instructions");
            long immediate = ((page >>> 5 & 0x7ffffL) << 2) | (page >>> 29 & 3);
            if ((immediate & 0x100000) != 0) immediate -= 0x200000;
            long table = base + ((getter + 28) & ~4095L) + (immediate << 12) + (add >>> 10 & 0xfff);
            requireWritable(maps, table, 40);
            ByteBuffer object = read(memory, table, 40);
            long count = object.getLong(24), node = object.getLong(16) & 0x00ffffffffffffffL;
            if (count < 1 || count > 256) throw new IllegalStateException("Invalid feature table size");
            java.util.HashSet<Long> seen = new java.util.HashSet<>();
            for (int i = 0; i < count && node != 0; i++) {
                requireWritable(maps, node, 48);
                if (!seen.add(node)) throw new IllegalStateException("Cyclic feature table");
                ByteBuffer item = read(memory, node, 48);
                int tag = Byte.toUnsignedInt(item.get(16));
                long length = (tag & 1) == 0 ? tag >>> 1 : item.getLong(24);
                long text = (tag & 1) == 0 ? node + 17 : item.getLong(32) & 0x00ffffffffffffffL;
                if (length == 9) {
                    requireWritable(maps, text, 9);
                    String name = new String(read(memory, text, 9).array(), java.nio.charset.StandardCharsets.US_ASCII);
                    if ("dpin_mute".equals(name)) return node + 40;
                }
                node = item.getLong(0) & 0x00ffffffffffffffL;
            }
            throw new IllegalStateException("DP mute feature unavailable");
        }
    }

    private static void requireWritable(String maps, long address, int size) throws Exception {
        try (BufferedReader reader = new BufferedReader(new FileReader(maps))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.trim().split("\\s+"), range = fields[0].split("-");
                if (fields[1].startsWith("rw") && address >= Long.parseLong(range[0], 16)
                        && address + size <= Long.parseLong(range[1], 16)) return;
            }
        }
        throw new IllegalStateException("Invalid feature storage");
    }

    private static long symbolValue(String requested, boolean function) throws Exception {
        try (RandomAccessFile elf = new RandomAccessFile(LIBRARY, "r")) {
            ByteBuffer header = read(elf, 0, 64);
            if (header.getInt(0) != 0x464c457f || header.get(4) != 2 || header.get(5) != 1)
                throw new IllegalStateException("Unsupported audio library ELF");
            long sections = header.getLong(40);
            int stride = Short.toUnsignedInt(header.getShort(58));
            int count = Short.toUnsignedInt(header.getShort(60));
            if (stride != 64) throw new IllegalStateException("Unsupported ELF sections");
            for (int index = 0; index < count; index++) {
                ByteBuffer section = read(elf, sections + (long) index * stride, stride);
                if (section.getInt(4) != 11 || section.getLong(56) != 24) continue;
                ByteBuffer names = read(elf, sections + (long) section.getInt(40) * stride, stride);
                for (long offset = 0; offset + 24 <= section.getLong(32); offset += 24) {
                    ByteBuffer symbol = read(elf, section.getLong(24) + offset, 24);
                    if ((symbol.get(4) & 15) != (function ? 2 : 1) || symbol.getLong(16) != (function ? 120 : 8)) continue;
                    long nameOffset = Integer.toUnsignedLong(symbol.getInt(0));
                    if (nameOffset + requested.length() + 1 > names.getLong(32)) continue;
                    byte[] name = read(elf, names.getLong(24) + nameOffset, requested.length() + 1).array();
                    if (!requested.equals(new String(name, 0, name.length - 1, java.nio.charset.StandardCharsets.US_ASCII))
                            || name[name.length - 1] != 0) continue;
                    int target = Short.toUnsignedInt(symbol.getShort(6));
                    if (target >= count) throw new IllegalStateException("Invalid mute symbol section");
                    ByteBuffer storage = read(elf, sections + (long) target * stride, stride);
                    long value = symbol.getLong(8), start = storage.getLong(16);
                    if ((storage.getLong(8) & (function ? 4 : 1)) == 0 || value < start
                            || value + symbol.getLong(16) > start + storage.getLong(32))
                        throw new IllegalStateException("Invalid mute symbol storage");
                    return value;
                }
            }
            throw new IllegalStateException("No exported DP media mute state");
        }
    }

    private static ByteBuffer read(RandomAccessFile file, long offset, int size) throws Exception {
        byte[] bytes = new byte[size];
        file.seek(offset);
        file.readFully(bytes);
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }
}
