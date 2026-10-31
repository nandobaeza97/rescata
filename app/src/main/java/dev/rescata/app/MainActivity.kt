package dev.rescata.app

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RescataTheme { RescataScreen() }
        }
    }

    override fun onStart() {
        super.onStart()
        // Si Android pausó un trabajo largo (límite de 6 h de Android 15+), al volver se reanuda
        BackgroundWork.resumeIfNeeded(this)
    }
}

/** Colores de la marca (verde del icono y coral de la insignia) para móviles sin colores dinámicos. */
private val LightBrand = lightColorScheme(
    primary = Color(0xFF0F6E56),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3F2E6),
    onPrimaryContainer = Color(0xFF04342C),
    secondary = Color(0xFF4C6359),
    secondaryContainer = Color(0xFFE1F5EE),
    onSecondaryContainer = Color(0xFF085041),
    tertiary = Color(0xFFB4481F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFAECE7),
    onTertiaryContainer = Color(0xFF712B13),
    // Superficies neutras con un toque verde (sin ellas, Material usa un tono lila por defecto)
    background = Color(0xFFF6FBF8),
    onBackground = Color(0xFF171D1A),
    surface = Color(0xFFF6FBF8),
    onSurface = Color(0xFF171D1A),
    surfaceVariant = Color(0xFFDBE5DF),
    onSurfaceVariant = Color(0xFF3F4945),
    outline = Color(0xFF6F7975),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F5F2),
    surfaceContainer = Color(0xFFEAEFEC),
    surfaceContainerHigh = Color(0xFFE4EAE6),
    surfaceContainerHighest = Color(0xFFDFE4E1),
)

private val DarkBrand = darkColorScheme(
    primary = Color(0xFF5DCAA5),
    onPrimary = Color(0xFF04342C),
    primaryContainer = Color(0xFF085041),
    onPrimaryContainer = Color(0xFFD3F2E6),
    secondary = Color(0xFFB2CCC1),
    secondaryContainer = Color(0xFF1F3A31),
    onSecondaryContainer = Color(0xFFE1F5EE),
    tertiary = Color(0xFFF0997B),
    onTertiary = Color(0xFF4A1B0C),
    tertiaryContainer = Color(0xFF712B13),
    onTertiaryContainer = Color(0xFFFAECE7),
    background = Color(0xFF0F1512),
    onBackground = Color(0xFFDEE4E0),
    surface = Color(0xFF0F1512),
    onSurface = Color(0xFFDEE4E0),
    surfaceVariant = Color(0xFF3F4945),
    onSurfaceVariant = Color(0xFFBFC9C3),
    outline = Color(0xFF89938E),
    surfaceContainerLowest = Color(0xFF0A0F0D),
    surfaceContainerLow = Color(0xFF171D1A),
    surfaceContainer = Color(0xFF1B211E),
    surfaceContainerHigh = Color(0xFF252B28),
    surfaceContainerHighest = Color(0xFF303633),
)

private val RescataShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
private fun RescataTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    // Android 12+: colores del fondo de pantalla (como las apps de Google); antes, los de la marca
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkBrand
        else -> LightBrand
    }
    MaterialTheme(colorScheme = scheme, shapes = RescataShapes, content = content)
}
