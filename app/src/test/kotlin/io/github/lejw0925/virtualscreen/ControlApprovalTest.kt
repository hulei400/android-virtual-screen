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



    @Test fun autoApproveTakesOverEvenDuringAnActiveGesture() {
        val sessions = sessions(); sessions.phoneTakeover()
        val approvals = ControlApprovalManager(sessions)
        // Phone holds the lease: takeover is auto-approved, no prompt.
        assertEquals("approved",approvals.request("agent","Agent","Resume").values["approval_status"])
        assertEquals("agent",sessions.leaseStatus()!!.owner)
        // A competing agent takes over from the first agent, also auto-approved.
        assertEquals("approved",approvals.request("other","Other agent","Competing task").values["approval_status"])
        assertEquals("other",sessions.leaseStatus()!!.owner)
        // An active phone gesture does not block the takeover either.
        sessions.beginPhoneGesture()
        assertEquals("approved",approvals.request("agent","Agent","Resume during gesture").values["approval_status"])
        assertEquals("agent",sessions.leaseStatus()!!.owner)
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

}
