package com.lalatendu.poweroffremote.ui.theme

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

private val Mint = Color(0xFF12B886)
private val MintLight = Color(0xFF22D3A5)
private val Danger = Color(0xFFE5484D)
private val DangerDark = Color(0xFFFF6369)
private val Ink = Color(0xFF0B1220)

private val DarkScheme = darkColorScheme(
    primary = MintLight,
    onPrimary = Color(0xFF00251A),
    primaryContainer = Color(0xFF00513A),
    onPrimaryContainer = Color(0xFFA9F0D6),
    secondary = Color(0xFF8FB8FF),
    error = DangerDark,
    onError = Color(0xFF3B0708),
    background = Ink,
    surface = Color(0xFF111A2B),
    surfaceVariant = Color(0xFF1B2740),
    onSurfaceVariant = Color(0xFFB9C4D8),
)

private val LightScheme = lightColorScheme(
    primary = Mint,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB6F2DD),
    onPrimaryContainer = Color(0xFF00261A),
    secondary = Color(0xFF31589E),
    error = Danger,
    background = Color(0xFFF7F9FC),
    surface = Color.White,
    surfaceVariant = Color(0xFFE3E9F2),
    onSurfaceVariant = Color(0xFF44506A),
)

/** Green means "reachable", red means "about to be turned off". Used outside the color scheme too. */
object StatusColors {
    val up @Composable get() = if (isSystemInDarkTheme()) MintLight else Mint
    val down @Composable get() = if (isSystemInDarkTheme()) DangerDark else Danger
    val unknown @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun PowerOffRemoteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
