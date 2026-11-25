package org.telegram.relay.mattermost.calls;

/**
 * The microphone or the camera of a call (port of the iOS {@code RelayLocalSource}). {@link #wanted} is the
 * user's choice, never the result of a publish. While the room is up ({@link #setReady}) it is applied through
 * the engine one request at a time; a failure (or a request that does not answer within the timeout: logged
 * {@code no answer after 15.0 s}, a late answer only logs) is retried after the delays (microphone 0.5, 1, 2, 4,
 * 8, 8, 8, 8 s, then it waits; camera 1, 2, 4 s, then {@link #onRetriesExhausted}), and again right away on
 * {@link #setWanted} and {@link #retry} (reconnect, audio became available). At most two requests may be
 * unanswered at a time, so a stalled engine is not asked over and over.
 */
final class RelayLocalSource {
    static final long[] MICROPHONE_RETRY_DELAYS = {500, 1000, 2000, 4000, 8000, 8000, 8000, 8000};
    static final long[] CAMERA_RETRY_DELAYS = {1000, 2000, 4000};
    static final long MICROPHONE_REQUEST_TIMEOUT = 15000;
    static final long CAMERA_REQUEST_TIMEOUT = 20000;
    static final int MAX_UNANSWERED_REQUESTS = 2;

    interface Apply {
        void apply(boolean enabled, RelayMedia.Result result);
    }

    interface Describe {
        String describe();
    }

    final String name;
    private final RelayCallScheduler scheduler;
    private final long[] retryDelays;
    private final long requestTimeout;
    private final Apply apply;
    Describe describe = () -> "call";
    Runnable onRetriesExhausted;

    private boolean wanted;
    /** null = unknown. */
    private Boolean applied;
    private boolean isApplying;
    private boolean isReady;
    private int failures;
    private int failedAttempts;
    private RelayCallScheduler.Cancellable retryTimer;
    private RelayCallScheduler.Cancellable timeoutTimer;
    private int requestId;
    private int unanswered;
    private int generation;

    RelayLocalSource(String name, boolean wanted, Boolean applied, RelayCallScheduler scheduler, long[] retryDelays, long requestTimeout, Apply apply) {
        this.name = name;
        this.wanted = wanted;
        this.applied = applied;
        this.scheduler = scheduler;
        this.retryDelays = retryDelays;
        this.requestTimeout = requestTimeout;
        this.apply = apply;
    }

    boolean wanted() {
        return wanted;
    }

    int failures() {
        return failures;
    }

    void setReady(boolean ready) {
        if (isReady == ready) {
            return;
        }
        isReady = ready;
        if (ready) {
            sync();
        } else {
            cancelRetry();
        }
    }

    void setWanted(boolean value) {
        wanted = value;
        failures = 0;
        cancelRetry();
        sync();
    }

    void retry(String reason) {
        failures = 0;
        cancelRetry();
        if (!isReady || (applied != null && applied == wanted)) {
            return;
        }
        RelayCallsLog.log(describe.describe() + ": " + name + " " + word(wanted) + " again (" + reason + ")");
        sync();
    }

    void stop() {
        isReady = false;
        cancelRetry();
        if (timeoutTimer != null) {
            timeoutTimer.cancel();
            timeoutTimer = null;
        }
        generation++;
        isApplying = false;
        applied = null;
        unanswered = 0;
    }

    private void sync() {
        if (!isReady || isApplying || (applied != null && applied == wanted)) {
            return;
        }
        if (unanswered >= MAX_UNANSWERED_REQUESTS) {
            RelayCallsLog.log(describe.describe() + ": " + name + " " + word(wanted) + " waits: " + unanswered + " earlier requests have not answered");
            return;
        }
        unanswered++;
        isApplying = true;
        final int request = ++requestId;
        final boolean target = wanted;
        final int gen = generation;
        if (timeoutTimer != null) {
            timeoutTimer.cancel();
        }
        timeoutTimer = scheduler.schedule(requestTimeout, () -> timedOut(request, target));
        apply.apply(target, error -> scheduler.post(() -> finished(request, gen, target, error)));
    }

    private void finished(int request, int gen, boolean target, Throwable error) {
        if (gen != generation) {
            return;
        }
        unanswered = Math.max(0, unanswered - 1);
        if (request != requestId || !isApplying) {
            // It timed out before: a newer request (or a retry) took over.
            if (error != null) {
                RelayCallsLog.log(describe.describe() + ": " + name + " " + word(target) + " answered after its timeout: failed: " + error);
            } else {
                RelayCallsLog.log(describe.describe() + ": " + name + " " + word(target) + " answered after its timeout: succeeded");
                if (!isApplying && target == wanted) {
                    applied = target;
                    failures = 0;
                    failedAttempts = 0;
                    cancelRetry();
                }
            }
            if (retryTimer == null) {
                sync();
            }
            return;
        }
        if (timeoutTimer != null) {
            timeoutTimer.cancel();
            timeoutTimer = null;
        }
        isApplying = false;
        if (error != null) {
            applied = null;
            failures++;
            failedAttempts++;
            failed(target, error);
            return;
        }
        applied = target;
        if (failedAttempts > 0) {
            RelayCallsLog.log(describe.describe() + ": " + name + " " + word(target) + " succeeded after " + failedAttempts + " failed attempt(s)");
        } else {
            RelayCallsLog.log(describe.describe() + ": " + name + " " + word(target));
        }
        failures = 0;
        failedAttempts = 0;
        cancelRetry();
        // The user may have changed it meanwhile.
        sync();
    }

    private void timedOut(int request, boolean target) {
        if (request != requestId || !isApplying) {
            return;
        }
        timeoutTimer = null;
        isApplying = false;
        applied = null;
        failures++;
        failedAttempts++;
        failed(target, new Exception("no answer after " + (requestTimeout / 1000.0) + " s"));
    }

    private void failed(boolean target, Throwable error) {
        int attempt = failures;
        if (wanted != target) {
            // The user changed it while the request ran: apply the new choice right away.
            RelayCallsLog.log(describe.describe() + ": " + name + " " + word(target) + " failed: " + error);
            failures = 0;
            sync();
            return;
        }
        if (attempt <= retryDelays.length) {
            long delay = retryDelays[attempt - 1];
            RelayCallsLog.log(describe.describe() + ": " + name + " " + word(target) + " failed (attempt " + attempt + "): " + error + "; retrying in " + (delay / 1000.0) + " s");
            cancelRetry();
            retryTimer = scheduler.schedule(delay, () -> {
                retryTimer = null;
                sync();
            });
        } else {
            RelayCallsLog.log(describe.describe() + ": " + name + " " + word(target) + " failed (attempt " + attempt + "): " + error + "; no more automatic retries");
            if (onRetriesExhausted != null) {
                onRetriesExhausted.run();
            }
        }
    }

    private void cancelRetry() {
        if (retryTimer != null) {
            retryTimer.cancel();
            retryTimer = null;
        }
    }

    private static String word(boolean enabled) {
        return enabled ? "on" : "off";
    }
}
