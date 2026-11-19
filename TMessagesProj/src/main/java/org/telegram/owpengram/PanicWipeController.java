package org.telegram.owpengram;

import android.content.Context;
import android.os.SystemClock;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesStorage;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.tl.TL_account;

import java.io.File;
import java.io.FileOutputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Best-effort local panic wipe.
 *
 * <p>Deleting a file only removes its name from the filesystem; it does not
 * guarantee that the old blocks are gone. Before deleting the app data we
 * therefore write random, encrypted-looking files over the free space of every
 * app-owned directory, call fsync for each file, and then delete them. This is
 * a best-effort overwrite, not a cryptographic proof of physical erasure on
 * flash storage.</p>
 */
public final class PanicWipeController {

    private static final int FLOOD_FILE_SIZE = 1024 * 1024;
    private static final int FLOOD_FILES_PER_DIRECTORY = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PanicWipeController() {
    }

    /**
     * @return true when a server-side deleteAccount request was sent.
     */
    public static boolean wipe(Context context, int account, boolean deleteServerAccount) {
        boolean serverRequested = false;

        if (deleteServerAccount) {
            try {
                TL_account.deleteAccount request = new TL_account.deleteAccount();
                request.reason = "Panic All requested from the app";
                ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> {
                    // The response is intentionally ignored: local cleanup must
                    // continue even if Telegram asks for 2-Step Verification.
                }, ConnectionsManager.RequestFlagInvokeAfter);
                serverRequested = true;
                // Give the request a short chance to leave the process before
                // the network configuration is deleted.
                SystemClock.sleep(900);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }

        try {
            MessagesStorage.getInstance(account).clearLocalDatabase();
        } catch (Throwable e) {
            FileLog.e(e);
        }
        // clearLocalDatabase() runs on MessagesStorage's queue. Let it finish
        // before the database and media files are removed.
        SystemClock.sleep(1200);

        List<File> roots = new ArrayList<>();
        roots.add(context.getCacheDir());
        roots.add(context.getNoBackupFilesDir());
        roots.add(context.getExternalCacheDir());
        roots.add(context.getExternalFilesDir(null));
        try {
            roots.add(ApplicationLoader.getFilesDirFixed());
        } catch (Throwable e) {
            FileLog.e(e);
        }

        for (File root : roots) {
            deleteContents(root);
        }

        // Remove the remaining app-private state: databases, shared preferences,
        // webview data, caches and files. Do not touch lib/ or code_cache; those
        // are application code, not user data.
        String dataDir = context.getApplicationInfo() != null ? context.getApplicationInfo().dataDir : null;
        if (dataDir != null) {
            for (String name : new String[]{"databases", "shared_prefs", "files", "cache", "no_backup", "app_webview", "app_textures"}) {
                deleteContents(new File(dataDir, name));
            }
        }

        // Now that the old files are unlinked, overwrite the newly freed space
        // with fresh random data and flush it. The flood files are removed at
        // the end of floodStorage().
        floodStorage(context);
        for (File root : roots) {
            deleteContents(root);
        }

        // The app process may still hold a few descriptors. The caller logs out
        // and offers the system uninstall screen immediately afterwards.
        return serverRequested;
    }

    private static void floodStorage(Context context) {
        List<File> dirs = new ArrayList<>();
        dirs.add(context.getCacheDir());
        dirs.add(context.getNoBackupFilesDir());
        dirs.add(context.getExternalCacheDir());
        dirs.add(context.getExternalFilesDir(null));
        try {
            dirs.add(ApplicationLoader.getFilesDirFixed());
        } catch (Throwable e) {
            FileLog.e(e);
        }

        byte[] buffer = new byte[256 * 1024];
        for (File dir : dirs) {
            if (dir == null) {
                continue;
            }
            if (!dir.exists() && !dir.mkdirs()) {
                continue;
            }
            for (int i = 0; i < FLOOD_FILES_PER_DIRECTORY; i++) {
                File file = new File(dir, "panic_" + UUID.randomUUID() + ".bin");
                FileOutputStream out = null;
                try {
                    out = new FileOutputStream(file);
                    int written = 0;
                    while (written < FLOOD_FILE_SIZE) {
                        RANDOM.nextBytes(buffer);
                        int chunk = Math.min(buffer.length, FLOOD_FILE_SIZE - written);
                        out.write(buffer, 0, chunk);
                        written += chunk;
                    }
                    out.flush();
                    out.getFD().sync();
                } catch (Throwable e) {
                    // A full disk is acceptable here: the point is to fill the
                    // free space as far as possible, then delete the files.
                    FileLog.e(e);
                } finally {
                    if (out != null) {
                        try {
                            out.close();
                        } catch (Throwable ignore) {
                        }
                    }
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
            }
        }
    }

    private static void deleteContents(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            deleteRecursive(child);
        }
    }

    private static void deleteRecursive(File file) {
        if (file == null) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
