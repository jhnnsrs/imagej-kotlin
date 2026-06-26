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
// they may carry a `seq`, and the backend acks them with EVENT_ACK. We do not implement
// retain-and-resend, so `seq` is left null and EVENT_ACK is a no-op.

internal fun newId(): String = UUID.randomUUID().toString()

// Lenient, default-encoding JSON with `type` as the polymorphic discriminator. `ignoreUnknownKeys`
// keeps us forward-compatible with extra fields the server may add to a known message.
val agentJson: Json = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = true
}

// ---- Inbound: rekuest backend -> agent --------------------------------------------------

@Serializable
sealed class AgentMessage {

    /** Response to REGISTER; carries the server-assigned agent id and any in-flight tasks to resume. */
    @Serializable
    @SerialName("INIT")
    data class Init(
            val agent: String,
            val inquiries: List<JsonElement> = emptyList(),
            val id: String = newId()
    ) : AgentMessage()

    /** Start a task. `task` is a UUID string (NOT an int). Server-only fields
     *  (`user`, `org`, `action`, `implementation`, `root`, `parent`, …) are ignored on decode. */
    @Serializable
    @SerialName("ASSIGN")
    data class Assign(
            @SerialName("interface") val functionName: String,
            val task: String,
            val args: Map<String, JsonElement?> = emptyMap(),
            val reference: String? = null,
            val token: String? = null,
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
            val mode: String = "EXECUTOR",
            @SerialName("session_id") val sessionId: String? = null,
            val id: String = newId()
    ) : AgentEvent()

    @Serializable @SerialName("HEARTBEAT_ANSWER") data class HeartbeatAnswer(val id: String = newId()) : AgentEvent()

    /** The actor accepted the task and began executing it. */
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
