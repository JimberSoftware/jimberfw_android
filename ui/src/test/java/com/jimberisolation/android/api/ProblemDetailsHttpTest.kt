package com.jimberisolation.android.api

import ApiService
import com.jimberisolation.android.authentication.AuthenticationApiRequest
import com.jimberisolation.android.authentication.AuthenticationWithVerificationCodeApiRequest
import com.jimberisolation.android.authentication.VerificationCodeApiRequest
import com.jimberisolation.android.daemon.CreateDaemonApiRequest
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class ProblemDetailsHttpTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ApiService

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder().baseUrl(server.url("/api/v1/"))
            .addConverterFactory(GsonConverterFactory.create()).build().create(ApiService::class.java)
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun allMobileEndpointsAcceptProblemJson() = runBlocking {
        val calls = listOf<Pair<String, suspend () -> Response<*>>>(
            "POST /api/v1/auth/verify-google-id" to { api.getUserAuthentication("google", AuthenticationApiRequest("test-id")) },
            "POST /api/v1/auth/verify-microsoft-id" to { api.getUserAuthentication("microsoft", AuthenticationApiRequest("test-id")) },
            "POST /api/v1/auth/send-user-token-code" to { api.sendVerificationEmail(VerificationCodeApiRequest("test@example.com")) },
            "POST /api/v1/auth/verify-email-token" to { api.verifyEmailWithToken(AuthenticationWithVerificationCodeApiRequest("test@example.com", 123456)) },
            "GET /api/v1/auth/refresh" to { api.refreshToken("Refresh=test-refresh") },
            "POST /api/v1/auth/logout" to { api.logout("Authentication=test-token") },
            "POST /api/v1/companies/Test/daemons/user/1" to { api.createDaemon(1, "Test", CreateDaemonApiRequest("test-public-key", "test-device"), "Authentication=test-token") },
            "DELETE /api/v1/companies/Test/daemons-mobile/2" to { api.deleteDaemon(2, "Test", "test-signature") },
            "GET /api/v1/companies/Test/daemons-mobile/2" to { api.getDaemonInformation(2, "Test", "test-signature") },
            "GET /api/v1/companies/Test/daemons-mobile/2/nc-information" to { api.getCloudControllerInformation(2, "Test", "test-signature") }
        )
        for ((expectedRequest, call) in calls) {
            server.enqueue(problem(400, """{"type":"urn:jimber:problem:bad-request","title":"Bad Request",
                "status":400,"detail":"Request rejected"}"""))
            val response = call()
            val error = assertThrows(ApiException::class.java) { response.requireBody() }
            assertEquals("Request rejected", error.userFacingMessage())
            assertEquals(400, error.status)
            val request = server.takeRequest()
            assertEquals(expectedRequest, "${request.method} ${request.path}")
        }
    }

    @Test fun unauthorizedVerificationResponseRetainsDetail() = runBlocking {
        server.enqueue(problem(401, """{"type":"urn:jimber:problem:unauthorized","title":"Unauthorized",
            "status":401,"detail":"Invalid verification code"}"""))
        val response = api.verifyEmailWithToken(AuthenticationWithVerificationCodeApiRequest("test@example.com", 123456))
        val error = assertThrows(ApiException::class.java) { response.requireBody() }
        assertEquals("Invalid verification code", error.userFacingMessage())
        assertTrue(error.hasHttpStatus(401))
    }

    @Test fun release22EmailFailuresDisplayActualServerMessages() = runBlocking {
        val failures = listOf(
            400 to "The token you've entered is invalid.",
            400 to "Token expired",
            400 to "Too many attempts. You will be able to request a new token in 10 minutes.",
            401 to "Your account is disabled. Contact your admin to resolve this issue."
        )
        for ((status, detail) in failures) {
            server.enqueue(problem(status, """{"status":$status,"detail":"$detail"}"""))
            val response = api.verifyEmailWithToken(AuthenticationWithVerificationCodeApiRequest("test@example.com", 123456))
            val error = assertThrows(ApiException::class.java) { response.requireBody() }
            assertEquals(status, error.status)
            assertEquals(detail, error.userFacingMessage())
        }
    }

    @Test fun validationAndPrismaConflictKeepFieldInformation() = runBlocking {
        for ((status, body, expected) in listOf(
            Triple(422, """{"status":422,"errors":[{"field":"email","detail":"Email is taken"}]}""", "Email is taken"),
            Triple(409, """{"status":409,"type":"urn:jimber:problem:already-exists","title":"Conflict",
                "errors":[{"field":"email","detail":""}]}""", "This value is already in use.")
        )) {
            server.enqueue(problem(status, body))
            val response = api.sendVerificationEmail(VerificationCodeApiRequest("test@example.com"))
            val error = assertThrows(ApiException::class.java) { response.requireBody() }
            assertEquals(expected, error.fieldError("email"))
        }
    }

    @Test fun resendRateLimitAndServerFailuresRemainReadable() = runBlocking {
        for ((status, detail) in listOf(
            400 to "Too many attempts. You will be able to request a new token in 10 minutes.",
            429 to "Please wait before requesting another code",
            500 to "Email delivery unavailable"
        )) {
            server.enqueue(problem(status, """{"status":$status,"detail":"$detail"}"""))
            val response = api.sendVerificationEmail(VerificationCodeApiRequest("test@example.com"))
            assertEquals(detail, assertThrows(ApiException::class.java) { response.requireBody() }.userFacingMessage())
        }
    }

    @Test fun missingDeviceStatusesRemainDistinctFromOutage() = runBlocking {
        for (status in listOf(403, 404, 500, 503)) {
            server.enqueue(problem(status, """{"status":$status,"title":"Test error"}"""))
            val response = api.getDaemonInformation(2, "Test", "test-signature")
            val error = assertThrows(ApiException::class.java) { response.requireBody() }
            assertEquals(status in listOf(403, 404), error.hasHttpStatus(403, 404))
        }
    }

    @Test fun proxyHtmlDoesNotCrashOrBecomeMissingDeviceError() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(502).setHeader("Content-Type", "text/html").setBody("<html>Bad gateway</html>"))
        val response = api.getCloudControllerInformation(2, "Test", "test-signature")
        val error = assertThrows(ApiException::class.java) { response.requireBody() }
        assertEquals(502, error.status)
        assertNull(error.problem)
        assertFalse(error.hasHttpStatus(403, 404))
        assertFalse(error.userFacingMessage().contains("<html>"))
    }

    @Test fun successfulEmailLoginAndDaemonResponsesStillDeserialize() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("true"))
        assertTrue(api.sendVerificationEmail(VerificationCodeApiRequest("test@example.com")).requireBody())
        server.enqueue(MockResponse().setBody("""{"id":1,"email":"test@example.com","company":{"name":"Test"}}"""))
        val user = api.verifyEmailWithToken(AuthenticationWithVerificationCodeApiRequest("test@example.com", 123456)).requireBody()
        assertEquals(1, user.id)
        assertEquals("Test", user.company.name)
        server.enqueue(MockResponse().setBody("""{"id":2,"name":"test-device","approvalStatus":"approved"}"""))
        assertEquals(2, api.getDaemonInformation(2, "Test", "test-signature").requireBody().id)
    }

    private fun problem(status: Int, body: String) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/problem+json").setBody(body)
}
