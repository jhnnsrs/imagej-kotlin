package com.mycompany.imagej

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
public data class InitialAgentMessage(
        @SerialName("type") val type: String = "INITIAL",
        val instance_id: String,
        val token: String
)

@Serializable
public data class HeartbeatResponseMessage(
        @SerialName("type") public val type: String = "HEARTBEAT"
)

// The agent's incoming messages can be one of several types.
// We define them as a sealed class hierarchy for convenience.

@Serializable
sealed class AgentMessage {
    @Serializable @SerialName("HEARTBEAT") object Heartbeat : AgentMessage()

    @Serializable
    @SerialName("INIT")
    data class Initial(
            val type: String = "INIT",
            val instance_id: String,
            val token: String? = null
    ) : AgentMessage()

    @Serializable
    @SerialName("ASSIGN")
    data class Assign(
            val type: String = "ASSIGN",
            val provision: Int,
            val args: Map<String, JsonElement?>,
            val assignation: Int
    ) : AgentMessage()

    @Serializable
    @SerialName("PROVIDE")
    data class Provide(val type: String = "PROVIDE", val provision: String) : AgentMessage()

    @Serializable @SerialName("UNPROVIDE") object Unprovide : AgentMessage()

    @Serializable
    @SerialName("ERROR")
    data class Error(val type: String = "ERROR", val code: Int) : AgentMessage()
}

@Serializable
data class AssignationEventMessage(
        @SerialName("type") val type_: String = "ASSIGNATION_EVENT",
        val assignation: Int,
        val kind: String,
        val message: String? = null,
        val returns: Map<String, JsonElement?>? = null
)
