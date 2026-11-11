package io.github.lejw0925.virtualscreen

import android.app.Application
import io.github.lejw0925.virtualscreen.backend.*
import io.github.lejw0925.virtualscreen.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class ControlApprovalTest {
    private fun sessions() = VirtualScreenSessionManager(RuntimeEnvironment.getApplication(),
        FakeBackend(BackendKind.ROOT),FakeBackend(BackendKind.SHIZUKU))

    @Test fun takeoverAutoApprovesAndRevokesOldLeaseAndRequiresFreshObservation() = runBlocking {
        val sessions = sessions()
        val first = (sessions.acquireLease("first") as LeaseDecision.Granted).lease
        sessions.create(first.owner,first.token)
        val observation = sessions.observe(first.owner,first.token).getOrThrow().values["observation_id"] as String
        val approvals = ControlApprovalManager(sessions)
        val granted = approvals.request("second","Agent","Continue the task")
        assertEquals("approved",granted.values["approval_status"])
        assertTrue(granted.ok)
        assertEquals("second",sessions.leaseStatus()!!.owner)
        assertEquals("LEASE_REQUIRED",sessions.observe(first.owner,first.token).exceptionOrNull()!!.message)
        assertEquals("STALE_OBSERVATION",runCatching { sessions.operate("second",granted.values["lease_token"] as String,observation,BackendOperation.Tap(10,10)) }.exceptionOrNull()!!.message)
        sessions.shutdown()
    }

    @Test fun allowOnceIsSessionBoundAndCannotBeReplayed() {
        val sessions = sessions(); sessions.phoneTakeover(); sessions.beginPhoneGesture()
        val approvals = ControlApprovalManager(sessions)
        val id = approvals.request("agent","Agent","Resume").values["approval_id"] as String
        assertEquals("CONTROL_REQUEST_NOT_FOUND",approvals.status("other",id).code)
        approvals.approve(id)
        assertFalse(approvals.alwaysAllow.value)
        val generation = sessions.leaseStatus()!!.generation
        approvals.approve(id,always=true)
        assertEquals(generation,sessions.leaseStatus()!!.generation)
        assertFalse(approvals.alwaysAllow.value)
        sessions.phoneTakeover()
        assertEquals("CONTROL_REQUEST_STALE",approvals.status("agent",id).code)
        assertEquals("pending",approvals.request("agent","Agent","Resume again").values["approval_status"])
    }

    @Test fun staleOrExpiredApprovalCannotTransferControlOrPersistPermission() {
        var now = 0L; var saved = false
        val sessions = sessions(); sessions.phoneTakeover(); sessions.beginPhoneGesture()
        val approvals = ControlApprovalManager(sessions,saveAlwaysAllow={ saved=it },nowMillis={ now })
        val stale = approvals.request("agent","Agent","Resume").values["approval_id"] as String
        val phone = sessions.phoneTakeover()
        approvals.approve(stale,always=true)
        assertEquals(phone,sessions.leaseStatus()); assertFalse(saved)
        val expired = approvals.request("agent","Agent","Retry").values["approval_id"] as String
        now = 120_001; approvals.approve(expired)
        assertEquals("CONTROL_REQUEST_EXPIRED",approvals.status("agent",expired).code)
        assertEquals(phone,sessions.leaseStatus()); assertTrue(approvals.pending.value.isEmpty())
    }

    @Test fun autoApproveTakesOverFromAnotherAgentButNotDuringAnActiveGesture() {
        val sessions = sessions(); sessions.phoneTakeover()
        val approvals = ControlApprovalManager(sessions)
        // Phone holds the lease: takeover is auto-approved, no prompt.
        assertEquals("approved",approvals.request("agent","Agent","Resume").values["approval_status"])
        assertEquals("agent",sessions.leaseStatus()!!.owner)
        // A competing agent takes over from the first agent, also auto-approved.
        assertEquals("approved",approvals.request("other","Other agent","Competing task").values["approval_status"])
        assertEquals("other",sessions.leaseStatus()!!.owner)
        // But an active phone gesture still blocks the takeover (user is present).
        val phone = sessions.beginPhoneGesture()
        val gestureRequest = approvals.request("agent","Agent","Resume after gesture")
        assertEquals("pending",gestureRequest.values["approval_status"])
        approvals.approve(gestureRequest.values["approval_id"] as String)
        assertEquals(phone,sessions.leaseStatus())
        sessions.finishPhoneControl(phone.token)
        assertEquals("approved",approvals.request("agent","Agent","Gesture finished").values["approval_status"])
    }

    @Test fun disconnectCancelAndDenyRemovePendingNotifications() {
        val sessions = sessions(); sessions.phoneTakeover(); sessions.beginPhoneGesture()
        val approvals = ControlApprovalManager(sessions)
        val id = approvals.request("agent","Agent","Resume").values["approval_id"] as String
        assertEquals(id,approvals.request("agent","Agent","Duplicate request").values["approval_id"])
        approvals.deny(id)
        assertEquals("CONTROL_REQUEST_DENIED",approvals.status("agent",id).code)
        val cancelled = approvals.request("agent","Agent","Retry").values["approval_id"] as String
        approvals.cancel("agent",cancelled); approvals.approve(cancelled)
        assertEquals(VirtualScreenSessionManager.PHONE_CLIENT,sessions.leaseStatus()!!.owner)
        approvals.request("agent","Agent","Retry again")
        approvals.forgetClient("agent")
        assertTrue(approvals.pending.value.isEmpty())
        assertEquals("CONTROL_REQUEST_NOT_FOUND",approvals.status("agent",id).code)
    }
    @Test fun openPhoneViewerDoesNotBlockAutoApprove() {
        val sessions=sessions()
        sessions.beginPhoneSession("viewer")
        val approvals=ControlApprovalManager(sessions,initiallyAlwaysAllow=true)
        val request=approvals.request("agent","Agent","Resume")
        assertEquals("approved",request.values["approval_status"])
        assertFalse(sessions.phoneViewerActive)
        assertEquals("agent",sessions.leaseStatus()?.owner)
    }

    @Test fun phoneDialogCannotInterruptAnUnfinishedGesture() {
        val sessions = sessions()
        sessions.beginPhoneSession("viewer")
        val phone = sessions.beginPhoneGesture()
        val approvals = ControlApprovalManager(sessions)
        val id = approvals.request("agent", "Agent", "Continue remotely").values["approval_id"] as String

        assertFalse(approvals.approve(id, releasePhoneViewer = true))
        assertTrue(sessions.phoneViewerActive)
        assertEquals(phone.token, sessions.leaseStatus()?.token)
        assertEquals(phone.generation, sessions.leaseStatus()?.generation)
        sessions.finishPhoneControl(phone.token)
        assertTrue(approvals.approve(id, releasePhoneViewer = true))
    }

    @Test fun consecutiveTouchesReuseThePhoneLeaseWithoutFencingTheScreenObservation() = runBlocking {
        val backend = FakeBackend(BackendKind.ROOT)
        val sessions = VirtualScreenSessionManager(RuntimeEnvironment.getApplication(), backend, FakeBackend(BackendKind.SHIZUKU))
        val phone = sessions.beginPhoneSession("viewer")
        sessions.create(phone.owner, phone.token)
        val first = sessions.observe(phone.owner, phone.token, captureFrame = false).getOrThrow()
        val observation = first.values.getValue("observation_id")
        val generation = sessions.state.value.lease?.generation
        val cancelledBeforeGesture = backend.cancelled
        assertEquals(VirtualScreenSessionManager.PHONE_CLIENT, sessions.leaseStatus()?.owner)

        val nextGesture = sessions.beginPhoneGesture()
        assertEquals(phone.token, nextGesture.token)
        assertEquals(cancelledBeforeGesture, backend.cancelled)
        sessions.finishPhoneControl(nextGesture.token)

        assertEquals(phone.token, sessions.state.value.lease?.token)
        assertEquals(generation, sessions.state.value.lease?.generation)
        assertEquals(observation, sessions.state.value.observationId)
        assertEquals(cancelledBeforeGesture, backend.cancelled)
    }

    @Test fun endingOneOverlappingGestureDoesNotClearTheOtherGestureGuard() {
        val sessions = sessions()
        sessions.beginPhoneSession("viewer")
        val first = sessions.beginPhoneGesture()
        val second = sessions.beginPhoneGesture()
        assertEquals(first.token, second.token)
        val approvals = ControlApprovalManager(sessions)
        val id = approvals.request("agent", "Agent", "Continue remotely").values["approval_id"] as String

        sessions.finishPhoneControl(first.token)

        assertFalse(approvals.approve(id, releasePhoneViewer = true))
        assertEquals(second.token, sessions.leaseStatus()?.token)
        sessions.finishPhoneControl(second.token)
        assertTrue(approvals.approve(id, releasePhoneViewer = true))
    }
}
