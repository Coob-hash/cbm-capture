package ai.cbm.capture.data.remote

import ai.cbm.capture.domain.model.DeviceInfo
import ai.cbm.capture.domain.model.LoginRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import java.io.IOException

/**
 * A success whose body is not the API's JSON is an [IOException] the screens already handle, never
 * an exception that closes the app. Third audit 2026-09-25, finding 3: HTML answering the login
 * crashed the app with a JsonDecodingException thrown inside Retrofit.
 */
class ReadableResponsesTest {

    private val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private lateinit var api: AppApi
    private val login = LoginRequest("tina@example.com", "long-enough-1", DeviceInfo(id = "00000000-0000-4000-8000-000000000001"))

    @Before
    fun setUp() {
        server.start()
        api = Retrofit.Builder().baseUrl(server.url("/")).client(OkHttpClient())
            .addConverterFactory(json.appConverterFactory()).build().create(AppApi::class.java)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun respond(code: Int, body: String) = server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    private suspend fun unreadable(call: suspend () -> Unit): IOException {
        try {
            call()
        } catch (e: UnreadableResponseException) {
            return e
        }
        fail("expected the answer to be refused as unreadable")
        throw AssertionError()
    }

    @Test
    fun `an HTML page answering the login is an IOException, not a crash`() = runTest {
        respond(200, "<!doctype html><html><body>Sign in to this Wi-Fi</body></html>")
        val e = unreadable { api.login(login) }
        assertEquals(UNREADABLE_MESSAGE, networkMessage(e))
    }

    @Test
    fun `JSON that is not the answer expected is refused the same way`() = runTest {
        respond(200, """{"token":"x"}""")
        unreadable { api.login(login) }
        respond(200, """{"reports": 7}""")
        unreadable { api.reports(bearer("a".repeat(64))) }
    }

    @Test
    fun `the API's own answers still decode, and a network failure keeps its own message`() = runTest {
        respond(200, """{"reports":[]}""")
        assertTrue(api.reports(bearer("a".repeat(64))).body()!!.reports.isEmpty())
        assertEquals(NETWORK_MESSAGE, networkMessage(IOException("connection reset")))
    }
}
