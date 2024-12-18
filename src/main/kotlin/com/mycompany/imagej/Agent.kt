import com.apollographql.apollo.api.Optional
import com.mycompany.imagej.*
import com.mycompany.rekuest.graphql.EnsureAgentMutation
import com.mycompany.rekuest.graphql.GetProvisionQuery
import com.mycompany.rekuest.graphql.SetExtensionTemplatesMutation
import com.mycompany.rekuest.graphql.type.AgentInput
import com.mycompany.rekuest.graphql.type.DefinitionInput
import com.mycompany.rekuest.graphql.type.SetExtensionTemplatesInput
import com.mycompany.rekuest.graphql.type.TemplateInput
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import io.ktor.serialization.kotlinx.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
// A registry that returns functions for a given template ID.
// Each function takes (App, String) where the second param is JSON args.




public class FunctionRegistry(
    private val functions: MutableMap<String, suspend (App, Map<String, JsonElement?>) -> Map<String, JsonElement?>> = mutableMapOf(),
    public val definitions: MutableMap<String, DefinitionInput> = mutableMapOf()
) {
    fun get_function(
        id: String
    ): (suspend (App, Map<String, JsonElement?>) -> Map<String, JsonElement?>)? = functions[id]


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
        private val config: RekuestFakt,
        private val token: String,
        private val registry: FunctionRegistry,
        private val app: App,
        private val instanceId: String
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun createAgent(
            name: String,
            extensions: List<String>
    ): Result<Unit> {
        return kotlin.runCatching {
            val request =
                    EnsureAgentMutation(
                            AgentInput(
                                    instanceId = instanceId,
                                    name = Optional.present(name),
                                    extensions = Optional.present(extensions)
                            )
                    )
            app.rekuest.getClient().mutation(request).execute().data
        }
    }

    suspend fun registerFunctions() {
        var templateInputs = registry.definitions.entries.map {
            (id, definition ) ->
            TemplateInput(
                    `interface` = id,
                    definition = definition,
                    dependencies = listOf()
                )
            }


        var mutation = SetExtensionTemplatesMutation(
                SetExtensionTemplatesInput(
                    instanceId = instanceId,
                    templates = templateInputs,
                    extension = "default",
                    runCleanup = Optional.present(true)
                )
            )


        app.rekuest.getClient().mutation(mutation).execute().data
    }

    suspend fun provideForever(): Result<String> {
        return coroutineScope {
            val messageChannel = Channel<Any>(capacity = 100)

            val wsClient = HttpClient {
                install(WebSockets) {
                    contentConverter = KotlinxWebsocketSerializationConverter(Json {
                        encodeDefaults = true
                    }
                    )
                }
            }

            // Connect to the endpoint
            try {
                val session = wsClient.webSocketSession { url(config.agent.endpoint_url) }
                // Launch a sender coroutine
                val senderJob = launch {
                    // Send initial message
                    val init = InitialAgentMessage(instance_id = "default", token = token)
                    session.sendSerialized(init)

                    // Continuously send any queued messages
                    for (msg in messageChannel) {
                        println("Sending message: $msg")
                        session.sendSerialized(msg)
                    }
                }

                // Launch a receiver coroutine
                val receiverJob = launch {
                    for (frame in session.incoming) {
                        frame as? Frame.Text ?: continue
                        val text = frame.readText()
                        println("Received message: $text")

                        val msg =
                                runCatching {
                                    json.decodeFromString(AgentMessage.serializer(), text)
                                }
                                        .getOrElse {
                                            println("Failed to deserialize message: $it $text")
                                        }

                        when (msg) {
                            is AgentMessage.Heartbeat -> {
                                val heartbeatResponse = HeartbeatResponseMessage("HEARTBEAT")
                                println(heartbeatResponse.type)
                                messageChannel.send(heartbeatResponse)
                                println("Received heartbeat")
                            }
                            is AgentMessage.Initial -> {
                                println("Received initial message: ${msg.instance_id}")
                            }
                            is AgentMessage.Assign -> {
                                println("Received assignment: ${msg.provision}")
                                // Fetch provision details
                                val getProvisionQuery = GetProvisionQuery(id = msg.provision.toString())
                                val response =
                                        app.rekuest.getClient().query(getProvisionQuery).execute()
                                val template = response.data?.provision?.template?.`interface`

                                if (template == null) {
                                    throw Exception("Template does not exist")
                                }

                                val func = registry.get_function(template)

                                if (func != null) {
                                    try {
                                        val returns = func(app, msg.args ?: mapOf())
                                        // Send a YIELD event
                                        val eventYield =
                                            AssignationEventMessage(
                                                assignation = msg.assignation,
                                                kind = "YIELD",
                                                returns = returns
                                            )
                                        messageChannel.send(
                                            eventYield
                                        )

                                        // Send a DONE event
                                        val eventDone =
                                            AssignationEventMessage(
                                                assignation = msg.assignation,
                                                kind = "DONE"
                                            )
                                        messageChannel.send(eventDone)
                                    }
                                    catch (e: Exception) {
                                        val eventError = AssignationEventMessage(
                                            assignation = msg.assignation,
                                            kind = "CRITICAL",
                                            message = e.message.toString(),

                                        )

                                        messageChannel.send(eventError)
                                    }
                                } else {
                                    println("Function not found: $template")
                                }
                            }
                            is AgentMessage.Provide -> {
                                println("Received provision: ${msg.provision}")
                            }
                            is AgentMessage.Unprovide -> {
                                println("Received unprovide")
                            }
                            is AgentMessage.Error -> {
                                println("Received error: ${msg.code}")
                            }
                        }
                    }
                }

                try {
                    receiverJob.join()
                    senderJob.cancelAndJoin()
                } catch (e: Exception) {} finally {
                    messageChannel.close()
                    session.close()
                    wsClient.close()
                }
            } catch (e: Exception) {
                println("Connection closed")
            }

            Result.success("Connection closed")
        }
    }
}
