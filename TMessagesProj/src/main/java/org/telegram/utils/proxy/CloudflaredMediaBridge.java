package org.telegram.utils.proxy;

import android.os.SystemClock;

import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketAdapter;
import com.neovisionaries.ws.client.WebSocketException;
import com.neovisionaries.ws.client.WebSocketFactory;
import com.neovisionaries.ws.client.WebSocketFrame;

import org.telegram.messenger.FileLog;
import org.telegram.owpengram.OwpengramServer;
import org.telegram.owpengram.OwpengramServers;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.IDN;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Carries call media through the Cloudflared TCP tunnel.
 *
 * <p>A Cloudflare Tunnel published as a TCP application cannot forward the
 * UDP that WebRTC/TURN normally use. The client therefore binds loopback UDP
 * sockets on the same ports the server advertises ({@value #SFU_PORT} and
 * {@value #TURN_PORT}), takes every datagram WebRTC sends there, and forwards
 * it over a second WebSocket to {@value #MEDIA_PATH} on the same tunnel. The
 * server's media bridge turns the stream back into UDP for its loopback
 * TURN/SFU listeners.</p>
 *
 * <p>The bridge is only started for a Cloudflared server. Direct servers keep
 * using the real advertised media addresses.</p>
 */
public final class CloudflaredMediaBridge {
    /** Must match the server's cloudflaredMediaSFUPort. */
    public static final int SFU_PORT = 12399;
    /** Must match the server's cloudflaredMediaTURNPort. */
    public static final int TURN_PORT = 12400;

    private static final byte KIND_SFU = 1;
    private static final byte KIND_TURN = 2;
    private static final byte[] MAGIC = {'O', 'W', 'P', 'M'};
    /** Must match the server's MediaWebSocketPath. */
    private static final String MEDIA_PATH = "/owpmedia";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int MAX_DATAGRAM = 65535;
    private static final int MAX_QUEUE = 64;
    private static final long IDLE_MS = 15 * 60 * 1000L;
    private static final long CLEANUP_INTERVAL_MS = 10_000L;
    private static final Object lock = new Object();

    private static Relay sfuRelay;
    private static Relay turnRelay;
    private static String host;

    private CloudflaredMediaBridge() {
    }

    /** Starts the bridge when the account's selected server uses Cloudflared. */
    public static void start(int accountNum) {
        OwpengramServer server = OwpengramServers.getServerForAccount(accountNum);
        if (server == null || server.isTelegram || !server.useCloudflared
                || server.host == null || server.host.isEmpty()) {
            stop();
            return;
        }
        start(server.host);
    }

    public static void start(String hostname) {
        if (hostname == null || hostname.trim().isEmpty()) {
            stop();
            return;
        }
        String nextHost = hostname.trim();
        synchronized (lock) {
            if (nextHost.equals(host) && sfuRelay != null && sfuRelay.isRunning()
                    && turnRelay != null && turnRelay.isRunning()) {
                return;
            }
            stopLocked();
            host = nextHost;
            Relay sfu = new Relay(nextHost, SFU_PORT, KIND_SFU);
            if (sfu.start()) {
                sfuRelay = sfu;
            } else {
                sfu.stop();
            }
            Relay turn = new Relay(nextHost, TURN_PORT, KIND_TURN);
            if (turn.start()) {
                turnRelay = turn;
            } else {
                turn.stop();
            }
        }
    }

    public static void stop() {
        synchronized (lock) {
            stopLocked();
        }
    }

    private static void stopLocked() {
        if (sfuRelay != null) {
            sfuRelay.stop();
            sfuRelay = null;
        }
        if (turnRelay != null) {
            turnRelay.stop();
            turnRelay = null;
        }
        host = null;
    }

    /**
     * One loopback UDP port. Datagrams from different source sockets get
     * separate WebSocket sessions, so multiple ICE sockets do not mix replies.
     */
    private static final class Relay {
        private final String hostname;
        private final int port;
        private final byte kind;
        private final ConcurrentHashMap<InetSocketAddress, Session> sessions = new ConcurrentHashMap<>();
        private volatile DatagramSocket socket;
        private volatile Thread receiver;
        private volatile boolean running;
        private volatile long lastCleanup;

        Relay(String hostname, int port, byte kind) {
            this.hostname = hostname;
            this.port = port;
            this.kind = kind;
        }

        boolean start() {
            try {
                DatagramSocket next = new DatagramSocket(null);
                next.setReuseAddress(true);
                next.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
                socket = next;
                running = true;
                Thread thread = new Thread(this::receiveLoop, "CloudflaredMedia-" + port);
                thread.setDaemon(true);
                receiver = thread;
                thread.start();
                FileLog.d("Cloudflared media: listening on 127.0.0.1:" + port);
                return true;
            } catch (Throwable t) {
                running = false;
                closeSocket();
                FileLog.e("Cloudflared media: could not listen on 127.0.0.1:" + port, t);
                return false;
            }
        }

        boolean isRunning() {
            DatagramSocket current = socket;
            Thread thread = receiver;
            return running && current != null && !current.isClosed()
                    && thread != null && thread.isAlive();
        }

        private void receiveLoop() {
            byte[] buffer = new byte[MAX_DATAGRAM];
            while (running) {
                try {
                    DatagramSocket current = socket;
                    if (current == null) {
                        break;
                    }
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    current.receive(packet);
                    if (packet.getLength() <= 0) {
                        continue;
                    }
                    InetSocketAddress source = (InetSocketAddress) packet.getSocketAddress();
                    Session session = sessions.get(source);
                    if (session == null) {
                        Session created = new Session(this, source);
                        Session previous = sessions.putIfAbsent(source, created);
                        session = previous != null ? previous : created;
                    }
                    session.offer(bufer, packet.getLength());

                    long now = SystemClock.elapsedRealtime();
                    if (now - lastCleanup > CLEANUP_INTERVAL_MS) {
                        lastCleanup = now;
                        cleanup(now);
                    }
                } catch (SocketException e) {
                    if (running) {
                        FileLog.e("Cloudflared media: receive failed on port " + port, e);
                    }
                    break;
                } catch (Throwable t) {
                    if (running) {
                        FileLog.e("Cloudflared media: receive loop for port " + port, t);
                    }
                }
            }
        }

        private void cleanup(long now) {
            for (Map.Entry<InetSocketAddress, Session> entry : sessions.entrySet()) {
                Session session = entry.getValue();
                if (now - session.lastActivity > IDlE_MS && sessions.remove(entry.getKey(), session)) {
                    session.close();
                }
            }
        }

        void send(byte[] data, int offset, int length, InetSocketAddress source) {
            DatagramSocket current = socket;
            if (current == null || current.isClosed()) {
                return;
            }
            try {
                current.send(new DatagramPacket(data, offset, length, source));
            } catch (Throwable t) {
                // A closed socket is expected while a call is being torn down.
                if (!current.isClosed()) {
                    FileLog.e("Cloudflared media: could not deliver a datagram", t);
                }
            }
        }

        void stop() {
            running = false;
            closeSocket();
            for (Session session : sessions.values()) {
                session.close();
            }
            sessions.clear();
            Thread thread = receiver;
            if (thread != null) {
                thread.interrupt();
            }
            receiver = null;
        }

        private void closeSocket() {
            DatagramSocket current = socket;
            socket = null;
            if (current != null) {
                try {
                    current.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** One WebSocket per UDP source socket. */
    private static final class Session {
        private final Relay relay;
        private final InetSocketAddress source;
        private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(MAX_QUEUE);
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile WebSocket webSocket;
        private volatile boolean connecting;
        private volatile long lastActivity;
        private volatile long reconnectDelayMs;
        private volatile long nextConnectAt;

        Session(Relay relay, InetSocketAddress source) {
            this.relay = relay;
            this.source = source;
            this.lastActivity = SystemClock.elapsedRealtime();
        }

        void offer(byte[] data, int length) {
            if (closed.get()) {
                return;
            }
            lastActivity = SystemClock.elapsedRealtime();
            byte[] copy = Arrays.copyOf(data, length);
            if (!queue.offer(copy)) {
                // Voice is better served by a fresh packet than by an old one.
                queue.poll();
                queue.offer(copy);
            }
            ensureConnected();
        }

        private void ensureConnected() {
            if (closed.get()) {
                return;
            }
            WebSocket current = webSocket;
            if (current != null && current.isOpen()) {
                return;
            }
            if (SystemClock.elapsedRealtime() < nextConnectAt) {
                return;
            }
            synchronized (this) {
                if (closed.get() || connecting) {
                    return;
                }
                current = webSocket;
                if (current != null && current.isOpen()) {
                    return;
                }
                connecting = true;
            }
            Thread thread = new Thread(this::connect, "CloudflaredMediaConnect-" + relay.port);
            thread.setDaemon(true);
            thread.start();
        }

        private void connect() {
            WebSocket socket = null;
            try {
                String asciiHost = IDN.toASCII(relay.hostname, IDN.USE_STD_ASNII_RULES);
                socket = new WebSocketFactory()
                        .setConnectionTimeout(CONNECT_TIMEOUT_MS)
                        .createSocket("wss://" + asciiHost + MEDIA_PATH);
                socket.addHeader("User-Agent", "OwpenGram");
                socket.setPingInterval(30_000);
                final WebSocket connectingSocket = socket;
                socket.addListener(new WebSocketAdapter() {
                    @Override
                    public void onConnected(WebSocket websocket, Map<String, List<String>> headers) {
                        if (closed.get()) {
                            disconnectQuietly(websocket);
                            return;
                        }
                        webSocket = websocket;
                        connecting = false;
                        websocket.sendBinary(new byte[]{MAGIC[0], MAGIC[1], MAGIC[2], MAGIC[3], relay.kind});
                        Thread writer = new Thread(() -> writeLoop(connectingSocket),
                                "CloudflaredMediaWriter-" + relay.port);
                        writer.setDaemon(true);
                        writer.start();
                    }

                    @Override
                    public void onBinaryMessage(WebSocket websocket, byte[] binary) {
                        if (closed.get() || binary == null || binary.length < 2) {
                            return;
                        }
                        int length = ((binary[0] & 0xff) << 8) | (binary[1] & 0xff);
                        if (length < 0 || length + 2 > binary.length) {
                            return;
                        }
                        lastActivity = SystemClock.elapsedRealtime();
                        reconnectDelayMs = 0;
                        relay.send(binary, 2, length, source);
                    }

                    @Override
                    public void onTextMessage(WebSocket websocket, String text) {
                        // The media relay only uses binary frames.
                    }

                    @Override
                    public void onDisconnected(WebSocket websocket, WebSocketFrame serverCloseFrame,
                                               WebSocketFrame clientCloseFrame, boolean closedByServer) {
                        handleDisconnect(websocket);
                    }

                    @Override
                    public void onError(WebSocket websocket, WebSocketException cause) {
                        if (!closed.get()) {
                            FileLog.e("Cloudflared media: websocket error for " + relay.hostname + ": " + cause.getError());
                        }
                        handleDisconnect(websocket);
                    }
                });
                webSocket = socket;
                socket.connect();
            } catch (Throwable t) {
                if (!closed.get()) {
                    FileLog.e("Cloudflared media: websocket connection to " + relay.hostname + " failed", t);
                }
                if (socket != null) {
                    disconnectQuietly(socket);
                }
                scheduleReconnect();
            } finally {
                connecting = false;
                if (closed.get()) {
                    if (socket != null) {
                        disconnectQuietly(socket);
                    }
                } else {
                    WebSocket current = webSocket;
                    if (current == null || !current.isOpen()) {
                        webSocket = null;
                    }
                }
            }
        }

        private void writeLoop(WebSocket socket) {
            try {
                while (!closed.get()) {
                    if (!socket.isOpen()) {
                        break;
                    }
                    byte[] payload = queue.poll(500, TimeUnit.MILLISECONDS);
                    if (payload == null) {
                        continue;
                    }
                    // Do not let a stalled tunnel accumulate frames without bound.
                    if (socket.getFrameQueueSize() > MAX_QUEUE) {
                        continue;
                    }
                    byte[] frame = new byte[2 + payload.length];
                    frame[0] = (byte) (payload.length >> 8);
                    frame[1] = (byte) payload.length;
                    System.arraycopy(payload, 0, frame, 2, payload.length);
                    socket.sendBinary(frame);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                if (!closed.get()) {
                    FileLog.e("Cloudflared media: websocket write failed for " + relay.hostname, t);
                }
            }
            if (!closed.get()) {
                handleDisconnect(socket);
            }
        }

        /** Keeps the session (and its backoff) for the next datagram. */
        private void handleDisconnect(WebSocket failed) {
            if (webSocket == failed) {
                webSocket = null;
            }
            if (!closed.get()) {
                scheduleReconnect();
            }
        }

        private void scheduleReconnect() {
            long delay = reconnectDelayMs <= 0
                    ? 1000L
                    : Math.min(reconnectDelayMs * 2, 10_000L);
            reconnectDelayMs = delay;
            nextConnectAt = SystemClock.elapsedRealtime() + delay;
        }

        void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            relay.sessions.remove(source, this);
            WebSocket socket = webSocket;
            if (socket != null) {
                disconnectQuietly(socket);
            }
        }

        private static void disconnectQuietly(WebSocket socket) {
            Thread thread = new Thread(() -> {
                try {
                    socket.disconnect();
                } catch (Throwable ignored) {
                }
            }, "CloudflaredMediaClose");
            thread.setDaemon(true);
            thread.start();
        }
    }
}
