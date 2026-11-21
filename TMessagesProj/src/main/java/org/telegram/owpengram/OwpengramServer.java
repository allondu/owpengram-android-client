package org.telegram.owpengram;

import org.json.JSONException;
import org.json.JSONObject;

public class OwpengramServer {
    /** Plain TCP to host:port; no Cloudflare involved. */
    public static final int CONNECTION_DIRECT = 0;
    /** Cloudflared WebSocket carrier inside the app process (local listener + foreground service). */
    public static final int CONNECTION_CLOUDFLARED = 1;
    /** Cloudflared WebSocket carrier inside tgnet (JNI bridge): no listener, no foreground service. */
    public static final int CONNECTION_CLOUDFLARED_NATIVE = 2;

    public String id;
    public String name;
    public String host;
    public int port;
    public String description = "";
    public String rsaPublicKey = "";
    public long rsaKeyFingerprint = 0;
    public String logoPath = "";
    public boolean isOfficial;
    public boolean isTelegram;
    public boolean multiDc;
    public int mainDcId;
    /** Kept in sync with {@link #connectionMode} for older code and saved profiles. */
    public boolean useCloudflared;
    public int connectionMode = CONNECTION_DIRECT;

    public boolean usesCloudflared() {
        return connectionMode != CONNECTION_DIRECT;
    }

    public boolean usesNativeCloudflared() {
        return connectionMode == CONNECTION_CLOUDFLARED_NATIVE;
    }

    public int effectiveMainDcId() {
        if (mainDcId > 0) return mainDcId;
        return multiDc ? 2 : 1;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", id);
        obj.put("name", name);
        obj.put("host", host);
        obj.put("port", port);
        if (description != null && !description.isEmpty()) obj.put("description", description);
        if (rsaPublicKey != null && !rsaPublicKey.isEmpty()) obj.put("rsaPublicKey", rsaPublicKey);
        if (logoPath != null && !logoPath.isEmpty()) obj.put("logoPath", logoPath);
        if (multiDc) obj.put("multiDc", true);
        if (mainDcId > 0) obj.put("mainDcId", mainDcId);
        obj.put("connectionMode", connectionMode);
        if (useCloudflared) obj.put("useCloudflared", true);
        return obj;
    }

    public static OwpengramServer fromJson(JSONObject obj) throws JSONException {
        OwpengramServer s = new OwpengramServer();
        s.id = obj.getString("id");
        s.name = obj.getString("name");
        s.host = obj.getString("host");
        s.port = obj.getInt("port");
        s.description = obj.optString("description", "");
        s.rsaPublicKey = obj.optString("rsaPublicKey", "");
        s.logoPath = obj.optString("logoPath", "");
        s.isOfficial = false;
        s.multiDc = obj.optBoolean("multiDc", false);
        s.mainDcId = obj.optInt("mainDcId", 0);
        int mode = obj.optInt("connectionMode", -1);
        if (mode < CONNECTION_DIRECT || mode > CONNECTION_CLOUDFLARED_NATIVE) {
            mode = obj.optBoolean("useCloudflared", false)
                    ? CONNECTION_CLOUDFLARED : CONNECTION_DIRECT;
        }
        s.connectionMode = mode;
        s.useCloudflared = mode != CONNECTION_DIRECT;
        return s;
    }
}
