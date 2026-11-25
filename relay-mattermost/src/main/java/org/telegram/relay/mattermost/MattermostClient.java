package org.telegram.relay.mattermost;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import org.telegram.relay.mattermost.model.AppError;
import org.telegram.relay.mattermost.model.Channel;
import org.telegram.relay.mattermost.model.ChannelMember;
import org.telegram.relay.mattermost.model.ChannelStats;
import org.telegram.relay.mattermost.model.ChannelViewResponse;
import org.telegram.relay.mattermost.model.ClientConfig;
import org.telegram.relay.mattermost.model.CustomEmoji;
import org.telegram.relay.mattermost.model.CustomStatus;
import org.telegram.relay.mattermost.model.FileInfo;
import org.telegram.relay.mattermost.model.FileUploadResponse;
import org.telegram.relay.mattermost.model.LoginResult;
import org.telegram.relay.mattermost.model.PingResponse;
import org.telegram.relay.mattermost.model.Post;
import org.telegram.relay.mattermost.model.PostDraft;
import org.telegram.relay.mattermost.model.PostList;
import org.telegram.relay.mattermost.model.PostSearchResults;
import org.telegram.relay.mattermost.model.Preference;
import org.telegram.relay.mattermost.model.Reaction;
import org.telegram.relay.mattermost.model.ServerVersion;
import org.telegram.relay.mattermost.model.Team;
import org.telegram.relay.mattermost.model.TeamMember;
import org.telegram.relay.mattermost.model.TeamUnread;
import org.telegram.relay.mattermost.model.ThreadList;
import org.telegram.relay.mattermost.model.User;
import org.telegram.relay.mattermost.model.UserStatus;
import org.telegram.relay.mattermost.model.UserThread;
import org.telegram.relay.mattermost.ws.MattermostWebSocket;
import org.telegram.relay.mattermost.ws.WebSocketListener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpRetryException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mattermost REST API v4 client.
 *
 * <p>All calls are synchronous and throw {@link MattermostException}; run them on a background
 * thread. The client is thread-safe; the session token can change at any time (login, logout).
 */
public class MattermostClient {
    /** The placeholder Mattermost accepts in paths instead of the current user's id. */
    public static final String ME = "me";

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Optional callbacks. */
    public interface Listener {
        /** The server answered 401 to a request sent with a token (expired or revoked session). */
        void onUnauthorized(MattermostClient client, MattermostException error);
    }

    private final String serverUrl;
    private volatile String token;
    private volatile String userAgent;
    private volatile int connectTimeoutMillis = 15_000;
    private volatile int readTimeoutMillis = 30_000;
    private volatile int uploadReadTimeoutMillis = 300_000;
    private volatile ConnectionFactory connectionFactory = ConnectionFactory.DEFAULT;
    private volatile Listener listener;
    private final Map<String, String> additionalHeaders = Collections.synchronizedMap(new LinkedHashMap<String, String>());

    /**
     * @param serverUrl the site URL, e.g. {@code https://chat.example.com} or
     *                  {@code https://example.com/mattermost}; {@code /api/v4} is appended.
     */
    public MattermostClient(String serverUrl) {
        this(serverUrl, null);
    }

    public MattermostClient(String serverUrl, String token) {
        String url = serverUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        this.serverUrl = url;
        this.token = token;
    }

    // MARK: - Configuration

    public String getServerUrl() {
        return serverUrl;
    }

    /** {@code <serverUrl>/api/v4}. */
    public String getApiBaseUrl() {
        return serverUrl + "/api/v4";
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public void setTimeouts(int connectTimeoutMillis, int readTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    public void setUploadReadTimeout(int millis) {
        this.uploadReadTimeoutMillis = millis;
    }

    public void setConnectionFactory(ConnectionFactory factory) {
        this.connectionFactory = factory != null ? factory : ConnectionFactory.DEFAULT;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** A header sent with every request. */
    public void setHeader(String name, String value) {
        if (value == null) {
            additionalHeaders.remove(name);
        } else {
            additionalHeaders.put(name, value);
        }
    }

    // MARK: - Server

    /** {@code GET /system/ping}; no token needed. Use to check that a URL points at a Mattermost server. */
    public PingResponse ping(boolean getServerStatus) throws MattermostException {
        Response response = execute(new Request("GET", "/system/ping").query(new QueryBuilder().flag("get_server_status", getServerStatus)).anonymous());
        JsonObject body = Json.parseObjectOrNull(response.text());
        if (body == null) {
            throw MattermostException.decoding("ping: not a Mattermost server", null);
        }
        return new PingResponse(body, response.header("X-Version-Id"));
    }

    /** {@code GET /config/client?format=old}. Logged-out callers get a limited subset. */
    public ClientConfig clientConfig() throws MattermostException {
        Response response = execute(new Request("GET", "/config/client").query(new QueryBuilder().add("format", "old")));
        Map<String, String> values = Json.fromJson(response.text(), new TypeToken<Map<String, String>>() { }.getType());
        return new ClientConfig(values);
    }

    /** {@code GET /license/client?format=old}. */
    public Map<String, String> clientLicense() throws MattermostException {
        Response response = execute(new Request("GET", "/license/client").query(new QueryBuilder().add("format", "old")));
        return Json.fromJson(response.text(), new TypeToken<Map<String, String>>() { }.getType());
    }

    /**
     * The server version from {@code X-Version-Id} of a ping, falling back to the client config's
     * {@code Version}; null when neither can be parsed.
     */
    public ServerVersion serverVersion() throws MattermostException {
        PingResponse ping = ping(false);
        ServerVersion version = ping.getServerVersion();
        if (version == null) {
            version = clientConfig().getServerVersion();
        }
        return version;
    }

    /** True when the server version is at least {@code minimum} (false when it can't be determined). */
    public boolean isServerVersionAtLeast(ServerVersion minimum) throws MattermostException {
        ServerVersion version = serverVersion();
        return version != null && version.isAtLeast(minimum);
    }

    // MARK: - Auth

    /**
     * {@code POST /users/login}. On success the client adopts the session token from the
     * {@code Token} response header.
     *
     * @param loginId  email or username (or LDAP id).
     * @param mfaToken the current MFA code, or null. See {@link MattermostException#isMfaRequired()}.
     * @param deviceId optional push device ({@code "<platform>:<token>"}), attached in the same request.
     */
    public LoginResult login(String loginId, String password, String mfaToken, String deviceId) throws MattermostException {
        JsonObject body = Json.object(
                "login_id", loginId,
                "password", password,
                "token", mfaToken != null && !mfaToken.isEmpty() ? mfaToken : null,
                "device_id", deviceId);
        Response response = execute(new Request("POST", "/users/login").json(body).anonymous());
        String sessionToken = response.header("Token");
        if (sessionToken == null || sessionToken.isEmpty()) {
            throw MattermostException.decoding("login response has no Token header", null);
        }
        User user = Json.fromJson(response.text(), User.class);
        setToken(sessionToken);
        return new LoginResult(user, sessionToken);
    }

    public LoginResult login(String loginId, String password) throws MattermostException {
        return login(loginId, password, null, null);
    }

    /** {@code POST /users/logout}. Clears the token locally even if the request fails. */
    public void logout() throws MattermostException {
        try {
            execute(new Request("POST", "/users/logout"));
        } finally {
            setToken(null);
        }
    }

    /** {@code PUT /users/sessions/device}: attaches a push device ({@code "<platform>:<token>"}) to the session. */
    public void attachDevice(String deviceId) throws MattermostException {
        execute(new Request("PUT", "/users/sessions/device").json(Json.object("device_id", deviceId)));
    }

    /** False only when the server definitely rejects the token ({@code GET /users/me} answers 401). */
    public boolean isTokenAccepted() {
        try {
            me();
            return true;
        } catch (MattermostException e) {
            return !e.isUnauthorized();
        }
    }

    // MARK: - Users

    /** {@code GET /users/me}. */
    public User me() throws MattermostException {
        return user(ME);
    }

    /** {@code GET /users/{id}}. */
    public User user(String userId) throws MattermostException {
        return get(UrlCodec.path("users", userId), User.class);
    }

    /** {@code GET /users/username/{username}}. */
    public User userByUsername(String username) throws MattermostException {
        return get(UrlCodec.path("users", "username", username), User.class);
    }

    /** {@code POST /users/ids}. */
    public List<User> users(List<String> userIds) throws MattermostException {
        return users(userIds, null);
    }

    /** {@code POST /users/ids?since=}: only users updated after {@code since} (ms). */
    public List<User> users(List<String> userIds, Long since) throws MattermostException {
        Response response = execute(new Request("POST", "/users/ids").query(new QueryBuilder().add("since", since)).json(Json.stringArray(userIds)));
        return Json.listFromJson(response.text(), User.class);
    }

    /** {@code POST /users/usernames}. */
    public List<User> usersByUsernames(List<String> usernames) throws MattermostException {
        Response response = execute(new Request("POST", "/users/usernames").json(Json.stringArray(usernames)));
        return Json.listFromJson(response.text(), User.class);
    }

    /** {@code POST /users/search}. */
    public List<User> searchUsers(String term, String teamId, int limit) throws MattermostException {
        JsonObject body = Json.object("term", term, "team_id", teamId, "limit", limit > 0 ? limit : null);
        Response response = execute(new Request("POST", "/users/search").json(body));
        return Json.listFromJson(response.text(), User.class);
    }

    /** {@code GET /users?in_channel=&page=&per_page=}: a page of a channel's members. */
    public List<User> usersInChannel(String channelId, int page, int perPage) throws MattermostException {
        Response response = execute(new Request("GET", "/users").query(new QueryBuilder().add("in_channel", channelId).add("page", page).add("per_page", perPage)));
        return Json.listFromJson(response.text(), User.class);
    }

    /** {@code GET /users/{id}/status}. */
    public UserStatus userStatus(String userId) throws MattermostException {
        return get(UrlCodec.path("users", userId, "status"), UserStatus.class);
    }

    /** {@code POST /users/status/ids}. */
    public List<UserStatus> userStatuses(List<String> userIds) throws MattermostException {
        Response response = execute(new Request("POST", "/users/status/ids").json(Json.stringArray(userIds)));
        return Json.listFromJson(response.text(), UserStatus.class);
    }

    /**
     * {@code PUT /users/{id}/status}. {@code userId} must be the real id.
     *
     * @param dndEndTime Unix seconds when a {@code dnd} status ends, or 0.
     */
    public UserStatus updateStatus(String userId, String status, long dndEndTime) throws MattermostException {
        JsonObject body = Json.object("user_id", userId, "status", status, "dnd_end_time", UserStatus.DND.equals(status) && dndEndTime > 0 ? dndEndTime : null);
        Response response = execute(new Request("PUT", UrlCodec.path("users", userId, "status")).json(body));
        return Json.fromJson(response.text(), UserStatus.class);
    }

    /** {@code PUT /users/me/status/custom}. */
    public void updateCustomStatus(CustomStatus status) throws MattermostException {
        // expires_at is a Go time on the server: "" is not a valid value, so empty fields are left out.
        JsonObject body = Json.object(
                "emoji", status.emoji,
                "text", status.text,
                "duration", status.duration != null && !status.duration.isEmpty() ? status.duration : null,
                "expires_at", status.expiresAt != null && !status.expiresAt.isEmpty() ? status.expiresAt : null);
        execute(new Request("PUT", UrlCodec.path("users", ME, "status", "custom")).json(body));
    }

    /** {@code DELETE /users/me/status/custom}. */
    public void removeCustomStatus() throws MattermostException {
        execute(new Request("DELETE", UrlCodec.path("users", ME, "status", "custom")));
    }

    /** {@code GET /users/{id}/image?_=lastPictureUpdate}: the profile picture (or the generated default). */
    public FileDownload profileImage(String userId, long lastPictureUpdate) throws MattermostException {
        QueryBuilder query = new QueryBuilder();
        if (lastPictureUpdate != 0) {
            query.add("_", lastPictureUpdate);
        }
        return download(new Request("GET", UrlCodec.path("users", userId, "image")).query(query).binary(), -1, -1);
    }

    /** The URL of a profile image, for image loaders that add the {@code Authorization} header themselves. */
    public String profileImageUrl(String userId, long lastPictureUpdate) {
        String url = getApiBaseUrl() + UrlCodec.path("users", userId, "image");
        return lastPictureUpdate != 0 ? url + "?_=" + lastPictureUpdate : url;
    }

    /** {@code POST /users/{user_id}/typing}: REST alternative to the WebSocket {@code user_typing} action. */
    public void publishTyping(String channelId, String parentId) throws MattermostException {
        execute(new Request("POST", UrlCodec.path("users", ME, "typing")).json(Json.object("channel_id", channelId, "parent_id", parentId != null ? parentId : "")));
    }

    /** {@code GET /users/me/preferences}. */
    public List<Preference> preferences() throws MattermostException {
        Response response = execute(new Request("GET", UrlCodec.path("users", ME, "preferences")));
        return Json.listFromJson(response.text(), Preference.class);
    }

    /** {@code PUT /users/me/preferences}. */
    public void savePreferences(List<Preference> preferences) throws MattermostException {
        execute(new Request("PUT", UrlCodec.path("users", ME, "preferences")).json(Json.GSON.toJsonTree(preferences)));
    }

    // MARK: - Teams

    /** {@code GET /users/me/teams}. */
    public List<Team> teams() throws MattermostException {
        Response response = execute(new Request("GET", UrlCodec.path("users", ME, "teams")));
        return Json.listFromJson(response.text(), Team.class);
    }

    /** {@code GET /teams/{id}}. */
    public Team team(String teamId) throws MattermostException {
        return get(UrlCodec.path("teams", teamId), Team.class);
    }

    /** {@code GET /users/me/teams/members}. */
    public List<TeamMember> teamMembers() throws MattermostException {
        Response response = execute(new Request("GET", UrlCodec.path("users", ME, "teams", "members")));
        return Json.listFromJson(response.text(), TeamMember.class);
    }

    /** {@code GET /users/me/teams/unread}. */
    public List<TeamUnread> teamsUnread(boolean includeCollapsedThreads) throws MattermostException {
        Response response = execute(new Request("GET", UrlCodec.path("users", ME, "teams", "unread")).query(new QueryBuilder().flag("include_collapsed_threads", includeCollapsedThreads)));
        return Json.listFromJson(response.text(), TeamUnread.class);
    }

    // MARK: - Channels

    /** {@code GET /users/me/teams/{team_id}/channels}: the user's channels in a team, including DMs and GMs. */
    public List<Channel> channels(String teamId, boolean includeDeleted) throws MattermostException {
        Response response = execute(new Request("GET", UrlCodec.path("users", ME, "teams", teamId, "channels")).query(new QueryBuilder().flag("include_deleted", includeDeleted)));
        return Json.listFromJson(response.text(), Channel.class);
    }

    /**
     * {@code GET /users/me/channels}: the user's channels across all teams, DMs and GMs once each.
     *
     * @param lastDeleteAt with {@code includeDeleted}, only archived channels deleted after this time (ms); 0 for all.
     */
    public List<Channel> allChannels(boolean includeDeleted, long lastDeleteAt) throws MattermostException {
        QueryBuilder query = new QueryBuilder().flag("include_deleted", includeDeleted);
        if (lastDeleteAt > 0) {
            query.add("last_delete_at", lastDeleteAt);
        }
        Response response = execute(new Request("GET", UrlCodec.path("users", ME, "channels")).query(query));
        return Json.listFromJson(response.text(), Channel.class);
    }

    /** {@code GET /users/me/teams/{team_id}/channels/members}: read state of every channel of a team. */
    public List<ChannelMember> channelMembers(String teamId) throws MattermostException {
        Response response = execute(new Request("GET", UrlCodec.path("users", ME, "teams", teamId, "channels", "members")));
        return Json.listFromJson(response.text(), ChannelMember.class);
    }

    /** {@code GET /users/me/channel_members?page=&per_page=}: read state across all teams, one page. */
    public List<ChannelMember> allChannelMembers(int page, int perPage) throws MattermostException {
        Response response = execute(new Request("GET", UrlCodec.path("users", ME, "channel_members")).query(new QueryBuilder().add("page", page).add("per_page", perPage)));
        return Json.listFromJson(response.text(), ChannelMember.class);
    }

    /** Every page of {@link #allChannelMembers(int, int)}. */
    public List<ChannelMember> allChannelMembers() throws MattermostException {
        List<ChannelMember> result = new ArrayList<ChannelMember>();
        int perPage = 200;
        for (int page = 0; page < 1000; page++) {
            List<ChannelMember> chunk = allChannelMembers(page, perPage);
            result.addAll(chunk);
            if (chunk.size() < perPage) {
                break;
            }
        }
        return result;
    }

    /** {@code GET /channels/{id}}. */
    public Channel channel(String channelId) throws MattermostException {
        return get(UrlCodec.path("channels", channelId), Channel.class);
    }

    /** {@code GET /teams/{team_id}/channels/name/{name}}. */
    public Channel channelByName(String teamId, String name, boolean includeDeleted) throws MattermostException {
        Response response = execute(new Request("GET", UrlCodec.path("teams", teamId, "channels", "name", name)).query(new QueryBuilder().flag("include_deleted", includeDeleted)));
        return Json.fromJson(response.text(), Channel.class);
    }

    /** {@code GET /channels/{channel_id}/members/{user_id}}. */
    public ChannelMember channelMember(String channelId, String userId) throws MattermostException {
        return get(UrlCodec.path("channels", channelId, "members", userId), ChannelMember.class);
    }

    /** {@code GET /channels/{channel_id}/members/me}. */
    public ChannelMember channelMember(String channelId) throws MattermostException {
        return channelMember(channelId, ME);
    }

    /** {@code GET /channels/{id}/stats}. */
    public ChannelStats channelStats(String channelId) throws MattermostException {
        return get(UrlCodec.path("channels", channelId, "stats"), ChannelStats.class);
    }

    /** {@code POST /channels/stats/member_count}: member counts by channel id (also public channels the user is not in, A7a). */
    public java.util.Map<String, Long> channelMemberCounts(List<String> channelIds) throws MattermostException {
        Response response = execute(new Request("POST", "/channels/stats/member_count").json(Json.stringArray(channelIds)));
        java.util.Map<String, Long> counts = new java.util.HashMap<String, Long>();
        JsonObject object = Json.parseObjectOrNull(response.text());
        if (object != null) {
            for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry : object.entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    counts.put(entry.getKey(), entry.getValue().getAsLong());
                }
            }
        }
        return counts;
    }

    /** {@code POST /channels/direct}. Pass the same id twice for a self-DM. Returns the existing DM if there is one. */
    public Channel createDirectChannel(String userId, String otherUserId) throws MattermostException {
        Response response = execute(new Request("POST", "/channels/direct").json(Json.stringArray(Arrays.asList(userId, otherUserId))));
        return Json.fromJson(response.text(), Channel.class);
    }

    /** {@code POST /channels/group}: a GM. Include the current user's id. */
    public Channel createGroupChannel(List<String> userIds) throws MattermostException {
        Response response = execute(new Request("POST", "/channels/group").json(Json.stringArray(userIds)));
        return Json.fromJson(response.text(), Channel.class);
    }

    /** {@code POST /channels}: a public ({@code O}) or private ({@code P}) channel. */
    public Channel createChannel(String teamId, String name, String displayName, String type, String purpose, String header) throws MattermostException {
        JsonObject body = Json.object("team_id", teamId, "name", name, "display_name", displayName, "type", type, "purpose", purpose, "header", header);
        Response response = execute(new Request("POST", "/channels").json(body));
        return Json.fromJson(response.text(), Channel.class);
    }

    /** {@code POST /channels/{id}/members}: joins a channel (own id) or adds a user. */
    public ChannelMember addChannelMember(String channelId, String userId) throws MattermostException {
        Response response = execute(new Request("POST", UrlCodec.path("channels", channelId, "members")).json(Json.object("user_id", userId)));
        return Json.fromJson(response.text(), ChannelMember.class);
    }

    /** {@code DELETE /channels/{id}/members/{user_id}}: leaves a channel or removes a user. */
    public void removeChannelMember(String channelId, String userId) throws MattermostException {
        execute(new Request("DELETE", UrlCodec.path("channels", channelId, "members", userId)));
    }

    /**
     * {@code POST /channels/members/me/view}: marks a channel read (updates {@code last_viewed_at}).
     *
     * @param previousChannelId the channel viewed before, or null.
     */
    public ChannelViewResponse viewChannel(String channelId, String previousChannelId, boolean collapsedThreadsSupported) throws MattermostException {
        JsonObject body = Json.object(
                "channel_id", channelId,
                "prev_channel_id", previousChannelId != null ? previousChannelId : "",
                "collapsed_threads_supported", collapsedThreadsSupported);
        Response response = execute(new Request("POST", UrlCodec.path("channels", "members", ME, "view")).json(body));
        return Json.fromJson(response.text(), ChannelViewResponse.class);
    }

    public ChannelViewResponse viewChannel(String channelId) throws MattermostException {
        return viewChannel(channelId, null, true);
    }

    /** {@code PUT /channels/{id}/members/me/notify_props}: merges props ({@code mark_unread=mention} mutes). */
    public void updateChannelNotifyProps(String channelId, Map<String, String> props) throws MattermostException {
        execute(new Request("PUT", UrlCodec.path("channels", channelId, "members", ME, "notify_props")).json(Json.GSON.toJsonTree(props)));
    }

    // MARK: - Posts

    /** {@code GET /channels/{id}/posts}. */
    public PostList posts(String channelId, PostsQuery query) throws MattermostException {
        Response response = execute(new Request("GET", UrlCodec.path("channels", channelId, "posts")).query(query.toQuery()));
        return Json.fromJson(response.text(), PostList.class);
    }

    /** {@code GET /users/me/channels/{id}/posts/unread}: the posts around the first unread one. */
    public PostList postsAroundUnread(String channelId, int limitBefore, int limitAfter, boolean collapsedThreads) throws MattermostException {
        QueryBuilder query = new QueryBuilder().add("limit_before", limitBefore).add("limit_after", limitAfter).flag("collapsedThreads", collapsedThreads);
        Response response = execute(new Request("GET", UrlCodec.path("users", ME, "channels", channelId, "posts", "unread")).query(query));
        return Json.fromJson(response.text(), PostList.class);
    }

    /** {@code GET /posts/{id}}. */
    public Post post(String postId, boolean includeDeleted) throws MattermostException {
        Response response = execute(new Request("GET", UrlCodec.path("posts", postId)).query(new QueryBuilder().flag("include_deleted", includeDeleted)));
        return Json.fromJson(response.text(), Post.class);
    }

    public Post post(String postId) throws MattermostException {
        return post(postId, false);
    }

    /** {@code GET /posts/{id}/thread}: the root post and its replies. */
    public PostList postThread(String postId, ThreadQuery query) throws MattermostException {
        Response response = execute(new Request("GET", UrlCodec.path("posts", postId, "thread")).query(query != null ? query.toQuery() : new QueryBuilder()));
        return Json.fromJson(response.text(), PostList.class);
    }

    /** {@code GET /channels/{id}/pinned}. */
    public PostList pinnedPosts(String channelId) throws MattermostException {
        return get(UrlCodec.path("channels", channelId, "pinned"), PostList.class);
    }

    /** {@code GET /posts/{id}/files/info}. */
    public List<FileInfo> postFiles(String postId) throws MattermostException {
        Response response = execute(new Request("GET", UrlCodec.path("posts", postId, "files", "info")));
        return Json.listFromJson(response.text(), FileInfo.class);
    }

    /**
     * {@code POST /posts}. Fills {@code pending_post_id} when empty.
     *
     * @param setOnline null for the server default; false for replies sent in the background.
     */
    public Post createPost(PostDraft draft, Boolean setOnline) throws MattermostException {
        if (draft.pendingPostId == null || draft.pendingPostId.isEmpty()) {
            draft.pendingPostId = UUID.randomUUID().toString().replace("-", "");
        }
        Response response = execute(new Request("POST", "/posts").query(new QueryBuilder().add("set_online", setOnline)).json(Json.GSON.toJsonTree(draft)));
        return Json.fromJson(response.text(), Post.class);
    }

    public Post createPost(PostDraft draft) throws MattermostException {
        return createPost(draft, null);
    }

    /** {@code PUT /posts/{id}/patch}: changes only the given fields ({@code message}, {@code file_ids}, {@code props}, {@code is_pinned}…). */
    public Post patchPost(String postId, JsonObject patch) throws MattermostException {
        Response response = execute(new Request("PUT", UrlCodec.path("posts", postId, "patch")).json(patch));
        return Json.fromJson(response.text(), Post.class);
    }

    /** Edits a post's text ({@code PUT /posts/{id}/patch} with {@code message}). */
    public Post editPost(String postId, String message) throws MattermostException {
        return patchPost(postId, Json.object("message", message));
    }

    /** {@code PUT /posts/{id}}: replaces the editable fields (the server needs {@code id} and {@code message}). */
    public Post updatePost(Post post) throws MattermostException {
        JsonObject body = Json.object("id", post.id, "message", post.message, "file_ids", Json.stringArray(post.fileIds != null ? post.fileIds : new ArrayList<String>()), "props", post.props, "is_pinned", post.isPinned);
        Response response = execute(new Request("PUT", UrlCodec.path("posts", post.id)).json(body));
        return Json.fromJson(response.text(), Post.class);
    }

    /** {@code DELETE /posts/{id}}. */
    public void deletePost(String postId) throws MattermostException {
        execute(new Request("DELETE", UrlCodec.path("posts", postId)));
    }

    /** {@code POST /posts/{id}/pin}. */
    public void pinPost(String postId) throws MattermostException {
        execute(new Request("POST", UrlCodec.path("posts", postId, "pin")));
    }

    /** {@code POST /posts/{id}/unpin}. */
    public void unpinPost(String postId) throws MattermostException {
        execute(new Request("POST", UrlCodec.path("posts", postId, "unpin")));
    }

    /** {@code POST /users/me/posts/{post_id}/set_unread}: marks the channel unread from this post. */
    public JsonObject markPostUnread(String postId, boolean collapsedThreadsSupported) throws MattermostException {
        Response response = execute(new Request("POST", UrlCodec.path("users", ME, "posts", postId, "set_unread")).json(Json.object("collapsed_threads_supported", collapsedThreadsSupported)));
        JsonObject result = Json.parseObjectOrNull(response.text());
        return result != null ? result : new JsonObject();
    }

    /**
     * {@code POST /teams/{team_id}/posts/search} (empty {@code teamId} searches all teams on 10.x+).
     *
     * @param terms Mattermost search syntax ({@code from:}, {@code in:}, quotes, {@code -exclusion}).
     */
    public PostSearchResults searchPosts(String teamId, String terms, boolean isOrSearch, int page, int perPage) throws MattermostException {
        JsonObject body = Json.object(
                "terms", terms,
                "is_or_search", isOrSearch,
                "include_deleted_channels", false,
                "time_zone_offset", 0,
                "page", page,
                "per_page", perPage);
        String path = teamId == null || teamId.isEmpty() ? "/posts/search" : UrlCodec.path("teams", teamId, "posts", "search");
        Response response = execute(new Request("POST", path).json(body));
        return Json.fromJson(response.text(), PostSearchResults.class);
    }

    /** {@code GET /users/me/posts/flagged}: saved posts. */
    public PostList flaggedPosts(int page, int perPage) throws MattermostException {
        Response response = execute(new Request("GET", UrlCodec.path("users", ME, "posts", "flagged")).query(new QueryBuilder().add("page", page).add("per_page", perPage)));
        return Json.fromJson(response.text(), PostList.class);
    }

    // MARK: - Reactions

    /** {@code POST /reactions}. {@code userId} must be the real id ({@code me} is not accepted in the body). */
    public Reaction addReaction(String userId, String postId, String emojiName) throws MattermostException {
        Response response = execute(new Request("POST", "/reactions").json(Json.GSON.toJsonTree(new Reaction(userId, postId, emojiName))));
        return Json.fromJson(response.text(), Reaction.class);
    }

    /** {@code DELETE /users/me/posts/{post_id}/reactions/{emoji_name}}. */
    public void removeReaction(String postId, String emojiName) throws MattermostException {
        execute(new Request("DELETE", UrlCodec.path("users", ME, "posts", postId, "reactions", emojiName)));
    }

    /** {@code GET /posts/{id}/reactions}. */
    public List<Reaction> reactions(String postId) throws MattermostException {
        Response response = execute(new Request("GET", UrlCodec.path("posts", postId, "reactions")));
        return Json.listFromJson(response.text(), Reaction.class);
    }

    // MARK: - Threads (collapsed reply threads)

    /** {@code GET /users/me/teams/{team_id}/threads}. */
    public ThreadList userThreads(String teamId, UserThreadsQuery query) throws MattermostException {
        Response response = execute(new Request("GET", UrlCodec.path("users", ME, "teams", teamId, "threads")).query(query != null ? query.toQuery() : new QueryBuilder()));
        return Json.fromJson(response.text(), ThreadList.class);
    }

    /** {@code GET /users/me/teams/{team_id}/threads/{thread_id}}. */
    public UserThread userThread(String teamId, String threadId, boolean extended) throws MattermostException {
        Response response = execute(new Request("GET", UrlCodec.path("users", ME, "teams", teamId, "threads", threadId)).query(new QueryBuilder().flag("extended", extended)));
        return Json.fromJson(response.text(), UserThread.class);
    }

    /** {@code PUT /users/me/teams/{team_id}/threads/{thread_id}/read/{timestamp}}. */
    public void markThreadRead(String teamId, String threadId, long timestamp) throws MattermostException {
        execute(new Request("PUT", UrlCodec.path("users", ME, "teams", teamId, "threads", threadId, "read", String.valueOf(timestamp))));
    }

    /** {@code PUT|DELETE /users/me/teams/{team_id}/threads/{thread_id}/following}. */
    public void setThreadFollowing(String teamId, String threadId, boolean following) throws MattermostException {
        execute(new Request(following ? "PUT" : "DELETE", UrlCodec.path("users", ME, "teams", teamId, "threads", threadId, "following")));
    }

    // MARK: - Files

    /**
     * {@code POST /files} (multipart): uploads files to attach to a post in {@code channelId}.
     * The body is streamed; with known sizes it is sent with a fixed length, otherwise chunked.
     */
    public FileUploadResponse uploadFiles(String channelId, List<FileUpload> files, UploadProgressListener progress) throws MattermostException {
        String boundary = "RelayBoundary" + UUID.randomUUID().toString().replace("-", "");
        Multipart multipart = new Multipart(boundary);
        // The server reads parts in order: channel_id first, each client_id before its file.
        multipart.field("channel_id", channelId);
        for (FileUpload file : files) {
            multipart.field("client_ids", file.clientId);
            multipart.file("files", file);
        }
        Request request = new Request("POST", "/files");
        request.multipart = multipart;
        request.uploadProgress = progress;
        request.readTimeout = uploadReadTimeoutMillis;
        Response response = execute(request);
        return Json.fromJson(response.text(), FileUploadResponse.class);
    }

    public FileUploadResponse uploadFile(String channelId, FileUpload file, UploadProgressListener progress) throws MattermostException {
        return uploadFiles(channelId, Collections.singletonList(file), progress);
    }

    /** {@code GET /files/{id}/info}. */
    public FileInfo fileInfo(String fileId) throws MattermostException {
        return get(UrlCodec.path("files", fileId, "info"), FileInfo.class);
    }

    /** {@code GET /files/{id}}: the whole file. */
    public FileDownload downloadFile(String fileId) throws MattermostException {
        return download(new Request("GET", UrlCodec.path("files", fileId)).binary(), -1, -1);
    }

    /**
     * {@code GET /files/{id}} with {@code Range: bytes=start-end}.
     *
     * @param end inclusive last byte, or -1 for "to the end".
     */
    public FileDownload downloadFile(String fileId, long start, long end) throws MattermostException {
        return download(new Request("GET", UrlCodec.path("files", fileId)).binary(), start, end);
    }

    /** {@code GET /files/{id}/thumbnail}. */
    public FileDownload fileThumbnail(String fileId) throws MattermostException {
        return download(new Request("GET", UrlCodec.path("files", fileId, "thumbnail")).binary(), -1, -1);
    }

    /** {@code GET /files/{id}/preview}. */
    public FileDownload filePreview(String fileId) throws MattermostException {
        return download(new Request("GET", UrlCodec.path("files", fileId, "preview")).binary(), -1, -1);
    }

    /** The URL of a file, for loaders that add the {@code Authorization} header themselves. */
    public String fileUrl(String fileId) {
        return getApiBaseUrl() + UrlCodec.path("files", fileId);
    }

    // MARK: - Custom emoji

    /** {@code GET /emoji?page=&per_page=&sort=name}. */
    public List<CustomEmoji> customEmojis(int page, int perPage, String sort) throws MattermostException {
        Response response = execute(new Request("GET", "/emoji").query(new QueryBuilder().add("page", page).add("per_page", perPage).add("sort", sort)));
        return Json.listFromJson(response.text(), CustomEmoji.class);
    }

    /** {@code GET /emoji/name/{name}}. */
    public CustomEmoji customEmojiByName(String name) throws MattermostException {
        return get(UrlCodec.path("emoji", "name", name), CustomEmoji.class);
    }

    /** {@code GET /emoji/{id}/image}. */
    public FileDownload customEmojiImage(String emojiId) throws MattermostException {
        return download(new Request("GET", UrlCodec.path("emoji", emojiId, "image")).binary(), -1, -1);
    }

    // MARK: - A7c: composer, profile, notifications

    /** {@code GET /emoji/autocomplete?name=}: custom emoji whose name starts with {@code name}. */
    public List<CustomEmoji> autocompleteEmoji(String name) throws MattermostException {
        Response response = execute(new Request("GET", "/emoji/autocomplete").query(new QueryBuilder().add("name", name)));
        return Json.listFromJson(response.text(), CustomEmoji.class);
    }

    /**
     * {@code GET /users/autocomplete?in_team=&in_channel=&name=&limit=}: {@code {users: [...], out_of_channel: [...]}}
     * (with {@code in_channel}); without a channel only {@code users}.
     */
    public JsonObject autocompleteUsers(String teamId, String channelId, String name, int limit) throws MattermostException {
        QueryBuilder query = new QueryBuilder().add("name", name).add("limit", limit > 0 ? limit : 25);
        if (teamId != null && !teamId.isEmpty()) {
            query.add("in_team", teamId);
        }
        if (channelId != null && !channelId.isEmpty()) {
            query.add("in_channel", channelId);
        }
        Response response = execute(new Request("GET", "/users/autocomplete").query(query));
        JsonObject object = Json.parseObjectOrNull(response.text());
        return object != null ? object : new JsonObject();
    }

    /** {@code GET /teams/{id}/channels/autocomplete?name=}: public and private channels of a team matching a prefix. */
    public List<Channel> autocompleteChannels(String teamId, String name) throws MattermostException {
        Response response = execute(new Request("GET", UrlCodec.path("teams", teamId, "channels", "autocomplete")).query(new QueryBuilder().add("name", name)));
        return Json.listFromJson(response.text(), Channel.class);
    }

    /**
     * {@code GET /teams/{id}/commands/autocomplete_suggestions?user_input=&channel_id=&root_id=}: the composer's slash
     * suggestions, each {@code {Complete, Suggestion, Hint, Description, IconData}}.
     */
    public JsonArray commandSuggestions(String teamId, String userInput, String channelId, String rootId) throws MattermostException {
        QueryBuilder query = new QueryBuilder().add("user_input", userInput).add("channel_id", channelId);
        if (rootId != null && !rootId.isEmpty()) {
            query.add("root_id", rootId);
        }
        Response response = execute(new Request("GET", UrlCodec.path("teams", teamId, "commands", "autocomplete_suggestions")).query(query));
        JsonElement element = Json.parse(response.text());
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    /** {@code POST /commands/execute}: runs a slash command; the answer is the command response. */
    public JsonObject executeCommand(String channelId, String teamId, String command, String rootId, String parentId) throws MattermostException {
        JsonObject body = Json.object("channel_id", channelId, "team_id", teamId, "command", command,
                "root_id", rootId != null ? rootId : "", "parent_id", parentId != null ? parentId : "");
        Response response = execute(new Request("POST", "/commands/execute").json(body));
        JsonObject object = Json.parseObjectOrNull(response.text());
        return object != null ? object : new JsonObject();
    }

    /** {@code GET /channels/{id}/timezones}: the distinct time zones of a channel's members. */
    public List<String> channelTimezones(String channelId) throws MattermostException {
        Response response = execute(new Request("GET", UrlCodec.path("channels", channelId, "timezones")));
        List<String> result = new ArrayList<String>();
        JsonElement element = Json.parse(response.text());
        if (element != null && element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (item.isJsonPrimitive() && !item.getAsString().isEmpty()) {
                    result.add(item.getAsString());
                }
            }
        }
        return result;
    }

    /** {@code PUT /users/me/patch}: changes only the given fields (names, nickname, position, notify_props, timezone…). */
    public User patchMe(JsonObject patch) throws MattermostException {
        Response response = execute(new Request("PUT", UrlCodec.path("users", ME, "patch")).json(patch));
        return Json.fromJson(response.text(), User.class);
    }

    /** {@code POST /users/{id}/image} (multipart field {@code image}): a new profile picture. */
    public void setProfileImage(String userId, FileUpload image) throws MattermostException {
        String boundary = "RelayBoundary" + UUID.randomUUID().toString().replace("-", "");
        Multipart multipart = new Multipart(boundary);
        multipart.file("image", image);
        Request request = new Request("POST", UrlCodec.path("users", userId, "image"));
        request.multipart = multipart;
        request.readTimeout = uploadReadTimeoutMillis;
        execute(request);
    }

    /** {@code DELETE /users/{id}/image}: back to the generated default picture. */
    public void deleteProfileImage(String userId) throws MattermostException {
        execute(new Request("DELETE", UrlCodec.path("users", userId, "image")));
    }

    // MARK: - WebSocket

    /** A WebSocket for this server with the current token and user agent; call {@code connect()} on it. */
    public MattermostWebSocket createWebSocket(WebSocketListener listener) {
        return createWebSocket(new MattermostWebSocket.Configuration(), listener);
    }

    public MattermostWebSocket createWebSocket(MattermostWebSocket.Configuration configuration, WebSocketListener listener) {
        if (configuration.userAgent == null) {
            configuration.userAgent = userAgent;
        }
        if (configuration.tokenVerifier == null) {
            configuration.tokenVerifier = new MattermostWebSocket.TokenVerifier() {
                @Override
                public boolean isTokenAccepted() {
                    return MattermostClient.this.isTokenAccepted();
                }
            };
        }
        return new MattermostWebSocket(serverUrl, token, configuration, listener);
    }

    // MARK: - Plugins

    /** The answer of a plugin route: any HTTP status (only transport failures throw), body as text. */
    public static final class PluginResponse {
        public final int statusCode;
        public final String body;

        public PluginResponse(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        public boolean isSuccessful() {
            return statusCode >= 200 && statusCode < 300;
        }
    }

    /**
     * {@code <method> <serverUrl>/plugins/<pluginId>/<path>} with the session token, an optional JSON body.
     * Non-2xx answers are returned, not thrown (plugins put useful JSON in error bodies, e.g. a {@code 409} with
     * the conflicting call); a {@code 401} still reaches the {@link Listener}.
     */
    public PluginResponse plugin(String method, String pluginId, String path, JsonElement body) throws MattermostException {
        Request request = new Request(method, "/plugins/" + pluginId + "/" + path);
        request.serverRoot = true;
        if (body != null) {
            request.json(body);
        }
        HttpURLConnection connection = open(request);
        try {
            int status;
            try {
                writeBody(connection, request);
                status = connection.getResponseCode();
            } catch (SocketTimeoutException e) {
                throw MattermostException.transport("timeout: " + method + " " + request.path, e);
            } catch (IOException e) {
                throw MattermostException.transport(method + " " + request.path + ": " + e.getMessage(), e);
            }
            byte[] bytes = new byte[0];
            try {
                InputStream in = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
                if (in != null) {
                    bytes = readFully(in);
                }
            } catch (IOException e) {
                if (status >= 200 && status < 300) {
                    throw MattermostException.transport("reading " + request.path + ": " + e.getMessage(), e);
                }
            }
            if (status == 401 && token != null) {
                Listener current = listener;
                if (current != null) {
                    current.onUnauthorized(this, errorFromResponse(status, bytes, null, null));
                }
            }
            return new PluginResponse(status, new String(bytes, UTF8));
        } finally {
            connection.disconnect();
        }
    }

    // MARK: - Request plumbing

    private <T> T get(String path, Class<T> type) throws MattermostException {
        Response response = execute(new Request("GET", path));
        return Json.fromJson(response.text(), type);
    }

    /** A request relative to {@code /api/v4}. */
    static final class Request {
        final String method;
        final String path;
        QueryBuilder query;
        byte[] body;
        String contentType;
        boolean authenticated = true;
        boolean expectsJson = true;
        Multipart multipart;
        UploadProgressListener uploadProgress;
        int readTimeout = -1;
        /** {@link #path} is relative to the server URL (plugin routes), not to {@code /api/v4}. */
        boolean serverRoot;
        final Map<String, String> headers = new LinkedHashMap<String, String>();

        Request(String method, String path) {
            this.method = method;
            this.path = path;
        }

        Request query(QueryBuilder query) {
            this.query = query;
            return this;
        }

        Request json(JsonElement json) {
            this.body = Json.GSON.toJson(json).getBytes(UTF8);
            this.contentType = "application/json";
            return this;
        }

        Request anonymous() {
            this.authenticated = false;
            return this;
        }

        Request binary() {
            this.expectsJson = false;
            return this;
        }
    }

    static final class Response {
        final int statusCode;
        final byte[] body;
        final Map<String, List<String>> headers;

        Response(int statusCode, byte[] body, Map<String, List<String>> headers) {
            this.statusCode = statusCode;
            this.body = body;
            this.headers = headers;
        }

        String text() {
            return new String(body, UTF8);
        }

        String header(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                    return entry.getValue().get(0);
                }
            }
            return null;
        }
    }

    URL url(Request request) throws MattermostException {
        String url = (request.serverRoot ? serverUrl : getApiBaseUrl()) + request.path;
        if (request.query != null && !request.query.isEmpty()) {
            url += "?" + request.query.encode();
        }
        try {
            return new URL(url);
        } catch (MalformedURLException e) {
            throw MattermostException.transport("bad URL " + url, e);
        }
    }

    private HttpURLConnection open(Request request) throws MattermostException {
        URL url = url(request);
        HttpURLConnection connection;
        try {
            connection = connectionFactory.open(url);
            connection.setRequestMethod(request.method);
        } catch (IOException e) {
            throw MattermostException.transport("cannot open " + url + ": " + e.getMessage(), e);
        }
        connection.setConnectTimeout(connectTimeoutMillis);
        connection.setReadTimeout(request.readTimeout > 0 ? request.readTimeout : readTimeoutMillis);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(true);
        synchronized (additionalHeaders) {
            for (Map.Entry<String, String> header : additionalHeaders.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
        }
        // Bearer token only: cookies from the login response would trigger CSRF checks.
        connection.setRequestProperty("X-Requested-With", "XMLHttpRequest");
        if (userAgent != null) {
            connection.setRequestProperty("User-Agent", userAgent);
        }
        String currentToken = token;
        if (request.authenticated && currentToken != null && !currentToken.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + currentToken);
        }
        if (request.expectsJson) {
            connection.setRequestProperty("Accept", "application/json");
        }
        for (Map.Entry<String, String> header : request.headers.entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
        return connection;
    }

    private void writeBody(HttpURLConnection connection, Request request) throws IOException {
        if (request.multipart != null) {
            Multipart multipart = request.multipart;
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", multipart.contentType());
            long length = multipart.length();
            if (length >= 0) {
                connection.setFixedLengthStreamingMode(length);
            } else {
                connection.setChunkedStreamingMode(64 * 1024);
            }
            OutputStream out = connection.getOutputStream();
            try {
                multipart.writeTo(out, request.uploadProgress, length);
            } finally {
                out.close();
            }
            return;
        }
        byte[] body = request.body;
        boolean needsBody = body != null || request.method.equals("POST") || request.method.equals("PUT");
        if (!needsBody) {
            return;
        }
        if (body == null) {
            body = new byte[0];
        }
        connection.setDoOutput(true);
        if (request.contentType != null) {
            connection.setRequestProperty("Content-Type", request.contentType);
        }
        // Small bodies are buffered, not streamed: in streaming mode the JDK's HttpURLConnection turns a
        // 401 into an HttpRetryException and the error body is lost.
        OutputStream out = connection.getOutputStream();
        try {
            out.write(body);
        } finally {
            out.close();
        }
    }

    /** Sends a request and returns a 2xx response with its body; maps everything else to {@link MattermostException}. */
    Response execute(Request request) throws MattermostException {
        HttpURLConnection connection = open(request);
        try {
            int status;
            try {
                writeBody(connection, request);
                status = connection.getResponseCode();
            } catch (HttpRetryException e) {
                // Streaming upload answered with 401/407 (JDK): no body to read.
                throw MattermostException.http(e.responseCode(), new AppError("", e.getReason() != null ? e.getReason() : "HTTP " + e.responseCode(), e.responseCode()), -1);
            } catch (SocketTimeoutException e) {
                throw MattermostException.transport("timeout: " + request.method + " " + request.path, e);
            } catch (IOException e) {
                throw MattermostException.transport(request.method + " " + request.path + ": " + e.getMessage(), e);
            }
            if (status < 200 || status >= 300) {
                throw errorFor(connection, status, request);
            }
            byte[] body;
            try {
                InputStream in = connection.getInputStream();
                body = readFully(in);
            } catch (IOException e) {
                throw MattermostException.transport("reading " + request.path + ": " + e.getMessage(), e);
            }
            return new Response(status, body, connection.getHeaderFields());
        } finally {
            connection.disconnect();
        }
    }

    private FileDownload download(Request request, long rangeStart, long rangeEnd) throws MattermostException {
        if (rangeStart >= 0) {
            request.headers.put("Range", "bytes=" + rangeStart + "-" + (rangeEnd >= 0 ? String.valueOf(rangeEnd) : ""));
        }
        HttpURLConnection connection = open(request);
        int status;
        try {
            status = connection.getResponseCode();
        } catch (IOException e) {
            connection.disconnect();
            throw MattermostException.transport(request.method + " " + request.path + ": " + e.getMessage(), e);
        }
        if (status < 200 || status >= 300) {
            try {
                throw errorFor(connection, status, request);
            } finally {
                connection.disconnect();
            }
        }
        try {
            return new FileDownload(connection, connection.getInputStream(), status);
        } catch (IOException e) {
            connection.disconnect();
            throw MattermostException.transport("reading " + request.path + ": " + e.getMessage(), e);
        }
    }

    private MattermostException errorFor(HttpURLConnection connection, int status, Request request) {
        byte[] body = new byte[0];
        try {
            InputStream error = connection.getErrorStream();
            if (error != null) {
                body = readFully(error);
            }
        } catch (IOException ignored) {
            // keep the empty body
        }
        MattermostException exception = errorFromResponse(status, body, connection.getHeaderField("Retry-After"), connection.getHeaderField("X-RateLimit-Reset"));
        if (status == 401 && request.authenticated && token != null) {
            Listener current = listener;
            if (current != null) {
                current.onUnauthorized(this, exception);
            }
        }
        return exception;
    }

    /** Maps a non-2xx response to an exception (public for tests). */
    public static MattermostException errorFromResponse(int status, byte[] body, String retryAfter, String rateLimitReset) {
        AppError appError = null;
        String text = new String(body, UTF8);
        JsonObject object = Json.parseObjectOrNull(text);
        if (object != null) {
            try {
                AppError parsed = Json.GSON.fromJson(object, AppError.class);
                if (parsed != null && !parsed.isEmpty()) {
                    appError = parsed;
                }
            } catch (RuntimeException ignored) {
                // not an AppError
            }
        }
        if (appError == null) {
            String snippet = text.length() > 512 ? text.substring(0, 512) : text;
            appError = new AppError("", "HTTP " + status, status);
            appError.detailedError = snippet;
        } else if (appError.statusCode == 0) {
            appError.statusCode = status;
        }
        long retryAfterSeconds = -1;
        if (status == 429) {
            retryAfterSeconds = parseSeconds(retryAfter);
            if (retryAfterSeconds < 0) {
                retryAfterSeconds = parseSeconds(rateLimitReset);
            }
        }
        return MattermostException.http(status, appError, retryAfterSeconds);
    }

    private static long parseSeconds(String value) {
        if (value == null) {
            return -1;
        }
        try {
            return Math.max(0, (long) Math.ceil(Double.parseDouble(value.trim())));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static byte[] readFully(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    /** Streams a {@code multipart/form-data} body. */
    static final class Multipart {
        private final String boundary;
        private final List<Object[]> parts = new ArrayList<Object[]>();

        Multipart(String boundary) {
            this.boundary = boundary;
        }

        String contentType() {
            return "multipart/form-data; boundary=" + boundary;
        }

        void field(String name, String value) {
            parts.add(new Object[]{name, value});
        }

        void file(String name, FileUpload upload) {
            parts.add(new Object[]{name, upload});
        }

        private static String escape(String value) {
            return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", " ").replace("\n", " ");
        }

        private byte[] header(Object[] part) {
            String name = (String) part[0];
            StringBuilder builder = new StringBuilder();
            builder.append("--").append(boundary).append("\r\n");
            if (part[1] instanceof FileUpload) {
                FileUpload upload = (FileUpload) part[1];
                builder.append("Content-Disposition: form-data; name=\"").append(escape(name)).append("\"; filename=\"").append(escape(upload.fileName)).append("\"\r\n");
                builder.append("Content-Type: ").append(upload.mimeType).append("\r\n\r\n");
            } else {
                builder.append("Content-Disposition: form-data; name=\"").append(escape(name)).append("\"\r\n\r\n");
            }
            return bytes(builder.toString());
        }

        private static byte[] bytes(String text) {
            try {
                return text.getBytes("UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw new IllegalStateException(e);
            }
        }

        private byte[] footer() {
            return bytes("--" + boundary + "--\r\n");
        }

        /** Total size, or -1 when a file's size is unknown. */
        long length() {
            long total = 0;
            for (Object[] part : parts) {
                total += header(part).length + 2;
                if (part[1] instanceof FileUpload) {
                    long size = ((FileUpload) part[1]).source.length();
                    if (size < 0) {
                        return -1;
                    }
                    total += size;
                } else {
                    total += bytes((String) part[1]).length;
                }
            }
            return total + footer().length;
        }

        void writeTo(OutputStream out, UploadProgressListener progress, long total) throws IOException {
            long sent = 0;
            byte[] crlf = bytes("\r\n");
            byte[] buffer = new byte[64 * 1024];
            for (Object[] part : parts) {
                byte[] header = header(part);
                out.write(header);
                sent += header.length;
                if (part[1] instanceof FileUpload) {
                    InputStream in = ((FileUpload) part[1]).source.open();
                    try {
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                            sent += read;
                            if (progress != null) {
                                progress.onProgress(sent, total);
                            }
                        }
                    } finally {
                        in.close();
                    }
                } else {
                    byte[] value = bytes((String) part[1]);
                    out.write(value);
                    sent += value.length;
                }
                out.write(crlf);
                sent += crlf.length;
            }
            byte[] footer = footer();
            out.write(footer);
            sent += footer.length;
            out.flush();
            if (progress != null) {
                progress.onProgress(sent, total);
            }
        }
    }

    /** For tests and diagnostics: the JSON array of a list of ids. */
    static JsonArray ids(List<String> ids) {
        return Json.stringArray(ids);
    }
}
