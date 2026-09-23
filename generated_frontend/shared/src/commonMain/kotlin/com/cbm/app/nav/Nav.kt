package com.cbm.app.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember

sealed interface Screen {
    data object ReporterHome : Screen
    data class Capture(val reportId: String?, val attemptsLeft: Int) : Screen
    data class ReviewCapture(val reportId: String?, val x: Float, val y: Float) : Screen
    data object TechnicianHome : Screen
    data object SkillsOnboarding : Screen
    data class TechReportForm(val ticketId: Int) : Screen
    data object FmHome : Screen
    data object Notifications : Screen
    data object Profile : Screen
}

class NavStack(start: Screen) {
    private val stack = mutableStateListOf(start)
    val current: Screen get() = stack.last()
    val canPop: Boolean get() = stack.size > 1
    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (canPop) stack.removeAt(stack.lastIndex) }
    fun root(s: Screen) { stack.clear(); stack.add(s) }
}

@Composable
fun rememberNavStack(start: Screen): NavStack = remember { NavStack(start) }

@Composable
expect fun CbmBackHandler(enabled: Boolean, onBack: () -> Unit)
