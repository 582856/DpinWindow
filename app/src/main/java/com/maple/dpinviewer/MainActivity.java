package com.maple.dpinviewer;

import android.Manifest;
import androidx.activity.ComponentActivity;
import androidx.activity.EdgeToEdge;
import androidx.activity.SystemBarStyle;
import android.app.PictureInPictureParams;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Rational;
import android.util.Log;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.window.OnBackInvokedDispatcher;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

public final class MainActivity extends ComponentActivity implements SurfaceHolder.Callback {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Messenger reply = new Messenger(new Handler(Looper.getMainLooper(), this::receive));
    private Messenger server;
    private SharedPreferences preferences;
    private LinearLayout root;
    private MiuixControls controls;
    private PowerMonitor powerMonitor;
    private ReleaseUpdateChecker releaseUpdates;
    private FrameLayout previewArea;
    private FrameLayout preview;
    private SurfaceView video;
    private AudioTrack volumeRegistration;
    private boolean appVolumeSupported, volumeRegistrationFailed;
    private boolean activityStarted, powerSampling;
    private boolean bound, connected, desired, started, nativeMode, dpAttached, pendingIsland, windowFullscreen, smallWindowMode;
    private int inputWidth = 1920, inputHeight = 1080, generation;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            server = new Messenger(binder);
            send(Protocol.HELLO, null);
            handler.postDelayed(() -> {
                if (!connected) showStatus("Hook 未响应：请在 LSPosed 启用本模块并勾选米联，再点重连", true);
            }, 4000);
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            connected = desired = started = false;
            server = null;
            updateControl();
            showStatus("米联连接已断开，点重连", true);
        }
        @Override public void onBindingDied(ComponentName name) { onServiceDisconnected(name); }
        @Override public void onNullBinding(ComponentName name) { onServiceDisconnected(name); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        EdgeToEdge.enable(this, SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
                SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT));
        getWindow().setNavigationBarContrastEnforced(false);
        preferences = getSharedPreferences("preview", MODE_PRIVATE);
        inputWidth = Math.max(1, preferences.getInt("width", 1920));
        inputHeight = Math.max(1, preferences.getInt("height", 1080));
        pendingIsland = getIntent().getBooleanExtra("fromIsland", false);
        smallWindowMode = isInMultiWindowMode();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets padding = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            if (windowFullscreen || isInPictureInPictureMode()) view.setPadding(0, 0, 0, 0);
            else view.setPadding(padding.left, padding.top, padding.right, 0);
            return insets;
        });
        controls = new MiuixControls(this, () -> {
            if (desired) stopPreview();
            else startPreview();
        }, this::connect, color -> root.setBackgroundColor(color));
        powerMonitor = new PowerMonitor(this, controls::setPower);
        root.addView(controls);
        preview = new FrameLayout(this);
        preview.setBackgroundColor(Color.BLACK);
        preview.setForeground(new ColorDrawable(Color.BLACK));
        video = new SurfaceView(this);
        ViewOutlineProvider outline = new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        windowFullscreen || isInPictureInPictureMode() ? 0 : dp(24));
            }
        };
        preview.setOutlineProvider(outline);
        preview.setClipToOutline(true);
        video.setOutlineProvider(outline);
        video.setClipToOutline(true);
        // Keep the same capture target when the activity is temporarily hidden.
        if (android.os.Build.VERSION.SDK_INT >= 34)
            video.setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT);
        GestureDetector taps = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onDoubleTap(MotionEvent event) {
                setWindowFullscreen(!windowFullscreen);
                return true;
            }
        });
        video.setOnTouchListener((view, event) -> taps.onTouchEvent(event));
        preview.setOnTouchListener((view, event) -> taps.onTouchEvent(event));
        video.getHolder().setFixedSize(inputWidth, inputHeight);
        video.getHolder().addCallback(this);
        preview.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        preview.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fitVideo());
        previewArea = new FrameLayout(this) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) preview.getLayoutParams();
                if (windowFullscreen || isInPictureInPictureMode()) {
                    params.width = params.height = LayoutParams.MATCH_PARENT;
                } else {
                    int width = Math.max(0, MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight());
                    params.width = width;
                    params.height = (int) Math.round(width * 9d / 16d);
                    heightSpec = MeasureSpec.makeMeasureSpec(params.height + getPaddingTop()
                            + getPaddingBottom(), MeasureSpec.EXACTLY);
                }
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        previewArea.addView(preview, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addView(previewArea, new LinearLayout.LayoutParams(-1, -2));
        updatePreviewFrame();
        ScrollView page = new ScrollView(this);
        page.setFillViewport(true);
        page.setVerticalScrollBarEnabled(false);
        page.addView(root, new ScrollView.LayoutParams(-1, -2));
        setContentView(page);
        MiuixUpdatePrompt updatePrompt = new MiuixUpdatePrompt(this);
        addContentView(updatePrompt, new android.view.ViewGroup.LayoutParams(0, 0));
        releaseUpdates = new ReleaseUpdateChecker(this, updatePrompt);
        if (smallWindowMode) setWindowFullscreen(true);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> {
            if (windowFullscreen) setWindowFullscreen(false);
            else finish();
        });
        connect();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_VOLUME_UP || key == KeyEvent.KEYCODE_VOLUME_DOWN
                || key == KeyEvent.KEYCODE_VOLUME_MUTE) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && !event.isCanceled()
                    && (key != KeyEvent.KEYCODE_VOLUME_MUTE || event.getRepeatCount() == 0)) {
                // Native DP uses call mode, which overrides the window's suggested volume stream.
                int direction = key == KeyEvent.KEYCODE_VOLUME_UP ? AudioManager.ADJUST_RAISE
                        : key == KeyEvent.KEYCODE_VOLUME_DOWN ? AudioManager.ADJUST_LOWER
                        : AudioManager.ADJUST_TOGGLE_MUTE;
                getSystemService(AudioManager.class).adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        direction, AudioManager.FLAG_SHOW_UI);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (activityStarted) releaseUpdates.check();
        if (intent.getBooleanExtra("fromIsland", false)) {
            pendingIsland = true;
            startFromIsland();
        }
    }

    private void startPreview() {
        if (!connected || !dpAttached || nativeMode) {
            showStatus(nativeMode ? "请先退出原厂 Pro" : "请先接入 DP 线", false);
            return;
        }
        desired = true;
        preview.setForeground(new ColorDrawable(Color.BLACK));
        updateControl();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 10);
        else publishSurface();
    }

    private void stopPreview() {
        desired = started = false;
        send(Protocol.STOP, null);
        preview.setForeground(new ColorDrawable(Color.BLACK));
        updateControl();
        showStatus("预览已停止", false);
    }

    private void startFromIsland() {
        if (!pendingIsland || !connected || !dpAttached || nativeMode) return;
        pendingIsland = false;
        if (!desired) startPreview();
        else publishSurface();
    }

    private void updateControl() {
        powerMonitor.state(connected, dpAttached, nativeMode);
        if (!desired || !connected || nativeMode) releaseVolumeRegistration();
        controls.setPreview(desired, connected && dpAttached && !nativeMode);
    }

    private void registerAppVolume() {
        if (!appVolumeSupported || volumeRegistration != null || volumeRegistrationFailed || !started
                || !getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return;
        try {
            // DP audio bypasses AudioTrack; a silent media player exposes this app in Sound Assistant.
            byte[] silence = new byte[8820];
            volumeRegistration = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(44100)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(silence.length).build();
            if (volumeRegistration.write(silence, 0, silence.length) != silence.length
                    || volumeRegistration.setLoopPoints(0, silence.length / 2, -1) != AudioTrack.SUCCESS)
                throw new IllegalStateException("Unable to initialize volume registration");
            volumeRegistration.play();
        } catch (RuntimeException error) {
            volumeRegistrationFailed = true;
            releaseVolumeRegistration();
            Log.w("DpinViewer", "App volume registration failed", error);
        }
    }

    private void releaseVolumeRegistration() {
        if (volumeRegistration == null) return;
        volumeRegistration.release();
        volumeRegistration = null;
    }

    private void connect() {
        disconnect();
        connected = started = false;
        updateControl();
        showStatus("正在连接米联 Hook…", false);
        Intent intent = new Intent(Protocol.ACTION).setComponent(new ComponentName(Protocol.TARGET, Protocol.SERVICE));
        try {
            // The visible caller passes camera capability to the bound MiLink process.
            bound = bindService(intent, connection, Context.BIND_AUTO_CREATE | Context.BIND_INCLUDE_CAPABILITIES);
            if (!bound) showStatus("无法绑定米联服务", true);
        } catch (RuntimeException error) { showStatus(error.toString(), true); }
    }

    private boolean receive(Message message) {
        Bundle data = message.getData();
        switch (message.what) {
            case Protocol.READY:
                connected = true;
                appVolumeSupported = data.getBoolean("appVolume");
                volumeRegistrationFailed = false;
                nativeMode = data.getBoolean("nativePro");
                dpAttached = data.getBoolean("attached");
                if (nativeMode || !dpAttached) desired = started = false;
                updateControl();
                showStatus(nativeMode ? "原厂 Pro 正在使用 DP，退出后可开始预览"
                        : dpAttached ? data.getString("text") : "请先接入 DP 线", false);
                if (pendingIsland) startFromIsland();
                else if (desired) publishSurface();
                break;
            case Protocol.STATUS:
                showStatus(data.getString("text"), data.getBoolean("error"));
                break;
            case Protocol.SIGNAL:
                int width = data.getInt("width"), height = data.getInt("height"), code = data.getInt("signal");
                boolean valid = (code == 0 || code == 3) && width > 0 && height > 0;
                controls.setSignal(valid ? "输入：" + width + " × " + height + " @ " + data.getInt("fps") + " Hz"
                        : "输入参数：等待有效 DP 信号");
                if (!valid) preview.setForeground(new ColorDrawable(Color.BLACK));
                if (valid) preferences.edit().putInt("width", width).putInt("height", height).apply();
                if ((code == 0 || code == 3) && width > 0 && height > 0
                        && (width != inputWidth || height != inputHeight)) {
                    inputWidth = width; inputHeight = height;
                    setPictureInPictureParams(pipParams());
                    video.getHolder().setFixedSize(width, height);
                    fitVideo();
                }
                break;
            case Protocol.FRAMES:
                if (!desired) break;
                registerAppVolume();
                preview.setForeground(null);
                showStatus("正在接收 DP 视频", false);
                break;
            case Protocol.AUDIO:
                break;
            case Protocol.NATIVE_MODE:
                nativeMode = data.getBoolean("active");
                desired = started = false;
                updateControl();
                preview.setForeground(new ColorDrawable(Color.BLACK));
                showStatus(nativeMode ? "已让出视频和音频给原厂 Pro"
                        : "原厂 Pro 已退出，点击开始预览", false);
                break;
            case Protocol.CONNECTION:
                dpAttached = data.getBoolean("attached");
                if (!dpAttached || !data.getBoolean("active")) {
                    desired = started = false;
                    preview.setForeground(new ColorDrawable(Color.BLACK));
                }
                if (!dpAttached) {
                    pendingIsland = false;
                    controls.setSignal("输入参数：等待 DP 信号");
                    showStatus("请先接入 DP 线", false);
                } else if (!desired && !nativeMode) showStatus("DP 线已接入，点击开始预览", false);
                updateControl();
                startFromIsland();
                break;
            default: break;
        }
        return true;
    }

    private void publishSurface() {
        if (!desired || !connected || !dpAttached || nativeMode) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        if (video.getVisibility() != View.VISIBLE) { video.setVisibility(View.VISIBLE); return; }
        SurfaceHolder holder = video.getHolder();
        if (!holder.getSurface().isValid()) return;
        Bundle data = new Bundle();
        data.putParcelable("surface", holder.getSurface());
        data.putInt("width", holder.getSurfaceFrame().width());
        data.putInt("height", holder.getSurfaceFrame().height());
        data.putInt("generation", generation);
        send(started ? Protocol.SURFACE : Protocol.START, data);
        started = true;
    }

    private void send(int what, Bundle data) {
        if (server == null) return;
        Message message = Message.obtain(); message.what = what; message.replyTo = reply;
        if (data != null) message.setData(data);
        try { server.send(message); }
        catch (RemoteException error) {
            connected = desired = started = false;
            updateControl();
            showStatus("连接已断开，点重连", true);
        }
    }

    @Override public void surfaceCreated(SurfaceHolder holder) { generation++; }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { publishSurface(); }
    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        releaseVolumeRegistration();
        send(Protocol.STOP, null);
        started = false;
    }
    @Override protected void onStart() {
        super.onStart();
        activityStarted = true;
        releaseUpdates.check();
        updatePowerSampling();
    }
    @Override protected void onResume() { super.onResume(); publishSurface(); }
    @Override public void onMultiWindowModeChanged(boolean multiWindow, Configuration configuration) {
        super.onMultiWindowModeChanged(multiWindow, configuration);
        boolean entering = multiWindow && !smallWindowMode;
        smallWindowMode = multiWindow;
        if (multiWindow) {
            if (entering) setWindowFullscreen(true);
        } else if (windowFullscreen) setWindowFullscreen(true);
    }
    @Override protected void onStop() {
        super.onStop();
        activityStarted = false;
        releaseUpdates.cancel();
        updatePowerSampling();
        releaseVolumeRegistration();
        if (!isInPictureInPictureMode()) { send(Protocol.STOP, null); started = false; }
    }
    @Override protected void onDestroy() { disconnect(); super.onDestroy(); }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code == 10 && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
            // Rebinding ensures AMS reevaluates the newly granted camera capability.
            connect();
        } else if (code == 10) showStatus("需要相机权限将视频采集能力传给米联", true);
    }
    @Override public void onPictureInPictureModeChanged(boolean pip, Configuration configuration) {
        super.onPictureInPictureModeChanged(pip, configuration);
        controls.setVisibility(pip || windowFullscreen ? View.GONE : View.VISIBLE);
        updatePowerSampling();
        updatePreviewFrame();
        if (pip || windowFullscreen) root.setPadding(0, 0, 0, 0);
        root.requestApplyInsets();
    }
    private void setWindowFullscreen(boolean fullscreen) {
        windowFullscreen = fullscreen;
        controls.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        updatePowerSampling();
        updatePreviewFrame();
        WindowInsetsController insets = getWindow().getInsetsController();
        if (insets != null) {
            boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            insets.setSystemBarsAppearance(fullscreen || dark ? 0 : light, light);
        }
        if (insets != null && !isInMultiWindowMode()) {
            insets.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            if (fullscreen) insets.hide(WindowInsets.Type.systemBars());
            else insets.show(WindowInsets.Type.systemBars());
        }
        if (fullscreen) root.setPadding(0, 0, 0, 0);
        root.requestApplyInsets();
    }
    private void updatePowerSampling() {
        boolean sample = activityStarted && !windowFullscreen && !isInPictureInPictureMode();
        if (sample == powerSampling) return;
        powerSampling = sample;
        if (sample) powerMonitor.start();
        else powerMonitor.stop();
    }
    private void updatePreviewFrame() {
        boolean fullscreen = windowFullscreen || isInPictureInPictureMode();
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) previewArea.getLayoutParams();
        params.height = fullscreen ? 0 : LinearLayout.LayoutParams.WRAP_CONTENT;
        params.weight = fullscreen ? 1 : 0;
        previewArea.setLayoutParams(params);
        int margin = fullscreen ? 0 : dp(20);
        previewArea.setPadding(margin, 0, margin, margin);
        previewArea.requestLayout();
        preview.invalidateOutline();
        video.invalidateOutline();
    }
    private void disconnect() {
        releaseVolumeRegistration();
        send(Protocol.BYE, null);
        if (bound) { unbindService(connection); bound = false; }
        server = null;
    }
    private void fitVideo() {
        // Match the video area even when the system adds window decorations.
        int width = preview.getWidth(), height = preview.getHeight();
        if (width <= 0 || height <= 0) return;
        double scale = Math.min((double) width / inputWidth, (double) height / inputHeight);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) video.getLayoutParams();
        int fitWidth = (int) Math.round(inputWidth * scale), fitHeight = (int) Math.round(inputHeight * scale);
        if (windowFullscreen && isInMultiWindowMode() && width - fitWidth <= 1 && height - fitHeight <= 1) {
            fitWidth = width; fitHeight = height;
        }
        // Do not loop if the system clamps or rejects the same requested size.
        if (params.width == fitWidth && params.height == fitHeight) return;
        params.width = fitWidth; params.height = fitHeight;
        video.setLayoutParams(params);
    }
    private Rational pipRatio() {
        double ratio = (double) inputWidth / inputHeight;
        if (ratio < 1d / 2.39d) return new Rational(100, 239);
        if (ratio > 2.39d) return new Rational(239, 100);
        return new Rational(inputWidth, inputHeight);
    }
    private PictureInPictureParams pipParams() {
        return new PictureInPictureParams.Builder().setAspectRatio(pipRatio())
                .setSeamlessResizeEnabled(true).build();
    }
    private void showStatus(String text, boolean error) {
        controls.setStatus(text, error);
    }
    private int dp(int size) { return (int) (size * getResources().getDisplayMetrics().density); }
}
