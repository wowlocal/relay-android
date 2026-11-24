package org.telegram.relay.mattermost.model;

import com.google.gson.annotations.SerializedName;

import java.util.HashMap;
import java.util.Map;

/**
 * Read state and notification settings of one user in one channel.
 *
 * <p>Unread count = {@code channel.total_msg_count - msg_count} (use the {@code _root} variants with
 * collapsed reply threads); {@code mention_count} is the badge.
 */
public class ChannelMember {
    @SerializedName("channel_id") public String channelId = "";
    @SerializedName("user_id") public String userId = "";
    @SerializedName("roles") public String roles = "";
    /** Milliseconds; posts created at or before this are read. */
    @SerializedName("last_viewed_at") public long lastViewedAt;
    @SerializedName("msg_count") public long msgCount;
    @SerializedName("msg_count_root") public long msgCountRoot;
    @SerializedName("mention_count") public long mentionCount;
    @SerializedName("mention_count_root") public long mentionCountRoot;
    @SerializedName("urgent_mention_count") public long urgentMentionCount;
    /** {@code desktop}, {@code push}, {@code email}, {@code mark_unread} ({@code all}|{@code mention}), {@code ignore_channel_mentions}. */
    @SerializedName("notify_props") public Map<String, String> notifyProps = new HashMap<String, String>();
    @SerializedName("last_update_at") public long lastUpdateAt;
    @SerializedName("scheme_guest") public boolean schemeGuest;
    @SerializedName("scheme_user") public boolean schemeUser;
    @SerializedName("scheme_admin") public boolean schemeAdmin;
    @SerializedName("explicit_roles") public String explicitRoles = "";

    /** Muted channels have {@code mark_unread = mention}. */
    public boolean isMuted() {
        return notifyProps != null && "mention".equals(notifyProps.get("mark_unread"));
    }
}
