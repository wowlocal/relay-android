package org.telegram.relay.mattermost.model;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** {@code Post.metadata}: files, reactions, embeds, custom emojis and image sizes in one response. */
public class PostMetadata {
    @SerializedName("embeds") public List<PostEmbed> embeds = new ArrayList<PostEmbed>();
    @SerializedName("emojis") public List<CustomEmoji> emojis = new ArrayList<CustomEmoji>();
    @SerializedName("files") public List<FileInfo> files = new ArrayList<FileInfo>();
    /** Image URL to dimensions, for images referenced by embeds and markdown. */
    @SerializedName("images") public Map<String, PostImage> images = new HashMap<String, PostImage>();
    @SerializedName("reactions") public List<Reaction> reactions = new ArrayList<Reaction>();
    /** {@code {"priority": "important"|"urgent", "requested_ack": bool}}; may be null. */
    @SerializedName("priority") public PostPriority priority;
    @SerializedName("acknowledgements") public List<PostAcknowledgement> acknowledgements = new ArrayList<PostAcknowledgement>();

    public static class PostPriority {
        @SerializedName("priority") public String priority = "";
        @SerializedName("requested_ack") public boolean requestedAck;
        @SerializedName("persistent_notifications") public boolean persistentNotifications;
    }

    public static class PostAcknowledgement {
        @SerializedName("user_id") public String userId = "";
        @SerializedName("post_id") public String postId = "";
        @SerializedName("acknowledged_at") public long acknowledgedAt;
    }
}
