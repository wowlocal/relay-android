package org.telegram.relay.mattermost.model;

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import org.junit.Test;
import org.telegram.relay.mattermost.Fixtures;
import org.telegram.relay.mattermost.Json;
import org.telegram.relay.mattermost.MattermostClient;
import org.telegram.relay.mattermost.MattermostException;

import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Every model decoded from the recorded REST fixtures. */
public class ModelFixtureTest {
    private static final String ALICE = "x6y9ko87wtrq7m9k3dbk3qt7me";
    private static final String BOB = "9ik4bw7aybbqupuhs9fjsprsie";

    private static <T> List<T> list(String fixture, Class<T> type) throws MattermostException {
        return Json.listFromJson(Fixtures.rest(fixture), type);
    }

    private static <T> T one(String fixture, Class<T> type) throws MattermostException {
        return Json.fromJson(Fixtures.rest(fixture), type);
    }

    private static void assertPostListConsistent(PostList list) {
        assertPostListConsistent(list, true);
    }

    /** Channel pages are newest first; pinned and thread lists use other orders. */
    private static void assertPostListConsistent(PostList list, boolean newestFirst) {
        assertFalse(list.order.isEmpty());
        long previous = Long.MAX_VALUE;
        for (Post post : list.getOrderedPosts()) {
            assertFalse(post.id.isEmpty());
            assertFalse(post.channelId.isEmpty());
            assertTrue(post.createAt > 0);
            if (newestFirst) {
                assertTrue("order is newest first", post.createAt <= previous);
            }
            previous = post.createAt;
        }
        assertEquals("every ordered id has a post", list.order.size(), list.getOrderedPosts().size());
    }

    @Test
    public void loginAndMe() throws Exception {
        for (String name : new String[]{"users_login", "users_me"}) {
            User user = one(name, User.class);
            assertEquals(ALICE, user.id);
            assertEquals("alice", user.username);
            assertEquals("Alice", user.firstName);
            assertEquals("Anderson", user.lastName);
            assertEquals("Alice Anderson", user.getFullName());
            assertEquals("Al", user.nickname);
            assertEquals("system_user", user.roles);
            assertFalse(user.isSystemAdmin());
            assertEquals("mention", user.notifyProps.get("push"));
            assertEquals("true", user.timezone.get("useAutomaticTimezone"));
            assertTrue(user.lastPictureUpdate > 0);
            assertTrue(user.hasCustomPicture());
            assertFalse(user.isDeactivated());
        }
    }

    @Test
    public void usersByIds() throws Exception {
        List<User> users = list("users_by_ids", User.class);
        assertEquals(5, users.size());
        Set<String> usernames = new HashSet<String>();
        for (User user : users) {
            usernames.add(user.username);
            if (user.username.equals("buildbot")) {
                assertTrue(user.isBot);
            } else {
                assertFalse(user.isBot);
            }
        }
        assertTrue(usernames.contains("bob") && usernames.contains("carol") && usernames.contains("dave"));
    }

    @Test
    public void userStatuses() throws Exception {
        List<UserStatus> statuses = list("users_status_ids", UserStatus.class);
        assertEquals(5, statuses.size());
        Set<String> values = new HashSet<String>();
        for (UserStatus status : statuses) {
            assertFalse(status.userId.isEmpty());
            values.add(status.status);
        }
        assertTrue(values.contains(UserStatus.ONLINE));
        UserStatus bob = statuses.get(0);
        assertEquals(BOB, bob.userId);
        assertEquals(UserStatus.AWAY, bob.status);
        assertTrue(bob.manual);
        assertTrue(bob.lastActivityAt > 0);
    }

    @Test
    public void teamsAndMembers() throws Exception {
        List<Team> teams = list("teams", Team.class);
        assertEquals(2, teams.size());
        Set<String> names = new HashSet<String>();
        for (Team team : teams) {
            names.add(team.name);
            assertFalse(team.displayName.isEmpty());
        }
        assertTrue(names.contains("engineering") && names.contains("design"));

        List<TeamMember> members = list("team_members", TeamMember.class);
        assertEquals(ALICE, members.get(0).userId);
        assertTrue(members.get(0).schemeUser);

        List<TeamUnread> unread = list("teams_unread", TeamUnread.class);
        assertFalse(unread.isEmpty());
        boolean anyUnread = false;
        for (TeamUnread team : unread) {
            anyUnread |= team.msgCount > 0;
        }
        assertTrue(anyUnread);
    }

    @Test
    public void channels() throws Exception {
        List<Channel> all = list("channels_all", Channel.class);
        assertEquals(39, all.size());
        Set<String> types = new HashSet<String>();
        int directs = 0;
        for (Channel channel : all) {
            types.add(channel.type);
            assertFalse(channel.id.isEmpty());
            if (channel.isDirect()) {
                directs++;
                String[] ids = channel.getDirectMessageUserIds();
                assertNotNull(ids);
                assertTrue(ids[0].equals(ALICE) || ids[1].equals(ALICE));
                assertTrue(channel.teamId.isEmpty());
            }
            assertTrue(channel.totalMsgCount >= channel.totalMsgCountRoot);
        }
        assertTrue(directs > 0);
        assertEquals(new HashSet<String>(java.util.Arrays.asList("O", "P", "D", "G")), types);

        Channel codeReview = one("channel_by_id", Channel.class);
        assertEquals("code-review", codeReview.name);
        assertEquals("Code Review", codeReview.displayName);
        assertTrue(codeReview.isOpen());
        assertEquals(21, codeReview.totalMsgCount);
        assertEquals(18, codeReview.totalMsgCountRoot);
        assertNull("props: null", codeReview.props);
        assertEquals("scheme_id: null decodes as empty", "", codeReview.schemeId);
        assertNull(codeReview.shared);

        ChannelStats stats = one("channel_stats", ChannelStats.class);
        assertEquals(codeReview.id, stats.channelId);
        assertEquals(4, stats.memberCount);

        assertEquals(list("channels_engineering", Channel.class).size() > 0, true);
    }

    @Test
    public void channelMembers() throws Exception {
        for (String name : new String[]{"channel_members_all", "channel_members_engineering"}) {
            List<ChannelMember> members = list(name, ChannelMember.class);
            assertFalse(members.isEmpty());
            boolean anyMention = false;
            for (ChannelMember member : members) {
                assertEquals(ALICE, member.userId);
                assertFalse(member.channelId.isEmpty());
                assertTrue(member.lastViewedAt >= 0);
                assertTrue(member.msgCount >= member.msgCountRoot);
                assertNotNull(member.notifyProps.get("mark_unread"));
                anyMention |= member.mentionCount > 0;
            }
            assertTrue(name, anyMention);
        }
    }

    @Test
    public void postPages() throws Exception {
        for (String name : new String[]{"posts_page_firehose", "posts_page_firehose_before", "posts_page_code_review", "posts_page_dm_bob", "posts_page_integrations", "posts_around_unread_firehose", "flagged_posts"}) {
            PostList list = one(name, PostList.class);
            assertPostListConsistent(list);
        }
        assertPostListConsistent(one("pinned_posts_releases", PostList.class), false);
        PostList firehose = one("posts_page_firehose", PostList.class);
        assertEquals(60, firehose.size());
        assertEquals("y9zgbmwdwjyjzdzmqncxr8q44w", firehose.prevPostId);
        assertNull("has_next missing", firehose.hasNext);
        PostList before = one("posts_page_firehose_before", PostList.class);
        // The `before` page continues strictly older than the cursor.
        Post cursor = null;
        for (Post post : firehose.getOrderedPosts()) {
            if (post.id.equals(Fixtures.manifestEntry("posts_page_firehose_before").getAsJsonObject("query").get("before").getAsString())) {
                cursor = post;
            }
        }
        if (cursor != null) {
            for (Post post : before.getOrderedPosts()) {
                assertTrue(post.createAt <= cursor.createAt);
            }
        }
        PostList around = one("posts_around_unread_firehose", PostList.class);
        assertEquals(Boolean.FALSE, around.hasNext);

        PostList pinned = one("pinned_posts_releases", PostList.class);
        for (Post post : pinned.getOrderedPosts()) {
            assertTrue(post.isPinned);
        }
    }

    @Test
    public void integrationPostsKeepProps() throws Exception {
        PostList list = one("posts_page_integrations", PostList.class);
        boolean webhook = false;
        boolean bot = false;
        boolean system = false;
        boolean attachments = false;
        for (Post post : list.getOrderedPosts()) {
            webhook |= post.isFromWebhook();
            bot |= post.isFromBot();
            system |= post.isSystemMessage();
            attachments |= post.props != null && post.props.has("attachments") && post.props.get("attachments").isJsonArray();
            if (post.isFromWebhook()) {
                assertNotNull(post.prop("override_username"));
            }
        }
        assertTrue(webhook);
        assertTrue(bot);
        assertTrue(system);
        assertTrue(attachments);
    }

    @Test
    public void threads() throws Exception {
        PostList thread = one("thread", PostList.class);
        assertPostListConsistent(thread, false);
        String rootId = "x6b9h6tdipraby95rjs4wmg8nw";
        Post root = thread.posts.get(rootId);
        assertNotNull(root);
        assertFalse(root.isReply());
        int replies = 0;
        for (Post post : thread.posts.values()) {
            if (!post.id.equals(rootId)) {
                assertEquals(rootId, post.rootId);
                assertTrue(post.isReply());
                replies++;
            }
        }
        assertTrue(replies > 50);

        PostList paged = one("thread_paged", PostList.class);
        assertEquals(Boolean.TRUE, paged.hasNext);
        assertEquals(21, paged.size());

        ThreadList threads = one("user_threads", ThreadList.class);
        assertEquals(4, threads.total);
        assertEquals(3, threads.totalUnreadThreads);
        assertEquals(1, threads.totalUnreadMentions);
        assertEquals(4, threads.threads.size());
        for (UserThread userThread : threads.threads) {
            assertFalse(userThread.id.isEmpty());
            assertNotNull(userThread.post);
            assertEquals(userThread.id, userThread.post.id);
            assertTrue(userThread.replyCount > 0);
        }

        UserThread one = one("user_thread", UserThread.class);
        assertEquals(rootId, one.id);
        assertEquals(119, one.replyCount);
        assertEquals(20, one.unreadReplies);
        assertTrue(one.post.isPinned);
        assertFalse(one.participants.isEmpty());
        // Without extended=true participants carry only ids (timezone: null decodes as empty).
        assertEquals(ALICE, one.participants.get(0).id);
        assertTrue(one.participants.get(0).timezone.isEmpty());
    }

    @Test
    public void postMetadataFilesReactionsEmojis() throws Exception {
        Post post = one("post_with_files_reactions", Post.class);
        assertEquals("yw64guysmpb79d9ptaaac5wioe", post.id);
        assertTrue(post.hasReactions);
        assertEquals(5, post.fileIds.size());
        assertNotNull(post.metadata);
        List<FileInfo> files = post.getFiles();
        assertEquals(post.fileIds.size(), files.size());
        Set<String> fileIds = new HashSet<String>(post.fileIds);
        for (FileInfo info : files) {
            assertTrue(fileIds.contains(info.id));
        }
        FileInfo file = files.get(0);
        assertTrue(file.size > 0);
        assertFalse(file.mimeType.isEmpty());
        assertFalse(file.name.isEmpty());
        assertFalse(post.getReactions().isEmpty());
        for (Reaction reaction : post.getReactions()) {
            assertEquals(post.id, reaction.postId);
            assertFalse(reaction.emojiName.isEmpty());
        }
        assertFalse(post.metadata.emojis.isEmpty());
        assertFalse(post.isEdited());
        assertFalse(post.isDeleted());

        List<FileInfo> infos = list("post_files_info", FileInfo.class);
        assertEquals(5, infos.size());
        boolean image = false;
        for (FileInfo info : infos) {
            assertFalse(info.id.isEmpty());
            assertFalse(info.postId.isEmpty());
            if (info.isImage()) {
                image = true;
                assertTrue(info.width > 0 && info.height > 0);
            }
        }
        assertTrue(image);

        List<Reaction> reactions = list("post_reactions", Reaction.class);
        assertEquals(6, reactions.size());
        for (Reaction reaction : reactions) {
            assertEquals("x6b9h6tdipraby95rjs4wmg8nw", reaction.postId);
            assertTrue(reaction.createAt > 0);
        }
    }

    @Test
    public void emojiAndPreferences() throws Exception {
        List<CustomEmoji> emojis = list("custom_emoji", CustomEmoji.class);
        assertFalse(emojis.isEmpty());
        assertEquals("lgtm", emojis.get(0).name);
        assertFalse(emojis.get(0).creatorId.isEmpty());

        List<Preference> preferences = list("preferences", Preference.class);
        assertEquals(11, preferences.size());
        for (Preference preference : preferences) {
            assertEquals(ALICE, preference.userId);
            assertFalse(preference.category.isEmpty());
        }
    }

    @Test
    public void clientConfigLicenseAndVersion() throws Exception {
        Map<String, String> values = Json.fromJson(Fixtures.rest("client_config"), new TypeToken<Map<String, String>>() { }.getType());
        ClientConfig config = new ClientConfig(values);
        assertNotNull(config.getVersion());
        ServerVersion version = config.getServerVersion();
        assertNotNull(version);
        assertEquals(11, version.major);
        assertTrue(version.isSupported());
        assertNotNull(config.getSiteUrl());
        assertNotNull(config.getCollapsedThreads());
        assertTrue(config.getMaxFileSize() > 0);
        assertTrue(config.isSignInWithUsernameEnabled() || config.isSignInWithEmailEnabled());

        Map<String, String> license = Json.fromJson(Fixtures.rest("client_license"), new TypeToken<Map<String, String>>() { }.getType());
        assertEquals("false", license.get("IsLicensed"));

        JsonObject ping = Json.parseObjectOrNull(Fixtures.rest("system_ping"));
        String header = Fixtures.manifestEntry("system_ping").getAsJsonObject("headers").get("X-Version-Id").getAsString();
        PingResponse response = new PingResponse(ping, header);
        assertTrue(response.isOk());
        assertEquals(new ServerVersion(11, 7, 14), response.getServerVersion());
        assertEquals("database", Json.string(response.raw, "ActiveSearchBackend"));
    }

    @Test
    public void serverVersionParsing() {
        assertEquals(new ServerVersion(11, 7, 14), ServerVersion.parse("11.7.14.39757492507.2f3e.false"));
        assertEquals(new ServerVersion(9, 11, 0), ServerVersion.parse("9.11"));
        assertEquals(new ServerVersion(10, 5, 1), ServerVersion.parse("v10.5.1"));
        assertNull(ServerVersion.parse("dev"));
        assertNull(ServerVersion.parse(null));
        assertTrue(new ServerVersion(10, 0, 0).isAtLeast(9, 11, 3));
        assertFalse(new ServerVersion(8, 1, 0).isSupported());
    }

    @Test
    public void errorBodyMapsToException() {
        byte[] body = Fixtures.rest("error_post_not_found").getBytes(Charset.forName("UTF-8"));
        MattermostException error = MattermostClient.errorFromResponse(404, body, null, null);
        assertEquals(MattermostException.Kind.HTTP, error.getKind());
        assertEquals(404, error.getStatusCode());
        assertTrue(error.isNotFound());
        assertEquals("app.post.get.app_error", error.getErrorId());
        assertEquals("Unable to get the post.", error.getAppError().message);
        assertFalse(error.isTransient());

        MattermostException html = MattermostClient.errorFromResponse(502, "<html>bad gateway</html>".getBytes(Charset.forName("UTF-8")), null, null);
        assertEquals("", html.getErrorId());
        assertTrue(html.isTransient());
        assertTrue(html.getAppError().detailedError.contains("bad gateway"));

        MattermostException limited = MattermostClient.errorFromResponse(429, new byte[0], "7", null);
        assertTrue(limited.isRateLimited());
        assertEquals(7, limited.getRetryAfterSeconds());

        MattermostException mfa = MattermostClient.errorFromResponse(401, "{\"id\":\"mfa.validate_token.authenticate.app_error\",\"message\":\"MFA\",\"status_code\":401}".getBytes(Charset.forName("UTF-8")), null, null);
        assertTrue(mfa.isUnauthorized());
        assertTrue(mfa.isMfaRequired());
    }

    @Test
    public void lenientDecoding() throws Exception {
        String json = "{\"id\":\"p\",\"create_at\":\"1700000000000\",\"edit_at\":null,\"is_pinned\":\"true\",\"message\":null,"
                + "\"reply_count\":3.0,\"file_ids\":null,\"props\":{\"from_webhook\":\"true\"},\"metadata\":{\"files\":null}}";
        Post post = Json.fromJson(json, Post.class);
        assertEquals(1_700_000_000_000L, post.createAt);
        assertEquals(0, post.editAt);
        assertTrue(post.isPinned);
        assertEquals("", post.message);
        assertEquals(3, post.replyCount);
        assertTrue(post.isFromWebhook());
        assertTrue(post.getFiles().isEmpty());
        assertTrue("null lists decode as empty", post.fileIds.isEmpty());

        User user = Json.fromJson("{\"id\":\"u\",\"props\":{\"customStatus\":\"{\\\"emoji\\\":\\\"calendar\\\",\\\"text\\\":\\\"In a meeting\\\"}\"}}", User.class);
        CustomStatus status = user.getCustomStatus();
        assertNotNull(status);
        assertEquals("calendar", status.emoji);
        assertEquals("In a meeting", status.text);
    }
}
