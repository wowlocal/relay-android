package org.telegram.relay.bridge;

import android.net.Uri;

import org.telegram.relay.account.RelaySession;
import org.telegram.relay.account.RelaySessionStore;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

/**
 * The {@code TL_config} native tgnet used to fetch with {@code help.getConfig} and push through
 * {@code MessagesController.updateConfig}. Relay pushes its own once per start.
 */
public final class RelayServerConfig {
    private RelayServerConfig() {
    }

    /** "Unlimited" edit/delete windows: ten years (MessagesController adds these to message dates). */
    static final int UNLIMITED_SECONDS = 10 * 365 * 24 * 60 * 60;

    public static TLRPC.TL_config build(int account) {
        int now = ConnectionsManager.getInstance(account).getCurrentTime();
        TLRPC.TL_config c = new TLRPC.TL_config();
        c.date = now;
        c.expires = now + 3600;
        c.test_mode = false;
        c.this_dc = 1;
        c.dc_txt_domain_name = "";
        c.chat_size_max = 200;
        c.megagroup_size_max = 200_000;
        c.forwarded_count_max = 100;
        c.online_update_period_ms = 120_000;
        c.offline_blur_timeout_ms = 5_000;
        c.offline_idle_timeout_ms = 30_000;
        c.online_cloud_timeout_ms = 300_000;
        c.notify_cloud_delay_ms = 30_000;
        c.notify_default_delay_ms = 1_500;
        c.push_chat_period_ms = 60_000;
        c.push_chat_limit = 2;
        c.edit_time_limit = UNLIMITED_SECONDS;
        c.revoke_time_limit = UNLIMITED_SECONDS;
        c.revoke_pm_time_limit = UNLIMITED_SECONDS;
        c.revoke_pm_inbox = true;
        c.rating_e_decay = 2_419_200;
        c.stickers_recent_limit = 0;
        c.channels_read_media_period = 604_800;
        c.call_receive_timeout_ms = 20_000;
        c.call_ring_timeout_ms = 90_000;
        c.call_connect_timeout_ms = 30_000;
        c.call_packet_timeout_ms = 10_000;
        c.me_url_prefix = linkPrefix(account);
        c.caption_length_max = RelayAppConfig.MAX_POST_SIZE;
        c.message_length_max = RelayAppConfig.MAX_POST_SIZE;
        c.webfile_dc_id = 1;
        // null: no remote language suggestion, so no langpack traffic (MessagesController.updateConfig).
        c.suggested_lang_code = null;
        c.lang_pack_version = 0;
        c.base_lang_pack_version = 0;
        c.static_maps_provider = null;
        c.autologin_token = null;
        return c;
    }

    /** {@code <host>/} of the account's server: what Telegram prefixes to public links. */
    private static String linkPrefix(int account) {
        RelaySession session = RelaySessionStore.get(account);
        if (session != null) {
            try {
                Uri uri = Uri.parse(session.serverUrl);
                String host = uri.getHost();
                if (host != null) {
                    return host + (uri.getPort() > 0 ? ":" + uri.getPort() : "") + "/";
                }
            } catch (Exception ignore) {
            }
        }
        return "relay.invalid/";
    }
}
