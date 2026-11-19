package org.telegram.owpengram;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Controls OwpenGram's diagnostic file logging. Logs are written next to the
 * media cache and can grow without an upper limit, so the user can turn the
 * writes off and delete what is already stored.
 */
public final class OwpengramLogs {

    private static final String PREFS_NAME = "systemConfig";
    private static final String KEY_LOGS_ENABLED = "logsEnabled";

    private OwpengramLogs() {
    }

    public static boolean isEnabled() {
        return BuildVars.LOGS_ENABLED;
    }

    public static void setEnabled(boolean enabled) {
        BuildVars.LOGS_ENABLED = enabled;
        getPrefs().edit().putBoolean(KEY_LOGS_ENABLED, enabled).commit();
        if (enabled) {
            FileLog.ensureInitied();
            FileLog.d("file logging enabled");
        } else {
            FileLog.closeLogFiles();
        }
    }

    /**
     * Removes all stored log files. Sizes are computed before deleting, and
     * the callback runs on the UI thread with the number of bytes freed.
     */
    public static void deleteLogs(Utilities.Callback<Long> callback) {
        // Close our own writers first, otherwise open file descriptors keep
        // the deleted files' blocks allocated until the process dies.
        FileLog.closeLogFiles();
        Utilities.cacheClearQueue.postRunnable(() -> {
            long size = 0;
            for (File dir : logDirs()) {
                size += directorySize(dir);
                deleteContents(dir);
            }
            final long deleted = size;
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() -> callback.run(deleted));
            }
        });
    }

    private static SharedPreferences getPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static Set<File> logDirs() {
        Set<File> dirs = new LinkedHashSet<>();
        File appLogs = AndroidUtilities.getLogsDir();
        if (appLogs != null) {
            dirs.add(appLogs);
        }
        Context context = ApplicationLoader.applicationContext;
        if (context != null) {
            // Native call logging uses its own directory and is not counted by
            // the app's storage screen.
            dirs.add(new File(context.getCacheDir(), "voip_logs"));
        }
        return dirs;
    }

    private static long directorySize(File file) {
        if (file == null || !file.exists()) {
            return 0;
        }
        if (file.isFile()) {
            return file.length();
        }
        File[] children = file.listFiles();
        if (children == null) {
            return 0;
        }
        long size = 0;
        for (File child : children) {
            size += directorySize(child);
        }
        return size;
    }

    private static void deleteContents(File file) {
        if (file == null) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteContents(child);
                }
            }
        } else {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}
