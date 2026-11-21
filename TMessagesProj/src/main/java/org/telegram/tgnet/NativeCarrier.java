package org.telegram.tgnet;

/**
 * Per-account switch for the in-tgnet Cloudflared carrier.
 *
 * <p>When enabled, tgnet stops dialing TCP for this account and instead hands
 * one end of an {@code AF_UNIX} socketpair to {@link #openWebSocket}. The Java
 * side carries that stream over {@code wss://<hostname>/} (the same wire
 * protocol {@code cloudflared access tcp} uses), so there is no loopback
 * listener, port allocation or foreground service.</p>
 */
public final class NativeCarrier {
    private NativeCarrier() {
    }

    public static native void native_setEnabled(int instanceNum, boolean enabled);

    public static void setEnabled(int instanceNum, boolean enabled) {
        native_setEnabled(instanceNum, enabled);
    }

    /**
     * Called from tgnet on its network thread. {@code fd} is one end of the
     * socketpair and is owned by the Java transport from here on.
     */
    public static void openWebSocket(int instanceNum, String hostname, int fd) {
        NativeWebSocketTransport.start(instanceNum, hostname, fd);
    }
}
