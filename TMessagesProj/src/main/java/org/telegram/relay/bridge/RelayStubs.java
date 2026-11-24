package org.telegram.relay.bridge;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_stories;

import java.util.Map;

/**
 * Cheap local answers for methods Relay does not back with Mattermost (yet). Each answer follows the
 * startup table in {@code docs/relay/telegram-internals.md} §3: empty, "not modified" or a specific
 * non-error object where an error would loop or crash the client.
 */
final class RelayStubs {
    private RelayStubs() {
    }

    interface Stub {
        TLObject answer(TLObject request, int account);
    }

    static int now(int account) {
        return ConnectionsManager.getInstance(account).getCurrentTime();
    }

    static TLRPC.TL_peerNotifySettings notifySettings() {
        TLRPC.TL_peerNotifySettings settings = new TLRPC.TL_peerNotifySettings();
        settings.flags = TLObject.FLAG_0;
        settings.show_previews = true;
        return settings;
    }

    static void register(Map<Class<?>, Stub> map) {
        Stub boolTrue = (req, account) -> new TLRPC.TL_boolTrue();
        Stub emptyVector = (req, account) -> RelayTl.emptyVector();

        // Group A: always or regularly sent at startup (§3.3).
        map.put(TLRPC.TL_help_getPeerColors.class, (req, account) -> new TLRPC.TL_help_peerColorsNotModified());
        map.put(TLRPC.TL_help_getPeerProfileColors.class, (req, account) -> new TLRPC.TL_help_peerColorsNotModified());
        map.put(TL_account.getWebBrowserSettings.class, (req, account) -> new TL_account.TL_webBrowserSettingsNotModified());
        map.put(TLRPC.TL_messages_getAvailableEffects.class, (req, account) -> new TLRPC.TL_messages_availableEffectsNotModified());
        map.put(TL_account.getAutoDownloadSettings.class, (req, account) -> {
            TL_account.autoDownloadSettings s = new TL_account.autoDownloadSettings();
            s.low = new TLRPC.TL_autoDownloadSettings();
            s.medium = new TLRPC.TL_autoDownloadSettings();
            s.high = new TLRPC.TL_autoDownloadSettings();
            return s;
        });
        map.put(TLRPC.TL_contacts_getStatuses.class, emptyVector);
        map.put(TLRPC.TL_help_getInviteText.class, (req, account) -> {
            TLRPC.TL_help_inviteText text = new TLRPC.TL_help_inviteText();
            text.message = "Join me on Relay";
            return text;
        });
        map.put(TL_account.getGlobalPrivacySettings.class, (req, account) -> new TLRPC.TL_globalPrivacySettings());
        // Must be a TLRPC.Updates: StoriesController casts the response unchecked.
        map.put(TL_stories.TL_stories_getAllReadPeerStories.class, (req, account) -> {
            TLRPC.TL_updates updates = new TLRPC.TL_updates();
            updates.date = now(account);
            return updates;
        });
        map.put(TL_stories.TL_stories_getAllStories.class, (req, account) -> {
            TL_stories.TL_stories_allStories all = new TL_stories.TL_stories_allStories();
            all.count = 0;
            all.state = "";
            all.stealth_mode = new TL_stories.TL_storiesStealthMode();
            return all;
        });
        map.put(TL_account.getBirthdays.class, (req, account) -> new TL_account.contactBirthdays());
        map.put(TLRPC.TL_messages_getFavedStickers.class, (req, account) -> new TLRPC.TL_messages_favedStickersNotModified());
        map.put(TLRPC.TL_messages_getStickers.class, (req, account) -> new TLRPC.TL_messages_stickersNotModified());
        // Errors here loop every second (MDC processLoadedFeaturedStickers with hash 0).
        Stub featured = (req, account) -> {
            TLRPC.TL_messages_featuredStickers f = new TLRPC.TL_messages_featuredStickers();
            f.hash = 1;
            f.count = 0;
            return f;
        };
        map.put(TLRPC.TL_messages_getFeaturedStickers.class, featured);
        map.put(TLRPC.TL_messages_getFeaturedEmojiStickers.class, featured);
        // Same 1 s loop on an error for the installed sets (MDC processLoadedStickers).
        Stub allStickers = (req, account) -> new TLRPC.TL_messages_allStickers();
        map.put(TLRPC.TL_messages_getAllStickers.class, allStickers);
        map.put(TLRPC.TL_messages_getEmojiStickers.class, allStickers);
        map.put(TLRPC.TL_messages_getMaskStickers.class, allStickers);
        map.put(TLRPC.TL_messages_getAvailableReactions.class, (req, account) -> {
            TLRPC.TL_messages_availableReactions r = new TLRPC.TL_messages_availableReactions();
            r.hash = 1;
            return r;
        });
        map.put(TLRPC.TL_messages_getRecentReactions.class, (req, account) -> new TLRPC.TL_messages_reactionsNotModified());
        map.put(TLRPC.TL_messages_getTopReactions.class, (req, account) -> new TLRPC.TL_messages_reactionsNotModified());
        map.put(TLRPC.TL_messages_getAttachMenuBots.class, (req, account) -> new TLRPC.TL_attachMenuBots());
        map.put(TLRPC.TL_help_getPremiumPromo.class, (req, account) -> {
            TLRPC.TL_help_premiumPromo p = new TLRPC.TL_help_premiumPromo();
            p.status_text = "";
            return p;
        });
        // The animated-emoji set must be a stickerSet with a non-zero id, or MDC retries every second.
        map.put(TLRPC.TL_messages_getStickerSet.class, (req, account) -> {
            TLRPC.TL_messages_getStickerSet r = (TLRPC.TL_messages_getStickerSet) req;
            if (r.stickerset instanceof TLRPC.TL_inputStickerSetAnimatedEmoji) {
                TLRPC.TL_messages_stickerSet set = new TLRPC.TL_messages_stickerSet();
                TLRPC.TL_stickerSet s = new TLRPC.TL_stickerSet();
                s.id = 0x52656c6179L; // "Relay"
                s.access_hash = 1;
                s.title = "Relay";
                s.short_name = "relay_emoji";
                s.emojis = true;
                set.set = s;
                return set;
            }
            return RelayTl.error(400, "STICKERSET_INVALID");
        });
        map.put(TL_account.getDefaultProfilePhotoEmojis.class, (req, account) -> new TLRPC.TL_emojiListNotModified());
        map.put(TL_account.getDefaultGroupPhotoEmojis.class, (req, account) -> new TLRPC.TL_emojiListNotModified());
        // Never topPeersDisabled: it turns search hints off for good.
        map.put(TLRPC.TL_contacts_getTopPeers.class, (req, account) -> new TLRPC.TL_contacts_topPeers());
        // No languages: listing "en" would start a remote langpack download.
        map.put(TLRPC.TL_langpack_getLanguages.class, emptyVector);
        map.put(TLRPC.TL_help_saveAppLog.class, boolTrue);

        // Account / notification settings (§3.1, stub-level for A2).
        map.put(TL_account.getNotifySettings.class, (req, account) -> notifySettings());
        map.put(TL_account.getReactionsNotifySettings.class, (req, account) -> {
            TL_account.TL_reactionsNotifySettings s = new TL_account.TL_reactionsNotifySettings();
            s.sound = new TLRPC.TL_notificationSoundDefault();
            s.show_previews = true;
            return s;
        });
        map.put(TL_account.getContactSignUpNotification.class, boolTrue);
        map.put(TL_account.registerDevice.class, boolTrue);
        map.put(TL_account.unregisterDevice.class, boolTrue);
        map.put(TL_account.updateStatus.class, boolTrue);
        map.put(TL_account.getThemes.class, (req, account) -> new TL_account.TL_themesNotModified());

        // Periodic (§3.2).
        map.put(TLRPC.TL_help_getPromoData.class, (req, account) -> {
            TLRPC.TL_help_promoDataEmpty p = new TLRPC.TL_help_promoDataEmpty();
            p.expires = now(account) + 86400;
            return p;
        });
        map.put(TLRPC.TL_help_getTermsOfServiceUpdate.class, (req, account) -> {
            TLRPC.TL_help_termsOfServiceUpdateEmpty t = new TLRPC.TL_help_termsOfServiceUpdateEmpty();
            t.expires = now(account) + 86400;
            return t;
        });

        // Group B: conditional.
        map.put(TLRPC.TL_help_getAppUpdate.class, (req, account) -> new TLRPC.TL_help_noAppUpdate());
        map.put(TLRPC.TL_messages_getSuggestedDialogFilters.class, emptyVector);

        // Chat screen side calls (A3): empty answers until the feature is backed by Mattermost.
        Stub emptyMessages = (req, account) -> new TLRPC.TL_messages_messages();
        map.put(TLRPC.TL_messages_search.class, emptyMessages);           // pinned bar, shared media (A7)
        map.put(TLRPC.TL_messages_getScheduledHistory.class, emptyMessages);
        map.put(TLRPC.TL_messages_getSponsoredMessages.class, (req, account) -> new TLRPC.TL_messages_sponsoredMessagesEmpty());
        map.put(TLRPC.TL_messages_getPeerSettings.class, (req, account) -> {
            TLRPC.TL_messages_peerSettings s = new TLRPC.TL_messages_peerSettings();
            s.settings = new TLRPC.TL_peerSettings();
            return s;
        });
        map.put(TLRPC.TL_channels_getParticipants.class, (req, account) -> new TLRPC.TL_channels_channelParticipants()); // members list: A7
    }
}
