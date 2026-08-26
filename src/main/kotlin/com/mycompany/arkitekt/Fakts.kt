package com.mycompany.arkitekt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

// =========================================================================================
// fakts protocol 2 (OAuth2-native)
//
// Two round-trips instead of protocol 1's four:
//
//   1. discover           GET  {url}/.well-known/fakts        -> FaktsEndpoint
//   2. device authorize   POST {device_authorization_endpoint} -> DeviceAuthorization
//      (human approves at verification_uri_complete)
//   3. poll               POST {token_endpoint}                -> TokenResponse + ActiveFakts
//
// There is no `claim` step: the successful token response IS the claim — the standard OAuth2
// members and the fakts envelope (`self`/`instances`/`statuses`) are top-level siblings in one
// flat JSON object. And there is no `auth` block with client credentials any more: the
// *rotating* refresh token is the only credential that survives, which is why every token
// acquisition has to go through the single TokenManager (see TokenManager.kt).
// =========================================================================================

// Shared lenient JSON for all fakts payloads. `ignoreUnknownKeys` is load-bearing: a real
// discovery document also carries mesh_*/hub_* members we deliberately ignore, and the flat
// grant response is decoded twice (once as a token, once as an envelope), each pass ignoring
// the other's fields. `encodeDefaults` governs *requests* too — a defaulted property here goes
// on the wire, so only declare request fields we actually mean to send.
val faktsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

// ---- Manifest (the app's identity card) -------------------------------------------------

@Serializable
data class Requirement(
        val key: String,
        val service: String,
        val optional: Boolean = false,
        val description: String? = null
)

@Serializable
data class Manifest(
        val identifier: String,
        val version: String = "1.0",
        val scopes: List<String> = listOf("openid"),
        val requirements: List<Requirement> = emptyList(),
        val logo: String? = null,
        val description: String? = null,
        val node_id: String? = null,
        // NOTE: the server's element type is {kind: "github"|"website", url}, not a bare string.
        // Always empty here, so `encodeDefaults` writes a harmless []; populating it as strings
        // would earn an `invalid_request`.
        val public_sources: List<String> = emptyList()
)

// ---- 1. Discovery: GET {url}/.well-known/fakts -------------------------------------------

@Serializable
data class FaktsEndpoint(
        val name: String,
        val version: String,
        val base_url: String,
        val frontend_url: String,
        // Configure-page template carrying a literal `{code}`. Parsed for completeness only —
        // the approval URL we open is `verification_uri_complete` from the device-auth response.
        val configure: String,
        // RFC 8628 device authorization + fakts' dynamic client registration.
        val device_authorization_endpoint: String,
        // The OAuth2 token endpoint: device-code poll, then refresh.
        val token_endpoint: String,
        val protocol_version: String? = null,
        val description: String? = null,
        val issuer: String? = null,
        val jwks_uri: String? = null,
        val grant_types_supported: List<String> = emptyList(),
        val token_endpoint_auth_methods_supported: List<String> = emptyList()
) {
    // The one and only use of `base_url`; every other endpoint arrives fully qualified.
    val report_endpoint: String
        get() = base_url.trimEnd('/') + "/report/"
}

// ---- 2. Device authorization -------------------------------------------------------------

// Only the fields we mean to send: `encodeDefaults` would put any other declared default on the
// wire. The server also accepts `expiration_time_seconds` (clamped to 900), `redirect_uris`,
// `requested_client_role` (interface|agent, default interface), `request_public` and
// `supported_layers` — all left to their server-side defaults.
@Serializable
data class DeviceAuthorizationRequest(
        val manifest: Manifest,
        val requested_client_kind: String = "desktop"
)

@Serializable
data class DeviceAuthorization(
        val status: String,
        // Full-entropy polling secret — never shown to the user.
        val device_code: String,
        // Short, human-transcribable code; what the configure URL carries.
        val user_code: String? = null,
        val client_id: String,
        // Overrides the discovery document's token_endpoint.
        val token_endpoint: String? = null,
        // Still contains the literal `{code}`; only ..._complete is substituted.
        val verification_uri: String? = null,
        val verification_uri_complete: String? = null,
        val expires_in: Int = 300,
        val interval: Int = 5
)

// The error envelope both the device-auth endpoint and the token endpoint answer with. The
// throttler answers HTTP 429 with `{"error": "slow_down"}` and no `status` key, so every member
// is optional.
@Serializable
data class FaktsErrorBody(
        val status: String? = null,
        val error: String? = null,
        val error_description: String? = null,
        val message: String? = null
) {
    fun describe(): String = error_description ?: message ?: error ?: status ?: "unknown error"
}

// ---- 3. Token --------------------------------------------------------------------------

@Serializable
data class TokenResponse(
        val access_token: String,
        val token_type: String = "Bearer",
        // The public OAuth2 client minted for us at device authorization. With no `auth` block
        // left in the config, this is the only surviving record of the client identity that the
        // refresh grant needs.
        val client_id: String,
        val expires_in: Int? = null,
        val scope: String? = null,
        val refresh_token: String? = null,
        // Client-synthesized at split time, never on the wire. If this is null the expiry math
        // cannot run and the token would never be refreshed.
        val received_at: Long? = null
)

const val DEVICE_CODE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"

// Refresh 60s before the nominal expiry, so an in-flight request never carries a token that
// expires mid-round-trip.
const val TOKEN_REFRESH_SKEW_MS = 60_000L

fun shouldRefreshToken(token: TokenResponse): Boolean {
    val expiresIn = token.expires_in ?: return false
    val receivedAt = token.received_at ?: return false
    return System.currentTimeMillis() >= receivedAt + expiresIn * 1000L - TOKEN_REFRESH_SKEW_MS
}

// ---- The fakts envelope ------------------------------------------------------------------

@Serializable
data class Alias(
        val id: String? = null,
        val host: String,
        val port: Int? = null,
        val ssl: Boolean,
        val path: String? = null,
        val challenge: String = "",
        val public: Boolean? = null
) {
    public fun to_http_path(append: String?): String {
        val protocol = if (ssl) "https" else "http"
        val portPart = when (port) {
            null, 80, 443 -> ""
            else -> ":$port"
        }
        val pathPart = path?.let { "/$it" } ?: ""
        val appendPart = append?.let {
            if (it.startsWith("/")) it else "/$it"
        } ?: ""

        return "$protocol://$host$portPart$pathPart$appendPart"
    }

    public fun to_ws_path(append: String?): String {

        val protocol = if (ssl) "wss" else "ws"
        val portPart = when (port) {
            null, 80, 443 -> ""
            else -> ":$port"
        }
        val pathPart = path?.let { "/$it" } ?: ""
        val appendPart = append?.let {
            if (it.startsWith("/")) it else "/$it"
        } ?: ""

        return "$protocol://$host$portPart$pathPart$appendPart"

    }

}

// Ed25519 public key for verifying signed alias challenges. Modelled so it round-trips through
// the cache; no signature is verified here (the plain 2xx probe below is what orkestrator does).
@Serializable data class ChallengeKey(val kind: String, val key: String)

@Serializable
data class Instance(
        val service: String,
        val identifier: String,
        val aliases: List<Alias>,
        val challenge_key: ChallengeKey? = null
)

@Serializable
data class SelfFakt(val deployment_name: String, val alias: Alias)

// `instances` and `statuses` are keyed by the *manifest requirement key* ("rekuest", "mikro",
// "datalayer"), not by the service identifier. There is no `auth` block any more.
@Serializable
data class ActiveFakts(
        val self: SelfFakt,
        val instances: Map<String, Instance>,
        val statuses: Map<String, String> = emptyMap()
)

// Outcome of a single requirement. The server only ever emits granted/denied/unavailable;
// UNKNOWN is a client-side synthesis for an absent or unrecognized value.
enum class GrantStatus {
    GRANTED,
    DENIED,
    UNAVAILABLE,
    UNKNOWN;

    companion object {
        fun from(value: String?): GrantStatus = when (value?.lowercase()) {
            "granted" -> GRANTED
            "denied" -> DENIED
            "unavailable" -> UNAVAILABLE
            else -> UNKNOWN
        }
    }
}

// ---- Report ------------------------------------------------------------------------------

@Serializable
data class AliasReport(val valid: Boolean, val alias_id: String? = null, val reason: String? = null)

@Serializable
data class ReportRequest(val alias_reports: Map<String, AliasReport>, val functional: Boolean)

// ---- Errors -----------------------------------------------------------------------------

open class FaktsError(message: String) : Exception(message)

class DiscoveryError(message: String) : FaktsError(message)

// Raised anywhere in the device-code grant: authorization refused, declined by the user, or the
// code expiring before approval.
class DemandError(message: String) : FaktsError(message)

// Raised when the token endpoint rejects us. `statusCode` is null for a transport-level failure.
class TokenError(val statusCode: Int?, message: String) : FaktsError(message)

// Raised when a *required* service could not be resolved to a working, granted alias.
class CompositionError(message: String) : FaktsError(message)

// ---- The persisted session ----------------------------------------------------------------

// Everything needed to resume without a browser: which deployment we negotiated with, what it
// granted, and the rotating refresh token. The `hash` binds the record to a (manifest, url)
// pair; the "v2:" prefix guarantees a protocol-1 record can never be mistaken for this one.
@Serializable
data class FaktsSession(
        val hash: String,
        val endpoint: FaktsEndpoint,
        val fakts: ActiveFakts,
        val token: TokenResponse
)

class FaktsCache(private val file: File) {
    fun load(hash: String): FaktsSession? {
        return try {
            if (!file.exists()) return null
            val cached = faktsJson.decodeFromString<FaktsSession>(file.readText())
            // A changed manifest or server url changes the hash and invalidates the cache. A
            // protocol-1 record has no `endpoint`/`token` at all and fails to decode above,
            // which is exactly the migration we want.
            if (cached.hash == hash) cached else null
        } catch (e: Exception) {
            println("Failed to read fakts cache: ${e.message}")
            null
        }
    }

    fun save(session: FaktsSession) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(faktsJson.encodeToString(session))
        } catch (e: Exception) {
            println("Failed to write fakts cache: ${e.message}")
        }
    }

    fun clear() {
        try {
            file.delete()
        } catch (e: Exception) {
            println("Failed to clear fakts cache: ${e.message}")
        }
    }
}

// ---- Alias resolution ---------------------------------------------------------------------

// Challenge each alias of a service (GET on its challenge URL must answer 200 iff reachable)
// and keep the first that answers. `challenge` is a path fragment, not a nonce.
suspend fun getFirstReachableAlias(instance: Instance): Alias? {
    val client = OkHttpClient()

    for (alias in instance.aliases) {
        val url = alias.to_http_path(alias.challenge)
        val isReachable = try {
            // Execute HTTP request in IO context
            withContext(Dispatchers.IO) {
                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()
                response.use { it.isSuccessful }
            }
        } catch (e: Exception) {
            println("Couldn't reach alias at ${alias.to_http_path(alias.challenge)}")
            false // If exception occurs, alias is not reachable
        }

        if (isReachable) {
            return alias // Return immediately if an alias is reachable
        }
    }

    return null // Return null if no alias is reachable
}

// Resolve every granted instance to its first reachable alias.
suspend fun buildInstanceMap(fakts: ActiveFakts): Map<String, Alias> {

    val instanceMap = mutableMapOf<String, Alias>()

    fakts.instances.forEach { (instanceName, instance) ->
        val alias = getFirstReachableAlias(instance)
        if (alias != null) {
            instanceMap[instanceName] = alias
        }
        else {
            println("No reachable alias found for instance $instanceName")
        }
    }

    return instanceMap
}

// ---- The protocol driver --------------------------------------------------------------------

// Result of a device-code grant: the token and the envelope that rode along with it.
data class GrantResult(val token: TokenResponse, val fakts: ActiveFakts)

// Same, but `fakts` may be null: the server appends the envelope on a best-effort basis, and a
// valid token with no envelope must refresh the session, not destroy it.
data class RefreshResult(val token: TokenResponse, val fakts: ActiveFakts?)

open class FaktsClient(private val client: OkHttpClient = OkHttpClient()) {

    private val jsonMedia = "application/json; charset=utf-8".toMediaTypeOrNull()

    // --- 1. Discovery -------------------------------------------------------------------

    suspend fun discover(url: String): FaktsEndpoint {
        val discoveryUrl = url.trimEnd('/') + "/.well-known/fakts"
        val request = Request.Builder().url(discoveryUrl).get().build()
        val endpoint = withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    throw DiscoveryError("Discovery at $discoveryUrl failed (${response.code}): ${text.take(300)}")
                }
                try {
                    faktsJson.decodeFromString<FaktsEndpoint>(text)
                } catch (e: Exception) {
                    throw DiscoveryError(
                            "Could not parse discovery response from $discoveryUrl as a fakts " +
                                    "protocol 2 document (${e.message}). A deployment still speaking " +
                                    "protocol 1 has no configure/device_authorization_endpoint/" +
                                    "token_endpoint and fails here — it needs upgrading."
                    )
                }
            }
        }
        // A missing protocol_version means protocol 1. Compare as a string: the server may send
        // either "2" or 2, and both decode into this nullable String.
        if (endpoint.protocol_version != "2") {
            throw DiscoveryError(
                    "$discoveryUrl advertises fakts protocol_version=" +
                            "${endpoint.protocol_version ?: "1 (absent)"}, but this plugin speaks " +
                            "protocol 2 only. The deployment needs upgrading."
            )
        }
        return endpoint
    }

    // --- 2. Device authorization ----------------------------------------------------------

    suspend fun deviceAuthorize(endpoint: FaktsEndpoint, manifest: Manifest): DeviceAuthorization {
        val body = faktsJson.encodeToString(DeviceAuthorizationRequest(manifest = manifest))

        // The throttler answers 429 {"error":"slow_down"} — back off and retry the start rather
        // than failing the whole login.
        var attempt = 0
        while (true) {
            val (code, text) = postJson(endpoint.device_authorization_endpoint, body)
            val error = parseErrorBody(text)

            if (code == 200 && error?.error == null) {
                val authorization = try {
                    faktsJson.decodeFromString<DeviceAuthorization>(text)
                } catch (e: Exception) {
                    throw DemandError("Malformed device authorization response: ${e.message}")
                }
                // A fakts deviation from RFC 8628: success is gated on this literal.
                if (authorization.status != "granted") {
                    throw DemandError("Device authorization was refused: ${authorization.status}")
                }
                return authorization
            }

            if ((code == 429 || error?.error == "slow_down") && attempt < 3) {
                attempt += 1
                println("Device authorization throttled; retrying in ${attempt * 5}s.")
                delay(attempt * 5000L)
                continue
            }

            throw DemandError(
                    "Device authorization at ${endpoint.device_authorization_endpoint} was refused " +
                            "(HTTP $code): ${error?.describe() ?: text.take(300)}"
            )
        }
    }

    // --- 3. Token poll --------------------------------------------------------------------

    // Poll the OAuth2 token endpoint with the device-code grant until the human approves (or
    // declines). The response semantics are inverted relative to protocol 1's challenge poll:
    // "still waiting" is an HTTP 400 carrying {"error": ...}, so this branches on the `error`
    // member and never on the HTTP status.
    suspend fun pollToken(
            tokenEndpoint: String,
            deviceCode: String,
            clientId: String,
            interval: Int,
            expiresIn: Int
    ): GrantResult {
        // Monotonic: a wall-clock jump mid-approval must not expire (or extend) the window.
        val deadline = System.nanoTime() + expiresIn * 1_000_000_000L
        var currentInterval = if (interval > 0) interval else 5

        while (System.nanoTime() < deadline) {
            // Sleep *before* the first poll: the code cannot have been approved yet, and polling
            // faster than `interval` is exactly what earns a slow_down.
            delay(currentInterval * 1000L)

            val (code, text) = postForm(
                    tokenEndpoint,
                    mapOf(
                            "grant_type" to DEVICE_CODE_GRANT_TYPE,
                            "device_code" to deviceCode,
                            "client_id" to clientId
                    )
            )
            val error = parseErrorBody(text)

            if (code in 200..299 && error?.error == null) {
                // The device code is single-use — burned server-side by this response. Never
                // poll again from here; continuity is the refresh chain.
                return splitGrantResponse(text)
            }

            when (error?.error) {
                "authorization_pending" -> {} // keep polling
                // RFC 8628 §3.5: back off by 5s and keep going.
                "slow_down" -> currentInterval += 5
                "access_denied" -> throw DemandError("The authorization request was declined.")
                "expired_token" ->
                        throw DemandError("The authorization request expired before it was approved.")
                else ->
                        throw DemandError(
                                "Token request to $tokenEndpoint failed (HTTP $code): " +
                                        (error?.describe() ?: text.take(300))
                        )
            }
        }

        throw DemandError("The authorization request expired before it was approved.")
    }

    // --- Refresh ----------------------------------------------------------------------------

    // Refresh as a public client: client_id only, no secret. The refresh token rotates on every
    // use, so the returned one must always be persisted. The response also carries a freshly
    // re-rendered fakts envelope (aliases are resolved against the requesting host), which is how
    // configuration changes reach us without a human re-approving.
    open suspend fun refresh(tokenEndpoint: String, current: TokenResponse): RefreshResult {
        val refreshToken = current.refresh_token
                ?: throw TokenError(null, "No refresh token available – cannot refresh.")

        val (code, text) = postForm(
                tokenEndpoint,
                mapOf(
                        "grant_type" to "refresh_token",
                        "refresh_token" to refreshToken,
                        "client_id" to current.client_id
                )
        )

        if (code !in 200..299) {
            val error = parseErrorBody(text)
            throw TokenError(
                    code,
                    "Failed to refresh the access token at $tokenEndpoint.\n" +
                            "  HTTP status : $code\n" +
                            "  client_id   : ${current.client_id}\n" +
                            "  response    : ${error?.describe() ?: text.take(300).ifBlank { "(empty body)" }}"
            )
        }

        return splitRefreshResponse(text)
    }

    // --- Report -------------------------------------------------------------------------------

    // Tell the deployment which aliases actually worked. Best-effort: never throws.
    suspend fun report(
            endpoint: FaktsEndpoint,
            accessToken: String,
            aliasReports: Map<String, AliasReport>,
            functional: Boolean
    ) {
        try {
            val body = faktsJson.encodeToString(ReportRequest(aliasReports, functional))
            val request = Request.Builder()
                    .url(endpoint.report_endpoint)
                    .addHeader("Authorization", "Bearer $accessToken")
                    .post(body.toRequestBody(jsonMedia))
                    .build()
            withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        println("Alias report to ${endpoint.report_endpoint} failed (${response.code}).")
                    }
                }
            }
        } catch (e: Exception) {
            println("Alias report to ${endpoint.report_endpoint} failed: ${e.message}")
        }
    }

    // --- Transport helpers ---------------------------------------------------------------------

    // Both POST helpers return (status, body) instead of throwing on a non-2xx: in this protocol
    // an error status is a normal, information-carrying answer.
    private suspend fun postJson(url: String, json: String): Pair<Int, String> {
        val request = Request.Builder().url(url).post(json.toRequestBody(jsonMedia)).build()
        return execute(url, request)
    }

    private suspend fun postForm(url: String, fields: Map<String, String>): Pair<Int, String> {
        val builder = FormBody.Builder()
        // FormBody url-encodes each field, so a value containing &, =, + or % survives intact.
        fields.forEach { (key, value) -> builder.add(key, value) }
        val request = Request.Builder().url(url).post(builder.build()).build()
        return execute(url, request)
    }

    private suspend fun execute(url: String, request: Request): Pair<Int, String> =
            withContext(Dispatchers.IO) {
                val response = try {
                    client.newCall(request).execute()
                } catch (e: Exception) {
                    // Transport-level failure: DNS, TLS, connection refused, timeout, ...
                    throw TokenError(
                            null,
                            "Request to $url failed before a response was received " +
                                    "(${e.javaClass.simpleName}: ${e.message})."
                    )
                }
                response.use { it.code to (it.body?.string() ?: "") }
            }

    private fun parseErrorBody(text: String): FaktsErrorBody? =
            try {
                faktsJson.decodeFromString<FaktsErrorBody>(text)
            } catch (e: Exception) {
                null
            }
}

// ---- Splitting the flat grant response ------------------------------------------------------

// A successful token response is ONE flat JSON object: the standard OAuth2 members and the fakts
// envelope (`self`/`instances`/`statuses`) are top-level siblings. Decode it twice — each pass
// ignores the other's members — and stamp `received_at`, which the server never sends.
fun splitGrantResponse(text: String): GrantResult {
    val token = decodeToken(text)
    val fakts = try {
        faktsJson.decodeFromString<ActiveFakts>(text)
    } catch (e: Exception) {
        throw TokenError(null, "Token response carried no usable fakts envelope: ${e.message}")
    }
    return GrantResult(token, fakts)
}

// Same split, but tolerant of a missing envelope: the server renders it best-effort and logs
// rather than failing the grant if it throws. A valid token with no envelope must refresh the
// session, not destroy it — the caller keeps the config it already holds.
fun splitRefreshResponse(text: String): RefreshResult {
    val token = decodeToken(text)
    val fakts = try {
        faktsJson.decodeFromString<ActiveFakts>(text)
    } catch (e: Exception) {
        println("Refresh response carried no fakts envelope; keeping the current config.")
        null
    }
    return RefreshResult(token, fakts)
}

private fun decodeToken(text: String): TokenResponse {
    val token = try {
        faktsJson.decodeFromString<TokenResponse>(text)
    } catch (e: Exception) {
        throw TokenError(
                null,
                "Malformed token response (${e.message}): ${text.take(300).ifBlank { "(empty body)" }}"
        )
    }
    return token.copy(received_at = System.currentTimeMillis())
}
