package com.mycompany.arkitekt

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The refresh token rotates on every use, so two concurrent refreshes kill the session
 * permanently. These pin the coalescing rule — which is invisible when everything works.
 */
class TokenManagerTest {

    /** A FaktsClient whose refreshes block until the test releases them, one gate per call. */
    private class GatedFaktsClient : FaktsClient() {
        val gates = CopyOnWriteArrayList<CompletableDeferred<Unit>>()
        private val calls = AtomicInteger(0)

        val callCount: Int
            get() = calls.get()

        override suspend fun refresh(tokenEndpoint: String, current: TokenResponse): RefreshResult {
            val gate = CompletableDeferred<Unit>()
            gates.add(gate)
            val n = calls.incrementAndGet()
            gate.await()
            return RefreshResult(
                    current.copy(
                            access_token = "token-$n",
                            refresh_token = "rt-$n",
                            received_at = System.currentTimeMillis()
                    ),
                    null
            )
        }

        fun release(index: Int) {
            gates[index].complete(Unit)
        }
    }

    private val endpoint = FaktsEndpoint(
            name = "test",
            version = "0.1.0",
            base_url = "http://localhost/lok/f/",
            frontend_url = "http://localhost/",
            configure = "http://localhost/configure/{code}",
            device_authorization_endpoint = "http://localhost/lok/o/app-authorization/",
            token_endpoint = "http://localhost/lok/o/token/",
            protocol_version = "2"
    )

    private val fakts = ActiveFakts(
            self = SelfFakt("test", Alias(host = "localhost", ssl = false, challenge = "ht")),
            instances = emptyMap()
    )

    /** An already-expired token, so every accessToken() call takes the refresh path. */
    private fun expiredToken() = TokenResponse(
            access_token = "stale",
            client_id = "cid",
            refresh_token = "rt-0",
            expires_in = 60,
            received_at = System.currentTimeMillis() - 120_000
    )

    private fun manager(client: FaktsClient, persisted: MutableList<TokenResponse>) =
            TokenManager(client, endpoint, expiredToken(), fakts) { _, _, token ->
                persisted.add(token)
            }

    private suspend fun awaitCalls(client: GatedFaktsClient, n: Int) {
        withTimeout(5_000) {
            while (client.callCount < n) delay(5)
        }
    }

    @Test
    fun `concurrent non-forced refreshes coalesce into one round-trip`() = runBlocking {
        val client = GatedFaktsClient()
        val persisted = mutableListOf<TokenResponse>()
        val tokens = manager(client, persisted)

        val a = async { tokens.accessToken() }
        val b = async { tokens.accessToken() }
        awaitCalls(client, 1)
        // Give a second refresh every chance to start before asserting it did not.
        delay(100)
        assertEquals(1, client.callCount)

        client.release(0)
        assertEquals("token-1", a.await())
        assertEquals("token-1", b.await())
        // The rotated refresh token is persisted before either caller gets the access token.
        assertEquals(listOf("rt-1"), persisted.map { it.refresh_token })
    }

    @Test
    fun `a forced refresh never settles for a raced non-forced one`() = runBlocking {
        val client = GatedFaktsClient()
        val tokens = manager(client, mutableListOf())

        // A non-forced refresh is in flight...
        val relaxed = async { tokens.accessToken() }
        awaitCalls(client, 1)

        // ...and now the server rejects the token we hold. Joining the in-flight refresh would
        // hand back the very credential this caller is trying to get past.
        val forced = async { tokens.accessToken(forceRefresh = true) }
        delay(100)
        assertEquals(1, client.callCount, "the forced caller must wait, not start a second refresh yet")

        client.release(0)
        assertEquals("token-1", relaxed.await())

        // Having waited the raced one out, it now runs a genuinely forced refresh.
        awaitCalls(client, 2)
        client.release(1)
        assertEquals("token-2", forced.await())
    }

    @Test
    fun `concurrent forced refreshes coalesce - one rejection storm is one round-trip`() =
            runBlocking {
                val client = GatedFaktsClient()
                val tokens = manager(client, mutableListOf())

                val a = async { tokens.accessToken(forceRefresh = true) }
                awaitCalls(client, 1)
                val b = async { tokens.accessToken(forceRefresh = true) }
                delay(100)
                assertEquals(1, client.callCount)

                client.release(0)
                assertEquals("token-1", a.await())
                assertEquals("token-1", b.await())
            }

    @Test
    fun `the fast path is disabled while a forced refresh is in flight`() = runBlocking {
        val client = GatedFaktsClient()
        // Start from a token that still looks perfectly fresh.
        val fresh = TokenResponse(
                access_token = "fresh",
                client_id = "cid",
                refresh_token = "rt-0",
                expires_in = 3600,
                received_at = System.currentTimeMillis()
        )
        val tokens = TokenManager(client, endpoint, fresh, fakts) { _, _, _ -> }

        val forced = async { tokens.accessToken(forceRefresh = true) }
        awaitCalls(client, 1)

        // A forced refresh runs precisely because the server rejected that token, so how fresh
        // the clock says it is means nothing — a relaxed caller must not be handed it.
        val relaxed = async { tokens.accessToken() }
        delay(100)

        client.release(0)
        assertEquals("token-1", forced.await())
        assertEquals("token-1", relaxed.await())
        assertTrue(client.callCount <= 2, "expected at most one extra refresh, got ${client.callCount}")
    }

    @Test
    fun `a refresh with no envelope keeps the config already held`() = runBlocking {
        val client = GatedFaktsClient() // its RefreshResult always carries fakts = null
        val tokens = manager(client, mutableListOf())

        val call = async { tokens.accessToken() }
        awaitCalls(client, 1)
        client.release(0)
        call.await()

        assertEquals("test", tokens.currentFakts.self.deployment_name)
    }
}
