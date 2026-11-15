package com.fixmylife.collection;

import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Self-update from GitHub releases.
 *
 * CI tags each build v<versionCode> and attaches fml-collection.apk. On start
 * (and from Create → Check for updates) this reads the latest release; if its
 * number is higher than the installed versionCode it offers to install it.
 * The APK streams straight into a PackageInstaller session, so no storage
 * permission is needed. Android asks once to allow "install unknown apps"
 * for this app; after the first self-update, later ones can install without
 * the system confirmation on Android 12+.
 */
class Updater {
    private final MainActivity act;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private String pendingApk;
    private boolean busy;

    Updater(MainActivity act) {
        this.act = act;
    }

    void check(boolean manual) {
        if (busy) return;
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(
                    "https://api.github.com/repos/" + BuildConfig.UPDATE_REPO + "/releases/latest").openConnection();
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "FMLCollectionApp/" + BuildConfig.VERSION_NAME);
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                int status = c.getResponseCode();
                if (status != 200) {
                    if (manual) ui.post(() -> act.toast("Couldn't check for updates (" + status + ")"));
                    return;
                }
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line).append('\n');
                }
                JSONObject json = new JSONObject(sb.toString());
                String tag = json.optString("tag_name", "");
                int code = 0;
                try {
                    code = Integer.parseInt(tag.replaceAll("[^0-9]", ""));
                } catch (NumberFormatException ignored) { }
                String apk = null;
                JSONArray assets = json.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        if (a.optString("name").endsWith(".apk")) apk = a.optString("browser_download_url");
                    }
                }
                String name = json.optString("name", tag);
                String notes = json.optString("body", "").trim();
                if (notes.length() > 600) notes = notes.substring(0, 600) + "…";
                final int latest = code;
                final String apkUrl = apk;
                final String message = name + (notes.isEmpty() ? "" : "\n\n" + notes);
                ui.post(() -> {
                    if (latest > BuildConfig.VERSION_CODE && apkUrl != null) offer(message, apkUrl);
                    else if (manual) act.toast("You're on the latest version (" + BuildConfig.VERSION_NAME + ")");
                });
            } catch (Exception e) {
                if (manual) ui.post(() -> act.toast("Couldn't check for updates: " + e.getMessage()));
            }
        }).start();
    }

    private void offer(String message, String apk) {
        if (act.isFinishing()) return;
        new AlertDialog.Builder(act, MainActivity.DIALOG_THEME)
            .setTitle("Update available")
            .setMessage(message)
            .setPositiveButton("Update", (d, w) -> install(apk))
            .setNegativeButton("Later", null)
            .show();
    }

    /** Back from the "install unknown apps" setting: carry on with the update. */
    void onResume() {
        if (pendingApk != null && act.getPackageManager().canRequestPackageInstalls()) {
            String apk = pendingApk;
            pendingApk = null;
            install(apk);
        }
    }

    private void install(String apk) {
        if (!act.getPackageManager().canRequestPackageInstalls()) {
            pendingApk = apk;
            new AlertDialog.Builder(act, MainActivity.DIALOG_THEME)
                .setTitle("Allow updates")
                .setMessage("To update itself, FML Collection needs permission to install apps. Turn on \"Allow from this source\", then come back.")
                .setPositiveButton("Open settings", (d, w) -> act.startActivity(new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.getPackageName()))))
                .setNegativeButton("Cancel", (d, w) -> pendingApk = null)
                .show();
            return;
        }

        busy = true;
        ProgressBar bar = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setIndeterminate(true);
        LinearLayout box = new LinearLayout(act);
        int pad = Math.round(20 * act.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new AlertDialog.Builder(act, MainActivity.DIALOG_THEME)
            .setTitle("Downloading update…")
            .setView(box)
            .setCancelable(false)
            .show();

        new Thread(() -> {
            PackageInstaller installer = act.getPackageManager().getPackageInstaller();
            int sessionId = -1;
            try {
                PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(act.getPackageName());
                if (Build.VERSION.SDK_INT >= 31) {
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                }
                sessionId = installer.createSession(params);
                try (PackageInstaller.Session session = installer.openSession(sessionId)) {
                    HttpURLConnection c = (HttpURLConnection) new URL(apk).openConnection();
                    c.setInstanceFollowRedirects(true);
                    c.setRequestProperty("User-Agent", "FMLCollectionApp/" + BuildConfig.VERSION_NAME);
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(30000);
                    long total = c.getContentLengthLong();
                    if (total > 0) ui.post(() -> bar.setIndeterminate(false));
                    try (InputStream in = c.getInputStream();
                         OutputStream out = session.openWrite("update.apk", 0, total > 0 ? total : -1)) {
                        byte[] buf = new byte[64 * 1024];
                        long done = 0;
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            done += n;
                            if (total > 0) {
                                final int pct = (int) (done * 100 / total);
                                ui.post(() -> bar.setProgress(pct));
                            }
                        }
                        session.fsync(out);
                    }
                    Intent done = new Intent(act, InstallReceiver.class);
                    PendingIntent pi = PendingIntent.getBroadcast(act, sessionId, done,
                        PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                    session.commit(pi.getIntentSender());
                }
                ui.post(() -> {
                    dialog.dismiss();
                    busy = false;
                    act.toast("Installing update — the app restarts when it's done");
                });
            } catch (Exception e) {
                if (sessionId != -1) {
                    try { installer.abandonSession(sessionId); } catch (Exception ignored) { }
                }
                ui.post(() -> {
                    dialog.dismiss();
                    busy = false;
                    act.toast("Update failed: " + e.getMessage());
                });
            }
        }).start();
    }
}
