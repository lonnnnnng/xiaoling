package com.longdev.endpointtester.ui.theme

import android.app.Activity
import android.os.Build
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.longdev.endpointtester.model.AppThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E3A8A),
    secondary = Color(0xFF64748B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F2FE),
    onSecondaryContainer = Color(0xFF0F172A),
    tertiary = Color(0xFF0F766E),
    tertiaryContainer = Color(0xFFCCFBF1),
    onTertiaryContainer = Color(0xFF134E4A),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0),
    error = Color(0xFFDC2626),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF93C5FD),
    onPrimary = Color(0xFF07111F),
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color(0xFFDCEBFF),
    secondary = Color(0xFFCBD5E1),
    onSecondary = Color(0xFF0F172A),
    secondaryContainer = Color(0xFF243244),
    onSecondaryContainer = Color(0xFFE2E8F0),
    tertiary = Color(0xFF5EEAD4),
    onTertiary = Color(0xFF042F2E),
    tertiaryContainer = Color(0xFF134E4A),
    onTertiaryContainer = Color(0xFFD7FFF8),
    background = Color(0xFF070B14),
    onBackground = Color(0xFFE5EEF8),
    surface = Color(0xFF101827),
    onSurface = Color(0xFFE5EEF8),
    surfaceVariant = Color(0xFF1B2637),
    onSurfaceVariant = Color(0xFFB8C4D6),
    outline = Color(0xFF64748B),
    outlineVariant = Color(0xFF28364A),
    error = Color(0xFFFCA5A5),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFFE4E6),
)

data class ChatBubblePalette(
    val userContainer: Color,
    val userContent: Color,
    val userBorder: Color,
    val userMeta: Color,
    val assistantContainer: Color,
    val assistantContent: Color,
    val assistantBorder: Color,
    val assistantMeta: Color,
    val errorContainer: Color,
    val errorContent: Color,
    val errorBorder: Color,
    val errorMeta: Color,
)

private val LightChatBubblePalette = ChatBubblePalette(
    userContainer = Color(0xFFDCEBFF),
    userContent = Color(0xFF102A56),
    userBorder = Color(0xFFB8D2FF),
    userMeta = Color(0xFF5E7AA8),
    assistantContainer = Color(0xFFFFF7ED),
    assistantContent = Color(0xFF2F2418),
    assistantBorder = Color(0xFFF3D6AE),
    assistantMeta = Color(0xFF8B6B47),
    errorContainer = Color(0xFFFFE4E6),
    errorContent = Color(0xFF7F1D1D),
    errorBorder = Color(0xFFFDA4AF),
    errorMeta = Color(0xFFA64B5C),
)

private val DarkChatBubblePalette = ChatBubblePalette(
    userContainer = Color(0xFF173B69),
    userContent = Color(0xFFEAF3FF),
    userBorder = Color(0xFF2A5C92),
    userMeta = Color(0xFFAFC7E8),
    assistantContainer = Color(0xFF223027),
    assistantContent = Color(0xFFF2F6EE),
    assistantBorder = Color(0xFF3D5044),
    assistantMeta = Color(0xFFB8C8B8),
    errorContainer = Color(0xFF5A1B2B),
    errorContent = Color(0xFFFFE8ED),
    errorBorder = Color(0xFF8D3148),
    errorMeta = Color(0xFFF2A8B8),
)

// long: 聊天记录区需要用角色专属色来建立阅读节奏；如果直接复用 Material 容器色，用户与模型消息在亮暗主题下都容易显得灰、脏或区分不足。
val LocalChatBubblePalette = staticCompositionLocalOf { LightChatBubblePalette }

private val CompactTypography = Typography(
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelLarge = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    bodySmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp),
)

@Suppress("DEPRECATION")
@Composable
fun EndpointTesterTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val chatBubblePalette = if (darkTheme) DarkChatBubblePalette else LightChatBubblePalette
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            @Suppress("DEPRECATION")
            run {
                window.statusBarColor = colorScheme.background.toArgb()
                window.navigationBarColor = colorScheme.background.toArgb()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                var flags = if (darkTheme) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                if (!darkTheme && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                }
                window.decorView.systemUiVisibility = flags
            }
        }
    }

    CompositionLocalProvider(
        LocalMinimumInteractiveComponentSize provides 0.dp,
        LocalChatBubblePalette provides chatBubblePalette,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = CompactTypography,
            content = content,
        )
    }
}
