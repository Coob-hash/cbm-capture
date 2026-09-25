package ai.cbm.capture.data.remote

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import java.io.File

/** What the phone sends to POST /v1/captures, and how each answer of the API is classified. */
class CaptureUploaderTest {

    private val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private lateinit var uploader: CaptureUploader
    private lateinit var image: File
    private val token = "a".repeat(64)

    @Before
    fun setUp() {
        server.start()
        val api = Retrofit.Builder().baseUrl(server.url("/")).client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()
            .create(AppApi::class.java)
        uploader = CaptureUploader(api, json)
        image = File.createTempFile("capture", ".jpg").apply { writeBytes(byteArrayOf(-1, -40, -1, -39)) }
    }

    @After
    fun tearDown() {
        server.shutdown()
        image.delete()
    }

    private fun respond(code: Int, body: String) = server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    private suspend fun upload() = uploader.upload(token, "cid-1", """{"capture_id":"cid-1"}""", image)

    @Test
    fun `sends one multipart request with the session token`() = runTest {
        respond(202, """{"capture_id":"cid-1","report_id":"r-1","status":"STORED"}""")
        assertEquals(UploadOutcome.Delivered("STORED"), upload())
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/captures", req.path)
        assertEquals("Bearer $token", req.getHeader("Authorization"))
        val body = req.body.readUtf8()
        assertTrue(req.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        assertTrue("metadata part", body.contains("""name="metadata"""") && body.contains("""{"capture_id":"cid-1"}"""))
        assertTrue("image part", body.contains("""name="image"; filename="cid-1.jpg"""") && body.contains("Content-Type: image/jpeg"))
    }

    @Test
    fun `a replay of an already stored photo is delivered`() = runTest {
        respond(200, """{"capture_id":"cid-1","report_id":"r-1","status":"SUBMITTED"}""")
        assertEquals(UploadOutcome.Delivered("SUBMITTED"), upload())
    }

    @Test
    fun `an ended session keeps the photo for the next login`() = runTest {
        respond(401, """{"error":"UNAUTHENTICATED","message":"Your session has ended. Log in again."}""")
        assertEquals(UploadOutcome.NeedsLogin, upload())
    }

    @Test
    fun `refusals that no retry can fix are permanent, with a readable reason`() = runTest {
        respond(422, """{"error":"FRAME_MISMATCH","message":"x"}""")
        assertEquals(UploadOutcome.PermanentFailure("The photo and its camera data did not match. Please take it again.", "FRAME_MISMATCH"), upload())
        respond(409, """{"error":"CONFLICT","message":"This capture id was already used for a different photo or report."}""")
        assertEquals(UploadOutcome.PermanentFailure("This capture id was already used for a different photo or report."), upload())
        respond(413, """{"error":"TOO_LARGE","message":"Request too large."}""")
        assertTrue(upload() is UploadOutcome.PermanentFailure)
        respond(422, """{"error":"SOMETHING_NEW","message":"Server says no."}""")
        assertEquals(UploadOutcome.PermanentFailure("Server says no.", "SOMETHING_NEW"), upload())
    }

    @Test
    fun `a description the server finds too long is named, so the reporter can correct it`() = runTest {
        respond(422, """{"error":"DESCRIPTION_TOO_LONG","message":"The description is longer than 500 characters."}""")
        assertEquals(
            UploadOutcome.PermanentFailure(
                "The description is longer than 500 characters. Edit it and send it again.", "DESCRIPTION_TOO_LONG"
            ),
            upload()
        )
    }

    @Test
    fun `server trouble and rate limits are retried`() = runTest {
        for (code in listOf(429, 500, 502, 503)) {
            respond(code, """{"error":"APP_UNAVAILABLE"}""")
            assertTrue("HTTP $code", upload() is UploadOutcome.TransientFailure)
        }
    }

    @Test
    fun `no network is retried and a missing file is not`() = runTest {
        server.shutdown()
        assertTrue(upload() is UploadOutcome.TransientFailure)
        image.delete()
        assertEquals(UploadOutcome.PermanentFailure("The stored photo is missing."), upload())
    }
}
