package com.mycompany.arkitekt

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single owner of the access token.
 *
 * Under fakts protocol 2 there is no `client_secret` any more, so nothing can independently
 * re-mint a token — and the refresh token *rotates on every use*. If two consumers refresh
 * concurrently, the second presents a refresh token the first already burned, the server rejects
 * it, and the session dies permanently (the user has to approve in a browser again).
 *
 * So every consumer — the three Apollo clients and the agent WebSocket — pulls its token from
 * here, and refreshes are serialized and coalesced.
 */
class TokenManager(
        private val client: FaktsClient,
        endpoint: FaktsEndpoint,
        token: TokenResponse,
        fakts: ActiveFakts,
        /**
         * Persist the rotated session. Called while still holding the refresh, *before* the new
         * access token is handed to any caller: the refresh token is the only credential that
         * survives a restart, so losing the rotated one costs the user a browser round-trip.
         */
        private val persist: (FaktsEndpoint, ActiveFakts, TokenResponse) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lock = Mutex()

    @Volatile private var endpointState: FaktsEndpoint = endpoint
    @Volatile private var tokenState: TokenResponse = token
    @Volatile private var faktsState: ActiveFakts = fakts

    /** The in-flight refresh, if any, and whether it was FORCED. */
    private var inFlight: CompletableDeferred<TokenResponse>? = null
    private var inFlightForced: Boolean = false

    val currentToken: TokenResponse
        get() = tokenState

    /** The most recent envelope. Refresh re-renders it, so this can change under a caller. */
    val currentFakts: ActiveFakts
        get() = faktsState

    /**
     * The access token to put on the wire, refreshing first if it is at or near expiry.
     *
     * Pass [forceRefresh] when the server has just *rejected* the token we hold (see
     * [AuthRetryInterceptor]) — how fresh the clock says it is means nothing then.
     */
    suspend fun accessToken(forceRefresh: Boolean = false): String {
        val cached = lock.withLock {
            val token = tokenState
            // A forced refresh is running precisely because the token was rejected, so the
            // "still looks fresh" fast path must be disabled while one is in flight — otherwise
            // it hands the rejected token to every concurrent caller and defeats the rotation.
            val forcedInFlight = inFlight != null && inFlightForced
            if (!forceRefresh && !forcedInFlight && !shouldRefreshToken(token)) token else null
        }
        if (cached != null) return cached.access_token
        return rotate(forceRefresh).access_token
    }

    /**
     * Refresh once, however many callers ask — EXCEPT that a FORCED refresh must never settle for
     * an in-flight NON-forced one.
     *
     * The non-forced path is allowed to hand back the cached token, which is by definition the
     * one the server just rejected, so joining a raced non-forced refresh would return the very
     * credential the caller is trying to get past. It waits that one out and then runs a
     * genuinely forced refresh instead. Forced-into-forced still coalesces — one rejection storm
     * is one round-trip.
     */
    private suspend fun rotate(forceRefresh: Boolean): TokenResponse {
        // Bounded so a pathological stream of raced non-forced refreshes cannot spin forever.
        repeat(4) {
            var joinable = true
            val pending = lock.withLock {
                val running = inFlight
                if (running != null) {
                    joinable = !forceRefresh || inFlightForced
                    running
                } else {
                    val fresh = CompletableDeferred<TokenResponse>()
                    inFlight = fresh
                    inFlightForced = forceRefresh
                    start(fresh)
                    fresh
                }
            }

            if (joinable) return pending.await()

            // Wait the raced non-forced refresh out — success or failure — then loop and run a
            // genuinely forced one.
            try {
                pending.await()
            } catch (e: Exception) {
                // Its failure is not ours to report; we are about to try again, forced.
            }
        }
        throw TokenError(null, "Could not obtain a forced token refresh; too many racing refreshes.")
    }

    private fun start(deferred: CompletableDeferred<TokenResponse>) {
        scope.launch {
            try {
                val token = doRefresh()
                clearInFlight(deferred)
                deferred.complete(token)
            } catch (e: Throwable) {
                clearInFlight(deferred)
                deferred.completeExceptionally(e)
            }
        }
    }

    // Cleared *before* the deferred completes, so a caller that was waiting out a raced
    // non-forced refresh finds no in-flight refresh when it loops.
    private suspend fun clearInFlight(deferred: CompletableDeferred<TokenResponse>) {
        lock.withLock {
            if (inFlight === deferred) {
                inFlight = null
                inFlightForced = false
            }
        }
    }

    private suspend fun doRefresh(): TokenResponse {
        val current = tokenState
        val result = client.refresh(endpointState.token_endpoint, current)

        // A refresh may legitimately arrive without an envelope: the server appends it
        // best-effort and returns the plain token rather than failing the grant when rendering
        // the instances throws. Keep the config we already hold and pick up a re-render next time.
        val fakts = result.fakts ?: faktsState

        tokenState = result.token
        faktsState = fakts
        persist(endpointState, fakts, result.token)

        return result.token
    }
}
