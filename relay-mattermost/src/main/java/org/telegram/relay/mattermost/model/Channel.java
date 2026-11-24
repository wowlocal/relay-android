package org.telegram.relay.mattermost.model;

import com.google.gson.JsonObject;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;

import org.telegram.relay.mattermost.Json;

/** {@code GET /channels/{id}}, {@code GET /users/{id}/teams/{team_id}/channels}. */
public class Channel {
    public static final String TYPE_OPEN = "O";
    public static final String TYPE_PRIVATE = "P";
    public static final String TYPE_DIRECT = "D";
    public static final String TYPE_GROUP = "G";

    @SerializedName("id") public String id = "";
    @SerializedName("create_at") public long createAt;
    @SerializedName("update_at") public long updateAt;
    /** Non-zero for archived channels. */
    @SerializedName("delete_at") public long deleteAt;
    /** Empty for DMs and GMs. */
    @SerializedName("team_id") public String teamId = "";
    /** {@link #TYPE_OPEN}, {@link #TYPE_PRIVATE}, {@link #TYPE_DIRECT} or {@link #TYPE_GROUP}. */
    @SerializedName("type") public String type = "";
    @SerializedName("display_name") public String displayName = "";
    /** URL name; {@code <userId>__<userId>} for DMs. */
    @SerializedName("name") public String name = "";
    @SerializedName("header") public String header = "";
    @SerializedName("purpose") public String purpose = "";
    @SerializedName("last_post_at") public long lastPostAt;
    @SerializedName("last_root_post_at") public long lastRootPostAt;
    @SerializedName("total_msg_count") public long totalMsgCount;
    @SerializedName("total_msg_count_root") public long totalMsgCountRoot;
    @SerializedName("extra_update_at") public long extraUpdateAt;
    @SerializedName("creator_id") public String creatorId = "";
    @SerializedName("scheme_id") public String schemeId = "";
    /** May be null. */
    @JsonAdapter(Json.NullableJsonObject.class) @SerializedName("props") public JsonObject props;
    @SerializedName("group_constrained") public Boolean groupConstrained;
    @SerializedName("shared") public Boolean shared;
    @SerializedName("policy_id") public String policyId = "";

    public boolean isArchived() {
        return deleteAt > 0;
    }

    public boolean isDirect() {
        return TYPE_DIRECT.equals(type);
    }

    public boolean isGroup() {
        return TYPE_GROUP.equals(type);
    }

    public boolean isPrivate() {
        return TYPE_PRIVATE.equals(type);
    }

    public boolean isOpen() {
        return TYPE_OPEN.equals(type);
    }

    /** The two user ids of a DM ({@code name = a__b}), or null for other channels. */
    public String[] getDirectMessageUserIds() {
        if (!isDirect() || name == null) {
            return null;
        }
        int separator = name.indexOf("__");
        if (separator <= 0 || separator + 2 >= name.length()) {
            return null;
        }
        return new String[]{name.substring(0, separator), name.substring(separator + 2)};
    }

    /** The other participant of a DM (the user's own id for a self-DM), or null. */
    public String getDirectMessageTeammateId(String currentUserId) {
        String[] ids = getDirectMessageUserIds();
        if (ids == null) {
            return null;
        }
        return ids[0].equals(currentUserId) ? ids[1] : ids[0];
    }

    @Override
    public String toString() {
        return "Channel(" + id + " " + type + " " + name + ")";
    }
}
