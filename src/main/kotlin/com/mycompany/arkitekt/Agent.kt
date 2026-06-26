package com.mycompany.arkitekt

import com.apollographql.apollo.api.Optional
import com.mycompany.rekuest.graphql.EnsureAgentMutation
import com.mycompany.rekuest.graphql.ImplementAgentMutation
import com.mycompany.rekuest.graphql.type.AgentInput
import com.mycompany.rekuest.graphql.type.DefinitionInput
import com.mycompany.rekuest.graphql.type.ImplementAgentInput
import com.mycompany.rekuest.graphql.type.ImplementationInput
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap

// A registry mapping an interface name -> (implementation function, its DefinitionInput).
// Each function takes (App, args) and returns the yielded values.
public class FunctionRegistry(
    private val functions: MutableMap<String, suspend (App, Map<String, JsonElement?>) -> Map<String, JsonElement?>> = mutableMapOf(),
    public val definitions: MutableMap<String, DefinitionInput> = mutableMapOf()
) {
    fun get_function(
        function_name: String
    ): (suspend (App, Map<String, JsonElement?>) -> Map<String, JsonElement?>)? = functions[function_name]


    fun register_function(
        at: String,
        definitionInput: DefinitionInput,
        function: suspend (App, Map<String, JsonElement?>) -> Map<String, JsonElement?>
    ) {
        functions[at] = function
        definitions[at] = definitionInput
    }
}

public class Agent(
        private val client: Rekuest,
        private val alias: Alias,
        private val token: String,
        private val registry: FunctionRegistry,
        private val app: App
) {

    // Register (or look up) the agent record. The agent is identified by the token + name;
    // there is no instanceId in the current protocol.
    suspend fun createAgent(name: String): Result<Unit> {
        return kotlin.runCatching {
            val request = EnsureAgentMutation(AgentInput(name = Optional.present(name)))
            app.rekuest.getClient().mutation(request).execute().data
        }
    }

    // Advertise all registered implementations to the server in a single implementAgent call.
    suspend fun registerFunctions() {
        val implementations =
                registry.definitions.entries.map { (functionName, definition) ->
                    ImplementationInput(
                            definition = definition,
                            `interface` = Optional.present(functionName)
                    )
                }

        val mutation =
                ImplementAgentMutation(
                        ImplementAgentInput(implementations = Optional.present(implementations))
                )

        val response = app.rekuest.getClient().mutation(mutation).execute()
        println("implementAgent -> ${response.data}")
    }

    suspend fun provideForever(): Result<String> {
        return coroutineScope {
            val outbound = Channel<AgentEvent>(capacity = 100)
            // Running tasks keyed by task id, so CANCEL/INTERRUPT can stop them.
            val runningJobs = ConcurrentHashMap<String, Job>()
            // Per-process session id: a fresh value tells the backend this is a fresh process.
            val sessionId = java.util.UUID.randomUUID().toString()

            val wsClient = HttpClient { install(WebSockets) }

            try {
                val session = wsClient.webSocketSession { url(alias.to_ws_path("agi")) }

                suspend fun send(event: AgentEvent) {
                    session.send(Frame.Text(agentJson.encodeToString(AgentEvent.serializer(), event)))
                }

                // Sender: REGISTER first, then drain queued events.
                val senderJob = launch {
                    send(AgentEvent.Register(token = token, force = false, sessionId = sessionId))
                    for (event in outbound) {
                        send(event)
                    }
                }

                // Receiver: dispatch inbound messages.
                val receiverJob = launch {
                    for (frame in session.incoming) {
                        val text = (frame as? Frame.Text)?.readText() ?: continue

                        val msg =
                                runCatching {
                                            agentJson.decodeFromString(AgentMessage.serializer(), text)
                                        }
                                        .getOrElse {
                                            println("Ignoring undecodable message: $it -- $text")
                                            null
                                        }
                                        ?: continue

                        when (msg) {
                            is AgentMessage.Heartbeat -> outbound.send(AgentEvent.HeartbeatAnswer())
                            is AgentMessage.Init ->
                                    println(
                                            "Agent registered as ${msg.agent}; pending inquiries=${msg.inquiries.size}"
                                    )
                            is AgentMessage.Assign -> {
                                val func = registry.get_function(msg.functionName)
                                if (func == null) {
                                    println("No implementation for interface '${msg.functionName}'")
                                    outbound.send(
                                            AgentEvent.Critical(
                                                    msg.task,
                                                    "No implementation for '${msg.functionName}'"
                                            )
                                    )
                                    continue
                                }
                                val job =
                                        launch(Dispatchers.IO) {
                                            try {
                                                outbound.send(AgentEvent.Started(msg.task))
                                                val returns = func(app, msg.args)
                                                outbound.send(AgentEvent.Yield(msg.task, returns))
                                                outbound.send(AgentEvent.Completed(msg.task))
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                outbound.send(
                                                        AgentEvent.Critical(
                                                                msg.task,
                                                                e.message ?: e.toString()
                                                        )
                                                )
                                            } finally {
                                                runningJobs.remove(msg.task)
                                            }
                                        }
                                runningJobs[msg.task] = job
                            }
                            is AgentMessage.Cancel -> {
                                runningJobs.remove(msg.task)?.cancel()
                                outbound.send(AgentEvent.Cancelled(msg.task))
                            }
                            is AgentMessage.Interrupt -> {
                                runningJobs.remove(msg.task)?.cancel()
                                outbound.send(AgentEvent.Interrupted(msg.task))
                            }
                            // Plain functions can't truly suspend mid-run; acknowledge to stay
                            // protocol-compliant.
                            is AgentMessage.Pause -> outbound.send(AgentEvent.Paused(msg.task))
                            is AgentMessage.Resume -> outbound.send(AgentEvent.Resumed(msg.task))
                            // We persist nothing, so the durability ack is informational only.
                            is AgentMessage.EventAck -> {}
                            is AgentMessage.Kick -> {
                                println("Kicked by server: ${msg.reason}")
                                break
                            }
                            is AgentMessage.Bounce -> {
                                println("Server requested reconnect (bounce)")
                                break
                            }
                            is AgentMessage.ProtocolError -> println("Protocol error: ${msg.error}")
                        }
                    }
                }

                try {
                    receiverJob.join()
                    senderJob.cancelAndJoin()
                } catch (e: Exception) {
                    println("Closed with exception $e")
                } finally {
                    runningJobs.values.forEach { it.cancel() }
                    outbound.close()
                    session.close()
                    wsClient.close()
                }
            } catch (e: Exception) {
                println("Connection closed: ${e.message}")
            }

            Result.success("Connection closed")
        }
    }
}
