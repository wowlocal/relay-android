package org.telegram.relay.mattermost.live;

import org.junit.Assume;
import org.telegram.relay.mattermost.MattermostClient;
import org.telegram.relay.mattermost.MattermostException;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

/**
 * The local Mattermost dev server (iOS repo {@code infra/dev-server}, compose project {@code mmdev}).
 *
 * <ul>
 *   <li>{@code RELAY_MM_URL}: server URL (default {@code http://localhost:8065}).</li>
 *   <li>{@code RELAY_MM_PASSWORD}: the shared password of alice/bob/carol/dave; otherwise read from
 *       {@code SEED_USER_PASSWORD} in {@code RELAY_MM_ENV_FILE} (default: the dev server's {@code .env}).
 *       The password is never stored in this repo.</li>
 *   <li>{@code RELAY_MM_LIVE=0}: skip the live tests.</li>
 * </ul>
 * Tests are skipped (not failed) when the server is not reachable.
 */
final class LiveServer {
    private LiveServer() {
    }

    private static final String DEFAULT_ENV_FILE = System.getProperty("user.home") + "/Developer/relay-calls-integration/ios-device/infra/dev-server/.env";
    private static Boolean reachable;

    static String url() {
        String url = System.getenv("RELAY_MM_URL");
        return url != null && !url.isEmpty() ? url : "http://localhost:8065";
    }

    static synchronized void assumeAvailable() {
        Assume.assumeFalse("RELAY_MM_LIVE=0", "0".equals(System.getenv("RELAY_MM_LIVE")));
        if (reachable == null) {
            MattermostClient client = new MattermostClient(url());
            client.setTimeouts(2000, 3000);
            try {
                reachable = client.ping(false).isOk();
            } catch (MattermostException e) {
                reachable = false;
            }
        }
        Assume.assumeTrue("Mattermost dev server not reachable at " + url(), reachable);
        Assume.assumeTrue("no password: set RELAY_MM_PASSWORD or provide the dev server .env", password() != null);
    }

    static String password() {
        String password = System.getenv("RELAY_MM_PASSWORD");
        if (password != null && !password.isEmpty()) {
            return password;
        }
        String path = System.getenv("RELAY_MM_ENV_FILE");
        File file = new File(path != null && !path.isEmpty() ? path : DEFAULT_ENV_FILE);
        if (!file.isFile()) {
            return null;
        }
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), Charset.forName("UTF-8")));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("SEED_USER_PASSWORD=")) {
                        String value = line.substring("SEED_USER_PASSWORD=".length()).trim();
                        if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'"))) {
                            value = value.substring(1, value.length() - 1);
                        }
                        return value;
                    }
                }
            } finally {
                reader.close();
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    /** A client logged in as {@code username}. */
    static MattermostClient login(String username) throws MattermostException {
        MattermostClient client = new MattermostClient(url());
        client.setUserAgent("relay-mattermost-tests");
        client.login(username, password());
        return client;
    }

    static void logoutQuietly(MattermostClient client) {
        if (client == null || client.getToken() == null) {
            return;
        }
        try {
            client.logout();
        } catch (MattermostException ignored) {
            // the session expires anyway
        }
    }
}
