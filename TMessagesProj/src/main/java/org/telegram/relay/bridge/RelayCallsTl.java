package org.telegram.relay.bridge;

import org.telegram.relay.calls.RelayCallManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_phone;

import java.util.Map;

/**
 * A8: Telegram's call methods answered by the Relay calls plugin ({@link RelayCallManager}). Telegram's VoIPService
 * and group-call UI keep their own flow; these handlers stand in for Telegram's servers.
 */
final class RelayCallsTl {
    private RelayCallsTl() {
    }

    interface Async {
        void run(RelayCallManager manager, TLObject request, RelayCallManager.Answer answer) throws Exception;
    }

    private static RelayHandlers.Handler async(Async body) {
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

    static void register(Map<Class<?>, RelayHandlers.Handler> map) {
        map.put(TLRPC.TL_messages_getDhConfig.class, (bridge, account, req) -> RelayCallManager.dhConfig((TLRPC.TL_messages_getDhConfig) req));
        map.put(TL_phone.getCallConfig.class, (bridge, account, req) -> {
            TLRPC.TL_dataJSON json = new TLRPC.TL_dataJSON();
            json.data = "{}";
            return json;
        });
        map.put(TL_phone.requestCall.class, async((m, r, a) -> m.requestCall((TL_phone.requestCall) r, a)));
        map.put(TL_phone.receivedCall.class, (bridge, account, req) -> new TLRPC.TL_boolTrue());
        map.put(TL_phone.acceptCall.class, async((m, r, a) -> m.acceptCall((TL_phone.acceptCall) r, a)));
        map.put(TL_phone.confirmCall.class, async((m, r, a) -> m.confirmCall((TL_phone.confirmCall) r, a)));
        map.put(TL_phone.discardCall.class, async((m, r, a) -> m.discardCall((TL_phone.discardCall) r, a)));
        map.put(TL_phone.setCallRating.class, (bridge, account, req) -> emptyUpdates(account.account));
        map.put(TL_phone.saveCallDebug.class, (bridge, account, req) -> new TLRPC.TL_boolTrue());
        map.put(TL_phone.sendSignalingData.class, (bridge, account, req) -> new TLRPC.TL_boolTrue());
        RelayGroupCallsTl.register(map);
    }

    static TLRPC.TL_updates emptyUpdates(int account) {
        TLRPC.TL_updates updates = new TLRPC.TL_updates();
        updates.date = RelayStubs.now(account);
        return updates;
    }
}
