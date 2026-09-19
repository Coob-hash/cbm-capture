package ai.cbm.capture.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire types of the App API (backend/cbm_api/main.py). Field names follow the API's snake_case.
 */

@Serializable
data class DeviceInfo(
    val id: String,
    val platform: String = "ANDROID",
    val model: String? = null,
    @SerialName("os_version") val osVersion: String? = null,
    @SerialName("app_version") val appVersion: String? = null
)

@Serializable
data class SignUpRequest(
    @SerialName("site_code") val siteCode: String,
    val role: String,
    val email: String,
    val password: String,
    val device: DeviceInfo
)

@Serializable
data class LoginRequest(val email: String, val password: String, val device: DeviceInfo)

@Serializable
data class SelectRoleRequest(@SerialName("membership_id") val membershipId: String)

@Serializable
data class UserInfo(val id: String, val email: String, @SerialName("display_name") val displayName: String? = null)

@Serializable
data class Membership(
    val id: String,
    @SerialName("site_id") val siteId: String,
    @SerialName("site_name") val siteName: String,
    val role: String,
    val status: String,
    @SerialName("technician_id") val technicianId: Int? = null
) {
    val isActive: Boolean get() = status == "ACTIVE"
}

/** Answer of sign-up and login: a session of exactly one hour. */
@Serializable
data class SessionResponse(
    val token: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("new_device") val newDevice: Boolean = false,
    @SerialName("membership_id") val membershipId: String? = null,
    val memberships: List<Membership> = emptyList(),
    val user: UserInfo
)

@Serializable
data class SelectRoleResponse(@SerialName("membership_id") val membershipId: String)

@Serializable
data class MeResponse(
    @SerialName("expires_at") val expiresAt: String,
    val user: UserInfo,
    val membership: Membership? = null,
    val memberships: List<Membership> = emptyList()
)

@Serializable
data class ReportSummary(
    @SerialName("report_id") val reportId: String,
    val description: String? = null,
    @SerialName("created_at") val createdAt: String,
    val photos: Int = 0,
    @SerialName("attempts_used") val attemptsUsed: Int = 0,
    @SerialName("attempts_left") val attemptsLeft: Int = 4,
    val status: String,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class ReportsResponse(val reports: List<ReportSummary> = emptyList())

@Serializable
data class CaptureStoredResponse(
    @SerialName("capture_id") val captureId: String,
    @SerialName("report_id") val reportId: String,
    val status: String
)

/** Every API error has this shape. */
@Serializable
data class ApiError(val error: String = "UNKNOWN", val message: String? = null)

/** The roles a person may request at sign-up. ADMIN is never self-selected. */
enum class RequestableRole(val wire: String, val label: String) {
    USER("USER", "User"),
    TECHNICIAN("TECHNICIAN", "Technician"),
    FM("FM", "Facility manager")
}
