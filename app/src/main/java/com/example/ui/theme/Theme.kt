package com.example.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = TokRed,
    onPrimary = Color.White,
    primaryContainer = TokRedContainer,
    onPrimaryContainer = TokOnRedContainer,
    secondary = TokCyan,
    onSecondary = Color.Black,
    secondaryContainer = TokCyanContainer,
    onSecondaryContainer = TokOnCyanContainer,
    tertiary = TokCyan,
    background = TokDarkBg,
    onBackground = TextPrimaryDark,
    surface = TokSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = TokSurfaceVariant,
    onSurfaceVariant = TextSecondaryDark,
    outline = TokOutline,
    outlineVariant = TokSurfaceCard
)

private val LightColorScheme = lightColorScheme(
    primary = TokRed,
    onPrimary = Color.White,
    primaryContainer = TokOnRedContainer,
    onPrimaryContainer = TokRedContainer,
    secondary = TokCyan,
    onSecondary = Color.Black,
    secondaryContainer = TokOnCyanContainer,
    onSecondaryContainer = TokCyanContainer,
    background = TokLightBg,
    onBackground = TextPrimaryLight,
    surface = TokLightSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = TokLightSurfaceVariant,
    onSurfaceVariant = TextSecondaryLight,
    outline = Color(0xFFCBD5E1)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // Default to sleek live streaming dark theme
    dynamicColor: Boolean = false, // Keep branded TokRecord aesthetic
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            window?.let {
                it.statusBarColor = colorScheme.background.toArgb()
                it.navigationBarColor = colorScheme.background.toArgb()
                val controller = WindowCompat.getInsetsController(it, view)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
