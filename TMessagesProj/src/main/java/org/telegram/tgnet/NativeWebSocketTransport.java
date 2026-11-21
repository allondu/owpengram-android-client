package org.telegram.tgnet;

import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import com.neovisionaries.ws.client.OpeningHandshakeException;
import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketAdapter;
import com.neovisionaries.ws.client.WebSocketException;
import com.neovisionaries.ws.client.WebSocketFactory;
import com.neovisionaries.ws.client.WebSocketFrame;

import org.telegram.messenger.FileLog;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.IDN;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Carries one tgnet connection over a Cloudflare Tunnel WebSocket.
 *
 * <p>tgnet creates an {@code AF_UNIX} socketpair and keeps one end as a normal
 * non-blocking socket (epoll, timeouts, byte accounting all keep working). This
 * class owns the other end and pumps it over a {@code wss://<hostname>/}
 * upgrade, the same wire protocol {@code cloudflared access tcp} uses. Because
 * the socket lives in the app process there is no loopback listener, no port
 * allocation and no foreground service.</p>
 *
 * <p>A WebSocket failure only closes this one socketpair, so tgnet reconnects
 * that single connection instead of dropping the whole tunnel.</p>
 */
final class NativeWebSocketTransport {
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int BUFFER_SIZE = 16 * 1024;
    private static final int MAX_QUEUED_FRAMES = 64;
    private static final long MAX_BACKPRESSURE_MS = 30_000;
    private static final long PING_INTERVAL_MS = 30_000;
    private static final byte[] CLOSE = new byte[0];
    /**
     * WebSocket.disconnect() can wait for the closing handshake. It must never
     * block the network thread or the WebSocket reader thread, so every
     * disconnect is handed to a daemon worker.
     */
    private static final ExecutorService CLOSER = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "CloudflaredNativeClose");
        thread.setDaemon(true);
        return thread;
    });

    private final String hostname;
    private final ParcelFileDescriptor descriptor;
    private final FileInputStream input;
    private final FileOutputStream output;
    private final BlockingQueue<byte[]> incoming = new ArrayBlockingQueue<>(MAX_QUEUED_FRAMES);
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile WebSocket webSocket;

    /**
     * Called from tgnet on the network thread. Must return quickly: the TLS
     * handshake and the pumps run on their own threads.
     */
    static void start(int instanceNum, String hostname, int fd) {
        try {
            NativeWebSocketTransport transport = new NativeWebSocketTransport(hostname, fd);
            Thread thread = new Thread(transport::run, "CloudflaredNative-" + instanceNum);
            thread.setDaemon(true);
            thread.start();
        } catch (Throwable t) {
            FileLog.e("Cloudflared native: unable to start the carrier for " + hostname, t);
            closeQuietly(fd);
        }
    }

    private NativeWebSocketTransport(String hostname, int fd) throws IOException {
        this.hostname = hostname;
        this.descriptor = ParcelFileDescriptor.adoptFd(fd);
        this.input = new FileInputStream(descriptor.getFileDescriptor());
        this.output = new FileOutputStream(descriptor.getFileDescriptor());
    }

    private void run() {
        try {
            String asciiHost = IDN.toASCII(hostname, IDN.USE_STD3_ASCII_RULES);
            WebSocket ws = new WebSocketFactory()
                    .setConnectionTimeout(CONNECT_TIMEOUT_MS)
                    .createSocket("wss://" + asciiHost + "/");
            ws.addHeader("User-Agent", "OwpenGram");
            ws.setPingInterval(PING_INTERVAL_MS);
            ws.addListener(new WebSocketAdapter() {
                @Override
                public void onConnected(WebSocket websocket, Map<String, List<String>> headers) {
                    if (closed.get()) {
                        disconnect(websocket);
                        return;
                    }
                    startPumps(websocket);
                }

                @Override
                public void onBinaryMessage(WebSocket websocket, byte[] binary) {
                    if (binary == null || binary.length == 0) {
                        return;
                    }
                    if (!incoming.offer(binary)) {
                        FileLog.e("Cloudflared native: tgnet is not draining " + hostname + "; closing");
                        close();
                    }
                }

                @Override
                public void onTextMessage(WebSocket websocket, String text) {
                    // The TCP carrier uses binary frames. A text frame is not
                    // part of the protocol; do not corrupt the byte stream.
                    FileLog.e("Cloudflared native: unexpected text frame for " + hostname + "; closing");
                    close();
                }

                @Override
                public void onDisconnected(WebSocket websocket, WebSocketFrame serverCloseFrame,
                                           WebSocketFrame clientCloseFrame, boolean closedByServer) {
                    close();
                }

                @Override
                public void onError(WebSocket websocket, WebSocketException cause) {
                    if (!closed.get()) {
                        FileLog.e("Cloudflared native: websocket error for " + hostname + ": " + cause.getError());
                    }
                    close();
                }
            });
            webSocket = ws;
            ws.connect();
        } catch (OpeningHandshakeException e) {
            int status = e.getStatusLine() == null ? -1 : e.getStatusLine().getStatusCode();
            if (status == 302) {
                FileLog.e("Cloudflared native: Cloudflare Access rejected " + hostname
                        + " (HTTP 302). Use a public hostname without Access for native mode.");
            } else if (status == 401 || status == 403) {
                FileLog.e("Cloudflared native: Cloudflare Access rejected " + hostname + " (HTTP " + status + ")");
            } else {
                FileLog.e("Cloudflared native: websocket handshake for " + hostname + " failed (HTTP " + status + ")");
            }
            close();
        } catch (WebSocketException e) {
            if (!closed.get()) {
                FileLog.e("Cloudflared native: websocket connection to " + hostname + " failed: " + e.getError());
            }
            close();
        } catch (Throwable t) {
            if (!closed.get()) {
                FileLog.e("Cloudflared native: connection to " + hostname + " failed", t);
            }
            close();
        }
    }

    private void startPumps(WebSocket ws) {
        Thread reader = new Thread(() -> readLoop(ws), "CloudflaredNativeRead-" + hostname);
        reader.setDaemon(true);
        reader.start();

        Thread writer = new Thread(this::writeLoop, "CloudflaredNativeWrite-" + hostname);
        writer.setDaemon(true);
        writer.start();
    }

    private void readLoop(WebSocket ws) {
        byte[] buffer = new byte[BUFFER_SIZE];
        long backpressureSince = 0;
        try {
            while (!closed.get()) {
                int read = input.read(buffer);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    continue;
                }
                while (!closed.get() && ws.getFrameQueueSize() > MAX_QUEUED_FRAMES) {
                    if (backpressureSince == 0) {
                        backpressureSince = SystemClock.elapsedRealtime();
                    }
                    if (SystemClock.elapsedRealtime() - backpressureSince > MAX_BACKPRESSURE_MS) {
                        FileLog.e("Cloudflared native: websocket for " + hostname + " is not draining; closing");
                        close();
                        return;
                    }
                    Thread.sleep(10);
                }
                backpressureSince = 0;
                if (closed.get()) {
                    break;
                }
                // nv-websocket owns the frame payload, and the copy is bounded
                // by BUFFER_SIZE.
                ws.sendBinary(Arrays.copyOf(buffer, read));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            if (!closed.get()) {
                FileLog.e("Cloudflared native: tgnet read failed for " + hostname, e);
            }
        } catch (Throwable t) {
            if (!closed.get()) {
                FileLog.e("Cloudflared native: carrier read failed for " + hostname, t);
            }
        } finally {
            close();
        }
    }

    private void writeLoop() {
        try {
            while (!closed.get()) {
                byte[] data = incoming.poll(500, TimeUnit.MILLISECONDS);
                if (data == null) {
                    continue;
                }
                if (data == CLOSE) {
                    break;
                }
                output.write(data);
                output.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            if (!closed.get()) {
                FileLog.e("Cloudflared native: tgnet write failed for " + hostname, e);
            }
            close();
        }
    }

    private void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        incoming.offer(CLOSE);
        WebSocket ws = webSocket;
        if (ws != null) {
            disconnect(ws);
        }
        try {
            // The two streams share this descriptor; closing it wakes tgnet
            // with EPOLLRDHUP and is enough to tear the socketpair down.
            descriptor.close();
        } catch (IOException ignored) {
        }
    }

    private static void disconnect(WebSocket ws) {
        try {
            CLOSER.execute(() -> {
                try {
                    ws.disconnect();
                } catch (Throwable ignored) {
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    private static void closeQuietly(int fd) {
        try {
            ParcelFileDescriptor.adoptFd(fd).close();
        } catch (Throwable ignored) {
        }
    }
}
