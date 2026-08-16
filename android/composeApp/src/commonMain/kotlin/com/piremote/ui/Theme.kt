package com.piremote.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.mikepenz.markdown.model.MarkdownTypography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.piremote.platform.platformDynamicColorScheme

/**
 * Dark-first: this is a terminal-adjacent tool, usually opened next to one.
 * Dynamic colour is used where the platform offers it, with a restrained
 * blue-grey fallback that keeps code blocks readable.
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF9EC7FF),
    onPrimary = Color(0xFF00325B),
    primaryContainer = Color(0xFF1E4B7D),
    surface = Color(0xFF121417),
    onSurface = Color(0xFFE2E2E5),
    surfaceVariant = Color(0xFF1D2126),
    onSurfaceVariant = Color(0xFFC3C7CF),
    background = Color(0xFF0E1013),
    error = Color(0xFFFFB4AB),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2A5CA8),
    surface = Color(0xFFFBFCFF),
    surfaceVariant = Color(0xFFEEF0F5),
    background = Color(0xFFFFFFFF),
)

/** Tool output, code, and streaming text all need a monospace face. */
val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp)

/**
 * Markdown styling for assistant replies, carried alongside the colour scheme.
 *
 * It belongs to the theme rather than to each message: it depends on nothing
 * but the scheme, and building it per message put a dozen text-style
 * allocations on the scroll path.
 */
val LocalMarkdownTypography = staticCompositionLocalOf<MarkdownTypography> {
    error("LocalMarkdownTypography read outside PiRemoteTheme")
}

@Composable
fun PiRemoteTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = when (val dynamic = platformDynamicColorScheme(dark)) {
        null -> if (dark) DarkColors else LightColors
        else -> dynamic
    }
    MaterialTheme(colorScheme = scheme, typography = Typography()) {
        // Inside MaterialTheme so it reads the scheme just applied, and only
        // rebuilt when that scheme changes.
        CompositionLocalProvider(
            LocalMarkdownTypography provides chatMarkdownTypography(),
            content = content,
        )
    }
}
