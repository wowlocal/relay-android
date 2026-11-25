package org.telegram.relay.mattermost.calls;

/** The core's diagnostics (port of {@code RelayCallsLog}): nothing is logged until the app installs a handler. */
public final class RelayCallsLog {
    public interface Handler {
        void log(String text);
    }

    public static volatile Handler handler;

    private RelayCallsLog() {
    }

    public static void log(String text) {
        Handler h = handler;
        if (h != null) {
            h.log(text);
        }
    }
}
