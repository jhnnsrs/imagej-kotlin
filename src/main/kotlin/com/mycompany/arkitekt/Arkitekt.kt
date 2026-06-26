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
import com.google.gson.Gson
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


// Shared lenient JSON for all fakts negotiation payloads. `encodeDefaults` keeps the
// protocol's optional-with-defaults fields on the wire; `ignoreUnknownKeys` makes the
// client forward-compatible with newer servers.
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
        val public_sources: List<String> = emptyList()
)

// ---- Discovery: GET {url}/.well-known/fakts ---------------------------------------------

@Serializable
data class FaktsLayer(
        val identifier: String,
        val kind: String,
        val dns_probe: String? = null,
        val get_probe: String? = null
)

@Serializable
data class FaktsEndpoint(
        val name: String,
        val base_url: String? = null,
        val version: String? = null,
        val description: String? = null,
        val configure_url: String? = null,
        val claim_url: String? = null,
        val retrieve_url: String? = null,
        val ca_crt: String? = null,
        val layers: List<FaktsLayer> = emptyList()
)

// ---- Demand: POST {base}start/ + {base}challenge/ ---------------------------------------

@Serializable
data class StartRequest(
        val manifest: Manifest,
        val expiration_time_seconds: Int = 300,
        val redirect_uris: List<String> = emptyList(),
        val requested_client_kind: String = "development",
        val supported_layers: List<String> = emptyList()
)

// The negotiation endpoints share a `status` envelope plus status-specific fields.
@Serializable
data class StartResponse(val status: String, val code: String? = null, val error: String? = null)

@Serializable data class ChallengeRequest(val code: String)

@Serializable
data class ChallengeResponse(
        val status: String,
        val token: String? = null,
        val message: String? = null,
        val error: String? = null
)

// ---- Claim: POST {base}claim/ -----------------------------------------------------------

@Serializable data class ClaimRequest(val token: String, val secure: Boolean)

@Serializable
data class ClaimResponse(
        val status: String,
        val config: ActiveFakts? = null,
        val error: String? = null
)

@Serializable
data class AuthFakt(
        public val client_id: String,
        public val client_secret: String,
        public val client_token: String? = null,
        public val token_url: String,
        public val report_url: String? = null,
        public val scopes: List<String> = emptyList()
)


@Serializable
data class Alias(
        val id: String? = null,
        val host: String,
        val port: Int? = null,
        val ssl: Boolean,
        val path: String? = null,
        val challenge: String
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



// ---- ActiveFakts: what the server grants ------------------------------------------------

@Serializable
data class Instance(val service: String, val identifier: String, val aliases: List<Alias>)

@Serializable
data class SelfFakt(val deployment_name: String, val alias: Alias)

@Serializable
data class ActiveFakts(
        val self: SelfFakt,
        val auth: AuthFakt,
        val instances: Map<String, Instance>,
        val statuses: Map<String, String> = emptyMap()
)

// Outcome of a single requirement; unrecognized server values coerce to UNKNOWN.
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

@Serializable
data class TokenResponse(
        val access_token: String,
        val token_type: String,
        val scope: String,
        val expires_in: String
)

// ---- Errors -----------------------------------------------------------------------------

open class FaktsError(message: String) : Exception(message)

class DiscoveryError(message: String) : FaktsError(message)

class DemandError(message: String) : FaktsError(message)

class ClaimError(message: String) : FaktsError(message)

// Raised when the OAuth2 token exchange fails. Carries the HTTP status (or null for a
// transport-level failure) so callers can react to e.g. a 400 (stale client creds) by
// re-negotiating from scratch.
class TokenError(val statusCode: Int?, message: String) : FaktsError(message)

// Raised when a *required* service could not be resolved to a working, granted alias.
class CompositionError(message: String) : FaktsError(message)

// ---- Cache: the granted ActiveFakts, keyed to a hash of manifest + server url ----------

@Serializable
data class CachedFakts(val hash: String, val config: ActiveFakts)

class FaktsCache(private val file: File) {
    fun load(hash: String): ActiveFakts? {
        return try {
            if (!file.exists()) return null
            val cached = faktsJson.decodeFromString<CachedFakts>(file.readText())
            // A changed manifest or server url changes the hash and invalidates the cache.
            if (cached.hash == hash) cached.config else null
        } catch (e: Exception) {
            println("Failed to read fakts cache: ${e.message}")
            null
        }
    }

    fun save(hash: String, config: ActiveFakts) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(faktsJson.encodeToString(CachedFakts(hash, config)))
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





class Unlok(alias: Alias, token: String) {
    private val apolloClient: ApolloClient =
            ApolloClient.Builder()
                    .serverUrl(alias.to_http_path("graphql"))
                    .addHttpInterceptor(AuthorizationInterceptor(token))
                .addHttpInterceptor(LogInterceptor("unlok"))
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

// Challenge each alias of a service (GET on its challenge URL must answer 200 iff reachable)
// and keep the first that answers.
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

class Mikro(alias: Alias, token: String) {
    private val apolloClient: ApolloClient =
            ApolloClient.Builder()
                    .serverUrl(alias.to_http_path("graphql"))
                    .addHttpInterceptor(AuthorizationInterceptor(token))
                .addHttpInterceptor(LogInterceptor("mikro"))
                .addInterceptor(ErrorLoggingInterceptor())
                    .build()

    fun getClient(): ApolloClient {
        return apolloClient
    }
}

class Rekuest(alias: Alias, token: String) {
    private val apolloClient: ApolloClient =
            ApolloClient.Builder()
                    .serverUrl(alias.to_http_path("graphql"))
                    .addHttpInterceptor(AuthorizationInterceptor(token))
                .addHttpInterceptor(LogInterceptor("rekuest"))
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








class Arkitekt(
        private val uiService: UIService,
        private val datasetService: DatasetService,
        private val imageDisplayService: ImageDisplayService
) {
    private val client = OkHttpClient()
    private val gson = Gson()

    // The background coroutine running the agent's provide loop (WebSocket to /agi).
    // Tracked so logout() can tear the connection down.
    private var provideJob: Job? = null

    // The lok/management endpoint now arrives as ActiveFakts.self.alias, so it is no longer a
    // requirement here. Only the services the app actually talks to are requested.
    private val manifest =
            Manifest(
                    identifier = "imagej",
                    version = "0.1.0",
                    scopes = listOf("openid"),
                    requirements = listOf(
                            Requirement(key = "rekuest", service = "live.arkitekt.rekuest"),
                            Requirement(key = "mikro", service = "live.arkitekt.mikro"),
                            Requirement(key = "datalayer", service = "live.arkitekt.s3")
                    )
            )

    // Required services that must be granted and reachable for the app to function.
    private val requiredKeys = listOf("rekuest", "mikro", "datalayer")

    private val cache = FaktsCache(File(System.getProperty("user.home"), ".arkitekt/fakts_cache.json"))

    private fun cacheHash(url: String): String {
        val raw = faktsJson.encodeToString(manifest) + "|" + url
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    suspend fun loginUser(unlok: AuthFakt): String {
        val tokenUrl = unlok.token_url
        val bodyString =
                "grant_type=client_credentials&client_id=${unlok.client_id}&client_secret=${unlok.client_secret}"
        val body =
                bodyString.toRequestBody(
                        "application/x-www-form-urlencoded; charset=utf-8".toMediaTypeOrNull()
                )

        val request = Request.Builder().url(tokenUrl).post(body).build()

        // The token endpoint is the OAuth2 client_credentials exchange. When it fails it is
        // almost always for a reason the server spells out in the response body
        // (e.g. {"error":"invalid_client","error_description":"..."}), so surface URL,
        // status line and body verbatim instead of just the bare status code.
        println("Obtaining token from $tokenUrl (grant_type=client_credentials, client_id=${unlok.client_id})")

        return withContext(Dispatchers.IO) {
            val response =
                    try {
                        client.newCall(request).execute()
                    } catch (e: Exception) {
                        // Transport-level failure: DNS, TLS, connection refused, timeout, ...
                        throw TokenError(
                                null,
                                "Token request to $tokenUrl failed before a response was received " +
                                        "(${e.javaClass.simpleName}: ${e.message}). " +
                                        "Check that token_url is reachable and the TLS/cert is valid."
                        )
                    }

            response.use {
                val responseBody = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    throw TokenError(
                            response.code,
                            "Failed to obtain token from $tokenUrl.\n" +
                                    "  HTTP status : ${response.code} ${response.message}\n" +
                                    "  client_id   : ${unlok.client_id}\n" +
                                    "  scopes      : ${unlok.scopes.joinToString(" ").ifEmpty { "(none)" }}\n" +
                                    "  response    : ${responseBody.ifBlank { "(empty body)" }}"
                    )
                }

                val tokenResponse =
                        try {
                            gson.fromJson(responseBody, TokenResponse::class.java)
                        } catch (e: Exception) {
                            throw TokenError(
                                    response.code,
                                    "Token endpoint $tokenUrl returned HTTP ${response.code} but the body " +
                                            "could not be parsed as a token response " +
                                            "(${e.message}).\n  response: ${responseBody.ifBlank { "(empty body)" }}"
                            )
                        }

                val accessToken = tokenResponse?.access_token
                if (accessToken.isNullOrBlank()) {
                    throw TokenError(
                            response.code,
                            "Token endpoint $tokenUrl returned HTTP ${response.code} with no access_token.\n" +
                                    "  response: ${responseBody.ifBlank { "(empty body)" }}"
                    )
                }

                accessToken
            }
        }
    }

    // Helper: POST a JSON body and return the (string) response body, throwing on transport failure.
    private suspend fun postJson(url: String, json: String): String {
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        val request = Request.Builder().url(url).post(body).build()
        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    throw FaktsError("POST $url failed (${response.code}): ${text.take(300)}")
                }
                text
            }
        }
    }

    // 1. Discovery — GET {url}/.well-known/fakts
    suspend fun discover(url: String): FaktsEndpoint {
        val discoveryUrl = url.trimEnd('/') + "/.well-known/fakts"
        val request = Request.Builder().url(discoveryUrl).get().build()
        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    throw DiscoveryError("Discovery at $discoveryUrl failed (${response.code}): ${text.take(300)}")
                }
                try {
                    faktsJson.decodeFromString<FaktsEndpoint>(text)
                } catch (e: Exception) {
                    throw DiscoveryError("Could not parse discovery response from $discoveryUrl: ${e.message}")
                }
            }
        }
    }

    // 2. Demand (interactive device code) — POST {base}start/ then poll {base}challenge/.
    //    Returns the claim token once the user approves in the browser.
    suspend fun demand(base: String, configureUrl: String): String {
        // start/ -> device code
        val startBody = faktsJson.encodeToString(StartRequest(manifest = manifest))
        val startText = postJson("${base}start/", startBody)
        val start = faktsJson.decodeFromString<StartResponse>(startText)
        if (start.status != "granted" || start.code == null) {
            throw DemandError("start/ refused: ${start.error ?: start.status}")
        }
        val code = start.code

        // Open the browser for one-time consent: {configure_url}{code}
        val deviceUrl = "$configureUrl$code"
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

        // Poll challenge/ once per second until the code's expiration window elapses.
        val challengeBody = faktsJson.encodeToString(ChallengeRequest(code))
        repeat(300) {
            delay(1000)
            val text =
                    try {
                        postJson("${base}challenge/", challengeBody)
                    } catch (e: Exception) {
                        println("challenge/ poll failed, retrying: ${e.message}")
                        return@repeat
                    }
            val answer = faktsJson.decodeFromString<ChallengeResponse>(text)
            when (answer.status) {
                "granted" ->
                        return answer.token
                                ?: throw DemandError("challenge/ granted without a token")
                "denied" -> throw DemandError("The user declined the app: ${answer.message ?: ""}")
                "error" -> throw DemandError("challenge/ error: ${answer.error ?: ""}")
                else -> {} // waiting / pending — keep polling
            }
        }
        throw DemandError("Device code expired before it was approved.")
    }

    // 3. Claim — POST {base}claim/ exchanges the claim token for the ActiveFakts config.
    suspend fun claim(base: String, token: String, secure: Boolean): ActiveFakts {
        val claimBody = faktsJson.encodeToString(ClaimRequest(token = token, secure = secure))
        val text = postJson("${base}claim/", claimBody)
        val response = faktsJson.decodeFromString<ClaimResponse>(text)
        if (response.status != "granted" || response.config == null) {
            throw ClaimError("claim/ refused: ${response.error ?: response.status}")
        }
        return response.config
    }

    // Full discover -> demand -> claim negotiation against a coordination server url.
    suspend fun negotiate(url: String): ActiveFakts {
        val endpoint = discover(url)
        val base = (endpoint.base_url ?: (url.trimEnd('/') + "/")).let {
            if (it.endsWith("/")) it else "$it/"
        }
        val configureUrl = endpoint.configure_url ?: "${base}configure/"
        val secure = url.startsWith("https")
        val token = demand(base, configureUrl)
        return claim(base, token, secure)
    }

    // Cache-first config loading. Returns (config, fromCache); fromCache enables self-healing.
    suspend fun getActiveFakts(url: String, forceRefresh: Boolean = false): Pair<ActiveFakts, Boolean> {
        val hash = cacheHash(url)
        if (!forceRefresh) {
            cache.load(hash)?.let { return it to true }
        }
        val fakts = negotiate(url)
        cache.save(hash, fakts)
        return fakts to false
    }


    fun login(
            url: String,
            onSuccess: (MeQuery.Data) -> Unit,
            onError: (Throwable) -> Unit = {}
    ) {
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val result = alogin(url)
                withContext(Dispatchers.Main) { onSuccess(result) }
            } catch (e: Exception) {
                println("Failed to login: ${e}")
                withContext(Dispatchers.Main) { onError(e) }
            }
        }
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

    suspend fun alogin(url: String): MeQuery.Data {

        // Resolve a granted config and exchange it for a token. A 400 from the token endpoint
        // means the cached client credentials are stale/rejected, so we wipe the cache and run
        // the full device flow from scratch — but only once, to avoid an approval loop.
        var forceRefresh = false
        var fakts: ActiveFakts
        var instanceMap: Map<String, Alias>
        var token: String

        while (true) {
            // Cache-first: load the granted config, negotiating only on a cache miss
            // (or when a prior 400 forced a refresh).
            val loaded = getActiveFakts(url, forceRefresh = forceRefresh)
            fakts = loaded.first
            val fromCache = loaded.second

            // Every required service must actually have been granted by the server.
            requiredKeys.forEach { key ->
                val status = GrantStatus.from(fakts.statuses[key])
                if (fakts.instances[key] == null ||
                        status == GrantStatus.DENIED ||
                        status == GrantStatus.UNAVAILABLE
                ) {
                    throw CompositionError("Required service '$key' was not granted (status=$status).")
                }
            }

            // Resolve each instance to a reachable alias. If a *cached* config has gone stale
            // (no alias answers), re-negotiate exactly once and retry before failing.
            instanceMap = buildInstanceMap(fakts)
            if (fromCache && requiredKeys.any { instanceMap[it] == null }) {
                println("Cached aliases are unreachable; re-negotiating once.")
                cache.clear()
                fakts = getActiveFakts(url, forceRefresh = true).first
                instanceMap = buildInstanceMap(fakts)
            }

            val missing = requiredKeys.filter { instanceMap[it] == null }
            if (missing.isNotEmpty()) {
                throw CompositionError("No reachable alias for required service(s): ${missing.joinToString()}.")
            }

            try {
                token = loginUser(fakts.auth)
                break
            } catch (e: TokenError) {
                if (e.statusCode == 400 && !forceRefresh) {
                    println(
                            "Token endpoint returned 400 (stale client credentials); " +
                                    "clearing cache and restarting device flow from scratch."
                    )
                    cache.clear()
                    forceRefresh = true
                    continue
                }
                throw e
            }
        }
        println("Obtained token (${token.length} chars, prefix ${token.take(6)}…)")

        var rekuest = Rekuest(instanceMap["rekuest"]!!, token)
        var unlok = Unlok(fakts.self.alias, token) // lok is the deployment itself (self.alias)
        var mikro = Mikro(instanceMap["mikro"]!!, token)
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


        println("Here is the app $app")
        var registry = FunctionRegistry()

        registry.register_function(
            "frage",
            DefinitionInput(
                key = "frage",
                version = "0.1.0",
                name = "Upload Image",
                description =
                    Optional.present(
                        "Upload the currently active image in the viewer."
                    ),
                args =
                    Optional.present(
                        listOf(
                            ArgPortInput(
                                key = "name",
                                kind = PortKind.STRING,
                                description =
                                    Optional.present(
                                        "How would you like to name the image?"
                                    ),
                                nullable = Optional.present(false)
                            )
                        )
                    ),
                returns =
                    Optional.present(
                        listOf(
                            ReturnPortInput(
                                key = "image",
                                kind = PortKind.STRUCTURE,
                                identifier =
                                    Optional.present(
                                        "@mikro/image"
                                    ),
                                description =
                                    Optional.present(
                                        "The returned image"
                                    )
                            )
                        )
                    ),
                kind = ActionKind.FUNCTION
            ),
            ::runX
        )

        registry.register_function(
            "show_image",
            DefinitionInput(
                key = "show_image",
                version = "0.1.0",
                name = "Show Image",
                description =
                    Optional.present(
                        "Show the currently active Image in the viewer."
                    ),
                args =
                    Optional.present(
                        listOf(
                            ArgPortInput(
                                key = "image",
                                kind = PortKind.STRUCTURE,
                                identifier =
                                    Optional.present(
                                        "@mikro/image"
                                    ),
                                description =
                                    Optional.present(
                                        "The image to show"
                                    ),
                                nullable = Optional.present(false)
                            )
                        )
                    ),
                returns =
                    Optional.present(
                        listOf(
                            ReturnPortInput(
                                key = "image",
                                kind = PortKind.STRUCTURE,
                                identifier =
                                    Optional.present(
                                        "@mikro/image"
                                    ),
                                description =
                                    Optional.present(
                                        "The image that was shown image"
                                    ),
                                nullable = Optional.present(false)
                            )
                        )
                    ),
                kind = ActionKind.FUNCTION
            ),
            ::loadImage
        )


        var agent = Agent(rekuest, instanceMap["rekuest"]!!, token, registry, app)

        agent.createAgent("my_agent")
        agent.registerFunctions()


        provideJob =
                CoroutineScope(Dispatchers.Default).launch {
                    agent.provideForever()
                }

        return unlok.getUser()
    }

    fun logout() {
        // Tear down the live agent connection (WebSocket provide loop), if any.
        provideJob?.cancel()
        provideJob = null
        // Clear the cached configuration so the next login re-negotiates from scratch.
        cache.clear()
        // Remove the legacy token key written by older versions of this plugin.
        val prefs = Preferences.userNodeForPackage(Arkitekt::class.java)
        prefs.remove("token")
    }
}
