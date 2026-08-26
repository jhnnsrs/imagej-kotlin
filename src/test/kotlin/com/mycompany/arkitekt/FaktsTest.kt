package com.mycompany.arkitekt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The parts of the fakts layer that are pure logic. Everything else needs a live coordination
 * server; these are the pieces whose failure modes are silent in normal operation.
 */
class FaktsTest {

    // ---- Expiry math ---------------------------------------------------------------------

    private fun token(expiresIn: Int?, receivedAt: Long?) =
            TokenResponse(
                    access_token = "at",
                    client_id = "cid",
                    expires_in = expiresIn,
                    received_at = receivedAt
            )

    @Test
    fun `a fresh token is not refreshed`() {
        assertFalse(shouldRefreshToken(token(3600, System.currentTimeMillis())))
    }

    @Test
    fun `a token inside the skew window is refreshed`() {
        // Nominally 30s of life left — inside the 60s skew, so it must not go on the wire.
        val receivedAt = System.currentTimeMillis() - (3600 - 30) * 1000L
        assertTrue(shouldRefreshToken(token(3600, receivedAt)))
    }

    @Test
    fun `an expired token is refreshed`() {
        assertTrue(shouldRefreshToken(token(60, System.currentTimeMillis() - 120_000)))
    }

    @Test
    fun `a token with no expiry information is never refreshed`() {
        // Both halves matter: received_at is client-synthesized, so a token that lost it would
        // otherwise be refreshed on every single request.
        assertFalse(shouldRefreshToken(token(null, System.currentTimeMillis())))
        assertFalse(shouldRefreshToken(token(3600, null)))
    }

    // ---- Splitting the flat grant response ------------------------------------------------

    // A real protocol-2 grant: OAuth2 members and the fakts envelope are top-level siblings.
    private val grantBody = """
        {
          "access_token": "at", "refresh_token": "rt", "token_type": "Bearer",
          "expires_in": 3600, "scope": "openid", "client_id": "cid",
          "self": {
            "deployment_name": "test-deployment",
            "alias": {"id": "self", "host": "localhost", "ssl": false, "challenge": "ht"}
          },
          "instances": {
            "mikro": {
              "service": "live.arkitekt.mikro", "identifier": "3",
              "aliases": [{"id": "a1", "host": "localhost", "ssl": false, "challenge": "ht"}],
              "challenge_key": {"kind": "ed25519", "key": "AAAA"}
            }
          },
          "statuses": {"mikro": "granted"}
        }
    """.trimIndent()

    @Test
    fun `a grant response splits into a token and an envelope`() {
        val result = splitGrantResponse(grantBody)

        assertEquals("at", result.token.access_token)
        assertEquals("rt", result.token.refresh_token)
        assertEquals("cid", result.token.client_id)
        assertEquals(3600, result.token.expires_in)

        assertEquals("test-deployment", result.fakts.self.deployment_name)
        assertEquals("granted", result.fakts.statuses["mikro"])
        assertEquals("a1", result.fakts.instances["mikro"]?.aliases?.first()?.id)
        assertEquals("ed25519", result.fakts.instances["mikro"]?.challenge_key?.kind)
    }

    @Test
    fun `a grant response is stamped with received_at`() {
        // The server never sends it, and without it the token would never be refreshed.
        val before = System.currentTimeMillis()
        val receivedAt = splitGrantResponse(grantBody).token.received_at
        assertNotNull(receivedAt)
        assertTrue(receivedAt >= before)
    }

    @Test
    fun `a refresh response with no envelope keeps the token`() {
        // The server appends the envelope best-effort; a valid token without one must refresh
        // the session, not destroy it.
        val result = splitRefreshResponse(
                """{"access_token": "at2", "refresh_token": "rt2", "token_type": "Bearer",
                    "expires_in": 3600, "client_id": "cid"}"""
        )
        assertEquals("at2", result.token.access_token)
        assertNull(result.fakts)
    }

    @Test
    fun `a refresh response with an envelope carries it`() {
        val result = splitRefreshResponse(grantBody)
        assertNotNull(result.fakts)
        assertEquals("test-deployment", result.fakts.self.deployment_name)
    }

    // ---- Unknown members ------------------------------------------------------------------

    @Test
    fun `discovery documents keep parsing when the server adds members`() {
        // A real document also carries mesh_*/hub_* members this client ignores.
        val endpoint = faktsJson.decodeFromString<FaktsEndpoint>(
                """
                {
                  "name": "default", "version": "0.1.0", "protocol_version": "2",
                  "base_url": "http://localhost/lok/f/", "frontend_url": "http://localhost/",
                  "configure": "http://localhost/configure/{code}",
                  "device_authorization_endpoint": "http://localhost/lok/o/app-authorization/",
                  "token_endpoint": "http://localhost/lok/o/token/",
                  "jwks_uri": "http://localhost/lok/o/jwks/",
                  "mesh_coord_url": "https://ionscale.arkitekt.live",
                  "hub_claim": "http://localhost/lok/f/claimhub/"
                }
                """.trimIndent()
        )
        assertEquals("2", endpoint.protocol_version)
        assertEquals("http://localhost/lok/o/token/", endpoint.token_endpoint)
        // base_url exists for exactly one purpose.
        assertEquals("http://localhost/lok/f/report/", endpoint.report_endpoint)
    }

    // ---- Alias URL construction -------------------------------------------------------------

    @Test
    fun `an empty append yields the trailing slash the S3 endpoint override needs`() {
        val alias = Alias(host = "localhost", ssl = false, path = "datalayer", challenge = "ht")
        assertEquals("http://localhost/datalayer/", alias.to_http_path(""))
        assertEquals("http://localhost/datalayer", alias.to_http_path(null))
        assertEquals("ws://localhost/datalayer/agi", alias.to_ws_path("agi"))
    }

    @Test
    fun `the default ports are elided`() {
        val alias = Alias(host = "example.com", port = 443, ssl = true, challenge = "ht")
        assertEquals("https://example.com/graphql", alias.to_http_path("graphql"))
        assertEquals(
                "https://example.com:8080/graphql",
                alias.copy(port = 8080).to_http_path("graphql")
        )
    }

    // ---- Grant statuses -----------------------------------------------------------------------

    @Test
    fun `unrecognized grant statuses coerce to UNKNOWN`() {
        // The server only ever emits these three; UNKNOWN is our synthesis for anything else.
        assertEquals(GrantStatus.GRANTED, GrantStatus.from("granted"))
        assertEquals(GrantStatus.DENIED, GrantStatus.from("denied"))
        assertEquals(GrantStatus.UNAVAILABLE, GrantStatus.from("unavailable"))
        assertEquals(GrantStatus.UNKNOWN, GrantStatus.from(null))
        assertEquals(GrantStatus.UNKNOWN, GrantStatus.from("something-new"))
    }
}
