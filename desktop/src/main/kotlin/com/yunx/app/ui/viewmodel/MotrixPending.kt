package com.yunx.app.ui.viewmodel

import com.yunx.app.data.motrix.MotrixClient
import com.yunx.app.data.motrix.MotrixSender

/**
 * 把一条待确认下载（直链 + 已算好的 Cookie/Referer/UA）发送到 Motrix。
 *
 * 各网盘页的下载弹窗共享这段逻辑：它们没有共同基类，所以用扩展函数避免
 * 十个 ViewModel 各写一份。
 *
 * @return 是否成功 + 给用户看的提示文案
 */
internal class MotrixExchange(val success: Boolean, val message: String)

/**
 * 发送当前待确认下载到 Motrix（不接管进度、不在本程序入队）。
 *
 * 失败不抛异常：网络/密钥/未安装等原因都折成一句提示，交给 cloudMessage 显示。
 *
 * @param saveDirOverride 本次指定的保存目录；空串 = 沿用 [PendingDownload.saveDir]
 *   （即「下载到…」时选的那个目录，再空则跟随 Motrix 默认目录）。
 *   用户在设置里开了「发送前询问保存目录」时，这里传的就是刚选中的路径。
 */
internal suspend fun PendingDownload.sendToMotrix(saveDirOverride: String = ""): MotrixExchange {
    val dir = saveDirOverride.ifBlank { saveDir }
    val result = runCatching {
        MotrixSender.send(url = url, fileName = fileName, headers = headers, saveDir = dir)
    }.getOrElse { e ->
        return MotrixExchange(
            success = false,
            message = "发送到 Motrix 失败：${e.message ?: e.javaClass.simpleName}"
        )
    }
    return MotrixExchange(
        success = result is MotrixClient.Result.Success,
        message = MotrixSender.describe(result, fileName)
    )
}
