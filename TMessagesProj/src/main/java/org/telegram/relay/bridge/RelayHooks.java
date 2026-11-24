package org.telegram.relay.bridge;

import org.telegram.relay.RelayConfig;
import org.telegram.relay.account.RelayAccount;
import org.telegram.tgnet.TLObject;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Small hooks called from Telegram's controllers in Relay mode.
 *
 * <p><b>Peer-aware message lookups.</b> Telegram keys private-chat messages by an account-wide id,
 * but Relay allocates message ids per dialog (one {@code MessageIdAllocator} space per chat, DMs
 * included; storage is keyed {@code (mid, uid)} anyway). A few client paths send ids without a peer
 * ({@code messages.getMessages} for reply previews and pinned messages). Those call sites tag the
 * request with its dialog via {@link #tagDialog}; the bridge reads the tag back with
 * {@link #taggedDialog}. Untagged requests get {@code messageEmpty}.
 */
public final class RelayHooks {
    private RelayHooks() {
    }

    private static final Map<TLObject, Long> dialogTags = Collections.synchronizedMap(new WeakHashMap<>());

    /** Remembers which dialog a peer-less request ({@code messages.getMessages}) is about. Returns the request. */
    public static <T extends TLObject> T tagDialog(T request, long dialogId) {
        if (RelayConfig.ENABLED && request != null && dialogId != 0) {
            dialogTags.put(request, dialogId);
        }
        return request;
    }

    static long taggedDialog(TLObject request) {
        Long dialogId = dialogTags.get(request);
        return dialogId != null ? dialogId : 0;
    }

    /** Debug dump of the bridge's id map for one dialog (see {@code RelayDebugHooks}). */
    public static String dumpDialog(int account, long dialogId) {
        RelayAccount relayAccount = RelayAccount.get(account);
        if (relayAccount == null) {
            return "no session";
        }
        try {
            return RelayReadPath.of(relayAccount).dumpDialog(dialogId);
        } catch (Exception e) {
            return "dump failed: " + e;
        }
    }

    /** The bridge's dialog id for a Mattermost channel id (debug), or 0. */
    public static long dialogIdOfChannel(int account, String channelId) {
        RelayAccount relayAccount = RelayAccount.get(account);
        if (relayAccount == null) {
            return 0;
        }
        try {
            RelayReadPath path = RelayReadPath.of(relayAccount);
            return path.directory.dialogId(path.directory.channel(channelId));
        } catch (Exception e) {
            return 0;
        }
    }
}
