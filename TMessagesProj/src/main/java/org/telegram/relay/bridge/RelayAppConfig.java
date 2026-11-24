package org.telegram.relay.bridge;

import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

/**
 * {@code help.getAppConfig} for Relay: limits high enough for Mattermost (folders = teams, no premium
 * tiers), purchases blocked, stories off. Bump {@link #VERSION} whenever a value changes so clients
 * holding the old hash reload it.
 */
final class RelayAppConfig {
    private RelayAppConfig() {
    }

    static final int VERSION = 1;
    static final int HASH = 0x52450000 | VERSION;

    /** Mattermost's default {@code MaxPostSize} (runes). */
    static final int MAX_POST_SIZE = 16383;

    static TLObject answer(TLRPC.TL_help_getAppConfig request) {
        if (request.hash == HASH) {
            return new TLRPC.TL_help_appConfigNotModified();
        }
        TLRPC.TL_jsonObject json = new TLRPC.TL_jsonObject();
        number(json, "dialog_filters_limit_default", 500);
        number(json, "dialog_filters_limit_premium", 500);
        number(json, "dialog_filters_chats_limit_default", 10000);
        number(json, "dialog_filters_chats_limit_premium", 10000);
        number(json, "dialog_filters_pinned_limit_default", 100);
        number(json, "dialog_filters_pinned_limit_premium", 100);
        number(json, "pinned_dialogs_count_max_default", 100);
        number(json, "pinned_dialogs_count_max_premium", 100);
        number(json, "channels_limit_default", 10000);
        number(json, "channels_limit_premium", 10000);
        number(json, "channels_public_limit_default", 10000);
        number(json, "channels_public_limit_premium", 10000);
        number(json, "caption_length_limit_default", MAX_POST_SIZE);
        number(json, "caption_length_limit_premium", MAX_POST_SIZE);
        number(json, "about_length_limit_default", 1024);
        number(json, "about_length_limit_premium", 1024);
        number(json, "upload_max_fileparts_default", 4000);
        number(json, "upload_max_fileparts_premium", 4000);
        number(json, "reactions_uniq_max", 50);
        number(json, "reactions_user_max_default", 50);
        number(json, "reactions_user_max_premium", 50);
        number(json, "quote_length_max", MAX_POST_SIZE);
        number(json, "saved_dialogs_pinned_limit_default", 100);
        number(json, "saved_dialogs_pinned_limit_premium", 100);
        bool(json, "premium_purchase_blocked", true);
        bool(json, "stars_purchase_blocked", true);
        bool(json, "stars_gifts_enabled", false);
        bool(json, "stargifts_blocked", true);
        bool(json, "dialog_filters_enabled", true);
        bool(json, "dialog_filters_tooltip", false);
        bool(json, "qr_login_camera", false);
        bool(json, "autoarchive_setting_available", false);
        bool(json, "translations_manual_enabled", false);
        bool(json, "translations_auto_enabled", false);
        string(json, "stories_posting", "disabled");

        TLRPC.TL_help_appConfig config = new TLRPC.TL_help_appConfig();
        config.hash = HASH;
        config.config = json;
        return config;
    }

    private static void put(TLRPC.TL_jsonObject json, String key, TLRPC.JSONValue value) {
        TLRPC.TL_jsonObjectValue entry = new TLRPC.TL_jsonObjectValue();
        entry.key = key;
        entry.value = value;
        json.value.add(entry);
    }

    private static void number(TLRPC.TL_jsonObject json, String key, double value) {
        TLRPC.TL_jsonNumber n = new TLRPC.TL_jsonNumber();
        n.value = value;
        put(json, key, n);
    }

    private static void bool(TLRPC.TL_jsonObject json, String key, boolean value) {
        TLRPC.TL_jsonBool b = new TLRPC.TL_jsonBool();
        b.value = value;
        put(json, key, b);
    }

    private static void string(TLRPC.TL_jsonObject json, String key, String value) {
        TLRPC.TL_jsonString s = new TLRPC.TL_jsonString();
        s.value = value;
        put(json, key, s);
    }
}
