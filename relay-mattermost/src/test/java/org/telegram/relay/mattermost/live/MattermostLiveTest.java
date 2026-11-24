package org.telegram.relay.mattermost.live;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.telegram.relay.mattermost.FileDownload;
import org.telegram.relay.mattermost.FileUpload;
import org.telegram.relay.mattermost.MattermostClient;
import org.telegram.relay.mattermost.MattermostException;
import org.telegram.relay.mattermost.PostsQuery;
import org.telegram.relay.mattermost.ThreadQuery;
import org.telegram.relay.mattermost.UserThreadsQuery;
import org.telegram.relay.mattermost.ids.HistoryDirection;
import org.telegram.relay.mattermost.ids.HistoryHole;
import org.telegram.relay.mattermost.ids.InMemoryMessageIdStore;
import org.telegram.relay.mattermost.ids.IngestResult;
import org.telegram.relay.mattermost.ids.MessageIdAllocator;
import org.telegram.relay.mattermost.ids.MessageIdEntry;
import org.telegram.relay.mattermost.ids.PostOrderKey;
import org.telegram.relay.mattermost.model.Channel;
import org.telegram.relay.mattermost.model.ChannelMember;
import org.telegram.relay.mattermost.model.ChannelViewResponse;
import org.telegram.relay.mattermost.model.ClientConfig;
import org.telegram.relay.mattermost.model.CustomEmoji;
import org.telegram.relay.mattermost.model.CustomStatus;
import org.telegram.relay.mattermost.model.FileInfo;
import org.telegram.relay.mattermost.model.FileUploadResponse;
import org.telegram.relay.mattermost.model.PingResponse;
import org.telegram.relay.mattermost.model.Post;
import org.telegram.relay.mattermost.model.PostDraft;
import org.telegram.relay.mattermost.model.PostList;
import org.telegram.relay.mattermost.model.PostSearchResults;
import org.telegram.relay.mattermost.model.Reaction;
import org.telegram.relay.mattermost.model.ServerVersion;
import org.telegram.relay.mattermost.model.Team;
import org.telegram.relay.mattermost.model.TeamUnread;
import org.telegram.relay.mattermost.model.ThreadList;
import org.telegram.relay.mattermost.model.User;
import org.telegram.relay.mattermost.model.UserStatus;
import org.telegram.relay.mattermost.ws.ConnectionState;
import org.telegram.relay.mattermost.ws.MattermostWebSocket;
import org.telegram.relay.mattermost.ws.WebSocketEvent;
import org.telegram.relay.mattermost.ws.WebSocketEventType;
import org.telegram.relay.mattermost.ws.WebSocketListener;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Live integration tests against the local Mattermost dev server (alice, bob). Skipped when the
 * server is down. They create posts in the alice–bob DM and delete them again.
 */
public class MattermostLiveTest {
    private final List<MattermostClient> clients = new ArrayList<MattermostClient>();
    private final List<MattermostWebSocket> sockets = new ArrayList<MattermostWebSocket>();
    private final List<String[]> createdPosts = new ArrayList<String[]>();

    /** Records WebSocket events and states; waits for matching ones. */
    static final class Recorder implements WebSocketListener {
        final BlockingQueue<WebSocketEvent> events = new LinkedBlockingQueue<WebSocketEvent>();
        final BlockingQueue<ConnectionState> states = new LinkedBlockingQueue<ConnectionState>();

        interface Match {
            boolean matches(WebSocketEvent event);
        }

        @Override
        public void onStateChanged(MattermostWebSocket socket, ConnectionState state) {
            states.add(state);
        }

        @Override
        public void onEvent(MattermostWebSocket socket, WebSocketEvent event) {
            events.add(event);
        }

        ConnectionState awaitConnected() throws InterruptedException {
            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline) {
                ConnectionState state = states.poll(200, TimeUnit.MILLISECONDS);
                if (state != null && state.kind == ConnectionState.Kind.CONNECTED) {
                    return state;
                }
                if (state != null && state.kind == ConnectionState.Kind.UNAUTHORIZED) {
                    fail("websocket unauthorized");
                }
            }
            fail("websocket never connected");
            return null;
        }

        WebSocketEvent await(String description, Match match) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline) {
                WebSocketEvent event = events.poll(200, TimeUnit.MILLISECONDS);
                if (event != null && match.matches(event)) {
                    return event;
                }
            }
            fail("no websocket event: " + description);
            return null;
        }
    }

    @Before
    public void requireServer() {
        LiveServer.assumeAvailable();
    }

    @After
    public void cleanUp() {
        for (MattermostWebSocket socket : sockets) {
            socket.shutdown();
        }
        for (String[] created : createdPosts) {
            for (MattermostClient client : clients) {
                if (client.getToken() != null) {
                    try {
                        client.deletePost(created[0]);
                        break;
                    } catch (MattermostException ignored) {
                        // already deleted, or not this client's post
                    }
                }
            }
        }
        for (MattermostClient client : clients) {
            LiveServer.logoutQuietly(client);
        }
    }

    private MattermostClient login(String username) throws MattermostException {
        MattermostClient client = LiveServer.login(username);
        clients.add(client);
        return client;
    }

    private Recorder connectWebSocket(MattermostClient client) throws InterruptedException {
        Recorder recorder = new Recorder();
        MattermostWebSocket socket = client.createWebSocket(recorder);
        sockets.add(socket);
        socket.connect();
        ConnectionState state = recorder.awaitConnected();
        assertFalse(state.info.connectionId.isEmpty());
        assertFalse("a fresh connection is not resumed", state.info.resumed);
        return recorder;
    }

    private MattermostWebSocket lastSocket() {
        return sockets.get(sockets.size() - 1);
    }

    private Post post(MattermostClient client, String channelId, String message, String rootId) throws MattermostException {
        PostDraft draft = new PostDraft(channelId, message);
        if (rootId != null) {
            draft.rootId(rootId);
        }
        Post post = client.createPost(draft);
        createdPosts.add(new String[]{post.id, channelId});
        return post;
    }

    private static String stamp() {
        return "relay-mattermost live test rmlt" + System.currentTimeMillis() + new Random().nextInt(100000);
    }

    // MARK: Tests

    @Test
    public void serverConfigAndLogin() throws Exception {
        MattermostClient anonymous = new MattermostClient(LiveServer.url());
        PingResponse ping = anonymous.ping(true);
        assertTrue(ping.isOk());
        ServerVersion version = anonymous.serverVersion();
        assertNotNull(version);
        assertTrue("server " + version + " is supported", version.isSupported());
        assertTrue(anonymous.isServerVersionAtLeast(ServerVersion.MINIMUM_SUPPORTED));
        ClientConfig config = anonymous.clientConfig();
        assertEquals(version, config.getServerVersion());

        try {
            anonymous.login("alice", "definitely-wrong-password");
            fail("bad password accepted");
        } catch (MattermostException e) {
            assertTrue(e.isUnauthorized());
            assertTrue(e.getErrorId(), e.getErrorId().startsWith("api.user.login"));
            assertNull(anonymous.getToken());
        }

        MattermostClient alice = login("alice");
        assertNotNull(alice.getToken());
        User me = alice.me();
        assertEquals("alice", me.username);
        assertTrue(alice.isTokenAccepted());
        assertEquals("alice", alice.userByUsername("alice").username);
        List<User> byName = alice.usersByUsernames(Arrays.asList("bob", "carol", "dave"));
        assertEquals(3, byName.size());
        List<String> ids = new ArrayList<String>();
        for (User user : byName) {
            ids.add(user.id);
        }
        assertEquals(3, alice.users(ids).size());
        List<UserStatus> statuses = alice.userStatuses(ids);
        assertEquals(3, statuses.size());
        assertFalse(alice.userStatus(me.id).status.isEmpty());

        // Custom status round trip.
        alice.updateCustomStatus(new CustomStatus("calendar", "In a meeting"));
        CustomStatus status = alice.me().getCustomStatus();
        assertNotNull(status);
        assertEquals("In a meeting", status.text);
        alice.removeCustomStatus();
        assertNull(alice.me().getCustomStatus());

        FileDownload image = alice.profileImage(me.id, me.lastPictureUpdate);
        byte[] bytes = image.readAll();
        assertTrue(bytes.length > 8);
        assertTrue(image.getContentType(), image.getContentType().startsWith("image/"));

        List<CustomEmoji> emojis = alice.customEmojis(0, 200, "name");
        if (!emojis.isEmpty()) {
            assertTrue(alice.customEmojiImage(emojis.get(0).id).readAll().length > 0);
            assertEquals(emojis.get(0).id, alice.customEmojiByName(emojis.get(0).name).id);
        }
        assertFalse(alice.preferences().isEmpty());
    }

    @Test
    public void teamsChannelsAndReadState() throws Exception {
        MattermostClient alice = login("alice");
        List<Team> teams = alice.teams();
        Set<String> names = new HashSet<String>();
        for (Team team : teams) {
            names.add(team.name);
        }
        assertTrue(names.containsAll(Arrays.asList("engineering", "design")));
        assertEquals(teams.size(), alice.teamMembers().size());
        List<TeamUnread> unread = alice.teamsUnread(true);
        assertEquals(teams.size(), unread.size());

        Set<String> channelIds = new HashSet<String>();
        for (Team team : teams) {
            List<Channel> channels = alice.channels(team.id, false);
            assertFalse(channels.isEmpty());
            List<ChannelMember> members = alice.channelMembers(team.id);
            assertFalse(members.isEmpty());
            for (Channel channel : channels) {
                channelIds.add(channel.id);
            }
            ThreadList threads = alice.userThreads(team.id, new UserThreadsQuery().perPage(25));
            assertTrue(threads.total >= threads.threads.size());
            if (!threads.threads.isEmpty()) {
                assertEquals(threads.threads.get(0).id, alice.userThread(team.id, threads.threads.get(0).id, true).id);
            }
        }
        List<Channel> all = alice.allChannels(false, 0);
        Set<String> allIds = new HashSet<String>();
        boolean dm = false;
        for (Channel channel : all) {
            allIds.add(channel.id);
            dm |= channel.isDirect();
        }
        assertTrue(dm);
        assertTrue("every team channel is in the cross-team list", allIds.containsAll(channelIds));
        // Memberships include archived channels, which allChannels(includeDeleted=false) leaves out.
        List<ChannelMember> members = alice.allChannelMembers();
        Set<String> memberChannelIds = new HashSet<String>();
        for (ChannelMember member : members) {
            memberChannelIds.add(member.channelId);
        }
        assertTrue(memberChannelIds.containsAll(allIds));
        Set<String> withArchived = new HashSet<String>();
        for (Channel channel : alice.allChannels(true, 0)) {
            withArchived.add(channel.id);
        }
        assertEquals(withArchived, memberChannelIds);
        Channel first = all.get(0);
        assertEquals(first.id, alice.channel(first.id).id);
        assertEquals(first.id, alice.channelMember(first.id).channelId);
        assertTrue(alice.channelStats(first.id).memberCount > 0);
    }

    @Test
    public void pageFirehoseBackwardsIntoTheIdAllocator() throws Exception {
        MattermostClient alice = login("alice");
        Channel firehose = null;
        for (Channel channel : alice.allChannels(false, 0)) {
            if (channel.name.equals("firehose")) {
                firehose = channel;
            }
        }
        assertNotNull("seeded firehose channel", firehose);
        assertTrue(firehose.totalMsgCount > 1000);

        MessageIdAllocator allocator = new MessageIdAllocator();
        InMemoryMessageIdStore store = new InMemoryMessageIdStore();
        PostList latest = alice.posts(firehose.id, PostsQuery.latest(60));
        assertEquals(60, latest.size());
        allocator.ingestLatestPage(keys(latest), false, firehose.id, store);

        Set<String> seen = new HashSet<String>(latest.order);
        Post oldest = latest.getOrderedPosts().get(latest.size() - 1);
        for (int page = 0; page < 4; page++) {
            List<HistoryHole> holes = allocator.holes(firehose.id, store);
            assertEquals(1, holes.size());
            String anchor = allocator.fillCursor(HistoryDirection.OLDER, holes.get(0).range.upper, firehose.id, store).postId;
            assertEquals("the cursor names the oldest loaded post", oldest.id, anchor);
            PostList older = alice.posts(firehose.id, PostsQuery.before(anchor, 60));
            assertEquals(60, older.size());
            for (Post post : older.getOrderedPosts()) {
                assertTrue("no overlap between pages", seen.add(post.id));
                assertTrue("strictly older", post.createAt <= oldest.createAt);
            }
            IngestResult result = allocator.ingestPage(keys(older), anchor, HistoryDirection.OLDER, older.size() < 60, firehose.id, store);
            assertNull(result.renumberPlan);
            assertEquals(60, result.insertedCount());
            oldest = older.getOrderedPosts().get(older.size() - 1);
        }
        List<MessageIdEntry> entries = store.allEntries(firehose.id);
        assertEquals(300, entries.size());
        assertEquals(1 << 30, entries.get(entries.size() - 1).localId);
        assertTrue(store.validate(firehose.id, Integer.MAX_VALUE).isEmpty());

        // Forward from an old post, and catch-up with since=.
        PostList newer = alice.posts(firehose.id, PostsQuery.after(oldest.id, 30));
        assertEquals(30, newer.size());
        for (Post post : newer.getOrderedPosts()) {
            assertTrue(post.createAt >= oldest.createAt);
        }
        PostList since = alice.posts(firehose.id, PostsQuery.since(latest.getOrderedPosts().get(0).createAt - 1));
        assertTrue(since.size() >= 1);
        PostList page2 = alice.posts(firehose.id, PostsQuery.page(1, 60));
        assertEquals(60, page2.size());
        PostList unread = alice.postsAroundUnread(firehose.id, 10, 10, false);
        assertNotNull(unread.order);
    }

    private static List<PostOrderKey> keys(PostList list) {
        List<PostOrderKey> keys = new ArrayList<PostOrderKey>();
        for (Post post : list.getOrderedPosts()) {
            keys.add(new PostOrderKey(post.createAt, post.id));
        }
        return keys;
    }

    @Test
    public void directMessageEventsReachTheWebSocket() throws Exception {
        MattermostClient alice = login("alice");
        MattermostClient bob = login("bob");
        final User aliceUser = alice.me();
        final User bobUser = bob.me();
        Recorder aliceEvents = connectWebSocket(alice);
        final Channel dm = bob.createDirectChannel(bobUser.id, aliceUser.id);
        assertTrue(dm.isDirect());
        assertEquals(bobUser.id, dm.getDirectMessageTeammateId(aliceUser.id));
        assertEquals("the same DM again", dm.id, alice.createDirectChannel(aliceUser.id, bobUser.id).id);

        // posted
        final String message = stamp();
        final Post sent = post(bob, dm.id, message, null);
        WebSocketEvent posted = aliceEvents.await("posted", new Recorder.Match() {
            @Override
            public boolean matches(WebSocketEvent event) {
                return event.type == WebSocketEventType.POSTED && event.getPosted() != null && event.getPosted().post.id.equals(sent.id);
            }
        });
        WebSocketEvent.Posted payload = posted.getPosted();
        assertEquals(message, payload.post.message);
        assertEquals(bobUser.id, payload.post.userId);
        assertEquals("D", payload.channelType);
        assertEquals("@bob", payload.senderName);
        assertNotNull(posted.seq);
        assertTrue(posted.raw.contains(sent.id));

        // post_edited
        Post edited = bob.editPost(sent.id, message + " (edited)");
        assertTrue(edited.isEdited());
        aliceEvents.await("post_edited", new Recorder.Match() {
            @Override
            public boolean matches(WebSocketEvent event) {
                Post post = event.getEditedPost();
                return post != null && post.id.equals(sent.id) && post.message.endsWith("(edited)") && post.editAt > 0;
            }
        });

        // reaction_added / reaction_removed
        Reaction reaction = bob.addReaction(bobUser.id, sent.id, "+1");
        assertEquals("+1", reaction.emojiName);
        aliceEvents.await("reaction_added", new Recorder.Match() {
            @Override
            public boolean matches(WebSocketEvent event) {
                Reaction r = event.getReaction();
                return event.type == WebSocketEventType.REACTION_ADDED && r != null && r.postId.equals(sent.id) && r.emojiName.equals("+1");
            }
        });
        List<Reaction> reactions = alice.reactions(sent.id);
        assertEquals(1, reactions.size());
        assertEquals(1, alice.post(sent.id).getReactions().size());
        bob.removeReaction(sent.id, "+1");
        aliceEvents.await("reaction_removed", new Recorder.Match() {
            @Override
            public boolean matches(WebSocketEvent event) {
                Reaction r = event.getReaction();
                return event.type == WebSocketEventType.REACTION_REMOVED && r != null && r.postId.equals(sent.id);
            }
        });

        // typing over bob's WebSocket
        Recorder bobEvents = connectWebSocket(bob);
        assertNotNull(bobEvents);
        MattermostWebSocket bobSocket = lastSocket();
        assertTrue(bobSocket.userTyping(dm.id, null));
        WebSocketEvent typing = aliceEvents.await("typing", new Recorder.Match() {
            @Override
            public boolean matches(WebSocketEvent event) {
                WebSocketEvent.Typing t = event.getTyping();
                return t != null && t.userId.equals(bobUser.id) && t.channelId.equals(dm.id);
            }
        });
        assertEquals("", typing.getTyping().parentId);
        assertTrue(bobSocket.ping().isOk());

        // a reply in the thread, then the thread
        final Post reply = post(alice, dm.id, "reply " + message, sent.id);
        bobEvents.await("posted reply", new Recorder.Match() {
            @Override
            public boolean matches(WebSocketEvent event) {
                WebSocketEvent.Posted p = event.getPosted();
                return p != null && p.post.id.equals(reply.id) && p.post.rootId.equals(sent.id);
            }
        });
        PostList thread = alice.postThread(sent.id, new ThreadQuery());
        assertTrue(thread.posts.containsKey(sent.id));
        assertTrue(thread.posts.containsKey(reply.id));

        // post_deleted
        alice.deletePost(reply.id);
        bob.deletePost(sent.id);
        aliceEvents.await("post_deleted", new Recorder.Match() {
            @Override
            public boolean matches(WebSocketEvent event) {
                Post post = event.getDeletedPost();
                return post != null && post.id.equals(sent.id);
            }
        });
        try {
            alice.post(sent.id);
            fail("deleted post still readable");
        } catch (MattermostException e) {
            assertTrue(e.isNotFound() || e.isForbidden());
        }
    }

    @Test
    public void fileUploadAndRangedDownloadRoundTrip() throws Exception {
        MattermostClient alice = login("alice");
        MattermostClient bob = login("bob");
        User aliceUser = alice.me();
        Channel dm = alice.createDirectChannel(aliceUser.id, bob.me().id);

        byte[] data = new byte[150_000];
        new Random(42).nextBytes(data);
        final long[] lastProgress = new long[2];
        FileUploadResponse upload = alice.uploadFile(dm.id, new FileUpload("client-1", "random.bin", "application/octet-stream", FileUpload.ofBytes("random.bin", null, data).source), new org.telegram.relay.mattermost.UploadProgressListener() {
            @Override
            public void onProgress(long sent, long total) {
                lastProgress[0] = sent;
                lastProgress[1] = total;
            }
        });
        assertEquals(1, upload.fileInfos.size());
        assertEquals(Collections.singletonList("client-1"), upload.clientIds);
        assertEquals(lastProgress[1], lastProgress[0]);
        FileInfo info = upload.fileInfos.get(0);
        assertEquals(data.length, info.size);
        assertEquals("random.bin", info.name);

        // A 64×64 PNG for thumbnail and preview.
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 64; x++) {
            for (int y = 0; y < 64; y++) {
                image.setRGB(x, y, (x * 4) << 16 | (y * 4) << 8 | 0x80);
            }
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        FileUploadResponse imageUpload = alice.uploadFile(dm.id, FileUpload.ofBytes("square.png", "image/png", png.toByteArray()), null);
        FileInfo imageInfo = imageUpload.fileInfos.get(0);
        assertTrue(imageInfo.isImage());
        assertEquals(64, imageInfo.width);

        Post post = alice.createPost(new PostDraft(dm.id, stamp()).fileIds(Arrays.asList(info.id, imageInfo.id)));
        createdPosts.add(new String[]{post.id, dm.id});
        assertEquals(2, post.fileIds.size());
        assertEquals(2, alice.postFiles(post.id).size());
        assertEquals(post.id, bob.fileInfo(info.id).postId);

        // Whole file, then byte ranges, as bob.
        FileDownload whole = bob.downloadFile(info.id);
        assertEquals(200, whole.getStatusCode());
        assertArrayEquals(data, whole.readAll());

        FileDownload range = bob.downloadFile(info.id, 1000, 1999);
        assertEquals(206, range.getStatusCode());
        assertTrue(range.isPartial());
        assertEquals(1000, range.getRangeStart());
        assertEquals(1999, range.getRangeEnd());
        assertEquals(data.length, range.getTotalLength());
        assertArrayEquals(Arrays.copyOfRange(data, 1000, 2000), range.readAll());

        FileDownload tail = bob.downloadFile(info.id, data.length - 500, -1);
        assertTrue(tail.isPartial());
        assertArrayEquals(Arrays.copyOfRange(data, data.length - 500, data.length), tail.readAll());

        // Thumbnails and previews are generated in the background.
        byte[] thumbnail = null;
        byte[] preview = null;
        for (int attempt = 0; attempt < 20 && (thumbnail == null || preview == null); attempt++) {
            try {
                if (thumbnail == null) {
                    thumbnail = bob.fileThumbnail(imageInfo.id).readAll();
                }
                if (preview == null) {
                    preview = bob.filePreview(imageInfo.id).readAll();
                }
            } catch (MattermostException e) {
                if (!e.isNotFound() && !e.isForbidden() && e.getStatusCode() < 500) {
                    throw e;
                }
                Thread.sleep(250);
            }
        }
        assertNotNull(thumbnail);
        assertNotNull(preview);
        assertTrue(thumbnail.length > 0 && preview.length > 0);
    }

    @Test
    public void markReadUpdatesLastViewedAt() throws Exception {
        MattermostClient alice = login("alice");
        MattermostClient bob = login("bob");
        User aliceUser = alice.me();
        final Channel dm = bob.createDirectChannel(bob.me().id, aliceUser.id);
        Recorder aliceEvents = connectWebSocket(alice);

        Post sent = post(bob, dm.id, stamp(), null);
        ChannelMember before = alice.channelMember(dm.id);
        assertTrue("bob's post is unread for alice", before.lastViewedAt < sent.createAt);
        Channel channel = alice.channel(dm.id);
        assertTrue(channel.totalMsgCount > before.msgCount);

        ChannelViewResponse view = alice.viewChannel(dm.id);
        assertEquals("OK", view.status);
        Long viewedAt = view.lastViewedAtTimes.get(dm.id);
        assertNotNull(viewedAt);
        assertTrue(viewedAt >= sent.createAt);

        ChannelMember after = alice.channelMember(dm.id);
        assertTrue("last_viewed_at moved", after.lastViewedAt > before.lastViewedAt);
        assertTrue(after.lastViewedAt >= sent.createAt);
        assertEquals("msg_count caught up", alice.channel(dm.id).totalMsgCount, after.msgCount);
        assertEquals(0, after.mentionCount);

        // Alice's other sessions learn about it (11.x sends multiple_channels_viewed).
        WebSocketEvent viewed = aliceEvents.await("channels viewed", new Recorder.Match() {
            @Override
            public boolean matches(WebSocketEvent event) {
                WebSocketEvent.ChannelsViewed v = event.getChannelsViewed();
                return v != null && v.channelIds.contains(dm.id);
            }
        });
        assertNotNull(viewed.getChannelsViewed());

        // Search finds the message.
        String term = sent.message.substring(sent.message.lastIndexOf(' ') + 1);
        assertFalse(term.isEmpty());
        Team team = alice.teams().get(0);
        PostSearchResults results = alice.searchPosts(team.id, term, false, 0, 20);
        assertTrue("search finds the DM post", results.order.contains(sent.id));
        assertNotNull(results.posts.get(sent.id));
    }

    @Test
    public void webSocketResumesAndReplaysMissedEvents() throws Exception {
        MattermostClient alice = login("alice");
        MattermostClient bob = login("bob");
        final Channel dm = bob.createDirectChannel(bob.me().id, alice.me().id);
        Recorder aliceEvents = connectWebSocket(alice);
        MattermostWebSocket socket = lastSocket();
        String connectionId = socket.getState().info.connectionId;

        // Something with a seq first, so there is a sequence number to resume from.
        final Post first = post(bob, dm.id, stamp(), null);
        aliceEvents.await("first posted", new Recorder.Match() {
            @Override
            public boolean matches(WebSocketEvent event) {
                return event.getPosted() != null && event.getPosted().post.id.equals(first.id);
            }
        });

        // Offline while bob posts: the resumed connection replays the event.
        socket.disconnect();
        long deadline = System.currentTimeMillis() + 5000;
        while (socket.getState().kind != ConnectionState.Kind.DISCONNECTED && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        aliceEvents.states.clear();
        final Post missed = post(bob, dm.id, stamp(), null);
        socket.connect();
        ConnectionState state = aliceEvents.awaitConnected();
        assertTrue(socket.getLastUrl(), socket.getLastUrl().contains("connection_id=" + connectionId + "&sequence_number="));
        if (state.info.resumed) {
            assertEquals(connectionId, state.info.connectionId);
            aliceEvents.await("replayed posted", new Recorder.Match() {
                @Override
                public boolean matches(WebSocketEvent event) {
                    return event.getPosted() != null && event.getPosted().post.id.equals(missed.id);
                }
            });
        } else {
            // The server dropped the old connection: the caller must catch up over REST.
            PostList since = alice.posts(dm.id, PostsQuery.since(first.createAt));
            assertTrue(since.posts.containsKey(missed.id));
        }

        // Live events keep flowing after the reconnect.
        final Post after = post(bob, dm.id, stamp(), null);
        aliceEvents.await("posted after reconnect", new Recorder.Match() {
            @Override
            public boolean matches(WebSocketEvent event) {
                return event.getPosted() != null && event.getPosted().post.id.equals(after.id);
            }
        });
        System.out.println("websocket reconnect resumed=" + state.info.resumed);
    }
}
