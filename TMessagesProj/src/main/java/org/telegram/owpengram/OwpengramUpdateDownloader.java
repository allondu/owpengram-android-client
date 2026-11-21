package org.telegram.owpengram;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;

import androidx.core.content.FileProvider;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Downloads the APK of an OwpenGram update inside the app instead of handing
 * the browser a download link. The file is written to app-private external
 * storage, checked to be a newer build of this package, and then passed to the
 * system package installer.
 */
public final class OwpengramUpdateDownloader {

    public interface Listener {
        void onProgress(int percent);

        void onCompleted(File apk);

        void onError(String message);
    }

    private static final Object LOCK = new Object();
    private static boolean downloading;
    private static int progress;

    private OwpengramUpdateDownloader() {
    }

    public static boolean isDownloading() {
        synchronized (LOCK) {
            return downloading;
        }
    }

    public static int getProgress() {
        synchronized (LOCK) {
            return progress;
        }
    }

    /** Returns the APK already downloaded for this release, if it is still on disk. */
    public static File getDownloadedFile(TLRPC.TL_help_appUpdate update) {
        if (update == null || ApplicationLoader.applicationContext == null) {
            return null;
        }
        File file = new File(updateDirectory(ApplicationLoader.applicationContext), fileName(update.version));
        return file.exists() ? file : null;
    }

    public static void download(Context context, TLRPC.TL_help_appUpdate update, Listener listener) {
        if (context == null || update == null || TextUtils.isEmpty(update.url)) {
            if (listener != null) {
                listener.onError("no download link");
            }
            return;
        }
        synchronized (LOCK) {
            if (downloading) {
                if (listener != null) {
                    listener.onError("already downloading");
                }
                return;
            }
            downloading = true;
            progress = 0;
        }
        final Context appContext = context.getApplicationContext();
        final String url = update.url;
        final String version = update.version;
        Utilities.globalQueue.postRunnable(() -> {
            File result = null;
            String failure = null;
            try {
                result = downloadApk(appContext, url, version, listener);
            } catch (Exception e) {
                FileLog.e(e);
                failure = e.getMessage();
                if (TextUtils.isEmpty(failure)) {
                    failure = e.getClass().getSimpleName();
                }
            } finally {
                synchronized (LOCK) {
                    downloading = false;
                }
            }
            final File downloaded = result;
            final String message = failure;
            AndroidUtilities.runOnUIThread(() -> {
                if (listener == null) {
                    return;
                }
                if (downloaded != null) {
                    listener.onCompleted(downloaded);
                } else {
                    listener.onError(TextUtils.isEmpty(message) ? "download failed" : message);
                }
            });
        });
    }

    private static File downloadApk(Context context, String url, String version, Listener listener) throws Exception {
        final File directory = updateDirectory(context);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("could not create update folder");
        }
        final File output = new File(directory, fileName(version));
        final File part = new File(directory, output.getName() + ".part");
        if (part.exists() && !part.delete()) {
            throw new IOException("could not replace old partial download");
        }

        final HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(30_000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "OwpenGram-Android/" + BuildVars.getOwpenGramVersion());
            connection.setRequestProperty("Accept", "application/octet-stream");
            final int response = connection.getResponseCode();
            if (response != HttpURLConnection.HTTP_OK) {
                throw new IOException("GitHub answered " + response);
            }
            final int total = connection.getContentLength();
            try (InputStream input = connection.getInputStream();
                 FileOutputStream outputStream = new FileOutputStream(part)) {
                final byte[] buffer = new byte[16 * 1024];
                long loaded = 0;
                int lastPercent = -1;
                int read;
                while ((read = input.read(buffer)) > 0) {
                    outputStream.write(buffer, 0, read);
                    loaded += read;
                    if (total > 0) {
                        final int percent = (int) (loaded * 100 / total);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            reportProgress(listener, percent);
                        }
                    }
                }
                outputStream.getFD().sync();
            }
        } finally {
            connection.disconnect();
        }

        if (part.length() < 1024) {
            part.delete();
            throw new IOException("downloaded file is too small");
        }

        final PackageManager packageManager = context.getPackageManager();
        final PackageInfo archive = packageManager.getPackageArchiveInfo(part.getAbsolutePath(), 0);
        if (archive == null || !context.getPackageName().equals(archive.packageName)) {
            part.delete();
            throw new IOException("downloaded file is not an OwpenGram APK");
        }
        final PackageInfo current = packageManager.getPackageInfo(context.getPackageName(), 0);
        if (archive.versionCode <= current.versionCode) {
            part.delete();
            throw new IOException("downloaded APK is not newer");
        }
        if (output.exists() && !output.delete()) {
            part.delete();
            throw new IOException("could not replace old update");
        }
        if (!part.renameTo(output)) {
            part.delete();
            throw new IOException("could not finish update download");
        }
        reportProgress(listener, 100);
        return output;
    }

    private static void reportProgress(Listener listener, int percent) {
        synchronized (LOCK) {
            if (progress == percent) {
                return;
            }
            progress = percent;
        }
        if (listener != null) {
            AndroidUtilities.runOnUIThread(() -> listener.onProgress(percent));
        }
    }

    /**
     * Hands a downloaded APK to the package installer. Returns true when the
     * installer was opened (or the "install unknown apps" dialog was shown).
     */
    public static boolean install(Context context, File apk) {
        if (context == null || apk == null || !apk.exists()) {
            return false;
        }
        if (ApplicationLoader.applicationLoaderInstance != null
                && !ApplicationLoader.applicationLoaderInstance.checkApkInstallPermissions(context)) {
            // The restricted-settings dialog is already on screen; asking again
            // would just stack another one on top of it.
            return true;
        }
        try {
            final Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            final Uri uri = FileProvider.getUriForFile(context, ApplicationLoader.getApplicationId() + ".provider", apk);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    private static File updateDirectory(Context context) {
        File directory = context.getExternalFilesDir("updates");
        if (directory == null) {
            directory = new File(context.getFilesDir(), "updates");
        }
        return directory;
    }

    private static String fileName(String version) {
        String safe = TextUtils.isEmpty(version) ? "update" : version.replaceAll("[^A-Za-z0-9._-]", "_");
        return "owpengram-" + safe + ".apk";
    }
}
