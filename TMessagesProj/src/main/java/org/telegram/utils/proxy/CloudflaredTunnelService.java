package org.telegram.utils.proxy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the app's Cloudflared listeners alive while the app process is alive.
 *
 * <p>There is no cloudflared child process any more. Each tunnel is a
 * {@link CloudflaredTunnelServer} in this process, and each accepted Telegram
 * connection is carried by its own WebSocket. The foreground service therefore
 * only has to keep the process from being killed and recreate a listener if its
 * accept thread ever dies.</p>
 */
public final class CloudflaredTunnelService extends Service {
    static final String ACTION_START = "org.telegram.messenger.action.CLOUDFLARED_START";
    static final String ACTION_STOP = "org.telegram.messenger.action.CLOUDFLARED_STOP";
    static final String EXTRA_HOSTNAME = "hostname";
    static final String EXTRA_PORT = "port";

    private static final String CHANNEL_ID = "cloudflared_tunnel";
    private static final int NOTIFICATION_ID = 7391;
    private static final long WATCHDOG_INTERVAL_MS = 30_000;

    private static final class TunnelSpec {
        final String hostname;
        final int port;

        TunnelSpec(String hostname, int port) {
            this.hostname = hostname;
            this.port = port;
        }

        boolean matches(String hostname, int port) {
            return this.port == port && this.hostname.equalsIgnoreCase(hostname);
        }
    }

    private final Map<String, CloudflaredTunnelServer> servers = new ConcurrentHashMap<>();
    private final Map<String, TunnelSpec> tunnels = new ConcurrentHashMap<>();
    private final Handler watchdogHandler = new Handler(Looper.getMainLooper());
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (stopping) {
                return;
            }
            restartMissingServers();
            watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS);
        }
    };
    private volatile boolean stopping;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            createNotificationChannel();
            startWatchdog();
        } catch (RuntimeException e) {
            FileLog.e("Unable to prepare the Cloudflared tunnel service", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            return handleStartCommand(intent, startId);
        } catch (Throwable e) {
            FileLog.e("Cloudflared tunnel service failed to start", e);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
    }

    private int handleStartCommand(Intent intent, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopAll();
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                // Declare the manifest type explicitly. On Android 14+ a foreground
                // service without a type can be rejected with
                // MissingForegroundServiceTypeException.
                startForeground(NOTIFICATION_ID, createNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, createNotification());
            }
        } catch (RuntimeException e) {
            // Android 12+ refuses a foreground service started from the background,
            // which is exactly what a sticky restart looks like. Letting this escape
            // killed the process, and the next sticky restart killed it again -- the
            // crash loop seen after a call or while adding an account.
            FileLog.e("Cloudflared tunnel cannot run in the foreground right now", e);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        stopping = false;
        startWatchdog();
        if (intent != null && ACTION_START.equals(intent.getAction())) {
            String hostname = intent.getStringExtra(EXTRA_HOSTNAME);
            int port = intent.getIntExtra(EXTRA_PORT, 0);
            startTunnel(hostname, port);
        } else if (intent == null) {
            for (Map.Entry<String, Integer> tunnel : CloudflaredTunnelManager.getStoredTunnels(this).entrySet()) {
                startTunnel(tunnel.getKey(), tunnel.getValue());
            }
        }
        // Deliberately not sticky: a tunnel that crashes the process would otherwise
        // be restarted by the system straight into the same crash. The app restarts
        // its tunnels from the first visible activity instead.
        return START_NOT_STICKY;
    }

    private synchronized void startTunnel(String hostname, int port) {
        if (hostname == null || !CloudflaredTunnelManager.isValidHostname(hostname) || port <= 0) {
            FileLog.e("Ignoring invalid Cloudflared tunnel request");
            return;
        }
        String key = hostname.toLowerCase(Locale.US);
        TunnelSpec existingSpec = tunnels.get(key);
        CloudflaredTunnelServer existingServer = servers.get(key);
        if (existingSpec != null && existingSpec.matches(hostname, port)
                && existingServer != null && existingServer.isRunning()) {
            return;
        }
        if (existingServer != null) {
            existingServer.stop();
            servers.remove(key, existingServer);
        }
        tunnels.put(key, new TunnelSpec(hostname, port));
        CloudflaredTunnelServer server = new CloudflaredTunnelServer(hostname, port, null, null);
        if (server.start()) {
            servers.put(key, server);
        } else {
            server.stop();
        }
    }

    private void startWatchdog() {
        watchdogHandler.removeCallbacks(watchdog);
        watchdogHandler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS);
    }

    /** Recreates a listener whose accept thread died without the service stopping. */
    private void restartMissingServers() {
        for (TunnelSpec spec : tunnels.values()) {
            CloudflaredTunnelServer server = servers.get(spec.hostname.toLowerCase(Locale.US));
            if (server == null || !server.isRunning()) {
                FileLog.e("Cloudflared: listener for " + spec.hostname + " is not running; restarting");
                startTunnel(spec.hostname, spec.port);
            }
        }
    }

    private Notification createNotification() {
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent contentIntent = launchIntent == null ? null : PendingIntent.getActivity(
                this, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.notification)
                .setContentTitle("Cloudflared tunnel")
                .setContentText("Tunnel active")
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE);
        if (contentIntent != null) {
            builder.setContentIntent(contentIntent);
        }
        return builder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Cloudflared tunnel", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private synchronized void stopAll() {
        stopping = true;
        watchdogHandler.removeCallbacks(watchdog);
        for (CloudflaredTunnelServer server : servers.values()) {
            server.stop();
        }
        servers.clear();
        tunnels.clear();
    }

    @Override
    public void onDestroy() {
        try {
            stopAll();
        } catch (RuntimeException e) {
            FileLog.e("Unable to stop Cloudflared tunnels cleanly", e);
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
