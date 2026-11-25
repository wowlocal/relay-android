package org.telegram.relay.mattermost.calls;

/**
 * The plugin routes the call controllers use (blocking; the controllers run them on their I/O executor).
 * {@link RelayCallsClient} implements it over a Mattermost session; tests use a fake.
 */
public interface RelayCallsApi {
    /** {@code GET /calls/<id>}; null when forgotten ({@code 404}). */
    RelayCall call(String callId) throws RelayCallsException;

    /** {@code POST /channels/<id>/join}: joins the channel's call ([callId] or the current one) or starts one. */
    RelayCallJoin join(String channelId, String callId, String deviceId) throws RelayCallsException;

    /** {@code POST /channels/<id>/ring}: a {@code 409} with a running call throws {@link RelayCallsException#isCallInProgress()}. */
    RelayCallJoin ring(String channelId, boolean video, String deviceId) throws RelayCallsException;

    void end(String channelId, String callId) throws RelayCallsException;

    void received(String callId, String deviceId) throws RelayCallsException;

    RelayCallJoin accept(String callId, String deviceId) throws RelayCallsException;

    /** [reason]: {@code declined} or {@code busy}. */
    void decline(String callId, String deviceId, String reason) throws RelayCallsException;

    void hangup(String callId, String deviceId) throws RelayCallsException;
}
