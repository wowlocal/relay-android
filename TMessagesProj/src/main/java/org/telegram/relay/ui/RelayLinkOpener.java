package org.telegram.relay.ui;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.relay.RelayConfig;
import org.telegram.relay.bridge.RelayPermalinks;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.LaunchActivity;

/**
 * A7a: opens Mattermost links in the app ({@link RelayLinks.Handler}, installed by the bridge): resolves
 * the link on the account's bridge ({@link RelayPermalinks}) and then opens the chat at the post, the
 * thread at the reply, the join prompt of an unjoined public channel, or an alert. A link of another
 * signed-in account's server switches to that account first (as iOS {@code navigateToChat(accountId:)}).
 */
public final class RelayLinkOpener implements RelayLinks.Handler {
    private static final String TAG = RelayLinks.TAG;
    private static final RelayLinkOpener INSTANCE = new RelayLinkOpener();

    public static void install() {
        RelayLinks.setHandler(INSTANCE);
    }

    @Override
    public boolean open(Context context, RelayLinks.Link link) {
        AndroidUtilities.runOnUIThread(() -> {
            int account = link.account;
            if (account != UserConfig.selectedAccount && LaunchActivity.instance != null && UserConfig.getInstance(account).isClientActivated()) {
                RelayConfig.log(TAG, "switching to account " + account + " for " + link.serverUrl);
                LaunchActivity.instance.switchToAccount(account, true);
            }
            RelayPermalinks.resolve(account, link, (destination, error) -> show(link, destination, error));
        });
        return true;
    }

    private static void show(RelayLinks.Link link, RelayPermalinks.Destination d, String error) {
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment == null || fragment.getParentActivity() == null) {
            RelayConfig.log(TAG, "no fragment to open " + link);
            return;
        }
        if (d == null) {
            alert(fragment, error);
            return;
        }
        MessagesController mc = MessagesController.getInstance(d.account);
        mc.putUsers(d.users, false);
        mc.putChats(d.chats, false);
        switch (d.kind) {
            case CHAT: {
                Bundle args = new Bundle();
                if (d.dialogId > 0) {
                    args.putLong("user_id", d.dialogId);
                } else {
                    args.putLong("chat_id", -d.dialogId);
                }
                if (d.messageId != 0) {
                    args.putInt("message_id", d.messageId);
                }
                fragment.presentFragment(new ChatActivity(args));
                RelayConfig.log(TAG, "opened chat " + d.dialogId + " at " + d.messageId);
                break;
            }
            case THREAD:
                RelayThreadsUi.openThread(fragment, d.account, d.dialogId, d.rootMessageId, d.messageId);
                break;
            case JOIN:
                joinPrompt(fragment, link, d);
                break;
            default:
                alert(fragment, d.error);
                break;
        }
    }

    private static void joinPrompt(BaseFragment fragment, RelayLinks.Link link, RelayPermalinks.Destination d) {
        StringBuilder text = new StringBuilder();
        if (d.memberCount > 0) {
            text.append(LocaleController.formatString(R.string.RelayJoinChannelInfo, d.channelName, LocaleController.formatPluralString("Members", (int) d.memberCount)));
        } else {
            text.append("~").append(d.channelName);
        }
        if (!TextUtils.isEmpty(d.purpose)) {
            text.append("\n\n").append(d.purpose);
        }
        if (!d.recent.isEmpty()) {
            text.append("\n");
            for (String line : d.recent) {
                text.append("\n").append(line.length() > 120 ? line.substring(0, 120) + "…" : line);
            }
        }
        text.append("\n\n").append(LocaleController.getString(R.string.RelayJoinChannelText));
        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity());
        builder.setTitle(LocaleController.formatString(R.string.RelayJoinChannelTitle, d.title));
        builder.setMessage(text.toString());
        builder.setPositiveButton(LocaleController.getString(R.string.RelayJoinChannel), (dialog, which) ->
                RelayPermalinks.joinAndResolve(d.account, d.channelId, link, (destination, error) -> {
                    if (destination != null && destination.kind == RelayPermalinks.Destination.Kind.JOIN) {
                        alert(fragment, "failed");
                        return;
                    }
                    // The client learns about the new chat from the bridge's updateChannel; give it a moment.
                    AndroidUtilities.runOnUIThread(() -> show(link, destination, error), 300);
                }));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        fragment.showDialog(builder.create());
        RelayConfig.log(TAG, "join prompt for " + d.channelName);
    }

    private static void alert(BaseFragment fragment, String error) {
        int text;
        if ("not_found".equals(error)) {
            text = R.string.RelayLinkNotFound;
        } else if ("no_access".equals(error)) {
            text = R.string.RelayLinkNoAccess;
        } else if ("offline".equals(error)) {
            text = R.string.RelayLinkOffline;
        } else {
            text = R.string.RelayLinkFailed;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.AppName));
        builder.setMessage(LocaleController.getString(text));
        builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        fragment.showDialog(builder.create());
        RelayConfig.log(TAG, "link alert " + error);
    }
}
