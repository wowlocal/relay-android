package org.telegram.relay.livekit

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.livekit.android.AudioOptions
import io.livekit.android.AudioType
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.RoomOptions
import io.livekit.android.audio.NoAudioHandler
import io.livekit.android.events.DisconnectReason
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.participant.VideoTrackPublishDefaults
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.room.track.RemoteTrackPublication
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.TrackPublication
import io.livekit.android.room.track.VideoCaptureParameter
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.room.track.screencapture.ScreenCaptureParams
import io.livekit.android.room.track.video.VideoSinkVisibility
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink
import java.nio.ByteBuffer

/**
 * One LiveKit room for one Relay call, with a Java-friendly API (callbacks instead of suspend functions and
 * flows). Port of the iOS `RelayRoomEngine` (submodules/RelayCalls): a snapshot of the room (connection state,
 * local + remote participants with their microphone/camera/screen publications, speaking, audio levels) is
 * published to [Listener.onSnapshot] on the main thread after every change; [Listener.onDisconnected] reports
 * disconnects that were not asked for.
 *
 * Every method must be called on the main thread. Results arrive on the main thread.
 *
 * Audio routing (speaker / earpiece / Bluetooth, audio focus, MODE_IN_COMMUNICATION) stays with the app
 * (Telegram's VoIPService): LiveKit runs with a [NoAudioHandler].
 *
 * Camera: the room owns one camera track. [setCameraEnabled] creates and starts it (a local preview works
 * before [connect]) and publishes it once connected; turning it off unpublishes and stops it. As on iOS, every
 * local video track is stopped before and after [disconnect] (a capturer must never outlive its capture), and a
 * stopped capturer is never restarted behind the track's back.
 */
class RelayRoom(context: Context, private val listener: Listener) {

    interface Listener {
        fun onSnapshot(snapshot: Snapshot)
        fun onDisconnected(reason: Int, message: String?)
    }

    fun interface Callback {
        /** [error] is null on success. */
        fun onResult(error: Throwable?)
    }

    /** Receives I420 frames (converted on WebRTC's thread). The sink must call [I420Frame.release] once. */
    fun interface FrameSink {
        fun onFrame(frame: I420Frame)
    }

    class I420Frame internal constructor(
        @JvmField val width: Int,
        @JvmField val height: Int,
        @JvmField val rotation: Int,
        @JvmField val timestampNs: Long,
        @JvmField val dataY: ByteBuffer,
        @JvmField val strideY: Int,
        @JvmField val dataU: ByteBuffer,
        @JvmField val strideU: Int,
        @JvmField val dataV: ByteBuffer,
        @JvmField val strideV: Int,
        private val buffer: VideoFrame.I420Buffer,
    ) {
        fun release() = buffer.release()
    }

    class TrackInfo(
        @JvmField val sid: String,
        @JvmField val muted: Boolean,
        @JvmField val width: Int,
        @JvmField val height: Int,
        @JvmField val subscribed: Boolean,
    ) {
        val isLive: Boolean get() = !muted
        override fun equals(other: Any?) = other is TrackInfo && other.sid == sid && other.muted == muted &&
            other.width == width && other.height == height && other.subscribed == subscribed
        override fun hashCode() = sid.hashCode()
        override fun toString() = "$sid${if (muted) " muted" else ""}${if (width > 0) " ${width}x$height" else ""}${if (subscribed) "" else " unsubscribed"}"
    }

    class ParticipantInfo(
        @JvmField val identity: String,
        @JvmField val name: String?,
        @JvmField val isLocal: Boolean,
        @JvmField val isSpeaking: Boolean,
        @JvmField val audioLevel: Float,
        /** 0 unknown, 1 lost, 2 poor, 3 good, 4 excellent. */
        @JvmField val connectionQuality: Int,
        @JvmField val microphone: TrackInfo?,
        @JvmField val camera: TrackInfo?,
        @JvmField val screenShare: TrackInfo?,
        /** Unix ms, 0 when unknown. */
        @JvmField val joinedAt: Long,
    ) {
        override fun equals(other: Any?) = other is ParticipantInfo && other.identity == identity && other.name == name &&
            other.isLocal == isLocal && other.isSpeaking == isSpeaking && other.audioLevel == audioLevel &&
            other.connectionQuality == connectionQuality && other.microphone == microphone && other.camera == camera &&
            other.screenShare == screenShare && other.joinedAt == joinedAt
        override fun hashCode() = identity.hashCode()
        override fun toString() = "$identity(${if (isLocal) "local " else ""}mic=$microphone cam=$camera screen=$screenShare${if (isSpeaking) " speaking" else ""})"
    }

    class Snapshot(
        /** One of [DISCONNECTED], [CONNECTING], [CONNECTED], [RECONNECTING]. */
        @JvmField val connectionState: Int,
        /** Local first, then remote ones by join time. */
        @JvmField val participants: List<ParticipantInfo>,
    ) {
        val local: ParticipantInfo? get() = participants.firstOrNull { it.isLocal }
        val remote: List<ParticipantInfo> get() = participants.filter { !it.isLocal }
        override fun equals(other: Any?) = other is Snapshot && other.connectionState == connectionState && other.participants == participants
        override fun hashCode() = connectionState
        override fun toString() = "state=$connectionState $participants"
    }

    companion object {
        const val DISCONNECTED = 0
        const val CONNECTING = 1
        const val CONNECTED = 2
        const val RECONNECTING = 3

        /** [Listener.onDisconnected] reasons. */
        const val REASON_REMOVED = 1
        const val REASON_DUPLICATE_IDENTITY = 2
        const val REASON_FAILED = 3

        const val SOURCE_CAMERA = 1
        const val SOURCE_SCREEN = 2

        private const val TAG = "RelayLiveKit"

        @JvmStatic
        var logger: ((String) -> Unit)? = null

        internal fun log(text: String) {
            Log.i(TAG, text)
            logger?.invoke(text)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val room: Room = LiveKit.create(
        appContext = context.applicationContext,
        options = RoomOptions(
            adaptiveStream = false,
            dynacast = true,
            videoTrackCaptureDefaults = LocalVideoTrackOptions(
                position = CameraPosition.FRONT,
                captureParams = VideoCaptureParameter(1280, 720, 30),
            ),
            videoTrackPublishDefaults = VideoTrackPublishDefaults(simulcast = true),
        ),
        overrides = LiveKitOverrides(
            audioOptions = AudioOptions(
                audioOutputType = AudioType.CallAudioType(),
                audioHandler = NoAudioHandler(),
            ),
        ),
    )
    private var eventsJob: Job? = null
    private var isDisconnecting = false
    private var released = false
    private var updatePosted = false

    @Volatile
    var snapshot: Snapshot = Snapshot(DISCONNECTED, emptyList())
        private set

    // Camera: our own track (LiveKit's setCameraEnabled cannot preview before connecting).
    private var cameraTrack: LocalVideoTrack? = null
    private var cameraFront = true
    private var cameraWanted = false
    private val previewSinks = HashMap<Int, SinkAdapter>()

    // Remote video: sink → (track it is attached to).
    private val remoteSinks = HashMap<FrameSink, Pair<VideoTrack, SinkAdapter>>()
    private val userVolumes = HashMap<String, Double>()

    init {
        eventsJob = scope.launch {
            room.events.collect { event -> handle(event) }
        }
    }

    // region Connection

    fun connect(url: String, token: String, callback: Callback) {
        isDisconnecting = false
        publishSnapshot(CONNECTING)
        scope.launch {
            try {
                room.connect(url, token, ConnectOptions(autoSubscribe = true))
                log("connected to ${room.name} as ${room.localParticipant.identity?.value}")
                if (cameraWanted) {
                    publishCamera()
                }
                publishSnapshot()
                callback.onResult(null)
            } catch (e: Throwable) {
                log("connect failed: $e")
                publishSnapshot()
                callback.onResult(e)
            }
        }
    }

    fun disconnect() {
        isDisconnecting = true
        val tracks = localVideoTracks()
        stopTracks(tracks)
        room.disconnect()
        stopTracks(tracks + localVideoTracks())
        cameraTrack = null
        publishSnapshot()
    }

    /** Disconnects (when needed) and releases the room; the object is unusable afterwards. */
    fun release() {
        if (released) {
            return
        }
        disconnect()
        released = true
        synchronized(remoteSinks) {
            remoteSinks.clear()
        }
        previewSinks.clear()
        eventsJob?.cancel()
        try {
            room.release()
        } catch (e: Throwable) {
            log("release failed: $e")
        }
        scope.cancel()
    }

    // endregion

    // region Microphone

    fun setMicrophoneEnabled(enabled: Boolean, callback: Callback) {
        scope.launch {
            try {
                val ok = room.localParticipant.setMicrophoneEnabled(enabled)
                publishSnapshot()
                callback.onResult(if (ok) null else IllegalStateException("microphone ${if (enabled) "on" else "off"} was refused"))
            } catch (e: Throwable) {
                publishSnapshot()
                callback.onResult(e)
            }
        }
    }

    // endregion

    // region Camera

    /**
     * Starts or stops capturing the camera without publishing it (Telegram's camera preview before "Share Video").
     * Stopping also unpublishes it.
     */
    fun setCameraCapture(enabled: Boolean): Throwable? {
        if (!enabled) {
            cameraWanted = false
            val track = cameraTrack
            cameraTrack = null
            if (track != null) {
                room.localParticipant.unpublishTrack(track, true)
                stopTracks(listOf(track))
                track.dispose()
            }
            publishSnapshot()
            return null
        }
        return try {
            if (cameraTrack == null) {
                val track = room.localParticipant.createVideoTrack(
                    name = "camera",
                    options = room.videoTrackCaptureDefaults.copy(position = if (cameraFront) CameraPosition.FRONT else CameraPosition.BACK),
                )
                track.startCapture()
                cameraTrack = track
                previewSinks[SOURCE_CAMERA]?.let { track.addRenderer(it) }
            }
            null
        } catch (e: Throwable) {
            log("camera start failed: $e")
            e
        }
    }

    /** Captures and publishes the camera (published once connected), or unpublishes and stops it. */
    fun setCameraEnabled(enabled: Boolean, callback: Callback) {
        if (!enabled) {
            setCameraCapture(false)
            callback.onResult(null)
            return
        }
        val failure = setCameraCapture(true)
        if (failure != null) {
            callback.onResult(failure)
            return
        }
        cameraWanted = true
        if (room.state != Room.State.CONNECTED) {
            callback.onResult(null)
            return
        }
        scope.launch {
            try {
                publishCamera()
                publishSnapshot()
                callback.onResult(null)
            } catch (e: Throwable) {
                callback.onResult(e)
            }
        }
    }

    private suspend fun publishCamera() {
        val track = cameraTrack ?: return
        if (room.localParticipant.trackPublications.values.any { it.track == track }) {
            return
        }
        val ok = room.localParticipant.publishVideoTrack(track)
        if (!ok) {
            throw IllegalStateException("camera publish failed")
        }
        log("camera published")
    }

    /** Front (true) or back camera; applies to the running camera at once. */
    fun setCameraFront(front: Boolean) {
        cameraFront = front
        cameraTrack?.switchCamera(position = if (front) CameraPosition.FRONT else CameraPosition.BACK)
    }

    val isCameraFront: Boolean get() = cameraFront
    val isCameraOn: Boolean get() = cameraTrack != null

    // endregion

    // region Screen sharing

    /** [permissionData]: the result data of MediaProjectionManager.createScreenCaptureIntent (needed to turn it on). */
    fun setScreenShareEnabled(enabled: Boolean, permissionData: Intent?, callback: Callback) {
        scope.launch {
            try {
                val ok = if (enabled) {
                    val data = permissionData ?: throw IllegalArgumentException("no screen capture permission")
                    room.localParticipant.setScreenShareEnabled(true, ScreenCaptureParams(data, onStop = {
                        main.post { publishSnapshot() }
                    }))
                } else {
                    room.localParticipant.setScreenShareEnabled(false)
                }
                val publication = room.localParticipant.getTrackPublication(Track.Source.SCREEN_SHARE)
                val track = publication?.track as? LocalVideoTrack
                previewSinks[SOURCE_SCREEN]?.let { sink -> track?.addRenderer(sink) }
                publishSnapshot()
                callback.onResult(if (ok) null else IllegalStateException("screen share ${if (enabled) "on" else "off"} failed"))
            } catch (e: Throwable) {
                publishSnapshot()
                callback.onResult(e)
            }
        }
    }

    // endregion

    // region Video sinks

    /** The local preview of [source] ([SOURCE_CAMERA] / [SOURCE_SCREEN]); null removes it. */
    fun setLocalPreviewSink(source: Int, sink: FrameSink?) {
        val old = previewSinks.remove(source)
        val track = if (source == SOURCE_CAMERA) cameraTrack else
            room.localParticipant.getTrackPublication(Track.Source.SCREEN_SHARE)?.track as? LocalVideoTrack
        if (old != null) {
            track?.removeRenderer(old)
        }
        if (sink != null) {
            val adapter = SinkAdapter(sink)
            previewSinks[source] = adapter
            track?.addRenderer(adapter)
        }
    }

    /**
     * Attaches [sink] to the video of a remote participant ([identity], [source]). Returns false until that track
     * is subscribed: call it again on the next snapshot. A sink attached elsewhere is moved.
     */
    fun attachRemoteSink(identity: String, source: Int, sink: FrameSink): Boolean {
        val participant = room.remoteParticipants.values.firstOrNull { it.identity?.value == identity } ?: return false
        val trackSource = if (source == SOURCE_SCREEN) Track.Source.SCREEN_SHARE else Track.Source.CAMERA
        val track = participant.getTrackPublication(trackSource)?.track as? VideoTrack ?: return false
        synchronized(remoteSinks) {
            val current = remoteSinks[sink]
            if (current != null && current.first === track) {
                return true
            }
            if (current != null) {
                current.first.removeRenderer(current.second)
            }
            val adapter = SinkAdapter(sink)
            remoteSinks[sink] = track to adapter
            if (track is io.livekit.android.room.track.RemoteVideoTrack) {
                track.addRenderer(adapter, AlwaysVisible)
            } else {
                track.addRenderer(adapter)
            }
        }
        return true
    }

    fun detachRemoteSink(sink: FrameSink) {
        synchronized(remoteSinks) {
            val current = remoteSinks.remove(sink) ?: return
            current.first.removeRenderer(current.second)
        }
    }

    private object AlwaysVisible : VideoSinkVisibility() {
        override fun isVisible() = true
        override fun size() = Track.Dimensions(1280, 720)
    }

    private class SinkAdapter(val sink: FrameSink) : VideoSink {
        override fun onFrame(frame: VideoFrame) {
            val i420 = try {
                frame.buffer.toI420()
            } catch (e: Throwable) {
                null
            } ?: return
            sink.onFrame(
                I420Frame(
                    i420.width, i420.height, frame.rotation, frame.timestampNs,
                    i420.dataY, i420.strideY, i420.dataU, i420.strideU, i420.dataV, i420.strideV, i420,
                ),
            )
        }
    }

    // endregion

    // region Audio

    /** This device's playback volume (0…1, 1 = normal) of every track of a Mattermost user. */
    fun setVolume(userId: String, volume: Double) {
        if (volume == 1.0) userVolumes.remove(userId) else userVolumes[userId] = volume
        applyVolumes()
    }

    private fun applyVolumes() {
        for (participant in room.remoteParticipants.values) {
            val userId = participant.identity?.value?.substringBefore(':') ?: continue
            val volume = userVolumes[userId] ?: 1.0
            for ((publication, _) in participant.audioTrackPublications) {
                (publication.track as? RemoteAudioTrack)?.setVolume(volume)
            }
        }
    }

    // endregion

    // region Snapshot

    private fun handle(event: RoomEvent) {
        when (event) {
            is RoomEvent.Disconnected -> roomDisconnected(event.reason, event.error)
            is RoomEvent.TrackSubscribed -> {
                applyVolumes()
                scheduleUpdate()
            }
            else -> scheduleUpdate()
        }
    }

    private fun scheduleUpdate() {
        if (updatePosted) {
            return
        }
        updatePosted = true
        main.post {
            updatePosted = false
            if (!released) {
                publishSnapshot()
            }
        }
    }

    private fun publishSnapshot(state: Int? = null) {
        val next = Snapshot(state ?: map(room.state), currentParticipants())
        if (next == snapshot) {
            return
        }
        snapshot = next
        listener.onSnapshot(next)
    }

    private fun currentParticipants(): List<ParticipantInfo> {
        if (room.state == Room.State.DISCONNECTED) {
            return emptyList()
        }
        val result = ArrayList<ParticipantInfo>()
        if (room.localParticipant.identity != null) {
            result.add(participant(room.localParticipant, true))
        }
        val remote = room.remoteParticipants.values.sortedWith(compareBy<RemoteParticipant>({ it.joinedAt ?: Long.MAX_VALUE }, { it.identity?.value ?: "" }))
        for (p in remote) {
            result.add(participant(p, false))
        }
        return result
    }

    private fun participant(p: Participant, isLocal: Boolean): ParticipantInfo {
        fun track(source: Track.Source): TrackInfo? {
            val publication: TrackPublication = p.getTrackPublication(source) ?: return null
            val subscribed = if (publication is RemoteTrackPublication) publication.subscribed && publication.track != null else true
            return TrackInfo(publication.sid, publication.muted, publication.dimensions?.width ?: 0, publication.dimensions?.height ?: 0, subscribed)
        }
        return ParticipantInfo(
            identity = p.identity?.value ?: "",
            name = p.name,
            isLocal = isLocal,
            isSpeaking = p.isSpeaking,
            audioLevel = p.audioLevel,
            connectionQuality = p.connectionQuality.ordinal,
            microphone = track(Track.Source.MICROPHONE),
            camera = track(Track.Source.CAMERA),
            screenShare = track(Track.Source.SCREEN_SHARE),
            joinedAt = p.joinedAt ?: 0L,
        )
    }

    private fun map(state: Room.State) = when (state) {
        Room.State.CONNECTED -> CONNECTED
        Room.State.CONNECTING -> CONNECTING
        Room.State.RECONNECTING -> RECONNECTING
        Room.State.DISCONNECTED -> DISCONNECTED
    }

    private fun roomDisconnected(reason: DisconnectReason, error: Exception?) {
        log("disconnected: $reason ${error ?: ""}")
        publishSnapshot()
        if (isDisconnecting) {
            return
        }
        isDisconnecting = true
        stopTracks(localVideoTracks())
        cameraTrack = null
        val code = when (reason) {
            DisconnectReason.DUPLICATE_IDENTITY -> REASON_DUPLICATE_IDENTITY
            DisconnectReason.ROOM_DELETED, DisconnectReason.PARTICIPANT_REMOVED, DisconnectReason.SERVER_SHUTDOWN,
            DisconnectReason.ROOM_CLOSED, DisconnectReason.CLIENT_INITIATED -> REASON_REMOVED
            else -> REASON_FAILED
        }
        listener.onDisconnected(code, error?.message ?: reason.name)
    }

    // endregion

    private fun localVideoTracks(): List<LocalVideoTrack> {
        val tracks = room.localParticipant.trackPublications.values.mapNotNull { it.track as? LocalVideoTrack }.toMutableList()
        cameraTrack?.let { if (it !in tracks) tracks.add(it) }
        return tracks
    }

    private fun stopTracks(tracks: List<LocalVideoTrack>) {
        for (track in tracks) {
            try {
                track.stopCapture()
            } catch (e: Throwable) {
                log("stopping a local video track failed: $e")
            }
        }
    }
}
