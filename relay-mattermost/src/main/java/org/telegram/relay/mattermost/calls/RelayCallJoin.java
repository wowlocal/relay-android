package org.telegram.relay.mattermost.calls;

/** {@code {url, token, call}}: what join, ring and accept answer. */
public final class RelayCallJoin {
    public final String url;
    public final String token;
    public final RelayCall call;

    public RelayCallJoin(String url, String token, RelayCall call) {
        this.url = url;
        this.token = token;
        this.call = call;
    }
}
