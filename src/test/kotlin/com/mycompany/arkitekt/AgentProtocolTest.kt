package com.mycompany.arkitekt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The agent wire format, pinned against the server's pydantic schema
 * (`facade/messages.py` in the rekuest deployment).
 *
 * `Register` is the one message the server declares `extra="forbid"`, so a stray field there is
 * not ignored — it fails discrimination and closes the socket. That is exactly how the plugin's
 * agent broke on the `mode` field, which is why these assert the *exact* key set rather than just
 * that the expected keys are present.
 */
class AgentProtocolTest {

    private fun encode(event: AgentEvent): JsonObject =
            Json.parseToJsonElement(agentJson.encodeToString(AgentEvent.serializer(), event))
                    as JsonObject

    private fun keysOf(event: AgentEvent): Set<String> = encode(event).keys

    // ---- Outbound: exact wire shape ---------------------------------------------------

    @Test
    fun `REGISTER carries exactly the fields the server allows`() {
        val keys = keysOf(AgentEvent.Register(token = "tok", force = false, sessionId = "sess"))
        assertEquals(setOf("type", "token", "force", "session_id", "id"), keys)
        // The field whose removal was the whole point: the server retired it and made Register
        // strict so a client still sending it is told to update rather than silently promoted.
        assertTrue("mode" !in keys)
    }

    @Test
    fun `REGISTER serializes session_id in snake_case`() {
        val json = encode(AgentEvent.Register(token = "tok", sessionId = "sess"))
        assertEquals("sess", json["session_id"]?.jsonPrimitive?.content)
        assertEquals("REGISTER", json["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `SESSION_INIT carries the session id and an empty states map`() {
        val json = encode(AgentEvent.SessionInit(sessionId = "sess"))
        assertEquals(setOf("type", "session_id", "states", "id"), json.keys)
        assertEquals("SESSION_INIT", json["type"]?.jsonPrimitive?.content)
        // `states` is required by the server even though we register none.
        assertEquals(JsonObject(emptyMap()), json["states"])
    }

    @Test
    fun `HEARTBEAT_ANSWER is bare`() {
        // No task, no seq, and deliberately no echo of the ping's id — the server just resolves
        // whichever answer arrives first.
        assertEquals(setOf("type", "id"), keysOf(AgentEvent.HeartbeatAnswer()))
    }

    @Test
    fun `task reports use the type literals the server discriminates on`() {
        fun typeOf(event: AgentEvent) = encode(event)["type"]?.jsonPrimitive?.content

        assertEquals("STARTED", typeOf(AgentEvent.Started("t")))
        assertEquals("YIELD", typeOf(AgentEvent.Yield("t")))
        // v2 renamed these two: DONE -> COMPLETED and ERROR -> FAILED.
        assertEquals("COMPLETED", typeOf(AgentEvent.Completed("t")))
        assertEquals("FAILED", typeOf(AgentEvent.Failed("t", "boom")))
        assertEquals("CRITICAL", typeOf(AgentEvent.Critical("t", "boom")))
        assertEquals("PROGRESS", typeOf(AgentEvent.Progress("t")))
        assertEquals("LOG", typeOf(AgentEvent.Log("t", "hi")))
        assertEquals("CANCELLED", typeOf(AgentEvent.Cancelled("t")))
        assertEquals("INTERRUPTED", typeOf(AgentEvent.Interrupted("t")))
        assertEquals("PAUSED", typeOf(AgentEvent.Paused("t")))
        assertEquals("RESUMED", typeOf(AgentEvent.Resumed("t")))
    }

    @Test
    fun `COMPLETED carries no returns`() {
        // Results travel as YIELD; a terminal COMPLETED is just the task id.
        assertEquals(setOf("type", "task", "seq", "id"), keysOf(AgentEvent.Completed("t")))
    }

    @Test
    fun `LOG defaults to a level the server accepts`() {
        val json = encode(AgentEvent.Log("t", "hi"))
        // The server's literal set is DEBUG|INFO|ERROR|WARN|CRITICAL — note WARN, not WARNING.
        assertEquals("INFO", json["level"]?.jsonPrimitive?.content)
    }

    // ---- Inbound decoding -----------------------------------------------------------------

    private fun decode(text: String) = agentJson.decodeFromString(AgentMessage.serializer(), text)

    @Test
    fun `INIT without inquiries decodes`() {
        val msg = decode("""{"type":"INIT","id":"m1","agent":"42"}""") as AgentMessage.Init
        assertEquals("42", msg.agent)
        assertTrue(msg.inquiries.isEmpty())
    }

    @Test
    fun `INIT inquiries decode as bare task objects`() {
        // AssignInquiry is not an AgentMessage: on the wire it has only `task` — no type, no id.
        val msg = decode(
                """{"type":"INIT","id":"m1","agent":"42",
                    "inquiries":[{"task":"t-1"},{"task":"t-2"}]}"""
        ) as AgentMessage.Init
        assertEquals(listOf("t-1", "t-2"), msg.inquiries.map { it.task })
    }

    @Test
    fun `ASSIGN decodes the fields we act on and tolerates the rest`() {
        val msg = decode(
                """{"type":"ASSIGN","id":"m1","interface":"frage","task":"t-1",
                    "args":{"name":"hello"},"probe":false,"root":null,"parent":null,
                    "user":"2","org":"2","action":"a-hash","implementation":"i-1",
                    "token":"prov-token","reference":"ref-1","step":null,"capture":null,
                    "resolution":null,"message":null}"""
        ) as AgentMessage.Assign

        assertEquals("frage", msg.functionName)
        assertEquals("t-1", msg.task)
        assertEquals("hello", msg.args["name"]?.jsonPrimitive?.content)
        assertEquals("prov-token", msg.token)
        assertEquals("i-1", msg.implementation)
        assertEquals(false, msg.probe)
        assertNull(msg.root)
    }

    @Test
    fun `HEARTBEAT and PROTOCOL_ERROR decode`() {
        assertTrue(decode("""{"type":"HEARTBEAT","id":"m1"}""") is AgentMessage.Heartbeat)
        val err = decode("""{"type":"PROTOCOL_ERROR","id":"m1","error":"nope"}""")
                as AgentMessage.ProtocolError
        assertEquals("nope", err.error)
    }

    @Test
    fun `BOUNCE carries the reconnect delay hint`() {
        val msg = decode("""{"type":"BOUNCE","id":"m1","duration":7}""") as AgentMessage.Bounce
        assertEquals(7, msg.duration)
    }

    @Test
    fun `an unknown message type is rejected by the decoder, not silently mis-decoded`() {
        // The server keeps adding types additively (the whole caller-side half — ASSIGN_RESPONSE,
        // the *_EVENT mirrors — is invisible to us). kotlinx has no polymorphic fallback, so the
        // decode throws, which is why the receive loop wraps it in runCatching and drops the frame
        // instead of letting one unknown message kill the connection.
        assertFailsWith<Exception> { decode("""{"type":"YIELD_EVENT","id":"m1","task":"t-1"}""") }
    }

    @Test
    fun `unknown fields on a known message are ignored`() {
        val msg = decode(
                """{"type":"CANCEL","id":"m1","task":"t-1","something_new":true}"""
        ) as AgentMessage.Cancel
        assertEquals("t-1", msg.task)
    }

    // ---- Inquiry reconciliation ---------------------------------------------------------

    @Test
    fun `every inquiry gets a terminal report`() {
        val reports = reportsForInquiries(listOf(AssignInquiry("t-1"), AssignInquiry("t-2")))
        assertEquals(listOf("t-1", "t-2"), reports.map { it.task })
        assertTrue(reports.all { it.error.isNotBlank() })
        // Silence here leaves the tasks in-flight server-side until a sweep resolves them.
        assertEquals(2, reports.size)
    }

    @Test
    fun `no inquiries means no reports`() {
        assertTrue(reportsForInquiries(emptyList()).isEmpty())
    }
}
