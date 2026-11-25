package org.telegram.relay.calls;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.telegram.relay.account.RelayAccount;
import org.telegram.relay.account.RelayDatabase;
import org.telegram.relay.mattermost.model.Post;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

/**
 * The calls plugin's record post ({@code type: custom_relay_call}, {@code props.relay_call = {v, call_id, kind,
 * video, state, started_by, end_reason, duration_ms, started_at, end_at}}), as Telegram's call service messages (port
 * of iOS {@code RelayCallRecord}): {@code direct} → {@code messageActionPhoneCall} (missed/cancelled → missed,
 * declined/busy → busy, hangup/ended → hangup, failed → disconnect; incoming when the author, the caller, is someone
 * else), {@code group}/{@code channel} → {@code messageActionGroupCall} (no duration while live). The plugin edits the
 * post when the call ends, so the message follows as an edit.
 */
public final class RelayCallRecord {
    public static final String POST_TYPE = "custom_relay_call";

    public final String callId;
    public final String kind;
    public final boolean video;
    public final String state;
    public final String endReason;
    public final long durationMs;

    private RelayCallRecord(String callId, String kind, boolean video, String state, String endReason, long durationMs) {
        this.callId = callId;
        this.kind = kind;
        this.video = video;
        this.state = state;
        this.endReason = endReason;
        this.durationMs = durationMs;
    }

    /** The record of a post, or null for other posts. */
    public static RelayCallRecord of(Post post) {
        if (post == null || !POST_TYPE.equals(post.type) || post.props == null) {
            return null;
        }
        JsonElement element = post.props.get("relay_call");
        JsonObject o = null;
        if (element != null && element.isJsonObject()) {
            o = element.getAsJsonObject();
        } else if (element != null && element.isJsonPrimitive()) {
            try {
                JsonElement parsed = JsonParser.parseString(element.getAsString());
                o = parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
            } catch (RuntimeException ignored) {
                o = null;
            }
        }
        if (o == null) {
            return null;
        }
        String callId = string(o, "call_id");
        if (callId.isEmpty()) {
            return null;
        }
        long duration = 0;
        try {
            duration = o.has("duration_ms") && !o.get("duration_ms").isJsonNull() ? (long) o.get("duration_ms").getAsDouble() : 0;
        } catch (RuntimeException ignored) {
            duration = 0;
        }
        boolean video = o.has("video") && o.get("video").isJsonPrimitive() && o.get("video").getAsBoolean();
        return new RelayCallRecord(callId, string(o, "kind"), video, string(o, "state"), string(o, "end_reason"), duration);
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? "" : e.getAsString();
    }

    public boolean isEnded() {
        return "ended".equals(state);
    }

    public TLRPC.MessageAction action(RelayAccount account) {
        long tlId = account.db().tlId(RelayDatabase.KIND_CALL, callId);
        if ("direct".equals(kind)) {
            TLRPC.TL_messageActionPhoneCall action = new TLRPC.TL_messageActionPhoneCall();
            action.call_id = tlId;
            action.video = video;
            if (isEnded()) {
                switch (endReason) {
                    case "missed":
                    case "cancelled":
                        action.reason = new TLRPC.TL_phoneCallDiscardReasonMissed();
                        break;
                    case "declined":
                    case "busy":
                        action.reason = new TLRPC.TL_phoneCallDiscardReasonBusy();
                        break;
                    case "failed":
                        action.reason = new TLRPC.TL_phoneCallDiscardReasonDisconnect();
                        break;
                    default:
                        action.reason = new TLRPC.TL_phoneCallDiscardReasonHangup();
                        break;
                }
                action.flags |= TLObject.FLAG_0;
                if (durationMs > 0) {
                    action.duration = (int) Math.max(1, Math.round(durationMs / 1000.0));
                    action.flags |= TLObject.FLAG_1;
                }
            }
            return action;
        }
        TLRPC.TL_messageActionGroupCall action = new TLRPC.TL_messageActionGroupCall();
        action.call = RelayGroupSession.inputCall(tlId, RelayGroupSession.accessHash(tlId));
        if (isEnded()) {
            action.duration = (int) Math.max(0, Math.round(durationMs / 1000.0));
            action.flags |= TLObject.FLAG_0;
        }
        return action;
    }
}
