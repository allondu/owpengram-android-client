package org.telegram.utils.proxy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class CloudflaredTunnelService extends Service {
    static final String ACTION_START = "org.telegram.messenger.action.CLOUDFLARED_START";
    static final String ACTION_STOP = "org.telegram.messenger.action.CLOUDFLARED_STOP";
    static final String EXTRA_HOSTNAME = "hostname";
    static final String EXTRA_PORT = "port";

    private static final String CHANNEL_ID = "cloudflared_tunnel";
    private static final int NOTIFICATION_ID = 7391;

    private final Map<String, Process> processes = new ConcurrentHashMap<>();
    private final Map<String, Thread> workers = new ConcurrentHashMap<>();
    private volatile boolean stopping;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopAll();
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, createNotification());
        if (intent != null && ACTION_START.equals(intent.getAction())) {
            String hostname = intent.getStringExtra(EXTRA_HOSTNAME);
            int port = intent.getIntExtra(EXTRA_PORT, 0);
            if (CloudflaredTunnelManager.isValidHostname(hostname) && port > 0) {
                startTunnel(hostname, port);
            } else {
                FileLog.e("Ignoring invalid Cloudflared tunnel service request");
            }
        } else if (intent == null) {
            for (Map.Entry<String, Integer> tunnel : CloudflaredTunnelManager.getStoredTunnels(this).entrySet()) {
                startTunnel(tunnel.getKey(), tunnel.getValue());
            }
        }
        return START_STICKY;
    }

    private void startTunnel(String hostname, int port) {
        String key = hostname.toLowerCase(java.util.Locale.US);
        if (workers.containsKey(key)) {
            return;
        }
        stopping = false;
        Thread worker = new Thread(() -> runTunnel(key, hostname, port), "Cloudflared-" + port);
        Thread existing = workers.putIfAbsent(key, worker);
        if (existing == null) {
            worker.start();
        }
    }

    private void runTunnel(String key, String hostname, int port) {
        File executable = new File(getApplicationInfo().nativeLibraryDir, "libcloudflared.so");
        if (!executable.isFile()) {
            FileLog.e("Bundled Cloudflared ARM64 executable is missing");
            workers.remove(key);
            return;
        }

        File resolverConfig = new File(getCacheDir(), "cloudflared-resolv-" + port + ".conf");
        while (!stopping) {
            try {
                writeResolverConfig(resolverConfig);
                ProcessBuilder builder = new ProcessBuilder(
                        executable.getAbsolutePath(),
                        "access", "tcp",
                        "--hostname", hostname,
                        "--url", "127.0.0.1:" + port);
                Map<String, String> environment = builder.environment();
                environment.put("GODEBUG", "netdns=go");
                environment.put("HOME", getFilesDir().getAbsolutePath());
                environment.put("PATH", "/system/bin:/system/xbin");
                builder.redirectInput(resolverConfig);
                builder.redirectErrorStream(true);

                Process process = builder.start();
                processes.put(key, process);
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while (!stopping && (line = reader.readLine()) != null) {
                        FileLog.d("Cloudflared: " + line);
                    }
                }
                int exitCode = process.waitFor();
                processes.remove(key, process);
                if (!stopping) {
                    FileLog.e("Cloudflared exited for " + hostname + " with status " + exitCode + "; retrying");
                    Thread.sleep(3000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                FileLog.e("Cloudflared failed for " + hostname, e);
                if (!stopping) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        workers.remove(key);
    }

    private void writeResolverConfig(File destination) throws Exception {
        StringBuilder contents = new StringBuilder();
        ConnectivityManager manager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        Network network = manager != null ? manager.getActiveNetwork() : null;
        LinkProperties properties = manager != null && network != null ? manager.getLinkProperties(network) : null;
        if (properties != null) {
            for (InetAddress address : properties.getDnsServers()) {
                contents.append("nameserver ").append(address.getHostAddress()).append('\n');
            }
        }
        if (contents.length() == 0) {
            contents.append("nameserver 1.1.1.1\n");
            contents.append("nameserver 8.8.8.8\n");
        }
        try (FileOutputStream output = new FileOutputStream(destination, false)) {
            output.write(contents.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            output.getFD().sync();
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
                .setContentText("Connecting through Cloudflare; Access sign-in may be required")
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

    private void stopAll() {
        stopping = true;
        for (Process process : processes.values()) {
            process.destroy();
        }
        for (Thread worker : workers.values()) {
            worker.interrupt();
        }
        processes.clear();
        workers.clear();
    }

    @Override
    public void onDestroy() {
        stopAll();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
