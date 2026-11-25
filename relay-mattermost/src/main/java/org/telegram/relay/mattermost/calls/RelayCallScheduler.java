package org.telegram.relay.mattermost.calls;

/**
 * The controllers' thread and clock (port of {@code RelayCallScheduler}): everything of a call runs through
 * {@link #post}; blocking plugin requests run through {@link #io} and post their results back. The app uses the
 * Android main thread and a background executor; tests use a manual clock.
 */
public interface RelayCallScheduler {
    /** Unix ms. */
    long now();

    /** Runs [action] on the controllers' thread after [delayMs]; the returned handle cancels it. */
    Cancellable schedule(long delayMs, Runnable action);

    /** Runs [action] on the controllers' thread (later, never inline). */
    void post(Runnable action);

    /** Runs a blocking [task] off the controllers' thread. */
    void io(Runnable task);

    interface Cancellable {
        void cancel();
    }
}
