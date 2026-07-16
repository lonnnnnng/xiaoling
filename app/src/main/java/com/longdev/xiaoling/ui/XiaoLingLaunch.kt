package com.longdev.xiaoling.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.longdev.xiaoling.R
import kotlinx.coroutines.delay

private const val LAUNCH_SPLASH_DURATION_MS = 880L

@Composable
fun XiaoLingLaunch(content: @Composable () -> Unit) {
    var showSplash by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        // long: 启动页只承担品牌过渡，不承载真实加载任务；时间控制在 1 秒内，避免用户每次打开都被动画阻塞。
        delay(LAUNCH_SPLASH_DURATION_MS)
        showSplash = false
    }

    Crossfade(
        targetState = showSplash,
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
    ) { visible ->
        if (visible) {
            XiaoLingSplashScreen()
        } else {
            content()
        }
    }
}

@Composable
private fun XiaoLingSplashScreen() {
    val darkTheme = isSystemInDarkTheme()
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        entered = true
    }

    val markScale by animateFloatAsState(
        targetValue = if (entered) 1f else 0.9f,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
    )
    val contentAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = 460, easing = FastOutSlowInEasing),
    )

    val backgroundColors = if (darkTheme) {
        listOf(Color(0xFF070B14), Color(0xFF0F172A), Color(0xFF172554))
    } else {
        listOf(Color(0xFFF8FAFC), Color(0xFFEFF6FF), Color(0xFFDBEAFE))
    }
    val titleColor = if (darkTheme) Color(0xFFE5EEF8) else Color(0xFF0F172A)
    val subtitleColor = if (darkTheme) Color(0xFFAFC7E8) else Color(0xFF527098)
    val accentColor = if (darkTheme) Color(0xFF5EEAD4) else Color(0xFF2563EB)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(backgroundColors)),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(280.dp)
                .alpha(if (darkTheme) 0.34f else 0.26f)
                .background(
                    Brush.radialGradient(
                        colors = listOf(accentColor, Color.Transparent),
                        radius = 420f,
                    ),
                    shape = CircleShape,
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .alpha(contentAlpha),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .scale(markScale)
                    .shadow(18.dp, RoundedCornerShape(34.dp), clip = false)
                    .clip(RoundedCornerShape(34.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF0F172A), Color(0xFF2563EB), Color(0xFF22D3EE)),
                        ),
                    )
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = if (darkTheme) 0.16f else 0.38f),
                        shape = RoundedCornerShape(34.dp),
                    )
                    .size(118.dp),
                contentAlignment = Alignment.Center,
            ) {
                // long: 启动页复用应用图标的核心标识，确保用户从桌面图标进入到主界面时能形成连续的品牌记忆。
                Image(
                    painter = painterResource(id = R.drawable.ic_xiaoling_splash_mark),
                    contentDescription = "小灵",
                    modifier = Modifier.size(94.dp),
                )
            }

            Spacer(modifier = Modifier.height(22.dp))
            Text(
                text = "小灵",
                color = titleColor,
                fontSize = 30.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(7.dp))
            Text(
                text = "你的个人 Agent",
                color = subtitleColor,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(20.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(3) { index ->
                    Box(
                        modifier = Modifier
                            .size(if (index == 1) 18.dp else 6.dp, 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (index == 1) {
                                    accentColor
                                } else {
                                    subtitleColor.copy(alpha = 0.45f)
                                },
                            ),
                    )
                }
            }
        }
    }
}
