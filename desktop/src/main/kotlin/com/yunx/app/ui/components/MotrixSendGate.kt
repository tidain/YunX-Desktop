package com.yunx.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.yunx.app.data.prefs.SettingsRepository

/**
 * 「发送到 Motrix」前的目录确认闸门。
 *
 * 设置里打开「发送前询问保存目录」（[SettingsRepository.motrixAskSaveDir]）时，
 * 先弹系统目录选择器，把选中的路径作为 aria2 的 `dir` 交给 Motrix；
 * 关掉时直接以空串下发，文件落到 Motrix 自己的默认下载目录（原行为）。
 *
 * 为什么目录要在本程序选：Motrix 的「新建任务」确认框没有自定义请求头字段，
 * 让 Motrix 自己弹窗就意味着丢掉 Cookie / Referer / UA，网盘直链必然 412/403。
 * 这里只是借用系统选择器，投递仍然走 RPC，请求头一个不落。
 *
 * 用法：
 * ```
 * val motrixSend = rememberMotrixSend()
 * ...
 * MultiSelectAction("发送到 Motrix", ...) { motrixSend { dir -> viewModel.sendSelectedToMotrix(dir) } }
 * ```
 * 回调参数是本次要用的保存目录：空串 = 跟随 Motrix 默认目录。
 */
@Composable
fun rememberMotrixSend(): (onSend: (String) -> Unit) -> Unit {
    val pickDownloadDir = rememberDownloadDirPicker()
    val settings = remember { SettingsRepository() }
    return remember(pickDownloadDir, settings) {
        { onSend ->
            if (settings.motrixAskSaveDir) {
                pickDownloadDir { dir -> onSend(dir) }
            } else {
                onSend("")
            }
        }
    }
}
