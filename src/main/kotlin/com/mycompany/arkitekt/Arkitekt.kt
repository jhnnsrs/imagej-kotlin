package com.mycompany.arkitekt

import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.net.URI
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.ApolloRequest
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Optional
import com.apollographql.apollo.api.http.HttpRequest
import com.apollographql.apollo.api.http.HttpResponse
import com.apollographql.apollo.network.http.HttpInterceptor
import com.apollographql.apollo.network.http.HttpInterceptorChain
import com.mycompany.lok.graphql.MeQuery
import com.mycompany.mikro.graphql.FromArrayLikeMutation
import com.mycompany.mikro.graphql.GetImageQuery
import com.mycompany.mikro.graphql.FinishZarrUploadMutation
import com.mycompany.mikro.graphql.RequestZarrAccessMutation
import com.mycompany.mikro.graphql.RequestZarrUploadMutation
import com.mycompany.mikro.graphql.type.FinishZarrUploadInput
import com.mycompany.mikro.graphql.type.FromArrayLikeInput
import com.mycompany.mikro.graphql.type.RequestZarrAccessInput
import com.mycompany.mikro.graphql.type.RequestZarrUploadInput
import com.mycompany.rekuest.graphql.type.*
import dev.zarr.zarrjava.store.S3Store
import dev.zarr.zarrjava.store.StoreHandle
import dev.zarr.zarrjava.v3.Array
import dev.zarr.zarrjava.v3.DataType
import ucar.ma2.DataType as UcarDataType
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.imagej.DatasetService
import net.imagej.ImgPlus
import net.imagej.axis.Axes
import net.imagej.axis.CalibratedAxis
import net.imagej.axis.DefaultLinearAxis
import net.imagej.display.ImageDisplayService
import net.imglib2.RandomAccess
import net.imglib2.type.numeric.RealType
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.scijava.ui.UIService
import java.util.*
import java.util.prefs.Preferences
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import dev.zarr.zarrjava.v3.Group;
import net.imagej.ImageJ
import net.imagej.Dataset
import net.imglib2.img.array.ArrayImgs
import net.imglib2.type.numeric.integer.ByteType
import net.imglib2.type.numeric.integer.IntType
import net.imglib2.type.numeric.integer.ShortType
import net.imglib2.type.numeric.integer.UnsignedByteType
import net.imglib2.type.numeric.integer.UnsignedIntType
import net.imglib2.type.numeric.integer.UnsignedShortType
import net.imglib2.type.numeric.real.FloatType
import ucar.ma2.*
import org.scijava.Context
import org.scijava.convert.ConvertService
import ij.IJ
import ij.ImagePlus
import ij.WindowManager
import net.imglib2.Cursor
import com.apollographql.apollo.interceptor.ApolloInterceptor
import com.apollographql.apollo.interceptor.ApolloInterceptorChain
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.logging.Logger
import com.apollographql.apollo.api.Operation
import net.imglib2.type.numeric.real.DoubleType

class ErrorLoggingInterceptor : ApolloInterceptor {

    private val logger = Logger.getLogger("ApolloGraphQLErrorLogger")

    override fun <D : Operation.Data> intercept(
        request: ApolloRequest<D>,
        chain: ApolloInterceptorChain
    ): Flow<ApolloResponse<D>> {
        return chain.proceed(request).map { response ->
            val operationName = request.operation.name()
            val errors = response.errors
            if (!errors.isNullOrEmpty()) {
                logger.warning("GraphQL errors in operation $operationName:")
                errors.forEach { error ->
                    logger.warning(" - ${error.message}")
                }
            }
            response
        }
    }
}


class LogInterceptor(val service_name: String) : HttpInterceptor {
    override suspend fun intercept(
        request: HttpRequest,
        chain: HttpInterceptorChain
    ): HttpResponse {
        try {
            var result =  chain.proceed(request)
            return result

        } catch (e: Exception) {
            println("$service_name: Error during request execution: ${e.message}")
            println("$service_name:Request URL: ${request.url}")
            println("$service_name: Request method: ${request.method}")
            throw e
        }

    }
}





class Unlok(alias: Alias, tokens: TokenManager) {
    private val apolloClient: ApolloClient =
            ApolloClient.Builder()
                    .serverUrl(alias.to_http_path("graphql"))
                    .addHttpInterceptor(AuthorizationInterceptor { tokens.accessToken() })
                .addHttpInterceptor(LogInterceptor("unlok"))
                .addInterceptor(AuthRetryInterceptor(tokens))
                    .build()

    fun getClient(): ApolloClient {
        return apolloClient
    }

    suspend fun getUser(): MeQuery.Data {



        val client = getClient()
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

}

class DatalayerStore(store: S3Store, storeId: String) {
    val store = store
    val storeId = storeId
}

class Datalayer(alias: Alias, mikro: Mikro) {
    private var mikro = mikro
    private var alias = alias

    // Request a fresh Zarr upload grant. Unlike the old `requestUpload`, the server
    // now assigns the object `key` (and `store` id) itself — we no longer pass one in.
    suspend fun requestStore(): DatalayerStore {

        val client = mikro.getClient()

        val response =
                client.mutation(RequestZarrUploadMutation(RequestZarrUploadInput()))
                        .execute()

        val accessKeyId = response.data?.requestZarrUpload?.accessKey
        val secretAccessKey = response.data?.requestZarrUpload?.secretKey
        val sessionToken = response.data?.requestZarrUpload?.sessionToken
        val bucketName = response.data?.requestZarrUpload?.bucket
        val key = response.data?.requestZarrUpload?.key
        val store = response.data?.requestZarrUpload?.store

        if (accessKeyId == null ||
                        secretAccessKey == null ||
                        sessionToken == null ||
                        bucketName == null ||
                        key == null ||
                        store == null
        ) {
            throw Exception("Failed to retrieve S3 credentials")
        }

        val sessionCredentials =
                AwsSessionCredentials.create(accessKeyId, secretAccessKey, sessionToken)

        val s3Client =
                S3Client.builder()
                        .endpointOverride(URI.create(alias.to_http_path("")))
                        .credentialsProvider(StaticCredentialsProvider.create(sessionCredentials))
                        .region(Region.US_EAST_1)
                        .forcePathStyle(true)
                        .build()

        return DatalayerStore(S3Store(s3Client, bucketName, key), store)
    }

    // Mark a Zarr upload as complete so the server validates and finalizes the store.
    // Must be called after the array bytes are written and before the store id is used.
    suspend fun finishStore(storeId: String) {
        val client = mikro.getClient()
        client.mutation(FinishZarrUploadMutation(FinishZarrUploadInput(storeId = storeId)))
                .execute()
    }


    suspend fun requestAccess(storeId: String): DatalayerStore {

        val client = mikro.getClient()

        val response =
            client.mutation(
                RequestZarrAccessMutation(
                    RequestZarrAccessInput(storeId = storeId)
                )
            )
                .execute()

        val accessKeyId = response.data?.requestZarrAccess?.accessKey
        val secretAccessKey = response.data?.requestZarrAccess?.secretKey
        val sessionToken = response.data?.requestZarrAccess?.sessionToken
        val bucketName = response.data?.requestZarrAccess?.bucket
        val key = response.data?.requestZarrAccess?.key

        if (accessKeyId == null ||
            secretAccessKey == null ||
            sessionToken == null ||
            bucketName == null ||
            key == null
        ) {
            throw Exception("Failed to retrieve S3 credentials")
        }

        val sessionCredentials =
            AwsSessionCredentials.create(accessKeyId, secretAccessKey, sessionToken)

        val s3Client =
            S3Client.builder()
                .endpointOverride(URI.create(alias.to_http_path("")))
                .credentialsProvider(StaticCredentialsProvider.create(sessionCredentials))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .build()

        return DatalayerStore(S3Store(s3Client, bucketName, key), storeId)

    }





}

class Mikro(alias: Alias, tokens: TokenManager) {
    private val apolloClient: ApolloClient =
            ApolloClient.Builder()
                    .serverUrl(alias.to_http_path("graphql"))
                    .addHttpInterceptor(AuthorizationInterceptor { tokens.accessToken() })
                .addHttpInterceptor(LogInterceptor("mikro"))
                .addInterceptor(AuthRetryInterceptor(tokens))
                .addInterceptor(ErrorLoggingInterceptor())
                    .build()

    fun getClient(): ApolloClient {
        return apolloClient
    }
}

class Rekuest(alias: Alias, tokens: TokenManager) {
    private val apolloClient: ApolloClient =
            ApolloClient.Builder()
                    .serverUrl(alias.to_http_path("graphql"))
                    .addHttpInterceptor(AuthorizationInterceptor { tokens.accessToken() })
                .addHttpInterceptor(LogInterceptor("rekuest"))
                .addInterceptor(AuthRetryInterceptor(tokens))
                .addInterceptor(ErrorLoggingInterceptor())
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

// A rekuest STRUCTURE arg arrives either as a bare id string or, more commonly, as the shrunk
// wire form {"__identifier": "@mikro/image", "object": "<id>"} (see rekuest-next
// structures/serialization/postman.py:ashrink_arg). This returns the id for both shapes.
fun structureArgId(value: JsonElement?): String {
    if (value == null) throw IllegalArgumentException("Missing structure argument")
    return when (value) {
        is JsonObject ->
                value["object"]?.jsonPrimitive?.contentOrNull
                        ?: throw IllegalArgumentException("Structure arg missing 'object': $value")
        else -> value.jsonPrimitive.content
    }
}

// Builds the shrunk wire form rekuest's aexpand_return requires for a STRUCTURE return — a bare
// id string is rejected there, it must be the tagged {"__identifier", "object"} dict.
fun structureReturn(identifier: String, id: String): JsonElement = buildJsonObject {
    put("__identifier", identifier)
    put("object", id)
}

fun <T : RealType<T>> imgPlusToCTZYXUcarArray(imgPlus: ImgPlus<T>): ucar.ma2.Array {
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

    // finalToImgDim maps the canonical axes (c,t,z,y,x) to their ImgPlus dimension index.
    val finalToImgDim = arrayOf(cDim, tDim, zDim, yDim, xDim)

    val ra: RandomAccess<T> = imgPlus.randomAccess()

    // Preserve the source dtype rather than force-casting to UINT32. The ucar dtype is chosen
    // from the ImgLib2 pixel type; `realDouble` carries the correct (unsigned) magnitude for
    // every integer type up to 32-bit, so a single read path fills any branch.
    val sample = imgPlus.firstElement()
    val ucarType: UcarDataType =
            when (sample) {
                is UnsignedByteType -> UcarDataType.UBYTE
                is UnsignedShortType -> UcarDataType.USHORT
                is UnsignedIntType -> UcarDataType.UINT
                is ByteType -> UcarDataType.BYTE
                is ShortType -> UcarDataType.SHORT
                is IntType -> UcarDataType.INT
                is FloatType -> UcarDataType.FLOAT
                is DoubleType -> UcarDataType.DOUBLE
                else -> UcarDataType.FLOAT
            }

    // Canonical c,t,z,y,x. The ucar IndexIterator walks row-major (x fastest), which matches
    // the loop nesting below, so values land in the right cells.
    val shape = intArrayOf(cSize, tSize, zSize, ySize, xSize)
    val ucarArray: ucar.ma2.Array = ucar.ma2.Array.factory(ucarType, shape)
    val iter = ucarArray.indexIterator

    val position = LongArray(numDimensions)
    for (c in 0 until cSize) {
        for (t in 0 until tSize) {
            for (z in 0 until zSize) {
                for (y in 0 until ySize) {
                    for (x in 0 until xSize) {
                        java.util.Arrays.fill(position, 0L)
                        finalToImgDim[0]?.let { position[it] = c.toLong() }
                        finalToImgDim[1]?.let { position[it] = t.toLong() }
                        finalToImgDim[2]?.let { position[it] = z.toLong() }
                        finalToImgDim[3]?.let { position[it] = y.toLong() }
                        finalToImgDim[4]?.let { position[it] = x.toLong() }

                        ra.setPosition(position)
                        val v = ra.get().realDouble
                        when (ucarType) {
                            UcarDataType.UBYTE, UcarDataType.BYTE -> iter.setByteNext(v.toInt().toByte())
                            UcarDataType.USHORT, UcarDataType.SHORT -> iter.setShortNext(v.toInt().toShort())
                            UcarDataType.UINT, UcarDataType.INT -> iter.setIntNext(v.toLong().toInt())
                            UcarDataType.FLOAT -> iter.setFloatNext(v.toFloat())
                            else -> iter.setDoubleNext(v)
                        }
                    }
                }
            }
        }
    }

    return ucarArray
}


fun loadArrayAsDataset(app: App, store: DatalayerStore, name: String): Dataset {

    val zarrArray = Array.open(store.store.resolve())
    val zarrArrayMetadata = zarrArray.metadata()
    val datasetService = app.datasetService

    // read takes (offset, shape) as long[]; metadata.shape is already long[].
    val array: ucar.ma2.Array =
            zarrArray.read(
                    longArrayOf(0, 0, 0, 0, 0),
                    zarrArrayMetadata.shape,
            )

    // Canonical c,t,z,y,x — matches the upload order and the stored dimension names.
    val shape = array.shape
    val channels = shape[0]  // Channels
    val time = shape[1]      // Timepoints
    val zSlices = shape[2]   // Z-stack depth
    val height = shape[3]    // Image height (Y)
    val width = shape[4]     // Image width (X)
    // NOTE: each dtype branch zips the ucar IndexIterator (x fastest … c slowest) against the
    // ImgLib2 cursor of an (x,y,z,c,t) image (x fastest … t slowest). The two agree on x,y,z;
    // they only diverge in the two slowest axes when an image has BOTH channels>1 and time>1,
    // which this single-image upload path does not currently produce.

    val dataset: Dataset = when (array.dataType) {
        UcarDataType.UBYTE, UcarDataType.BYTE -> {
            // uint8 reads back as ucar UBYTE; mask to keep the unsigned 0..255 magnitude.
            val img = ArrayImgs.unsignedBytes(width.toLong(), height.toLong(), zSlices.toLong(), channels.toLong(), time.toLong())
            val cursor: Cursor<UnsignedByteType> = img.cursor()
            val iterator = array.indexIterator // NetCDF's IndexIterator to access elements
            while (cursor.hasNext() && iterator.hasNext()) {
                cursor.fwd()
                cursor.get().set(iterator.byteNext.toInt() and 0xFF)
            }
            datasetService.create(img)
        }
        UcarDataType.SHORT -> {
            val img = ArrayImgs.unsignedShorts(width.toLong(), height.toLong(), zSlices.toLong(), channels.toLong(), time.toLong())
            val cursor: Cursor<UnsignedShortType> = img.cursor()
            val iterator = array.indexIterator
            while (cursor.hasNext() && iterator.hasNext()) {
                cursor.fwd()
                cursor.get().set(iterator.shortNext.toInt() and 0xFFFF) // Correctly reads short values
            }
            datasetService.create(img)
        }
        UcarDataType.USHORT -> {
            val img = ArrayImgs.unsignedShorts(width.toLong(), height.toLong(), zSlices.toLong(), channels.toLong(), time.toLong())
            val cursor: Cursor<UnsignedShortType> = img.cursor()
            val iterator = array.indexIterator
            while (cursor.hasNext() && iterator.hasNext()) {
                cursor.fwd()
                cursor.get().set(iterator.shortNext.toInt() and 0xFFFF) // Correctly reads short values
            }
            datasetService.create(img)
        }

        UcarDataType.UINT -> { // Treat as UINT32
            val img = ArrayImgs.unsignedInts(width.toLong(), height.toLong(), zSlices.toLong(), channels.toLong(), time.toLong())
            val cursor: Cursor<net.imglib2.type.numeric.integer.UnsignedIntType> = img.cursor()
            val iterator = array.indexIterator
            while (cursor.hasNext() && iterator.hasNext()) {
                cursor.fwd()
                cursor.get().set(iterator.intNext.toLong() and 0xFFFFFFFFL) // Ensures correct unsigned handling
            }
            datasetService.create(img)
        }
        UcarDataType.INT -> { // Treat as UINT32
            val img = ArrayImgs.ints(width.toLong(), height.toLong(), zSlices.toLong(), channels.toLong(), time.toLong())
            val cursor: Cursor<net.imglib2.type.numeric.integer.IntType> = img.cursor()
            val iterator = array.indexIterator
            while (cursor.hasNext() && iterator.hasNext()) {
                cursor.fwd()
                cursor.get().set(iterator.intNext.toInt()) // Ensures correct unsigned handling
            }
            datasetService.create(img)
        }
        UcarDataType.FLOAT -> {
            val img = ArrayImgs.floats(width.toLong(), height.toLong(), zSlices.toLong(), channels.toLong(), time.toLong())
            val cursor: Cursor<FloatType> = img.cursor()
            val iterator = array.indexIterator
            while (cursor.hasNext() && iterator.hasNext()) {
                cursor.fwd()
                cursor.get().set(iterator.floatNext) // Correctly reads float values
            }
            datasetService.create(img)
        }
        UcarDataType.DOUBLE -> {
            val img = ArrayImgs.doubles(width.toLong(), height.toLong(), zSlices.toLong(), channels.toLong(), time.toLong())
            val cursor: Cursor<DoubleType> = img.cursor()
            val iterator = array.indexIterator
            while (cursor.hasNext() && iterator.hasNext()) {
                cursor.fwd()
                cursor.get().set(iterator.doubleNext) // Correctly reads float values
            }
            datasetService.create(img)
        }
        else -> throw IllegalArgumentException("Unsupported data type: ${array.dataType}")
    }


    return dataset


}








// Run an arbitrary ImageJ (IJ1) macro against a Dataset and return the transformed Dataset.
//
// The macro engine (`ij.IJ.runMacro`) is IJ1-only, so this needs the ImageJ legacy layer on the
// classpath — present when the plugin runs inside Fiji, absent from the standalone `./gradlew run`
// ImageJ (see build.gradle.kts: imagej-legacy is compileOnly). The conversion Dataset<->ImagePlus
// is done through the SciJava ConvertService, whose converters imagej-legacy registers at runtime.
//
// The image is made the IJ1 "current image" via WindowManager.setTempCurrentImage — this avoids
// opening/closing an AWT window (so it is safe to call off the EDT, which is where the agent
// handler runs) and is the standard pattern for programmatic image-to-image macros, which operate
// in place on the current image. If the macro replaces the current image, we read that back instead.
fun runMacroOnDataset(app: App, dataset: Dataset, macro: String): Dataset {
    val context = app.datasetService.context()
    val convertService = context.getService(ConvertService::class.java)
            ?: throw IllegalStateException("ConvertService is unavailable in this context.")

    val imp = convertService.convert(dataset, ImagePlus::class.java)
            ?: throw IllegalStateException(
                    "Could not convert the image to an IJ1 ImagePlus. Running macros requires the " +
                            "ImageJ legacy layer (Fiji / imagej-legacy) on the classpath — it is not " +
                            "available in the standalone `./gradlew run` ImageJ."
            )

    val result: ImagePlus =
            try {
                WindowManager.setTempCurrentImage(imp)
                IJ.runMacro(macro)
                // Prefer whatever the macro left as the current image (a macro may replace it);
                // fall back to the in-place-modified input.
                WindowManager.getCurrentImage() ?: imp
            } finally {
                WindowManager.setTempCurrentImage(null)
            }

    return convertService.convert(result, Dataset::class.java)
            ?: throw IllegalStateException("Could not convert the macro result back to a Dataset.")
}


class Arkitekt(
        private val uiService: UIService,
        private val datasetService: DatasetService,
        private val imageDisplayService: ImageDisplayService
) {
    private val client = OkHttpClient()

    // The background coroutine running the agent's provide loop (WebSocket to /agi).
    // Tracked so logout() can tear the connection down.
    private var provideJob: Job? = null

    // The background coroutine running an in-flight login (device-code challenge poll).
    // Tracked so it can be cancelled (cancelLogin()/logout()) mid-flight.
    private var loginJob: Job? = null

    // The lok/management endpoint now arrives as ActiveFakts.self.alias, so it is no longer a
    // requirement here. Only the services the app actually talks to are requested.
    private val manifest =
            Manifest(
                    identifier = "imagej",
                    version = "0.1.0",
                    scopes = listOf("openid"),
                    node_id = NodeId.getOrSet(),
                    requirements = listOf(
                            Requirement(key = "rekuest", service = "live.arkitekt.rekuest"),
                            Requirement(key = "mikro", service = "live.arkitekt.mikro"),
                            Requirement(key = "datalayer", service = "live.arkitekt.s3")
                    )
            )

    // Required services that must be granted and reachable for the app to function.
    private val requiredKeys = listOf("rekuest", "mikro", "datalayer")

    private val cache = FaktsCache(File(System.getProperty("user.home"), ".arkitekt/fakts_cache.json"))

    // The "v2:" prefix binds the key to the protocol generation, so a protocol-1 record can
    // never be mistaken for a protocol-2 one even if the manifest and url are unchanged.
    private fun cacheHash(url: String): String {
        val raw = "v2:" + faktsJson.encodeToString(manifest) + "|" + url
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * True if a login for [url] can proceed silently, without the device-code browser flow.
     *
     * Under protocol 2 that is not "do I have a config" but "do I hold a refresh token": the
     * config alone is inert, because there are no client credentials left to mint a token from.
     */
    fun hasCachedConfig(url: String): Boolean =
            cache.load(cacheHash(url))?.token?.refresh_token != null

    // The protocol driver lives in Fakts.kt; this class only sequences it and owns the cache.
    private val fakts = FaktsClient(client)

    // Open the approval page. `verification_uri_complete` already has the user code substituted;
    // `verification_uri` still carries the literal `{code}`, so it is only a last-resort fallback
    // the human has to complete by hand.
    private fun openApprovalPage(authorization: DeviceAuthorization) {
        val deviceUrl = authorization.verification_uri_complete
                ?: authorization.verification_uri?.replace(
                        "{code}",
                        authorization.user_code.orEmpty()
                )
                ?: throw DemandError("Device authorization carried no verification URI to approve at.")

        val osName = System.getProperty("os.name").lowercase()
        try {
            when {
                osName.contains("win") ->
                        ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", deviceUrl).start()
                osName.contains("mac") -> ProcessBuilder("open", deviceUrl).start()
                osName.contains("nix") || osName.contains("nux") ->
                        ProcessBuilder("xdg-open", deviceUrl).start()
                else -> println("Please open this URL to approve the app: $deviceUrl")
            }
        } catch (e: Exception) {
            println("Could not open a browser automatically. Approve the app at: $deviceUrl")
        }
        println("Waiting for approval at: $deviceUrl")
    }

    // The full interactive grant: discover -> device authorize -> human approves -> poll.
    // There is no separate claim step any more; the token response carries the config with it.
    suspend fun negotiate(url: String): FaktsSession {
        val endpoint = fakts.discover(url)
        val authorization = fakts.deviceAuthorize(endpoint, manifest)

        openApprovalPage(authorization)

        val grant =
                fakts.pollToken(
                        // The device-auth response re-states the token endpoint; prefer it.
                        tokenEndpoint = authorization.token_endpoint ?: endpoint.token_endpoint,
                        deviceCode = authorization.device_code,
                        clientId = authorization.client_id,
                        interval = authorization.interval,
                        expiresIn = authorization.expires_in
                )

        return FaktsSession(cacheHash(url), endpoint, grant.fakts, grant.token)
    }

    // Cache-first session loading. Returns (session, fromCache); fromCache enables self-healing.
    suspend fun getSession(url: String, forceRefresh: Boolean = false): Pair<FaktsSession, Boolean> {
        if (!forceRefresh) {
            cache.load(cacheHash(url))?.let { return it to true }
        }
        val session = negotiate(url)
        cache.save(session)
        return session to false
    }


    fun login(
            url: String,
            onSuccess: (MeQuery.Data) -> Unit,
            onError: (Throwable) -> Unit = {}
    ) {
        // Cancel any in-flight attempt before starting a new one (e.g. rapid Re-Login).
        loginJob?.cancel()
        ArkitektState.setState(ConnState.Connecting)
        loginJob = CoroutineScope(Dispatchers.Default).launch {
            try {
                val result = alogin(url)
                ArkitektState.setState(ConnState.Connected(result.me.username))
                withContext(Dispatchers.Main) { onSuccess(result) }
            } catch (e: CancellationException) {
                // Cancelled by the user (clicked the button mid-challenge); state is set to
                // Disconnected by cancelLogin(), so don't surface this as an error.
                throw e
            } catch (e: Exception) {
                println("Failed to login: ${e}")
                ArkitektState.setState(ConnState.Error(e.message ?: e.toString()))
                withContext(Dispatchers.Main) { onError(e) }
            }
        }
    }

    /**
     * Cancel an in-flight login (the device-code challenge poll). Safe to call when nothing is
     * running. Resets the shared state to Disconnected.
     */
    fun cancelLogin() {
        loginJob?.cancel()
        loginJob = null
        ArkitektState.setState(ConnState.Disconnected)
    }

    // Port of mikro_next `rechunk` (mikro_next/utils.py): aim for ~20 MB chunks given the
    // dtype's byte width. Shape and chunks are in canonical c,t,z,y,x order: c is always 1,
    // x/y are kept whole (capped at 2048), then z and t are sized to fit the byte budget.
    fun generateChunkShape(inarrayShape: IntArray, itemSize: Int): IntArray {
        if (inarrayShape.size != 5) {
            throw IllegalArgumentException(
                    "Input array shape must have exactly 5 dimensions: c, t, z, y, x"
            )
        }

        val cs = inarrayShape[0]
        val ts = inarrayShape[1]
        val zs = inarrayShape[2]
        val ys = inarrayShape[3]
        val xs = inarrayShape[4]

        // Don't bother rechunking small arrays — store them as a single chunk.
        val totalElements = 1L * cs * ts * zs * ys * xs
        if (totalElements < 1L * 2048 * 2048) {
            return inarrayShape.copyOf()
        }

        val chunkSizeInBytes = 20_000_000L
        val x = if (xs > 2048) 2048 else xs
        val y = if (ys > 2048) 2048 else ys

        val bestZ = Math.ceil(chunkSizeInBytes.toDouble() / (x.toLong() * y * itemSize)).toInt()
        val z = if (bestZ < zs) bestZ else zs

        val bestT = Math.ceil(chunkSizeInBytes.toDouble() / (x.toLong() * y * z * itemSize)).toInt()
        val t = if (bestT < ts) bestT else ts

        return intArrayOf(1, t, z, y, x)
    }

    suspend fun uploadArray(
            app: App,
            inarray: ucar.ma2.Array,
            name: String
    ): FromArrayLikeMutation.FromArrayLike {

        val s3Client = app.datalayer.requestStore()

        // Preserve the source dtype: map the incoming ucar dtype to the matching zarr v3 dtype
        // (and its byte width, which drives the ~20 MB chunk heuristic).
        val (zarrType, itemSize) =
                when (inarray.dataType) {
                    UcarDataType.UBYTE -> DataType.UINT8 to 1
                    UcarDataType.BYTE -> DataType.INT8 to 1
                    UcarDataType.USHORT -> DataType.UINT16 to 2
                    UcarDataType.SHORT -> DataType.INT16 to 2
                    UcarDataType.UINT -> DataType.UINT32 to 4
                    UcarDataType.INT -> DataType.INT32 to 4
                    UcarDataType.FLOAT -> DataType.FLOAT32 to 4
                    UcarDataType.DOUBLE -> DataType.FLOAT64 to 8
                    else -> throw IllegalArgumentException("Unsupported dtype for upload: ${inarray.dataType}")
                }

        val array =
                Array.create(
                        s3Client.store.resolve(),
                        Array.metadataBuilder()
                                .withShape(*inarray.shape.map { i -> i.toLong() }.toLongArray())
                                .withDimensionNames("c", "t", "z", "y", "x")
                                .withDataType(zarrType)
                                .withChunkShape(
                                        *generateChunkShape(
                                                inarray.shape.map { i -> i.toInt() }.toIntArray(),
                                                itemSize
                                        )
                                )
                                .withFillValue(0)
                                .withCodecs { it.withBlosc() }
                                .build()!!
                )

        array.write(listOf(0L, 0L, 0L, 0L, 0L).toLongArray(), inarray)

        // Finalize the upload before registering it (the server validates the store).
        app.datalayer.finishStore(s3Client.storeId)

        var mutation =
                FromArrayLikeMutation(FromArrayLikeInput(array = s3Client.storeId, name = name))

        val client = app.mikro.getClient()

        val response = client.mutation(mutation).execute()

        println("Response: ${response.data}")
        return response.dataOrThrow().fromArrayLike
    }

    suspend fun runX(app: App, args: Map<String, JsonElement?>): Map<String, JsonElement?> {

        imageDisplayService.imageDisplays.forEach { d -> println(d) }

        var active = imageDisplayService.getActiveDataset(imageDisplayService.imageDisplays[0])

        var name = args.get("name")!!.jsonPrimitive.content

        var array = imgPlusToCTZYXUcarArray(active.imgPlus)

        var image = uploadArray(app, array, name)

        return mapOf(Pair("image", structureReturn("@mikro/image", image.id)))
    }


    suspend fun loadImage(app: App, args: Map<String, JsonElement?>): Map<String, JsonElement?> {

        imageDisplayService.imageDisplays.forEach { d -> println(d) }

        val imageId = structureArgId(args["image"])

        val response = app.mikro.getClient().query(GetImageQuery(id = imageId)).execute()

        println("Response: ${response.data}")
        response.data.let { data ->
            if (data == null) {
                throw Exception("Failed to retrieve user data")
            }
            println(data)


            var store = app.datalayer.requestAccess((data.image.store.id))

            var dataset = loadArrayAsDataset(app, store, data.image.name)

            app.imageDisplayService.createImageDisplay(dataset)

        }

        return mapOf(Pair("image", structureReturn("@mikro/image", imageId)))

    }

    // Download an image, run an arbitrary ImageJ macro over it (image in -> image out), and upload
    // the result as a new @mikro/image. The macro sees the downloaded image as the IJ1 "current
    // image" and typically transforms it in place (e.g. run("Gaussian Blur...", "sigma=2")).
    suspend fun runImageToImageMacro(app: App, args: Map<String, JsonElement?>): Map<String, JsonElement?> {

        val imageId = structureArgId(args["image"])
        val macro = args["macro"]?.jsonPrimitive?.contentOrNull
                ?: throw IllegalArgumentException("Missing 'macro' argument")
        val name = args["name"]?.jsonPrimitive?.contentOrNull ?: "Macro result"

        // 1. Download the input image as a Dataset (same access path as loadImage).
        val response = app.mikro.getClient().query(GetImageQuery(id = imageId)).execute()
        val data = response.data ?: throw Exception("Failed to retrieve image $imageId")
        val store = app.datalayer.requestAccess(data.image.store.id)
        val inputDataset = loadArrayAsDataset(app, store, data.image.name)

        // 2. Run the macro (needs the IJ1 legacy layer; see runMacroOnDataset).
        val outputDataset = runMacroOnDataset(app, inputDataset, macro)

        // 3. Upload the transformed image as a new @mikro/image.
        val array = imgPlusToCTZYXUcarArray(outputDataset.imgPlus)
        val image = uploadArray(app, array, name)

        return mapOf(Pair("image", structureReturn("@mikro/image", image.id)))
    }

    suspend fun alogin(url: String): MeQuery.Data {

        // Resolve a granted session. A refresh rejected by the server means the cached refresh
        // chain is dead (revoked, superseded, or simply too old), so wipe the cache and run the
        // device flow from scratch — but only once, to avoid an approval loop.
        var forceRefresh = false
        var session: FaktsSession
        var instanceMap: Map<String, Alias>
        var tokens: TokenManager

        while (true) {
            // Cache-first: load the granted session, negotiating only on a cache miss (or when a
            // prior refresh failure forced a re-negotiation).
            val loaded = getSession(url, forceRefresh = forceRefresh)
            session = loaded.first
            var fromCache = loaded.second

            // Every required service must have been granted AND resolve to a reachable alias.
            var (failure, resolved) = compose(session)
            instanceMap = resolved

            // A cached session can go stale in two ways — a requirement stops being granted, or
            // its aliases stop answering — and both are fixed the same way: re-negotiate exactly
            // once and re-run the whole check before giving up.
            if (fromCache && failure != null) {
                println("Cached session no longer composes ($failure); re-negotiating once.")
                cache.clear()
                session = getSession(url, forceRefresh = true).first
                fromCache = false
                val recomposed = compose(session)
                failure = recomposed.first
                instanceMap = recomposed.second
            }

            failure?.let { throw CompositionError(it) }

            tokens = buildTokenManager(url, session)

            // A cached access token is very likely expired; prove the credentials still work
            // before wiring four clients to them.
            try {
                tokens.accessToken()
                break
            } catch (e: TokenError) {
                if (fromCache && !forceRefresh) {
                    println(
                            "The cached refresh token was rejected (${e.statusCode ?: "transport"}); " +
                                    "clearing the cache and restarting the device flow from scratch."
                    )
                    cache.clear()
                    forceRefresh = true
                    continue
                }
                throw e
            }
        }

        // NB: `fakts` on this class is the protocol client; the envelope is session.fakts.
        val envelope = session.fakts
        var rekuest = Rekuest(instanceMap["rekuest"]!!, tokens)
        var unlok = Unlok(envelope.self.alias, tokens) // lok is the deployment (self.alias)
        var mikro = Mikro(instanceMap["mikro"]!!, tokens)
        var datalayer = Datalayer(instanceMap["datalayer"]!!, mikro)

        var app =
            App(
                mikro,
                datalayer,
                unlok,
                rekuest,
                uiService,
                datasetService,
                imageDisplayService
            )

        // Tell the deployment which aliases actually answered. Best-effort; never throws.
        val aliasReports = envelope.instances.keys.associateWith { key ->
            val alias = instanceMap[key]
            if (alias != null) AliasReport(valid = true, alias_id = alias.id)
            else AliasReport(valid = false, reason = "No working alias found for service: $key")
        }
        fakts.report(
                session.endpoint,
                tokens.accessToken(),
                aliasReports,
                functional = aliasReports.values.all { it.valid }
        )

        var registry = buildFunctionRegistry(this)

        var agent = Agent(instanceMap["rekuest"]!!, tokens, registry, app)

        agent.createAgent("my_agent")
        agent.registerFunctions()


        provideJob =
                CoroutineScope(Dispatchers.Default).launch {
                    agent.provideForever()
                }

        return unlok.getUser()
    }

    /**
     * Check every required service is granted and resolve it to a reachable alias.
     *
     * Returns (failure, instanceMap), where a null failure means composed. It reports rather than
     * throws so the caller can distinguish "this cached session went stale" — worth exactly one
     * re-negotiation — from "the deployment genuinely will not serve us".
     */
    private suspend fun compose(session: FaktsSession): Pair<String?, Map<String, Alias>> {
        val ungranted = requiredKeys.filter { key ->
            val status = GrantStatus.from(session.fakts.statuses[key])
            session.fakts.instances[key] == null ||
                    status == GrantStatus.DENIED ||
                    status == GrantStatus.UNAVAILABLE
        }
        if (ungranted.isNotEmpty()) {
            val detail = ungranted.joinToString {
                "$it=${GrantStatus.from(session.fakts.statuses[it])}"
            }
            return "Required service(s) not granted: $detail" to emptyMap()
        }

        val instanceMap = buildInstanceMap(session.fakts)
        val unreachable = requiredKeys.filter { instanceMap[it] == null }
        val failure =
                if (unreachable.isEmpty()) null
                else "No reachable alias for required service(s): ${unreachable.joinToString()}"
        return failure to instanceMap
    }

    /**
     * The single token owner for this login. Every rotation is persisted back to the cache
     * before the new access token is handed out: the refresh token rotates on use, so a lost
     * one costs the user a browser round-trip on the next start.
     */
    private fun buildTokenManager(url: String, session: FaktsSession): TokenManager {
        val hash = cacheHash(url)
        return TokenManager(fakts, session.endpoint, session.token, session.fakts) {
            endpoint, activeFakts, token ->
            cache.save(FaktsSession(hash, endpoint, activeFakts, token))
        }
    }

    fun logout() {
        // Tear down the live agent connection (WebSocket provide loop) and any in-flight login.
        provideJob?.cancel()
        provideJob = null
        loginJob?.cancel()
        loginJob = null
        // Clear the cached configuration so the next login re-negotiates from scratch.
        cache.clear()
        // Remove the legacy token key written by older versions of this plugin.
        val prefs = Preferences.userNodeForPackage(Arkitekt::class.java)
        prefs.remove("token")
        ArkitektState.setState(ConnState.Disconnected)
    }
}
