package org.telegram.utils.proxy;

import android.os.SystemClock;

import com.neovisionaries.ws.client.OpeningHandshakeException;
import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketAdapter;
import com.neovisionaries.ws.client.WebSocketException;
import com.neovisionaries.ws.client.WebSocketFactory;
import com.neovisionaries.ws.client.WebSocketFrame;

import org.telegram.messenger.FileLog;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.IDN;
import java.net.Socket;
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
 * Carries one local TCP connection over a WebSocket to the Cloudflare edge.
 *
 * <p>This is the same wire protocol cloudflared's {@code access tcp} carrier
 * uses: a {@code wss://<hostname>/} upgrade with no subprotocol, then the raw
 * bytes of the TCP stream in binary WebSocket frames. Keeping it in-process
 * removes the child process, its resolver-config hack and the "one process for
 * every connection" failure mode.</p>
 */
final class CloudflaredWebSocketConnection implements Runnable {
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_BUFFER_SIZE = 16 * 1024;
    private static final int MAX_QUEUED_FRAMES = 64;
    private static final long MAX_BACKPRESSURE_MS = 30_000;
    private static final long PING_INTERVAL_MS = 30_000;
    private static final byte[] CLOSE = new byte[0];
    private static final String HEADER_ACCESS_CLIENT_ID = "Cf-Access-Client-Id";
    private static final String HEADER_ACCESS_CLIENT_SECRET = "Cf-Access-Client-Secret";
    /**
     * WebSocket.disconnect() can wait for the closing handshake. It must never
     * run on the service's main thread or on the WebSocket reader thread (which
     * may be the caller), so every disconnect is handed to a daemon worker.
     */
    private static final ExecutorService CLOSER = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "CloudflaredClose");
        thread.setDaemon(true);
        return thread;
    });

    private final String hostname;
    private final Socket socket;
    private final String accessClientId;
    private final String accessClientSecret;
    private final Runnable onClosed;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final BlockingQueue<byte[]> incoming = new ArrayBlockingQueue<>(MAX_QUEUED_FRAMES);
    private volatile WebSocket webSocket;

    CloudflaredWebSocketConnection(String hostname, Socket socket, String accessClientId,
                                   String accessClientSecret, Runnable onClosed) {
        this.hostname = hostname;
        this.socket = socket;
        this.accessClientId = accessClientId == null ? "" : accessClientId;
        this.accessClientSecret = accessClientSecret == null ? "" : accessClientSecret;
        this.onClosed = onClosed;
    }

    @Override
    public void run() {
        try {
            String asciiHost = IDN.toASCII(hostname, IDN.USE_STD3_ASCII_RULES);
            WebSocket ws = new WebSocketFactory()
                    .setConnectionTimeout(CONNECT_TIMEOUT_MS)
                    .createSocket("wss://" + asciiHost + "/");
            ws.addHeader("User-Agent", "OwpenGram");
            if (!accessClientId.isEmpty()) {
                ws.addHeader(HEADER_ACCESS_CLIENT_ID, accessClientId);
            }
            if (!accessClientSecret.isEmpty()) {
                ws.addHeader(HEADER_ACCESS_CLIENT_SECRET, accessClientSecret);
            }
            ws.setPingInterval(PING_INTERVAL_MS);
            ws.addListener(new WebSocketAdapter() {
                @Override
                public void onConnected(WebSocket websocket, Map<String, List<String>> headers) {
                    if (closed.get()) {
                        websocket.disconnect();
                    }
                }

                @Override
                public void onBinaryMessage(WebSocket websocket, byte[] binary) {
                    if (binary == null || binary.length == 0) {
                        return;
                    }
                    if (!incoming.offer(binary)) {
                        FileLog.e("Cloudflared: local side for " + hostname + " is not draining; closing");
                        close();
                    }
                }

                @Override
                public void onTextMessage(WebSocket websocket, String text) {
                    // The TCP carrier uses binary frames. A text frame is not part
                    // of the protocol; do not corrupt the local byte stream.
                    FileLog.e("Cloudflared: unexpected text frame for " + hostname + "; closing");
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
                        FileLog.e("Cloudflared: websocket error for " + hostname + ": " + cause.getError());
                    }
                    close();
                }
            });
            webSocket = ws;
            ws.connect();
            if (closed.get()) {
                return;
            }
            Thread writer = new Thread(this::writeLoop, "CloudflaredWriter-" + socket.getLocalPort());
            writer.setDaemon(true);
            writer.start();
            readLoop(ws);
        } catch (OpeningHandshakeException e) {
            int status = e.getStatusLine() == null ? -1 : e.getStatusLine().getStatusCode();
            if (status == 302) {
                FileLog.e("Cloudflared: Cloudflare Access rejected the request for " + hostname
                        + " (HTTP 302). Add a service token in the server settings.");
            } else if (status == 401 || status == 403) {
                FileLog.e("Cloudflared: Cloudflare Access rejected the service token for " + hostname
                        + " (HTTP " + status + ")");
            } else {
                FileLog.e("Cloudflared: websocket handshake for " + hostname + " failed (HTTP " + status + ")");
            }
        } catch (WebSocketException e) {
            if (!closed.get()) {
                FileLog.e("Cloudflared: websocket connection to " + hostname + " failed: " + e.getError());
            }
        } catch (Throwable t) {
            if (!closed.get()) {
                FileLog.e("Cloudflared: connection to " + hostname + " failed", t);
            }
        } finally {
            close();
        }
    }

    private void readLoop(WebSocket ws) throws Exception {
        InputStream in = socket.getInputStream();
        byte[] buffer = new byte[READ_BUFFER_SIZE];
        long backpressureSince = 0;
        while (!closed.get()) {
            int read = in.read(buffer);
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
                    FileLog.e("Cloudflared: websocket for " + hostname + " is not draining; closing");
                    return;
                }
                Thread.sleep(10);
            }
            backpressureSince = 0;
            if (closed.get()) {
                break;
            }
            // nv-websocket only exposes a whole-array send; the copy is bounded
            // by READ_BUFFER_SIZE and lets the library own the frame payload.
            ws.sendBinary(Arrays.copyOf(buffer, read));
        }
    }

    private void writeLoop() {
        try {
            OutputStream out = socket.getOutputStream();
            while (!closed.get()) {
                byte[] data = incoming.poll(500, TimeUnit.MILLISECONDS);
                if (data == null) {
                    continue;
                }
                if (data == CLOSE) {
                    break;
                }
                out.write(data);
                out.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            if (!closed.get()) {
                FileLog.e("Cloudflared: local write failed for " + hostname, e);
            }
            close();
        }
    }

    void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        incoming.offer(CLOSE);
        WebSocket ws = webSocket;
        if (ws != null) {
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
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        try {
            onClosed.run();
        } catch (Throwable ignored) {
        }
    }
}
