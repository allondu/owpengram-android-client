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
import android.os.SystemClock;

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
    private final Object networkLock = new Object();
    private volatile boolean stopping;
    private volatile boolean networkAvailable = true;
    private volatile long networkGeneration;
    private Network activeNetwork;
    private boolean hasSeenNetwork;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        registerNetworkCallback();
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
            workers.remove(key, Thread.currentThread());
            return;
        }

        File resolverConfig = new File(getCacheDir(), "cloudflared-resolv-" + port + ".conf");
        int consecutiveFailures = 0;
        long observedNetworkGeneration = networkGeneration;
        while (!stopping) {
            if (!awaitNetwork()) {
                break;
            }
            long processStartedAt = SystemClock.elapsedRealtime();
            Process process = null;
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

                process = builder.start();
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
                    if (observedNetworkGeneration != networkGeneration) {
                        observedNetworkGeneration = networkGeneration;
                        consecutiveFailures = 0;
                        continue;
                    }
                    consecutiveFailures = SystemClock.elapsedRealtime() - processStartedAt >= 60000
                            ? 0
                            : Math.min(consecutiveFailures + 1, 5);
                    Thread.sleep(retryDelayMs(consecutiveFailures));
                }
            } catch (InterruptedException e) {
                if (stopping) {
                    Thread.currentThread().interrupt();
                    break;
                }
                observedNetworkGeneration = networkGeneration;
                consecutiveFailures = 0;
            } catch (Exception e) {
                FileLog.e("Cloudflared failed for " + hostname, e);
                if (!stopping) {
                    try {
                        if (observedNetworkGeneration != networkGeneration) {
                            observedNetworkGeneration = networkGeneration;
                            consecutiveFailures = 0;
                            continue;
                        }
                        consecutiveFailures = SystemClock.elapsedRealtime() - processStartedAt >= 60000
                                ? 0
                                : Math.min(consecutiveFailures + 1, 5);
                        Thread.sleep(retryDelayMs(consecutiveFailures));
                    } catch (InterruptedException interrupted) {
                        if (stopping) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        observedNetworkGeneration = networkGeneration;
                        consecutiveFailures = 0;
                    }
                }
            } finally {
                if (process != null) {
                    processes.remove(key, process);
                    if (process.isAlive()) {
                        process.destroy();
                    }
                }
            }
        }
        workers.remove(key, Thread.currentThread());
    }

    private static long retryDelayMs(int consecutiveFailures) {
        return Math.min(3000L << Math.max(0, consecutiveFailures - 1), 60000L);
    }

    private boolean awaitNetwork() {
        synchronized (networkLock) {
            while (!stopping && !networkAvailable) {
                try {
                    networkLock.wait();
                } catch (InterruptedException e) {
                    if (stopping) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
            return !stopping;
        }
    }

    private void registerNetworkCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return;
        }
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) {
            FileLog.e("Unable to monitor network changes for Cloudflared");
            return;
        }
        activeNetwork = connectivityManager.getActiveNetwork();
        networkAvailable = activeNetwork != null;
        hasSeenNetwork = activeNetwork != null;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                boolean changed;
                synchronized (this) {
                    changed = hasSeenNetwork && (activeNetwork == null || !activeNetwork.equals(network));
                    activeNetwork = network;
                    hasSeenNetwork = true;
                }
                synchronized (networkLock) {
                    networkAvailable = true;
                    networkLock.notifyAll();
                }
                if (changed) {
                    restartTunnelsForNetworkChange();
                }
            }

            @Override
            public void onLost(Network network) {
                boolean changed;
                synchronized (this) {
                    changed = network.equals(activeNetwork);
                    if (changed) {
                        activeNetwork = null;
                    }
                }
                if (changed) {
                    synchronized (networkLock) {
                        networkAvailable = false;
                    }
                    restartTunnelsForNetworkChange();
                }
            }
        };
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback);
        } catch (RuntimeException e) {
            FileLog.e("Unable to register Cloudflared network callback", e);
            networkCallback = null;
            networkAvailable = true;
        }
    }

    private void restartTunnelsForNetworkChange() {
        networkGeneration++;
        for (Process process : processes.values()) {
            process.destroy();
        }
        for (Thread worker : workers.values()) {
            worker.interrupt();
        }
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
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (IllegalArgumentException e) {
                FileLog.e("Unable to unregister Cloudflared network callback", e);
            }
            networkCallback = null;
        }
        for (Process process : processes.values()) {
            process.destroy();
        }
        for (Thread worker : workers.values()) {
            worker.interrupt();
        }
        processes.clear();
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
