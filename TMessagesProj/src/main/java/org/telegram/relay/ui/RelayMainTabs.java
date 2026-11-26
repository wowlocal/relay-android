package org.telegram.relay.ui;

import android.os.Bundle;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.relay.RelayConfig;
import org.telegram.relay.bridge.RelayThreads;
import org.telegram.relay.bridge.RelayThreadsHooks;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.glass.GlassTabView;

/**
 * Relay's own main tabs (iOS {@code TelegramRootController.makeRelayTabControllers}, as Mattermost's mobile app):
 * the tab bar is Chats · Threads · Mentions · Settings · Profile. Threads ({@link RelayThreadsActivity}) takes
 * Telegram's Contacts slot, Mentions ({@link RelayPostListActivity}, Recent Mentions) comes after it; Profile stays
 * (its long press switches accounts). {@code MainTabsActivity} creates the pages here and shows the Threads badge.
 *
 * <p>Telegram's Contacts tab was Relay's user directory; New Message (the chat list's pencil button) lists the same
 * directory with search, and opening a person creates the DM on first use.
 */
public final class RelayMainTabs {
    private RelayMainTabs() {
    }

    private static final String TAG = "RelayTabs";

    /** The page of a Relay tab: Threads (following the chat list's selected team folder) or Mentions. */
    public static BaseFragment createTab(boolean threads, RelayThreadsUi.FolderProvider selectedFolder) {
        Bundle args = new Bundle();
        args.putBoolean("hasMainTabs", true);
        if (threads) {
            RelayThreadsActivity fragment = new RelayThreadsActivity(args);
            fragment.setFolderProvider(selectedFolder);
            return fragment;
        }
        return RelayPostListActivity.mentionsTab();
    }

    /**
     * The Threads tab's badge: unread followed threads of all teams, "@" in front when one mentions the user (iOS
     * {@code totalThreadsBadge}); null with none, before the first load or with collapsed reply threads off.
     */
    public static String threadsBadge(int account) {
        if (!RelayThreadsHooks.collapsedThreads(account)) {
            return null;
        }
        RelayThreads.Totals totals = RelayThreads.totals(account);
        if (totals.unreadThreads <= 0) {
            return null;
        }
        return (totals.unreadMentions > 0 ? "@" : "") + (totals.unreadThreads > 99 ? "99+" : String.valueOf(totals.unreadThreads));
    }

    private static final String[] shownBadges = new String[16];

    /** Shows the badge on the Threads tab; its content description carries it too (TalkBack, e2e). */
    public static void updateThreadsBadge(GlassTabView tab, int account, boolean animated) {
        if (tab == null) {
            return;
        }
        String badge = threadsBadge(account);
        tab.setCounter(badge, false, animated);
        String title = LocaleController.getString(R.string.RelayThreads);
        tab.setContentDescription(badge != null ? LocaleController.formatString(R.string.RelayThreadsTabUnread, title, badge) : title);
        if (account >= 0 && account < shownBadges.length) {
            String shown = badge != null ? badge : "";
            if (!shown.equals(shownBadges[account])) {
                shownBadges[account] = shown;
                RelayConfig.log(TAG, "threads tab badge account=" + account + " badge=" + shown);
            }
        }
    }
}
