package org.telegram.relay.mattermost.calls;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A call of the Relay calls plugin ({@code org.relaychat.calls}, contract version 2), port of the iOS
 * {@code RelayCall}. Decoded leniently: a version 1 call (no kind/state) is an active channel call; nested
 * objects may arrive as JSON strings (WebSocket event data).
 */
public final class RelayCall {
    public enum Kind { DIRECT, GROUP, CHANNEL }

    public enum State { RINGING, ACTIVE, ENDED }

    /** {@code end_reason}. */
    public enum EndReason { MISSED, CANCELLED, DECLINED, BUSY, HANGUP, ENDED, FAILED }

    public enum Invitee {
        RINGING, RECEIVED, ACCEPTED, DECLINED, BUSY, MISSED;

        public boolean isRinging() {
            return this == RINGING || this == RECEIVED;
        }
    }

    public String id = "";
    public String channelId = "";
    public String room = "";
    public Kind kind = Kind.CHANNEL;
    public State state = State.ACTIVE;
    public boolean video;
    public String startedBy = "";
    public long startedAt;
    /** Unix ms, 0 when not ringing. */
    public long ringExpiresAt;
    public EndReason endReason;
    public long endAt;
    /** -1 when absent. */
    public long durationMs = -1;
    public int participants;
    public List<String> participantIds = new ArrayList<>();
    /** Absent (empty) for calls that did not ring. */
    public Map<String, Invitee> invitees = new LinkedHashMap<>();
    public String postId;
    public boolean canEnd;

    public boolean isLive() {
        return state != State.ENDED;
    }

    public boolean isRinging(String userId) {
        Invitee invitee = invitees.get(userId);
        return state == State.RINGING && invitee != null && invitee.isRinging();
    }

    public Invitee invitee(String userId) {
        return invitees.get(userId);
    }

    public static RelayCall fromJson(String json) {
        try {
            return from(JsonParser.parseString(json));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {@code null} when [element] is not a call object (no {@code id}). */
    public static RelayCall from(JsonElement element) {
        JsonObject o = object(element);
        if (o == null) {
            return null;
        }
        RelayCall call = new RelayCall();
        call.id = string(o, "id");
        if (call.id.isEmpty()) {
            return null;
        }
        call.channelId = string(o, "channel_id");
        call.room = string(o, "room");
        String kind = string(o, "kind");
        call.kind = "direct".equals(kind) ? Kind.DIRECT : "group".equals(kind) ? Kind.GROUP : Kind.CHANNEL;
        String state = string(o, "state");
        call.state = "ringing".equals(state) ? State.RINGING : "ended".equals(state) ? State.ENDED : State.ACTIVE;
        call.video = bool(o, "video");
        call.startedBy = string(o, "started_by");
        call.startedAt = number(o, "started_at", 0);
        call.ringExpiresAt = number(o, "ring_expires_at", 0);
        call.endReason = endReason(string(o, "end_reason"));
        call.endAt = number(o, "end_at", 0);
        call.durationMs = o.has("duration_ms") && !o.get("duration_ms").isJsonNull() ? number(o, "duration_ms", -1) : -1;
        call.participants = (int) number(o, "participants", 0);
        JsonElement ids = o.get("participant_ids");
        if (ids != null && ids.isJsonArray()) {
            for (JsonElement id : ids.getAsJsonArray()) {
                if (id.isJsonPrimitive()) {
                    call.participantIds.add(id.getAsString());
                }
            }
        }
        JsonObject invitees = object(o.get("invitees"));
        if (invitees != null) {
            for (Map.Entry<String, JsonElement> entry : invitees.entrySet()) {
                Invitee value = parseInvitee(entry.getValue().isJsonPrimitive() ? entry.getValue().getAsString() : "");
                if (value != null) {
                    call.invitees.put(entry.getKey(), value);
                }
            }
        }
        String post = string(o, "post_id");
        call.postId = post.isEmpty() ? null : post;
        call.canEnd = bool(o, "can_end");
        return call;
    }

    public static EndReason endReason(String value) {
        if (value == null) {
            return null;
        }
        switch (value) {
            case "missed": return EndReason.MISSED;
            case "cancelled": return EndReason.CANCELLED;
            case "declined": return EndReason.DECLINED;
            case "busy": return EndReason.BUSY;
            case "hangup": return EndReason.HANGUP;
            case "ended": return EndReason.ENDED;
            case "failed": return EndReason.FAILED;
            default: return null;
        }
    }

    public static Invitee parseInvitee(String value) {
        switch (value) {
            case "ringing": return Invitee.RINGING;
            case "received": return Invitee.RECEIVED;
            case "accepted": return Invitee.ACCEPTED;
            case "declined": return Invitee.DECLINED;
            case "busy": return Invitee.BUSY;
            case "missed": return Invitee.MISSED;
            default: return null;
        }
    }

    /** A JSON object, also when it arrives as a JSON string; else null. */
    static JsonObject object(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonObject()) {
            return element.getAsJsonObject();
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String text = element.getAsString().trim();
            if (text.startsWith("{")) {
                try {
                    JsonElement parsed = JsonParser.parseString(text);
                    return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
                } catch (RuntimeException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
            return "";
        }
        return e.getAsString();
    }

    static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
            return false;
        }
        if (e.getAsJsonPrimitive().isBoolean()) {
            return e.getAsBoolean();
        }
        return "true".equalsIgnoreCase(e.getAsString());
    }

    static long number(JsonObject o, String key, long fallback) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return (long) Double.parseDouble(e.getAsString());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public RelayCall copy() {
        RelayCall c = new RelayCall();
        c.id = id; c.channelId = channelId; c.room = room; c.kind = kind; c.state = state; c.video = video;
        c.startedBy = startedBy; c.startedAt = startedAt; c.ringExpiresAt = ringExpiresAt; c.endReason = endReason;
        c.endAt = endAt; c.durationMs = durationMs; c.participants = participants;
        c.participantIds = new ArrayList<>(participantIds); c.invitees = new LinkedHashMap<>(invitees);
        c.postId = postId; c.canEnd = canEnd;
        return c;
    }

    public List<String> participantIds() {
        return Collections.unmodifiableList(participantIds);
    }

    @Override
    public String toString() {
        return "call " + id + " " + kind.name().toLowerCase() + " " + state.name().toLowerCase()
                + (endReason != null ? " (" + endReason.name().toLowerCase() + ")" : "")
                + " channel " + channelId + " by " + startedBy + " participants " + participantIds + " invitees " + invitees;
    }
}
