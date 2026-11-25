package org.telegram.relay.mattermost.calls;

/** Shared helpers of the call controllers. */
final class RelayCalls {
    private RelayCalls() {
    }

    /** Runs a blocking plugin request on the I/O executor and delivers its outcome on the controllers' thread. */
    static <T> void request(RelayCallScheduler scheduler, RelayDirectCall.Call<T> call, RelayDirectCall.Ok<T> ok, RelayDirectCall.Failed failed) {
        scheduler.io(() -> {
            try {
                T value = call.run();
                scheduler.post(() -> ok.done(value));
            } catch (RelayCallsException e) {
                scheduler.post(() -> failed.failed(e));
            } catch (RuntimeException e) {
                RelayCallsException wrapped = new RelayCallsException(String.valueOf(e), e);
                scheduler.post(() -> failed.failed(wrapped));
            }
        });
    }
}
