package com.yunx.app.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.ui.BackHandler
import com.yunx.app.ui.GlobalSnackbarHost
import com.yunx.app.ui.SnackbarController

/**
 * 「Tracker 服务器」二级页：逐条管理 BT tracker 列表（从「引擎管理 → Gopeed 引擎」进入）。
 *
 * 相比原来「一个大文本框、每行一条」的编辑方式，这里改成**一条一行**的增删：
 * - 顶部输入框 + 「添加」：校验协议前缀（udp:// / http(s):// / ws(s)://）并去重；
 * - 每条右侧「删除」：单条移除；
 * - 底部「恢复内置默认列表」：一键填回随包内置的公共 tracker（trackerslist 常用集合）。
 *
 * 任何增删都会立刻落库（[SettingsRepository.btTrackers]）；当列表恰好等于内置默认列表时写空串，
 * 表示「跟随内置默认」（这样内置列表将来更新时用户能自动用上）。修改只对**新任务**生效，
 * 已交给引擎的旧任务沿用创建时下发的 tracker。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackerListScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }
    val settingsRepo = remember { SettingsRepository() }
    // 工作副本：以「当前生效列表」为起点——没自定义时就是内置默认列表，用户可直接在此基础上改。
    val trackers = remember {
        mutableStateListOf<String>().apply { addAll(settingsRepo.resolvedBtTrackers()) }
    }
    var draft by remember { mutableStateOf("") }

    /** 把工作副本落库：与内置默认列表完全一致时写空串（跟随默认），否则逐行写入 */
    fun persist() {
        val current = trackers.toList()
        settingsRepo.btTrackers =
            if (current == SettingsRepository.DEFAULT_BT_TRACKERS) "" else current.joinToString("\n")
    }

    /** tracker 协议前缀校验：只做前缀判断，具体连通性交给引擎 */
    fun isTrackerUrl(value: String): Boolean {
        val v = value.trim().lowercase()
        return v.startsWith("udp://") || v.startsWith("http://") ||
            v.startsWith("https://") || v.startsWith("ws://") || v.startsWith("wss://")
    }

    fun addTracker() {
        val value = draft.trim()
        when {
            value.isEmpty() -> SnackbarController.show("请输入 tracker 地址")
            !isTrackerUrl(value) ->
                SnackbarController.show("地址需以 udp:// / http(s):// / ws(s):// 开头")
            trackers.any { it.equals(value, ignoreCase = true) } -> SnackbarController.show("该 tracker 已存在")
            else -> {
                trackers.add(value)
                draft = ""
                persist()
                SnackbarController.show("已添加（对新任务生效）")
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { GlobalSnackbarHost() },
        topBar = {
            TopAppBar(
                title = { Text("Tracker 服务器", style = MaterialTheme.typography.titleLarge) },
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
        ) {
            // 顶部说明 + 新增输入区
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "磁力 / ed2k / BT 种子靠 tracker 与 DHT 找到 peer：tracker 越全越容易连上。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            placeholder = { Text("udp://tracker.opentrackr.org:1337/announce") },
                            textStyle = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { addTracker() }) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("添加")
                        }
                    }
                }
            }

            // 列表标题
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "当前列表（${trackers.size} 条）",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(6.dp))

            if (trackers.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        text = "列表为空，新任务将没有 tracker 可用（建议恢复内置默认列表或手动添加）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(trackers) { index, url ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = url,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = {
                                        trackers.removeAt(index)
                                        persist()
                                        SnackbarController.show("已删除（对新任务生效）")
                                    }
                                ) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = "删除",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 底部：恢复内置默认列表
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                OutlinedButton(
                    onClick = {
                        trackers.clear()
                        trackers.addAll(SettingsRepository.DEFAULT_BT_TRACKERS)
                        persist()
                        SnackbarController.show("已恢复内置默认 tracker 列表")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("恢复内置默认列表")
                }
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("完成")
                }
            }
        }
    }
}
