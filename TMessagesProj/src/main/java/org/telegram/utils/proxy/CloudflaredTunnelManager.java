package org.telegram.utils.proxy;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.File;
import java.net.IDN;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.HashMap;
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
    private static volatile boolean lifecycleHooked;

    private CloudflaredTunnelManager() {}

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
        if (!isValidHostname(hostname)) {
            FileLog.e("Cloudflared tunnel hostname is invalid");
            return 0;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            FileLog.e("Cloudflared tunnels require Android 8.0 or later");
            return 0;
        }
        if (!isArm64Supported()) {
            FileLog.e("Cloudflared tunnels are currently available on ARM64 devices only");
            return 0;
        }

        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            FileLog.e("Cloudflared tunnel requested before the application context was ready");
            return 0;
        }
        ensureLifecycleHook(context);

        File executable = new File(context.getApplicationInfo().nativeLibraryDir, "libcloudflared.so");
        if (!executable.isFile()) {
            FileLog.e("Cloudflared tunnel support is not packaged in this build");
            return 0;
        }

        String key = hostname.toLowerCase(Locale.US);
        synchronized (localPorts) {
            Integer existingPort = localPorts.get(key);
            if (existingPort == null) {
                existingPort = getStoredPort(context, key);
            }
            int localPort = existingPort != null ? existingPort : findLocalPort();
            if (localPort <= 0) {
                return 0;
            }
            savePort(context, key, localPort);
            localPorts.put(key, localPort);
            Intent intent = new Intent(context, CloudflaredTunnelService.class)
                    .setAction(CloudflaredTunnelService.ACTION_START)
                    .putExtra(CloudflaredTunnelService.EXTRA_HOSTNAME, hostname)
                    .putExtra(CloudflaredTunnelService.EXTRA_PORT, localPort);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent);
                } else {
                    context.startService(intent);
                }
                pending.remove(key);
            } catch (RuntimeException e) {
                // Android 12+ refuses to start a foreground service while the app
                // has no visible activity, e.g. from Application.onCreate. Keep the
                // port and retry once an activity is visible: returning 0 here used
                // to leave the account configured for no server at all.
                pending.add(key);
                FileLog.e("Cloudflared foreground service is not allowed to start yet", e);
            }
            return localPort;
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
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    ((Application) application).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                        private int started;

                        @Override
                        public void onActivityStarted(Activity activity) {
                            started++;
                            if (started == 1) {
                                resumePending();
                            }
                        }

                        @Override
                        public void onActivityStopped(Activity activity) {
                            if (started > 0) {
                                started--;
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
                }
            });
        }
    }

    private static void resumePending() {
        if (pending.isEmpty()) {
            return;
        }
        for (String hostname : new ArrayList<>(pending)) {
            if (start(hostname) > 0) {
                pending.remove(hostname);
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
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 250);
                return true;
            } catch (Exception ignored) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
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
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            FileLog.e("Unable to allocate a local Cloudflared port", e);
            return 0;
        }
    }
}
