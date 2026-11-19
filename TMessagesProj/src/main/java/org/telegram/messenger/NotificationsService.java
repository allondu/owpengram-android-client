/*
 * This is the source code of Telegram for Android v. 1.3.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import org.telegram.ui.LaunchActivity;

public class NotificationsService extends Service {

    public static final int NOTIFICATION_ID = 36;

    private volatile boolean initFailed;

    @Override
    public void onCreate() {
        super.onCreate();
        // Android gives a process started with startForegroundService() only about five
        // seconds to call startForeground(). This service is also started on boot from
        // AppStartReceiver, in a fresh process, and postInitApplication() below can take
        // longer than that. The system then killed the process before the notification
        // appeared, and START_STICKY restarted it straight into the same timeout -- the
        // crash loop seen with the keep-alive service forced on. Enter the foreground
        // with a minimal, non-localized notification first; onStartCommand replaces it
        // once the app is initialized.
        try {
            showMinimalForegroundNotification();
        } catch (Throwable e) {
            FileLog.e("Unable to enter the foreground before app init", e);
        }
        try {
            ApplicationLoader.postInitApplication();
        } catch (Throwable e) {
            FileLog.e("Unable to initialize the app from the push service", e);
            initFailed = true;
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (initFailed) {
            // Initialization failed in onCreate(). Returning START_STICKY here would let
            // the system restart the service into the same failure forever.
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ApplicationLoader.isPushServiceEnabled()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            showForegroundNotification();
        } catch (Throwable e) {
            // LocaleController is not ready if postInitApplication() failed; the minimal
            // notification still keeps the process in the foreground.
            FileLog.e("Unable to show the localized push notification", e);
            try {
                showMinimalForegroundNotification();
            } catch (Throwable e2) {
                FileLog.e("Unable to show the push notification", e2);
            }
        }
        return START_STICKY;
    }

    private void showMinimalForegroundNotification() {
        NotificationsController.checkPushServiceNotificationsChannel();
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NotificationsController.PUSH_SERVICE_NOTIFICATIONS_CHANNEL)
                .setSmallIcon(R.drawable.notification)
                .setContentTitle(SharedConfig.getAppDisplayName())
                .setContentText("Keeping the connection alive")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setShowWhen(false)
                .setSilent(true);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, builder.build());
            }
        } catch (Throwable e) {
            FileLog.e(e);
            startForeground(NOTIFICATION_ID, builder.build());
        }
    }

    private void showForegroundNotification() {
        NotificationsController.checkPushServiceNotificationsChannel();

        Intent openIntent = new Intent(this, LaunchActivity.class);
        openIntent.setAction(Intent.ACTION_MAIN);
        openIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NotificationsController.PUSH_SERVICE_NOTIFICATIONS_CHANNEL)
                .setSmallIcon(R.drawable.notification)
                .setContentTitle(LocaleController.getString(R.string.NotificationsService))
                .setContentText(LocaleController.getString(R.string.NotificationsServiceInfo))
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setShowWhen(false)
                .setSilent(true);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, builder.build());
            }
        } catch (Throwable e) {
            FileLog.e(e);
            try {
                startForeground(NOTIFICATION_ID, builder.build());
            } catch (Throwable e2) {
                FileLog.e(e2);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            stopForeground(true);
        } catch (Throwable ignore) {

        }
        if (ApplicationLoader.isPushServiceEnabled()) {
            Intent intent = new Intent("org.telegram.start");
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
        }
    }
}
