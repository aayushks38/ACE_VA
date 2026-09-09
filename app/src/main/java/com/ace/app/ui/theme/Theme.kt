package com.ace.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val AceDarkColorScheme = darkColorScheme(
    primary = AcePurple,
    onPrimary = AceTextWhite,
    primaryContainer = AcePurpleDark,
    onPrimaryContainer = AceLavender,
    secondary = AceLavender,
    onSecondary = AceTextWhite,
    background = AceBackgroundDark,
    onBackground = AceTextWhite,
    surface = AceSurfaceDark,
    onSurface = AceTextWhite,
    surfaceVariant = AceSurfaceDark,
    onSurfaceVariant = AceTextGray,
    outline = AceBorderDark,
    outlineVariant = AceBorderPurple,
    error = Color(0xFFEF4444),
    onError = AceTextWhite
)

private fun android.content.Context.findActivity(): Activity? {
    var current = this
    while (current is android.content.ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

@Composable
fun AceTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = AceDarkColorScheme
    val view = LocalView.current
    
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context.findActivity()
            if (activity != null) {
                val window = activity.window
                window.statusBarColor = AceBackgroundDark.toArgb()
                window.navigationBarColor = AceBackgroundDark.toArgb()
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
