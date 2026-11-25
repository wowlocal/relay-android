package org.telegram.relay.mattermost.calls;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fakes of the plugin, the media engine and the clock (port of the iOS core's Fakes.swift). */
final class Fakes {
    static final String ME = "me00000000000000000000000m";
    static final String PEER = "peer000000000000000000000p";
    static final String DM = "dm0000000000000000000000dm";
    static final String DEVICE = "device-1";

    static RelayCall call(String id, RelayCall.Kind kind, RelayCall.State state, String startedBy) {
        RelayCall c = new RelayCall();
        c.id = id;
        c.channelId = DM;
        c.kind = kind;
        c.state = state;
        c.startedBy = startedBy;
        return c;
    }

    static RelayCall ringing(String id, String startedBy, String invitee, RelayCall.Invitee state, long expiresAt) {
        RelayCall c = call(id, RelayCall.Kind.DIRECT, RelayCall.State.RINGING, startedBy);
        c.invitees.put(invitee, state);
        c.ringExpiresAt = expiresAt;
        return c;
    }

    static RelayCall ended(RelayCall from, RelayCall.EndReason reason, long durationMs) {
        RelayCall c = from.copy();
        c.state = RelayCall.State.ENDED;
        c.endReason = reason;
        c.durationMs = durationMs;
        return c;
    }

    static RelayMedia.Participant device(String userId, String device, boolean local, boolean mic, boolean camera) {
        return new RelayMedia.Participant(userId + ":" + device, userId, local, false, 0, 0,
                mic ? new RelayMedia.Track("MIC_" + userId + device, false, 0, 0) : null,
                camera ? new RelayMedia.Track("CAM_" + userId + device, false, 640, 360) : null, null, 0);
    }

    /** Virtual clock; posts and timers run when the test calls {@link #run()} / {@link #advance}. io runs inline. */
    static final class Scheduler implements RelayCallScheduler {
        long now = 1_000_000;
        final Deque<Runnable> posted = new ArrayDeque<>();

        private static final class Timer {
            final long at;
            final Runnable action;
            boolean cancelled;

            Timer(long at, Runnable action) {
                this.at = at;
                this.action = action;
            }
        }

        final List<Timer> timers = new ArrayList<>();

        @Override
        public long now() {
            return now;
        }

        @Override
        public Cancellable schedule(long delayMs, Runnable action) {
            Timer t = new Timer(now + delayMs, action);
            timers.add(t);
            return () -> t.cancelled = true;
        }

        @Override
        public void post(Runnable action) {
            posted.add(action);
        }

        @Override
        public void io(Runnable task) {
            task.run();
        }

        void run() {
            for (int i = 0; i < 10_000 && !posted.isEmpty(); i++) {
                posted.poll().run();
            }
        }

        void advance(long ms) {
            long target = now + ms;
            run();
            while (true) {
                Timer next = null;
                for (Timer t : timers) {
                    if (!t.cancelled && t.at <= target && (next == null || t.at < next.at)) {
                        next = t;
                    }
                }
                if (next == null) {
                    break;
                }
                timers.remove(next);
                now = Math.max(now, next.at);
                next.action.run();
                run();
            }
            now = target;
            run();
        }
    }

    static final class Api implements RelayCallsApi {
        final List<String> requests = new ArrayList<>();
        RelayCallJoin ringResult;
        RelayCallsException ringError;
        RelayCallJoin joinResult;
        RelayCallsException joinError;
        RelayCallJoin acceptResult;
        RelayCallsException acceptError;
        final Map<String, RelayCall> calls = new LinkedHashMap<>();

        @Override
        public RelayCall call(String callId) {
            requests.add("call " + callId);
            return calls.get(callId);
        }

        @Override
        public RelayCallJoin join(String channelId, String callId, String deviceId) throws RelayCallsException {
            requests.add("join " + channelId + " " + callId);
            if (joinError != null) {
                throw joinError;
            }
            return joinResult;
        }

        @Override
        public RelayCallJoin ring(String channelId, boolean video, String deviceId) throws RelayCallsException {
            requests.add("ring " + channelId + (video ? " video" : ""));
            if (ringError != null) {
                throw ringError;
            }
            return ringResult;
        }

        @Override
        public void end(String channelId, String callId) {
            requests.add("end " + callId);
        }

        @Override
        public void received(String callId, String deviceId) {
            requests.add("received " + callId);
        }

        @Override
        public RelayCallJoin accept(String callId, String deviceId) throws RelayCallsException {
            requests.add("accept " + callId);
            if (acceptError != null) {
                throw acceptError;
            }
            return acceptResult;
        }

        @Override
        public void decline(String callId, String deviceId, String reason) {
            requests.add("decline " + callId + " " + reason);
        }

        @Override
        public void hangup(String callId, String deviceId) {
            requests.add("hangup " + callId);
        }

        boolean did(String request) {
            return requests.contains(request);
        }
    }

    static final class Engine implements RelayMedia.Engine {
        RelayMedia.Listener listener;
        RelayMedia.Snapshot snapshot = RelayMedia.Snapshot.EMPTY;
        final List<String> requests = new ArrayList<>();
        Throwable connectError;
        /** Failures to return for the next microphone / camera requests (null = success). */
        final Deque<Throwable> microphoneFailures = new ArrayDeque<>();
        final Deque<Throwable> cameraFailures = new ArrayDeque<>();
        /** Hold answers instead of answering at once. */
        boolean holdMicrophone;
        final List<RelayMedia.Result> heldMicrophone = new ArrayList<>();
        boolean microphoneOn;
        boolean cameraOn;
        boolean connected;
        int disconnects;

        @Override
        public RelayMedia.Snapshot snapshot() {
            return snapshot;
        }

        @Override
        public void setListener(RelayMedia.Listener listener) {
            this.listener = listener;
        }

        @Override
        public void connect(String url, String token, RelayMedia.Result result) {
            requests.add("connect " + url);
            if (connectError != null) {
                result.done(connectError);
                return;
            }
            connected = true;
            snapshot = new RelayMedia.Snapshot(RelayMedia.ConnectionState.CONNECTED, list(device(ME, DEVICE, true, false, false)));
            result.done(null);
        }

        @Override
        public void disconnect() {
            requests.add("disconnect");
            disconnects++;
            connected = false;
        }

        @Override
        public void setMicrophone(boolean enabled, RelayMedia.Result result) {
            requests.add("microphone " + (enabled ? "on" : "off"));
            if (holdMicrophone) {
                heldMicrophone.add(result);
                return;
            }
            Throwable failure = microphoneFailures.poll();
            if (failure == null) {
                microphoneOn = enabled;
            }
            result.done(failure);
        }

        @Override
        public void setCamera(boolean enabled, RelayMedia.Result result) {
            requests.add("camera " + (enabled ? "on" : "off"));
            Throwable failure = cameraFailures.poll();
            if (failure == null) {
                cameraOn = enabled;
            }
            result.done(failure);
        }

        @Override
        public void setScreenShare(boolean enabled, RelayMedia.Result result) {
            requests.add("screen " + (enabled ? "on" : "off"));
            result.done(null);
        }

        void change(RelayMedia.ConnectionState state, RelayMedia.Participant... participants) {
            snapshot = new RelayMedia.Snapshot(state, list(participants));
            listener.onChange(snapshot);
        }

        /** The local device plus [remote] ones. */
        void remote(RelayMedia.Participant... remote) {
            List<RelayMedia.Participant> all = new ArrayList<>();
            all.add(device(ME, DEVICE, true, microphoneOn, cameraOn));
            for (RelayMedia.Participant p : remote) {
                all.add(p);
            }
            snapshot = new RelayMedia.Snapshot(RelayMedia.ConnectionState.CONNECTED, all);
            listener.onChange(snapshot);
        }

        int count(String request) {
            int n = 0;
            for (String r : requests) {
                if (r.equals(request)) {
                    n++;
                }
            }
            return n;
        }
    }

    static List<RelayMedia.Participant> list(RelayMedia.Participant... participants) {
        List<RelayMedia.Participant> list = new ArrayList<>();
        for (RelayMedia.Participant p : participants) {
            list.add(p);
        }
        return list;
    }

    private Fakes() {
    }
}
