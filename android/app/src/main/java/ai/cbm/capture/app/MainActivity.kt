package ai.cbm.capture.app

import ai.cbm.capture.ui.capture.CaptureScreen
import ai.cbm.capture.ui.reports.ReportsScreen
import ai.cbm.capture.ui.settings.SettingsScreen
import ai.cbm.capture.ui.theme.CbmCaptureTheme
import ai.cbm.capture.work.UploadWorker
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CbmCaptureTheme {
                val navController = rememberNavController()
                val scope = rememberCoroutineScope()

                NavHost(navController = navController, startDestination = Route.CAPTURE) {
                    composable(Route.CAPTURE) {
                        CaptureScreen(
                            onOpenReports = { navController.navigate(Route.REPORTS) },
                            onOpenSettings = { navController.navigate(Route.SETTINGS) }
                        )
                    }
                    composable(Route.REPORTS) {
                        val viewModel = androidx.hilt.navigation.compose.hiltViewModel<
                            ai.cbm.capture.ui.reports.ReportsViewModel>()
                        ReportsScreen(
                            onBack = { navController.popBackStack() },
                            onOpenSettings = { navController.navigate(Route.SETTINGS) },
                            onSendAll = {
                                scope.launch {
                                    UploadWorker.enqueueNow(this@MainActivity, viewModel.allowMetered())
                                }
                            },
                            viewModel = viewModel
                        )
                    }
                    composable(Route.SETTINGS) {
                        SettingsScreen(onBack = { navController.popBackStack() })
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning to the app is the most common moment for connectivity to have changed, so
        // it is the cheapest place to nudge the queue.
        UploadWorker.enqueue(this, allowMetered = true)
    }

    private object Route {
        const val CAPTURE = "capture"
        const val REPORTS = "reports"
        const val SETTINGS = "settings"
    }
}
