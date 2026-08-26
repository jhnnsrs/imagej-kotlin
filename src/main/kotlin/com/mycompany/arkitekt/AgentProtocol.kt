package com.mycompany.arkitekt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.util.UUID

// The rekuest agent wire protocol (see rekuest-next `rekuest_next/messages.py`).
//
// Both directions are discriminated unions on a `type` string. Every message carries an `id`
// (a uuid4). We rely on kotlinx's class discriminator (`type`) rather than declaring a `type`
// property on each subclass, so the discriminator is written/read automatically.
//
// Each task is keyed by `task` (a UUID string). The agent's report events
// (STARTED -> YIELD -> COMPLETED, with FAILED/CRITICAL on error) extend the ack-able stream:
// they may carry a `seq`, and the backend acks them with EVENT_ACK.
//
// DELIBERATE: we implement neither `seq` nor retain-and-resend. `seq` is optional and the backend
// dedups terminal reports by task id regardless of it, and the reason rekuest-next replays unacked
// terminals on INIT — reconciling tasks the server still thinks are running — we achieve instead by
// answering INIT's `inquiries` directly (see Agent.kt). So EVENT_ACK is a no-op by choice, not by
// omission.
//
// TRAP: `FromAgentMessageType` on the server also lists APP_CANCELLED, but it has no message class
// and is absent from the union — sending it fails discrimination and closes the socket. Report
// app-side cancellation as CANCELLED. Do not "complete" the enum here.

internal fun newId(): String = UUID.randomUUID().toString()

// Lenient, default-encoding JSON with `type` as the polymorphic discriminator. `ignoreUnknownKeys`
// keeps us forward-compatible with extra fields the server may add to a known message.
val agentJson: Json = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * One still-open task the server is handing back on reconnect (nested inside INIT).
 *
 * Deliberately NOT an [AgentMessage]: on the wire it is a bare object with only `task` — no
 * `type`, no `id` — so it must not go through the polymorphic discriminator.
 */
@Serializable data class AssignInquiry(val task: String)

// ---- Inbound: rekuest backend -> agent --------------------------------------------------

@Serializable
sealed class AgentMessage {

    /**
     * The server's acknowledgement of our REGISTER — the only proof this connection worked.
     *
     * `inquiries` is one entry per task the server still has open for us, handed back to a
     * reconnect that presented the same `session_id`. There is no inquiry-reply message type:
     * the contract is that we either keep running the task or report a terminal event for it.
     */
    @Serializable
    @SerialName("INIT")
    data class Init(
            val agent: String,
            val inquiries: List<AssignInquiry> = emptyList(),
            val id: String = newId()
    ) : AgentMessage()

    /**
     * Start a task. Delivery is **at-least-once** (the backend queue does pop -> send -> ack), so
     * the same `task` can arrive twice and the handler must only run once.
     */
    @Serializable
    @SerialName("ASSIGN")
    data class Assign(
            @SerialName("interface") val functionName: String,
            val task: String,
            val args: Map<String, JsonElement?> = emptyMap(),
            val reference: String? = null,
            /**
             * An opaque, signed provenance token attesting who caused this task. The agent is
             * meant to forward it untouched to downstream services (never validate it); null when
             * the implementation sets `needsToken = false`. We parse but do not yet forward it —
             * threading it into the mikro calls is handler work, tracked separately.
             */
            val token: String? = null,
            /** A probe (`p-…` id): ephemeral, no server-side history, no replay or recovery. */
            val probe: Boolean = false,
            /** The user-triggered task at the head of the cascade; null if this is the root. */
            val root: String? = null,
            /** The direct parent; null if this is the root. */
            val parent: String? = null,
            val user: String? = null,
            val org: String? = null,
            val action: String? = null,
            val implementation: String? = null,
            val id: String = newId()
    ) : AgentMessage()

    @Serializable
    @SerialName("CANCEL")
    data class Cancel(val task: String, val id: String = newId()) : AgentMessage()

    @Serializable
    @SerialName("INTERRUPT")
    data class Interrupt(val task: String, val id: String = newId()) : AgentMessage()

    @Serializable
    @SerialName("PAUSE")
    data class Pause(val task: String, val id: String = newId()) : AgentMessage()

    @Serializable
    @SerialName("RESUME")
    data class Resume(val task: String, val step: Boolean = false, val id: String = newId()) : AgentMessage()

    @Serializable @SerialName("HEARTBEAT") data class Heartbeat(val id: String = newId()) : AgentMessage()

    /** Backend ack that a reported event was made durable. We do not retain/resend, so it's a no-op. */
    @Serializable
    @SerialName("EVENT_ACK")
    data class EventAck(val event: String, val task: String? = null, val seq: Int? = null, val id: String = newId()) :
            AgentMessage()

    @Serializable
    @SerialName("KICK")
    data class Kick(val reason: String? = null, val id: String = newId()) : AgentMessage()

    /** A soft restart: disconnect and reconnect. `duration` hints how long to wait first. */
    @Serializable
    @SerialName("BOUNCE")
    data class Bounce(val duration: Int? = null, val id: String = newId()) : AgentMessage()

    @Serializable
    @SerialName("PROTOCOL_ERROR")
    data class ProtocolError(val error: String, val id: String = newId()) : AgentMessage()
}

// ---- Outbound: agent -> rekuest backend -------------------------------------------------

@Serializable
sealed class AgentEvent {

    /** First message after connect. `force` kicks any existing connection for this agent.
     *  `mode` is the requested participation mode (we are an EXECUTOR); `sessionId` is a
     *  per-process uuid — a fresh value signals a fresh process to the backend's reclaim logic. */
    @Serializable
    @SerialName("REGISTER")
    data class Register(
            val token: String,
            val force: Boolean = false,
            @SerialName("session_id") val sessionId: String? = null,
            val id: String = newId()
    ) : AgentEvent()
    // NOTE: no `mode`. The server dropped the field (rekuest, 2026-08-13) and made REGISTER the
    // one message with `extra="forbid"` on purpose: a client still sending the old
    // `mode: "OBSERVER"` must be told to update, not be silently promoted to a full agent and
    // displace the real executor. Sending it is a hard protocol error, not a warning.

    /**
     * Announces this run to the backend, which opens a `Session` row from it.
     *
     * Sent once per process, after the first INIT — the server only dispatches non-REGISTER
     * frames once the session exists, so sending it before acknowledgement risks a close. It is
     * deliberately not re-sent on reconnect: the session belongs to the process, not the socket.
     *
     * `states` is the initial state snapshots by name. We register no states, so it is always
     * empty — but the field is required, so it must still be written.
     */
    @Serializable
    @SerialName("SESSION_INIT")
    data class SessionInit(
            @SerialName("session_id") val sessionId: String,
            val states: Map<String, JsonElement> = emptyMap(),
            val id: String = newId()
    ) : AgentEvent()

    @Serializable @SerialName("HEARTBEAT_ANSWER") data class HeartbeatAnswer(val id: String = newId()) : AgentEvent()

    /**
     * The actor accepted the task and began executing it.
     *
     * A deliberate divergence from rekuest-next, which never emits STARTED and uses
     * `Progress(progress = 0, message = "Queued for running")` as its de-facto start marker. The
     * server accepts both, and STARTED is the only thing that produces a `StartedEvent` for the
     * caller — so ours is strictly more informative. Not an oversight.
     */
    @Serializable
    @SerialName("STARTED")
    data class Started(val task: String, val seq: Int? = null, val id: String = newId()) : AgentEvent()

    @Serializable
    @SerialName("YIELD")
    data class Yield(
            val task: String,
            val returns: Map<String, JsonElement?>? = null,
            val seq: Int? = null,
            val id: String = newId()
    ) : AgentEvent()

    /** The task finished successfully (terminal). */
    @Serializable
    @SerialName("COMPLETED")
    data class Completed(val task: String, val seq: Int? = null, val id: String = newId()) : AgentEvent()

    /** Recoverable error for a task. */
    @Serializable
    @SerialName("FAILED")
    data class Failed(val task: String, val error: String, val seq: Int? = null, val id: String = newId()) :
            AgentEvent()

    /** Unrecoverable error for a task. */
    @Serializable
    @SerialName("CRITICAL")
    data class Critical(val task: String, val error: String, val seq: Int? = null, val id: String = newId()) :
            AgentEvent()

    @Serializable
    @SerialName("PROGRESS")
    data class Progress(
            val task: String,
            val progress: Int? = null,
            val message: String? = null,
            val seq: Int? = null,
            val id: String = newId()
    ) : AgentEvent()

    @Serializable
    @SerialName("LOG")
    data class Log(
            val task: String,
            val message: String,
            val level: String = "INFO",
            val seq: Int? = null,
            val id: String = newId()
    ) : AgentEvent()

    @Serializable
    @SerialName("CANCELLED")
    data class Cancelled(val task: String, val seq: Int? = null, val id: String = newId()) : AgentEvent()

    @Serializable
    @SerialName("INTERRUPTED")
    data class Interrupted(val task: String, val seq: Int? = null, val id: String = newId()) : AgentEvent()

    @Serializable
    @SerialName("PAUSED")
    data class Paused(val task: String, val seq: Int? = null, val id: String = newId()) : AgentEvent()

    @Serializable
    @SerialName("RESUMED")
    data class Resumed(val task: String, val seq: Int? = null, val id: String = newId()) : AgentEvent()
}

// ---- Close codes -------------------------------------------------------------------------

/**
 * The websocket close codes the agent gateway uses (`facade/codes.py`).
 *
 * These come from the SERVER, not from rekuest-next: that client's table
 * (`agents/transport/websocket.py`) predates 4004/4005 and still maps 3001 to "kicked", which
 * would make a mere heartbeat timeout stop the agent for good.
 */
object AgentCloseCodes {
    const val HEARTBEAT_NOT_RESPONDED = 3001
    const val MESSAGE_IS_NOT_VALID_JSON = 3002
    const val MESSAGE_DOES_NOT_MATCH_SCHEMA = 3003
    // Defined server-side but never emitted; a pre-registration frame closes 3003 instead.
    const val MESSAGE_RECEIVED_BEFORE_REGISTRATION = 3004
    const val AGENT_IS_BLOCKED = 4003
    const val AGENT_ALREADY_CONNECTED = 4004
    const val AGENT_REPLACED = 4005
}

enum class CloseAction {
    /** Transient: reconnect on the usual backoff. */
    RECONNECT,
    /** Another connection holds the agent; it may go stale, so wait it out and try once more. */
    WAIT_AND_RETRY,
    /** Reconnecting cannot help, or would actively harm. Give up and surface why. */
    STOP
}

data class CloseVerdict(val action: CloseAction, val why: String)

/**
 * Decide what a closed connection means.
 *
 * [registered] (did INIT arrive?) is only the fallback for a transport-level drop that carried no
 * code at all — the code itself is authoritative whenever we have one.
 */
fun classifyClose(code: Int?, registered: Boolean): CloseVerdict = when (code) {
    AgentCloseCodes.HEARTBEAT_NOT_RESPONDED ->
            CloseVerdict(CloseAction.RECONNECT, "we stopped answering heartbeats")

    // Reconnecting re-sends whatever the server could not parse or accept. 3003 is overloaded:
    // it is also what an invalid token and a missing agent record produce, because the server's
    // register handler wraps everything in one catch-all. Either way a retry changes nothing.
    AgentCloseCodes.MESSAGE_IS_NOT_VALID_JSON ->
            CloseVerdict(CloseAction.STOP, "the server could not parse a frame we sent")
    AgentCloseCodes.MESSAGE_DOES_NOT_MATCH_SCHEMA ->
            CloseVerdict(
                    CloseAction.STOP,
                    "the server rejected our registration or a frame we sent (close 3003 — it " +
                            "covers a wire-format mismatch, an invalid token, and an agent that " +
                            "was never created, indistinguishably)"
            )
    AgentCloseCodes.MESSAGE_RECEIVED_BEFORE_REGISTRATION ->
            CloseVerdict(CloseAction.STOP, "we sent a frame before registering")

    AgentCloseCodes.AGENT_IS_BLOCKED ->
            CloseVerdict(CloseAction.STOP, "this agent is blocked; an operator has to unblock it")

    // The server only rejects a *provably live* incumbent. Once its last_seen ages past
    // AGENT_STALE_AFTER (3 x the 10s heartbeat) a plain reconnect takes the lease over without
    // force, so waiting is the fix. Escalating to force would kick a live peer — a person's call.
    AgentCloseCodes.AGENT_ALREADY_CONNECTED ->
            CloseVerdict(CloseAction.WAIT_AND_RETRY, "another connection already holds this agent")

    // We were displaced, or our lease was fenced. Another process owns the agent now, and
    // reconnecting immediately would displace it right back — two instances evicting each other
    // in a loop. Stop and let a human decide.
    AgentCloseCodes.AGENT_REPLACED ->
            CloseVerdict(CloseAction.STOP, "another connection took over this agent")

    else ->
            if (registered) CloseVerdict(CloseAction.RECONNECT, "the connection dropped")
            else CloseVerdict(CloseAction.RECONNECT, "the connection dropped before registering")
}
