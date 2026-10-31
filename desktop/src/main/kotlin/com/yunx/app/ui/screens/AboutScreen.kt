package com.yunx.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunx.app.APP_IS_PREVIEW
import com.yunx.app.ui.BackHandler
import com.yunx.app.ui.SnackbarController
import com.yunx.app.data.update.UpdateChecker
import com.yunx.app.util.DesktopActions

/** QQ 交流群群号（关于页入口；桌面端无 mqqapi scheme 保证，点击复制群号供用户在 QQ 中搜索） */
private const val QQ_GROUP = "635207650"

/**
 * 关于云析页：应用介绍、支持平台、功能特性、技术栈与免责声明。
 * Material3 风格：卡片分区 + 主题色 + 动态色适配。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onPreviewOnboarding: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 系统返回键 → 返回主页（而不是退出应用）
    BackHandler { onBack() }
    val versionName = UpdateChecker.currentVersion()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("关于云析", style = MaterialTheme.typography.titleLarge) },
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
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ---------- App 头部 ----------
            AppHeader(versionName = versionName)

            // ---------- 简介 ----------
            InfoCard(
                icon = Icons.Outlined.Cloud,
                title = "应用简介",
                description = "云析（YunX）是一款网盘分享链接 + 视频链接解析与高速下载工具。" +
                    "粘贴分享链接或视频短链，登录账号后即可浏览内容、在线预览并直接高速下载；" +
                    "也支持磁力 / BT 种子 / ed2k 等 P2P 链接下载。\n" +
                    "Windows 桌面端：YunX-Desktop 基于 Compose Multiplatform 构建，" +
                    "在安卓版能力之上补齐桌面交互（右键菜单、文件夹整夹下载、可拖动窗口）。"
            )
            

            // ---------- 支持平台 ----------
            PlatformCard()

            // ---------- 功能特性 ----------
            FeatureCard()

            // ---------- 技术栈 ----------
            TechCard()

            // ---------- 免责声明 ----------
            DisclaimerCard()

            // ---------- 开源协议 ----------
            Text(
                text = "本项目基于 GNU AGPL-3.0 协议开源",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            // ---------- 重新预览欢迎界面 ----------
            PreviewOnboardingCard(onClick = onPreviewOnboarding)

            // ---------- 开源仓库 ----------
            GitHubCard()
            Spacer(modifier = Modifier.height(8.dp))
            DesktopRepoCard()

            Spacer(modifier = Modifier.height(8.dp))
            QQGroupCard()

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "云析 v$versionName · Made with ❤ and deepseek",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

/** App 头部：应用图标 + 应用名 + 版本 + 标语 */
@Composable
private fun AppHeader(versionName: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource("about_icon.png"),
            contentDescription = "云析图标",
            modifier = Modifier.size(88.dp)
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "云析",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "YunX-Desktop · v$versionName",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // 预览版标记：version.txt 第二行写了 preview / beta 时显示（黄色底，浅深色下都醒目）
            if (APP_IS_PREVIEW) {
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = Color(0xFFFFC107)
                ) {
                    Text(
                        text = "Beta",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF3A2A00),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "网盘与视频链接解析 · 高速下载",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

/** 通用信息卡片：图标 + 标题 + 描述 */
@Composable
private fun InfoCard(icon: ImageVector, title: String, description: String) {
    SectionCard {
        Row(verticalAlignment = Alignment.Top) {
            CardIcon(icon)
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 22.sp
                )
            }
        }
    }
}

/** 支持平台卡片（网盘 + 视频解析） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlatformCard() {
    val platforms = listOf(
        "夸克网盘" to Icons.Outlined.Cloud,
        "UC 网盘" to Icons.Outlined.Storage,
        "迅雷网盘" to Icons.Outlined.Speed,
        "百度网盘" to Icons.Outlined.Link,
        "139 网盘" to Icons.Outlined.Cloud,
        "123云盘" to Icons.Outlined.Cloud,
        "115网盘" to Icons.Outlined.Cloud,
        "光鸭云盘" to Icons.Outlined.Cloud,
        "蓝奏云" to Icons.Outlined.Cloud,
        "蓝奏云优享版" to Icons.Outlined.Cloud
    )
    val videoPlatforms = listOf("哔哩哔哩", "小红书", "抖音", "微博", "快手", "AcFun", "Pixiv")
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardIcon(Icons.Outlined.Storage)
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = "支持平台",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            platforms.forEach { (name, icon) ->
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = name,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardIcon(Icons.Outlined.Forum)
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = "视频解析",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "短链 / 口令 / 整段文案粘贴即解析，可选清晰度与图集",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            videoPlatforms.forEach { name ->
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    Text(
                        text = name,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }
    }
}

/** 功能特性卡片 */
@Composable
private fun FeatureCard() {
    val features = listOf(
        "网盘分享解析" to "夸克 / UC / 迅雷 / 百度 / 139 / 123 / 115 / 光鸭 / 蓝奏云 / 蓝奏云优享版 分享直链识别",
        "视频链接解析" to "哔哩哔哩 / 小红书 / 抖音 / 微博 / 快手 / AcFun / Pixiv；短链、口令、整段文案均可",
        "高速分片下载" to "多线程并发 + 断点续传 + 慢连接自动换线；B 站高清分轨两轨并行后自动合轨",
        "P2P 下载" to "磁力 / BT 种子 / ed2k 电驴链接交给 Gopeed 引擎下载；支持本地种子文件、Tracker 服务器管理与 DHT 开关",
        "在线预览" to "ffplay 直读直链、真·边下边播（HLS 原生支持）；没装 FFmpeg 时回退内置播放器并可一键下载",
        "Live 图" to "微博 / 抖音动图可存为动态照片、静态图片或视频",
        "多账号与备份" to "网盘与视频平台均支持多账号（主账号 / 备注），认证信息可加密导出 / 导入",
        "引擎管理" to "FFmpeg（预览 / 合轨）与 Gopeed 内核集中下载·更新·卸载，装到应用私有目录，不改系统环境变量",
        "凭证本地化" to "Cookie / Token 加密落库，仅存本机"
    )
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardIcon(Icons.Outlined.CheckCircle)
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = "功能特性",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        features.forEach { (title, desc) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

/** 技术栈卡片 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TechCard() {
    val techs = listOf("Kotlin", "Compose Multiplatform", "Material 3", "SQLite", "OkHttp", "FFmpeg", "JavaFX Media", "JCEF")
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardIcon(Icons.Outlined.Code)
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = "技术栈",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            techs.forEach { name ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    Text(
                        text = name,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 免责声明卡片 */
@Composable
private fun DisclaimerCard() {
    SectionCard {
        Row(verticalAlignment = Alignment.Top) {
            CardIcon(Icons.Outlined.Shield)
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = "免责声明",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "本应用仅供个人学习与技术交流使用，请勿用于任何商业用途。" +
                        "下载内容版权归原作者所有，请于下载后 24 小时内删除。" +
                        "使用本应用产生的任何后果由使用者自行承担。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 22.sp
                )
            }
        }
    }
}

/** 重新预览欢迎界面入口 */
@Composable
private fun PreviewOnboardingCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "重新预览欢迎界面",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "重新展示首次启动引导页",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}

/** 卡片容器统一风格 */
@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}

/** 卡片图标圆形底 */
@Composable
private fun CardIcon(icon: ImageVector) {
    Surface(
        modifier = Modifier.size(40.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

/** 安卓版仓库入口卡片 */
@Composable
private fun GitHubCard() {
    Card(
        onClick = {
            DesktopActions.openUrl("https://github.com/CYQawa/YunX")
        },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
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
                    text = "安卓版仓库",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "github.com/CYQawa/YunX",
                    style = MaterialTheme.typography.bodyMedium,
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

/** PC 版仓库入口卡片 */
@Composable
private fun DesktopRepoCard() {
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.tertiaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.DesktopWindows,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "PC 版仓库",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "github.com/tidain/YunX-Desktop",
                    style = MaterialTheme.typography.bodyMedium,
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

/** QQ 交流群入口：点击复制群号（桌面端无 mqqapi scheme 保证，复制后在 QQ 中搜索加群） */
@Composable
private fun QQGroupCard() {
    Card(
        onClick = {
            DesktopActions.copyToClipboard(QQ_GROUP)
            SnackbarController.show("QQ 群号已复制：$QQ_GROUP")
        },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Forum,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "QQ 交流群",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "群号：$QQ_GROUP（点击复制）",
                    style = MaterialTheme.typography.bodyMedium,
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