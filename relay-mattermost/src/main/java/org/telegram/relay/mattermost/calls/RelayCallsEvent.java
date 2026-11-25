package org.telegram.relay.mattermost.calls;

import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.List;

/**
 * A plugin event: Mattermost delivers them as {@code custom_org.relaychat.calls_<name>} WebSocket events
 * ({@code incoming}, {@code call_state}, {@code call_updated}, {@code call_ended}); {@link Type#ACTIVE_CALLS}
 * is synthesised by the client after each (re)connect from {@code GET /calls/active} (events are not replayed).
 * Port of the iOS {@code RelayCallsEvent}.
 */
public final class RelayCallsEvent {
    public static final String PREFIX = "custom_" + RelayCallsClient.PLUGIN_ID + "_";

    public enum Type { INCOMING, CALL_STATE, CALL_UPDATED, CALL_ENDED, ACTIVE_CALLS }

    /** {@code call_state} actions. */
    public enum Action { RECEIVED, ACCEPTED, DECLINED, BUSY, MISSED, CANCELLED, HANGUP, JOINED, LEFT, UNKNOWN }

    public final Type type;
    /** The call (null only for {@link Type#ACTIVE_CALLS}). */
    public final RelayCall call;
    /** {@code incoming}: the caller. */
    public final String callerId;
    public final String callerUsername;
    public final String callerName;
    /** {@code call_state}. */
    public final String userId;
    public final String deviceId;
    public final Action action;
    /** {@link Type#ACTIVE_CALLS}. */
    public final List<RelayCall> calls;

    private RelayCallsEvent(Type type, RelayCall call, String callerId, String callerUsername, String callerName, String userId, String deviceId, Action action, List<RelayCall> calls) {
        this.type = type;
        this.call = call;
        this.callerId = callerId;
        this.callerUsername = callerUsername;
        this.callerName = callerName;
        this.userId = userId;
        this.deviceId = deviceId;
        this.action = action;
        this.calls = calls;
    }

    public static boolean isRelayCallsEvent(String eventName) {
        return eventName != null && eventName.startsWith(PREFIX);
    }

    public static RelayCallsEvent activeCalls(List<RelayCall> calls) {
        return new RelayCallsEvent(Type.ACTIVE_CALLS, null, null, null, null, null, null, null, Collections.unmodifiableList(calls));
    }

    public static RelayCallsEvent incoming(RelayCall call, String callerId, String callerUsername, String callerName) {
        return new RelayCallsEvent(Type.INCOMING, call, callerId, callerUsername, callerName, null, null, null, null);
    }

    public static RelayCallsEvent callState(RelayCall call, String userId, String deviceId, Action action) {
        return new RelayCallsEvent(Type.CALL_STATE, call, null, null, null, userId, deviceId, action, null);
    }

    public static RelayCallsEvent updated(RelayCall call) {
        return new RelayCallsEvent(Type.CALL_UPDATED, call, null, null, null, null, null, null, null);
    }

    public static RelayCallsEvent ended(RelayCall call) {
        return new RelayCallsEvent(Type.CALL_ENDED, call, null, null, null, null, null, null, null);
    }

    /** null for other events or data without a call. */
    public static RelayCallsEvent decode(String eventName, JsonObject data) {
        if (!isRelayCallsEvent(eventName) || data == null) {
            return null;
        }
        String name = eventName.substring(PREFIX.length());
        RelayCall call = RelayCall.from(data.get("call"));
        if (call == null) {
            return null;
        }
        switch (name) {
            case "incoming": {
                JsonObject caller = RelayCall.object(data.get("caller"));
                String id = caller == null ? call.startedBy : RelayCall.string(caller, "id");
                return incoming(call, id.isEmpty() ? call.startedBy : id, caller == null ? "" : RelayCall.string(caller, "username"),
                        caller == null ? "" : RelayCall.string(caller, "display_name"));
            }
            case "call_state":
                return callState(call, RelayCall.string(data, "user_id"), RelayCall.string(data, "device_id"), action(RelayCall.string(data, "action")));
            case "call_updated":
                return updated(call);
            case "call_ended":
                return ended(call);
            default:
                return null;
        }
    }

    static Action action(String value) {
        switch (value) {
            case "received": return Action.RECEIVED;
            case "accepted": return Action.ACCEPTED;
            case "declined": return Action.DECLINED;
            case "busy": return Action.BUSY;
            case "missed": return Action.MISSED;
            case "cancelled": return Action.CANCELLED;
            case "hangup": return Action.HANGUP;
            case "joined": return Action.JOINED;
            case "left": return Action.LEFT;
            default: return Action.UNKNOWN;
        }
    }

    @Override
    public String toString() {
        switch (type) {
            case ACTIVE_CALLS: return "active calls " + calls.size();
            case CALL_STATE: return "call_state " + action + " by " + userId + ":" + deviceId + " " + call;
            default: return type.name().toLowerCase() + " " + call;
        }
    }

}
