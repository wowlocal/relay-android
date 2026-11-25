package org.telegram.relay.calls;

import android.content.Context;
import android.content.Intent;

import org.telegram.relay.livekit.RelayRoom;
import org.telegram.relay.mattermost.calls.RelayMedia;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link RelayMedia.Engine} over one LiveKit room ({@link RelayRoom}, module relay-livekit): the port of the iOS
 * {@code RelayRoomEngine} the call controllers drive. Main thread only. Screen sharing needs the
 * MediaProjection permission result first ({@link #setScreenCapturePermission}).
 */
public final class RelayLiveKitEngine implements RelayMedia.Engine, RelayRoom.Listener {
    private final RelayRoom room;
    private RelayMedia.Listener listener;
    private RelayMedia.Snapshot snapshot = RelayMedia.Snapshot.EMPTY;
    private Intent screenPermission;
    private final List<Runnable> snapshotObservers = new ArrayList<>();

    public RelayLiveKitEngine(Context context) {
        room = new RelayRoom(context, this);
    }

    public RelayRoom room() {
        return room;
    }

    public void setScreenCapturePermission(Intent data) {
        screenPermission = data;
    }

    /** Called after every snapshot (video routing re-attaches sinks then). */
    public void addSnapshotObserver(Runnable r) {
        snapshotObservers.add(r);
    }

    public void removeSnapshotObserver(Runnable r) {
        snapshotObservers.remove(r);
    }

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
        room.connect(url, token, result::done);
    }

    @Override
    public void disconnect() {
        room.disconnect();
    }

    public void release() {
        room.release();
        snapshotObservers.clear();
    }

    @Override
    public void setMicrophone(boolean enabled, RelayMedia.Result result) {
        room.setMicrophoneEnabled(enabled, result::done);
    }

    @Override
    public void setCamera(boolean enabled, RelayMedia.Result result) {
        room.setCameraEnabled(enabled, result::done);
    }

    @Override
    public void setScreenShare(boolean enabled, RelayMedia.Result result) {
        room.setScreenShareEnabled(enabled, enabled ? screenPermission : null, result::done);
    }

    // RelayRoom.Listener

    @Override
    public void onSnapshot(RelayRoom.Snapshot s) {
        snapshot = convert(s);
        if (listener != null) {
            listener.onChange(snapshot);
        }
        for (Runnable r : new ArrayList<>(snapshotObservers)) {
            r.run();
        }
    }

    @Override
    public void onDisconnected(int reason, String message) {
        if (listener == null) {
            return;
        }
        RelayMedia.DisconnectReason r = reason == RelayRoom.REASON_DUPLICATE_IDENTITY ? RelayMedia.DisconnectReason.DUPLICATE_IDENTITY
                : reason == RelayRoom.REASON_REMOVED ? RelayMedia.DisconnectReason.REMOVED : RelayMedia.DisconnectReason.FAILED;
        listener.onDisconnect(r, message);
    }

    static RelayMedia.Snapshot convert(RelayRoom.Snapshot s) {
        RelayMedia.ConnectionState state;
        switch (s.connectionState) {
            case RelayRoom.CONNECTED: state = RelayMedia.ConnectionState.CONNECTED; break;
            case RelayRoom.CONNECTING: state = RelayMedia.ConnectionState.CONNECTING; break;
            case RelayRoom.RECONNECTING: state = RelayMedia.ConnectionState.RECONNECTING; break;
            default: state = RelayMedia.ConnectionState.DISCONNECTED; break;
        }
        List<RelayMedia.Participant> list = new ArrayList<>();
        for (RelayRoom.ParticipantInfo p : s.participants) {
            list.add(new RelayMedia.Participant(p.identity, p.name, p.isLocal, p.isSpeaking, p.audioLevel, p.connectionQuality,
                    track(p.microphone), track(p.camera), track(p.screenShare), p.joinedAt));
        }
        return new RelayMedia.Snapshot(state, list);
    }

    private static RelayMedia.Track track(RelayRoom.TrackInfo t) {
        return t == null ? null : new RelayMedia.Track(t.sid, t.muted, t.width, t.height);
    }
}
