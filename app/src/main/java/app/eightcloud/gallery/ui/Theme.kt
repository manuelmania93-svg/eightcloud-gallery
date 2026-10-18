package app.eightcloud.gallery.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF3E7CB8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E9F8),
    onPrimaryContainer = Color(0xFF0B2B44),
    secondary = Color(0xFF6FA8DC),
    background = Color(0xFFF2F8FD),
    onBackground = Color(0xFF16324A),
    surface = Color(0xFFF2F8FD),
    onSurface = Color(0xFF16324A),
    surfaceVariant = Color(0xFFE1EFFA),
    onSurfaceVariant = Color(0xFF3D5A74)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CC8EC),
    onPrimary = Color(0xFF0B2B44),
    primaryContainer = Color(0xFF1E4A6E),
    onPrimaryContainer = Color(0xFFD6E9F8),
    background = Color(0xFF10181F),
    onBackground = Color(0xFFDFE9F2),
    surface = Color(0xFF10181F),
    onSurface = Color(0xFFDFE9F2),
    surfaceVariant = Color(0xFF22313E),
    onSurfaceVariant = Color(0xFFB9CCDC)
)

private val CloudShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(28.dp)
)

@Composable
fun EightCloudTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        shapes = CloudShapes,
        content = content
    )
}
