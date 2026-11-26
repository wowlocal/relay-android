package org.telegram.relay.bridge;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.relay.RelayConfig;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

/**
 * DM threads: the DM counterpart of what {@code channelDifferenceTooLong} does for a megagroup
 * ({@code MessagesStorage.overwriteChannel}). A DM's main history depends on collapsed reply threads (roots only
 * with CRT, every post without), and the client keeps it in its message cache with "no hole" ranges, so when CRT
 * switches, or on the first start of a build that collapses DM threads (caches of older builds have the replies
 * inline and roots without their "N Replies"), the DM's cached history is dropped and the dialog gets the bridge's
 * row (preview = newest root with CRT): the next open loads the history from the bridge again.
 *
 * <p>Telegram has no private-chat "too long" answer short of {@code updates.differenceTooLong} (which reloads every
 * chat), so this runs client-side: storage first (the cached messages, holes and media of the DM; the dialog row is
 * rewritten with first holes, as {@code overwriteChannel} does), then the in-memory chat list (the old preview is
 * marked deleted so {@code processDialogsUpdate} takes the new one even when its id is lower). Cached files stay.
 * An open DM keeps what it shows until it is opened again.
 */
final class RelayDmCache {
    private static final String TAG = RelayConfig.TAG_BRIDGE;

    private RelayDmCache() {
    }

    /** {@code dialogs}: the bridge's rows (dialog + preview message) of DMs, with their users. Any thread. */
    static void overwrite(int account, TLRPC.TL_messages_dialogs dialogs) {
        if (dialogs == null || dialogs.dialogs.isEmpty()) {
            return;
        }
        MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> {
            SQLiteDatabase db = storage.getDatabase();
            for (TLRPC.Dialog dialog : dialogs.dialogs) {
                long did = dialog.id != 0 ? dialog.id : (dialog.peer != null ? dialog.peer.user_id : 0);
                if (did <= 0) {
                    continue;
                }
                dialog.id = did;
                SQLiteCursor cursor = null;
                try {
                    cursor = db.queryFinalized("SELECT pinned, folder_id FROM dialogs WHERE did = " + did);
                    if (cursor.next()) {
                        int pinned = cursor.intValue(0);
                        if (pinned != 0) {
                            dialog.pinned = true;
                            dialog.pinnedNum = pinned;
                        }
                        dialog.folder_id = cursor.intValue(1);
                    }
                    cursor.dispose();
                    cursor = null;
                    db.executeFast("DELETE FROM messages_v2 WHERE uid = " + did).stepThis().dispose();
                    db.executeFast("DELETE FROM messages_holes WHERE uid = " + did).stepThis().dispose();
                    db.executeFast("DELETE FROM media_v4 WHERE uid = " + did).stepThis().dispose();
                    db.executeFast("DELETE FROM media_holes_v2 WHERE uid = " + did).stepThis().dispose();
                    db.executeFast("UPDATE media_counts_v2 SET old = 1 WHERE uid = " + did).stepThis().dispose();
                    db.executeFast("DELETE FROM messages_topics WHERE uid = " + did).stepThis().dispose();
                    db.executeFast("DELETE FROM messages_holes_topics WHERE uid = " + did).stepThis().dispose();
                } catch (Exception e) {
                    RelayConfig.logError(TAG, "DM cache reset " + did, e);
                } finally {
                    if (cursor != null) {
                        cursor.dispose();
                    }
                }
            }
            for (TLRPC.Message message : dialogs.messages) {
                message.dialog_id = MessageObject.getDialogId(message);
            }
            // check 0: the dialog row is replaced and gets first holes below its new top message (overwriteChannel).
            storage.putDialogs(dialogs, 0);
            storage.getStorageQueue().postRunnable(() -> AndroidUtilities.runOnUIThread(() -> {
                MessagesController mc = MessagesController.getInstance(account);
                mc.putUsers(dialogs.users, false);
                for (TLRPC.Dialog dialog : dialogs.dialogs) {
                    ArrayList<MessageObject> old = mc.dialogMessage.get(dialog.id);
                    if (old != null) {
                        for (MessageObject m : old) {
                            if (m != null && m.getId() != dialog.top_message) {
                                m.deleted = true; // processDialogsUpdate replaces a deleted preview even with a lower id
                            }
                        }
                    }
                }
                mc.processDialogsUpdate(dialogs, null, false);
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
                RelayConfig.log(TAG, "DM caches overwritten: " + dialogs.dialogs.size() + " chats");
            }));
        });
    }
}
