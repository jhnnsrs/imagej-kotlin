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
        private val alias: Alias,
        private val tokens: TokenManager,
        private val registry: FunctionRegistry,
        private val app: App,
        /**
         * How long to let a live incumbent's lease go stale before trying again.
         *
         * The server frees the lease once the incumbent's `last_seen` ages past
         * `AGENT_STALE_AFTER` (3 x its 10s heartbeat), so waiting is what makes a 4004 recoverable
         * without force-kicking a healthy peer. A constructor parameter only so tests can drive
         * that path without actually waiting half a minute.
         */
        private val staleTakeoverWaitSeconds: Long = 35,
        /**
         * How long a connection must stand up to count as a recovery, refunding the retry budget
         * and re-arming the wait-out path above.
         */
        private val budgetResetAfterSeconds: Double = 30.0
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

    // SESSION_INIT announces the *process*, not the socket, so it is sent once and never
    // repeated on a reconnect.
    private var sessionAnnounced = false

    /** What one connection attempt ended up telling us. */
    private data class Attempt(
            /** The server's close code, or null for a transport-level drop that carried none. */
            val closeCode: Int?,
            /** Did INIT arrive? The only proof this connection was ever accepted. */
            val registered: Boolean,
            /** KICK: a clean, deliberate stop rather than a failure. */
            val kicked: Boolean,
            /** BOUNCE's hint for how long to wait before coming back. */
            val bounceDelaySeconds: Int?,
            /** The last PROTOCOL_ERROR or exception text — often the only human-readable detail. */
            val detail: String?,
            /** How long the connection stood up, in seconds. */
            val upSeconds: Double
    )

    // Backoff mirrors rekuest-next's ConnectionPolicy (agents/policy.py): 1s doubling to a 60s
    // cap, +/-10% jitter so a fleet of agents does not reconnect in lockstep after a restart.
    private val backoffInitialSeconds = 1.0
    private val backoffMaxSeconds = 60.0
    private val backoffJitter = 0.1
    private val maxRetries = 5

    private fun backoffDelayFor(attempt: Int): Long {
        val raw = minOf(backoffInitialSeconds * Math.pow(2.0, attempt.toDouble()), backoffMaxSeconds)
        val jittered = raw + raw * (Math.random() * 2 - 1) * backoffJitter
        return maxOf(0L, (jittered * 1000).toLong())
    }

    /**
     * Stay connected to the agent gateway, reconnecting when the socket drops.
     *
     * The access token expires now, and a socket carries the token it opened with — so an
     * expired-token close can only be recovered by reconnecting with a fresh one, never by
     * refreshing in place.
     *
     * What we do on a close is driven by the server's close code (see [classifyClose]), not by a
     * guess: some closes mean "come back", and at least one — being displaced by another
     * connection — means "do not", because reconnecting would displace the incumbent right back
     * and turn two instances into a mutual-eviction loop.
     */
    suspend fun provideForever(): Result<String> {
        // Minted ONCE, outside the loop. The backend reads session_id as "is this the same
        // process?": presenting the same one lets a reconnect reclaim its in-flight tasks, while a
        // fresh one tells the backend the process died and makes it fail-and-cascade that work.
        val sessionId = java.util.UUID.randomUUID().toString()

        var retry = 0
        var waitedOutIncumbent = false

        while (true) {
            val attempt = connectOnce(sessionId)
            val detail = attempt.detail?.let { " ($it)" } ?: ""

            if (attempt.kicked) {
                println("Agent stopped: the server kicked us$detail")
                return Result.success("Agent stopped by the server.")
            }

            // A connection that stood up long enough counts as a recovery: it refunds the retry
            // budget and re-arms the wait-out-the-incumbent path, which otherwise would only ever
            // work once per process (a long-lived agent displaced hours later would give up
            // immediately instead of waiting for the new holder's lease to go stale).
            if (attempt.upSeconds >= budgetResetAfterSeconds) {
                retry = 0
                waitedOutIncumbent = false
            }

            val verdict = classifyClose(attempt.closeCode, attempt.registered)

            when (verdict.action) {
                CloseAction.STOP -> {
                    val why = "${verdict.why}$detail"
                    println("Agent stopped: $why")
                    return Result.failure(IllegalStateException("Agent stopped: $why"))
                }

                CloseAction.WAIT_AND_RETRY -> {
                    if (waitedOutIncumbent) {
                        val why = "${verdict.why}$detail — it is still there after waiting"
                        println("Agent stopped: $why")
                        return Result.failure(IllegalStateException("Agent stopped: $why"))
                    }
                    waitedOutIncumbent = true
                    println(
                            "${verdict.why}$detail; waiting ${staleTakeoverWaitSeconds}s for its " +
                                    "lease to go stale, then trying once more."
                    )
                    delay(staleTakeoverWaitSeconds * 1000)
                }

                CloseAction.RECONNECT -> {
                    if (retry >= maxRetries) {
                        val why = "gave up after $maxRetries reconnect attempts (${verdict.why})$detail"
                        println("Agent stopped: $why")
                        return Result.failure(IllegalStateException("Agent stopped: $why"))
                    }
                    // A BOUNCE is the server asking for a soft restart and may name its own delay.
                    val delayMs =
                            attempt.bounceDelaySeconds?.let { it * 1000L } ?: backoffDelayFor(retry)
                    retry += 1
                    println(
                            "Agent connection closed (${verdict.why})$detail; reconnecting in " +
                                    "${delayMs / 1000.0}s."
                    )
                    delay(delayMs)
                }
            }
        }
    }

    /** One connection attempt, from opening the socket to it closing. */
    private suspend fun connectOnce(sessionId: String): Attempt {
        return coroutineScope {
            val outbound = Channel<AgentEvent>(capacity = 100)
            // Running tasks keyed by task id, so CANCEL/INTERRUPT can stop them, and so a
            // redelivered ASSIGN for a task already running can be recognised and dropped.
            val runningJobs = ConcurrentHashMap<String, Job>()
            // KICK means "do not come back"; a bounce or a plain close means reconnect.
            var kicked = false
            // INIT is the server's acceptance of our REGISTER, and the only proof this
            // connection ever worked.
            var registered = false
            var bounceDelaySeconds: Int? = null
            var detail: String? = null
            var closeCode: Int? = null
            // Set once the socket is actually up, so a slow TLS handshake cannot earn uptime
            // toward the retry-budget refund.
            var connectedAt: Long? = null

            val wsClient = HttpClient { install(WebSockets) }

            try {
                val session = wsClient.webSocketSession { url(alias.to_ws_path("agi")) }
                connectedAt = System.nanoTime()

                suspend fun send(event: AgentEvent) {
                    session.send(Frame.Text(agentJson.encodeToString(AgentEvent.serializer(), event)))
                }

                // Sender: REGISTER first, then drain queued events. The token is pulled at
                // connect time so a reconnect after an expiry carries the refreshed one.
                val senderJob = launch {
                    send(
                            AgentEvent.Register(
                                    token = tokens.accessToken(),
                                    force = false,
                                    sessionId = sessionId
                            )
                    )
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
                            // Answered on the session directly, never through `outbound`: the
                            // server pings every 10s and closes the socket if the answer takes
                            // more than 5s, so liveness must not queue behind a large YIELD (or
                            // behind a full channel, which would block this very loop).
                            is AgentMessage.Heartbeat -> send(AgentEvent.HeartbeatAnswer())

                            is AgentMessage.Init -> {
                                registered = true
                                println(
                                        "Agent registered as ${msg.agent}; pending inquiries=${msg.inquiries.size}"
                                )

                                // Announce the run once per process. The backend opens a Session
                                // row from this; it belongs to the process, not the socket, so a
                                // reconnect does not repeat it.
                                if (!sessionAnnounced) {
                                    sessionAnnounced = true
                                    send(AgentEvent.SessionInit(sessionId = sessionId))
                                }

                                reportsForInquiries(msg.inquiries).forEach { send(it) }
                            }
                            is AgentMessage.Assign -> {
                                // Assign delivery is at-least-once, so the same task can arrive
                                // twice. Running it twice would repeat the side effect (an upload,
                                // say) — the server only dedups the resulting *report*.
                                if (runningJobs.containsKey(msg.task)) {
                                    println("Ignoring duplicate ASSIGN for task ${msg.task}")
                                    continue
                                }
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
                                kicked = true
                                break
                            }
                            is AgentMessage.Bounce -> {
                                println("Server requested reconnect (bounce)")
                                bounceDelaySeconds = msg.duration
                                break
                            }
                            // We sent something the backend could not process. Keep it as this
                            // connection's failure reason: a close usually follows, and this text
                            // is the only human-readable detail behind an otherwise opaque code.
                            is AgentMessage.ProtocolError -> {
                                detail = msg.error
                                println("Protocol error: ${msg.error}")
                            }
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
                    // Close FIRST, then read the reason. `closeReason` only completes once the
                    // session is closed, and on the paths where *we* leave — KICK and BOUNCE both
                    // arrive as messages that the server does not follow with a close — awaiting
                    // it before closing would wait for something we are about to cause. The
                    // timeout covers the other direction: a half-open socket may never deliver a
                    // close frame at all, and that must not wedge the reconnect loop.
                    session.close()
                    closeCode = withTimeoutOrNull(2_000) { session.closeReason.await() }
                            ?.code
                            ?.toInt()
                    wsClient.close()
                }
            } catch (e: Exception) {
                // A PROTOCOL_ERROR already read off the wire is more specific than the exception
                // that follows it, so don't overwrite one with the other.
                if (detail == null) detail = e.message
                println("Connection closed: ${e.message}")
            }

            Attempt(
                    closeCode = closeCode,
                    registered = registered,
                    kicked = kicked,
                    bounceDelaySeconds = bounceDelaySeconds,
                    detail = detail,
                    upSeconds = connectedAt?.let { (System.nanoTime() - it) / 1_000_000_000.0 } ?: 0.0
            )
        }
    }
}

/**
 * What to report for the tasks INIT hands back as still open.
 *
 * The server sends these to a reconnect that presented the same `session_id`, expecting us to
 * either carry on running them or say how they ended — there is no reply message type, so silence
 * leaves them in-flight until a server-side sweep resolves them.
 *
 * Every one of our jobs is cancelled when a connection drops, so by the time INIT arrives they are
 * all genuinely dead and the honest answer is always CRITICAL. (rekuest-next can answer PROGRESS
 * here instead, because its actors outlive the socket; ours do not.)
 */
fun reportsForInquiries(inquiries: List<AssignInquiry>): List<AgentEvent.Critical> =
        inquiries.map {
            AgentEvent.Critical(
                    it.task,
                    "The agent reconnected; this task did not survive the disconnect."
            )
        }
