package com.yunx.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunx.app.APP_VERSION
import com.yunx.app.ui.GlobalSnackbarHost
import com.yunx.app.ui.SnackbarController
import com.yunx.app.ui.theme.MotionPolicy
import com.yunx.app.util.DesktopActions
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** QQ 交流群群号（桌面端无 mqqapi scheme 保证，点击复制群号供用户在 QQ 中搜索） */
private const val QQ_GROUP = "635207650"

/** 引导页总页数：欢迎 / 功能特性 / 协议与声明 / 存储与安全 */
private const val PAGE_COUNT = 4

/** 页面正文的最大宽度：桌面窗口（默认 1100dp）远宽于手机，不限宽会把内容拉成一行超长文本 */
private const val CONTENT_MAX_WIDTH = 560

/** 换页时按钮文案 / 指示器的过渡时长（与项目内其它淡入淡出一致：进 170ms、出 140ms） */
private const val FADE_IN_MS = 170
private const val FADE_OUT_MS = 140

/**
 * 首次启动引导页（四页式，参照上游安卓端重做）：
 * - 第 1 页：简洁欢迎 —— 图标 / 名称 / 版本 / 一句话 / 标签胶囊 / 开源仓库 / QQ 群；
 * - 第 2 页：功能特性 + 「完全免费」卡；
 * - 第 3 页：用户协议 + 免责声明 + 开源协议（长文，页内可滚动）；
 * - 第 4 页：存储与安全 —— 下载保存位置 / 数据库位置 / 数据库加密等级（复用设置里的储存面板）；
 * - 底部导航：圆点指示器 + 「上一步 / 下一步 / 开始使用」，换页时按钮区淡入淡出；
 * - 背景：Animated Blob（流体渐变），自绘实现，不引第三方库。
 *
 * ★ 桌面端差异：上游的「权限准备」（通知 / 后台运行 / 存储）在桌面没有对应概念，换成了桌面版自己的
 *   功能特性页；第 4 页是桌面版新增的首次运行配置页。
 * ★ 限宽：手机端页面本来就只有几百 dp 宽，桌面窗口默认 1100dp —— 所有页面正文都限宽
 *   居中（[CONTENT_MAX_WIDTH]），否则一行文本横跨整个窗口，观感与上游差异极大。
 */
@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    /** 第 4 页「迁移数据库目录后立即重启」用：由 MainScreen 提供（拉起新实例 + 退出本进程） */
    onRestartApp: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
    val scope = rememberCoroutineScope()
    // 只在换页时变化的粗粒度读取：按钮文案/指示器需要它
    val page = pagerState.currentPage

    // ★ 整页铺一层 Surface：引导页是 MainScreen 里提前 return 的全屏覆盖页，**不在 Scaffold 里**，
    //   没有 Surface/Scaffold 就没人提供 LocalContentColor（其默认值是黑色）——
    //   于是没写 color 的文本在深色模式下会变成黑字。底色与 BlobBackground 自己铺的 base 一致。
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            BlobBackground()

            Column(modifier = Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) { index ->
                    // 页面内部的过渡：随滑动比例做轻微位移 + 淡出。
                    // ★ currentPageOffsetFraction 只在 graphicsLayer 块里读 —— 逐帧只失效图层，不重组页面内容。
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val offset = (pagerState.currentPage - index) + pagerState.currentPageOffsetFraction
                                translationX = offset * size.width * 0.22f
                                alpha = (1f - abs(offset)).coerceIn(0f, 1f)
                            }
                    ) {
                        when (index) {
                            0 -> WelcomePage()
                            1 -> FeaturesPage()
                            2 -> TermsPage()
                            else -> StorageSetupPage(onRestartApp = onRestartApp)
                        }
                    }
                }

                OnboardingBottomBar(
                    page = page,
                    pageCount = pagerState.pageCount,
                    onPrev = { scope.launch { pagerState.animateScrollToPage(page - 1) } },
                    onNext = { scope.launch { pagerState.animateScrollToPage(page + 1) } },
                    onFinish = onFinish
                )
            }

            // ★ 引导页自己渲染一个 Snackbar 宿主：MainScreen 的宿主在这页 early-return 之后才组合，
            //   没有它「QQ 群号已复制」之类的气泡根本没地方显示（点击复制会毫无反馈）。
            GlobalSnackbarHost()
        }
    }
}

// ==================== 背景：流体渐变 / Blob ====================

/**
 * Animated Blob 背景：底色 + 3 个缓慢漂移的径向渐变光斑（颜色→透明，天然柔边）。
 *
 * ★ 为什么用"径向渐变"而不是 Modifier.blur：径向渐变的 alpha 衰减本身就有柔边效果，
 *   不依赖平台模糊能力，观感在各后端（OPENGL / DIRECT3D / SOFTWARE）一致。
 * ★ 动效刻意用超长周期 + 线性缓动（环境动效是"呼吸"，不是"操作反馈"，弹簧/过冲在这里只会显得躁）。
 */
@Composable
private fun BlobBackground(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    // 颜色 / 透明度 / 初始相位
    val blobs = listOf(
        Triple(scheme.primary, 0.34f, 0.00f),
        Triple(scheme.tertiary, 0.28f, 0.34f),
        Triple(scheme.secondary, 0.24f, 0.67f)
    )
    val transition = if (MotionPolicy.reducedMotion) null else rememberInfiniteTransition(label = "blob")
    // 降级时不做无限漂移：固定相位画静态光斑（§3.7「无限装饰动画 → 改静态」）
    val drift = blobs.mapIndexed { i, _ ->
        if (transition == null) {
            remember { mutableFloatStateOf(0.15f + i * 0.33f) }
        } else {
            transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 17000 + i * 5300, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "blob$i"
            )
        }
    }
    val base = scheme.surface

    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(color = base)
        blobs.forEachIndexed { i, (color, alpha, phase) ->
            val t = (drift[i].value + phase) % 1f
            val angle = 2f * PI.toFloat() * t
            val center = Offset(
                x = size.width * (0.5f + 0.40f * cos(angle)),
                y = size.height * (0.5f + 0.32f * sin(angle * 0.85f + phase * 6f))
            )
            val radius = size.minDimension * (0.62f + 0.10f * sin(angle * 1.3f))
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = alpha), Color.Transparent),
                    center = center,
                    radius = radius
                ),
                radius = radius,
                center = center
            )
        }
    }
}

// ==================== 页面骨架 ====================

/**
 * 页面骨架：可滚动 + 限宽居中。
 *
 * ★ 用 fillMaxSize 而不是 wrapContentHeight：内容不足一屏时可视高度就是整屏，
 *   [centerVertically] 的 Arrangement.Center 才能真正居中；内容超出一屏时自动变成可滚动，两侧行为都对。
 */
@Composable
private fun OnboardingPage(
    centerVertically: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (centerVertically) Arrangement.Center else Arrangement.Top
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = CONTENT_MAX_WIDTH.dp)
                .fillMaxWidth()
                .padding(vertical = if (centerVertically) 8.dp else 16.dp),
            horizontalAlignment = if (centerVertically) Alignment.CenterHorizontally else Alignment.Start,
            content = content
        )
    }
}

// ==================== 第 1 页：简洁欢迎 ====================

@Composable
private fun WelcomePage() {
    OnboardingPage(centerVertically = true) {
        // 应用图标：圆角 + 投影，形成"浮在 blob 上"的观感
        Image(
            painter = painterResource("about_icon.png"),
            contentDescription = "云析图标",
            modifier = Modifier
                .size(120.dp)
                .shadow(elevation = 10.dp, shape = RoundedCornerShape(28.dp))
        )

        Spacer(modifier = Modifier.height(26.dp))
        Text(
            text = "云析",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "YunX-Desktop · v$APP_VERSION",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "网盘分享 · 视频链接，一站式解析与高速下载",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(26.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FactChip("10 大网盘")
            FactChip("7 个视频平台")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FactChip("分片下载")
            FactChip("磁力 / BT / ed2k")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FactChip("在线预览")
            FactChip("完全免费")
        }

        Spacer(modifier = Modifier.height(30.dp))
        GitHubCard()

        Spacer(modifier = Modifier.height(12.dp))
        QQGroupPill()
    }
}

/** 小标签（胶囊）：底色比背景亮一档，浮在 blob 上仍然清晰 */
@Composable
private fun FactChip(text: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
        )
    }
}

/** 开源仓库入口卡片 */
@Composable
private fun GitHubCard() {
    Card(
        onClick = {
            DesktopActions.openUrl("https://github.com/tidain/YunX-Desktop")
        },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Code,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "开源仓库",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "github.com/tidain/YunX-Desktop",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Outlined.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}

/**
 * QQ 交流群入口（胶囊）：点击复制群号（桌面端无法可靠拉起 QQ）。
 *
 * ★ 做成胶囊而不是卡片：与上面的 GitHub 卡片形成主次，小屏/矮窗口下也不会把图标区挤掉。
 * ★ 反馈用全局 Snackbar —— 引导页自己渲染了 GlobalSnackbarHost（见 OnboardingScreen）。
 */
@Composable
private fun QQGroupPill() {
    Card(
        onClick = {
            DesktopActions.copyToClipboard(QQ_GROUP)
            SnackbarController.show("QQ 群号已复制：$QQ_GROUP")
        },
        shape = CircleShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Forum,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "QQ 群 $QQ_GROUP",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Outlined.OpenInNew,
                contentDescription = "复制群号",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

// ==================== 第 2 页：功能特性 ====================

@Composable
private fun FeaturesPage() {
    OnboardingPage {
        Text(
            text = "功能特性",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "网盘分享与视频链接，一个应用全部搞定",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))

        FeatureRow(
            icon = Icons.Outlined.Link,
            title = "一键解析分享链接",
            description = "夸克 / UC / 迅雷 / 百度 / 139 / 123 / 115 / 光鸭 / 蓝奏云 / 蓝奏云优享版 分享链接自动识别，登录网盘账号后即可解析与下载"
        )
        FeatureRow(
            icon = Icons.Outlined.Forum,
            title = "视频链接解析",
            description = "哔哩哔哩 / 小红书 / 抖音 / 微博 / 快手 / AcFun / Pixiv：短链、口令、整段文案粘贴即解析，可选清晰度、批量下载图集"
        )
        FeatureRow(
            icon = Icons.Outlined.Speed,
            title = "高速分片下载",
            description = "多线程并发 + 断点续传 + 慢连接自动换线；B 站高清分轨两轨并行，下载后自动合轨"
        )
        FeatureRow(
            icon = Icons.Outlined.CloudDownload,
            title = "P2P 下载",
            description = "磁力 / BT 种子 / ed2k 电驴链接交给下载引擎，支持本地种子文件、Tracker 服务器管理与 DHT 开关"
        )
        FeatureRow(
            icon = Icons.Outlined.Storage,
            title = "在线预览与 Live 图",
            description = "视频直读直链、边下边播（HLS 原生支持，可全屏 / 拖动）；首次预览缺引擎时会询问是否下载 FFmpeg，装完自动起播；微博、抖音动图可存为动态照片、静图或视频"
        )
        FeatureRow(
            icon = Icons.Outlined.Lock,
            title = "多账号与隐私",
            description = "网盘与视频平台都支持多账号（主账号 / 备注），凭证仅加密存本机，可导出加密备份换机恢复"
        )

        Spacer(modifier = Modifier.height(12.dp))
        FreeCard()
    }
}

/** 功能特性条目：圆形图标底 + 标题 + 描述 */
@Composable
private fun FeatureRow(
    icon: ImageVector,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 19.sp
            )
        }
    }
}

/** 「完全免费」卡 */
@Composable
private fun FreeCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.12f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Favorite,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = "完全免费",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    text = "无广告、无内购，所有功能永久免费",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
    }
}

// ==================== 第 3 页：协议与声明 ====================

@Composable
private fun TermsPage() {
    OnboardingPage {
        Text(
            text = "使用前请阅读",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "云析是开源工具，以下是使用条款与免责说明",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))

        TermsCard(
            icon = Icons.Outlined.Description,
            title = "用户协议",
            paragraphs = listOf(
                "本应用是开源的网盘分享链接解析与下载工具，仅供个人学习、技术交流使用，请勿用于任何商业用途。",
                "请遵守你所使用网盘的服务条款及当地法律法规；不得利用本应用下载、传播侵权或违法内容。",
                "网盘账号凭证（Cookie / Token）仅保存在本机，不会上传到任何服务器。",
                "应用按「现状」提供，不保证解析成功率与下载速度，也不对你的使用结果作出任何承诺。"
            )
        )
        Spacer(modifier = Modifier.height(12.dp))

        TermsCard(
            icon = Icons.Outlined.Shield,
            title = "免责声明",
            paragraphs = listOf(
                "下载内容的版权归原作者所有，请于下载后 24 小时内删除。",
                "使用本应用产生的任何后果由使用者自行承担，开发者不承担由此引起的任何责任。"
            )
        )
        Spacer(modifier = Modifier.height(12.dp))

        TermsCard(
            icon = Icons.Outlined.Code,
            title = "开源协议",
            paragraphs = listOf(
                "本项目基于 GNU AGPL-3.0 协议开源，源码公开于 github.com/tidain/YunX-Desktop。"
            )
        )

        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = "点击下方「下一步」即表示你已阅读并同意以上条款",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** 协议卡片：图标 + 标题 + 若干段落 */
@Composable
private fun TermsCard(
    icon: ImageVector,
    title: String,
    paragraphs: List<String>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            paragraphs.forEachIndexed { i, p ->
                if (i > 0) Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = p,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )
            }
        }
    }
}

// ==================== 第 4 页：存储与安全 ====================

/**
 * 第 4 页：首次启动就把「下载保存位置 / 数据库位置 / 数据库加密等级」设好。
 * 直接复用「设置 → 储存目录」的同一套面板（[StorageSettingsPanel]）并隐藏缓存目录区块 ——
 * 两处共用同一份搬迁 / 改档逻辑，不会分叉。
 */
@Composable
private fun StorageSetupPage(onRestartApp: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(20.dp))
        Column(
            modifier = Modifier
                .widthIn(max = CONTENT_MAX_WIDTH.dp)
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
        ) {
            Text(
                text = "存储与安全",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "先定好下载文件的保存位置、数据存放位置与加密方式；之后随时可在「设置 → 储存目录」修改。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        // ★ 面板自带滚动：必须给它一个有界高度（weight）才能滚，否则会被内容撑开、顶掉底部导航
        StorageSettingsPanel(
            onRestartApp = onRestartApp,
            showCacheDir = false,
            // 左右 28dp 与上面的标题对齐（面板默认 16dp，这里改成与标题一致的 28dp）
            contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 0.dp, bottom = 20.dp),
            modifier = Modifier
                .widthIn(max = CONTENT_MAX_WIDTH.dp)
                .fillMaxWidth()
                .weight(1f)
        )
    }
}

// ==================== 底部导航 ====================

/**
 * 底部导航：进度指示器居中，「上一步」在左、「下一步 / 开始使用」在右（紧凑按钮，不占满整行）。
 * ★ 用 Box + align 而不是 Row(SpaceBetween)：左右按钮宽度随文案变化（「上一步」与「开始使用」字数不同），
 *   SpaceBetween 会把指示器挤偏；绝对居中才符合"进度条在中间"。
 */
@Composable
private fun OnboardingBottomBar(
    page: Int,
    pageCount: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onFinish: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp)
    ) {
        // 中：进度指示器（绝对居中）
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(pageCount) { i ->
                val selected = i == page
                val dotWidth by animateDpAsState(
                    targetValue = if (selected) 22.dp else 8.dp,
                    animationSpec = tween(durationMillis = FADE_IN_MS),
                    label = "dotWidth"
                )
                val dotColor by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                    animationSpec = tween(durationMillis = FADE_IN_MS),
                    label = "dotColor"
                )
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(width = dotWidth, height = 8.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
            }
        }

        // 左：上一步（第 1 页没有上一页：淡出且不占位，指示器因此始终居中）
        AnimatedVisibility(
            visible = page > 0,
            enter = fadeIn(tween(FADE_IN_MS)),
            exit = fadeOut(tween(FADE_OUT_MS)),
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            TextButton(
                onClick = onPrev,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.ChevronLeft,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(text = "上一步", style = MaterialTheme.typography.labelLarge)
            }
        }

        // 右：下一步 / 开始使用
        AnimatedContent(
            targetState = page,
            transitionSpec = { fadeIn(tween(FADE_IN_MS)) togetherWith fadeOut(tween(FADE_OUT_MS)) },
            modifier = Modifier.align(Alignment.CenterEnd),
            label = "onboardingAction"
        ) { p ->
            val last = p == pageCount - 1
            Button(
                onClick = { if (last) onFinish() else onNext() },
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)
            ) {
                if (last) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = if (last) "开始使用" else "下一步",
                    style = MaterialTheme.typography.labelLarge
                )
                if (!last) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
