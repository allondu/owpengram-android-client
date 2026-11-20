package org.telegram.utils.proxy;

import org.telegram.messenger.FileLog;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns the loopback listener for one Cloudflare Tunnel hostname.
 *
 * <p>The old implementation spawned the cloudflared binary and pointed the
 * Telegram client at the TCP listener it created. That made every tunnel
 * problem a process-lifecycle problem: a killed child, a stale port, a network
 * callback or a watchdog restart dropped every live session at once. This class
 * keeps the listener in the app process. Each accepted socket gets its own
 * {@link CloudflaredWebSocketConnection}, so one failed WebSocket only closes
 * that one Telegram connection; the listener and every other connection stay
 * up.</p>
 */
final class CloudflaredTunnelServer {
    private static final int MAX_CONNECTIONS = 32;
    private static final int ACCEPT_BACKLOG = 50;

    private final String hostname;
    private final int port;
    private final String accessClientId;
    private final String accessClientSecret;
    private final Set<CloudflaredWebSocketConnection> connections = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile ExecutorService connectionExecutor;
    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;

    CloudflaredTunnelServer(String hostname, int port, String accessClientId, String accessClientSecret) {
        this.hostname = hostname;
        this.port = port;
        this.accessClientId = accessClientId == null ? "" : accessClientId;
        this.accessClientSecret = accessClientSecret == null ? "" : accessClientSecret;
        this.connectionExecutor = createExecutor();
    }

    synchronized boolean start() {
        if (isRunning()) {
            return true;
        }
        closeServerSocket();
        if (connectionExecutor.isShutdown()) {
            connectionExecutor = createExecutor();
        }
        try {
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), ACCEPT_BACKLOG);
            serverSocket = socket;
            running.set(true);
            Thread thread = new Thread(this::acceptLoop, "CloudflaredAccept-" + port);
            thread.setDaemon(true);
            acceptThread = thread;
            thread.start();
            FileLog.d("Cloudflared: listening on 127.0.0.1:" + port + " for " + hostname);
            return true;
        } catch (Throwable t) {
            running.set(false);
            closeServerSocket();
            FileLog.e("Cloudflared: could not listen on 127.0.0.1:" + port + " for " + hostname, t);
            return false;
        }
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                ServerSocket socket = serverSocket;
                if (socket == null) {
                    break;
                }
                Socket accepted = socket.accept();
                if (connections.size() >= MAX_CONNECTIONS) {
                    FileLog.e("Cloudflared: too many local connections for " + hostname + "; rejecting one");
                    closeQuietly(accepted);
                    continue;
                }
                try {
                    accepted.setTcpNoDelay(true);
                    CloudflaredWebSocketConnection connection = new CloudflaredWebSocketConnection(
                            hostname, accepted, accessClientId, accessClientSecret, this::onConnectionClosed);
                    connections.add(connection);
                    try {
                        connectionExecutor.execute(connection);
                    } catch (RejectedExecutionException e) {
                        connections.remove(connection);
                        connection.close();
                    }
                } catch (Throwable t) {
                    closeQuietly(accepted);
                    FileLog.e("Cloudflared: could not prepare a local connection for " + hostname, t);
                }
            } catch (Throwable t) {
                if (!running.get()) {
                    break;
                }
                // A transient accept failure must not kill the listener or spin.
                FileLog.e("Cloudflared: accept failed for " + hostname, t);
                sleepQuietly(250);
            }
        }
    }

    private void onConnectionClosed(CloudflaredWebSocketConnection connection) {
        connections.remove(connection);
    }

    boolean isRunning() {
        ServerSocket socket = serverSocket;
        Thread thread = acceptThread;
        return running.get() && socket != null && !socket.isClosed()
                && thread != null && thread.isAlive();
    }

    synchronized void stop() {
        running.set(false);
        closeServerSocket();
        Thread thread = acceptThread;
        if (thread != null) {
            thread.interrupt();
        }
        for (CloudflaredWebSocketConnection connection : connections) {
            connection.close();
        }
        connections.clear();
        connectionExecutor.shutdownNow();
    }

    private ExecutorService createExecutor() {
        return Executors.newCachedThreadPool(new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "CloudflaredConn-" + port + "-" + counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    private void closeServerSocket() {
        ServerSocket socket = serverSocket;
        serverSocket = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
