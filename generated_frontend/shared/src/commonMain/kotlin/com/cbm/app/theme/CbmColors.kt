package com.cbm.app.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * CBM field-instrument palette.
 * Concrete + paper neutrals, ink/steel structure, hi-vis signal colours used
 * only for semantics. Role accents follow PRD §4.2: reporter = blueprint blue,
 * technician = hi-vis orange, FM = site teal.
 */
object CbmPalette {
    val Ink900 = Color(0xFF14171B)
    val Ink800 = Color(0xFF1C2026)
    val Steel700 = Color(0xFF272D34)
    val Steel600 = Color(0xFF353D46)
    val Steel500 = Color(0xFF49525C)
    val Steel400 = Color(0xFF6A7480)
    val Steel300 = Color(0xFF97A0AA)
    val Steel200 = Color(0xFFC7CDD3)
    val Steel100 = Color(0xFFE3E6E9)
    val Concrete50 = Color(0xFFF4F3EE)
    val Concrete100 = Color(0xFFECEAE3)
    val Concrete200 = Color(0xFFDFDCD2)
    val Paper = Color(0xFFFCFBF7)
    val HiVis = Color(0xFFE8590C)
    val HiVisDark = Color(0xFFBF4A08)
    val HiVisSoft = Color(0xFFFBE3D2)
    val Amber = Color(0xFFE8890C)
    val AmberSoft = Color(0xFFFCF0DA)
    val Red = Color(0xFFC92A2A)
    val RedSoft = Color(0xFFF9E1E1)
    val Green = Color(0xFF2B8A3E)
    val GreenSoft = Color(0xFFE1F0E4)
    val Blueprint = Color(0xFF2F5D8A)
    val BlueprintSoft = Color(0xFFE2EAF3)
    val Teal = Color(0xFF0B6E63)
    val TealSoft = Color(0xFFDDEDEA)
}

enum class RoleAccent(val color: Color, val soft: Color, val homeTitle: String) {
    REPORTER(CbmPalette.Blueprint, CbmPalette.BlueprintSoft, "My reports"),
    TECHNICIAN(CbmPalette.HiVis, CbmPalette.HiVisSoft, "My jobs"),
    FM(CbmPalette.Teal, CbmPalette.TealSoft, "Building overview"),
}

val CbmLightColors = lightColorScheme(
    primary = CbmPalette.HiVis,
    onPrimary = Color.White,
    primaryContainer = CbmPalette.HiVisSoft,
    onPrimaryContainer = CbmPalette.HiVisDark,
    secondary = CbmPalette.Steel600,
    onSecondary = Color.White,
    secondaryContainer = CbmPalette.Steel100,
    onSecondaryContainer = CbmPalette.Steel700,
    background = CbmPalette.Concrete50,
    onBackground = CbmPalette.Ink900,
    surface = CbmPalette.Paper,
    onSurface = CbmPalette.Ink900,
    surfaceVariant = CbmPalette.Concrete100,
    onSurfaceVariant = CbmPalette.Steel500,
    outline = CbmPalette.Steel200,
    outlineVariant = CbmPalette.Steel100,
    error = CbmPalette.Red,
    onError = Color.White,
    errorContainer = CbmPalette.RedSoft,
    onErrorContainer = CbmPalette.Red,
)

val CbmDarkColors = darkColorScheme(
    primary = CbmPalette.HiVis,
    onPrimary = Color.White,
    primaryContainer = CbmPalette.HiVisDark,
    onPrimaryContainer = Color.White,
    secondary = CbmPalette.Steel300,
    onSecondary = CbmPalette.Ink900,
    secondaryContainer = CbmPalette.Steel700,
    onSecondaryContainer = CbmPalette.Steel100,
    background = CbmPalette.Ink900,
    onBackground = CbmPalette.Steel100,
    surface = CbmPalette.Ink800,
    onSurface = CbmPalette.Steel100,
    surfaceVariant = CbmPalette.Steel700,
    onSurfaceVariant = CbmPalette.Steel300,
    outline = CbmPalette.Steel600,
    outlineVariant = CbmPalette.Steel700,
    error = Color(0xFFE57373),
    onError = CbmPalette.Ink900,
)
