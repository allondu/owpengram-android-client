package org.telegram.owpengram;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
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
        Utilities.cacheClearQueue.postRunnable(() -> {
            // Close our own writers first, otherwise open file descriptors keep
            // the deleted files' blocks allocated until the process dies. This
            // runs off the UI thread because closing waits for queued writes.
            FileLog.closeLogFiles();
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

    /**
     * Returns the stack trace of the last uncaught exception, or null when the
     * app has not crashed since the file was last cleared. BuildVars writes the
     * same content to internal and external app storage; internal is checked
     * first because Android/data is not browsable on newer releases.
     */
    public static String readLastCrash() {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return null;
        }
        for (File dir : new File[]{context.getFilesDir(), context.getExternalFilesDir(null)}) {
            if (dir == null) {
                continue;
            }
            File file = new File(dir, "crash-last.txt");
            if (!file.isFile()) {
                continue;
            }
            try (FileInputStream input = new FileInputStream(file)) {
                int max = (int) Math.min(file.length(), 256 * 1024);
                byte[] data = new byte[max];
                int length = 0;
                while (length < max) {
                    int read = input.read(data, length, max - length);
                    if (read <= 0) {
                        break;
                    }
                    length += read;
                }
                return new String(data, 0, length, StandardCharsets.UTF_8);
            } catch (Exception e) {
                // Try the next location.
            }
        }
        return null;
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
