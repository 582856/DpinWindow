package com.maple.dpinviewer;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.json.JSONObject;

final class DpinIsland {
    private static final String TAG = "DpinViewerHook";
    private static final int PREVIEW_ID = 20046;
    private static final String OPEN = Protocol.APP + ".OPEN_PREVIEW";
    private final Context context;
    private final ClassLoader loader;
    private final DpinBridge bridge;
    private final NotificationManager notifications;
    private volatile boolean attached;

    DpinIsland(Context context, ClassLoader loader, DpinBridge bridge) {
        this.context = context;
        this.loader = loader;
        this.bridge = bridge;
        notifications = context.getSystemService(NotificationManager.class);
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context receiverContext, Intent intent) {
                if (!attached || bridge.nativePro) return;
                notifications.cancel(PREVIEW_ID);
                context.startActivity(previewIntent());
                Log.i(TAG, "Preview island clicked");
            }
        }, new IntentFilter(OPEN), Context.RECEIVER_EXPORTED);
    }

    void usb(int state) {
        if (state != 0 && state != 1) return;
        attached = state == 1;
        bridge.worker.post(() -> {
            if (!attached) { cancel(); return; }
            if (bridge.nativePro) return;
            try {
                Object osd = singleton(loader.loadClass("com.milink.dpin.ui.osd.OsdManager"));
                osd.getClass().getDeclaredMethod("k", Context.class).invoke(osd, context);
                Class<?> builder = loader.loadClass(bridge.updatedVersion() ? "pa.a" : "y7.a");
                Notification pro = (Notification) builder.getDeclaredMethod("a", Context.class)
                        .invoke(singleton(builder), context);
                Notification preview = pro.clone();
                JSONObject root = new JSONObject(preview.extras.getString("miui.focus.param"));
                JSONObject params = root.getJSONObject("param_v2");
                params.getJSONObject("param_island").getJSONObject("bigIslandArea")
                        .getJSONObject("imageTextInfoRight").getJSONObject("textInfo").put("title", "进入“屏幕扩展”");
                params.getJSONObject("iconTextInfo").put("title", "进入“屏幕扩展”").put("content", "在 Android 中预览 DP 视频");
                Intent click = new Intent(OPEN).setPackage(Protocol.TARGET);
                JSONObject action = params.getJSONArray("actions").getJSONObject(0);
                action.put("actionTitle", "打开").put("actionIntentType", 2)
                        .put("actionIntent", click.toUri(Intent.URI_INTENT_SCHEME));
                preview.extras.putString("miui.focus.param", root.toString());
                preview.extras.putCharSequence(Notification.EXTRA_TITLE, "进入“屏幕扩展”");
                preview.extras.putCharSequence(Notification.EXTRA_TEXT, "点击打开 DP-in窗口显示");
                preview.contentIntent = PendingIntent.getActivity(context, PREVIEW_ID, previewIntent(),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                preview = Notification.Builder.recoverBuilder(context, preview).setTimeoutAfter(300000).build();
                if (!attached || bridge.nativePro) return;
                notifications.notify(20045, pro);
                notifications.notify(PREVIEW_ID, preview);
                Log.i(TAG, "Published screen extension and Pro islands");
            } catch (Exception error) { Log.e(TAG, "DP island publication failed", error); }
        });
    }

    void cancel() {
        context.stopService(new Intent().setClassName(Protocol.TARGET,
                "com.milink.dpin.ui.osd.IslandNotificationService"));
        notifications.cancel(PREVIEW_ID);
        notifications.cancel(20045);
    }

    private Intent previewIntent() {
        return new Intent().setComponent(new ComponentName(Protocol.APP, Protocol.APP + ".MainActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("fromIsland", true);
    }

    private static Object singleton(Class<?> type) throws Exception {
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == type) {
                field.setAccessible(true);
                return field.get(null);
            }
        }
        throw new NoSuchFieldException(type.getName());
    }
}
