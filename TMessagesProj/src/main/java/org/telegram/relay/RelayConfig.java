package org.telegram.relay;

import android.util.Log;

import org.telegram.messenger.BuildConfig;

/**
 * Relay switches. {@link #ENABLED} is a compile-time constant: Relay for Android always talks to
 * Mattermost through {@link org.telegram.relay.bridge.RelayBridge}, and native tgnet never connects.
 */
public final class RelayConfig {
    private RelayConfig() {
    }

    /** Relay mode: every TL request goes to the bridge, tgnet is never initialised. */
    public static final boolean ENABLED = true;

    /** Debug-only behaviour (unhandled-method summaries, verbose logs). */
    public static boolean isDebug() {
        return BuildConfig.DEBUG_VERSION;
    }

    /**
     * Polish 2b: the folder name in the shared storage (Pictures/, Movies/, Download/, Music/) where saved media
     * goes, and the title of the app folder in the file picker. Telegram uses "Telegram".
     */
    public static final String MEDIA_FOLDER = ENABLED ? "Relay" : "Telegram";

    public static final String TAG_BRIDGE = "RelayBridge";
    public static final String TAG_AUTH = "RelayAuth";

    public static void log(String tag, String message) {
        Log.i(tag, message);
    }

    public static void logError(String tag, String message, Throwable error) {
        Log.w(tag, message, error);
    }
}
