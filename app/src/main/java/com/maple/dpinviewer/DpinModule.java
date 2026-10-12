package com.maple.dpinviewer;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.os.Handler;
import android.os.Bundle;
import android.util.Log;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public final class DpinModule extends XposedModule {
    private static final String TAG = "DpinViewerHook";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private String process;

    @Override public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        process = param.getProcessName();
    }

    @Override public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!Protocol.TARGET.equals(param.getPackageName()) || !Protocol.TARGET.equals(process)) return;
        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            hook(attach).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                if (INSTALLED.compareAndSet(false, true)) {
                    Context context = (Context) chain.getArg(0);
                    install(context);
                }
                return result;
            });
            Log.i(TAG, "Application.attach hook installed");
        } catch (Throwable error) { Log.e(TAG, "attach hook failed", error); }
    }

    private void install(Context context) {
        try {
            // Resolve business classes only after attach, through the target app loader.
            ClassLoader loader = context.getClassLoader();
            DpinBridge bridge = new DpinBridge(context, loader);
            Class<?> display = loader.loadClass("com.milink.dpin.ui.display.DisplayModeActivity");
            hook(display.getDeclaredMethod("onCreate", Bundle.class)).intercept(chain -> {
                bridge.enterPro();
                return chain.proceed();
            });
            hook(display.getDeclaredMethod("onDestroy")).intercept(chain -> {
                Object result = chain.proceed();
                bridge.exitPro();
                return result;
            });
            Class<?> dpState = loader.loadClass(bridge.updatedVersion() ? "ba.a" : "k7.a");
            hook(dpState.getDeclaredMethod("n")).intercept(chain -> {
                bridge.enterPro();
                return chain.proceed();
            });
            hook(dpState.getDeclaredMethod("j", loader.loadClass(bridge.updatedVersion() ? "vq.r" : "fm.r")))
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        if (chain.getArg(0) != null) bridge.replayProSignal();
                        return result;
                    });
            Method bind = loader.loadClass(Protocol.SERVICE).getDeclaredMethod("onBind", Intent.class);
            hook(bind).intercept(chain -> {
                Intent intent = (Intent) chain.getArg(0);
                return intent != null && Protocol.ACTION.equals(intent.getAction())
                        ? bridge.messenger.getBinder() : chain.proceed();
            });

            Class<?> manager = loader.loadClass("com.milink.dpin.hardware.bridge.BridgeManager");
            // Repeated MIPI restarts can make the driver report corrupt input timings.
            for (Method method : manager.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if (types.length != 6 || method.getReturnType() != void.class) continue;
                boolean integers = true;
                for (Class<?> type : types) integers &= type == int.class;
                if (!integers) continue;
                hook(method).intercept(chain -> {
                    Object result = chain.proceed();
                    int[] values = new int[6];
                    for (int i = 0; i < values.length; i++) values[i] = (Integer) chain.getArg(i);
                    bridge.signal(values);
                    return result;
                });
            }

            Method usb = loader.loadClass("com.milink.dpin.hardware.usb.UsbBroadcastReceiver")
                    .getDeclaredMethod("onReceive", Context.class, Intent.class);
            hook(usb).intercept(chain -> {
                Intent intent = (Intent) chain.getArg(1);
                if (bridge.ownsDp && !bridge.nativePro && intent != null
                        && "miui.intent.action.ACTION_USB_DP_IN_STATE_CHANGED".equals(intent.getAction())) {
                    int state = intent.getIntExtra("miui.intent.extra.EXTRA_USB_DP_IN_STATE", -1);
                    // Keep native unplug deduplication in sync while preview consumes the event.
                    Method remember = chain.getThisObject().getClass().getDeclaredMethod("k", int.class);
                    remember.setAccessible(true);
                    remember.invoke(chain.getThisObject(), state);
                    bridge.usb(state);
                    bridge.island.usb(state);
                    return null;
                }
                Object result = chain.proceed();
                if (intent != null && "miui.intent.action.ACTION_USB_DP_IN_STATE_CHANGED".equals(intent.getAction())) {
                    int state = intent.getIntExtra("miui.intent.extra.EXTRA_USB_DP_IN_STATE", -1);
                    bridge.usb(state);
                    bridge.island.usb(state);
                }
                return result;
            });

            Method openCamera = CameraManager.class.getDeclaredMethod("openCamera", String.class,
                    CameraDevice.StateCallback.class, Handler.class);
            hook(openCamera).intercept(chain -> {
                if (bridge.openingCamera() && "5".equals(chain.getArg(0))) {
                    return chain.proceed(new Object[]{chain.getArg(0),
                            bridge.cameraCallback((CameraDevice.StateCallback) chain.getArg(1)), bridge.worker});
                }
                return chain.proceed();
            });

            // Native DP capture supplies no callback; add one to report received frames.
            Method repeating = Class.forName("android.hardware.camera2.impl.CameraCaptureSessionImpl")
                    .getDeclaredMethod("setRepeatingRequest", CaptureRequest.class,
                            CameraCaptureSession.CaptureCallback.class, Handler.class);
            hook(repeating).intercept(chain -> {
                CameraCaptureSession session = (CameraCaptureSession) chain.getThisObject();
                if (bridge.ownsCamera(session.getDevice()) && chain.getArg(1) == null) {
                    CameraCaptureSession.CaptureCallback callback = new CameraCaptureSession.CaptureCallback() {
                        @Override public void onCaptureCompleted(CameraCaptureSession session,
                                CaptureRequest request, TotalCaptureResult result) { bridge.frame(session); }
                    };
                    bridge.captureRequest(session, (CaptureRequest) chain.getArg(0), callback);
                    return 0;
                }
                return chain.proceed();
            });
            bridge.installDiagnostics(this, loader);
            Class<?> audioController = loader.loadClass(bridge.updatedVersion() ? "da.b" : "m7.b");
            AtomicBoolean sharedAudio = new AtomicBoolean();
            // The native state machine requires success, but preview must not interrupt other media.
            hook(audioController.getDeclaredMethod("e")).intercept(chain -> {
                if (bridge.ownsDp && !bridge.nativePro) {
                    sharedAudio.set(true);
                    Log.i(TAG, "preview audio: shared playback, no focus or call mode request");
                    return true;
                }
                return chain.proceed();
            });
            // A preview request acquired neither focus nor a mode owner, so it has nothing to restore.
            hook(audioController.getDeclaredMethod("b")).intercept(chain -> {
                if (sharedAudio.getAndSet(false)) return null;
                return chain.proceed();
            });
            Class<?> audio = loader.loadClass(bridge.updatedVersion() ? "da.c" : "m7.c");
            hook(audio.getDeclaredMethod("c")).intercept(chain -> {
                if (bridge.ownsDp && !bridge.nativePro && !bridge.active) return null;
                Object result = chain.proceed();
                bridge.audioStarted();
                return result;
            });
            Log.i(TAG, "injected package=" + Protocol.TARGET + " native DP bridge ready");
            Log.i("VectorToastModule", "injected package=" + Protocol.TARGET);
        } catch (Throwable error) { Log.e(TAG, "DP hook installation failed", error); }
    }
}
