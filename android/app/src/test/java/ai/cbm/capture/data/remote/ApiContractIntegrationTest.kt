package ai.cbm.capture.data.remote

import ai.cbm.capture.domain.model.CameraIntrinsics
import ai.cbm.capture.domain.model.CaptureMetadata
import ai.cbm.capture.domain.model.ClientInfo
import ai.cbm.capture.domain.model.DeviceInfo
import ai.cbm.capture.domain.model.ImageDescriptor
import ai.cbm.capture.domain.model.IntrinsicsSource
import ai.cbm.capture.domain.model.LoginRequest
import ai.cbm.capture.domain.model.PixelPoint
import ai.cbm.capture.domain.model.SignUpRequest
import ai.cbm.capture.domain.model.TargetDescriptor
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import retrofit2.Retrofit
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * The phone's own client code against a real App API: the app's Retrofit interface, JSON settings,
 * uploader and capture contract 2.0.0. Catches any disagreement on field names,
 * multipart shape or status handling that mocks cannot.
 *
 * Skipped unless CBM_API_IT_URL (a throwaway API, never the live one) and CBM_API_IT_SITE_CODE are
 * set; backend/tests/run-app-contract.sh starts one and runs this test.
 */
class ApiContractIntegrationTest {

    private val baseUrl = System.getenv("CBM_API_IT_URL")
    private val siteCode = System.getenv("CBM_API_IT_SITE_CODE")
    private val siteId = System.getenv("CBM_API_IT_SITE_ID") ?: "API-TEST"
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    private fun api() = Retrofit.Builder().baseUrl(baseUrl.trimEnd('/') + "/").client(OkHttpClient())
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(AppApi::class.java)

    /** A real 960 x 1280 JPEG (a plain grey frame, 5 KB, in test resources). The API decodes what it
     *  accepts - a frame header with no pixels behind it is refused - and Android's unit-test
     *  classpath has no image encoder, so the image is a file rather than made here. */
    private fun jpeg960x1280(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/contract-960x1280.jpg")) { "test resource missing" }.use { it.readBytes() }

    @Test
    fun `sign up, report a problem with a photo, see it listed, log out`() = runTest {
        assumeTrue("set CBM_API_IT_URL to run", baseUrl != null && siteCode != null)
        val api = api()
        val device = DeviceInfo(id = UUID.randomUUID().toString(), model = "JVM test", osVersion = "test", appVersion = "test")
        val email = "it-${UUID.randomUUID().toString().take(8)}@example.com"

        val signUp = api.signUp(SignUpRequest(siteCode, "USER", email, "long-enough-1", device))
        assertEquals(signUp.errorBody()?.string(), 201, signUp.code())
        val session = signUp.body()!!
        assertEquals("USER", session.memberships.single().role)
        assertEquals("ACTIVE", session.memberships.single().status)

        val bytes = jpeg960x1280()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val reportId = UUID.randomUUID().toString()
        val captureId = UUID.randomUUID().toString()
        val metadata = CaptureMetadata(
            captureId = captureId, reportId = reportId, buildingId = siteId, description = "Door handle detached",
            capturedAt = "2026-09-19T15:00:00Z",
            client = ClientInfo(appVersion = "test", osVersion = "test", deviceModel = "JVM"),
            image = ImageDescriptor(width = 960, height = 1280, sha256 = sha, byteLength = bytes.size, orientationApplied = 0),
            camera = CameraIntrinsics(source = IntrinsicsSource.ARCORE, trusted = true, fx = 954.6, fy = 955.1, cx = 480.2, cy = 639.6, width = 960, height = 1280),
            target = TargetDescriptor(pixel = PixelPoint(512.0, 700.0), centrality = 0.1)
        )
        val metadataJson = json.encodeToString(CaptureMetadata.serializer(), metadata)
        assertTrue("contract 2.0.0 fields", metadataJson.contains("\"report_id\"") && !metadataJson.contains("reporter_email"))
        val file = File.createTempFile("cbm-it", ".jpg").apply { writeBytes(bytes) }

        val uploader = CaptureUploader(api, json)
        assertEquals(UploadOutcome.Delivered("STORED"), uploader.upload(session.token, captureId, metadataJson, file))
        assertTrue("replay", uploader.upload(session.token, captureId, metadataJson, file) is UploadOutcome.Delivered)

        val reports = api.reports(bearer(session.token)).body()!!.reports
        assertEquals(reportId, reports.single().reportId)
        assertEquals("RECEIVED", reports.single().status)
        assertEquals("Door handle detached", reports.single().description)

        val login = api.login(LoginRequest(email, "long-enough-1", device))
        assertEquals(200, login.code())
        assertEquals(false, login.body()!!.newDevice)

        assertEquals(204, api.logout(bearer(session.token)).code())
        assertEquals(UploadOutcome.NeedsLogin, uploader.upload(session.token, UUID.randomUUID().toString(), metadataJson, file))
        file.delete()
    }
}
