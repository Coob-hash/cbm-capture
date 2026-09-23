package com.cbm.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.cbm.app.data.CbmAppState
import com.cbm.app.data.FakeCbmApi
import com.cbm.app.data.LocalApp
import com.cbm.app.domain.MembershipStatus
import com.cbm.app.domain.Role
import com.cbm.app.feature.auth.LoginScreen
import com.cbm.app.feature.auth.MembershipPickerScreen
import com.cbm.app.feature.auth.PendingApprovalScreen
import com.cbm.app.feature.fm.FmHomeScreen
import com.cbm.app.feature.notifications.NotificationsScreen
import com.cbm.app.feature.profile.ProfileScreen
import com.cbm.app.feature.reporter.CaptureScreen
import com.cbm.app.feature.reporter.MyReportsScreen
import com.cbm.app.feature.reporter.ReviewCaptureScreen
import com.cbm.app.feature.technician.SkillsScreen
import com.cbm.app.feature.technician.TechReportFormScreen
import com.cbm.app.feature.technician.TechnicianHomeScreen
import com.cbm.app.nav.CbmBackHandler
import com.cbm.app.nav.Screen
import com.cbm.app.nav.rememberNavStack
import com.cbm.app.theme.CbmTheme
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock

@Composable
fun App() {
    val app = remember { CbmAppState(FakeCbmApi()) }
    CbmTheme(role = app.session?.active?.role) {
        CompositionLocalProvider(LocalApp provides app) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                val session = app.session
                when {
                    session == null -> LoginScreen()
                    session.active.status == MembershipStatus.PENDING -> PendingApprovalScreen()
                    session.memberships.size > 1 && !app.membershipChosen -> MembershipPickerScreen()
                    else -> RoleRoot()
                }
            }
        }
    }
}

@Composable
private fun RoleRoot() {
    val app = LocalApp.current
    val session = app.session ?: return
    val start = when (session.active.role) {
        Role.REPORTER -> Screen.ReporterHome
        Role.TECHNICIAN -> Screen.TechnicianHome
        Role.FM -> Screen.FmHome
    }
    val nav = rememberNavStack(start)
    CbmBackHandler(enabled = nav.canPop) { nav.pop() }
    LaunchedEffect(session.token) {
        app.refreshNotices()
        // One-hour session, never extended (PRD §4.1): back to login when it ends.
        while (true) {
            delay(5000)
            if (Clock.System.now().toEpochMilliseconds() > session.expiresAtMillis) { app.signOut(); break }
        }
    }
    when (val s = nav.current) {
        Screen.ReporterHome -> MyReportsScreen(
            onCapture = { nav.push(Screen.Capture(null, 3)) },
            onRetake = { id, left -> nav.push(Screen.Capture(id, left)) },
            onNotifications = { nav.push(Screen.Notifications) },
            onProfile = { nav.push(Screen.Profile) },
        )
        is Screen.Capture -> CaptureScreen(s.reportId, s.attemptsLeft, onBack = { nav.pop() }, onCaptured = { x, y -> nav.push(Screen.ReviewCapture(s.reportId, x, y)) })
        is Screen.ReviewCapture -> ReviewCaptureScreen(s.reportId, s.x, s.y, onDone = { nav.root(Screen.ReporterHome) }, onRetake = { nav.pop() })
        Screen.TechnicianHome -> TechnicianHomeScreen(
            onOpenReport = { nav.push(Screen.TechReportForm(it)) },
            onNotifications = { nav.push(Screen.Notifications) },
            onProfile = { nav.push(Screen.Profile) },
        )
        Screen.SkillsOnboarding -> SkillsScreen(onDone = { nav.pop() })
        is Screen.TechReportForm -> TechReportFormScreen(s.ticketId, onDone = { nav.pop() })
        Screen.FmHome -> FmHomeScreen(onNotifications = { nav.push(Screen.Notifications) }, onProfile = { nav.push(Screen.Profile) })
        Screen.Notifications -> NotificationsScreen(onBack = { nav.pop() })
        Screen.Profile -> ProfileScreen(onBack = { nav.pop() }, onSkills = { nav.push(Screen.SkillsOnboarding) })
    }
}
