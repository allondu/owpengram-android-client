package org.telegram.owpengram;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.LaunchActivity;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds out whether a newer OwpenGram release is published on GitHub, the same
 * source the desktop client's updater reads, and hands it to the stock update
 * dialog as an ordinary help.appUpdate.
 *
 * It asks GitHub rather than a server on purpose: help.getAppUpdate goes to
 * whichever server the active account lives on, and for a Telegram account that
 * is Telegram itself, which would offer its own app instead of ours.
 *
 * Releases are tagged O1, O2, ...; the running build knows its own number
 * (OWPENGRAM_BUILD, given to the build script for every release; 0 = a local
 * dev build, which never checks).
 */
public final class OwpengramUpdater {

    private static final long CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000;
    private static final long PROMPT_INTERVAL_MS = 24L * 60 * 60 * 1000;
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;
    private static final int MAX_NOTES_CHARS = 4000;
    private static final Pattern TAG = Pattern.compile("^[Oo](\\d+)$");

    private static final String PREFS = "owpengram_updater";
    private static final String KEY_BUILD = "build";
    private static final String KEY_LAST_CHECK = "last_check";
    private static final String KEY_PROMPTED_TAG = "prompted_tag";
    private static final String KEY_PROMPTED_AT = "prompted_at";
    private static final String KEY_NOTIFIED_TAG = "notified_tag";
    private static final String CHANNEL_ID = "owpengram_updates";
    private static final int NOTIFICATION_ID = 0x0F1E;

    public static final String EXTRA_OPEN_UPDATE = "owpengram_open_update";

    public interface Callback {
        /**
         * Runs on the UI thread. {@code update} is null when there is nothing to
         * offer; {@code failed} tells "could not ask" apart from "up to date".
         */
        void onResult(TLRPC.TL_help_appUpdate update, boolean failed);
    }

    private static boolean checking;
    private static boolean periodicScheduled;
    private static boolean firstCheckInProcess = true;
    private static volatile TLRPC.TL_help_appUpdate pendingUpdate;

    private OwpengramUpdater() {
    }

    /**
     * The repo the APKs are published to: the release workflow in this repo
     * creates an O<N> release per build, so this is the repo to ask.
     */
    private static final String LATEST_RELEASE_URL = "https://api.github.com/repos/allondu/owpengram-android-client/releases/latest";

    /** Only the direct-download build updates itself this way; store builds leave it to the store. */
    public static boolean enabled() {
        return ApplicationLoader.isStandaloneBuild() && BuildVars.OWPENGRAM_BUILD > 0;
    }

    public static void check(boolean force, Callback callback) {
        final SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getInt(KEY_BUILD, 0) != BuildVars.OWPENGRAM_BUILD) {
            // A freshly installed or just updated build asks GitHub once right away;
            // the previous build's check time must not hide the release it was upgraded to.
            prefs.edit().putInt(KEY_BUILD, BuildVars.OWPENGRAM_BUILD).remove(KEY_LAST_CHECK).apply();
        }
        final long now = System.currentTimeMillis();
        final boolean skipThrottle;
        synchronized (OwpengramUpdater.class) {
            skipThrottle = firstCheckInProcess;
            firstCheckInProcess = false;
        }
        if (!force && !skipThrottle && Math.abs(now - prefs.getLong(KEY_LAST_CHECK, 0)) < CHECK_INTERVAL_MS) {
            final TLRPC.TL_help_appUpdate pending = pendingUpdate;
            if (pending != null && !ApplicationLoader.mainInterfacePaused) {
                markPrompted(prefs, pending.version);
            }
            AndroidUtilities.runOnUIThread(() -> callback.onResult(pending, false));
            return;
        }
        synchronized (OwpengramUpdater.class) {
            if (checking) {
                AndroidUtilities.runOnUIThread(() -> callback.onResult(null, true));
                return;
            }
            checking = true;
        }
        Utilities.globalQueue.postRunnable(() -> {
            TLRPC.TL_help_appUpdate update = null;
            boolean failed = false;
            try {
                update = fetchNewerRelease();
                prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
            } catch (Exception e) {
                // an update check is advisory: no network, GitHub rate limit or a
                // malformed answer must never reach the user as an error dialog
                FileLog.e(e);
                failed = true;
            } finally {
                synchronized (OwpengramUpdater.class) {
                    checking = false;
                }
            }
            final boolean prompted = update != null && !force && alreadyPrompted(prefs, update.version);
            if (update != null && !prompted) {
                if (ApplicationLoader.mainInterfacePaused) {
                    // The app is in the background, so a dialog would be invisible.
                    // Keep the update in memory, notify once if allowed and leave
                    // the prompt unmarked: the next time the app comes to the
                    // foreground the normal update dialog appears immediately.
                    pendingUpdate = update;
                    notifyUpdate(prefs, update);
                } else {
                    pendingUpdate = null;
                    markPrompted(prefs, update.version);
                }
            }
            if (prompted) {
                update = null;
            }
            if (update == null && !failed) {
                pendingUpdate = null;
            }
            final TLRPC.TL_help_appUpdate result = update;
            final boolean failedResult = failed;
            AndroidUtilities.runOnUIThread(() -> callback.onResult(result, failedResult));
        });
    }

    /** The same release is not offered again within a day unless the user asked to check. */
    private static boolean alreadyPrompted(SharedPreferences prefs, String tag) {
        return tag.equals(prefs.getString(KEY_PROMPTED_TAG, null))
                && Math.abs(System.currentTimeMillis() - prefs.getLong(KEY_PROMPTED_AT, 0)) < PROMPT_INTERVAL_MS;
    }

    private static void markPrompted(SharedPreferences prefs, String tag) {
        prefs.edit()
                .putString(KEY_PROMPTED_TAG, tag)
                .putLong(KEY_PROMPTED_AT, System.currentTimeMillis())
                .apply();
    }

    /** Keeps checking while the app process is alive, including its background service. */
    public static void schedulePeriodicCheck() {
        if (!enabled() || periodicScheduled) {
            return;
        }
        periodicScheduled = true;
        Utilities.globalQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                check(false, (update, failed) -> {
                });
                Utilities.globalQueue.postRunnable(this, CHECK_INTERVAL_MS);
            }
        }, CHECK_INTERVAL_MS);
    }

    private static void notifyUpdate(SharedPreferences prefs, TLRPC.TL_help_appUpdate update) {
        if (update.version == null || update.version.equals(prefs.getString(KEY_NOTIFIED_TAG, null))) {
            return;
        }
        final Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        final NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || Build.VERSION.SDK_INT >= 24 && !manager.areNotificationsEnabled()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID,
                    LocaleController.getString(R.string.AppUpdateNotificationChannel),
                    NotificationManager.IMPORTANCE_DEFAULT
            ));
        }
        final Intent intent = new Intent(context, LaunchActivity.class);
        intent.putExtra(EXTRA_OPEN_UPDATE, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        final PendingIntent pendingIntent = PendingIntent.getActivity(context, NOTIFICATION_ID, intent, pendingFlags);
        final Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);
        builder.setSmallIcon(R.drawable.notification)
                .setContentTitle(LocaleController.getString(R.string.AppUpdateNotificationTitle))
                .setContentText(LocaleController.formatString("AppUpdateNotificationText", R.string.AppUpdateNotificationText, update.version))
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis());
        if (Build.VERSION.SDK_INT < 26) {
            builder.setPriority(Notification.PRIORITY_DEFAULT);
        }
        try {
            manager.notify(NOTIFICATION_ID, builder.build());
            prefs.edit().putString(KEY_NOTIFIED_TAG, update.version).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static TLRPC.TL_help_appUpdate fetchNewerRelease() throws Exception {
        final HttpURLConnection connection = (HttpURLConnection) new URL(LATEST_RELEASE_URL).openConnection();
        try {
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "OwpenGram-Android/" + BuildVars.OWPENGRAM_BUILD);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("GitHub answered " + connection.getResponseCode());
            }
            return parseRelease(readBounded(connection.getInputStream()));
        } finally {
            connection.disconnect();
        }
    }

    private static String readBounded(InputStream in) throws Exception {
        try (InputStream stream = in) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                if (out.size() > MAX_RESPONSE_BYTES) {
                    throw new IllegalStateException("release answer too large");
                }
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    /** Turns GitHub's release JSON into the dialog's payload, or null when there is nothing newer to offer. */
    static TLRPC.TL_help_appUpdate parseRelease(String json) throws Exception {
        final JSONObject release = new JSONObject(json);
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) {
            return null;
        }
        final String tag = release.optString("tag_name", "");
        final Matcher matcher = TAG.matcher(tag);
        if (!matcher.matches()) {
            return null;
        }
        final int build = Integer.parseInt(matcher.group(1));
        if (build <= BuildVars.OWPENGRAM_BUILD) {
            return null;
        }

        String url = apkUrl(release.optJSONArray("assets"));
        if (url == null || url.isEmpty()) {
            // no APK attached yet: send the person to the release page instead
            url = release.optString("html_url", null);
        }
        if (url == null || !url.startsWith("https://")) {
            return null;
        }

        final TLRPC.TL_help_appUpdate update = new TLRPC.TL_help_appUpdate();
        update.id = build;
        // shown as "Version O8" in the dialog: built from the release number, not copied from the tag text
        update.version = "O" + build;
        update.text = notes(release.optString("body", ""));
        update.url = url;
        update.flags |= 4; // url
        return update;
    }

    /**
     * Releases carry one APK per CPU architecture plus a universal APK, so hand
     * out the one this device can run, in the order the device itself prefers
     * them. Falls back to the universal APK, then to the first attached APK, when
     * no asset name carries a known ABI.
     */
    private static String apkUrl(JSONArray assets) {
        if (assets == null) {
            return null;
        }
        String firstApk = null;
        String universalApk = null;
        String bestApk = null;
        int bestRank = Integer.MAX_VALUE;
        for (int i = 0; i < assets.length(); i++) {
            final JSONObject asset = assets.optJSONObject(i);
            if (asset == null) {
                continue;
            }
            final String name = asset.optString("name", "").toLowerCase(Locale.US);
            final String url = asset.optString("browser_download_url", "");
            if (!name.endsWith(".apk") || !url.startsWith("https://")) {
                continue;
            }
            if (firstApk == null) {
                firstApk = url;
            }
            if (universalApk == null && name.contains("universal")) {
                universalApk = url;
            }
            for (int rank = 0; rank < Build.SUPPORTED_ABIS.length && rank < bestRank; rank++) {
                if (name.contains(Build.SUPPORTED_ABIS[rank].toLowerCase(Locale.US))) {
                    bestRank = rank;
                    bestApk = url;
                    break;
                }
            }
        }
        return bestApk != null ? bestApk : (universalApk != null ? universalApk : firstApk);
    }

    private static String notes(String body) {
        String text = body == null ? "" : body.replace("\r\n", "\n").trim();
        // the dialog shows plain text: drop the markdown markers that would show up as noise
        text = text.replaceAll("(?m)^#{1,6}\\s*", "").replace("**", "").replace("__", "");
        if (text.length() > MAX_NOTES_CHARS) {
            text = text.substring(0, MAX_NOTES_CHARS).trim() + "…";
        }
        return text;
    }
}
