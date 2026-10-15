package com.maple.dpinviewer;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

final class ReleaseUpdateChecker {
    private static final String API = "https://api.github.com/repos/582856/DpinWindow/releases/latest";
    private final Activity activity;
    private final MiuixUpdatePrompt prompt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile int generation;

    ReleaseUpdateChecker(Activity activity, MiuixUpdatePrompt prompt) {
        this.activity = activity;
        this.prompt = prompt;
    }

    void check() {
        cancel();
        int request = generation;
        new Thread(() -> {
            HttpURLConnection http = null;
            try {
                http = (HttpURLConnection) new URL(API).openConnection();
                http.setConnectTimeout(8000);
                http.setReadTimeout(8000);
                http.setRequestProperty("Accept", "application/vnd.github+json");
                http.setRequestProperty("User-Agent", "DpinWindow");
                if (request != generation) return;
                if (http.getResponseCode() != 200) return;
                JSONObject release;
                try (java.io.InputStream input = http.getInputStream()) {
                    byte[] body = input.readNBytes(256 * 1024 + 1);
                    if (body.length > 256 * 1024) return;
                    release = new JSONObject(new String(body, StandardCharsets.UTF_8));
                }
                String tag = release.optString("tag_name");
                String installed = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
                if (release.optBoolean("draft") || release.optBoolean("prerelease") || !newer(tag, installed)) return;
                String notes = release.optString("body", "").trim();
                String url = release.optString("html_url");
                if (!url.startsWith("https://github.com/582856/DpinWindow/releases/tag/")) return;
                handler.post(() -> {
                    if (request != generation || activity.isFinishing() || activity.isDestroyed()) return;
                    prompt.showUpdate(tag, notes.isEmpty() ? "此版本未提供更新说明。" : notes, () -> {
                        try { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
                        catch (android.content.ActivityNotFoundException error) { Log.w("DpinViewer", "No release URL handler", error); }
                    });
                });
            } catch (Exception error) {
                // Update checks must not block preview when GitHub is unreachable.
                if (request == generation) Log.d("DpinViewer", "Release check unavailable: " + error.getClass().getSimpleName());
            } finally {
                if (http != null) http.disconnect();
            }
        }, "DpinReleaseCheck").start();
    }

    void cancel() {
        generation++;
        prompt.dismiss();
    }

    static boolean newer(String candidate, String installed) {
        if (candidate == null || installed == null) return false;
        String latest = candidate.replaceFirst("^[vV]", "");
        String current = installed.replaceFirst("^[vV]", "");
        if (!latest.matches("[0-9]+(\\.[0-9]+)*") || !current.matches("[0-9]+(\\.[0-9]+)*")) return false;
        String[] a = latest.split("\\."), b = current.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            java.math.BigInteger x = new java.math.BigInteger(i < a.length ? a[i] : "0");
            java.math.BigInteger y = new java.math.BigInteger(i < b.length ? b[i] : "0");
            int order = x.compareTo(y);
            if (order != 0) return order > 0;
        }
        return false;
    }
}
