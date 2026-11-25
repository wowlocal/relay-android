package org.telegram.relay.mattermost.calls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

/** Port of the iOS core's RelayCallsEventTests (wire contract version 2). */
public class RelayCallsEventTest {
    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static final String CALL = "{\"id\":\"c1\",\"channel_id\":\"dm\",\"room\":\"relay_c1\",\"kind\":\"direct\",\"state\":\"ringing\",\"video\":true,"
            + "\"started_by\":\"u1\",\"started_at\":1700000000000,\"ring_expires_at\":1700000045000,\"participants\":1,\"participant_ids\":[\"u1\"],"
            + "\"invitees\":{\"u2\":\"ringing\"},\"post_id\":\"p1\",\"can_end\":true}";

    @Test
    public void incoming() {
        RelayCallsEvent e = RelayCallsEvent.decode("custom_org.relaychat.calls_incoming",
                json("{\"call\":" + CALL + ",\"caller\":{\"id\":\"u1\",\"username\":\"bob\",\"display_name\":\"Bob\"}}"));
        assertEquals(RelayCallsEvent.Type.INCOMING, e.type);
        assertEquals("c1", e.call.id);
        assertEquals(RelayCall.Kind.DIRECT, e.call.kind);
        assertEquals(RelayCall.State.RINGING, e.call.state);
        assertTrue(e.call.video);
        assertEquals(1700000045000L, e.call.ringExpiresAt);
        assertTrue(e.call.isRinging("u2"));
        assertEquals("bob", e.callerUsername);
        assertEquals("Bob", e.callerName);
        assertEquals("p1", e.call.postId);
    }

    @Test
    public void incomingWithoutCallerUsesTheStarter() {
        RelayCallsEvent e = RelayCallsEvent.decode("custom_org.relaychat.calls_incoming", json("{\"call\":" + CALL + "}"));
        assertEquals("u1", e.callerId);
    }

    @Test
    public void callStateUpdatedEnded() {
        RelayCallsEvent s = RelayCallsEvent.decode("custom_org.relaychat.calls_call_state",
                json("{\"call\":" + CALL + ",\"user_id\":\"u2\",\"device_id\":\"d2\",\"action\":\"received\"}"));
        assertEquals(RelayCallsEvent.Action.RECEIVED, s.action);
        assertEquals("u2", s.userId);
        assertEquals("d2", s.deviceId);
        RelayCallsEvent ended = RelayCallsEvent.decode("custom_org.relaychat.calls_call_ended",
                json("{\"call\":{\"id\":\"c1\",\"channel_id\":\"dm\",\"kind\":\"direct\",\"state\":\"ended\",\"end_reason\":\"hangup\",\"duration_ms\":8000},\"channel_id\":\"dm\",\"call_id\":\"c1\"}"));
        assertEquals(RelayCallsEvent.Type.CALL_ENDED, ended.type);
        assertEquals(RelayCall.EndReason.HANGUP, ended.call.endReason);
        assertEquals(8000, ended.call.durationMs);
        assertEquals(RelayCallsEvent.Type.CALL_UPDATED, RelayCallsEvent.decode("custom_org.relaychat.calls_call_updated", json("{\"call\":" + CALL + "}")).type);
    }

    @Test
    public void versionOneCallIsAnActiveChannelCall() {
        RelayCall c = RelayCall.fromJson("{\"id\":\"c1\",\"channel_id\":\"ch\",\"participants\":2}");
        assertEquals(RelayCall.Kind.CHANNEL, c.kind);
        assertEquals(RelayCall.State.ACTIVE, c.state);
        assertEquals(-1, c.durationMs);
    }

    @Test
    public void nestedObjectsMayBeJsonStrings() {
        JsonObject data = new JsonObject();
        data.addProperty("call", CALL);
        data.addProperty("caller", "{\"id\":\"u1\",\"username\":\"bob\"}");
        RelayCallsEvent e = RelayCallsEvent.decode("custom_org.relaychat.calls_incoming", data);
        assertEquals("c1", e.call.id);
        assertEquals("bob", e.callerUsername);
    }

    @Test
    public void unknownValuesDecodeLeniently() {
        RelayCall c = RelayCall.fromJson("{\"id\":\"c1\",\"kind\":\"weird\",\"state\":\"weird\",\"invitees\":{\"a\":\"weird\",\"b\":\"busy\"},\"end_reason\":\"weird\"}");
        assertEquals(RelayCall.Kind.CHANNEL, c.kind);
        assertNull(c.endReason);
        assertEquals(1, c.invitees.size());
        assertEquals(RelayCallsEvent.Action.UNKNOWN, RelayCallsEvent.decode("custom_org.relaychat.calls_call_state",
                json("{\"call\":" + CALL + ",\"action\":\"teleported\"}")).action);
    }

    @Test
    public void otherEventsAndMalformedDataAreIgnored() {
        assertNull(RelayCallsEvent.decode("posted", json("{\"call\":" + CALL + "}")));
        assertNull(RelayCallsEvent.decode("custom_org.relaychat.calls_incoming", json("{}")));
        assertNull(RelayCallsEvent.decode("custom_org.relaychat.calls_whatever", json("{\"call\":" + CALL + "}")));
        assertFalse(RelayCallsEvent.isRelayCallsEvent("custom_com.mattermost.calls_call_start"));
    }

    @Test
    public void mediaUrlsAndIds() {
        assertTrue(RelayCallsClient.isAllowedMediaUrl("wss://livekit.example.com"));
        assertTrue(RelayCallsClient.isAllowedMediaUrl("ws://127.0.0.1:7880"));
        assertTrue(RelayCallsClient.isAllowedMediaUrl("ws://localhost:7880"));
        assertFalse(RelayCallsClient.isAllowedMediaUrl("ws://example.com:7880"));
        assertFalse(RelayCallsClient.isAllowedMediaUrl("wss://user:pw@example.com"));
        assertFalse(RelayCallsClient.isAllowedMediaUrl("ftp://example.com"));
        try {
            RelayCallsClient.validateChannel("short");
            throw new AssertionError("accepted a bad channel id");
        } catch (RelayCallsException expected) {
            assertEquals(RelayCallsException.INVALID_CHANNEL, expected.status);
        }
    }
}
