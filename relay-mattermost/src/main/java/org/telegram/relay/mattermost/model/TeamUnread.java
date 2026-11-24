package org.telegram.relay.mattermost.model;

import com.google.gson.annotations.SerializedName;

/** {@code GET /users/{id}/teams/unread}. */
public class TeamUnread {
    @SerializedName("team_id") public String teamId = "";
    @SerializedName("msg_count") public long msgCount;
    @SerializedName("mention_count") public long mentionCount;
    @SerializedName("msg_count_root") public long msgCountRoot;
    @SerializedName("mention_count_root") public long mentionCountRoot;
    @SerializedName("thread_count") public long threadCount;
    @SerializedName("thread_mention_count") public long threadMentionCount;
    @SerializedName("thread_urgent_mention_count") public long threadUrgentMentionCount;
}
