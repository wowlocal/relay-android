package org.telegram.relay.mattermost.model;

import com.google.gson.JsonObject;

import org.telegram.relay.mattermost.Json;

/** {@code GET /system/ping}. */
public class PingResponse {
    /** {@code "OK"} for a healthy server. */
    public final String status;
    /** The {@code X-Version-Id} header, e.g. {@code 11.7.14.39757492507.<hash>.false}; may be null. */
    public final String versionHeader;
    /** The whole body ({@code ActiveSearchBackend}, {@code database_status}, …). */
    public final JsonObject raw;

    public PingResponse(JsonObject raw, String versionHeader) {
        this.raw = raw != null ? raw : new JsonObject();
        this.status = Json.string(this.raw, "status", "");
        this.versionHeader = versionHeader;
    }

    public boolean isOk() {
        return "OK".equalsIgnoreCase(status);
    }

    /** The server version from {@code X-Version-Id}, or null. */
    public ServerVersion getServerVersion() {
        return ServerVersion.parse(versionHeader);
    }
}
