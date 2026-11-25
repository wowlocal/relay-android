package org.telegram.relay.ui;

import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.relay.RelayConfig;
import org.telegram.relay.account.RelayAccount;

/**
 * Polish 2b: the server's upload limit ({@code MaxFileSize} of the client config) for texts that quote one, such
 * as the gallery's empty state, which said Telegram's "up to 2 GB". Fetched once per account in the background.
 */
public final class RelayServerLimits {
    private RelayServerLimits() {
    }

    private static final long[] maxFileSize = new long[UserConfig.MAX_ACCOUNT_COUNT];
    private static final boolean[] loading = new boolean[UserConfig.MAX_ACCOUNT_COUNT];

    /** The server's MaxFileSize in bytes, 0 while unknown (a fetch is started). Any thread. */
    public static long maxFileSize(int account) {
        if (account < 0 || account >= maxFileSize.length) {
            return 0;
        }
        synchronized (maxFileSize) {
            if (maxFileSize[account] > 0 || loading[account]) {
                return maxFileSize[account];
            }
            loading[account] = true;
        }
        Utilities.globalQueue.postRunnable(() -> {
            long value = 0;
            try {
                RelayAccount relayAccount = RelayAccount.get(account);
                String raw = relayAccount != null ? relayAccount.client.clientConfig().get("MaxFileSize") : null;
                value = raw != null ? Long.parseLong(raw.trim()) : 0;
            } catch (Exception e) {
                RelayConfig.logError(RelayConfig.TAG_BRIDGE, "client config MaxFileSize", e);
            }
            synchronized (maxFileSize) {
                maxFileSize[account] = value;
                loading[account] = false;
            }
        });
        return 0;
    }

    /** The gallery's "share photos and videos …" line with the server's limit (generic until it is known). */
    public static void bindGalleryAccessText(TextView view, int account) {
        long max = maxFileSize(account);
        if (max > 0) {
            view.setText(LocaleController.formatString(R.string.RelayGalleryAccessText, AndroidUtilities.formatFileSize(max)));
            return;
        }
        view.setText(LocaleController.getString(R.string.GalleryAccessAllowAccessTextNonPremium));
        AndroidUtilities.runOnUIThread(() -> {
            if (maxFileSize(account) > 0) {
                view.setText(LocaleController.formatString(R.string.RelayGalleryAccessText, AndroidUtilities.formatFileSize(maxFileSize(account))));
            }
        }, 1500);
    }
}
