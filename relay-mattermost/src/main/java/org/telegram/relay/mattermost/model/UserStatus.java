package org.telegram.relay.mattermost.model;

import com.google.gson.annotations.SerializedName;

/** {@code GET /users/{id}/status}, {@code POST /users/status/ids}. */
public class UserStatus {
    public static final String ONLINE = "online";
    public static final String AWAY = "away";
    public static final String DND = "dnd";
    public static final String OFFLINE = "offline";

    @SerializedName("user_id") public String userId = "";
    /** {@link #ONLINE}, {@link #AWAY}, {@link #DND} or {@link #OFFLINE}. */
    @SerializedName("status") public String status = "";
    @SerializedName("manual") public boolean manual;
    @SerializedName("last_activity_at") public long lastActivityAt;
    /** Unix seconds when a DND status ends (0: no end). */
    @SerializedName("dnd_end_time") public long dndEndTime;
    @SerializedName("prev_status") public String prevStatus = "";

    public boolean isOnline() {
        return ONLINE.equals(status);
    }
}
