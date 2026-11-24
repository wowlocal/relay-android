package org.telegram.relay.mattermost;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/** Opens connections; replace to add proxies, TLS pinning or test stubs. */
public interface ConnectionFactory {
    HttpURLConnection open(URL url) throws IOException;

    ConnectionFactory DEFAULT = new ConnectionFactory() {
        @Override
        public HttpURLConnection open(URL url) throws IOException {
            return (HttpURLConnection) url.openConnection();
        }
    };
}
