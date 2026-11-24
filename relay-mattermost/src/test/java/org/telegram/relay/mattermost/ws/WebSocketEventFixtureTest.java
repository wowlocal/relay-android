package org.telegram.relay.mattermost.ws;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import org.junit.Test;
import org.telegram.relay.mattermost.Fixtures;
import org.telegram.relay.mattermost.model.Channel;
import org.telegram.relay.mattermost.model.Post;
import org.telegram.relay.mattermost.model.Reaction;
import org.telegram.relay.mattermost.model.User;
import org.telegram.relay.mattermost.model.UserThread;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** WebSocket frames from the recorded fixtures (and synthetic ones for events not in the recording). */
public class WebSocketEventFixtureTest {
    private static final String ALICE = "x6y9ko87wtrq7m9k3dbk3qt7me";
    private static final String BOB = "9ik4bw7aybbqupuhs9fjsprsie";
    private static final String SANDBOX = "4gq5dooa3iguik5izypw3ugjmh";
    private static final String POST = "zgjwsgzgj3nmbjrbxrtbg4jocr";

    private static WebSocketEvent event(String fixture) {
        String raw = Fixtures.ws(fixture);
        WebSocketEvent event = WebSocketEvent.parse(raw);
        assertNotNull(fixture, event);
        assertSame(raw, event.raw);
        return event;
    }

    @Test
    public void hello() {
        WebSocketEvent event = event("hello");
        assertEquals(WebSocketEventType.HELLO, event.type);
        assertEquals(Long.valueOf(0), event.seq);
        WebSocketEvent.Hello hello = event.getHello();
        assertEquals("we4619i9x7yq3nqwpnjjf58dna", hello.connectionId);
        assertTrue(hello.serverVersion.startsWith("11.7.14."));
        assertEquals(ALICE, event.broadcast.userId);
        assertTrue("omit_users: null decodes as empty", event.broadcast.omitUsers.isEmpty());
        assertNull(event.getPosted());
    }

    @Test
    public void actionResponse() {
        Object frame = WebSocketEvent.parseFrame(Fixtures.ws("response_seq_1"));
        assertTrue(frame instanceof WebSocketResponse);
        WebSocketResponse response = (WebSocketResponse) frame;
        assertTrue(response.isOk());
        assertEquals(1, response.seqReply);
        assertEquals("away", response.data.get(BOB).getAsString());
        assertFalse(response.isNotAuthenticated());
    }

    @Test
    public void typing() {
        WebSocketEvent event = event("typing");
        WebSocketEvent.Typing typing = event.getTyping();
        assertEquals(BOB, typing.userId);
        assertEquals(SANDBOX, typing.channelId);
        assertEquals("", typing.parentId);
        assertEquals(Boolean.TRUE, event.broadcast.omitUsers.get(BOB));
    }

    @Test
    public void posted() {
        WebSocketEvent event = event("posted");
        assertEquals(Long.valueOf(2), event.seq);
        WebSocketEvent.Posted posted = event.getPosted();
        assertNotNull(posted);
        assertEquals(POST, posted.post.id);
        assertEquals(BOB, posted.post.userId);
        assertEquals(SANDBOX, posted.post.channelId);
        assertEquals("fixture capture: hello from bob :wave:", posted.post.message);
        assertFalse(posted.post.isReply());
        assertEquals("O", posted.channelType);
        assertEquals("sandbox", posted.channelName);
        assertEquals("Sandbox", posted.channelDisplayName);
        assertEquals("@bob", posted.senderName);
        assertEquals("x3wnh555offqbkfx9usn17xo1y", posted.teamId);
        assertEquals(Boolean.TRUE, posted.setOnline);
        assertTrue(posted.mentions.isEmpty());
        assertEquals(SANDBOX, event.getChannelId());
        assertNotNull(event.getPost());
    }

    @Test
    public void postedReply() {
        WebSocketEvent.Posted posted = event("posted_reply").getPosted();
        assertNotNull(posted);
        assertEquals(POST, posted.post.rootId);
        assertTrue(posted.post.isReply());
    }

    @Test
    public void editedAndDeleted() {
        Post edited = event("post_edited").getEditedPost();
        assertNotNull(edited);
        assertEquals(POST, edited.id);
        assertTrue(edited.isEdited());
        assertTrue(edited.message.endsWith("(edited)"));

        WebSocketEvent deletedEvent = event("post_deleted");
        Post deleted = deletedEvent.getDeletedPost();
        assertNotNull(deleted);
        assertEquals(POST, deleted.id);
        assertEquals(SANDBOX, deletedEvent.broadcast.channelId);
        assertNull(deletedEvent.getEditedPost());
    }

    @Test
    public void reactions() {
        Reaction added = event("reaction_added").getReaction();
        assertEquals(BOB, added.userId);
        assertEquals(POST, added.postId);
        assertEquals("+1", added.emojiName);
        assertEquals(SANDBOX, added.channelId);
        WebSocketEvent removed = event("reaction_removed");
        assertEquals(WebSocketEventType.REACTION_REMOVED, removed.type);
        assertNotNull(removed.getReaction());
    }

    @Test
    public void threadUpdated() {
        UserThread thread = event("thread_updated").getThread();
        assertNotNull(thread);
        assertEquals(POST, thread.id);
        assertEquals(1, thread.replyCount);
        assertEquals("alice", thread.participants.get(0).username);
        assertNotNull(thread.post);
        assertTrue(thread.post.isEdited());
    }

    @Test
    public void multipleChannelsViewed() {
        WebSocketEvent.ChannelsViewed viewed = event("multiple_channels_viewed").getChannelsViewed();
        assertEquals(Collections.singletonList(SANDBOX), viewed.channelIds);
        assertEquals(Long.valueOf(1792998044185L), viewed.lastViewedAt.get(SANDBOX));
    }

    @Test
    public void fullRecordingParsesInOrder() {
        JsonArray frames = JsonParser.parseString(Fixtures.ws("events")).getAsJsonArray();
        long lastSeq = -1;
        int events = 0;
        int responses = 0;
        for (JsonElement element : frames) {
            Object frame = WebSocketEvent.parseFrame(element.toString());
            assertNotNull(element.toString(), frame);
            if (frame instanceof WebSocketEvent) {
                WebSocketEvent event = (WebSocketEvent) frame;
                events++;
                assertTrue("known event " + event.event, event.type != WebSocketEventType.UNKNOWN);
                if (event.seq != null) {
                    assertTrue("seq increases", event.seq > lastSeq);
                    lastSeq = event.seq;
                }
            } else {
                responses++;
            }
        }
        assertTrue(events >= 10);
        assertEquals(1, responses);
    }

    @Test
    public void manifestListsEveryRecordedEventType() {
        // ws_events_seen lists fixture labels: event names plus posted_reply / response_seq_1.
        JsonArray seen = Fixtures.manifest().getAsJsonArray("ws_events_seen");
        for (JsonElement element : seen) {
            String name = element.getAsString();
            if (name.equals("posted_reply") || name.startsWith("response_")) {
                continue;
            }
            assertTrue(name, WebSocketEventType.from(name) != WebSocketEventType.UNKNOWN);
        }
    }

    // MARK: - Synthetic frames (shapes from the Mattermost server source)

    private static WebSocketEvent synthetic(String event, String data, String broadcast) {
        String raw = "{\"event\":\"" + event + "\",\"data\":" + data + ",\"broadcast\":" + broadcast + ",\"seq\":9}";
        WebSocketEvent parsed = WebSocketEvent.parse(raw);
        assertNotNull(parsed);
        return parsed;
    }

    @Test
    public void statusAndUserEvents() {
        WebSocketEvent.StatusChange status = synthetic("status_change", "{\"status\":\"away\",\"user_id\":\"" + BOB + "\"}", "{\"user_id\":\"" + ALICE + "\"}").getStatusChange();
        assertEquals(BOB, status.userId);
        assertEquals("away", status.status);

        User user = synthetic("user_updated", "{\"user\":{\"id\":\"" + BOB + "\",\"username\":\"bob\",\"last_picture_update\":5}}", "{}").getUser();
        assertEquals("bob", user.username);
        assertEquals(5, user.lastPictureUpdate);
    }

    @Test
    public void channelEvents() {
        WebSocketEvent.ChannelChange created = synthetic("channel_created", "{\"channel_id\":\"c1\",\"team_id\":\"t1\"}", "{\"user_id\":\"" + ALICE + "\"}").getChannelChange();
        assertEquals("c1", created.channelId);
        assertEquals("t1", created.teamId);

        Channel updated = synthetic("channel_updated", "{\"channel\":\"{\\\"id\\\":\\\"c1\\\",\\\"type\\\":\\\"P\\\",\\\"display_name\\\":\\\"Renamed\\\"}\"}", "{\"channel_id\":\"c1\"}").getChannel();
        assertEquals("Renamed", updated.displayName);
        assertTrue(updated.isPrivate());

        WebSocketEvent.ChannelChange deleted = synthetic("channel_deleted", "{\"channel_id\":\"c1\",\"delete_at\":1700}", "{\"team_id\":\"t1\"}").getChannelChange();
        assertEquals(1700, deleted.deleteAt);
        assertEquals("t1", deleted.teamId);

        WebSocketEvent.Membership added = synthetic("user_added", "{\"user_id\":\"" + BOB + "\",\"team_id\":\"t1\"}", "{\"channel_id\":\"c1\"}").getMembership();
        assertEquals(BOB, added.userId);
        assertEquals("c1", added.channelId);
        WebSocketEvent.Membership removedSelf = synthetic("user_removed", "{\"channel_id\":\"c1\",\"remover_id\":\"" + BOB + "\"}", "{\"user_id\":\"" + ALICE + "\"}").getMembership();
        assertEquals(ALICE, removedSelf.userId);
        assertEquals("c1", removedSelf.channelId);
        assertEquals(BOB, removedSelf.removerId);

        WebSocketEvent.DirectAdded direct = synthetic("direct_added", "{\"creator_id\":\"" + BOB + "\",\"teammate_id\":\"" + ALICE + "\"}", "{\"channel_id\":\"dm\"}").getDirectAdded();
        assertEquals("dm", direct.channelId);
        assertEquals(Collections.singletonList(ALICE), direct.teammateIds);
        WebSocketEvent.DirectAdded group = synthetic("group_added", "{\"teammate_ids\":\"[\\\"a\\\",\\\"b\\\"]\"}", "{\"channel_id\":\"gm\"}").getDirectAdded();
        assertEquals(Arrays.asList("a", "b"), group.teammateIds);

        WebSocketEvent.ChannelsViewed viewed = synthetic("channel_viewed", "{\"channel_id\":\"c1\"}", "{}").getChannelsViewed();
        assertEquals(Collections.singletonList("c1"), viewed.channelIds);
        assertTrue(viewed.lastViewedAt.isEmpty());
    }

    @Test
    public void threadAndUnreadEvents() {
        WebSocketEvent.ThreadReadChanged read = synthetic("thread_read_changed", "{\"thread_id\":\"r1\",\"timestamp\":99,\"unread_replies\":0,\"unread_mentions\":0,\"channel_id\":\"c1\"}", "{\"team_id\":\"t1\"}").getThreadReadChanged();
        assertEquals("r1", read.threadId);
        assertEquals(99, read.timestamp);
        assertEquals("t1", read.teamId);

        WebSocketEvent.PostUnread unread = synthetic("post_unread", "{\"channel_id\":\"c1\",\"post_id\":\"p1\",\"last_viewed_at\":10,\"msg_count\":4,\"mention_count\":1}", "{}").getPostUnread();
        assertEquals("p1", unread.postId);
        assertEquals(10, unread.lastViewedAt);
        assertEquals(4, unread.msgCount);
        assertEquals(1, unread.mentionCount);

        List<org.telegram.relay.mattermost.model.Preference> preferences = synthetic("preferences_changed", "{\"preferences\":\"[{\\\"user_id\\\":\\\"u\\\",\\\"category\\\":\\\"favorite_channel\\\",\\\"name\\\":\\\"c1\\\",\\\"value\\\":\\\"true\\\"}]\"}", "{}").getPreferences();
        assertEquals(1, preferences.size());
        assertEquals("favorite_channel", preferences.get(0).category);
    }

    @Test
    public void unknownEventsKeepTheirName() {
        WebSocketEvent event = synthetic("custom_plugin_event", "{\"x\":1}", "{}");
        assertEquals(WebSocketEventType.UNKNOWN, event.type);
        assertEquals("custom_plugin_event", event.event);
        assertEquals(1, event.data.get("x").getAsInt());
        assertNull(WebSocketEvent.parseFrame("not json"));
        assertNull(WebSocketEvent.parseFrame("{\"foo\":1}"));
    }
}
