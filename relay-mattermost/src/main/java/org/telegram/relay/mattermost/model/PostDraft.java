package org.telegram.relay.mattermost.model;

import com.google.gson.JsonObject;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;

import org.telegram.relay.mattermost.Json;

import java.util.ArrayList;
import java.util.List;

/** The body of {@code POST /posts}. */
public class PostDraft {
    @SerializedName("channel_id") public String channelId = "";
    @SerializedName("message") public String message = "";
    /** Thread root id for a reply; empty for a root post. */
    @SerializedName("root_id") public String rootId = "";
    /** Ids from {@code POST /files} (at most 10 by default). */
    @SerializedName("file_ids") public List<String> fileIds = new ArrayList<String>();
    /** Echoed back in the response and the {@code posted} event; deduplicates retried sends. */
    @SerializedName("pending_post_id") public String pendingPostId = "";
    @JsonAdapter(Json.NullableJsonObject.class) @SerializedName("props") public JsonObject props;
    /** {@code {"priority": {"priority": "urgent"}}}; null to omit. */
    @JsonAdapter(Json.NullableJsonObject.class) @SerializedName("metadata") public JsonObject metadata;

    public PostDraft() {
    }

    public PostDraft(String channelId, String message) {
        this.channelId = channelId;
        this.message = message;
    }

    public PostDraft rootId(String rootId) {
        this.rootId = rootId;
        return this;
    }

    public PostDraft fileIds(List<String> fileIds) {
        this.fileIds = new ArrayList<String>(fileIds);
        return this;
    }

    public PostDraft pendingPostId(String pendingPostId) {
        this.pendingPostId = pendingPostId;
        return this;
    }
}
