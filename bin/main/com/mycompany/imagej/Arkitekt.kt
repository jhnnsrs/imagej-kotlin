package com.mycompany.imagej

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.http.HttpRequest
import com.apollographql.apollo.api.http.HttpResponse
import com.apollographql.apollo.network.http.HttpInterceptor
import com.apollographql.apollo.network.http.HttpInterceptorChain
import com.google.gson.Gson
import java.util.prefs.Preferences
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

data class Requirement(val service: String, val key: String)

data class Manifest(
        val identifier: String,
        val requirements: List<Requirement>,
        val version: String = "1.0",
        val scopes: List<String> = listOf("read")
)

data class FaktsStartRequest(val manifest: Manifest, val requested_client_kind: String)

data class DeviceCodeAnswer(val code: String)

data class DeviceCodeChallenge(val code: String)

data class ChallengeAnswer(val status: String, val token: String?)

data class RetrieveRequest(val token: String)

data class UnlokFakt(
        val client_id: String,
        val client_secret: String,
        val scopes: List<String>,
        val endpoint_url: String
)

data class Fakts(val unlok: UnlokFakt)

data class RetrieveAnswer(val config: Fakts)

data class TokenResponse(
        val access_token: String,
        val token_type: String,
        val scope: String,
        val expires_in: String
)

class AuthorizationInterceptor(val token: String) : HttpInterceptor {
    override suspend fun intercept(
            request: HttpRequest,
            chain: HttpInterceptorChain
    ): HttpResponse {
        return chain.proceed(
                request.newBuilder().addHeader("Authorization", "Bearer $token").build()
        )
    }
}

class Unlok(url: String, token: String) {
    private val apolloClient: ApolloClient =
            ApolloClient.Builder()
                    .serverUrl(url)
                    .addHttpInterceptor(AuthorizationInterceptor(token))
                    .build()

    fun getClient(): ApolloClient {
        return apolloClient
    }
}

class Arkitekt {
    private val client = OkHttpClient()
    private val gson = Gson()
    private val manifest = Manifest("qupath", listOf(Requirement("live.arkitekt.lok", "unlok")))

    suspend fun loginUser(unlok: UnlokFakt): String {
        val tokenUrl = "http://127.0.0.1/lok/o/token/"
        val bodyString =
                "grant_type=client_credentials&client_id=${unlok.client_id}&client_secret=${unlok.client_secret}"
        val body =
                bodyString.toRequestBody(
                        "application/x-www-form-urlencoded; charset=utf-8".toMediaTypeOrNull()
                )

        val request = Request.Builder().url(tokenUrl).post(body).build()

        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful && response.body != null) {
                    val responseJson = response.body!!.string()
                    val tokenResponse = gson.fromJson(responseJson, TokenResponse::class.java)
                    tokenResponse.access_token
                } else {
                    throw Exception("Failed to obtain token. Response code: ${response.code}")
                }
            }
        }
    }

    suspend fun retrieveFakts(token: String): Fakts {
        val challengeJson = gson.toJson(RetrieveRequest(token))
        val challengeBody =
                challengeJson.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

        val challengeRequest =
                Request.Builder().url("http://127.0.0.1/lok/f/claim/").post(challengeBody).build()

        return withContext(Dispatchers.IO) {
            client.newCall(challengeRequest).execute().use { response ->
                if (response.isSuccessful && response.body != null) {
                    val answerJson = response.body!!.string()
                    val answer = gson.fromJson(answerJson, RetrieveAnswer::class.java)
                    answer.config
                } else {
                    throw Exception("Failed to retrieve Fakts. Response code: ${response.code}")
                }
            }
        }
    }

    suspend fun retrieveDeviceCode(url: String): String {
        val json = gson.toJson(FaktsStartRequest(manifest, "development"))
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

        val request = Request.Builder().url(url).post(body).build()

        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful && response.body != null) {
                    val responseJson =
                            gson.fromJson(response.body!!.string(), DeviceCodeAnswer::class.java)
                    responseJson.code
                } else {
                    throw Exception("Failed to send manifest. Response code: ${response.code}")
                }
            }
        }
    }

    suspend fun challengeDeviceCode(
            deviceCode: String,
    ): String {
        repeat(10) {
            delay(2000)

            val challengeJson = gson.toJson(DeviceCodeChallenge(deviceCode))
            val challengeBody =
                    challengeJson.toRequestBody(
                            "application/json; charset=utf-8".toMediaTypeOrNull()
                    )

            val challengeRequest =
                    Request.Builder()
                            .url("http://127.0.0.1/lok/f/challenge/")
                            .post(challengeBody)
                            .build()
            val response = client.newCall(challengeRequest).execute()
            if (response.isSuccessful && response.body != null) {
                val challengeAnswer =
                        gson.fromJson(response.body!!.string(), ChallengeAnswer::class.java)
                if (challengeAnswer.status == "granted") {
                    return challengeAnswer.token!!
                } else {
                    println(
                            "Waiting for user to accept challenge. Status: ${challengeAnswer.status}"
                    )
                }
            }
        }
        throw Exception("Failed to challenge. No response received.")
    }

    suspend fun getFakts(url: String): Fakts {
        val prefs = Preferences.userNodeForPackage(Arkitekt::class.java)
        val storedToken = prefs.get("token", null)

        storedToken?.let {
            try {
                return retrieveFakts(it)
            } catch (e: Exception) {
                prefs.remove("token")
                println("Failed to retrieve Fakts: ${e.message}")
            }
        }

        val deviceCode = retrieveDeviceCode("http://127.0.0.1/lok/f/start/")
        println("http://127.0.0.1/lok/f/configure/?grant=device_code&device_code=" + deviceCode)
        val token = challengeDeviceCode(deviceCode)
        val fakts = retrieveFakts(token)
        prefs.put("token", token)
        return fakts
    }

    suspend fun getUser(endpoint: String, token: String): MeQuery.Data {
        val unlok = Unlok(endpoint, token)
        val client = unlok.getClient()
        // Use the client to execute the query
        val response = client.query(MeQuery()).execute()

        println("Response: ${response.data}")
        response.data.let { data ->
            if (data == null) {
                throw Exception("Failed to retrieve user data")
            }
            return data
        }
    }

    fun login(callback: (MeQuery.Data) -> Unit) {
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val result = alogin("http://127.0.0.1/lok/f")
                withContext(Dispatchers.Main) { callback(result) }
            } catch (e: Exception) {
                println("Failed to login: ${e.message}")
            }
        }
    }

    suspend fun doStuff() {
        var fakts = getFakts("http://127.0.0.1/lok/f")
    }

    suspend fun alogin(url: String): MeQuery.Data {

        val fakts = getFakts(url)
        val token = loginUser(fakts.unlok)
        println("Token: $token")

        return getUser(fakts.unlok.endpoint_url, token)
    }

    fun logout() {
        val prefs = Preferences.userNodeForPackage(Arkitekt::class.java)
        prefs.remove("token")
    }
}
