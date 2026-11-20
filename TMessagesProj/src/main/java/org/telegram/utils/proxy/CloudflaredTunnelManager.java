package org.telegram.utils.proxy;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.owpengram.OwpengramServer;
import org.telegram.owpengram.OwpengramServers;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.Components.ForegroundDetector;

import java.io.File;
import java.net.IDN;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class CloudflaredTunnelManager {
    private static final String PREFERENCES_NAME = "cloudflared_tunnels";
    private static final String PORT_PREFIX = "port:";
    private static final ConcurrentHashMap<String, Integer> localPorts = new ConcurrentHashMap<>();
    /** Hostnames whose tunnel could not be started yet; retried once the app is visible. */
    private static final Set<String> pending = ConcurrentHashMap.newKeySet();
    /** Hostnames whose accounts still have to be woken once a recovered tunnel is up. */
    private static final Set<String> waking = ConcurrentHashMap.newKeySet();
    /** Every hostname this process has been asked to tunnel, including login flows. */
    private static final Set<String> requested = ConcurrentHashMap.newKeySet();
    private static volatile long lastResumeAt;
    private static volatile boolean lifecycleHooked;
    /** Activities currently started; the tunnel service may only start while this is above zero. */
    private static volatile int visibleActivities;

    private CloudflaredTunnelManager() {}

    private static final class StartResult {
        final int port;
        final boolean started;

        StartResult(int port, boolean started) {
            this.port = port;
            this.started = started;
        }
    }

    public static boolean isValidHostname(String hostname) {
        if (TextUtils.isEmpty(hostname) || hostname.length() > 253
                || hostname.contains("://") || hostname.contains("/") || hostname.contains(":")) {
            return false;
        }
        try {
            String ascii = IDN.toASCII(hostname, IDN.USE_STD3_ASCII_RULES);
            if (ascii.length() > 253 || ascii.startsWith(".") || ascii.endsWith(".")) {
                return false;
            }
            String[] labels = ascii.split("\\.");
            if (labels.length < 2) {
                return false;
            }
            for (String label : labels) {
                if (label.isEmpty() || label.length() > 63 || label.startsWith("-") || label.endsWith("-")) {
                    return false;
                }
            }
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static int start(String hostname) {
        try {
            return startTunnel(hostname).port;
        } catch (Throwable t) {
            // This is called from ApplicationLoader while restoring accounts: a tunnel
            // problem must never take the whole launch down with it.
            FileLog.e("Unable to start the Cloudflared tunnel for " + hostname, t);
            return 0;
        }
    }

    private static StartResult startTunnel(String hostname) {
        if (!isValidHostname(hostname)) {
            FileLog.e("Cloudflared tunnel hostname is invalid");
            return new StartResult(0, false);
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            FileLog.e("Cloudflared tunnels require Android 8.0 or later");
            return new StartResult(0, false);
        }
        if (!isArm64Supported()) {
            FileLog.e("Cloudflared tunnels are currently available on ARM64 devices only");
            return new StartResult(0, false);
        }

        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            FileLog.e("Cloudflared tunnel requested before the application context was ready");
            return new StartResult(0, false);
        }
        ensureLifecycleHook(context);

        File executable = new File(context.getApplicationInfo().nativeLibraryDir, "libcloudflared.so");
        if (!executable.isFile()) {
            FileLog.e("Cloudflared tunnel support is not packaged in this build");
            return new StartResult(0, false);
        }

        String key = hostname.toLowerCase(Locale.US);
        requested.add(key);
        synchronized (localPorts) {
            Integer existingPort = localPorts.get(key);
            int localPort;
            if (existingPort != null) {
                // Already chosen in this process: keep it even while the service is
                // starting, otherwise a second request would move the account to a
                // different port than the tunnel that is coming up.
                localPort = existingPort;
            } else {
                Integer storedPort = getStoredPort(context, key);
                // An old cloudflared child may still own the stored port after the
                // app process was killed. Reusing it would make every retry fail
                // with "address already in use", so only reuse a free port.
                localPort = storedPort != null && isPortAvailable(storedPort)
                        ? storedPort : findLocalPort();
            }
            if (localPort <= 0) {
                return new StartResult(0, false);
            }
            savePort(context, key, localPort);
            localPorts.put(key, localPort);
            if (!isAppVisible()) {
                // Android kills the process when a service started with
                // startForegroundService() has not called startForeground() within
                // five seconds. Restoring a stored tunnel from Application.onCreate
                // does exactly that: the service cannot run until the (slow) cold
                // start finishes, so the watchdog fires and the app dies on every
                // launch. Keep the port and let the first visible activity start it.
                pending.add(key);
                FileLog.d("Cloudflared tunnel for " + hostname + " is waiting for a visible activity");
                return new StartResult(localPort, false);
            }
            boolean alreadyListening = isListenerOpen(localPort);
            Intent intent = new Intent(context, CloudflaredTunnelService.class)
                    .setAction(CloudflaredTunnelService.ACTION_START)
                    .putExtra(CloudflaredTunnelService.EXTRA_HOSTNAME, hostname)
                    .putExtra(CloudflaredTunnelService.EXTRA_PORT, localPort);
            boolean started = false;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent);
                } else {
                    context.startService(intent);
                }
                started = true;
            } catch (RuntimeException e) {
                // Android 12+ refuses to start a foreground service while the app
                // has no visible activity, e.g. from Application.onCreate. Keep the
                // port and retry once an activity is visible: returning 0 here used
                // to leave the account configured for no server at all.
                FileLog.e("Cloudflared foreground service is not allowed to start yet", e);
            }
            if (started) {
                pending.remove(key);
                if (!alreadyListening) {
                    // The account is configured for 127.0.0.1:port immediately, but
                    // cloudflared still has to bind it. Wake the account once the
                    // listener exists. Do NOT re-apply the stored server here:
                    // native_applyServerConfig suspends every DC connection on a
                    // single-server backend, so doing it on each tunnel blip shows
                    // up as a disconnect/reconnect and an "Updating" title.
                    scheduleWake(hostname, key, localPort);
                }
            } else {
                // Keep it queued for the next visible activity.
                pending.add(key);
            }
            return new StartResult(localPort, started);
        }
    }

    /**
     * Whether starting a foreground service is currently allowed. The activity
     * counter alone is not enough: the lifecycle hook can be registered after an
     * activity has already started (for example the first time the user selects
     * a Cloudflared server in Settings), so the counter never saw that activity.
     * ApplicationLoader.mainInterfacePaused is false while the main UI is resumed
     * and true during the cold-start window where starting the service would hit
     * the five-second foreground-service watchdog.
     */
    private static boolean isAppVisible() {
        if (visibleActivities > 0 || !ApplicationLoader.mainInterfacePaused) {
            return true;
        }
        if (!lifecycleHooked) {
            // Fallback for the rare case where the early hook could not be
            // installed. The detector tracks every activity, including bubbles.
            ForegroundDetector detector = ForegroundDetector.getInstance();
            return detector != null && detector.isForeground();
        }
        return false;
    }

    /**
     * Registers the foreground hook early, from Application.onCreate, so an
     * activity that is already running when the first tunnel is requested is
     * still counted. Without this, selecting the first Cloudflared server from
     * Settings could queue the tunnel forever: the hook was installed after the
     * activity had already started, so no onActivityStarted callback followed.
     */
    public static void initialize(Context context) {
        if (context != null) {
            ensureLifecycleHook(context);
        }
    }

    /** Tunnels that lost the foreground-service race start as soon as the app is visible again. */
    private static void ensureLifecycleHook(Context context) {
        if (lifecycleHooked) {
            return;
        }
        synchronized (CloudflaredTunnelManager.class) {
            if (lifecycleHooked) {
                return;
            }
            Context application = context.getApplicationContext();
            if (!(application instanceof Application)) {
                return;
            }
            lifecycleHooked = true;
            Runnable register = () -> {
                try {
                    ((Application) application).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                        @Override
                        public void onActivityStarted(Activity activity) {
                            visibleActivities++;
                            // Activity lifecycle callbacks run on the main thread: an
                            // exception here would take the whole app down, so a tunnel
                            // problem must stay contained.
                            try {
                                resumeTunnels();
                            } catch (Throwable t) {
                                FileLog.e("Unable to resume Cloudflared tunnels", t);
                            }
                        }

                        @Override
                        public void onActivityStopped(Activity activity) {
                            if (visibleActivities > 0) {
                                visibleActivities--;
                            }
                        }

                        @Override
                        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                        }

                        @Override
                        public void onActivityResumed(Activity activity) {
                        }

                        @Override
                        public void onActivityPaused(Activity activity) {
                        }

                        @Override
                        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
                        }

                        @Override
                        public void onActivityDestroyed(Activity activity) {
                        }
                    });
                } catch (RuntimeException e) {
                    FileLog.e("Unable to track app visibility for Cloudflared", e);
                    // Allow a later call to try again instead of disabling the retry path.
                    lifecycleHooked = false;
                }
            };
            // ApplicationLoader calls this while the main thread is still cold-starting.
            // Register right there (posting would queue behind that work and miss the
            // first onActivityStarted), and only post when called off the main thread.
            if (Looper.myLooper() == Looper.getMainLooper()) {
                register.run();
            } else {
                AndroidUtilities.runOnUIThread(register);
            }
        }
    }

    /**
     * Called whenever an activity becomes visible. Restarts every tunnel this
     * process asked for, not only hostnames that were waiting for a visible
     * activity: Android may destroy the tunnel service while the app is in the
     * background. Without this, the account stays pointed at a local port with
     * no listener until the whole app is force-stopped and relaunched.
     */
    private static void resumeTunnels() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastResumeAt < 1500) {
            return;
        }
        lastResumeAt = now;
        Set<String> hostnames = new LinkedHashSet<>(pending);
        hostnames.addAll(requested);
        for (String hostname : hostnames) {
            try {
                if (!startTunnel(hostname).started) {
                    // Still blocked (for example the app went background again); keep it queued.
                    pending.add(hostname);
                }
            } catch (Throwable t) {
                pending.add(hostname);
                FileLog.e("Unable to start Cloudflared tunnel for " + hostname, t);
            }
        }
    }

    /** Waits for the listener, then wakes every account that uses this host. */
    private static void scheduleWake(final String hostname, String key, final int port) {
        if (!waking.add(key)) {
            return;
        }
        Thread worker = new Thread(() -> {
            boolean ready = false;
            try {
                ready = awaitListener(port, 20000);
            } catch (Throwable t) {
                FileLog.e("Unable to wait for the Cloudflared listener for " + hostname, t);
            }
            if (!ready) {
                waking.remove(key);
                FileLog.e("Cloudflared listener for " + hostname + " did not become ready on port " + port);
                return;
            }
            try {
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        wakeAccounts(hostname);
                    } catch (Throwable t) {
                        FileLog.e("Unable to wake accounts for Cloudflared server " + hostname, t);
                    } finally {
                        waking.remove(key);
                    }
                });
            } catch (Throwable t) {
                waking.remove(key);
                FileLog.e("Unable to schedule Cloudflared wake-up for " + hostname, t);
            }
        }, "CloudflaredWake");
        worker.setDaemon(true);
        worker.start();
    }

    private static boolean awaitListener(int port, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (isListenerOpen(port)) {
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return isListenerOpen(port);
    }

    /**
     * Whether something is already listening on the tunnelled port. This uses
     * a bind instead of a TCP connect on purpose: connecting to cloudflared's
     * local proxy opens a real tunnelled connection (Cloudflare Access sees it
     * and may start an auth flow), while a bind only asks the kernel whether the
     * port is taken. The tunnel is the only expected owner of this port.
     */
    private static boolean isListenerOpen(int port) {
        if (port <= 0) {
            return false;
        }
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    private static boolean isPortAvailable(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void wakeAccounts(String hostname) {
        for (int accountNum = 0; accountNum < UserConfig.MAX_ACCOUNT_COUNT; accountNum++) {
            try {
                OwpengramServer server = OwpengramServers.getServerForAccount(accountNum);
                if (server != null && server.useCloudflared && server.host != null
                        && server.host.equalsIgnoreCase(hostname)
                        && UserConfig.getInstance(accountNum).isClientActivated()) {
                    // Resume the existing sessions instead of applying the server
                    // config again: resumeNetworkMaybe() only wakes connections that
                    // are waiting or backing off, it does not suspend them.
                    ConnectionsManager.getInstance(accountNum).resumeNetworkMaybe();
                }
            } catch (RuntimeException e) {
                FileLog.e("Unable to wake account " + accountNum + " for Cloudflared", e);
            }
        }
    }

    static Map<String, Integer> getStoredTunnels(Context context) {
        Map<String, Integer> tunnels = new HashMap<>();
        Map<String, ?> stored = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).getAll();
        for (Map.Entry<String, ?> entry : stored.entrySet()) {
            if (!entry.getKey().startsWith(PORT_PREFIX) || !(entry.getValue() instanceof Integer)) {
                continue;
            }
            int port = (Integer) entry.getValue();
            if (port > 0 && port <= 65535) {
                tunnels.put(entry.getKey().substring(PORT_PREFIX.length()), port);
            }
        }
        return tunnels;
    }

    private static Integer getStoredPort(Context context, String hostname) {
        int port = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getInt(PORT_PREFIX + hostname, 0);
        return port > 0 && port <= 65535 ? port : null;
    }

    private static void savePort(Context context, String hostname, int port) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit().putInt(PORT_PREFIX + hostname, port).apply();
    }

    public static boolean awaitLocalListener(String hostname, long timeoutMs) {
        int port = start(hostname);
        if (port == 0) {
            return false;
        }
        return awaitListener(port, timeoutMs);
    }

    public static int getLocalPort(String hostname) {
        if (TextUtils.isEmpty(hostname)) {
            return 0;
        }
        Integer port = localPorts.get(hostname.toLowerCase(Locale.US));
        return port == null ? 0 : port;
    }

    private static boolean isArm64Supported() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) {
                return true;
            }
        }
        return false;
    }

    private static int findLocalPort() {
        for (int attempt = 0; attempt < 5; attempt++) {
            try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                int port = socket.getLocalPort();
                if (!localPorts.containsValue(port)) {
                    return port;
                }
            } catch (Exception e) {
                FileLog.e("Unable to allocate a local Cloudflared port", e);
                return 0;
            }
        }
        FileLog.e("Unable to allocate a free local Cloudflared port");
        return 0;
    }
}
