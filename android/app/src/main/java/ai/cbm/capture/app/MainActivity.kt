package ai.cbm.capture.app

import ai.cbm.capture.data.session.Session
import ai.cbm.capture.data.session.SessionStore
import ai.cbm.capture.data.settings.SettingsRepository
import ai.cbm.capture.ui.auth.AuthViewModel
import ai.cbm.capture.ui.auth.ChooseRoleScreen
import ai.cbm.capture.ui.auth.JoinSiteScreen
import ai.cbm.capture.ui.auth.LoginScreen
import ai.cbm.capture.ui.auth.SignUpScreen
import ai.cbm.capture.ui.auth.WaitingScreen
import ai.cbm.capture.ui.auth.roleLabel
import ai.cbm.capture.ui.capture.CaptureScreen
import ai.cbm.capture.ui.capture.CaptureViewModel
import ai.cbm.capture.ui.fm.FmHomeScreen
import ai.cbm.capture.ui.fm.FmViewModel
import ai.cbm.capture.ui.home.ReporterHomeViewModel
import ai.cbm.capture.ui.reports.ReporterHomeScreen
import ai.cbm.capture.ui.settings.SettingsScreen
import ai.cbm.capture.ui.design.LocalPhotoUrl
import ai.cbm.capture.ui.technician.ReportFormActions
import ai.cbm.capture.ui.technician.ReportFormScreen
import ai.cbm.capture.ui.technician.ReportFormViewModel
import ai.cbm.capture.ui.technician.ReportSentScreen
import ai.cbm.capture.ui.technician.TechnicianHomeScreen
import ai.cbm.capture.ui.technician.TechnicianViewModel
import ai.cbm.capture.ui.theme.CbmCaptureTheme
import ai.cbm.capture.BuildConfig
import ai.cbm.capture.work.UploadWorker
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One app, one login, a home per role. The role comes from the server (the session's membership);
 * this activity only routes to the matching home. A session lasts one hour: when it ends, every
 * protected screen returns to the login screen.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var sessions: SessionStore
    @Inject lateinit var settings: SettingsRepository

    private val auth: AuthViewModel by viewModels()

    /** A join link (cbmapp://join?site=...) opened from the site's QR poster. */
    private val pendingLink = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingLink.value = intent?.dataString
        setContent {
            val session by sessions.session.collectAsStateWithLifecycle()
            CbmCaptureTheme(role = session?.membership?.role) {
                val nav = rememberNavController()
                CompositionLocalProvider(LocalPhotoUrl provides ::photoUrl) {
                val form by auth.form.collectAsStateWithLifecycle()
                val link by pendingLink.collectAsStateWithLifecycle()

                // The hour is checked every 30 s and on every resume (onResume below).
                LaunchedEffect(Unit) { while (true) { delay(30_000); sessions.expireIfNeeded() } }
                LaunchedEffect(session) {
                    if (session == null && nav.currentDestination?.route in PROTECTED) nav.go(Route.LOGIN)
                }
                LaunchedEffect(link) {
                    val l = link ?: return@LaunchedEffect
                    pendingLink.value = null
                    if (auth.joinSite(l) && sessions.current() == null) nav.go(Route.SIGN_UP)
                }

                NavHost(navController = nav, startDestination = startRoute()) {
                    composable(Route.JOIN) {
                        JoinSiteScreen(form, auth::onCode,
                            onContinue = { if (auth.joinSite()) nav.navigate(Route.SIGN_UP) },
                            onHaveAccount = { auth.clearError(); nav.navigate(Route.LOGIN) })
                    }
                    composable(Route.LOGIN) {
                        LoginScreen(form, auth::onEmail, auth::onPassword,
                            onLogin = { auth.login { s -> onSessionOpened(nav, s) } },
                            onSignUp = { auth.clearError(); nav.navigate(if (form.siteCode == null) Route.JOIN else Route.SIGN_UP) })
                    }
                    composable(Route.SIGN_UP) {
                        SignUpScreen(form, siteLabel = "code ${form.siteCode ?: "—"}", auth::onEmail, auth::onPassword, auth::onRole,
                            onCreate = { auth.signUp { s -> onSessionOpened(nav, s) } },
                            onHaveAccount = { auth.clearError(); nav.navigate(Route.LOGIN) })
                    }
                    composable(Route.CHOOSE_ROLE) {
                        ChooseRoleScreen(session?.memberships.orEmpty(), form.busy, form.error,
                            onPick = { m -> auth.chooseRole(m) { s -> onSessionOpened(nav, s) } },
                            onLogout = { logout(nav) })
                    }
                    composable(Route.WAITING) {
                        val (title, message) = waitingText(session)
                        WaitingScreen(title, message, form.busy,
                            onRefresh = { auth.refresh { s -> onSessionOpened(nav, s) } },
                            onLogout = { logout(nav) })
                    }
                    composable(Route.HOME) {
                        val vm: ReporterHomeViewModel = hiltViewModel()
                        val state by vm.state.collectAsStateWithLifecycle()
                        ReporterHomeScreen(state,
                            onOpenReport = { nav.navigate(Route.capture(null)) },
                            onTakeAnotherPhoto = { reportId -> nav.navigate(Route.capture(reportId)) },
                            onRetryUpload = { id -> vm.retry(id); drainQueue() },
                            onDiscardUpload = vm::discard,
                            onRefresh = vm::refresh,
                            onSettings = { nav.navigate(Route.SETTINGS) },
                            onLogout = { logout(nav) })
                    }
                    composable(Route.TECHNICIAN) {
                        val vm: TechnicianViewModel = hiltViewModel()
                        val state by vm.state.collectAsStateWithLifecycle()
                        TechnicianHomeScreen(
                            state = state,
                            onRefresh = vm::refresh,
                            onAnswerOffer = vm::answerOffer,
                            onSaveSkills = vm::saveSkills,
                            onOpenReport = { job -> nav.navigate(Route.report(job.ticketId)) },
                            onProfile = { nav.navigate(Route.SETTINGS) }
                        )
                    }
                    composable(Route.FM) {
                        val vm: FmViewModel = hiltViewModel()
                        val state by vm.state.collectAsStateWithLifecycle()
                        FmHomeScreen(
                            state = state,
                            onRefresh = vm::refresh,
                            onDecide = vm::decide,
                            onProfile = { nav.navigate(Route.SETTINGS) }
                        )
                    }
                    composable(
                        Route.CAPTURE,
                        arguments = listOf(navArgument(CaptureViewModel.REPORT_ID_ARG) { type = NavType.StringType; defaultValue = "" })
                    ) {
                        CaptureScreen(onDone = { nav.popBackStack(Route.HOME, inclusive = false) })
                    }
                    composable(
                        Route.REPORT,
                        arguments = listOf(navArgument(ReportFormViewModel.TICKET_ARG) { type = NavType.IntType })
                    ) {
                        val vm: ReportFormViewModel = hiltViewModel()
                        val state by vm.state.collectAsStateWithLifecycle()
                        // The camera writes into this app's cache and hands back that one picture.
                        var pending by remember { mutableStateOf<Pair<File, Uri>?>(null) }
                        val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
                            val taken = pending
                            pending = null
                            if (ok && taken != null) vm.onPhotoTaken(taken.first, taken.second.toString())
                            else taken?.first?.delete()
                        }
                        if (state.sent) {
                            ReportSentScreen(state.ticketId, state.siteCode, state.expiresAtMillis) {
                                nav.popBackStack()
                            }
                        } else {
                            ReportFormScreen(
                                state = state,
                                actions = ReportFormActions(
                                    onWorkDate = vm::onWorkDate, onFindings = vm::onFindings,
                                    onWorkPerformed = vm::onWorkPerformed, onMaterials = vm::onMaterials,
                                    onChecks = vm::onChecks, onCheckResult = vm::onCheckResult,
                                    onOutcome = vm::onOutcome, onRemainingIssues = vm::onRemainingIssues,
                                    onDeclaration = vm::onDeclaration,
                                    onTakePhoto = {
                                        val file = newPhotoFile()
                                        val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.photos", file)
                                        pending = file to uri
                                        camera.launch(uri)
                                    },
                                    onRemovePhoto = vm::onRemovePhoto, onCaption = vm::onCaption,
                                    onSend = vm::send, onBack = { nav.popBackStack() }
                                )
                            )
                        }
                    }
                    composable(Route.SETTINGS) {
                        SettingsScreen(onBack = { nav.popBackStack() }, onLogout = { logout(nav) })
                    }
                }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingLink.value = intent.dataString
    }

    override fun onResume() {
        super.onResume()
        sessions.expireIfNeeded()
        // Returning to the app is the most common moment for connectivity to have changed.
        drainQueue()
    }

    private fun startRoute(): String = sessions.current()?.let(::homeFor)
        ?: if (sessions.siteCode == null && sessions.lastAccountId == null) Route.JOIN else Route.LOGIN

    private fun onSessionOpened(nav: NavHostController, session: Session) {
        drainQueue()   // this account's photos queued while it was logged out
        nav.go(homeFor(session))
    }

    private fun logout(nav: NavHostController) = auth.logout { nav.go(Route.LOGIN) }

    /** A new file in this app's cache for the camera to write the report's photo into. */
    private fun newPhotoFile(): File {
        val dir = File(cacheDir, "report-photos").apply { mkdirs() }
        return File(dir, "after-${System.currentTimeMillis()}.jpg")
    }

    /** Where a capture id is served from: the App API, with this session's token (Coil adds it). */
    private fun photoUrl(captureId: String): String = BuildConfig.API_BASE_URL + "v1/photos/" + captureId

    private fun drainQueue() {
        if (sessions.current() == null) return
        lifecycleScope.launch { UploadWorker.enqueue(this@MainActivity, settings.settings.first().uploadOnMetered) }
    }

    private fun NavHostController.go(route: String) = navigate(route) { popUpTo(0) { inclusive = true }; launchSingleTop = true }

    private object Route {
        const val JOIN = "join"
        const val LOGIN = "login"
        const val SIGN_UP = "signup"
        const val CHOOSE_ROLE = "role"
        const val WAITING = "waiting"
        const val HOME = "home"
        const val TECHNICIAN = "technician"
        const val FM = "fm"
        const val CAPTURE = "capture?${CaptureViewModel.REPORT_ID_ARG}={${CaptureViewModel.REPORT_ID_ARG}}"
        const val SETTINGS = "settings"
        const val REPORT = "report/{ticket}"
        fun capture(reportId: String?) = "capture?${CaptureViewModel.REPORT_ID_ARG}=${reportId.orEmpty()}"
        fun report(ticketId: Int) = "report/$ticketId"
    }

    private companion object {
        val PROTECTED = setOf(
            Route.CHOOSE_ROLE, Route.WAITING, Route.HOME, Route.TECHNICIAN, Route.FM, Route.CAPTURE,
            Route.REPORT, Route.SETTINGS
        )
    }
}

/** Where a session lands: the role's home, a role choice, or a waiting screen. */
internal fun homeFor(session: Session): String {
    val m = session.membership
    return when {
        m == null && session.memberships.size > 1 -> "role"
        m == null || !m.isActive -> "waiting"
        m.role == "USER" -> "home"
        m.role == "TECHNICIAN" -> "technician"
        m.role == "FM" || m.role == "ADMIN" -> "fm"
        else -> "waiting"
    }
}

@Composable
private fun waitingText(session: Session?): Pair<String, String> {
    val m = session?.membership ?: return "No role yet" to "This account has no role on a site. Ask your facility manager."
    val role = roleLabel(m.role).lowercase()
    return when {
        !m.isActive && m.role == "FM" -> "Waiting for approval" to
            "Your facility manager account for ${m.siteName} must be approved by an administrator. Tap Check again later."
        !m.isActive -> "Waiting for approval" to
            "Your $role account for ${m.siteName} must be approved by the facility manager. Tap Check again later."
        else -> "Nothing to do here" to "This account has no role with screens on ${m.siteName}."
    }
}
