package org.telegram.relay;

import org.telegram.ui.ActionBar.BaseFragment;

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
    /** Telegram calls and video chats (LiveKit calls come in A8). */
    public static final boolean TG_CALLS = false;
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
    /** Telegram's rich-text article messages (InstantPage). */
    public static final boolean RICH_MESSAGES = false;
    /** Telegram's 2-step verification cloud password. */
    public static final boolean CLOUD_PASSWORD = false;

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
