package org.telegram.relay.mattermost;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;

import org.telegram.relay.mattermost.model.Channel;
import org.telegram.relay.mattermost.model.ChannelMember;
import org.telegram.relay.mattermost.model.FileInfo;
import org.telegram.relay.mattermost.model.Post;
import org.telegram.relay.mattermost.model.PostList;
import org.telegram.relay.mattermost.model.PostSearchResults;
import org.telegram.relay.mattermost.model.Preference;
import org.telegram.relay.mattermost.model.User;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finding things and membership (phase A7b): search with a time zone, file search, saved posts, member
 * lists with roles, user directory, channel browse, create/patch/archive/restore. Kept beside
 * {@link MattermostClient} (same package, same request path) so parallel work on the client does not collide.
 */
public final class MattermostDirectory {
    private MattermostDirectory() {
    }

    /** {@code POST /teams/{id}/files/search}: files and their order (newest first). */
    public static final class FileInfoList {
        @SerializedName("order") public List<String> order = new ArrayList<String>();
        @SerializedName("file_infos") public Map<String, FileInfo> fileInfos = new HashMap<String, FileInfo>();
        @SerializedName("next_file_info_id") public String nextFileInfoId = "";
        @SerializedName("prev_file_info_id") public String prevFileInfoId = "";

        public List<FileInfo> ordered() {
            List<FileInfo> result = new ArrayList<FileInfo>();
            if (order != null && fileInfos != null) {
                for (String id : order) {
                    FileInfo info = fileInfos.get(id);
                    if (info != null) {
                        result.add(info);
                    }
                }
            }
            return result;
        }
    }

    /** {@code /roles/names}. */
    public static final class Role {
        @SerializedName("id") public String id = "";
        @SerializedName("name") public String name = "";
        @SerializedName("permissions") public List<String> permissions = new ArrayList<String>();
    }

    // MARK: - Search

    /**
     * {@code POST /teams/{team_id}/posts/search} with the client's time zone (seconds east of UTC) for
     * {@code before:/after:/on:}.
     */
    public static PostSearchResults searchPosts(MattermostClient client, String teamId, String terms, boolean isOrSearch, int timeZoneOffset, int page, int perPage) throws MattermostException {
        return searchPosts(client, teamId, terms, isOrSearch, false, timeZoneOffset, page, perPage);
    }

    /** {@link #searchPosts} that also searches archived channels ({@code includeDeletedChannels}). */
    public static PostSearchResults searchPosts(MattermostClient client, String teamId, String terms, boolean isOrSearch, boolean includeDeletedChannels, int timeZoneOffset, int page, int perPage) throws MattermostException {
        JsonObject body = Json.object(
                "terms", terms,
                "is_or_search", isOrSearch,
                "include_deleted_channels", includeDeletedChannels,
                "time_zone_offset", timeZoneOffset,
                "page", page,
                "per_page", perPage);
        String path = teamId == null || teamId.isEmpty() ? "/posts/search" : UrlCodec.path("teams", teamId, "posts", "search");
        MattermostClient.Response response = client.execute(new MattermostClient.Request("POST", path).json(body));
        return Json.fromJson(response.text(), PostSearchResults.class);
    }

    /** {@code POST /teams/{team_id}/files/search} (same modifiers as the post search, plus {@code ext:}). */
    public static FileInfoList searchFiles(MattermostClient client, String teamId, String terms, int timeZoneOffset, int page, int perPage) throws MattermostException {
        JsonObject body = Json.object(
                "terms", terms,
                "is_or_search", false,
                "include_deleted_channels", true,
                "time_zone_offset", timeZoneOffset,
                "page", page,
                "per_page", perPage);
        MattermostClient.Response response = client.execute(new MattermostClient.Request("POST", UrlCodec.path("teams", teamId, "files", "search")).json(body));
        return Json.fromJson(response.text(), FileInfoList.class);
    }

    /** {@code POST /posts/ids}. */
    public static List<Post> postsByIds(MattermostClient client, List<String> ids) throws MattermostException {
        if (ids.isEmpty()) {
            return new ArrayList<Post>();
        }
        MattermostClient.Response response = client.execute(new MattermostClient.Request("POST", "/posts/ids").json(Json.stringArray(ids)));
        return Json.listFromJson(response.text(), Post.class);
    }

    /** {@code GET /users/me/posts/flagged} (all teams; {@code channelId} limits it to one channel). */
    public static PostList flaggedPosts(MattermostClient client, String channelId, int page, int perPage) throws MattermostException {
        QueryBuilder query = new QueryBuilder().add("page", page).add("per_page", perPage);
        if (channelId != null) {
            query.add("channel_id", channelId);
        }
        MattermostClient.Response response = client.execute(new MattermostClient.Request("GET", UrlCodec.path("users", MattermostClient.ME, "posts", "flagged")).query(query));
        return Json.fromJson(response.text(), PostList.class);
    }

    /** {@code POST /users/{id}/preferences/delete}. */
    public static void deletePreferences(MattermostClient client, String userId, List<Preference> preferences) throws MattermostException {
        client.execute(new MattermostClient.Request("POST", UrlCodec.path("users", userId, "preferences", "delete")).json(Json.GSON.toJsonTree(preferences)));
    }

    /** {@code PUT /users/{id}/preferences}. */
    public static void savePreferences(MattermostClient client, String userId, List<Preference> preferences) throws MattermostException {
        client.execute(new MattermostClient.Request("PUT", UrlCodec.path("users", userId, "preferences")).json(Json.GSON.toJsonTree(preferences)));
    }

    // MARK: - Members and users

    /** {@code GET /channels/{id}/members?page=&per_page=}: memberships with roles. */
    public static List<ChannelMember> channelMembers(MattermostClient client, String channelId, int page, int perPage) throws MattermostException {
        MattermostClient.Response response = client.execute(new MattermostClient.Request("GET", UrlCodec.path("channels", channelId, "members")).query(new QueryBuilder().add("page", page).add("per_page", perPage)));
        return Json.listFromJson(response.text(), ChannelMember.class);
    }

    /** {@code POST /channels/{id}/members/ids}. */
    public static List<ChannelMember> channelMembersByIds(MattermostClient client, String channelId, List<String> userIds) throws MattermostException {
        MattermostClient.Response response = client.execute(new MattermostClient.Request("POST", UrlCodec.path("channels", channelId, "members", "ids")).json(Json.stringArray(userIds)));
        return Json.listFromJson(response.text(), ChannelMember.class);
    }

    /** {@code PUT /channels/{id}/members/{user_id}/schemeRoles}: channel admin or member. */
    public static void setChannelAdmin(MattermostClient client, String channelId, String userId, boolean admin) throws MattermostException {
        client.execute(new MattermostClient.Request("PUT", UrlCodec.path("channels", channelId, "members", userId, "schemeRoles"))
                .json(Json.object("scheme_admin", admin, "scheme_user", true)));
    }

    /** {@code GET /users?in_team=&page=&per_page=} (active users of a team, by username). */
    public static List<User> usersInTeam(MattermostClient client, String teamId, int page, int perPage) throws MattermostException {
        MattermostClient.Response response = client.execute(new MattermostClient.Request("GET", "/users").query(new QueryBuilder().add("in_team", teamId).add("active", true).add("page", page).add("per_page", perPage)));
        return Json.listFromJson(response.text(), User.class);
    }

    /** {@code POST /users/search} with channel scopes ({@code inChannel}/{@code notInChannel} may be null). */
    public static List<User> searchUsers(MattermostClient client, String term, String teamId, String inChannel, String notInChannel, int limit) throws MattermostException {
        JsonObject body = Json.object("term", term, "team_id", teamId, "in_channel_id", inChannel, "not_in_channel_id", notInChannel,
                "allow_inactive", false, "limit", limit > 0 ? limit : null);
        MattermostClient.Response response = client.execute(new MattermostClient.Request("POST", "/users/search").json(body));
        return Json.listFromJson(response.text(), User.class);
    }

    /** {@code POST /roles/names}. */
    public static List<Role> rolesByNames(MattermostClient client, List<String> names) throws MattermostException {
        MattermostClient.Response response = client.execute(new MattermostClient.Request("POST", "/roles/names").json(Json.stringArray(names)));
        return Json.listFromJson(response.text(), Role.class);
    }

    // MARK: - Channels

    /** {@code GET /teams/{id}/channels}: public channels of a team (page), or the archived ones. */
    public static List<Channel> teamChannels(MattermostClient client, String teamId, boolean archived, int page, int perPage) throws MattermostException {
        String path = archived ? UrlCodec.path("teams", teamId, "channels", "deleted") : UrlCodec.path("teams", teamId, "channels");
        MattermostClient.Response response = client.execute(new MattermostClient.Request("GET", path).query(new QueryBuilder().add("page", page).add("per_page", perPage)));
        return Json.listFromJson(response.text(), Channel.class);
    }

    /** {@code POST /teams/{id}/channels/search} (public) or {@code …/search_archived}. */
    public static List<Channel> searchTeamChannels(MattermostClient client, String teamId, String term, boolean archived) throws MattermostException {
        String path = UrlCodec.path("teams", teamId, "channels", archived ? "search_archived" : "search");
        MattermostClient.Response response = client.execute(new MattermostClient.Request("POST", path).json(Json.object("term", term)));
        return Json.listFromJson(response.text(), Channel.class);
    }

    /** {@code PUT /channels/{id}/patch} ({@code display_name}, {@code header}, {@code purpose}, {@code name}). */
    public static Channel patchChannel(MattermostClient client, String channelId, JsonObject patch) throws MattermostException {
        MattermostClient.Response response = client.execute(new MattermostClient.Request("PUT", UrlCodec.path("channels", channelId, "patch")).json(patch));
        return Json.fromJson(response.text(), Channel.class);
    }

    /** {@code DELETE /channels/{id}}: archives the channel. */
    public static void archiveChannel(MattermostClient client, String channelId) throws MattermostException {
        client.execute(new MattermostClient.Request("DELETE", UrlCodec.path("channels", channelId)));
    }

    /** {@code POST /channels/{id}/restore}: unarchives the channel. */
    public static Channel restoreChannel(MattermostClient client, String channelId) throws MattermostException {
        MattermostClient.Response response = client.execute(new MattermostClient.Request("POST", UrlCodec.path("channels", channelId, "restore")));
        return Json.fromJson(response.text(), Channel.class);
    }

    /** {@code GET /users/me/channels?include_deleted=true}: the user's archived channels too. */
    public static List<Channel> myChannelsWithArchived(MattermostClient client) throws MattermostException {
        return client.allChannels(true, 0);
    }
}
