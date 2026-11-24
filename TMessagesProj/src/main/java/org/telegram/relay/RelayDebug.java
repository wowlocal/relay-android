package org.telegram.relay;

import android.util.Log;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;

/**
 * Helpers behind the debug broadcasts of {@code org.relaychat.debug.RelayDebugHooks} (debug builds only;
 * they live here because they touch Telegram UI classes the app module cannot compile against).
 */
public final class RelayDebug {
    private static final String TAG = "RelayDebug";

    private RelayDebug() {
    }

    /**
     * The client's chat list as the UI has it, one TSV line per dialog:
     * {@code dialog <did> <channel> <title> <unread> <mentions> <muted> <pinned> <top_id> <date> <preview>},
     * then {@code filter <id> <title> <dialog count>} per folder.
     */
    public static void dumpDialogs(int account) {
        MessagesController mc = MessagesController.getInstance(account);
        java.util.ArrayList<org.telegram.tgnet.TLRPC.Dialog> dialogs = new java.util.ArrayList<>(mc.getAllDialogs());
        java.util.ArrayList<org.telegram.messenger.MessagesController.DialogFilter> filters = new java.util.ArrayList<>(mc.getDialogFilters());
        new Thread(() -> {
            StringBuilder text = new StringBuilder();
            for (org.telegram.tgnet.TLRPC.Dialog d : dialogs) {
                String title;
                if (d.id > 0) {
                    org.telegram.tgnet.TLRPC.User u = mc.getUser(d.id);
                    title = u == null ? "?" : (u.self ? "Saved Messages" : org.telegram.messenger.UserObject.getUserName(u));
                } else {
                    org.telegram.tgnet.TLRPC.Chat c = mc.getChat(-d.id);
                    title = c == null ? "?" : c.title;
                }
                java.util.ArrayList<org.telegram.messenger.MessageObject> msgs = mc.dialogMessage.get(d.id);
                String preview = msgs != null && !msgs.isEmpty() && msgs.get(0).messageText != null ? msgs.get(0).messageText.toString() : "";
                text.append("dialog\t").append(d.id).append('\t').append(org.telegram.relay.bridge.RelayHooks.channelIdOfDialog(account, d.id)).append('\t')
                        .append(clean(title)).append('\t').append(d.unread_count).append('\t').append(d.unread_mentions_count).append('\t')
                        .append(mc.isDialogMuted(d.id, 0) ? 1 : 0).append('\t').append(d.pinned ? 1 : 0).append('\t')
                        .append(d.top_message).append('\t').append(d.last_message_date).append('\t').append(clean(preview)).append('\n');
            }
            for (org.telegram.messenger.MessagesController.DialogFilter f : filters) {
                text.append("filter\t").append(f.id).append('\t').append(clean(f.name)).append('\t').append(f.dialogs.size());
                for (org.telegram.tgnet.TLRPC.Dialog d : f.dialogs) {
                    text.append('\t').append(d.id);
                }
                text.append('\n');
            }
            write("relay-dialogs.txt", text.toString());
            Log.i(TAG, "DEBUG_DUMP_DIALOGS ok dialogs=" + dialogs.size() + " filters=" + filters.size());
        }, "RelayDebugDialogs").start();
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ');
    }

    private static void write(String name, String text) {
        try {
            java.io.File file = new java.io.File(ApplicationLoader.getFilesDirFixed(), name);
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
                out.write(text.getBytes("UTF-8"));
            }
        } catch (Exception e) {
            Log.w(TAG, "write " + name + " failed: " + e);
        }
    }

    /**
     * Sends local files through Telegram's own send path (FileUploadOperation → upload.saveFilePart →
     * messages.sendMedia / uploadMedia + sendMultiMedia), as the attach menu would, without its picker UI.
     * {@code kind}: {@code photo} (one or an album, grouped), {@code video}, {@code document}, {@code voice}
     * (an Ogg/Opus file sent as a voice message, waveform from Telegram's own decoder).
     */
    public static void sendMedia(int account, long dialogId, String kind, java.util.List<String> paths, String caption) {
        if (dialogId == 0 || paths.isEmpty()) {
            Log.w(TAG, "DEBUG_SEND_MEDIA: no chat or files");
            return;
        }
        org.telegram.messenger.AccountInstance instance = org.telegram.messenger.AccountInstance.getInstance(account);
        switch (kind) {
            case "document": {
                java.util.ArrayList<String> list = new java.util.ArrayList<>(paths);
                org.telegram.messenger.SendMessagesHelper.prepareSendingDocuments(instance, list, list, null, caption, null, dialogId,
                        null, null, null, null, null, true, 0, null, null, 0, false, 0);
                break;
            }
            case "voice": {
                String path = paths.get(0);
                java.io.File file = new java.io.File(path);
                org.telegram.tgnet.TLRPC.TL_document doc = new org.telegram.tgnet.TLRPC.TL_document();
                doc.file_reference = new byte[0];
                doc.dc_id = Integer.MIN_VALUE;
                doc.id = org.telegram.messenger.SharedConfig.getLastLocalId();
                doc.user_id = org.telegram.messenger.UserConfig.getInstance(account).getClientUserId();
                doc.mime_type = "audio/ogg";
                doc.date = org.telegram.tgnet.ConnectionsManager.getInstance(account).getCurrentTime();
                doc.size = (int) file.length();
                org.telegram.tgnet.TLRPC.TL_documentAttributeAudio audio = new org.telegram.tgnet.TLRPC.TL_documentAttributeAudio();
                audio.voice = true;
                audio.duration = 3;
                audio.waveform = org.telegram.messenger.MediaController.getWaveform(path);
                if (audio.waveform != null) {
                    audio.flags |= 4;
                }
                doc.attributes.add(audio);
                org.telegram.messenger.SendMessagesHelper.getInstance(account).sendMessage(org.telegram.messenger.SendMessagesHelper.SendMessageParams.of(
                        doc, null, path, dialogId, null, null, null, null, null, null, true, 0, 0, 0, null, null, false));
                break;
            }
            default: {
                java.util.ArrayList<org.telegram.messenger.SendMessagesHelper.SendingMediaInfo> media = new java.util.ArrayList<>();
                for (int i = 0; i < paths.size(); i++) {
                    org.telegram.messenger.SendMessagesHelper.SendingMediaInfo info = new org.telegram.messenger.SendMessagesHelper.SendingMediaInfo();
                    info.path = paths.get(i);
                    info.isVideo = "video".equals(kind);
                    if (i == 0 && caption != null) {
                        info.caption = caption;
                    }
                    media.add(info);
                }
                org.telegram.messenger.SendMessagesHelper.prepareSendingMedia(instance, media, dialogId, null, null, null, null,
                        false, true, null, true, 0, 0, 0, false, null, null, 0, false, 0, 0, null);
                break;
            }
        }
        Log.i(TAG, "DEBUG_SEND_MEDIA ok kind=" + kind + " files=" + paths.size() + " dialog=" + dialogId);
    }

    /**
     * Sets the own reactions of a message in the open chat (found by text) through Telegram's
     * {@code SendMessagesHelper.sendReaction} → {@code messages.sendReaction}: {@code emoji} empty clears them.
     * (The reaction picker needs Telegram's animated reaction set, which Relay does not have yet.)
     */
    public static void react(int account, String text, String emoji) {
        org.telegram.ui.ActionBar.BaseFragment last = org.telegram.ui.LaunchActivity.getLastFragment();
        if (!(last instanceof org.telegram.ui.ChatActivity)) {
            Log.w(TAG, "DEBUG_REACT: no open chat");
            return;
        }
        org.telegram.ui.ChatActivity chat = (org.telegram.ui.ChatActivity) last;
        if (emoji != null && emoji.matches("[a-z0-9_+\\-]+")) {
            emoji = org.telegram.relay.mattermost.text.EmojiTable.unicode(emoji); // a Mattermost name (adb-safe)
        }
        for (org.telegram.messenger.MessageObject m : chat.messages) {
            if (m.messageOwner != null && m.messageOwner.message != null && m.messageOwner.message.contains(text)) {
                java.util.ArrayList<org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble.VisibleReaction> list = new java.util.ArrayList<>();
                org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble.VisibleReaction added = null;
                if (emoji != null && !emoji.isEmpty()) {
                    added = org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble.VisibleReaction.fromEmojicon(emoji);
                    list.add(added);
                }
                org.telegram.messenger.SendMessagesHelper.getInstance(account).sendReaction(m, list, added, false, false, chat, null);
                Log.i(TAG, "DEBUG_REACT ok id=" + m.getId() + " emoji=" + emoji);
                return;
            }
        }
        Log.w(TAG, "DEBUG_REACT: message not found: " + text);
    }

    /** Opens {@code url} the way an in-app link tap does ({@code Browser.openUrl}). */
    public static void openUrl(String url) {
        final android.app.Activity activity = org.telegram.ui.LaunchActivity.instance;
        if (url == null || activity == null) {
            Log.w(TAG, "DEBUG_OPEN_URL failed: needs --es url and a running UI");
            return;
        }
        org.telegram.messenger.browser.Browser.openUrl(activity, url);
        Log.i(TAG, "DEBUG_OPEN_URL done " + url);
    }

    /** Opens a chat (optionally at a message) the way a notification tap does. */
    public static void openChat(long dialogId, int messageId) {
        if (dialogId == 0) {
            Log.w(TAG, "DEBUG_OPEN_CHAT: unknown channel");
            return;
        }
        android.os.Bundle args = new android.os.Bundle();
        if (dialogId > 0) {
            args.putLong("user_id", dialogId);
        } else {
            args.putLong("chat_id", -dialogId);
        }
        if (messageId != 0) {
            args.putInt("message_id", messageId);
        }
        org.telegram.ui.ActionBar.BaseFragment last = org.telegram.ui.LaunchActivity.getLastFragment();
        if (last == null) {
            Log.w(TAG, "DEBUG_OPEN_CHAT: no fragment");
            return;
        }
        last.presentFragment(new org.telegram.ui.ChatActivity(args));
        Log.i(TAG, "DEBUG_OPEN_CHAT ok dialog=" + dialogId + " message=" + messageId);
    }

}
