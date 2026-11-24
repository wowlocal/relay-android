package org.telegram.relay.mattermost.model;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

/** A followed thread (collapsed reply threads): {@code GET /users/{id}/teams/{team}/threads}, WebSocket {@code thread_updated}. */
public class UserThread {
    /** The root post id. */
    @SerializedName("id") public String id = "";
    @SerializedName("reply_count") public long replyCount;
    @SerializedName("last_reply_at") public long lastReplyAt;
    @SerializedName("last_viewed_at") public long lastViewedAt;
    /** Users (only ids unless {@code extended=true}). */
    @SerializedName("participants") public List<User> participants = new ArrayList<User>();
    /** The root post; may be null. */
    @SerializedName("post") public Post post;
    @SerializedName("unread_replies") public long unreadReplies;
    @SerializedName("unread_mentions") public long unreadMentions;
    @SerializedName("is_urgent") public boolean isUrgent;
    @SerializedName("delete_at") public long deleteAt;
}
