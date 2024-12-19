package com.mycompany.arkitekt
import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.auth.BasicSessionCredentials
import com.amazonaws.client.builder.AwsClientBuilder
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import com.apollographql.apollo.api.http.HttpRequest
import com.apollographql.apollo.api.http.HttpResponse
import com.apollographql.apollo.network.http.HttpInterceptor
import com.apollographql.apollo.network.http.HttpInterceptorChain
import com.google.gson.Gson
import com.mycompany.lok.graphql.MeQuery
import com.mycompany.mikro.graphql.FromArrayLikeMutation
import com.mycompany.mikro.graphql.RequestUploadMutation
import com.mycompany.mikro.graphql.type.FromArrayLikeInput
import com.mycompany.mikro.graphql.type.RequestUploadInput
import com.mycompany.rekuest.graphql.type.*
import dev.zarr.zarrjava.store.S3Store
import dev.zarr.zarrjava.store.StoreHandle
import dev.zarr.zarrjava.v3.Array
import dev.zarr.zarrjava.v3.DataType
import java.util.UUID
import java.util.prefs.Preferences
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import net.imagej.DatasetService
import net.imagej.ImgPlus
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.scijava.ui.UIService
import net.imglib2.img.Img
import net.imglib2.type.numeric.RealType
import net.imglib2.RandomAccess
import net.imagej.axis.Axes
import net.imagej.display.ImageDisplayService


@Serializable data class Requirement(val service: String, val key: String)

@Serializable
data class Manifest(
        val identifier: String,
        val requirements: List<Requirement>,
        val version: String = "1.0",
        val scopes: List<String> = listOf("read")
)

@Serializable
data class FaktsStartRequest(val manifest: Manifest, val requested_client_kind: String)

@Serializable data class DeviceCodeAnswer(val code: String)

@Serializable data class DeviceCodeChallenge(val code: String)

@Serializable data class ChallengeAnswer(val status: String, val token: String?)

@Serializable data class RetrieveRequest(val token: String)

@Serializable
data class UnlokFakt(
        public val client_id: String,
        public val client_secret: String,
        public val scopes: List<String>,
        public val endpoint_url: String,
        public val token_url: String
)

@Serializable data class MikroFakt(val endpoint_url: String)

@Serializable data class AgentFakt(val endpoint_url: String)

@Serializable data class RekuestFakt(val endpoint_url: String, val agent: AgentFakt)

@Serializable data class DatalayerFakts(val endpoint_url: String)

@Serializable
data class Fakts(
        val unlok: UnlokFakt,
        val datalayer: DatalayerFakts,
        val mikro: MikroFakt,
        val rekuest: RekuestFakt
)

@Serializable data class RetrieveAnswer(val config: Fakts, val status: String)

@Serializable
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

class Unlok(fakt: UnlokFakt, token: String) {
    private val apolloClient: ApolloClient =
            ApolloClient.Builder()
                    .serverUrl(fakt.endpoint_url)
                    .addHttpInterceptor(AuthorizationInterceptor(token))
                    .build()

    fun getClient(): ApolloClient {
        return apolloClient
    }
}

class DatalayerStore(store: StoreHandle, storeId: String) {
    val store = store
    val storeId = storeId
}

class Datalayer(fakt: DatalayerFakts, mikro: Mikro) {
    private var endpoint_url = fakt.endpoint_url
    private var mikro = mikro

    suspend fun requestStore(key: String): DatalayerStore {

        val client = mikro.getClient()

        val response =
                client.mutation(
                                RequestUploadMutation(
                                        RequestUploadInput(key = key, datalayer = "default")
                                )
                        )
                        .execute()

        val accessKeyId = response.data?.requestUpload?.accessKey
        val secretAccessKey = response.data?.requestUpload?.secretKey
        val sessionToken = response.data?.requestUpload?.sessionToken
        val bucketName = response.data?.requestUpload?.bucket
        val key = response.data?.requestUpload?.key
        val store = response.data?.requestUpload?.store

        if (accessKeyId == null ||
                        secretAccessKey == null ||
                        sessionToken == null ||
                        bucketName == null ||
                        key == null ||
                        store == null
        ) {
            throw Exception("Failed to retrieve S3 credentials")
        }

        val sessionCredentials = BasicSessionCredentials(accessKeyId, secretAccessKey, sessionToken)

        var s3Client =
                AmazonS3ClientBuilder.standard()
                        .withEndpointConfiguration(
                                AwsClientBuilder.EndpointConfiguration(endpoint_url, "us-east-1")
                        )
                        .withCredentials(AWSStaticCredentialsProvider(sessionCredentials))
                        .withPathStyleAccessEnabled(true)
                        .build()

        return DatalayerStore(S3Store(s3Client, bucketName, null).resolve(key), store)
    }
}

class Mikro(fakt: MikroFakt, token: String) {
    private val apolloClient: ApolloClient =
            ApolloClient.Builder()
                    .serverUrl(fakt.endpoint_url)
                    .addHttpInterceptor(AuthorizationInterceptor(token))
                    .build()

    fun getClient(): ApolloClient {
        return apolloClient
    }
}

class Rekuest(fakt: RekuestFakt, token: String) {
    private val apolloClient: ApolloClient =
            ApolloClient.Builder()
                    .serverUrl(fakt.endpoint_url)
                    .addHttpInterceptor(AuthorizationInterceptor(token))
                    .build()

    fun getClient(): ApolloClient {
        return apolloClient
    }
}

class App(
        public val mikro: Mikro,
        public val datalayer: Datalayer,
        public val unlok: Unlok,
        public val rekuest: Rekuest,
        public val uiService: UIService,
        public val datasetService: DatasetService,
        public var imageDisplayService: ImageDisplayService
)




fun <T : RealType<T>> imgPlusToCTZXYUInt32UcarArray(imgPlus: ImgPlus<T>): ucar.ma2.Array {
    val numDimensions = imgPlus.numDimensions()
    val axisTypes = (0 until numDimensions).map { imgPlus.axis(it).type() }

    // Final axis order: c=0, t=1, z=2, x=3, y=4
    var cSize = 1
    var tSize = 1
    var zSize = 1
    var xSize = 1
    var ySize = 1

    var cDim: Int? = null
    var tDim: Int? = null
    var zDim: Int? = null
    var xDim: Int? = null
    var yDim: Int? = null

    // Identify sizes and mapping for each axis
    for (d in 0 until numDimensions) {
        val sizeD = imgPlus.dimension(d)
        val axisType = axisTypes[d]
        when {
            axisType == Axes.CHANNEL -> {
                cSize = sizeD.toInt()
                cDim = d
            }
            axisType == Axes.TIME -> {
                tSize = sizeD.toInt()
                tDim = d
            }
            axisType == Axes.Z -> {
                zSize = sizeD.toInt()
                zDim = d
            }
            axisType == Axes.X -> {
                xSize = sizeD.toInt()
                xDim = d
            }
            axisType == Axes.Y -> {
                ySize = sizeD.toInt()
                yDim = d
            }
        }
    }


    // finalToImgDim maps final axes (c,t,z,x,y) to their corresponding ImgPlus dimension
    val finalToImgDim = arrayOf(cDim, tDim, zDim, xDim, yDim)

    val totalSize = 1L * cSize * tSize * zSize * xSize * ySize
    val data = IntArray(totalSize.toInt())

    val ra: RandomAccess<T> = imgPlus.randomAccess()

    var index = 0
    for (c in 0 until cSize) {
        for (t in 0 until tSize) {
            for (z in 0 until zSize) {
                for (x in 0 until xSize) {
                    for (y in 0 until ySize) {
                        val position = LongArray(numDimensions) { 0L }

                        // Helper to set position if dimension is present
                        fun setPositionIfNotNull(axisIndex: Int, value: Long) {
                            val dim = finalToImgDim[axisIndex]
                            if (dim != null) {
                                position[dim] = value
                            }
                        }

                        // final axes: c=0, t=1, z=2, x=3, y=4
                        setPositionIfNotNull(0, c.toLong()) // c
                        setPositionIfNotNull(1, t.toLong()) // t
                        setPositionIfNotNull(2, z.toLong()) // z
                        setPositionIfNotNull(3, x.toLong()) // x
                        setPositionIfNotNull(4, y.toLong()) // y

                        ra.setPosition(position)

                        val pixelValue = ra.get().realDouble
                        val clamped = when {
                            pixelValue < 0.0 -> 0
                            pixelValue > 0xFFFFFFFFL -> 255
                            else -> pixelValue.toInt()
                        }
                        data[index++] = clamped
                    }
                }
            }
        }
    }

    // Data is already in the order (c, t, z, x, y) because we iterated in that order.
    // Shape matches the order: c,t,z,x,y
    val shape = intArrayOf(cSize, tSize, zSize, xSize, ySize)

    // Create the ucar.ma2.Array of type UINT32
    val ucarArray: ucar.ma2.Array = ucar.ma2.Array.factory(ucar.ma2.DataType.UINT, shape, data)
    return ucarArray
}

class Arkitekt(private val uiService: UIService, private val datasetService: DatasetService, private val imageDisplayService: ImageDisplayService) {
    private val client = OkHttpClient()
    private val gson = Gson()
    private val manifest =
            Manifest(
                    "qupath",
                    listOf(
                            Requirement("live.arkitekt.lok", "unlok"),
                            Requirement("live.arkitekt.rekuest", "rekuest"),
                            Requirement("live.arkitekt.mikro", "mikro"),
                            Requirement("live.arkitekt.s3", "datalayer")
                    )
            )

    suspend fun loginUser( unlok: UnlokFakt): String {
        val tokenUrl = unlok.token_url + "/"
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

    suspend fun retrieveFakts(url: String, token: String): Fakts {
        val challengeJson = gson.toJson(RetrieveRequest(token))
        val challengeBody =
                challengeJson.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

        val challengeRequest =
                Request.Builder().url("${url}/claim/").post(challengeBody).build()

        return withContext(Dispatchers.IO) {
            client.newCall(challengeRequest).execute().use { response ->
                if (response.isSuccessful && response.body != null) {
                    val answerJson = response.body!!.string()
                    val answer =
                            Json { ignoreUnknownKeys = true }
                                    .decodeFromString<RetrieveAnswer>(
                                            answerJson,
                                    )

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
            url: String,
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
                            .url("${url}/challenge/")
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
                return retrieveFakts(url, it)
            } catch (e: Exception) {
                prefs.remove("token")
                println("Failed to retrieve Fakts: ${e.message}")
            }
        }

        val deviceCode = retrieveDeviceCode("${url}/start/")

        val deviceUrl = "${url}/configure/?grant=device_code&device_code=${deviceCode}"
        val osName = System.getProperty("os.name").lowercase()
        when {
            osName.contains("win") -> ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", deviceUrl).start()
            osName.contains("mac") -> ProcessBuilder("open", deviceUrl).start()
            osName.contains("nix") || osName.contains("nux") -> ProcessBuilder("xdg-open", deviceUrl).start()
            else -> throw Exception("Unsupported operating system")
        }
        println("${url}/configure/?grant=device_code&device_code=" + deviceCode)
        val token = challengeDeviceCode(url, deviceCode)
        val fakts = retrieveFakts(url, token)
        prefs.put("token", token)
        return fakts
    }

    suspend fun getUser(fakt: UnlokFakt, token: String): MeQuery.Data {
        val unlok = Unlok(fakt, token)
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
        throw Exception("Failed to retrieve user data")
    }

    fun login(url: String, callback: (MeQuery.Data) -> Unit) {
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val result = alogin(url)
                withContext(Dispatchers.Main) { callback(result) }
            } catch (e: Exception) {
                println("Failed to login: ${e.message}")
            }
        }
    }

    fun generateChunkShape(inarrayShape: IntArray): IntArray {
        // Ensure the shape has the expected dimensions: c, t, z, y, x
        if (inarrayShape.size != 5) {
            throw IllegalArgumentException("Input array shape must have exactly 5 dimensions: c, t, z, y, x")
        }

        val chunkShape = IntArray(5)

        // Fixed chunk sizes for c, t, and z
        chunkShape[0] = 1 // c
        chunkShape[1] = 1 // t
        chunkShape[2] = 1 // z

        // For x and y, use 1024 if size > 1024, otherwise use the input size
        chunkShape[3] = if (inarrayShape[3] > 1024) 1024 else inarrayShape[3] // y
        chunkShape[4] = if (inarrayShape[4] > 1024) 1024 else inarrayShape[4] // x

        return chunkShape
    }



    suspend fun uploadArray(app: App, inarray: ucar.ma2.Array, name: String): FromArrayLikeMutation.FromArrayLike {

        var key = UUID.randomUUID().toString()

        val s3Client = app.datalayer.requestStore(key)




        val array =
                Array.create(
                        s3Client.store,
                        Array.metadataBuilder()
                                .withShape(*inarray.shape.map { i -> i.toLong() }.toLongArray())
                                .withDimensionNames("c", "t", "z", "y", "x")
                                .withDataType(DataType.UINT32)
                                .withChunkShape(*generateChunkShape(inarray.shape.map { i -> i.toInt() }.toIntArray()))
                                .withFillValue(0)
                                .withCodecs { it.withBlosc() }
                                .build()!!
                )

        array.write(
                listOf(0L, 0L, 0L, 0L, 0L).toLongArray(),
                inarray
        )

        var mutation = FromArrayLikeMutation(FromArrayLikeInput(array=s3Client.storeId, name=name))

        val client = app.mikro.getClient()

        val response = client.mutation(mutation).execute()

        println("Response: ${response.data}")
        return response.dataOrThrow().fromArrayLike
    }

    suspend fun runX(app: App, args: Map<String, JsonElement?>): Map<String, JsonElement?> {

        imageDisplayService.imageDisplays.forEach {
            d -> println(d)
        }



        var active = imageDisplayService.getActiveDataset( imageDisplayService.imageDisplays[0])




        var name = args.get("name")!!.jsonPrimitive.content

        var array = imgPlusToCTZXYUInt32UcarArray(active.imgPlus)

        var image = uploadArray(app, array, name)

        return mapOf(Pair("image" , JsonPrimitive(image.id)))
    }

    suspend fun alogin(url: String): MeQuery.Data {

        val fakts = getFakts(url)
        val token = loginUser(fakts.unlok)
        println("Token: $token")

        CoroutineScope(Dispatchers.Default).launch {
            var registry = FunctionRegistry()


            registry.register_function(
                "frage",
                DefinitionInput(
                    name = "Upload Image",
                    description =  Optional.present("Upload the currently active image in the viewer."),
                    args = Optional.present(listOf(PortInput(
                        key = "name",
                        kind = PortKind.STRING,
                        scope = PortScope.GLOBAL,
                        description = Optional.present("How would you like to name the image?")
                    ))),
                    returns = Optional.present(listOf(PortInput(
                        key = "image",
                        kind = PortKind.STRUCTURE,
                        identifier = Optional.present("@mikro/image"),
                        scope = PortScope.GLOBAL,
                        description = Optional.present("The returned image")
                    ))),
                    kind = NodeKind.FUNCTION
                ),
                ::runX
            )


            var rekuest = Rekuest(fakts.rekuest, token)
            var unlok = Unlok(fakts.unlok, token)
            var mikro = Mikro(fakts.mikro, token)
            var datalayer = Datalayer(fakts.datalayer, mikro)

            var app = App(mikro, datalayer, unlok, rekuest, uiService, datasetService, imageDisplayService)
            var agent = Agent(rekuest, fakts.rekuest, token, registry, app, "default")

            agent.createAgent("my_agent", listOf("default"))
            agent.registerFunctions()
            agent.provideForever()
        }

        return getUser(fakts.unlok, token)
    }

    fun logout() {
        val prefs = Preferences.userNodeForPackage(Arkitekt::class.java)
        prefs.remove("token")
    }
}
