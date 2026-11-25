package org.telegram.relay.mattermost.calls;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The media side of a call, without LiveKit (port of the iOS {@code RelayMediaEngine.swift}): the controllers
 * ({@link RelayDirectCall}, {@link RelayGroupCall}) only see {@link Engine}; the app implements it over a LiveKit
 * room, tests over a fake. Every method runs on the controllers' thread; results and changes are delivered there.
 */
public final class RelayMedia {
    private RelayMedia() {
    }

    public enum ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

    public enum DisconnectReason { REMOVED, DUPLICATE_IDENTITY, FAILED }

    public static final class Track {
        public final String sid;
        public final boolean muted;
        public final int width;
        public final int height;

        public Track(String sid, boolean muted, int width, int height) {
            this.sid = sid;
            this.muted = muted;
            this.width = width;
            this.height = height;
        }

        public boolean isLive() {
            return !muted;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Track)) {
                return false;
            }
            Track t = (Track) o;
            return sid.equals(t.sid) && muted == t.muted && width == t.width && height == t.height;
        }

        @Override
        public int hashCode() {
            return sid.hashCode();
        }

        @Override
        public String toString() {
            return sid + (muted ? " muted" : "");
        }
    }

    public static final class Participant {
        /** {@code <user id>:<device id>} (plugin tokens). */
        public final String identity;
        public final String name;
        public final boolean isLocal;
        public final boolean isSpeaking;
        public final float audioLevel;
        /** 0 unknown … 4 excellent. */
        public final int connectionQuality;
        public final Track microphone;
        public final Track camera;
        public final Track screenShare;
        /** Unix ms, 0 when unknown. */
        public final long joinedAt;

        public Participant(String identity, String name, boolean isLocal, boolean isSpeaking, float audioLevel, int connectionQuality, Track microphone, Track camera, Track screenShare, long joinedAt) {
            this.identity = identity;
            this.name = name;
            this.isLocal = isLocal;
            this.isSpeaking = isSpeaking;
            this.audioLevel = audioLevel;
            this.connectionQuality = connectionQuality;
            this.microphone = microphone;
            this.camera = camera;
            this.screenShare = screenShare;
            this.joinedAt = joinedAt;
        }

        /** The Mattermost user of a plugin identity ({@code <user>:<device>}); null for others. */
        public String userId() {
            int colon = identity.indexOf(':');
            return colon > 0 ? identity.substring(0, colon) : null;
        }

        public boolean isMicrophoneEnabled() {
            return microphone != null && microphone.isLive();
        }

        public boolean isCameraEnabled() {
            return camera != null && camera.isLive();
        }

        public boolean isScreenSharing() {
            return screenShare != null && screenShare.isLive();
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Participant)) {
                return false;
            }
            Participant p = (Participant) o;
            return identity.equals(p.identity) && Objects.equals(name, p.name) && isLocal == p.isLocal && isSpeaking == p.isSpeaking
                    && audioLevel == p.audioLevel && connectionQuality == p.connectionQuality && Objects.equals(microphone, p.microphone)
                    && Objects.equals(camera, p.camera) && Objects.equals(screenShare, p.screenShare) && joinedAt == p.joinedAt;
        }

        @Override
        public int hashCode() {
            return identity.hashCode();
        }

        @Override
        public String toString() {
            return identity + (isLocal ? " (local)" : "") + " mic=" + microphone + " cam=" + camera + " screen=" + screenShare;
        }
    }

    public static final class Snapshot {
        public static final Snapshot EMPTY = new Snapshot(ConnectionState.DISCONNECTED, Collections.<Participant>emptyList());

        public final ConnectionState connectionState;
        /** Local first, then remote ones by join time. */
        public final List<Participant> participants;

        public Snapshot(ConnectionState connectionState, List<Participant> participants) {
            this.connectionState = connectionState;
            this.participants = Collections.unmodifiableList(new ArrayList<>(participants));
        }

        public Participant local() {
            for (Participant p : participants) {
                if (p.isLocal) {
                    return p;
                }
            }
            return null;
        }

        public List<Participant> remote() {
            List<Participant> list = new ArrayList<>();
            for (Participant p : participants) {
                if (!p.isLocal) {
                    list.add(p);
                }
            }
            return list;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Snapshot && ((Snapshot) o).connectionState == connectionState && ((Snapshot) o).participants.equals(participants);
        }

        @Override
        public int hashCode() {
            return connectionState.hashCode();
        }

        @Override
        public String toString() {
            return connectionState + " " + participants;
        }
    }

    /** Async results. [error] is null on success. */
    public interface Result {
        void done(Throwable error);
    }

    public interface Listener {
        void onChange(Snapshot snapshot);

        /** Only for disconnects that were not asked for. */
        void onDisconnect(DisconnectReason reason, String message);
    }

    public interface Engine {
        Snapshot snapshot();

        void setListener(Listener listener);

        void connect(String url, String token, Result result);

        void disconnect();

        void setMicrophone(boolean enabled, Result result);

        void setCamera(boolean enabled, Result result);

        void setScreenShare(boolean enabled, Result result);
    }
}
