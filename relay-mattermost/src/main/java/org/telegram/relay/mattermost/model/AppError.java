package org.telegram.relay.mattermost.model;

import com.google.gson.annotations.SerializedName;

/** Mattermost's error body: {@code {"id","message","detailed_error","request_id","status_code"}}. */
public class AppError {
    @SerializedName("id") public String id = "";
    @SerializedName("message") public String message = "";
    @SerializedName("detailed_error") public String detailedError = "";
    @SerializedName("request_id") public String requestId = "";
    @SerializedName("status_code") public int statusCode;

    public AppError() {
    }

    public AppError(String id, String message, int statusCode) {
        this.id = id;
        this.message = message;
        this.statusCode = statusCode;
    }

    public boolean isEmpty() {
        return (id == null || id.isEmpty()) && (message == null || message.isEmpty());
    }

    @Override
    public String toString() {
        return "AppError(" + statusCode + " " + id + ": " + message + ")";
    }
}
