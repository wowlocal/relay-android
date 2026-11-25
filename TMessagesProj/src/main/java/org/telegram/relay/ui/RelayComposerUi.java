package org.telegram.relay.ui;

import android.app.Activity;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.relay.bridge.RelayComposer;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.LaunchActivity;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The composer's Relay behaviour (A7c), called from small hooks in {@code MentionsAdapter} (autocomplete tokens),
 * {@code ChatActivity} (inserting a suggestion) and {@code SendMessagesHelper.sendMessage} (slash commands, the
 * {@code @channel} confirmation). The Mattermost side is {@link RelayComposer}.
 */
public final class RelayComposerUi {
    private RelayComposerUi() {
    }

    /** The autocomplete token before the cursor: trigger, query, and the range it replaces. */
    public static final class Token {
        public final char trigger;
        public final String query;
        public final int start;
        public final int length;

        Token(char trigger, String query, int start, int length) {
            this.trigger = trigger;
            this.query = query;
            this.start = start;
            this.length = length;
        }
    }

    /**
     * {@code @user}, {@code ~channel} and {@code :emoji} (2+ letters) right before the cursor, or a slash command when
     * the whole input up to the cursor starts with {@code /} (one line); null when there is nothing to suggest.
     */
    public static Token token(String text, int cursor) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        if (cursor < 0 || cursor > text.length()) {
            cursor = text.length();
        }
        String before = text.substring(0, cursor);
        if (before.startsWith("/") && !before.startsWith("//") && before.indexOf('\n') < 0) {
            int space = before.indexOf(' ');
            String first = space < 0 ? before.substring(1) : before.substring(1, space);
            if (!first.contains("/")) {
                return new Token('/', before, 0, cursor);
            }
        }
        int start = cursor;
        while (start > 0) {
            char c = before.charAt(start - 1);
            if (Character.isWhitespace(c)) {
                break;
            }
            start--;
        }
        if (start >= cursor) {
            return null;
        }
        String word = before.substring(start);
        char trigger = word.charAt(0);
        String query = word.substring(1);
        switch (trigger) {
            case '@':
                return query.matches("[A-Za-z0-9_.\\-]*") ? new Token('@', query, start, word.length()) : null;
            case '~':
                return query.matches("[A-Za-z0-9_\\-]*") ? new Token('~', query, start, word.length()) : null;
            case ':':
                return query.length() >= 2 && query.matches("[A-Za-z0-9_+\\-]+") ? new Token(':', query, start, word.length()) : null;
            default:
                return null;
        }
    }

    // MARK: - Send interception

    /** Params already confirmed (or decided to post as text): passed through on the second call. */
    private static final Map<SendMessagesHelper.SendMessageParams, Boolean> approved = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Called first in {@code SendMessagesHelper.sendMessage}: true when Relay took the message (a slash command is run,
     * or a channel-wide mention waits for confirmation; the message is sent later through the same call).
     */
    public static boolean interceptSend(int account, SendMessagesHelper.SendMessageParams params) {
        if (params == null || params.message == null || params.retryMessageObject != null || params.scheduleDate != 0
                || params.photo != null || params.document != null || params.location != null || params.poll != null) {
            return false;
        }
        if (approved.remove(params) != null) {
            return false;
        }
        String text = params.message;
        String trigger = RelayComposer.commandTrigger(text);
        if (trigger != null) {
            int replyTo = params.replyToMsg != null ? params.replyToMsg.getId() : params.replyToTopMsg != null ? params.replyToTopMsg.getId() : 0;
            RelayComposer.executeCommand(account, params.peer, replyTo, text.trim(), result -> {
                if (result.ok) {
                    return;
                }
                BaseFragment fragment = LaunchActivity.getLastFragment();
                Activity activity = fragment != null ? fragment.getParentActivity() : null;
                if (result.notFound && activity != null) {
                    AlertDialog dialog = new AlertDialog.Builder(activity)
                            .setTitle(LocaleController.getString(R.string.RelayCommandNotFoundTitle))
                            .setMessage(LocaleController.formatString(R.string.RelayCommandNotFound, trigger))
                            .setPositiveButton(LocaleController.getString(R.string.RelaySendAsMessage), (d, w) -> {
                                approved.put(params, Boolean.TRUE);
                                SendMessagesHelper.getInstance(account).sendMessage(params);
                            })
                            .setNegativeButton(LocaleController.getString(R.string.Cancel), (d, w) -> restoreText(text))
                            .create();
                    fragment.showDialog(dialog);
                } else if (fragment != null) {
                    BulletinFactory.of(fragment).createErrorBulletin(LocaleController.formatString(R.string.RelayCommandFailed, result.error != null ? result.error : "?")).show();
                }
            });
            return true;
        }
        if (text.contains("@")) {
            RelayComposer.channelMentionConfirmation(account, params.peer, text, info -> {
                if (info == null) {
                    approved.put(params, Boolean.TRUE);
                    SendMessagesHelper.getInstance(account).sendMessage(params);
                    return;
                }
                BaseFragment fragment = LaunchActivity.getLastFragment();
                Activity activity = fragment != null ? fragment.getParentActivity() : null;
                if (activity == null) {
                    approved.put(params, Boolean.TRUE);
                    SendMessagesHelper.getInstance(account).sendMessage(params);
                    return;
                }
                String message;
                if (info[2] == 1) {
                    message = LocaleController.formatString(R.string.RelayConfirmHere, info[0]);
                } else if (info[1] > 0) {
                    message = LocaleController.formatString(R.string.RelayConfirmChannel, info[0], info[1]);
                } else {
                    message = LocaleController.formatString(R.string.RelayConfirmChannelNoTz, info[0]);
                }
                AlertDialog dialog = new AlertDialog.Builder(activity)
                        .setTitle(LocaleController.getString(R.string.RelayConfirmChannelTitle))
                        .setMessage(message)
                        .setPositiveButton(LocaleController.getString(R.string.RelayConfirmSend), (d, w) -> {
                            approved.put(params, Boolean.TRUE);
                            SendMessagesHelper.getInstance(account).sendMessage(params);
                        })
                        .setNegativeButton(LocaleController.getString(R.string.Cancel), (d, w) -> restoreText(text))
                        .create();
                fragment.showDialog(dialog);
            });
            return true;
        }
        return false;
    }

    /** Puts a cancelled message back into the open chat's composer. */
    private static void restoreText(String text) {
        BaseFragment fragment = LaunchActivity.getLastFragment();
        if (fragment instanceof ChatActivity && ((ChatActivity) fragment).getChatActivityEnterView() != null) {
            CharSequence current = ((ChatActivity) fragment).getChatActivityEnterView().getFieldText();
            if (TextUtils.isEmpty(current)) {
                ((ChatActivity) fragment).getChatActivityEnterView().setFieldText(text);
            }
        }
    }
}
