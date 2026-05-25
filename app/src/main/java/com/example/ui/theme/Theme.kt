package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = DarkBluePrimary,
    secondary = DarkGrayContainer,
    tertiary = DarkBlueContainer,
    background = DarkLightBg,
    surface = DarkGrayContainer,
    onPrimary = Color.White,
    onSecondary = DarkSlateText,
    onBackground = DarkSlateText,
    onSurface = DarkSlateText,
    surfaceVariant = DarkLightGrayActive,
    onSurfaceVariant = DarkSlateSubtle,
    outline = DarkGrayContainer
  )

private val LightColorScheme =
  lightColorScheme(
    primary = CleanBluePrimary,
    secondary = CleanGrayContainer,
    tertiary = CleanBlueContainer,
    background = CleanLightBg,
    surface = Color.White,
    onPrimary = Color.White,
    onSecondary = CleanSlateText,
    onBackground = CleanSlateText,
    onSurface = CleanSlateText,
    surfaceVariant = CleanLightGrayActive,
    onSurfaceVariant = CleanSlateSubtle,
    outline = CleanGrayContainer
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Disable dynamic system overlays so our precise Clean Minimal palette is preserved
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
