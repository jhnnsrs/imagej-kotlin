package com.mycompany.arkitekt

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A stand-in for the rekuest agent gateway, so the connection lifecycle can be driven for real.
 *
 * The live gateway currently refuses our registration for a server-side reason we cannot fix from
 * here, which would otherwise leave every path after REGISTER — INIT, SESSION_INIT, inquiry
 * reconciliation, heartbeats, KICK/BOUNCE, close codes — verified only by reading.
 *
 * [onConnection] is invoked per accepted socket with the connection index (0-based), so a test can
 * behave differently on a reconnect than on the first attempt.
 */
class FakeGateway(
        private val onConnection: suspend FakeGateway.(index: Int, session: WebSocketServerSession) -> Unit
) {
    /** Every text frame the agent sent us, in order, across all connections. */
    val received = CopyOnWriteArrayList<String>()

    /** Signalled once per accepted connection, so a test can wait for a reconnect. */
    val connections = Channel<Int>(Channel.UNLIMITED)

    private var server: EmbeddedServer<*, *>? = null
    private var connectionCount = 0

    var port: Int = 0
        private set

    /** Decode what the agent sent, ignoring frames a test does not care about. */
    fun receivedTypes(): List<String> =
            received.mapNotNull { text ->
                Regex("\"type\"\\s*:\\s*\"(\\w+)\"").find(text)?.groupValues?.get(1)
            }

    fun start() {
        val engine = embeddedServer(Netty, port = 0) {
            install(io.ktor.server.websocket.WebSockets)
            routing {
                webSocket("/agi") {
                    val index = connectionCount++
                    connections.send(index)
                    onConnection(this@FakeGateway, index, this)
                }
            }
        }
        engine.start(wait = false)
        port = runBlockingResolvePort(engine)
        server = engine
    }

    fun stop() {
        server?.stop(0, 0)
    }

    /** Read frames until [predicate] matches, recording everything seen. */
    suspend fun WebSocketServerSession.readUntil(predicate: (String) -> Boolean): String? {
        for (frame in incoming) {
            val text = (frame as? Frame.Text)?.readText() ?: continue
            received.add(text)
            if (predicate(text)) return text
        }
        return null
    }

    /** Keep draining (and recording) whatever the agent sends, until the socket ends. */
    suspend fun WebSocketServerSession.drain() {
        for (frame in incoming) {
            val text = (frame as? Frame.Text)?.readText() ?: continue
            received.add(text)
        }
    }

    suspend fun WebSocketServerSession.sendJson(json: String) = send(Frame.Text(json))

    private fun runBlockingResolvePort(engine: EmbeddedServer<*, *>): Int =
            kotlinx.coroutines.runBlocking {
                engine.engine.resolvedConnectors().first().port
            }
}

/** An alias pointing at a local fake gateway. */
fun localAlias(port: Int) =
        Alias(id = "fake", host = "127.0.0.1", port = port, ssl = false, challenge = "ht")

/** A TokenManager that hands out a fixed token and never talks to a server. */
fun fixedTokenManager(token: String = "test-token"): TokenManager {
    val endpoint = FaktsEndpoint(
            name = "test",
            version = "0.1.0",
            base_url = "http://localhost/lok/f/",
            frontend_url = "http://localhost/",
            configure = "http://localhost/configure/{code}",
            device_authorization_endpoint = "http://localhost/lok/o/app-authorization/",
            token_endpoint = "http://localhost/lok/o/token/",
            protocol_version = "2"
    )
    val fakts = ActiveFakts(
            self = SelfFakt("test", Alias(host = "localhost", ssl = false, challenge = "ht")),
            instances = emptyMap()
    )
    val fresh = TokenResponse(
            access_token = token,
            client_id = "cid",
            refresh_token = "rt",
            expires_in = 3600,
            received_at = System.currentTimeMillis()
    )
    return TokenManager(FaktsClient(), endpoint, fresh, fakts) { _, _, _ -> }
}

/** A minimal port definition; these tests exercise the socket, not the definition. */
fun testDefinition(key: String) =
        com.mycompany.rekuest.graphql.type.DefinitionInput(
                key = key,
                version = "0.1.0",
                name = key,
                kind = com.mycompany.rekuest.graphql.type.ActionKind.FUNCTION
        )
