package com.jimberisolation.android.authentication

import ApiClient
import ApiService
import android.graphics.Color
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jimberisolation.android.R
import com.jimberisolation.android.activity.SignInActivity
import com.jimberisolation.android.activity.MainActivity
import com.jimberisolation.android.Application
import com.jimberisolation.android.daemon.CreateDaemonApiResult
import com.jimberisolation.android.daemon.GetDaemonApiResult
import com.jimberisolation.android.networkcontroller.NetworkControllerApiResult
import com.jimberisolation.android.storage.SharedStorage
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import retrofit2.Response
import org.bouncycastle.util.encoders.Base64
import org.bouncycastle.util.encoders.Hex
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SignInScreenTest {
    @Before fun clearRegistrationFixture() = runBlocking {
        Application.getTunnelManager().getTunnels().firstOrNull { it.getDaemonId() == 987654 }?.let {
            Application.getTunnelManager().deleteState(it)
        }
        SharedStorage.getInstance().clearDaemonKeys(987654)
        if (SharedStorage.getInstance().getCurrentUser()?.id == 987654) {
            SharedStorage.getInstance().clearUserLoginData()
        }
    }

    @Test fun reopeningMainScreenDoesNotReinitializeCrypto() {
        repeat(2) {
            ActivityScenario.launch(MainActivity::class.java).use {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            }
        }
    }

    @Test fun labelsKeepTheirContrastWhileSigningIn() {
        ActivityScenario.launch(SignInActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val signingIn = SignInActivity::class.java.getDeclaredField("signingIn").apply { isAccessible = true }
                val update = SignInActivity::class.java.getDeclaredMethod("updateSignInButtons").apply { isAccessible = true }
                for (busy in listOf(false, true, false)) {
                    signingIn.setBoolean(activity, busy)
                    update.invoke(activity)
                    for (id in listOf(R.id.google_sign_in, R.id.microsoft_sign_in, R.id.email_sign_in)) {
                        val button = activity.findViewById<Button>(id)
                        val expected = activity.getColor(R.color.jimber_primary_button)
                        assertEquals("Button $id, busy=$busy", expected, button.currentTextColor)
                        if (busy) assertFalse(button.isEnabled)
                        assertEquals(255, Color.alpha(button.currentTextColor))
                    }
                }
            }
        }
    }

    @Test fun confirmingDeviceNameResumesTheSignInFlow() = runBlocking {
        ActivityScenario.launch(SignInActivity::class.java).use { scenario ->
            val reference = AtomicReference<SignInActivity>()
            scenario.onActivity { reference.set(it) }
            val name = async(Dispatchers.Main) {
                suspendCoroutine<String?> { continuation ->
                    val method = SignInActivity::class.java.getDeclaredMethod(
                        "showNameInputDialog", String::class.java, Continuation::class.java
                    ).apply { isAccessible = true }
                    method.invoke(reference.get(), null, continuation)
                }
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                val root = WindowInspector.getGlobalWindowViews().first {
                    it.findViewById<EditText>(R.id.nameInput) != null
                }
                root.findViewById<EditText>(R.id.nameInput).setText("test-device")
                root.findViewById<Button>(R.id.btnSubmit).performClick()
            }
            assertEquals("test-device", name.await())
        }
    }

    @Test fun confirmedNewDeviceReachesTunnelScreen() = runBlocking {
        val serviceField = ApiClient::class.java.declaredFields.single { it.type == ApiService::class.java }.apply { isAccessible = true }
        val original = serviceField.get(ApiClient)
        val calls = mutableListOf<String>()
        val service = Proxy.newProxyInstance(ApiService::class.java.classLoader, arrayOf(ApiService::class.java)) { _, method, _ ->
            calls.add(method.name)
            when (method.name) {
                "createDaemon" -> Response.success(CreateDaemonApiResult(987654, "10.0.0.2", "test-device"))
                "getCloudControllerInformation" -> Response.success(NetworkControllerApiResult(
                    Base64.toBase64String(Hex.decode("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")),
                    "10.0.0.1", "127.0.0.1", "10.0.0.0/24"
                ))
                "getDaemonInformation" -> Response.success(GetDaemonApiResult(987654, "test-device", "approved"))
                else -> error("Unexpected API call: ${method.name}")
            }
        }
        serviceField.set(ApiClient, service)
        try {
            ActivityScenario.launch(SignInActivity::class.java).use { scenario ->
                val reference = AtomicReference<SignInActivity>()
                scenario.onActivity {
                    reference.set(it)
                    SharedStorage.getInstance().saveCurrentUser(User(987654, "fixture@example.com"))
                }
                val registration = async(Dispatchers.Main) {
                    suspendCoroutine<Unit> { continuation ->
                        val method = SignInActivity::class.java.getDeclaredMethod(
                            "completeSignIn", UserAuthentication::class.java, Continuation::class.java
                        ).apply { isAccessible = true }
                        method.invoke(reference.get(), UserAuthentication(987654, "RegistrationTest"), continuation)
                    }
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity {
                    val root = WindowInspector.getGlobalWindowViews().first { it.findViewById<EditText>(R.id.nameInput) != null }
                    root.findViewById<EditText>(R.id.nameInput).setText("test-device")
                    root.findViewById<Button>(R.id.btnSubmit).performClick()
                }
                registration.await()
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertTrue(calls.contains("createDaemon"))
                assertTrue(calls.contains("getCloudControllerInformation"))
                assertTrue(Application.getTunnelManager().getTunnels().any { it.getDaemonId() == 987654 })
            }
        } finally {
            Application.getTunnelManager().getTunnels().firstOrNull { it.getDaemonId() == 987654 }?.let {
                Application.getTunnelManager().deleteState(it)
            }
            SharedStorage.getInstance().clearDaemonKeys(987654)
            SharedStorage.getInstance().clearUserLoginData()
            serviceField.set(ApiClient, original)
        }
    }
}
