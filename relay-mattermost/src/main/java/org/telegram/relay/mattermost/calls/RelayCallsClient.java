package org.telegram.relay.mattermost.calls;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.telegram.relay.mattermost.MattermostClient;
import org.telegram.relay.mattermost.MattermostException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * REST client of the Relay calls plugin ({@code /plugins/org.relaychat.calls}, contract version 2) over the
 * account's {@link MattermostClient} session. Port of the iOS {@code RelayCallsClient}: same routes, bodies and
 * validation (26-character channel ids, safe call ids, media URLs {@code wss}/{@code https}, or
 * {@code ws}/{@code http} for loopback hosts only).
 */
public final class RelayCallsClient implements RelayCallsApi {
    public static final String PLUGIN_ID = "org.relaychat.calls";

    /** {@code GET /config}. */
    public static final class Configuration {
        public boolean enabled;
        public int version;
        public int maxParticipants;
        public boolean screenSharing;
        public boolean camera;
        public boolean ringing;
        public int ringTimeoutSeconds = 45;

        public boolean supportsRinging() {
            return enabled && version >= 2 && ringing;
        }
    }

    private final MattermostClient client;

    public RelayCallsClient(MattermostClient client) {
        this.client = client;
    }

    public Configuration configuration() throws RelayCallsException {
        JsonObject o = object(request("GET", "config", null, false));
        Configuration c = new Configuration();
        c.enabled = !o.has("enabled") || RelayCall.bool(o, "enabled");
        c.version = (int) RelayCall.number(o, "version", 1);
        c.maxParticipants = (int) RelayCall.number(o, "max_participants", 0);
        c.screenSharing = RelayCall.bool(o, "screen_sharing");
        c.camera = RelayCall.bool(o, "camera");
        c.ringing = RelayCall.bool(o, "ringing");
        c.ringTimeoutSeconds = (int) RelayCall.number(o, "ring_timeout_seconds", 45);
        return c;
    }

    /** {@code GET /channels/<id>}: the channel's current call or null. */
    public RelayCall channelCall(String channelId) throws RelayCallsException {
        validateChannel(channelId);
        JsonObject o = object(request("GET", "channels/" + channelId, null, false));
        return RelayCall.from(o.get("call"));
    }

    @Override
    public RelayCallJoin join(String channelId, String callId, String deviceId) throws RelayCallsException {
        validateChannel(channelId);
        JsonObject body = new JsonObject();
        body.addProperty("call_id", callId == null ? "" : callId);
        if (deviceId != null) {
            body.addProperty("device_id", deviceId);
        }
        RelayCallJoin join = join(request("POST", "channels/" + channelId + "/join", body, false));
        if (!join.call.channelId.equals(channelId) || (callId != null && !callId.isEmpty() && !join.call.id.equals(callId))) {
            throw new RelayCallsException(RelayCallsException.INVALID_SERVER, "The server returned an invalid calling address.", null);
        }
        return join;
    }

    @Override
    public RelayCallJoin ring(String channelId, boolean video, String deviceId) throws RelayCallsException {
        validateChannel(channelId);
        JsonObject body = new JsonObject();
        body.addProperty("video", video);
        body.addProperty("device_id", deviceId);
        RelayCallJoin join = join(request("POST", "channels/" + channelId + "/ring", body, true));
        if (!join.call.channelId.equals(channelId)) {
            throw new RelayCallsException(RelayCallsException.INVALID_SERVER, "The server returned an invalid calling address.", null);
        }
        return join;
    }

    @Override
    public void end(String channelId, String callId) throws RelayCallsException {
        validateChannel(channelId);
        JsonObject body = new JsonObject();
        body.addProperty("call_id", callId);
        request("POST", "channels/" + channelId + "/end", body, false);
    }

    @Override
    public RelayCall call(String callId) throws RelayCallsException {
        validateCall(callId);
        try {
            JsonElement e = request("GET", "calls/" + callId, null, false);
            RelayCall call = RelayCall.from(e);
            if (call == null) {
                JsonObject o = RelayCall.object(e);
                call = o == null ? null : RelayCall.from(o.get("call"));
            }
            if (call == null) {
                throw new RelayCallsException(RelayCallsException.INVALID_RESPONSE, "The calling service returned an invalid response.", null);
            }
            return call;
        } catch (RelayCallsException e) {
            if (e.status == 404) {
                return null;
            }
            throw e;
        }
    }

    /** {@code GET /calls/active}: ringing and active calls in the user's channels, newest first. */
    public List<RelayCall> activeCalls() throws RelayCallsException {
        JsonElement e = request("GET", "calls/active", null, false);
        JsonArray array = null;
        if (e != null && e.isJsonArray()) {
            array = e.getAsJsonArray();
        } else if (e != null && e.isJsonObject() && e.getAsJsonObject().has("calls") && e.getAsJsonObject().get("calls").isJsonArray()) {
            array = e.getAsJsonObject().getAsJsonArray("calls");
        }
        List<RelayCall> calls = new ArrayList<>();
        if (array != null) {
            for (JsonElement item : array) {
                RelayCall call = RelayCall.from(item);
                if (call != null) {
                    calls.add(call);
                }
            }
        }
        return calls;
    }

    @Override
    public void received(String callId, String deviceId) throws RelayCallsException {
        validateCall(callId);
        request("POST", "calls/" + callId + "/received", device(deviceId), false);
    }

    @Override
    public RelayCallJoin accept(String callId, String deviceId) throws RelayCallsException {
        validateCall(callId);
        RelayCallJoin join = join(request("POST", "calls/" + callId + "/accept", device(deviceId), false));
        if (!join.call.id.equals(callId)) {
            throw new RelayCallsException(RelayCallsException.INVALID_SERVER, "The server returned an invalid calling address.", null);
        }
        return join;
    }

    @Override
    public void decline(String callId, String deviceId, String reason) throws RelayCallsException {
        validateCall(callId);
        JsonObject body = device(deviceId);
        body.addProperty("reason", reason == null ? "declined" : reason);
        request("POST", "calls/" + callId + "/decline", body, false);
    }

    @Override
    public void hangup(String callId, String deviceId) throws RelayCallsException {
        validateCall(callId);
        request("POST", "calls/" + callId + "/hangup", device(deviceId), false);
    }

    // MARK: Validation

    static void validateChannel(String id) throws RelayCallsException {
        boolean ok = id != null && id.length() == 26;
        for (int i = 0; ok && i < id.length(); i++) {
            char c = id.charAt(i);
            ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
        }
        if (!ok) {
            throw new RelayCallsException(RelayCallsException.INVALID_CHANNEL, "This conversation is not ready for calls.", null);
        }
    }

    static void validateCall(String id) throws RelayCallsException {
        boolean ok = id != null && !id.isEmpty() && id.length() <= 64;
        for (int i = 0; ok && i < id.length(); i++) {
            char c = id.charAt(i);
            ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
        }
        if (!ok) {
            throw new RelayCallsException(RelayCallsException.INVALID_RESPONSE, "The calling service returned an invalid response.", null);
        }
    }

    public static boolean isAllowedMediaUrl(String text) {
        try {
            URI uri = new URI(text);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            if (host == null || host.isEmpty() || uri.getUserInfo() != null || uri.getFragment() != null) {
                return false;
            }
            switch (scheme) {
                case "https":
                case "wss":
                    return true;
                case "http":
                case "ws":
                    return isLoopbackHost(host);
                default:
                    return false;
            }
        } catch (Exception e) {
            return false;
        }
    }

    static boolean isLoopbackHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        if (h.equals("localhost") || h.equals("::1") || h.equals("0:0:0:0:0:0:0:1")) {
            return true;
        }
        String[] parts = h.split("\\.", -1);
        if (parts.length != 4 || !parts[0].equals("127")) {
            return false;
        }
        for (String part : parts) {
            try {
                int v = Integer.parseInt(part);
                if (v < 0 || v > 255) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    // MARK: Requests

    private static JsonObject device(String deviceId) {
        JsonObject body = new JsonObject();
        body.addProperty("device_id", deviceId);
        return body;
    }

    private static RelayCallJoin join(JsonElement e) throws RelayCallsException {
        JsonObject o = RelayCall.object(e);
        RelayCall call = o == null ? null : RelayCall.from(o.get("call"));
        if (o == null || call == null) {
            throw new RelayCallsException(RelayCallsException.INVALID_RESPONSE, "The calling service returned an invalid response.", null);
        }
        String url = RelayCall.string(o, "url");
        String token = RelayCall.string(o, "token");
        // A busy direct ring answers 200 with an ended call and a token that cannot join: keep it, the
        // controller sees the ended call and never connects.
        if (call.state != RelayCall.State.ENDED && (!isAllowedMediaUrl(url) || token.isEmpty())) {
            throw new RelayCallsException(RelayCallsException.INVALID_SERVER, "The server returned an invalid calling address.", null);
        }
        return new RelayCallJoin(url, token, call);
    }

    private static JsonObject object(JsonElement e) throws RelayCallsException {
        JsonObject o = RelayCall.object(e);
        if (o == null) {
            throw new RelayCallsException(RelayCallsException.INVALID_RESPONSE, "The calling service returned an invalid response.", null);
        }
        return o;
    }

    private JsonElement request(String method, String path, JsonObject body, boolean conflictCall) throws RelayCallsException {
        MattermostClient.PluginResponse response;
        try {
            response = client.plugin(method, PLUGIN_ID, path, body);
        } catch (MattermostException e) {
            throw new RelayCallsException(e.getMessage(), e);
        }
        JsonElement json = null;
        String text = response.body == null ? "" : response.body.trim();
        if (!text.isEmpty()) {
            try {
                json = JsonParser.parseString(text);
            } catch (RuntimeException ignored) {
                json = null;
            }
        }
        if (response.isSuccessful()) {
            return json;
        }
        String message = "HTTP " + response.statusCode;
        RelayCall call = null;
        JsonObject o = RelayCall.object(json);
        if (o != null) {
            String m = RelayCall.string(o, "message");
            if (!m.isEmpty()) {
                message = m;
            }
            call = RelayCall.from(o.get("call"));
        }
        throw new RelayCallsException(response.statusCode, message, conflictCall || response.statusCode == 409 ? call : null);
    }
}
