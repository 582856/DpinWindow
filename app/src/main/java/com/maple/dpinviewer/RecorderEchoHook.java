package com.maple.dpinviewer;

import android.Manifest;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import android.util.Log;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import io.github.libxposed.api.XposedInterface;

final class RecorderEchoHook {
    static final String TARGET = "com.miui.screenrecorder";
    private static final String TAG = "DpinRecorderEcho";
    private final Context context;
    private final ConcurrentHashMap<AudioRecord, Session> sessions = new ConcurrentHashMap<>();
    private volatile IBinder audioBroker;
    private long lastStatusCheck;
    private boolean dpAudioActive;

    private RecorderEchoHook(Context context) { this.context = context; }

    static void install(XposedInterface module, Context context) {
        try { new RecorderEchoHook(context).attach(module); }
        catch (Throwable error) { Log.e(TAG, "Recorder hook unavailable", error); }
    }

    private void attach(XposedInterface module) throws Exception {
        long version = context.getPackageManager().getPackageInfo(TARGET, 0).getLongVersionCode();
        if (version != 41602061L) throw new IllegalStateException("Unsupported recorder: " + version);
        Class<?> recorder = context.getClassLoader().loadClass("h0.i");
        Field internal = recorder.getDeclaredField("u");
        internal.setAccessible(true);
        Class<?> base = recorder.getSuperclass();
        Field enabled = base.getDeclaredField("h");
        enabled.setAccessible(true);
        Field threadOwner = null;
        Class<?> thread = context.getClassLoader().loadClass("h0.i$b");
        for (Field field : thread.getDeclaredFields()) {
            if (field.getType() == recorder) { threadOwner = field; break; }
        }
        if (threadOwner == null) throw new NoSuchFieldException("AudioThread owner");
        threadOwner.setAccessible(true);
        final Field ownerField = threadOwner;

        module.hook(recorder.getDeclaredMethod("m")).intercept(chain -> {
            Object result = chain.proceed();
            Object owner = chain.getThisObject();
            AudioRecord original = (AudioRecord) internal.get(owner);
            if (enabled.getBoolean(owner) && original != null) {
                sessions.put(original, new Session(owner));
                Log.i(TAG, "Internal recording prepared; DP audio active=" + previewActive());
            }
            return result;
        });
        module.hook(recorder.getDeclaredMethod("e")).intercept(chain -> {
            Object owner = chain.getThisObject();
            close(owner);
            try { return chain.proceed(); }
            finally { remove(owner); }
        });
        module.hook(thread.getDeclaredMethod("run")).intercept(chain -> {
            try { return chain.proceed(); }
            finally { close(ownerField.get(chain.getThisObject())); }
        });
        for (Class<?> array : new Class<?>[]{byte[].class, short[].class}) {
            Method read = AudioRecord.class.getDeclaredMethod("read", array, int.class, int.class);
            module.hook(read).intercept(chain -> {
                Session session = sessions.get(chain.getThisObject());
                if (session == null) return chain.proceed();
                int result = session.read(chain.getArg(0), (Integer) chain.getArg(1),
                        (Integer) chain.getArg(2));
                return result >= 0 ? result : chain.proceed();
            });
        }
        context.bindService(new Intent().setComponent(new ComponentName(Protocol.APP,
                Protocol.APP + ".AudioMixService")), new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) {
                audioBroker = service;
            }
            @Override public void onServiceDisconnected(ComponentName name) { audioBroker = null; }
        }, Context.BIND_AUTO_CREATE);
        Log.i(TAG, "Recorder echo hooks installed; 16kHz reference -> 44.1kHz mono");
    }

    private synchronized boolean previewActive() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastStatusCheck < 250) return dpAudioActive;
        lastStatusCheck = now;
        dpAudioActive = false;
        IBinder broker = audioBroker;
        if (broker == null) return false;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(AudioMixFix.INTERFACE);
            if (broker.transact(3, data, reply, 0)) {
                reply.readException();
                dpAudioActive = reply.readInt() == 1;
            }
        } catch (Exception error) { Log.w(TAG, "Preview status query failed", error); }
        finally { data.recycle(); reply.recycle(); }
        return dpAudioActive;
    }

    private void close(Object owner) {
        for (Session session : sessions.values()) if (session.owner == owner) session.close();
    }

    private void remove(Object owner) {
        sessions.entrySet().removeIf(entry -> entry.getValue().owner == owner);
    }

    private final class Session {
        final Object owner;
        final EchoPcm pcm = new EchoPcm();
        final short[] raw = new short[1280];
        short[] converted = new short[0];
        AudioRecord echo;
        boolean failed, closed;
        long frames, squareSum;

        Session(Object owner) { this.owner = owner; }

        synchronized int read(Object output, int offset, int count) {
            if (closed || failed) return -1;
            if (!previewActive()) { release(); return -1; }
            try {
                if (echo == null) {
                    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                            != PackageManager.PERMISSION_GRANTED)
                        throw new SecurityException("Grant microphone permission to Screen Recorder first");
                    AudioRecord.Builder builder = new AudioRecord.Builder()
                            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                            .setAudioFormat(new AudioFormat.Builder().setSampleRate(16000)
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setChannelIndexMask(15).build())
                            .setBufferSizeInBytes(12800);
                    AudioRecord.Builder.class.getMethod("setContext", Context.class).invoke(builder, context);
                    echo = builder.build();
                    if (echo.getState() != AudioRecord.STATE_INITIALIZED || echo.getSampleRate() != 16000
                            || echo.getChannelCount() != 4) throw new IllegalStateException("Unexpected ASR format");
                    echo.startRecording();
                    if (echo.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                        throw new IllegalStateException("ASR capture did not start");
                    Log.i(TAG, "Echo started; only reference channels 2/3 retained");
                }
                boolean bytes = output instanceof byte[];
                int samples = bytes ? count / 2 : count;
                if (converted.length < samples) converted = new short[samples];
                int written = 0;
                long deadline = SystemClock.elapsedRealtime() + 500;
                while (written < samples) {
                    written += pcm.read(converted, written, samples - written);
                    if (written == samples) break;
                    int n = echo.read(raw, 0, raw.length, AudioRecord.READ_NON_BLOCKING);
                    if (n < 0) throw new IllegalStateException("ASR read error: " + n);
                    if (n > 0) {
                        try { pcm.append(raw, n); }
                        finally { Arrays.fill(raw, (short) 0); }
                    } else {
                        if (SystemClock.elapsedRealtime() >= deadline)
                            throw new IllegalStateException("ASR read timeout");
                        SystemClock.sleep(2);
                    }
                }
                if (bytes) {
                    byte[] destination = (byte[]) output;
                    for (int i = 0; i < samples; i++) {
                        destination[offset + i * 2] = (byte) converted[i];
                        destination[offset + i * 2 + 1] = (byte) (converted[i] >> 8);
                    }
                } else System.arraycopy(converted, 0, output, offset, samples);
                for (int i = 0; i < samples; i++) squareSum += (long) converted[i] * converted[i];
                frames += samples;
                return bytes ? samples * 2 : samples;
            } catch (Throwable error) {
                failed = true;
                release();
                Log.e(TAG, "Echo unavailable; keeping original internal recording", error);
                return -1;
            }
        }

        synchronized void close() { closed = true; release(); }

        private void release() {
            if (echo != null) {
                try { echo.release(); }
                catch (RuntimeException error) { Log.w(TAG, "Echo release failed", error); }
                echo = null;
                Log.i(TAG, "Echo released; frames=" + frames + " meanSquare="
                        + (frames == 0 ? 0 : squareSum / frames));
            }
            pcm.clear();
            Arrays.fill(raw, (short) 0);
            Arrays.fill(converted, (short) 0);
        }
    }
}
