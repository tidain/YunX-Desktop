package com.yunx.app.ui.screens

import com.yunx.app.ui.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.yunx.app.ui.theme.ThemeController

/** 预置主题色（Material 风格种子色） */
private val presetColors = listOf(
    "蓝色" to 0xFF415F91L,
    "靛蓝" to 0xFF3F51B5L,
    "紫色" to 0xFF6750A4L,
    "玫红" to 0xFFC2185BL,
    "红色" to 0xFFB3261EL,
    "橙色" to 0xFFF4631CL,
    "金黄" to 0xFFF9A825L,
    "绿色" to 0xFF38761DL,
    "青色" to 0xFF00897BL,
    "天蓝" to 0xFF0288D1L,
)

/**
 * 主题与外观设置页（参考 WebIDE ThemeSettingsItem 风格）：
 * - 外观模式：FilterChip 胶囊单选（跟随系统 / 浅色 / 深色）
 * - 主题色：可折叠卡片，动态色彩开关（Android12+）+ LazyRow 色圆选择 + 自定义调色盘
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }
    var showColorPicker by remember { mutableStateOf(false) }
    // 主题色卡片默认展开
    var expanded by rememberSaveable { mutableStateOf(true) }
    val expandDuration = 200

    // 单一动画源驱动折叠（Animatable 支持打断：快速连续点击时自动平滑过渡到新目标）：
    // 高度 = contentHeightPx * progress，透明度 = progress，二者同步
    val density = LocalDensity.current
    var contentHeightPx by remember { mutableIntStateOf(0) }
    val expandProgress = remember { Animatable(if (expanded) 1f else 0f) }
    LaunchedEffect(expanded) {
        expandProgress.animateTo(
            targetValue = if (expanded) 1f else 0f,
            animationSpec = tween(250, easing = FastOutSlowInEasing)
        )
    }

    // 桌面无动态取色：colorMode 0/1 等价（默认蓝色）
    val effectiveColorMode = ThemeController.colorMode

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("主题与外观", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            // ---------- 外观模式 ----------
            SectionLabel("外观模式")
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "选择应用的明暗外观",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val modes = listOf("跟随系统", "浅色", "深色")
                        modes.forEachIndexed { index, label ->
                            SmoothFilterChip(
                                selected = ThemeController.darkMode == index,
                                label = label,
                                onClick = { ThemeController.updateDarkMode(index) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ---------- 主题色（可折叠卡片） ----------
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column {
                    // Header：点击展开/收起
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded = !expanded }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Palette,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "主题色",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            AnimatedVisibility(
                                visible = !expanded,
                                enter = fadeIn(tween(200)) + expandVertically(tween(200), expandFrom = Alignment.Top),
                                exit = fadeOut(tween(200)) + shrinkVertically(tween(200), shrinkTowards = Alignment.Top)
                            ) {
                                Text(
                                    text = when {
                                        effectiveColorMode == 2 -> "自定义颜色"
                                        else -> "默认蓝色"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                        val rotation by animateFloatAsState(
                            targetValue = if (expanded) 180f else 0f,
                            label = "arrow",
                            animationSpec = tween(expandDuration)
                        )
                        Icon(
                            imageVector = Icons.Filled.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.rotate(rotation)
                        )
                    }

                            // 展开内容：高度 + 透明度由 Animatable 同步驱动（可打断、不裁剪、无跳变）
                            val animatedHeightDp = with(density) { (contentHeightPx * expandProgress.value).toDp() }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(if (contentHeightPx > 0) Modifier.height(animatedHeightDp) else Modifier)
                                    .clipToBounds()
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .wrapContentHeight(unbounded = true)
                                        .onSizeChanged { contentHeightPx = it.height }
                                        .graphicsLayer { alpha = expandProgress.value }
                                ) {
                        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Spacer(modifier = Modifier.height(16.dp))

                            // 默认蓝色开关（桌面无动态取色，0/1 等价：关闭即回到默认蓝）
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("默认蓝色", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "桌面端默认使用蓝色主题色",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = effectiveColorMode != 2,
                                    onCheckedChange = { on ->
                                        ThemeController.updateColorMode(if (on) 0 else 1)
                                    }
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))

                            // 主题色选择（始终可见：选择预设色或自定义即切到自定义模式）
                            Column {
                                Text(
                                    text = "主题颜色",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    contentPadding = PaddingValues(top = 10.dp, bottom = 8.dp)
                                ) {
                                    itemsIndexed(presetColors) { _, (name, color) ->
                                        // 默认蓝色模式只高亮蓝色；自定义模式高亮匹配种子色的那个
                                        val isSelected = (effectiveColorMode != 2 && color == 0xFF415F91L) ||
                                            (effectiveColorMode == 2 && ThemeController.seedColor == color)
                                        ColorSelectionItem(
                                            color = color,
                                            name = name,
                                            isSelected = isSelected,
                                            onClick = { ThemeController.updateSeedColor(color) }
                                        )
                                    }
                                    item {
                                        val isCustomSelected = effectiveColorMode == 2 &&
                                            ThemeController.seedColor !in presetColors.map { it.second }
                                        CustomColorButton(
                                            isSelected = isCustomSelected,
                                            customColor = ThemeController.seedColor,
                                            onClick = {
                                                ThemeController.updateColorMode(2)
                                                showColorPicker = true
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // 自定义调色盘
    if (showColorPicker) {
        ColorPickerDialog(
            initialColor = ThemeController.seedColor,
            onDismiss = { showColorPicker = false },
            onColorSelected = { color ->
                ThemeController.updateSeedColor(color)
                showColorPicker = false
            }
        )
    }
}

/** FilterChip 胶囊单选（参考 WebIDE SmoothFilterChip：选中 secondaryContainer，未选中描边） */
@Composable
private fun SmoothFilterChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val duration = 200
    val containerColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        animationSpec = tween(durationMillis = duration, easing = LinearEasing),
        label = "container"
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(durationMillis = duration, easing = LinearEasing),
        label = "content"
    )
    Surface(
        onClick = onClick,
        modifier = modifier.height(36.dp),
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        border = if (!selected) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}

/** 预置色圆点（色圆 + 名称，选中显示对勾） */
@Composable
private fun ColorSelectionItem(
    color: Long,
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color(color.toInt()))
                .border(
                    width = if (isSelected) 3.dp else 1.dp,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 自定义颜色按钮（色圆 + 名称，圆内显示 Palette 图标） */
@Composable
private fun CustomColorButton(
    isSelected: Boolean,
    customColor: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    if (isSelected) Color(customColor.toInt())
                    else MaterialTheme.colorScheme.surfaceContainerHighest
                )
                .border(
                    width = if (isSelected) 3.dp else 1.dp,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Palette,
                contentDescription = null,
                tint = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "自定义",
            style = MaterialTheme.typography.labelSmall,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ================= 调色盘 Dialog（参考 WebIDE ColorPickerDialog 精简版） =================

@Composable
private fun ColorPickerDialog(
    initialColor: Long,
    onDismiss: () -> Unit,
    onColorSelected: (Long) -> Unit
) {
    val initialHsv = colorToHsv(Color(initialColor.toInt()))
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initialHsv[1]) }
    var value by remember { mutableFloatStateOf(initialHsv[2]) }
    val currentColor = Color.hsv(hue, saturation, value)
    var hexInput by remember(currentColor) {
        mutableStateOf(colorToHex(currentColor).removePrefix("#"))
    }

    Dialog(onCloseRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth()
            ) {
                // 顶部：HEX 输入 + 预览
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "#",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        BasicTextField(
                            value = hexInput,
                            onValueChange = { input ->
                                val filtered = input.filter { it.isLetterOrDigit() }.take(6).uppercase()
                                hexInput = filtered
                                if (filtered.length == 6) {
                                    runCatching {
                                        val color = hexToColor(filtered)
                                        val hsv = colorToHsv(color)
                                        hue = hsv[0]
                                        saturation = hsv[1]
                                        value = hsv[2]
                                    }
                                }
                            },
                            textStyle = MaterialTheme.typography.headlineMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            ),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                capitalization = KeyboardCapitalization.Characters,
                                keyboardType = KeyboardType.Ascii
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.width(140.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    ) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(currentColor)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 颜色选择核心区：左侧 SatVal 方块 + 右侧色相条
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                ) {
                    SatValPanel(
                        hue = hue,
                        saturation = saturation,
                        value = value,
                        onValChange = { s, v ->
                            saturation = s
                            value = v
                        },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    VerticalHueSlider(
                        hue = hue,
                        onHueChange = { hue = it },
                        modifier = Modifier
                            .width(24.dp)
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 底部按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { onColorSelected(currentColor.toArgb().toLong() and 0xFFFFFFFFL) }) {
                        Text("应用")
                    }
                }
            }
        }
    }
}

/** 饱和度/明度大方块（可拖拽/点击） */
@Composable
private fun SatValPanel(
    hue: Float,
    saturation: Float,
    value: Float,
    onValChange: (Float, Float) -> Unit,
    modifier: Modifier
) {
    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        onValChange(
                            (offset.x / size.width).coerceIn(0f, 1f),
                            1f - (offset.y / size.height).coerceIn(0f, 1f)
                        )
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        onValChange(
                            (change.position.x / size.width).coerceIn(0f, 1f),
                            1f - (change.position.y / size.height).coerceIn(0f, 1f)
                        )
                    }
                }
        ) {
            drawRect(color = Color.hsv(hue, 1f, 1f))
            drawRect(brush = Brush.horizontalGradient(listOf(Color.White, Color.Transparent)))
            drawRect(brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))

            // 指示器（方形描边）
            val x = saturation * size.width
            val y = (1f - value) * size.height
            val cursorSize = 14f
            drawRect(
                color = Color.Black.copy(alpha = 0.5f),
                topLeft = Offset(x - cursorSize / 2, y - cursorSize / 2),
                size = Size(cursorSize, cursorSize),
                style = Stroke(3f)
            )
            drawRect(
                color = Color.White,
                topLeft = Offset(x - cursorSize / 2, y - cursorSize / 2),
                size = Size(cursorSize, cursorSize),
                style = Stroke(1.5f)
            )
        }
    }
}

/** 竖向色相条（可拖拽/点击） */
@Composable
private fun VerticalHueSlider(
    hue: Float,
    onHueChange: (Float) -> Unit,
    modifier: Modifier
) {
    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        onHueChange((offset.y / size.height * 360f).coerceIn(0f, 360f))
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        onHueChange((change.position.y / size.height * 360f).coerceIn(0f, 360f))
                    }
                }
        ) {
            val colors = (0..360 step 10).map { Color.hsv(it.toFloat(), 1f, 1f) }
            drawRect(brush = Brush.verticalGradient(colors = colors))

            // 指示器（横条）
            val y = (hue / 360f) * size.height
            val barHeight = 6f
            drawRect(
                color = Color.Black.copy(alpha = 0.5f),
                topLeft = Offset(0f, y - barHeight / 2),
                size = Size(size.width, barHeight),
                style = Stroke(2f)
            )
            drawRect(
                color = Color.White,
                topLeft = Offset(0f, y - barHeight / 2),
                size = Size(size.width, barHeight),
                style = Stroke(1f)
            )
        }
    }
}

// ================= 辅助 =================

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

/** RGB → HSV（桌面版无 android.graphics，纯 Kotlin 实现；H∈[0,360)，S/V∈[0,1]） */
private fun colorToHsv(color: Color): FloatArray {
    val r = color.red
    val g = color.green
    val b = color.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val h = when {
        delta == 0f -> 0f
        max == r -> 60f * (((g - b) / delta) % 6f)
        max == g -> 60f * ((b - r) / delta + 2f)
        else -> 60f * ((r - g) / delta + 4f)
    }.let { if (it < 0f) it + 360f else it }
    val s = if (max == 0f) 0f else delta / max
    return floatArrayOf(h, s, max)
}

private fun colorToHex(color: Color): String {
    val red = (color.red * 255).toInt()
    val green = (color.green * 255).toInt()
    val blue = (color.blue * 255).toInt()
    return "#%02X%02X%02X".format(red, green, blue)
}

private fun hexToColor(hex: String): Color {
    val clean = hex.removePrefix("#")
    return Color(
        red = clean.substring(0, 2).toInt(16) / 255f,
        green = clean.substring(2, 4).toInt(16) / 255f,
        blue = clean.substring(4, 6).toInt(16) / 255f
    )
}