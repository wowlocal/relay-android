package org.telegram.relay.mattermost.model;

import com.google.gson.annotations.SerializedName;

import java.util.HashMap;
import java.util.Map;

/** {@code POST /channels/members/{user_id}/view}. */
public class ChannelViewResponse {
    @SerializedName("status") public String status = "";
    /** Channel id to its new {@code last_viewed_at} (ms). */
    @SerializedName("last_viewed_at_times") public Map<String, Long> lastViewedAtTimes = new HashMap<String, Long>();
}
