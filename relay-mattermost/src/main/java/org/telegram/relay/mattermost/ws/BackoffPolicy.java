package org.telegram.relay.mattermost.ws;

/** Exponential backoff with jitter for reconnects. */
public final class BackoffPolicy {
    public final long initialDelayMillis;
    public final double multiplier;
    public final long maximumDelayMillis;
    /** Fraction of the delay that is randomised, 0…1. */
    public final double jitter;

    public BackoffPolicy() {
        this(1000, 2, 60_000, 0.5);
    }

    public BackoffPolicy(long initialDelayMillis, double multiplier, long maximumDelayMillis, double jitter) {
        this.initialDelayMillis = initialDelayMillis;
        this.multiplier = multiplier;
        this.maximumDelayMillis = maximumDelayMillis;
        this.jitter = jitter;
    }

    /**
     * @param attempt 1 for the first retry.
     * @param random  a value in {@code [0, 1)}.
     */
    public long delayMillis(int attempt, double random) {
        double exponent = Math.max(0, attempt - 1);
        double base = Math.min(maximumDelayMillis, initialDelayMillis * Math.pow(multiplier, exponent));
        double j = Math.min(Math.max(jitter, 0), 1);
        double r = Math.min(Math.max(random, 0), 1);
        return Math.round(base * (1 - j) + base * j * r);
    }
}
