package com.mycompany.arkitekt

import com.apollographql.apollo.api.ApolloRequest
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Error
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.api.http.HttpRequest
import com.apollographql.apollo.api.http.HttpResponse
import com.apollographql.apollo.interceptor.ApolloInterceptor
import com.apollographql.apollo.interceptor.ApolloInterceptorChain
import com.apollographql.apollo.network.http.HttpInterceptor
import com.apollographql.apollo.network.http.HttpInterceptorChain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Attaches the bearer token, pulled fresh from the [TokenManager] on every request.
 *
 * It takes a provider rather than a `String` because the token now expires and rotates: baking
 * one in at client-construction time would pin the client to a credential that dies within the
 * hour.
 */
class AuthorizationInterceptor(private val token: suspend () -> String) : HttpInterceptor {
    override suspend fun intercept(
            request: HttpRequest,
            chain: HttpInterceptorChain
    ): HttpResponse {
        return chain.proceed(
                request.newBuilder().addHeader("Authorization", "Bearer ${token()}").build()
        )
    }
}

/** The one `extensions.code` that a fresh token can do something about. */
private const val REFRESHABLE_CODE = "UNAUTHENTICATED"

/**
 * TRANSITIONAL: the auth failures of authentikate <= 3.0, which had no error codes.
 *
 * Kept only while services are being upgraded — an un-upgraded one answers with a bare message
 * and would otherwise never auto-refresh. Deliberately absent even here: the *permission*
 * failures (user-not-found, missing organization, blocked membership). Those authenticate fine
 * and are refused anyway, so a new token changes nothing.
 *
 * Delete this once every service is on authentikate >= 3.1.
 */
private val LEGACY_REFRESHABLE_MESSAGES = listOf(
        "token has expired",
        "token claims are invalid",
        "error decoding token",
        "error decoding token header",
        "missing kid in header",
        "no authorization header",
        "not a valid token"
)

/**
 * Whether a single GraphQL error says "re-authenticate and try again".
 *
 * authentikate >= 3.1 answers with a machine-readable `extensions.code` — the coarse category a
 * client is meant to branch on — plus a finer `reason`:
 *
 *     {"message": "The access token has expired.",
 *      "extensions": {"code": "UNAUTHENTICATED", "reason": "TOKEN_EXPIRED"}}
 *
 * `code` is the whole contract. `reason` exists so the backend can add failures without breaking
 * us, which only works if we never branch on it — so we don't.
 *
 *   UNAUTHENTICATED   — no usable credentials: refresh and retry.
 *   PERMISSION_DENIED — authenticated and refused anyway; a new token changes nothing.
 *   INTERNAL_ERROR    — a server fault, not a decision about our credentials.
 *   NOT_FOUND / VALIDATION_ERROR / … — ordinary domain errors.
 */
fun isRefreshableAuthError(error: Error): Boolean {
    val code = error.extensions?.get("code")
    if (code is String) {
        // A service that speaks codes is authoritative in BOTH directions: a PERMISSION_DENIED
        // carries a perfectly good token and must never be retried, so we must not fall through
        // to message matching and re-decide it on wording.
        return code == REFRESHABLE_CODE
    }
    val message = error.message.trim().lowercase()
    return LEGACY_REFRESHABLE_MESSAGES.any { message.contains(it) }
}

fun hasRefreshableAuthError(errors: List<Error>?): Boolean =
        errors?.any { isRefreshableAuthError(it) } ?: false

/**
 * On an `UNAUTHENTICATED` response, force a token refresh and replay the operation once.
 *
 * Bounded to a single retry per operation: if the replay is rejected too, the credentials are not
 * the problem and looping would only burn refresh tokens.
 */
class AuthRetryInterceptor(private val tokens: TokenManager) : ApolloInterceptor {

    override fun <D : Operation.Data> intercept(
            request: ApolloRequest<D>,
            chain: ApolloInterceptorChain
    ): Flow<ApolloResponse<D>> = flow {
        var retried = false
        chain.proceed(request).collect { response ->
            if (!retried && hasRefreshableAuthError(response.errors)) {
                retried = true
                val refreshed = try {
                    tokens.accessToken(forceRefresh = true)
                    true
                } catch (e: Exception) {
                    println("Forced token refresh failed; surfacing the original error: ${e.message}")
                    false
                }
                if (refreshed) {
                    // Re-entering the chain re-runs the HTTP interceptors, so
                    // AuthorizationInterceptor picks up the token we just rotated to.
                    chain.proceed(request.newBuilder().build()).collect { emit(it) }
                    return@collect
                }
            }
            emit(response)
        }
    }
}
