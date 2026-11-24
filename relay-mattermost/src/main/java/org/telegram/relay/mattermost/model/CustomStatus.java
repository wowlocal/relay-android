package org.telegram.relay.mattermost.model;

import com.google.gson.annotations.SerializedName;

/** {@code PUT /users/{id}/status/custom}; also kept in {@code User.props.customStatus}. */
public class CustomStatus {
    @SerializedName("emoji") public String emoji = "";
    @SerializedName("text") public String text = "";
    /** {@code thirty_minutes}, {@code one_hour}, {@code four_hours}, {@code today}, {@code this_week}, {@code date_and_time}, or empty. */
    @SerializedName("duration") public String duration = "";
    /** RFC 3339 timestamp, or empty for no expiry. */
    @SerializedName("expires_at") public String expiresAt = "";

    public CustomStatus() {
    }

    public CustomStatus(String emoji, String text) {
        this.emoji = emoji;
        this.text = text;
    }

    public boolean isEmpty() {
        return (emoji == null || emoji.isEmpty()) && (text == null || text.isEmpty());
    }
}
