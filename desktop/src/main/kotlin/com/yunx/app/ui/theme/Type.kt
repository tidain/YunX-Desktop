@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.yunx.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.FontHinting
import androidx.compose.ui.text.FontRasterizationSettings
import androidx.compose.ui.text.FontSmoothing
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.PlatformParagraphStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Windows 文字渲染按后端自适应：
 * - SOFTWARE：亚像素 ClearType + Slight hinting（CPU 光栅化下观感最锐利）；
 * - GPU（默认 OPENGL，备选 DIRECT3D）：灰度抗锯齿下采用 GDI 风格渲染追求最大锐度：
 *   Full hinting + 强制自动暗示把笔画对齐像素网格，并关闭亚像素定位
 *   （分数坐标会让同一字形的灰度分布不一致，反而显糊）。
 *   OPENGL 下此配置经用户实测：文字清晰 + 动画流畅（DIRECT3D 下 hinting 无改善）。
 * 仅桌面端生效（PlatformParagraphStyle 为 desktop API）。
 */
private val isSoftwareBackend =
    (System.getProperty("skiko.renderApi") ?: "SOFTWARE") == "SOFTWARE"

private val CrispPlatformStyle = PlatformParagraphStyle(
    fontRasterizationSettings = if (isSoftwareBackend) {
        FontRasterizationSettings(
            smoothing = FontSmoothing.SubpixelAntiAlias,
            hinting = FontHinting.Slight,
            subpixelPositioning = true,
            autoHintingForced = false
        )
    } else {
        FontRasterizationSettings(
            smoothing = FontSmoothing.AntiAlias,
            hinting = FontHinting.Full,
            subpixelPositioning = false,
            autoHintingForced = true
        )
    }
)

private fun TextStyle.crisp(): TextStyle = merge(ParagraphStyle(platformStyle = CrispPlatformStyle))

private fun crispAll(t: Typography): Typography = Typography(
    displayLarge = t.displayLarge.crisp(),
    displayMedium = t.displayMedium.crisp(),
    displaySmall = t.displaySmall.crisp(),
    headlineLarge = t.headlineLarge.crisp(),
    headlineMedium = t.headlineMedium.crisp(),
    headlineSmall = t.headlineSmall.crisp(),
    titleLarge = t.titleLarge.crisp(),
    titleMedium = t.titleMedium.crisp(),
    titleSmall = t.titleSmall.crisp(),
    bodyLarge = t.bodyLarge.crisp(),
    bodyMedium = t.bodyMedium.crisp(),
    bodySmall = t.bodySmall.crisp(),
    labelLarge = t.labelLarge.crisp(),
    labelMedium = t.labelMedium.crisp(),
    labelSmall = t.labelSmall.crisp()
)

/**
 * 全局排版：整体字号保持克制，符合现代紧凑设计。
 */
val Typography = crispAll(Typography(
    // 顶部大标题（折叠后缩小），避免过大
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.15.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.25.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.05.sp
    )
    )
)