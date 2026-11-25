package org.telegram.relay.mattermost.calls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.telegram.relay.mattermost.calls.Fakes.DEVICE;
import static org.telegram.relay.mattermost.calls.Fakes.DM;
import static org.telegram.relay.mattermost.calls.Fakes.ME;
import static org.telegram.relay.mattermost.calls.Fakes.PEER;
import static org.telegram.relay.mattermost.calls.Fakes.device;

import org.junit.Before;
import org.junit.Test;

/** Port of the iOS core's RelayDirectCallTests (the transitions both clients rely on). */
public class RelayDirectCallTest {
    private Fakes.Scheduler scheduler;
    private Fakes.Api api;
    private Fakes.Engine engine;

    @Before
    public void setUp() {
        scheduler = new Fakes.Scheduler();
        api = new Fakes.Api();
        engine = new Fakes.Engine();
    }

    private RelayDirectCall outgoing(boolean video) {
        RelayCall ringing = Fakes.ringing("c1", ME, PEER, RelayCall.Invitee.RINGING, scheduler.now + 45_000);
        ringing.video = video;
        api.ringResult = new RelayCallJoin("ws://127.0.0.1:7880", "token", ringing);
        RelayDirectCall call = RelayDirectCall.outgoing(api, engine, scheduler, ME, DEVICE, DM, PEER, video);
        call.start();
        scheduler.run();
        return call;
    }

    private RelayDirectCall incoming() {
        RelayCall ringing = Fakes.ringing("c2", PEER, ME, RelayCall.Invitee.RINGING, scheduler.now + 45_000);
        RelayDirectCall call = RelayDirectCall.incoming(api, engine, scheduler, ME, DEVICE, ringing);
        call.start();
        scheduler.run();
        return call;
    }

    private static RelayCallsEvent state(RelayCall call, String user, String device, RelayCallsEvent.Action action) {
        return RelayCallsEvent.callState(call, user, device, action);
    }

    @Test
    public void outgoingRingsConnectsEarlyRingsBackAndBecomesActive() {
        RelayDirectCall call = outgoing(false);
        assertTrue(api.did("ring " + DM));
        assertTrue("media connects while ringing", engine.connected);
        assertEquals(RelayDirectCall.State.REQUESTING, call.state());
        assertFalse(call.snapshot().calleeRinging);
        assertEquals("no microphone before the answer", 0, engine.count("microphone on"));

        RelayCall received = call.call().copy();
        received.invitees.put(PEER, RelayCall.Invitee.RECEIVED);
        call.handle(state(received, PEER, "pd", RelayCallsEvent.Action.RECEIVED));
        assertTrue(call.snapshot().calleeRinging);

        RelayCall accepted = received.copy();
        accepted.state = RelayCall.State.ACTIVE;
        accepted.invitees.put(PEER, RelayCall.Invitee.ACCEPTED);
        call.handle(state(accepted, PEER, "pd", RelayCallsEvent.Action.ACCEPTED));
        scheduler.run();
        assertEquals(RelayDirectCall.State.CONNECTING, call.state());
        assertEquals("microphone after the answer", 1, engine.count("microphone on"));

        engine.remote(device(PEER, "pd", false, true, false));
        scheduler.run();
        assertEquals(RelayDirectCall.State.ACTIVE, call.state());
    }

    @Test
    public void outgoingCalleeJoiningBeforeAcceptedEventIsActive() {
        RelayDirectCall call = outgoing(false);
        engine.remote(device(PEER, "pd", false, true, false));
        scheduler.run();
        assertEquals(RelayDirectCall.State.ACTIVE, call.state());
        assertEquals(1, engine.count("microphone on"));
    }

    @Test
    public void outgoingDeclinedByCallee() {
        RelayDirectCall call = outgoing(false);
        call.handle(state(call.call(), PEER, "pd", RelayCallsEvent.Action.DECLINED));
        assertEquals(RelayDirectCall.State.TERMINATED, call.state());
        assertEquals(RelayDirectCall.EndReason.DECLINED, call.snapshot().endReason);
        assertTrue(engine.disconnects > 0);
    }

    @Test
    public void outgoingBusyRingResponseDoesNotConnect() {
        RelayCall busy = Fakes.ended(Fakes.ringing("c1", ME, PEER, RelayCall.Invitee.BUSY, 0), RelayCall.EndReason.BUSY, 0);
        api.ringResult = new RelayCallJoin("", "no-join", busy);
        RelayDirectCall call = RelayDirectCall.outgoing(api, engine, scheduler, ME, DEVICE, DM, PEER, false);
        call.start();
        scheduler.run();
        assertEquals(RelayDirectCall.EndReason.BUSY, call.snapshot().endReason);
        assertFalse(engine.connected);
    }

    @Test
    public void outgoingMissedFromServer() {
        RelayDirectCall call = outgoing(false);
        call.handle(RelayCallsEvent.ended(Fakes.ended(call.call(), RelayCall.EndReason.MISSED, 0)));
        assertEquals(RelayDirectCall.EndReason.MISSED, call.snapshot().endReason);
    }

    @Test
    public void outgoingRingExpiresLocallyAfterGrace() {
        RelayDirectCall call = outgoing(false);
        scheduler.advance(45_000 + 2_000);
        assertEquals(RelayDirectCall.State.REQUESTING, call.state());
        scheduler.advance(2_000);
        assertEquals(RelayDirectCall.EndReason.MISSED, call.snapshot().endReason);
        assertTrue(api.did("hangup c1"));
    }

    @Test
    public void outgoingHangupBeforeAnswerCancels() {
        RelayDirectCall call = outgoing(false);
        call.hangup();
        scheduler.run();
        assertEquals(RelayDirectCall.EndReason.HANGUP, call.snapshot().endReason);
        assertTrue(api.did("hangup c1"));
    }

    @Test
    public void ringFailureTerminates() {
        api.ringError = new RelayCallsException(500, "boom", null);
        RelayDirectCall call = RelayDirectCall.outgoing(api, engine, scheduler, ME, DEVICE, DM, PEER, false);
        call.start();
        scheduler.run();
        assertEquals(RelayDirectCall.EndReason.FAILED, call.snapshot().endReason);
    }

    @Test
    public void mediaConnectFailureTerminatesAndHangsUp() {
        engine.connectError = new Exception("no ice");
        RelayDirectCall call = outgoing(false);
        assertEquals(RelayDirectCall.EndReason.FAILED, call.snapshot().endReason);
        assertTrue(api.did("hangup c1"));
    }

    @Test
    public void remoteHangupUsesServerDuration() {
        RelayDirectCall call = outgoing(false);
        engine.remote(device(PEER, "pd", false, true, false));
        scheduler.run();
        scheduler.advance(10_000);
        call.handle(RelayCallsEvent.ended(Fakes.ended(call.call(), RelayCall.EndReason.HANGUP, 12_345)));
        assertEquals(RelayDirectCall.EndReason.REMOTE_HANGUP, call.snapshot().endReason);
        assertEquals(12.345, call.snapshot().duration, 0.001);
    }

    @Test
    public void localHangupOfActiveCallCountsDurationFromAnswer() {
        RelayDirectCall call = outgoing(false);
        scheduler.advance(5_000);
        engine.remote(device(PEER, "pd", false, true, false));
        scheduler.run();
        scheduler.advance(7_000);
        call.hangup();
        assertEquals(RelayDirectCall.EndReason.HANGUP, call.snapshot().endReason);
        assertEquals(7.0, call.snapshot().duration, 0.01);
    }

    @Test
    public void peerDeviceLeavingReconnectsThenFails() {
        RelayDirectCall call = outgoing(false);
        engine.remote(device(PEER, "pd", false, true, false));
        scheduler.run();
        engine.remote();
        scheduler.run();
        assertEquals(RelayDirectCall.State.RECONNECTING, call.state());
        scheduler.advance(RelayDirectCall.RECONNECT_TIMEOUT_MS + 10);
        assertEquals(RelayDirectCall.EndReason.FAILED, call.snapshot().endReason);
        assertTrue(api.did("hangup c1"));
    }

    @Test
    public void answeredCallThatNeverConnectsFails() {
        RelayDirectCall call = incoming();
        RelayCall accepted = call.call().copy();
        accepted.state = RelayCall.State.ACTIVE;
        api.acceptResult = new RelayCallJoin("ws://127.0.0.1:7880", "t", accepted);
        call.accept();
        scheduler.run();
        assertEquals(RelayDirectCall.State.CONNECTING, call.state());
        scheduler.advance(RelayDirectCall.CONNECT_TIMEOUT_MS + 10);
        assertEquals(RelayDirectCall.EndReason.FAILED, call.snapshot().endReason);
    }

    @Test
    public void videoCallPublishesTheCameraAndCanTurnItOff() {
        RelayDirectCall call = outgoing(true);
        assertEquals("camera right away for the preview", 1, engine.count("camera on"));
        assertEquals(0, engine.count("microphone on"));
        call.setCameraEnabled(false);
        scheduler.run();
        assertEquals(1, engine.count("camera off"));
    }

    @Test
    public void muteChosenBeforeConnectingIsApplied() {
        RelayCall ringing = Fakes.ringing("c1", ME, PEER, RelayCall.Invitee.RINGING, scheduler.now + 45_000);
        api.ringResult = new RelayCallJoin("ws://127.0.0.1:7880", "token", ringing);
        RelayDirectCall call = RelayDirectCall.outgoing(api, engine, scheduler, ME, DEVICE, DM, PEER, false);
        call.setMuted(true);
        call.start();
        scheduler.run();
        engine.remote(device(PEER, "pd", false, true, false));
        scheduler.run();
        assertEquals(0, engine.count("microphone on"));
        assertTrue(call.snapshot().isMuted);
        call.setMuted(false);
        scheduler.run();
        assertEquals(1, engine.count("microphone on"));
    }

    @Test
    public void failedMicrophoneRetriesAndNeverShowsMuted() {
        engine.microphoneFailures.add(new Exception("no audio"));
        engine.microphoneFailures.add(new Exception("no audio"));
        RelayDirectCall call = outgoing(false);
        engine.remote(device(PEER, "pd", false, true, false));
        scheduler.run();
        assertFalse(call.snapshot().isMuted);
        assertEquals(1, engine.count("microphone on"));
        scheduler.advance(500);
        assertEquals(2, engine.count("microphone on"));
        scheduler.advance(1000);
        assertEquals(3, engine.count("microphone on"));
        assertTrue(engine.microphoneOn);
        scheduler.advance(30_000);
        assertEquals(3, engine.count("microphone on"));
    }

    @Test
    public void microphoneRequestThatNeverAnswersTimesOutAndRetries() {
        engine.holdMicrophone = true;
        RelayDirectCall call = outgoing(false);
        engine.remote(device(PEER, "pd", false, true, false));
        scheduler.run();
        assertEquals(1, engine.count("microphone on"));
        scheduler.advance(15_000 + 500);
        assertEquals(2, engine.count("microphone on"));
        scheduler.advance(15_000 + 1000);
        assertEquals("at most two unanswered requests", 2, engine.count("microphone on"));
        assertFalse(call.snapshot().isMuted);
    }

    @Test
    public void cameraRetriesThenTurnsOff() {
        for (int i = 0; i < 4; i++) {
            engine.cameraFailures.add(new Exception("camera busy"));
        }
        RelayDirectCall call = outgoing(true);
        scheduler.advance(1000 + 2000 + 4000 + 100);
        assertEquals(4, engine.count("camera on"));
        assertFalse(call.snapshot().isCameraEnabled);
    }

    @Test
    public void glareAnswersTheCalleesRing() {
        RelayCall theirs = Fakes.ringing("c9", PEER, ME, RelayCall.Invitee.RINGING, scheduler.now + 45_000);
        api.ringError = new RelayCallsException(409, "call in progress", theirs);
        RelayCall accepted = theirs.copy();
        accepted.state = RelayCall.State.ACTIVE;
        api.acceptResult = new RelayCallJoin("ws://127.0.0.1:7880", "t", accepted);
        RelayDirectCall call = RelayDirectCall.outgoing(api, engine, scheduler, ME, DEVICE, DM, PEER, false);
        call.start();
        scheduler.run();
        assertTrue(api.did("accept c9"));
        assertEquals("c9", call.callId());
        engine.remote(device(PEER, "pd", false, true, false));
        scheduler.run();
        assertEquals(RelayDirectCall.State.ACTIVE, call.state());
    }

    @Test
    public void droppedMediaEndsWithTheServersReason() {
        RelayDirectCall call = outgoing(false);
        engine.remote(device(PEER, "pd", false, true, false));
        scheduler.run();
        api.calls.put("c1", Fakes.ended(call.call(), RelayCall.EndReason.HANGUP, 3000));
        engine.listener.onDisconnect(RelayMedia.DisconnectReason.REMOVED, "room deleted");
        scheduler.run();
        assertEquals(RelayDirectCall.EndReason.REMOTE_HANGUP, call.snapshot().endReason);
    }

    @Test
    public void activeCallsWithoutTheCallChecksTheServer() {
        RelayDirectCall call = outgoing(false);
        api.calls.put("c1", Fakes.ended(call.call(), RelayCall.EndReason.CANCELLED, 0));
        call.handle(RelayCallsEvent.activeCalls(new java.util.ArrayList<>()));
        scheduler.run();
        assertTrue(api.did("call c1"));
        assertEquals(RelayDirectCall.State.TERMINATED, call.state());
    }

    @Test
    public void eventsOfOtherCallsAreIgnored() {
        RelayDirectCall call = outgoing(false);
        RelayCall other = Fakes.ended(Fakes.ringing("other", PEER, ME, RelayCall.Invitee.RINGING, 0), RelayCall.EndReason.MISSED, 0);
        call.handle(RelayCallsEvent.ended(other));
        call.handle(state(other, PEER, "pd", RelayCallsEvent.Action.DECLINED));
        assertEquals(RelayDirectCall.State.REQUESTING, call.state());
    }

    @Test
    public void incomingReportsReceivedAcceptsAndBecomesActive() {
        RelayDirectCall call = incoming();
        assertTrue(api.did("received c2"));
        assertEquals(RelayDirectCall.State.RINGING, call.state());
        assertFalse(engine.connected);
        RelayCall accepted = call.call().copy();
        accepted.state = RelayCall.State.ACTIVE;
        accepted.invitees.put(ME, RelayCall.Invitee.ACCEPTED);
        api.acceptResult = new RelayCallJoin("ws://127.0.0.1:7880", "t", accepted);
        call.accept();
        scheduler.run();
        assertTrue(api.did("accept c2"));
        assertTrue(engine.connected);
        assertEquals(1, engine.count("microphone on"));
        engine.remote(device(PEER, "pd", false, true, false));
        scheduler.run();
        assertEquals(RelayDirectCall.State.ACTIVE, call.state());
    }

    @Test
    public void incomingDeclineAndBusy() {
        RelayDirectCall call = incoming();
        call.decline("declined");
        assertEquals(RelayDirectCall.EndReason.DECLINED_LOCALLY, call.snapshot().endReason);
        assertTrue(api.did("decline c2 declined"));

        RelayDirectCall busy = incoming();
        busy.decline("busy");
        assertEquals(RelayDirectCall.EndReason.BUSY, busy.snapshot().endReason);
        assertTrue(api.did("decline c2 busy"));
    }

    @Test
    public void incomingHangupWhileRingingDeclines() {
        RelayDirectCall call = incoming();
        call.hangup();
        assertTrue(api.did("decline c2 declined"));
        assertFalse(api.did("hangup c2"));
    }

    @Test
    public void incomingAnsweredOrDeclinedOnAnotherDevice() {
        RelayDirectCall call = incoming();
        call.handle(state(call.call(), ME, "other-device", RelayCallsEvent.Action.ACCEPTED));
        assertEquals(RelayDirectCall.EndReason.ANSWERED_ELSEWHERE, call.snapshot().endReason);

        RelayDirectCall second = incoming();
        RelayCall declined = second.call().copy();
        declined.invitees.put(ME, RelayCall.Invitee.DECLINED);
        second.handle(RelayCallsEvent.updated(declined));
        assertEquals(RelayDirectCall.EndReason.DECLINED_ELSEWHERE, second.snapshot().endReason);
    }

    @Test
    public void incomingCancelledByTheCaller() {
        RelayDirectCall call = incoming();
        call.handle(RelayCallsEvent.ended(Fakes.ended(call.call(), RelayCall.EndReason.CANCELLED, 0)));
        assertEquals(RelayDirectCall.EndReason.CANCELLED, call.snapshot().endReason);

        RelayDirectCall second = incoming();
        second.handle(state(second.call(), PEER, "pd", RelayCallsEvent.Action.CANCELLED));
        assertEquals(RelayDirectCall.EndReason.CANCELLED, second.snapshot().endReason);
    }

    @Test
    public void incomingKeepsRingingWhileTheCallersDeviceJoinsAndLeaves() {
        RelayDirectCall call = incoming();
        call.handle(state(call.call(), PEER, "pd", RelayCallsEvent.Action.JOINED));
        call.handle(state(call.call(), PEER, "pd", RelayCallsEvent.Action.LEFT));
        assertEquals(RelayDirectCall.State.RINGING, call.state());
    }

    @Test
    public void incomingRingExpiresLocally() {
        RelayDirectCall call = incoming();
        scheduler.advance(45_000 + 10);
        assertEquals(RelayDirectCall.EndReason.MISSED, call.snapshot().endReason);
    }

    @Test
    public void acceptConflictMeansAnsweredElsewhere() {
        RelayDirectCall call = incoming();
        api.acceptError = new RelayCallsException(409, "not ringing", null);
        RelayCall answered = call.call().copy();
        answered.state = RelayCall.State.ACTIVE;
        answered.invitees.put(ME, RelayCall.Invitee.ACCEPTED);
        api.calls.put("c2", answered);
        call.accept();
        scheduler.run();
        assertEquals(RelayDirectCall.EndReason.ANSWERED_ELSEWHERE, call.snapshot().endReason);
    }

    @Test
    public void incomingRemoteHangupAfterAnswer() {
        RelayDirectCall call = incoming();
        RelayCall accepted = call.call().copy();
        accepted.state = RelayCall.State.ACTIVE;
        api.acceptResult = new RelayCallJoin("ws://127.0.0.1:7880", "t", accepted);
        call.accept();
        scheduler.run();
        engine.remote(device(PEER, "pd", false, true, false));
        scheduler.run();
        call.handle(state(accepted, PEER, "pd", RelayCallsEvent.Action.HANGUP));
        assertEquals(RelayDirectCall.EndReason.REMOTE_HANGUP, call.snapshot().endReason);
    }

    @Test
    public void terminatedCallIgnoresLaterEvents() {
        RelayDirectCall call = incoming();
        call.decline("declined");
        RelayCall accepted = call.call().copy();
        accepted.invitees.put(ME, RelayCall.Invitee.ACCEPTED);
        call.handle(state(accepted, ME, "other", RelayCallsEvent.Action.ACCEPTED));
        assertEquals(RelayDirectCall.EndReason.DECLINED_LOCALLY, call.snapshot().endReason);
    }
}
