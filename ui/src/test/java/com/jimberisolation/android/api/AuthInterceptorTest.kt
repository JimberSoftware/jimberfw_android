package com.jimberisolation.android.api

import AuthInterceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AuthInterceptorTest {
    private lateinit var server: MockWebServer
    private var renewals = 0
    private var expiredEvents = 0
    private var renewedToken: String? = "new-token"
    private lateinit var client: OkHttpClient

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder().addInterceptor(AuthInterceptor(
            renewToken = { renewals++; renewedToken },
            onSessionExpired = { expiredEvents++ }
        )).build()
    }

    @After fun tearDown() {
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        server.shutdown()
    }

    @Test fun invalidOtpAndOtherLoginErrorsNeverRefreshOrExpireSession() {
        for (path in listOf("auth/verify-email-token", "auth/send-user-token-code", "auth/verify-google-id", "auth/verify-microsoft-id", "auth/refresh")) {
            server.enqueue(unauthorized())
            val request = request(path).header("Cookie", "Authentication=old; Refresh=refresh").build()
            client.newCall(request).execute().use { response ->
                assertEquals(401, response.code)
                assertEquals("Original error detail", parseApiException(response.code, response.body!!.string()).userFacingMessage())
            }
        }
        assertEquals(0, renewals)
        assertEquals(0, expiredEvents)
        assertEquals(5, server.requestCount)
    }

    @Test fun signedDaemonUnauthorizedDoesNotRefreshJwtEvenWithCookies() {
        server.enqueue(unauthorized())
        val request = request("companies/Test/daemons-mobile/2")
            .header("Authorization", "test-ed25519-signature")
            .header("Cookie", "Authentication=old; Refresh=refresh").build()
        client.newCall(request).execute().use { assertEquals(401, it.code) }
        assertEquals(0, renewals)
        assertEquals(0, expiredEvents)
    }

    @Test fun unauthenticatedRequestDoesNotRefresh() {
        server.enqueue(unauthorized())
        client.newCall(request("companies/Test/daemons/user/1")
            .header("Cookie", "Authentication=null; Refresh=null").build()).execute().use { assertEquals(401, it.code) }
        assertEquals(0, renewals)
        assertEquals(0, expiredEvents)
    }

    @Test fun cookieSessionRetriesWithNewBearerAndCookieWhilePreservingOtherCookies() {
        server.enqueue(unauthorized())
        server.enqueue(MockResponse().setBody("success"))
        val request = request("companies/Test/daemons/user/1")
            .header("Cookie", "Authentication=old; Refresh=refresh; Other=keep").build()
        client.newCall(request).execute().use {
            assertEquals(200, it.code)
            assertEquals("success", it.body!!.string())
        }
        server.takeRequest()
        val retry = server.takeRequest()
        assertEquals("Bearer new-token", retry.getHeader("Authorization"))
        assertEquals("Refresh=refresh; Other=keep; Authentication=new-token", retry.getHeader("Cookie"))
        assertEquals(1, renewals)
        assertEquals(0, expiredEvents)
        assertEquals(2, server.requestCount)
    }

    @Test fun bearerSessionRetriesWithoutAddingCookies() {
        server.enqueue(unauthorized())
        server.enqueue(MockResponse().setBody("success"))
        client.newCall(request("companies/Test/daemons/user/1").header("Authorization", "Bearer old").build())
            .execute().use { assertEquals(200, it.code) }
        server.takeRequest()
        val retry = server.takeRequest()
        assertEquals("Bearer new-token", retry.getHeader("Authorization"))
        assertNull(retry.getHeader("Cookie"))
        assertEquals(1, renewals)
    }

    @Test fun failedRefreshPreservesOriginalProblemBody() {
        renewedToken = null
        server.enqueue(unauthorized())
        client.newCall(request("companies/Test/daemons/user/1").header("Cookie", "Refresh=expired").build())
            .execute().use {
                assertEquals(401, it.code)
                val error = parseApiException(it.code, it.body!!.string())
                assertEquals("Original error detail", error.userFacingMessage())
                assertEquals("urn:jimber:problem:unauthorized", error.problem!!.type)
            }
        assertEquals(1, renewals)
        assertEquals(1, expiredEvents)
        assertEquals(1, server.requestCount)
    }

    @Test fun invalidRefreshedTokenCannotCauseInfiniteRetries() {
        server.enqueue(unauthorized())
        server.enqueue(unauthorized("Refreshed session rejected"))
        client.newCall(request("companies/Test/daemons/user/1").header("Authorization", "Bearer old").build())
            .execute().use {
                assertEquals(401, it.code)
                assertEquals("Refreshed session rejected", parseApiException(it.code, it.body!!.string()).userFacingMessage())
            }
        assertEquals(1, renewals)
        assertEquals(1, expiredEvents)
        assertEquals(2, server.requestCount)
    }

    @Test fun permissionRateLimitAndServerErrorsNeverRefresh() {
        for (status in listOf(400, 403, 404, 409, 422, 429, 500, 503)) {
            server.enqueue(MockResponse().setResponseCode(status).setBody("""{"status":$status,"detail":"Rejected"}"""))
            client.newCall(request("companies/Test/daemons/user/1").header("Authorization", "Bearer token").build())
                .execute().use { assertEquals(status, it.code) }
        }
        assertEquals(0, renewals)
        assertEquals(0, expiredEvents)
    }

    private fun request(path: String) = Request.Builder().url(server.url("/api/v1/$path"))

    private fun unauthorized(detail: String = "Original error detail") = MockResponse()
        .setResponseCode(401).setHeader("Content-Type", "application/problem+json")
        .setBody("""{"status":401,"type":"urn:jimber:problem:unauthorized","detail":"$detail"}""")
}
