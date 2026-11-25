package org.telegram.relay.mattermost.calls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.telegram.relay.mattermost.calls.Fakes.DEVICE;
import static org.telegram.relay.mattermost.calls.Fakes.ME;
import static org.telegram.relay.mattermost.calls.Fakes.PEER;
import static org.telegram.relay.mattermost.calls.Fakes.device;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

/** Port of the iOS core's RelayGroupCallTests. */
public class RelayGroupCallTest {
    static final String CHANNEL = "ch000000000000000000000000";
    static final String THIRD = "third00000000000000000000t";

    private Fakes.Scheduler scheduler;
    private Fakes.Api api;
    private Fakes.Engine engine;

    @Before
    public void setUp() {
        scheduler = new Fakes.Scheduler();
        api = new Fakes.Api();
        engine = new Fakes.Engine();
    }

    private RelayCall channelCall(String id) {
        RelayCall c = Fakes.call(id, RelayCall.Kind.CHANNEL, RelayCall.State.ACTIVE, PEER);
        c.channelId = CHANNEL;
        return c;
    }

    @Test
    public void devicesMergePerUserWithTheOwnUserFirst() {
        List<RelayGroupCall.Participant> merged = RelayGroupCall.mergeParticipants(Fakes.list(
                device(PEER, "a", false, true, false),
                device(ME, "x", false, false, false),
                device(PEER, "b", false, false, true),
                device(ME, DEVICE, true, true, false)), ME);
        assertEquals(2, merged.size());
        assertEquals(ME, merged.get(0).userId);
        assertTrue(merged.get(0).isSelf);
        assertTrue(merged.get(0).devices.get(0).isLocal);
        assertEquals(PEER, merged.get(1).userId);
        assertTrue(merged.get(1).isMicrophoneEnabled);
        assertNotNull(merged.get(1).camera);
        assertEquals(PEER + ":b", merged.get(1).camera.identity);
    }

    @Test
    public void liveTrackWinsOverAMutedOne() {
        RelayMedia.Participant muted = new RelayMedia.Participant(PEER + ":a", "p", false, false, 0, 0, null, new RelayMedia.Track("CAM_A", true, 0, 0), null, 1);
        RelayMedia.Participant live = new RelayMedia.Participant(PEER + ":b", "p", false, false, 0, 0, null, new RelayMedia.Track("CAM_B", false, 0, 0), null, 2);
        List<RelayGroupCall.Participant> merged = RelayGroupCall.mergeParticipants(Fakes.list(muted, live), ME);
        assertEquals("CAM_B", merged.get(0).camera.track.sid);
    }

    @Test
    public void identitiesWithoutAUserStandAlone() {
        RelayMedia.Participant probe = new RelayMedia.Participant("probe", null, false, false, 0, 0, null, null, null, 0);
        List<RelayGroupCall.Participant> merged = RelayGroupCall.mergeParticipants(Fakes.list(probe), ME);
        assertEquals("probe", merged.get(0).userId);
    }

    @Test
    public void joinConnectsAndTracksParticipants() {
        api.joinResult = new RelayCallJoin("ws://127.0.0.1:7880", "t", channelCall("g1"));
        RelayGroupCall call = RelayGroupCall.join(api, engine, scheduler, ME, DEVICE, CHANNEL, "g1", RelayCall.Kind.CHANNEL);
        call.start();
        scheduler.run();
        assertTrue(api.did("join " + CHANNEL + " g1"));
        assertEquals(RelayGroupCall.State.CONNECTED, call.state());
        assertEquals(1, engine.count("microphone on"));
        engine.remote(device(PEER, "a", false, true, false), device(THIRD, "c", false, false, false));
        scheduler.run();
        assertEquals(3, call.participants().size());
    }

    @Test
    public void muteCameraAndScreenShare() {
        api.joinResult = new RelayCallJoin("ws://127.0.0.1:7880", "t", channelCall("g1"));
        RelayGroupCall call = RelayGroupCall.join(api, engine, scheduler, ME, DEVICE, CHANNEL, null, null);
        call.start();
        scheduler.run();
        call.setMuted(true);
        call.setCameraEnabled(true);
        call.setScreenShare(true);
        scheduler.run();
        assertEquals(1, engine.count("microphone off"));
        assertEquals(1, engine.count("camera on"));
        assertEquals(1, engine.count("screen on"));
        assertTrue(call.snapshot().isMuted);
    }

    @Test
    public void leaveHangsUpAndEndForEveryoneEnds() {
        api.joinResult = new RelayCallJoin("ws://127.0.0.1:7880", "t", channelCall("g1"));
        RelayGroupCall call = RelayGroupCall.join(api, engine, scheduler, ME, DEVICE, CHANNEL, null, null);
        call.start();
        scheduler.run();
        call.leave();
        scheduler.run();
        assertEquals(RelayGroupCall.EndReason.LEFT, call.snapshot().endReason);
        assertTrue(api.did("hangup g1"));

        RelayGroupCall second = RelayGroupCall.join(api, engine, scheduler, ME, DEVICE, CHANNEL, null, null);
        second.start();
        scheduler.run();
        Throwable[] result = {new Exception("pending")};
        second.endForEveryone(e -> result[0] = e);
        scheduler.run();
        assertNull(result[0]);
        assertTrue(api.did("end g1"));
        assertEquals(RelayGroupCall.EndReason.ENDED, second.snapshot().endReason);
    }

    @Test
    public void joinFailureAndRemoval() {
        api.joinError = new RelayCallsException(403, "not a member", null);
        RelayGroupCall call = RelayGroupCall.join(api, engine, scheduler, ME, DEVICE, CHANNEL, null, null);
        call.start();
        scheduler.run();
        assertEquals(RelayGroupCall.EndReason.FAILED, call.snapshot().endReason);

        api.joinError = null;
        api.joinResult = new RelayCallJoin("ws://127.0.0.1:7880", "t", channelCall("g1"));
        RelayGroupCall removed = RelayGroupCall.join(api, engine, scheduler, ME, DEVICE, CHANNEL, null, null);
        removed.start();
        scheduler.run();
        engine.listener.onDisconnect(RelayMedia.DisconnectReason.REMOVED, "kicked");
        scheduler.run();
        assertEquals(RelayGroupCall.EndReason.REMOVED, removed.snapshot().endReason);
    }

    @Test
    public void ringingAGroupThatAlreadyHasACallJoinsIt() {
        RelayCall running = channelCall("g2");
        running.kind = RelayCall.Kind.GROUP;
        api.ringError = new RelayCallsException(409, "in progress", running);
        api.joinResult = new RelayCallJoin("ws://127.0.0.1:7880", "t", running);
        RelayGroupCall call = RelayGroupCall.ring(api, engine, scheduler, ME, DEVICE, CHANNEL, false);
        call.start();
        scheduler.run();
        assertTrue(api.did("join " + CHANNEL + " g2"));
        assertEquals(RelayGroupCall.State.CONNECTED, call.state());
    }

    @Test
    public void incomingGroupRingAcceptedDeclinedExpired() {
        RelayCall ring = Fakes.ringing("g3", PEER, ME, RelayCall.Invitee.RINGING, scheduler.now + 45_000);
        ring.kind = RelayCall.Kind.GROUP;
        ring.channelId = CHANNEL;
        RelayGroupCall call = RelayGroupCall.incoming(api, engine, scheduler, ME, DEVICE, ring);
        call.start();
        scheduler.run();
        assertTrue(api.did("received g3"));
        assertEquals(RelayGroupCall.State.RINGING, call.state());
        api.acceptResult = new RelayCallJoin("ws://127.0.0.1:7880", "t", ring);
        call.accept();
        scheduler.run();
        assertEquals(RelayGroupCall.State.CONNECTED, call.state());

        RelayGroupCall declined = RelayGroupCall.incoming(api, engine, scheduler, ME, DEVICE, ring);
        declined.start();
        declined.decline("declined");
        assertEquals(RelayGroupCall.EndReason.DECLINED, declined.snapshot().endReason);

        RelayGroupCall expired = RelayGroupCall.incoming(api, engine, scheduler, ME, DEVICE, ring);
        expired.start();
        scheduler.advance(45_010);
        assertEquals(RelayGroupCall.EndReason.MISSED, expired.snapshot().endReason);
    }

    @Test
    public void incomingGroupRingAnsweredOnAnotherDevice() {
        RelayCall ring = Fakes.ringing("g3", PEER, ME, RelayCall.Invitee.RINGING, scheduler.now + 45_000);
        ring.kind = RelayCall.Kind.GROUP;
        RelayGroupCall call = RelayGroupCall.incoming(api, engine, scheduler, ME, DEVICE, ring);
        call.start();
        call.handle(RelayCallsEvent.callState(ring, ME, "other", RelayCallsEvent.Action.ACCEPTED));
        assertEquals(RelayGroupCall.EndReason.ANSWERED_ELSEWHERE, call.snapshot().endReason);
    }

    @Test
    public void acceptAfterTheRingStoppedJoinsTheRunningCall() {
        RelayCall ring = Fakes.ringing("g3", PEER, ME, RelayCall.Invitee.RINGING, scheduler.now + 45_000);
        ring.kind = RelayCall.Kind.GROUP;
        ring.channelId = CHANNEL;
        api.acceptError = new RelayCallsException(409, "not ringing", null);
        RelayCall active = ring.copy();
        active.state = RelayCall.State.ACTIVE;
        api.joinResult = new RelayCallJoin("ws://127.0.0.1:7880", "t", active);
        RelayGroupCall call = RelayGroupCall.incoming(api, engine, scheduler, ME, DEVICE, ring);
        call.start();
        call.accept();
        scheduler.run();
        assertTrue(api.did("join " + CHANNEL + " g3"));
        assertEquals(RelayGroupCall.State.CONNECTED, call.state());
        assertFalse(call.snapshot().isMuted);
    }

    @Test
    public void groupCallRetriesTheMicrophoneWithBackoff() {
        engine.microphoneFailures.add(new Exception("x"));
        engine.microphoneFailures.add(new Exception("x"));
        engine.microphoneFailures.add(new Exception("x"));
        api.joinResult = new RelayCallJoin("ws://127.0.0.1:7880", "t", channelCall("g1"));
        RelayGroupCall call = RelayGroupCall.join(api, engine, scheduler, ME, DEVICE, CHANNEL, null, null);
        call.start();
        scheduler.run();
        scheduler.advance(500);
        scheduler.advance(1000);
        scheduler.advance(2000);
        assertEquals(4, engine.count("microphone on"));
        assertTrue(engine.microphoneOn);
        assertFalse(call.snapshot().isMuted);
    }
}
