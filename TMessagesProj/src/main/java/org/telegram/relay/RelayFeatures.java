package org.telegram.relay;

import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;

import java.util.List;

/**
 * Telegram features that Relay (Mattermost) does not have. All flags are compile-time constants:
 * javac folds {@code if (RelayFeatures.X)} and R8 strips the dead branch. Never read them from
 * prefs or make them non-final.
 *
 * <p>Hiding happens at the entry points (menu items, settings rows, buttons, tabs) and in a few
 * central predicates ({@code MessagesController.premiumFeaturesBlocked()}, {@code storiesEnabled()},
 * {@code StoriesController.hasStories()}, …). {@link #isFragmentAllowed} is the safety net in
 * {@code ActionBarLayout.presentFragment} for entry points that were missed. See
 * docs/relay/telegram-internals.md §9.
 */
public final class RelayFeatures {
    private RelayFeatures() {
    }

    public static final String TAG = "RelayFeatures";

    /** Stories: chat-list stories bar, camera FAB, avatar rings, profile stories tab. */
    public static final boolean STORIES = false;
    /** Telegram Premium: badges, promo, upsells, premium emoji status store. */
    public static final boolean PREMIUM = false;
    /** Stars, payments, paid media, Gram / TON earnings. */
    public static final boolean STARS_PAYMENTS = false;
    /** Gifts (send a gift, profile gifts tab, birthday gifts). */
    public static final boolean GIFTS = false;
    /** Telegram Business. */
    public static final boolean BUSINESS = false;
    /** TON wallet. */
    public static final boolean WALLET = false;
    /** Sticker panel, sticker store, trending / featured sets. */
    public static final boolean STICKERS = false;
    /** GIF panel (Telegram's GIF search bot). */
    public static final boolean GIFS = false;
    /** Phone number, phone-book contacts sync, invites by SMS. */
    public static final boolean PHONE_CONTACTS = false;
    /** Secret (end-to-end) chats. */
    public static final boolean SECRET_CHATS = false;
    /** Telegram's own calls network (tgcalls, Telegram's call servers, call privacy, call log). */
    public static final boolean TG_CALLS = false;
    /**
     * A8: Relay calls (LiveKit through the {@code org.relaychat.calls} plugin) with Telegram's call UI: 1:1 calls in
     * DMs (VoIPFragment, incoming call ring), camera, screen sharing.
     */
    public static final boolean RELAY_CALLS = true;
    /** A8: channel and group-message calls with Telegram's video chat UI (GroupCallActivity, join panel). */
    public static final boolean RELAY_GROUP_CALLS = true;
    /** Bots, mini apps, attach-menu bots, side-menu bots, bot menu button. */
    public static final boolean BOT_WEBAPPS = false;
    /** Inline bots (@gif, @bing …). */
    public static final boolean INLINE_BOTS = false;
    /** Broadcast channels (Mattermost channels map to supergroups). */
    public static final boolean CHANNELS = false;
    /** Boosts, giveaways, statistics, monetization. */
    public static final boolean BOOSTS_STATS = false;
    /** Sponsored messages / ads. */
    public static final boolean SPONSORED = false;
    /** Collectible usernames and numbers (Fragment). */
    public static final boolean FRAGMENT = false;
    /** QR login and linking desktop devices. */
    public static final boolean QR_LOGIN = false;
    /** Telegram Passport. */
    public static final boolean PASSPORT = false;
    /** MTProto / SOCKS proxy and data-center settings. */
    public static final boolean PROXY = false;
    /** "Ask a Question", Telegram FAQ, Telegram Features, Telegram privacy policy. */
    public static final boolean TG_SUPPORT = false;
    /** Editing chat folders (team folders come from the server; viewing stays). */
    public static final boolean FOLDER_EDITING = false;
    /** Telegram-only privacy rules (last seen, phone, forwards, calls, invites, …). */
    public static final boolean TG_PRIVACY = false;
    /** Telegram's phone / intro login. Relay uses RelayLoginActivity. */
    public static final boolean TELEGRAM_LOGIN = false;
    /** Polls and checklists (Mattermost has no native polls). */
    public static final boolean POLLS = false;
    /** Polish 1: "Location" in the attach menu. Off, as on iOS (never posted; Mattermost has no location message). */
    public static final boolean LOCATION = false;
    /** Telegram's rich-text article messages (InstantPage). */
    public static final boolean RICH_MESSAGES = false;
    /** Telegram's 2-step verification cloud password. */
    public static final boolean CLOUD_PASSWORD = false;
    /** Telegram's per-user chat archive (Mattermost archives channels for everyone, as an admin action). */
    public static final boolean CHAT_ARCHIVE = false;
    /** Blocking users (Mattermost has no blocking). */
    public static final boolean BLOCK_USERS = false;
    /** Telegram's message translation (messages.translateText). */
    public static final boolean TRANSLATE = false;
    /** Reporting messages and chats to Telegram. */
    public static final boolean REPORT = false;
    /** Forum topics (Mattermost threads are not topics). */
    public static final boolean TOPICS = false;
    /** Telegram invite links (t.me/+…). */
    public static final boolean INVITE_LINKS = false;

    /** Message long-press actions Relay does not offer ({@code ChatActivity.OPTION_*}). */
    private static final int[] DENIED_MESSAGE_OPTIONS = {
        ChatActivity.OPTION_APPLY_LOCALIZATION_OR_THEME,
        ChatActivity.OPTION_ADD_TO_STICKERS_OR_MASKS,
        ChatActivity.OPTION_ADD_TO_GIFS,
        ChatActivity.OPTION_ADD_CONTACT,
        // A8: "Call back" / "Call again" on call messages stay (Relay calls); rating goes to Telegram only.
        ChatActivity.OPTION_RATE_CALL,
        ChatActivity.OPTION_ADD_STICKER_TO_FAVORITES,
        ChatActivity.OPTION_DELETE_STICKER_FROM_FAVORITES,
        ChatActivity.OPTION_REPORT_CHAT,
        ChatActivity.OPTION_UNVOTE,
        ChatActivity.OPTION_STOP_POLL_OR_QUIZ,
        ChatActivity.OPTION_STATISTICS,
        ChatActivity.OPTION_TRANSLATE,
        ChatActivity.OPTION_TRANSCRIBE,
        ChatActivity.OPTION_HIDE_SPONSORED_MESSAGE,
        ChatActivity.OPTION_ABOUT_REVENUE_SHARING_ADS,
        ChatActivity.OPTION_REPORT_AD,
        ChatActivity.OPTION_REMOVE_ADS,
        ChatActivity.OPTION_SPEED_PROMO,
        ChatActivity.OPTION_FACT_CHECK,
        ChatActivity.OPTION_EDIT_PRICE,
        ChatActivity.OPTION_GIFT,
        ChatActivity.OPTION_EDIT_TODO,
        ChatActivity.OPTION_ADD_TO_TODO,
        ChatActivity.OPTION_SUGGESTION_EDIT_PRICE,
        ChatActivity.OPTION_SUGGESTION_EDIT_TIME,
        ChatActivity.OPTION_SUGGESTION_EDIT_MESSAGE,
        ChatActivity.OPTION_SUGGESTION_ADD_OFFER,
        ChatActivity.OPTION_VIEW_STATISTICS,
        ChatActivity.OPTION_SEND_MONEY,
    };

    /**
     * A8: who may start a video chat in a chat. Relay: any member (the calls plugin checks membership), as iOS's
     * {@code canStartRelayCall}; Telegram: call admins.
     */
    public static boolean canStartGroupCall(org.telegram.tgnet.TLRPC.Chat chat) {
        if (!RelayConfig.ENABLED) {
            return org.telegram.messenger.ChatObject.canManageCalls(chat);
        }
        return RELAY_GROUP_CALLS && chat != null && !chat.left && !chat.kicked;
    }

    public static boolean isMessageOptionAllowed(int option) {
        if (!RelayConfig.ENABLED) {
            return true;
        }
        for (int denied : DENIED_MESSAGE_OPTIONS) {
            if (denied == option) {
                return false;
            }
        }
        return true;
    }

    /**
     * Removes denied entries from the parallel lists {@code ChatActivity.createMenu} builds
     * (option ids, titles, icons). Kept: reply, copy, copy link, edit, pin, forward, select, delete,
     * save, share, replies, scheduling.
     */
    public static void filterMessageOptions(List<Integer> options, List<CharSequence> items, List<Integer> icons) {
        for (int i = options.size() - 1; i >= 0; i--) {
            if (!isMessageOptionAllowed(options.get(i))) {
                options.remove(i);
                if (i < items.size()) {
                    items.remove(i);
                }
                if (i < icons.size()) {
                    icons.remove(i);
                }
            }
        }
    }

    /** Fragments that must never open in Relay (class simple names). */
    private static final String[] DENIED_FRAGMENTS = {
        "PremiumPreviewFragment",
        "StarsIntroActivity",
        "TONIntroActivity",
        "WalletActivity",
        "ChannelCreateActivity",
        "PassportActivity",
        "ProxyListActivity",
        "ProxySettingsActivity",
        "StatisticActivity",
        "ChannelBoostLayout",
        "BoostsActivity",
        "CallLogActivity",
        "TwoStepVerificationActivity",
        "TwoStepVerificationSetupActivity",
        "CameraScanActivity",
        "ChangePhoneActivity",
        "PrivacyControlActivity",
        "FilterCreateActivity",
        "FiltersSetupActivity",
        "PeopleNearbyActivity",
        "LoginActivity",
        "IntroActivity",
        "ChangeUsernameActivity",
        "QrShareActivity",
        // A6b: settings screens Relay has no backing for.
        "PrivacySettingsActivity",
        "StickersActivity",
        "ArchivedStickersActivity",
        "FeaturedStickersActivity",
        "FilterChatlistActivity",
        "GroupStickersActivity",
    };

    /**
     * Whether {@code fragment} may be presented. Relay refuses Telegram-only screens here, so an
     * entry point that was not hidden opens nothing instead of a Telegram screen.
     */
    public static boolean isFragmentAllowed(BaseFragment fragment) {
        if (fragment == null || !RelayConfig.ENABLED) {
            return true;
        }
        final Class<?> cls = fragment.getClass();
        final String name = cls.getSimpleName();
        if (fragment instanceof org.telegram.ui.ActionIntroActivity) {
            // Channel creation, change phone and QR login intros; the passcode intro stays.
            if (((org.telegram.ui.ActionIntroActivity) fragment).getActionType() == org.telegram.ui.ActionIntroActivity.ACTION_TYPE_SET_PASSCODE) {
                return true;
            }
            RelayConfig.log(TAG, "blocked " + name);
            return false;
        }
        if (cls.getName().startsWith("org.telegram.ui.Business.")) {
            RelayConfig.log(TAG, "blocked " + cls.getName());
            return false;
        }
        for (String denied : DENIED_FRAGMENTS) {
            if (denied.equals(name)) {
                RelayConfig.log(TAG, "blocked " + name);
                return false;
            }
        }
        return true;
    }
}
