package org.telegram.utils.proxy;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.text.TextUtils;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.File;
import java.net.IDN;
import java.net.ServerSocket;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

public final class CloudflaredTunnelManager {
    private static final ConcurrentHashMap<String, Integer> localPorts = new ConcurrentHashMap<>();

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
        File executable = new File(context.getApplicationInfo().nativeLibraryDir, "libcloudflared.so");
        if (!executable.isFile()) {
            FileLog.e("Cloudflared tunnel support is not packaged in this build");
            return 0;
        }

        String key = hostname.toLowerCase(Locale.US);
        Integer existingPort = localPorts.get(key);
        if (existingPort != null) {
            return existingPort;
        }

        synchronized (localPorts) {
            existingPort = localPorts.get(key);
            if (existingPort != null) {
                return existingPort;
            }
            int localPort = findLocalPort();
            if (localPort == 0) {
                return 0;
            }
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
                localPorts.put(key, localPort);
                return localPort;
            } catch (RuntimeException e) {
                FileLog.e("Failed to start Cloudflared foreground service", e);
                return 0;
            }
        }
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
