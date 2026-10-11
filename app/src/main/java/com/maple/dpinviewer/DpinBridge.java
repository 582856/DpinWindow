package com.maple.dpinviewer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureRequest;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Looper;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.concurrent.FutureTask;
import io.github.libxposed.api.XposedModule;

final class DpinBridge {
    private static final String TAG = "DpinViewerHook";
    final Handler worker;
    final Messenger messenger;
    final DpinIsland island;
    volatile boolean active;
    volatile boolean ownsDp;
    volatile boolean nativePro;
    volatile boolean dpAttached;
    private volatile int proGeneration;
    private volatile int[] latestSignal;
    private volatile boolean replayForPro;
    private final Context context;
    private final ClassLoader loader;
    private final boolean updated;
    private Messenger client;
    private IBinder.DeathRecipient deathRecipient;
    private Surface surface;
    private int surfaceWidth, surfaceHeight, surfaceGeneration;
    private int width, height, fps, format, depth, signalStatus = -1;
    private Object camera, lifecycle, audio;
    private volatile CameraDevice currentDevice, retiredDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest previewRequest;
    private CameraCaptureSession.CaptureCallback frameCallback;
    private boolean paused;
    private boolean powered, capturing, configuring, replacingSurface;
    private boolean audioConnected, volumeWatching;
    private Method playerVolume;
    private int lastDpVolume = -1;
    private final Runnable followAppVolume = new Runnable() {
        @Override public void run() {
            if (!active || !audioConnected || nativePro) return;
            try { syncVolume(AudioManager.STREAM_MUSIC); }
            catch (Exception error) { Log.w(TAG, "App volume synchronization failed", error); }
            worker.postDelayed(this, 200);
        }
    };
    private final BroadcastReceiver volumeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context receiverContext, Intent intent) {
            int stream = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1);
            if (audioConnected && (stream == AudioManager.STREAM_MUSIC || stream == AudioManager.STREAM_VOICE_CALL)) {
                try { syncVolume(AudioManager.STREAM_MUSIC); }
                catch (Exception error) { audioStatus("音量同步失败：" + rootMessage(error), true); }
            }
        }
    };
    private long frameTotal, reportTime, reportFrames, captureAllowedAt;
    private int captureGeneration;
    private boolean firstFrame;

    DpinBridge(Context context, ClassLoader loader) throws Exception {
        this.context = context;
        this.loader = loader;
        long version = context.getPackageManager().getPackageInfo(Protocol.TARGET, 0).getLongVersionCode();
        if (version != 180020201L && version != 180010202L)
            throw new IllegalStateException("Unsupported MiLink version: " + version);
        updated = version == 180020201L;
        try { playerVolume = AudioManager.class.getMethod("getPlayerVolume", String.class); }
        catch (NoSuchMethodException error) { Log.w(TAG, "System app volume API unavailable", error); }
        HandlerThread thread = new HandlerThread("DpinPreview");
        thread.start();
        worker = new Handler(thread.getLooper(), this::command);
        messenger = new Messenger(worker);
        island = new DpinIsland(context, loader, this);
    }

    boolean updatedVersion() { return updated; }

    boolean openingCamera() {
        return active && ownsDp && !nativePro && Looper.myLooper() == worker.getLooper();
    }

    boolean ownsCamera(CameraDevice device) {
        return ownsDp && !nativePro && device != null && device == currentDevice;
    }

    void enterPro() throws Exception {
        nativePro = true;
        proGeneration++;
        active = false;
        FutureTask<Void> handoff = new FutureTask<>(() -> {
            island.cancel();
            try {
                if (ownsDp) {
                    replayForPro = powered;
                    closeCapture();
                }
            } finally {
                releaseAudio();
                capturing = paused = false;
                captureSession = null;
                previewRequest = null;
                frameCallback = null;
                currentDevice = null;
                ownsDp = powered = false;
                signalStatus = -1;
                width = height = fps = 0;
                if (surface != null) { surface.release(); surface = null; }
                nativeMode();
            }
            Log.i(TAG, "Preview released; native Pro owns DP");
            return null;
        });
        if (Looper.myLooper() == worker.getLooper()) handoff.run();
        else worker.post(handoff);
        // Native onCreate must not open Camera 5 until preview has closed it.
        handoff.get();
    }

    void exitPro() {
        int generation = proGeneration;
        try {
            // Native teardown queues camera close/powerOff; enable preview after that queue drains.
            onSerial(() -> worker.post(() -> {
                    if (generation != proGeneration) return;
                    replayForPro = false;
                    nativePro = false;
                    nativeMode();
                    Log.i(TAG, "Native Pro exited; preview may start again");
                }));
        } catch (Exception error) {
            Log.e(TAG, "Native Pro teardown fence failed", error);
            status("原厂 Pro 退出状态未确认，请重连米联", true);
        }
    }

    void replayProSignal() throws Exception {
        if (!nativePro || !replayForPro) return;
        int generation = proGeneration;
        onSerial(() -> {
            if (!nativePro || !replayForPro || generation != proGeneration) return;
            replayForPro = false;
            int[] values = latestSignal;
            if (values == null || values[5] != 0 || values[0] <= 0 || values[1] <= 0) return;
            try {
                // Native start resets its cache, but a continuously powered input may not emit it again.
                call(singleton(loader.loadClass("com.milink.dpin.hardware.bridge.BridgeManager")),
                        updated ? "c" : "b", new Class<?>[]{int.class, int.class, int.class,
                                int.class, int.class, int.class},
                        values[0], values[1], values[2], values[3], values[4], values[5]);
                Log.i(TAG, "Handed valid DP signal to Pro: " + values[0] + "x" + values[1] + "@" + values[2]);
            } catch (Exception error) { Log.e(TAG, "Native signal handoff failed", error); }
        });
    }

    private void onSerial(Runnable callback) throws Exception {
        Class<?> action = loader.loadClass(updated ? "vq.a" : "fm.a");
        Object fence = Proxy.newProxyInstance(loader, new Class<?>[]{action}, (proxy, method, args) -> {
            if (method.getName().equals("invoke")) callback.run();
            return null;
        });
        call(singleton(loader.loadClass(updated ? "pa.s" : "y7.q")), "h", new Class<?>[]{action}, fence);
    }

    private void nativeMode() {
        Bundle data = new Bundle(); data.putBoolean("active", nativePro);
        send(Protocol.NATIVE_MODE, data);
        status(nativePro ? "已让出视频和音频给原厂 Pro" : "原厂 Pro 已退出，点击开始预览", false);
    }

    private boolean command(Message message) {
        try {
            // A published Binder is not an authorization token; verify every caller UID.
            int uid = context.getPackageManager().getApplicationInfo(Protocol.APP, 0).uid;
            if (message.sendingUid != uid) { Log.w(TAG, "Rejected preview caller"); return true; }
            if (message.what == Protocol.HELLO) {
                if (message.replyTo == null) return true;
                if (client == null || !client.getBinder().equals(message.replyTo.getBinder())) {
                    disconnect();
                    surfaceGeneration = -1;
                }
                else if (deathRecipient != null) client.getBinder().unlinkToDeath(deathRecipient, 0);
                client = message.replyTo;
                IBinder binder = client.getBinder();
                deathRecipient = () -> worker.post(() -> {
                    if (client != null && binder.equals(client.getBinder())) disconnect();
                });
                binder.linkToDeath(deathRecipient, 0);
                Bundle ready = new Bundle();
                ready.putString("text", "Hook 已连接 · Camera 5 · " + (updated ? "米联 18.2" : "米联 18.1"));
                ready.putBoolean("nativePro", nativePro);
                ready.putBoolean("attached", dpAttached);
                ready.putBoolean("appVolume", playerVolume != null);
                send(Protocol.READY, ready);
                return true;
            }
            if (client == null || message.replyTo == null
                    || !client.getBinder().equals(message.replyTo.getBinder())) return true;
            if (nativePro && message.what != Protocol.BYE) {
                if (message.what == Protocol.START || message.what == Protocol.SURFACE) {
                    Bundle data = message.getData();
                    data.setClassLoader(Surface.class.getClassLoader());
                    Surface ignored = data.getParcelable("surface", Surface.class);
                    if (ignored != null) ignored.release();
                }
                nativeMode();
                return true;
            }
            if (!dpAttached && (message.what == Protocol.START || message.what == Protocol.SURFACE)) {
                Bundle data = message.getData();
                data.setClassLoader(Surface.class.getClassLoader());
                Surface ignored = data.getParcelable("surface", Surface.class);
                if (ignored != null) ignored.release();
                connectionState();
                status("请先接入 DP 线", false);
                return true;
            }
            switch (message.what) {
                case Protocol.START:
                    if (!active) {
                        active = true;
                        ownsDp = true;
                        frameTotal = 0;
                        reportTime = SystemClock.elapsedRealtime();
                        reportFrames = 0;
                        acceptSurface(message);
                        powerOn();
                        if (signalStatus == 0 || signalStatus == 3) {
                            publishSignal();
                            captureIfReady();
                        } else {
                            status("等待 DP 视频源", false);
                            captureIfReady();
                        }
                    }
                    connectionState();
                    break;
                case Protocol.SURFACE:
                    acceptSurface(message);
                    captureIfReady();
                    break;
                case Protocol.STOP:
                    stop();
                    connectionState();
                    status("预览已停止", false);
                    break;
                case Protocol.BYE:
                    disconnect();
                    break;
                default: break;
            }
        } catch (Throwable error) {
            Log.e(TAG, "Preview command failed", error);
            stop();
            connectionState();
            status("无法启动：" + rootMessage(error), true);
        }
        return true;
    }

    private void initialize() throws Exception {
        if (camera != null) return;
        Class<?> state = loader.loadClass(updated ? "ba.a" : "k7.a");
        call(singleton(state), "f", new Class<?>[]{Context.class}, context.getApplicationContext());
        Class<?> lifecycleClass = loader.loadClass("com.milink.dpin.core.lifecycle.AppLifecycleManager");
        for (Field field : state.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == lifecycleClass) {
                field.setAccessible(true);
                lifecycle = field.get(null);
                break;
            }
        }
        if (lifecycle == null) throw new IllegalStateException("DP lifecycle unavailable");
        camera = singleton(loader.loadClass(updated ? "ea.g" : "n7.f"));
        audio = singleton(loader.loadClass(updated ? "da.c" : "m7.c"));
    }

    private void powerOn() throws Exception {
        initialize();
        if (powered) return;
        signalStatus = -1;
        width = height = fps = 0;
        // Native startup also prepares the USB gadget for one-cable DP sources.
        // Preview must skip that preparation: the native relay grabs local input even when disabled.
        call(lifecycle, "j", new Class<?>[0]);
        powered = true;
    }

    private void acceptSurface(Message message) throws Exception {
        Bundle data = message.getData();
        data.setClassLoader(Surface.class.getClassLoader());
        Surface next = data.getParcelable("surface", Surface.class);
        int nextWidth = data.getInt("width"), nextHeight = data.getInt("height");
        int generation = data.getInt("generation");
        if (next == null || !next.isValid() || nextWidth <= 0 || nextHeight <= 0) {
            if (next != null) next.release();
            throw new IllegalArgumentException("预览 Surface 无效");
        }
        if (capturing && nextWidth == surfaceWidth && nextHeight == surfaceHeight
                && generation == surfaceGeneration) { next.release(); return; }
        if (capturing) {
            if (captureSession != null) captureSession.stopRepeating();
            replacingSurface |= configuring;
            configuring = false;
            captureSession = null;
            previewRequest = null;
            frameCallback = null;
        } else closeCapture();
        if (surface != null) surface.release();
        surface = next;
        surfaceWidth = nextWidth;
        surfaceHeight = nextHeight;
        surfaceGeneration = generation;
    }

    void signal(int[] values) {
        latestSignal = values;
        worker.post(() -> {
            if (!ownsDp || nativePro) return;
            boolean changed = width > 0 && height > 0
                    && (width != values[0] || height != values[1] || fps != values[2]);
            width = values[0]; height = values[1]; fps = values[2];
            format = values[3]; depth = values[4]; signalStatus = values[5];
            if (!active) {
                if (changed || (signalStatus != 0 && signalStatus != 3)) {
                    try { closeCapture(); }
                    catch (Exception error) { Log.e(TAG, "Paused capture cleanup failed", error); }
                }
                return;
            }
            publishSignal();
            try {
                if (changed || ((signalStatus != 0 && signalStatus != 3) && configuring)) closeCapture();
                if (signalStatus == 0 || signalStatus == 3) captureIfReady();
                else status(signalStatus == 1 && dpAttached ? "DP 握手未完成，等待视频源"
                        : "等待有效视频信号", false);
            } catch (Throwable error) { status(rootMessage(error), true); }
        });
    }

    private void publishSignal() {
        Bundle info = new Bundle();
        info.putInt("width", width); info.putInt("height", height); info.putInt("fps", fps);
        info.putInt("format", format); info.putInt("depth", depth); info.putInt("signal", signalStatus);
        send(Protocol.SIGNAL, info);
    }

    private void captureIfReady() throws Exception {
        if (!active || !dpAttached || nativePro || !ownsDp || !powered || surface == null || !surface.isValid()) return;
        boolean valid = (signalStatus == 0 || signalStatus == 3) && width > 0 && height > 0;
        if (valid && (surfaceWidth != width || surfaceHeight != height)) {
            status("信号已就绪，等待预览缓冲区调整", false);
            return;
        }
        if (capturing) {
            if (!valid) return;
            connectAudio();
            if (currentDevice != null && !configuring) {
                call(camera, "f", new Class<?>[]{int.class}, fps);
                configuring = true;
                firstFrame = true;
                call(camera, replacingSurface ? "d" : "g",
                        new Class<?>[]{Surface.class, int.class, int.class}, surface, width, height);
                replacingSurface = false;
            } else if (paused && captureSession != null) resumeCapture();
            return;
        }
        long delay = captureAllowedAt - SystemClock.elapsedRealtime();
        if (delay > 0) {
            int generation = captureGeneration;
            worker.postDelayed(() -> {
                if (generation != captureGeneration) return;
                try { captureIfReady(); }
                catch (Exception error) { status(rootMessage(error), true); }
            }, delay);
            status("正在恢复预览…", false);
            return;
        }
        if (valid) connectAudio();
        capturing = true;
        firstFrame = true;
        int currentCapture = ++captureGeneration;
        // startCaptureSession reopens a not-yet-opened device; wait for onOpened first.
        call(camera, "c", new Class<?>[]{Context.class}, context.getApplicationContext());
        status(valid ? "正在配置 DP 视频采集" : "正在连接 DP 视频源", false);
        long before = frameTotal;
        worker.postDelayed(() -> {
            if (active && capturing && captureGeneration == currentCapture && frameTotal == before
                    && (signalStatus == 0 || signalStatus == 3) && width > 0 && height > 0)
                status("已收到输入参数，但尚未收到视频帧", true);
        }, 15000);
    }

    void captureRequest(CameraCaptureSession session, CaptureRequest request,
            CameraCaptureSession.CaptureCallback callback) throws Exception {
        Surface target = surface;
        if (!Boolean.TRUE.equals(call(request, "containsTarget", new Class<?>[]{Surface.class}, target))) return;
        worker.post(() -> {
            if (!capturing || !ownsCamera(session.getDevice()) || surface != target) return;
            captureSession = session;
            previewRequest = request;
            frameCallback = callback;
            paused = true;
            if (active) {
                try { resumeCapture(); }
                catch (Exception error) { status(rootMessage(error), true); }
            }
        });
    }

    private void resumeCapture() throws Exception {
        connectAudio();
        call(audio, "c", new Class<?>[0]);
        firstFrame = true;
        captureSession.setRepeatingRequest(previewRequest, frameCallback, worker);
        paused = false;
        status("正在恢复 DP 视频", false);
    }

    private void connectAudio() {
        if (audioConnected) return;
        try {
            // Native video starts audio data only after audioConnected has acquired focus.
            audioConnected = Boolean.TRUE.equals(call(audio, "a", new Class<?>[0]));
            if (!audioConnected) {
                audioStatus("音频焦点申请失败", true);
                return;
            }
            syncVolume(AudioManager.STREAM_MUSIC);
            context.registerReceiver(volumeReceiver, new IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
                    null, worker, Context.RECEIVER_NOT_EXPORTED);
            volumeWatching = true;
            worker.removeCallbacks(followAppVolume);
            worker.post(followAppVolume);
            audioStatus("音频已连接，等待视频流启动", false);
        } catch (Exception error) { audioStatus("音频连接失败：" + rootMessage(error), true); }
    }

    private void syncVolume(int stream) throws Exception {
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        int maximum = manager.getStreamMaxVolume(stream);
        int volume = maximum > 0 ? manager.getStreamVolume(stream) * 150 / maximum : 0;
        // Sound Assistant applies gain only to registered players, so mirror it to the DP hardware path.
        if (playerVolume != null) {
            try {
                float gain = (Float) playerVolume.invoke(manager, Protocol.APP);
                if (!Float.isFinite(gain)) gain = 1f;
                volume = Math.round(volume * Math.max(0f, Math.min(1f, gain)));
            } catch (ReflectiveOperationException error) {
                playerVolume = null;
                Log.w(TAG, "System app volume API failed; using media volume", error);
            }
        }
        if (manager.isStreamMute(stream)) volume = 0;
        if (volume == lastDpVolume) return;
        call(audio, updated ? "l" : "k", new Class<?>[]{int.class}, volume);
        lastDpVolume = volume;
    }

    void audioStarted() {
        worker.post(() -> {
            if (active && audioConnected) audioStatus("DP 音频通道已开启", false);
        });
    }

    private void releaseAudio() {
        worker.removeCallbacks(followAppVolume);
        lastDpVolume = -1;
        try {
            if (volumeWatching) context.unregisterReceiver(volumeReceiver);
        } catch (RuntimeException error) { Log.w(TAG, "Volume receiver cleanup failed", error); }
        volumeWatching = false;
        try {
            if (audio != null && audioConnected) call(audio, "b", new Class<?>[0]);
        } catch (Exception error) { Log.e(TAG, "Audio cleanup failed", error); }
        audioConnected = false;
        audioStatus("音频已停止", false);
    }

    private void audioStatus(String text, boolean error) {
        Log.i(TAG, text);
        Bundle data = new Bundle(); data.putString("text", text); data.putBoolean("error", error);
        send(Protocol.AUDIO, data);
    }

    CameraDevice.StateCallback cameraCallback(CameraDevice.StateCallback original) {
        int generation = captureGeneration;
        return new CameraDevice.StateCallback() {
            private boolean current() { return active && !nativePro && ownsDp && capturing && generation == captureGeneration; }
            @Override public void onOpened(CameraDevice device) {
                if (!current()) { device.close(); return; }
                currentDevice = device;
                original.onOpened(device);
                try {
                    // Camera 5 disables MIPI before link training, as in the native Pro startup.
                    captureIfReady();
                } catch (Throwable error) {
                    capturing = false;
                    device.close();
                    status("无法创建采集会话：" + rootMessage(error), true);
                }
            }
            @Override public void onDisconnected(CameraDevice device) {
                if (current()) original.onDisconnected(device);
                else device.close();
            }
            @Override public void onError(CameraDevice device, int error) {
                if (current()) original.onError(device, error);
                else device.close();
            }
            @Override public void onClosed(CameraDevice device) { original.onClosed(device); }
        };
    }

    void usb(int state) {
        if (state != 0 && state != 1) return;
        dpAttached = state == 1;
        worker.post(() -> {
            if (!ownsDp || nativePro) { connectionState(); return; }
            try {
                if (state == 0) {
                    active = false;
                    closeCapture();
                    releaseAudio();
                    if (powered) {
                        call(lifecycle, "l", new Class<?>[0]);
                    }
                    powered = false;
                    signalStatus = 1;
                    width = height = fps = 0;
                    status("DP 线已拔出，等待重新连接", false);
                } else if (state == 1) {
                    if (active) {
                        powerOn();
                        status("DP 线已接入，等待视频信号", false);
                    } else status("DP 线已接入，点击开始预览", false);
                }
            } catch (Throwable error) { status(rootMessage(error), true); }
            connectionState();
        });
    }

    private void connectionState() {
        Bundle data = new Bundle();
        data.putBoolean("attached", dpAttached);
        data.putBoolean("active", active);
        send(Protocol.CONNECTION, data);
    }

    void frame(CameraCaptureSession session) {
        if (!active || !capturing || !ownsCamera(session.getDevice())) return;
        frameTotal++;
        long now = SystemClock.elapsedRealtime();
        if (firstFrame) {
            firstFrame = false;
            reportTime = now;
            reportFrames = frameTotal;
            status("正在接收 DP 视频", false);
            send(Protocol.FRAMES, new Bundle());
        }
        if (now - reportTime < 1000) return;
        Bundle data = new Bundle();
        data.putLong("frames", frameTotal);
        data.putFloat("measuredFps", (frameTotal - reportFrames) * 1000f / (now - reportTime));
        reportFrames = frameTotal;
        reportTime = now;
        send(Protocol.FRAMES, data);
    }

    void installDiagnostics(XposedModule module, ClassLoader loader) {
        String base = updated ? "ea.e" : "n7.d";
        for (String suffix : new String[]{"$b", "$c"}) {
            try {
                for (Method method : loader.loadClass(base + suffix).getDeclaredMethods()) {
                    if (!method.getName().equals("onError") && !method.getName().equals("onConfigured") && !method.getName().equals("onConfigureFailed")
                            && !method.getName().equals("onDisconnected")) continue;
                    module.hook(method).intercept(chain -> {
                        Object argument = chain.getArg(0);
                        CameraDevice device = argument instanceof CameraCaptureSession
                                ? ((CameraCaptureSession) argument).getDevice() : (CameraDevice) argument;
                        if (method.getName().equals("onConfigured")) {
                            if (device == retiredDevice) {
                                ((CameraCaptureSession) argument).close();
                                Log.i(TAG, "Discarded stale preview session");
                                return null;
                            }
                            return chain.proceed();
                        }
                        Object result = chain.proceed();
                        if (active && ownsCamera(device)) {
                            String detail = method.getName();
                            if (method.getParameterCount() == 2) detail += " " + chain.getArg(1);
                            String text = detail;
                            worker.post(() -> {
                                if (!active || !ownsCamera(device)) return;
                                capturing = false;
                                status("采集异常：" + text, true);
                            });
                        }
                        return result;
                    });
                }
            } catch (Throwable error) { Log.w(TAG, "Optional capture diagnostics unavailable", error); }
        }
    }

    private void closeCapture() throws Exception {
        captureGeneration++;
        if (currentDevice != null) retiredDevice = currentDevice;
        if (audio != null && audioConnected) call(audio, "d", new Class<?>[0]);
        if (camera != null && (capturing || currentDevice != null)) call(camera, "a", new Class<?>[0]);
        if (capturing) {
            // Back-to-back MIPI toggles can report an invalid transient input timing.
            captureAllowedAt = SystemClock.elapsedRealtime() + 1000;
        }
        capturing = false;
        configuring = false;
        replacingSurface = false;
        paused = false;
        captureSession = null;
        previewRequest = null;
        frameCallback = null;
        currentDevice = null;
    }

    private void stop() {
        active = false;
        if (!ownsDp || nativePro) return;
        try {
            if (captureSession != null) captureSession.stopRepeating();
            else closeCapture();
            paused = true;
            if (audio != null && audioConnected) call(audio, "d", new Class<?>[0]);
        } catch (Throwable error) { Log.e(TAG, "DP cleanup failed", error); }
        releaseAudio();
        // Power cycling the receiver can leave an attached source unable to renegotiate.
    }

    private void disconnect() {
        if (nativePro) {
            if (client != null && deathRecipient != null) client.getBinder().unlinkToDeath(deathRecipient, 0);
            client = null;
            deathRecipient = null;
            return;
        }
        stop();
        try {
            if (ownsDp && !nativePro && !dpAttached) {
                closeCapture();
                if (powered && !dpAttached) {
                    call(lifecycle, "l", new Class<?>[0]);
                }
            }
        } catch (Throwable error) { Log.e(TAG, "DP shutdown failed", error); }
        // Client rebinding must not interrupt link training while the cable is attached.
        if (!dpAttached) {
            powered = false;
            ownsDp = false;
            signalStatus = -1;
            width = height = fps = 0;
        }
        if (!dpAttached && surface != null) { surface.release(); surface = null; }
        if (client != null && deathRecipient != null) client.getBinder().unlinkToDeath(deathRecipient, 0);
        client = null;
        deathRecipient = null;
    }

    private void status(String text, boolean error) {
        Log.i(TAG, text);
        Bundle data = new Bundle(); data.putString("text", text); data.putBoolean("error", error);
        send(Protocol.STATUS, data);
    }

    private void send(int what, Bundle data) {
        if (client == null) return;
        Message message = Message.obtain(); message.what = what; message.setData(data);
        Messenger target = client;
        try { target.send(message); }
        catch (RemoteException error) {
            worker.post(() -> { if (client == target) disconnect(); });
        }
    }

    private static Object singleton(Class<?> type) throws Exception {
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == type) {
                field.setAccessible(true); return field.get(null);
            }
        }
        throw new NoSuchFieldException(type.getName() + " singleton");
    }

    private static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true); return method.invoke(target, args);
    }

    private static String rootMessage(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error.getClass().getSimpleName() + ": " + error.getMessage();
    }
}
