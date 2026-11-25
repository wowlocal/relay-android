package org.telegram.relay.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.relay.RelayConfig;
import org.telegram.relay.account.RelayAccount;
import org.telegram.relay.bridge.RelayFinding;
import org.telegram.relay.mattermost.model.Team;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Polish 2b: "Copy Link" for posts in DMs and Saved Messages. Telegram offers it in channels only (the bridge answers
 * {@code channels.exportMessageLink} there). A Mattermost permalink is {@code <server>/<team>/pl/<post>}; the server
 * resolves a DM post's channel itself, so any of the user's teams works (the first by name, as the bridge does).
 */
public final class RelayDmLinks {
    private static final String TAG = "RelayDmLinks";

    private RelayDmLinks() {
    }

    public static void copyLink(BaseFragment fragment, long dialogId, int messageId) {
        final int account = fragment.getCurrentAccount();
        Utilities.globalQueue.postRunnable(() -> {
            String link = null;
            try {
                link = permalink(account, dialogId, messageId);
            } catch (Exception e) {
                RelayConfig.logError(TAG, "permalink " + dialogId + "/" + messageId, e);
            }
            final String result = link;
            AndroidUtilities.runOnUIThread(() -> {
                if (result == null) {
                    if (BulletinFactory.canShowBulletin(fragment)) {
                        BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(R.string.RelayLinkFailed)).show();
                    }
                    return;
                }
                ClipboardManager clipboard = (ClipboardManager) ApplicationLoader.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("label", result));
                RelayConfig.log(TAG, "copied " + result);
                if (BulletinFactory.canShowBulletin(fragment)) {
                    BulletinFactory.of(fragment).createCopyLinkBulletin().show();
                }
            });
        });
    }

    /** {@code <server>/<team>/pl/<post id>}, or null. Not on the UI thread (REST for the teams). */
    static String permalink(int account, long dialogId, int messageId) throws Exception {
        RelayAccount relayAccount = RelayAccount.get(account);
        String postId = RelayFinding.debugPostId(account, dialogId, messageId);
        if (relayAccount == null || TextUtils.isEmpty(postId)) {
            return null;
        }
        List<Team> teams = new ArrayList<>(relayAccount.client.teams());
        if (teams.isEmpty()) {
            return null;
        }
        Collections.sort(teams, (a, b) -> String.valueOf(a.displayName).compareToIgnoreCase(String.valueOf(b.displayName)));
        String server = relayAccount.session.serverUrl;
        while (server.endsWith("/")) {
            server = server.substring(0, server.length() - 1);
        }
        return server + "/" + teams.get(0).name + "/pl/" + postId;
    }
}
