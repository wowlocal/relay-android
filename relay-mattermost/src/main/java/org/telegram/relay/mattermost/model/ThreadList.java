package org.telegram.relay.mattermost.model;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

/** {@code GET /users/{id}/teams/{team_id}/threads}. */
public class ThreadList {
    @SerializedName("total") public long total;
    @SerializedName("total_unread_threads") public long totalUnreadThreads;
    @SerializedName("total_unread_mentions") public long totalUnreadMentions;
    @SerializedName("total_unread_urgent_mentions") public long totalUnreadUrgentMentions;
    @SerializedName("threads") public List<UserThread> threads = new ArrayList<UserThread>();
}
