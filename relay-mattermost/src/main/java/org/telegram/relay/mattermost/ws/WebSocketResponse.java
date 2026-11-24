package org.telegram.relay.mattermost.ws;

import com.google.gson.JsonObject;

import org.telegram.relay.mattermost.Json;
import org.telegram.relay.mattermost.model.AppError;

/** The reply to a client action ({@code seq_reply} frames). */
public class WebSocketResponse {
    /** {@code "OK"} or {@code "FAIL"}. */
    public final String status;
    public final long seqReply;
    public final JsonObject data;
    /** May be null. */
    public final AppError error;
    public final String raw;

    public WebSocketResponse(String status, long seqReply, JsonObject data, AppError error, String raw) {
        this.status = status != null ? status : "";
        this.seqReply = seqReply;
        this.data = data != null ? data : new JsonObject();
        this.error = error;
        this.raw = raw;
    }

    static WebSocketResponse fromJson(JsonObject object, String raw) {
        JsonObject data = object.has("data") && object.get("data").isJsonObject() ? object.getAsJsonObject("data") : new JsonObject();
        AppError error = null;
        if (object.has("error") && object.get("error").isJsonObject()) {
            error = Json.decodeEmbedded(object.get("error"), AppError.class);
        }
        return new WebSocketResponse(Json.string(object, "status", ""), Json.longValue(object, "seq_reply", -1), data, error, raw);
    }

    public boolean isOk() {
        return "OK".equalsIgnoreCase(status);
    }

    /** The server rejected the connection's authentication. */
    public boolean isNotAuthenticated() {
        if (isOk() || error == null) {
            return false;
        }
        return error.statusCode == 401 || "api.web_socket_router.not_authenticated.app_error".equals(error.id);
    }
}
