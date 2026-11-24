package org.telegram.relay.mattermost.ws;

import com.google.gson.annotations.SerializedName;

import java.util.HashMap;
import java.util.Map;

/** Who an event was sent to. */
public class WebSocketBroadcast {
    @SerializedName("user_id") public String userId = "";
    @SerializedName("channel_id") public String channelId = "";
    @SerializedName("team_id") public String teamId = "";
    /** May be null. */
    @SerializedName("omit_users") public Map<String, Boolean> omitUsers = new HashMap<String, Boolean>();
    @SerializedName("connection_id") public String connectionId = "";
    @SerializedName("omit_connection_id") public String omitConnectionId = "";
}
