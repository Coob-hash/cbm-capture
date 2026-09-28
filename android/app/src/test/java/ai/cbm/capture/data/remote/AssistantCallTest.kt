package ai.cbm.capture.data.remote

import ai.cbm.capture.di.AppModule
import ai.cbm.capture.domain.model.AssistantRequest
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

/**
 * 2026-09-28: the FM's assistant panel said "coming soon"; it now asks the building assistant. The
 * call is the API's own shape, and it may wait for an answer longer than any other call.
 */
class AssistantCallTest {

    private val server = MockWebServer()
    private val json = AppModule.provideJson()
    private val readTimeouts = mutableMapOf<String, Int>()
    private lateinit var api: AppApi

    @Before
    fun setUp() {
        server.start()
        // The app's own client, with a look at each call's read timeout after the app's interceptors.
        val client = AppModule.provideOkHttp().newBuilder()
            .addInterceptor { chain -> readTimeouts[chain.request().url.encodedPath] = chain.readTimeoutMillis(); chain.proceed(chain.request()) }
            .build()
        api = Retrofit.Builder().baseUrl(server.url("/")).client(client)
            .addConverterFactory(json.appConverterFactory()).build().create(AppApi::class.java)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `a question goes out as the API expects and the answer comes back`() = runTest {
        server.enqueue(MockResponse().setBody("""{"answer":"Ticket 42 is **ASSIGNED**."}"""))
        val response = api.fmAssistant(bearer("a".repeat(64)), AssistantRequest("What about ticket 42?"))
        assertEquals("Ticket 42 is **ASSIGNED**.", response.body()!!.answer)
        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("/v1/fm/assistant", sent.path)
        assertEquals("Bearer " + "a".repeat(64), sent.getHeader("Authorization"))
        assertEquals("""{"message":"What about ticket 42?"}""", sent.body.readUtf8())
    }

    @Test
    fun `the assistant is given two and a half minutes, every other call one`() = runTest {
        server.enqueue(MockResponse().setBody("""{"answer":"ok"}"""))
        server.enqueue(MockResponse().setBody("""{"reports":[]}"""))
        api.fmAssistant(bearer("a".repeat(64)), AssistantRequest("hi"))
        api.reports(bearer("a".repeat(64)))
        assertEquals(ASSISTANT_READ_TIMEOUT_SECONDS * 1000, readTimeouts["/v1/fm/assistant"])
        assertEquals(60_000, readTimeouts["/v1/reports"])
    }

    @Test
    fun `when the assistant does not answer, the server's words are what the screen shows`() = runTest {
        val words = "The assistant did not answer. If you asked it to act on a ticket, check the ticket before asking again."
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"ASSISTANT_UNAVAILABLE","message":"$words"}"""))
        val response = api.fmAssistant(bearer("a".repeat(64)), AssistantRequest("hi"))
        assertEquals(503, response.code())
        assertEquals(words, response.apiError(json).message)
    }
}
