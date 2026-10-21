package com.jimberisolation.android.api

import ApiService
import AuthInterceptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jimberisolation.android.authentication.AuthenticationApiRequest
import com.jimberisolation.android.authentication.VerificationCodeApiRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** Run against release with -PandroidTestBuildType=release to cover R8 and Gson on Android. */
@RunWith(AndroidJUnit4::class)
class HttpStackCompatibilityTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before fun setUp() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            start()
        }
        client = OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .build()
    }

    @After fun tearDown() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        server.shutdown()
    }

    private fun api(httpClient: OkHttpClient = client): ApiService = Retrofit.Builder()
        .baseUrl(server.url("/api/v1/"))
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build().create(ApiService::class.java)

    @Test fun loginAndDaemonJsonSurviveReleaseShrinking() = runBlocking {
        val api = api()
        server.enqueue(MockResponse().setBody("""{"id":7,"email":"test@example.com","company":{"name":"Test"},"futureField":true}"""))
        val user = api.getUserAuthentication("google", AuthenticationApiRequest("fixture-id-token")).requireBody()
        assertEquals(7, user.id)
        assertEquals("test@example.com", user.email)
        assertEquals("Test", user.company.name)
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/api/v1/auth/verify-google-id", request.path)
        assertEquals("fixture-id-token", JSONObject(request.body.readUtf8()).getString("idToken"))

        server.enqueue(MockResponse().setBody("""{"id":9,"name":"test-device","approvalStatus":"approved"}"""))
        val daemon = api.getDaemonInformation(9, "Test", "fixture-signature").requireBody()
        assertEquals(9, daemon.id)
        assertEquals("test-device", daemon.name)
        assertEquals("approved", daemon.approvalStatus)
    }

    @Test fun fieldErrorsRemainReadableInRelease() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(422)
            .setHeader("Content-Type", "application/problem+json")
            .setBody("""{"type":"urn:jimber:problem:validation-error","status":422,"detail":"Check your input","errors":[{"field":"email","detail":"Email is taken"}]}"""))
        val response = api().sendVerificationEmail(VerificationCodeApiRequest("test@example.com"))
        val error = assertThrows(ApiException::class.java) { response.requireBody() }
        assertEquals(422, error.status)
        assertEquals("Check your input\nEmail is taken", error.userFacingMessage())
        assertEquals("Email is taken", error.fieldError("email"))
    }

    @Test fun expiredSessionRetriesWithRenewedCredentials() = runBlocking {
        var refreshes = 0
        val authenticated = client.newBuilder().addInterceptor(AuthInterceptor(
            renewToken = { refreshes++; "new-fixture-token" },
            onSessionExpired = { fail("A refreshed session must remain signed in") }
        )).build()
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"status":401}"""))
        server.enqueue(MockResponse().setBody("true"))
        assertTrue(api(authenticated).logout("Authentication=old-fixture-token").requireBody())
        assertEquals(1, refreshes)
        assertEquals("Authentication=old-fixture-token", server.takeRequest().getHeader("Cookie"))
        val retry = server.takeRequest()
        assertEquals("Authentication=new-fixture-token", retry.getHeader("Cookie"))
        assertEquals("Bearer new-fixture-token", retry.getHeader("Authorization"))
    }

    @Test(timeout = 15000) fun cancellingCoroutineCancelsPendingHttpCall() = runBlocking {
        val cancelled = CountDownLatch(1)
        val cancellableClient = client.newBuilder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { cancelled.countDown() }
        }).build()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch {
            api(cancellableClient).getDaemonInformation(9, "Test", "fixture-signature")
            fail("Cancelled request must not return a result")
        }
        try {
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
        } finally {
            job.cancelAndJoin()
        }
        assertTrue("Retrofit must cancel OkHttp when its coroutine is cancelled", cancelled.await(5, TimeUnit.SECONDS))
        assertTrue(job.isCancelled)
    }
}
