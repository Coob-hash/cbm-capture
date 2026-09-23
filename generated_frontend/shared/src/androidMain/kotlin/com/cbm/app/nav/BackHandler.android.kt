package com.cbm.app.nav

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

@Composable
actual fun CbmBackHandler(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)
