package com.mycompany.arkitekt

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the agent does when the socket closes.
 *
 * This is pure logic and it is the part that, wrong, does real damage: classifying `AGENT_REPLACED`
 * as transient makes two instances displace each other in a loop forever. The numbers come from the
 * server (`facade/codes.py`), deliberately NOT from rekuest-next, whose own table predates 4004 and
 * 4005 and still reads 3001 as "kicked".
 */
class CloseCodeTest {

    private fun action(code: Int?, registered: Boolean = true) =
            classifyClose(code, registered).action

    @Test
    fun `a heartbeat timeout is transient`() {
        // The one 3xxx code that is not a client bug: we were alive, just slow to answer.
        assertEquals(CloseAction.RECONNECT, action(AgentCloseCodes.HEARTBEAT_NOT_RESPONDED))
    }

    @Test
    fun `being displaced stops the agent`() {
        // The regression this test exists for: another connection owns the agent now. Coming back
        // would displace it, it would come back and displace us, forever.
        assertEquals(CloseAction.STOP, action(AgentCloseCodes.AGENT_REPLACED))
    }

    @Test
    fun `being displaced stops even though we had registered successfully`() {
        // Displacement can only happen *after* INIT, so "did we register" would call this a clean
        // transient drop. The close code has to win.
        assertEquals(CloseAction.STOP, action(AgentCloseCodes.AGENT_REPLACED, registered = true))
    }

    @Test
    fun `a live incumbent is waited out, not fought`() {
        // The server frees the lease once the incumbent goes stale, so waiting works and forcing
        // would evict a healthy peer.
        assertEquals(
                CloseAction.WAIT_AND_RETRY,
                action(AgentCloseCodes.AGENT_ALREADY_CONNECTED)
        )
    }

    @Test
    fun `client-side protocol faults are fatal`() {
        // Re-sending whatever the server could not accept cannot help.
        assertEquals(CloseAction.STOP, action(AgentCloseCodes.MESSAGE_IS_NOT_VALID_JSON))
        assertEquals(CloseAction.STOP, action(AgentCloseCodes.MESSAGE_DOES_NOT_MATCH_SCHEMA))
        assertEquals(CloseAction.STOP, action(AgentCloseCodes.MESSAGE_RECEIVED_BEFORE_REGISTRATION))
    }

    @Test
    fun `a blocked agent stops`() {
        assertEquals(CloseAction.STOP, action(AgentCloseCodes.AGENT_IS_BLOCKED))
    }

    @Test
    fun `a drop with no close code falls back to reconnecting`() {
        // A transport-level failure (no close frame at all) is the one case where the close code
        // tells us nothing, so it is the only place "did INIT arrive" still matters.
        assertEquals(CloseAction.RECONNECT, action(null, registered = true))
        assertEquals(CloseAction.RECONNECT, action(null, registered = false))
    }

    @Test
    fun `an unrecognised code is treated as transient`() {
        // Codes are added additively; a normal 1000/1006 close must not stop the agent.
        assertEquals(CloseAction.RECONNECT, action(1000))
        assertEquals(CloseAction.RECONNECT, action(1006))
    }

    @Test
    fun `every verdict explains itself`() {
        val codes = listOf(
                AgentCloseCodes.HEARTBEAT_NOT_RESPONDED,
                AgentCloseCodes.MESSAGE_IS_NOT_VALID_JSON,
                AgentCloseCodes.MESSAGE_DOES_NOT_MATCH_SCHEMA,
                AgentCloseCodes.MESSAGE_RECEIVED_BEFORE_REGISTRATION,
                AgentCloseCodes.AGENT_IS_BLOCKED,
                AgentCloseCodes.AGENT_ALREADY_CONNECTED,
                AgentCloseCodes.AGENT_REPLACED
        )
        // 3003 in particular is overloaded across a wire-format bug, a bad token and a missing
        // agent record, so the human-readable half is the only thing a user can act on.
        codes.forEach { code ->
            assertEquals(true, classifyClose(code, true).why.isNotBlank(), "no reason for $code")
        }
    }
}
