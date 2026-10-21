import com.jimberisolation.android.authentication.AuthenticationApiRequest
import com.jimberisolation.android.authentication.AuthenticationWithVerificationCodeApiRequest
import com.jimberisolation.android.authentication.RefreshTokenApiResult
import com.jimberisolation.android.authentication.UserAuthenticationApiResult
import com.jimberisolation.android.authentication.VerificationCodeApiRequest
import com.jimberisolation.android.authentication.refreshToken
import com.jimberisolation.android.daemon.CreateDaemonApiRequest
import com.jimberisolation.android.daemon.CreateDaemonApiResult
import com.jimberisolation.android.daemon.DeleteDaemonApiResult
import com.jimberisolation.android.daemon.GetDaemonApiResult
import com.jimberisolation.android.networkcontroller.NetworkControllerApiResult
import com.jimberisolation.android.util.SingleLiveEvent
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

object AuthEventManager {
    val authFailedEvent = SingleLiveEvent<Boolean>()
}

class AuthInterceptor(
    private val renewToken: () -> String? = { runBlocking { refreshToken().getOrNull()?.accessToken } },
    private val onSessionExpired: () -> Unit = { AuthEventManager.authFailedEvent.postValue(true) }
) : Interceptor {
    private val excludedPaths = listOf(
        "auth/refresh", "auth/verify-google-id", "auth/verify-microsoft-id",
        "auth/send-user-token-code", "auth/verify-email-token"
    )

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val authorization = request.header("Authorization")
        val cookies = request.header("Cookie").orEmpty().split(';').map { it.trim() }
        val hasSession = authorization?.startsWith("Bearer ") == true || cookies.any {
            val name = it.substringBefore('=')
            val value = it.substringAfter('=', "")
            name in setOf("Authentication", "Refresh") && value.isNotBlank() && value != "null"
        }
        val usesSignature = authorization != null && !authorization.startsWith("Bearer ")
        if (response.code != 401 || !hasSession || usesSignature ||
            excludedPaths.any { request.url.encodedPath.endsWith("/$it") }) {
            return response
        }

        synchronized(this) {
            val newToken = renewToken()?.takeIf { it.isNotBlank() && it != "null" }
            if (newToken == null) {
                onSessionExpired()
                return response // Keep the original Problem Details available to the caller.
            }
            val retry = request.newBuilder().header("Authorization", "Bearer $newToken")
            if (request.header("Cookie") != null) {
                val updatedCookies = cookies.filter { it.isNotBlank() && it.substringBefore('=') != "Authentication" } +
                    "Authentication=$newToken"
                retry.header("Cookie", updatedCookies.joinToString("; "))
            }
            response.close()
            return chain.proceed(retry.build()).also {
                if (it.code == 401) onSessionExpired()
            }
        }
    }
}

// Custom logger that filters sensitive information
val customLogger = HttpLoggingInterceptor.Logger { message ->
    // Redact sensitive data
    val filteredMessage = message
        .replace(Regex("(accessToken\":\")\\S+")) { "${it.groupValues[1]}****\"}" }
        .replace(Regex("(Authentication=)[^;]+")) { "${it.groupValues[1]}****" }
        .replace(Regex("(Authorization:)[^;]+")) { "${it.groupValues[1]}****" }
        .replace(Regex("(Refresh=)[^;]+")) { "${it.groupValues[1]}****" }
        .replace(Regex("(idToken\":\")\\S+")) { "${it.groupValues[1]}****\"}" }

    println(filteredMessage) // Log the filtered message
}

// Set up the logging interceptor
val logging = HttpLoggingInterceptor(customLogger).apply {
    level = HttpLoggingInterceptor.Level.BODY // Log the request and response body
}

// Retrofit API service interface
interface ApiService {
    @POST("auth/verify-{type}-id")
    suspend fun getUserAuthentication(@Path("type") type: String, @Body data: AuthenticationApiRequest): retrofit2.Response<UserAuthenticationApiResult>

    @POST("companies/{company}/daemons/user/{userId}")
    suspend fun createDaemon(@Path("userId") userId: Int, @Path("company") company: String, @Body data: CreateDaemonApiRequest, @Header("Cookie") cookies: String): retrofit2.Response<CreateDaemonApiResult>

    @DELETE("companies/{company}/daemons-mobile/{daemonId}")
    suspend fun deleteDaemon(@Path("daemonId") daemonId: Number, @Path("company") company: String, @Header("Authorization") authorization: String): retrofit2.Response<DeleteDaemonApiResult>

    @POST("auth/send-user-token-code")
    suspend fun sendVerificationEmail(@Body data: VerificationCodeApiRequest): retrofit2.Response<Boolean>

    @POST("auth/verify-email-token")
    suspend fun verifyEmailWithToken(@Body data: AuthenticationWithVerificationCodeApiRequest): retrofit2.Response<UserAuthenticationApiResult>

    @GET("auth/refresh")
    suspend fun refreshToken(@Header("Cookie") cookies: String): retrofit2.Response<RefreshTokenApiResult>

    @POST("auth/logout")
    suspend fun logout(@Header("Cookie") cookies: String): retrofit2.Response<Boolean>

    @GET("companies/{company}/daemons-mobile/{daemonId}/nc-information")
    suspend fun getCloudControllerInformation(@Path("daemonId") daemonId: Number, @Path("company") company: String, @Header("Authorization") authorization: String): retrofit2.Response<NetworkControllerApiResult>

    @GET("companies/{company}/daemons-mobile/{daemonId}")
    suspend fun getDaemonInformation(@Path("daemonId") daemonId: Number, @Path("company") company: String, @Header("Authorization") authorization: String): retrofit2.Response<GetDaemonApiResult>

}

// ApiClient class
object ApiClient {
    private const val BASE_URL = Config.BASE_URL

    private val client = OkHttpClient.Builder()
        .addInterceptor(logging)
        .addInterceptor(AuthInterceptor())
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val apiService: ApiService = retrofit.create(ApiService::class.java)
}