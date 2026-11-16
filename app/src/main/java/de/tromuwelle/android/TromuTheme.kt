package de.tromuwelle.android

import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp

/** User-selectable Material 3 palettes. Settings contain no account information. */
enum class TromuPalette(val title: String, val darkAccent: Color, val lightAccent: Color) {
    MINT("Mint", Color(0xFF69E5CD), Color(0xFF006B58)),
    OCEAN("Ozean", Color(0xFF88C8FF), Color(0xFF175E9F)),
    VIOLET("Violett", Color(0xFFD1B1FF), Color(0xFF7041A5)),
    SUNSET("Sonnenuntergang", Color(0xFFFFC18B), Color(0xFF91501A)),
    DYNAMIC("Material You", Color(0xFF90C9FF), Color(0xFF175E9F));

    companion object {
        fun fromStored(value: String?): TromuPalette =
            entries.firstOrNull { it.name == value } ?: MINT
    }
}

enum class TromuAppearance(val title: String) {
    SYSTEM("System"), DARK("Dunkel"), LIGHT("Hell");
    companion object {
        fun fromStored(value: String?): TromuAppearance =
            entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

class TromuThemeSettings(context: Context) {
    private val preferences = context.getSharedPreferences("tromuwelle_appearance_v1",
        Context.MODE_PRIVATE)
    var palette by mutableStateOf(TromuPalette.fromStored(
        preferences.getString("palette", null)))
        private set
    var appearance by mutableStateOf(TromuAppearance.fromStored(
        preferences.getString("appearance", null)))
        private set

    fun setPalette(next: TromuPalette) {
        palette = next
        preferences.edit().putString("palette", next.name).apply()
    }

    fun setAppearance(next: TromuAppearance) {
        appearance = next
        preferences.edit().putString("appearance", next.name).apply()
    }
}

@Composable
fun TromuStreamingTheme(settings: TromuThemeSettings, content: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dark = when (settings.appearance) {
        TromuAppearance.DARK -> true
        TromuAppearance.LIGHT -> false
        TromuAppearance.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    val palette = settings.palette
    val scheme = if (palette == TromuPalette.DYNAMIC &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) {
        darkColorScheme(
            primary = palette.darkAccent,
            onPrimary = Color(0xFF0C1420),
            secondary = Color(0xFFAAC5E6),
            onSecondary = Color(0xFF10253F),
            tertiary = Color(0xFFB6B1FA),
            onTertiary = Color(0xFF242047),
            background = Color(0xFF090E18),
            onBackground = Color(0xFFF5F7FF),
            surface = Color(0xFF121A28),
            onSurface = Color(0xFFF5F7FF),
            surfaceVariant = Color(0xFF243044),
            onSurfaceVariant = Color(0xFFD1DBEA),
            primaryContainer = Color(0xFF26384D),
            onPrimaryContainer = Color(0xFFF2F8FF),
            outline = Color(0xFF718197),
            error = Color(0xFFFFB3B8),
            onError = Color(0xFF5F1119),
            errorContainer = Color(0xFF5B242D),
            onErrorContainer = Color(0xFFFFE1E3)
        )
    } else {
        lightColorScheme(
            primary = palette.lightAccent,
            onPrimary = Color.White,
            secondary = Color(0xFF435F7B),
            onSecondary = Color.White,
            tertiary = Color(0xFF59559D),
            onTertiary = Color.White,
            background = Color(0xFFF4F7FC),
            onBackground = Color(0xFF111A2A),
            surface = Color.White,
            onSurface = Color(0xFF142035),
            surfaceVariant = Color(0xFFE5EBF4),
            onSurfaceVariant = Color(0xFF394B61),
            primaryContainer = Color(0xFFDDECF9),
            onPrimaryContainer = Color(0xFF132944),
            outline = Color(0xFF687D92),
            error = Color(0xFFB32631),
            onError = Color.White,
            errorContainer = Color(0xFFFFDADD),
            onErrorContainer = Color(0xFF5E151C)
        )
    }
    // One consistent, scalable typography system across discovery, login and
    // playback. SP units retain Android's accessibility font scaling.
    val type = remember {
        val base = Typography()
        Typography(
            headlineLarge = base.headlineLarge.copy(
                fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.7).sp),
            headlineMedium = base.headlineMedium.copy(
                fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
            headlineSmall = base.headlineSmall.copy(
                fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
            titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
            titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold)
        )
    }
    MaterialTheme(colorScheme = scheme, typography = type,
        shapes = Shapes(
            extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
            large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
        ), content = content)
}

/** One selector drives all screens, including login and player; no per-page theme overrides. */
@Composable
fun TromuThemeSelector(settings: TromuThemeSettings) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Text("Darstellung", style = MaterialTheme.typography.headlineSmall)
        Text("Wähle deine Farben. Die Einstellung gilt in der gesamten App und bleibt gespeichert.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Helligkeit", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            TromuAppearance.entries.forEach { appearance ->
                FilterChip(selected = settings.appearance == appearance,
                    onClick = { settings.setAppearance(appearance) },
                    label = { Text(appearance.title) })
            }
        }
        Text("Farbschema", style = MaterialTheme.typography.titleMedium)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TromuPalette.entries.forEach { palette ->
                FilterChip(selected = settings.palette == palette,
                    onClick = { settings.setPalette(palette) },
                    leadingIcon = {
                        Box(Modifier.size(18.dp).background(
                            if (settings.appearance == TromuAppearance.LIGHT)
                                palette.lightAccent else palette.darkAccent, CircleShape))
                    },
                    label = {
                        Text(if (palette == TromuPalette.DYNAMIC &&
                            Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
                            "Material You (ab Android 12)" else palette.title)
                    })
            }
        }
        Text("Die Systemfarben von Material You sind ab Android 12 verfügbar.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall)
    }
}
