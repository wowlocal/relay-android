package org.telegram.relay.mattermost.model;

import com.google.gson.annotations.SerializedName;

/** {@code GET /channels/{id}/stats}. */
public class ChannelStats {
    @SerializedName("channel_id") public String channelId = "";
    @SerializedName("member_count") public long memberCount;
    @SerializedName("guest_count") public long guestCount;
    @SerializedName("pinnedpost_count") public long pinnedPostCount;
    @SerializedName("files_count") public long filesCount;
}
