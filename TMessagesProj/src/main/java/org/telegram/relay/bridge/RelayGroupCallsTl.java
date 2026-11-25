package org.telegram.relay.bridge;

import org.telegram.relay.calls.RelayCallManager;
import org.telegram.relay.calls.RelayGroupSession;
import org.telegram.tgnet.OutputSerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;
import org.telegram.tgnet.tl.TL_phone;

import java.util.List;
import java.util.Map;

/**
 * A8: Telegram's group-call (video chat) methods over the plugin's channel and group calls
 * ({@link org.telegram.relay.calls.RelayGroupSession}). Admin-only and Telegram-only features (titles, recording,
 * join muted, invite links, scheduling, RTMP streams) answer errors; their UI is hidden.
 */
final class RelayGroupCallsTl {
    private RelayGroupCallsTl() {
    }

    /** A {@code Vector<int>} response. */
    static final class RawIntVector extends TLObject {
        final List<Integer> values;

        RawIntVector(List<Integer> values) {
            this.values = values;
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(Vector.constructor);
            stream.writeInt32(values.size());
            for (int v : values) {
                stream.writeInt32(v);
            }
        }
    }

    static void register(Map<Class<?>, RelayHandlers.Handler> map) {
        map.put(TL_phone.createGroupCall.class, async((m, r, a) -> RelayGroupSession.create(m, (TL_phone.createGroupCall) r, a)));
        map.put(TL_phone.joinGroupCall.class, async((m, r, a) -> RelayGroupSession.join(m, (TL_phone.joinGroupCall) r, a)));
        map.put(TL_phone.leaveGroupCall.class, async((m, r, a) -> RelayGroupSession.leave(m, ((TL_phone.leaveGroupCall) r).call, false, a)));
        map.put(TL_phone.discardGroupCall.class, async((m, r, a) -> RelayGroupSession.leave(m, ((TL_phone.discardGroupCall) r).call, true, a)));
        map.put(TL_phone.getGroupCall.class, (bridge, account, req) -> withManager(account, m -> RelayGroupSession.getGroupCall(m, ((TL_phone.getGroupCall) req).call, false)));
        map.put(TL_phone.getGroupParticipants.class, (bridge, account, req) -> withManager(account, m -> RelayGroupSession.getGroupCall(m, ((TL_phone.getGroupParticipants) req).call, true)));
        map.put(TL_phone.editGroupCallParticipant.class, async((m, r, a) -> RelayGroupSession.edit(m, (TL_phone.editGroupCallParticipant) r, a)));
        map.put(TL_phone.joinGroupCallPresentation.class, async((m, r, a) -> RelayGroupSession.presentation(m, ((TL_phone.joinGroupCallPresentation) r).call, true, a)));
        map.put(TL_phone.leaveGroupCallPresentation.class, async((m, r, a) -> RelayGroupSession.presentation(m, ((TL_phone.leaveGroupCallPresentation) r).call, false, a)));
        // The client polls this with its own source while connected; every source stays valid in Relay.
        map.put(TL_phone.checkGroupCall.class, (bridge, account, req) -> new RawIntVector(((TL_phone.checkGroupCall) req).sources));
        map.put(TL_phone.getGroupCallJoinAs.class, (bridge, account, req) -> {
            TL_phone.joinAsPeers res = new TL_phone.joinAsPeers();
            TLRPC.TL_peerUser self = new TLRPC.TL_peerUser();
            self.user_id = account.selfTlId();
            res.peers.add(self);
            TLRPC.TL_user user = account.tlUser(account.session.userId);
            if (user != null) {
                res.users.add(user);
            }
            return res;
        });
        map.put(TL_phone.saveDefaultGroupCallJoinAs.class, (bridge, account, req) -> new TLRPC.TL_boolTrue());
        map.put(TL_phone.toggleGroupCallSettings.class, (bridge, account, req) -> RelayCallsTl.emptyUpdates(account.account));
        map.put(TL_phone.editGroupCallTitle.class, (bridge, account, req) -> RelayTl.error(400, "RELAY_UNSUPPORTED"));
        map.put(TL_phone.toggleGroupCallRecord.class, (bridge, account, req) -> RelayTl.error(400, "RELAY_UNSUPPORTED"));
        map.put(TL_phone.exportGroupCallInvite.class, (bridge, account, req) -> RelayTl.error(400, "RELAY_UNSUPPORTED"));
        map.put(TL_phone.inviteToGroupCall.class, (bridge, account, req) -> RelayTl.error(400, "RELAY_UNSUPPORTED"));
        map.put(TL_phone.startScheduledGroupCall.class, (bridge, account, req) -> RelayTl.error(400, "RELAY_UNSUPPORTED"));
        map.put(TL_phone.toggleGroupCallStartSubscription.class, (bridge, account, req) -> RelayTl.error(400, "RELAY_UNSUPPORTED"));
    }

    interface WithManager {
        TLObject run(RelayCallManager manager) throws Exception;
    }

    private static TLObject withManager(org.telegram.relay.account.RelayAccount account, WithManager body) throws Exception {
        RelayCallManager manager = RelayCallManager.of(account.account);
        return manager == null ? RelayTl.error(400, "RELAY_NO_CALLS") : body.run(manager);
    }

    private static RelayHandlers.Handler async(RelayCallsTl.Async body) {
        return (bridge, account, req) -> {
            RelayCallManager manager = RelayCallManager.of(account.account);
            if (manager == null) {
                return RelayTl.error(400, "RELAY_NO_CALLS");
            }
            RelayBridge.Deferred deferred = bridge.defer();
            body.run(manager, req, deferred::complete);
            return RelayBridge.DEFERRED;
        };
    }
}
