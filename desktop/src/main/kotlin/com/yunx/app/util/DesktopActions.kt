package com.yunx.app.util

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.KnownFolders
import com.sun.jna.platform.win32.Shell32
import com.sun.jna.platform.win32.Shell32Util
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.platform.win32.WinUser
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI
import javax.swing.JFileChooser
import javax.swing.UIManager
import javax.swing.filechooser.FileSystemView

/**
 * 桌面端系统能力统一入口：剪贴板 / 打开文件与链接 / 目录选择器。
 * 替代 Android 的 ClipboardManager、Intent.ACTION_VIEW、SAF 选择器。
 */
object DesktopActions {

    /** FOLDERID_Downloads 的 GUID（注册表兜底读取时使用） */
    private const val DOWNLOADS_FOLDER_GUID = "{374DE290-123F-4565-9164-39C4925E467B}"

    /** 复制文本到系统剪贴板 */
    fun copyToClipboard(text: String) {
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard
                .setContents(StringSelection(text), null)
        }
    }

    /** 读取系统剪贴板文本（无文本内容时返回 null） */
    fun readClipboard(): String? = runCatching {
        Toolkit.getDefaultToolkit().systemClipboard
            .getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
    }.getOrNull()

    /** 用系统默认应用打开文件（下载完成后的「打开」） */
    fun openFile(path: String): Boolean {
        val file = File(path)
        if (!file.exists()) return false
        return runCatching {
            java.awt.Desktop.getDesktop().open(file)
            true
        }.getOrDefault(false)
    }

    /**
     * 在资源管理器中显示文件（选中）。
     *
     * Windows 走 ShellExecute，把 `/select,"<绝对路径>"` 作为**原始参数串**交给系统；
     * 不能用 ProcessBuilder —— 它按自己的规则给参数加引号/转义，两种写法都会失效：
     *   1. `/select,` 与路径拆成两个参数：路径里的**半角逗号**会被 Explorer 当成参数分隔符，
     *      截断后的路径不存在，Explorer 就落到「主文件夹」等默认位置（本次修的 bug）；
     *   2. 合成一个参数并自带引号：Java 又把引号转义成 `\"`，Explorer 解析不出路径，同样落回默认位置。
     * ShellExecute 不做任何转义，逗号/空格/中文路径都能正确定位。
     */
    fun revealFile(path: String): Boolean {
        val file = File(path)
        if (!file.exists()) return false
        if (WindowsTitleBar.isWindows) {
            // 返回 HINSTANCE：> 32 表示成功（<= 32 是错误码），失败再退回命令行方式
            val ret = runCatching {
                Shell32.INSTANCE.ShellExecute(
                    null, "open", "explorer.exe",
                    "/select,\"${file.absolutePath}\"", null, WinUser.SW_SHOWNORMAL
                )
            }.getOrNull()
            if (ret != null && com.sun.jna.Pointer.nativeValue(ret.toPointer()) > 32L) return true
        }
        return runCatching {
            ProcessBuilder("explorer.exe", "/select,", file.absolutePath).start()
            true
        }.getOrDefault(false)
    }

    /** 在资源管理器中打开目录（不存在时先创建） */
    fun openDirectory(path: String): Boolean {
        val dir = File(path)
        if (!dir.isDirectory && !dir.mkdirs()) return false
        return runCatching {
            ProcessBuilder("explorer.exe", dir.absolutePath).start()
            true
        }.getOrDefault(false)
    }

    /**
     * 把主窗口拉到前台（托盘「显示主窗口」、剪贴板弹窗点「打开」共用）。
     *
     * ★ Windows 上必须做两件事，少一件就会出现「点了打开但窗口没到前台」：
     *   1. **先还原**：窗口可能已**最小化到任务栏**（extendedState 含 ICONIFIED 位），
     *      此时 toFront() 对它是无效的 —— 必须先把状态改回 NORMAL；
     *   2. **再置顶置前**：toFront() 单独调用不一定能夺取前台焦点，用 alwaysOnTop 瞬时切换强制置顶。
     */
    fun bringWindowToFront(window: Frame?) {
        val w = window ?: return
        runCatching {
            w.isVisible = true
            if (w.extendedState and Frame.ICONIFIED != 0) {
                w.extendedState = Frame.NORMAL
            }
            val wasOnTop = w.isAlwaysOnTop
            w.isAlwaysOnTop = true
            w.toFront()
            w.requestFocus()
            w.isAlwaysOnTop = wasOnTop
        }
    }

    /**
     * 重启应用：延迟 2 秒后拉起新的实例（给本进程留出退出的时间，避免两个实例同时抢数据库/缓存），
     * 返回是否成功发起。调用方应在返回 true 后立即退出本进程。
     *
     * 拿不到启动器 exe（开发运行 `gradle run`）时返回 false，由调用方提示手动重启。
     */
    fun restartApp(): Boolean {
        val exe = launcherExe() ?: return false
        return runCatching {
            // 用 cmd 延迟启动：本进程马上就会退出，必须等它把文件句柄都放掉。
            // 延迟用 ping 而不是 timeout —— timeout 在 stdin 被重定向时会直接报错退出（ProcessBuilder 默认就是管道）
            ProcessBuilder(
                "cmd.exe", "/c",
                "ping -n 3 127.0.0.1 >nul & start \"\" \"${exe.absolutePath}\""
            ).directory(exe.parentFile).start()
            true
        }.getOrDefault(false)
    }

    /** 便携版/安装版的启动器 exe：优先 jpackage 注入的路径，否则取工作目录下的 YunX-Desktop.exe */
    private fun launcherExe(): File? {
        System.getProperty("jpackage.app-path")?.let { p ->
            File(p).takeIf { it.isFile }?.let { return it }
        }
        return File(File(System.getProperty("user.dir") ?: "."), "YunX-Desktop.exe").takeIf { it.isFile }
    }

    /** 用系统默认浏览器打开链接 */
    fun openUrl(url: String) {
        runCatching {
            java.awt.Desktop.getDesktop().browse(URI(url))
        }
    }

    /**
     * 弹出目录选择对话框。
     *
     * Windows：使用原生 COM IFileOpenDialog（FOS_PICKFOLDERS），
     *   即资源管理器同款「选择文件夹」对话框（WindowsFolderPicker），
     *   COM 不可用时回退 Swing JFileChooser。
     * Linux：使用 Swing JFileChooser（DIRECTORIES_ONLY 模式）。
     * macOS：继续使用 AWT FileDialog（原生体验更好）。
     *
     * @return 选中目录绝对路径；取消返回 null
     */
    fun pickDirectory(): String? {
        val osName = System.getProperty("os.name", "").lowercase()
        val isMac = osName.contains("mac") || osName.contains("darwin")

        if (isMac) {
            val dialog = FileDialog(null as Frame?, "选择文件夹", FileDialog.LOAD)
            dialog.isMultipleMode = false
            System.setProperty("apple.awt.fileDialogForDirectories", "true")
            dialog.isVisible = true
            val dir = dialog.directory
            val file = dialog.file
            dialog.dispose()
            return when {
                dir == null -> null
                file == null -> dir
                else -> File(dir, file).absolutePath
            }
        }

        if (WindowsTitleBar.isWindows) {
            return when (val r = WindowsFolderPicker.pick(defaultDownloadDir.takeIf { it.isDirectory }, "选择文件夹")) {
                is WindowsFolderPicker.Result.Success -> r.path
                WindowsFolderPicker.Result.Cancelled -> null
                WindowsFolderPicker.Result.Unavailable -> swingDirectoryChooser()
            }
        }

        return swingDirectoryChooser()
    }

    /** 旧式 Swing 目录选择器（回退用） */
    private fun swingDirectoryChooser(): String? = runCatching {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) } catch (_: Exception) {}
        val chooser = JFileChooser(FileSystemView.getFileSystemView())
        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        chooser.dialogTitle = "选择文件夹"
        chooser.isAcceptAllFileFilterUsed = false
        chooser.approveButtonText = "选择文件夹"
        chooser.currentDirectory = defaultDownloadDir
        val result = chooser.showOpenDialog(null)
        if (result == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile?.absolutePath
        } else {
            null
        }
    }.getOrNull()

    /**
     * 弹出文件选择对话框（导入备份等）。
     *
     * Windows：使用原生 COM IFileOpenDialog（资源管理器同款样式，WindowsFilePicker），
     *   初始定位到下载目录，COM 不可用时回退 AWT FileDialog。
     * macOS / Linux：AWT FileDialog。
     *
     * @param filters 文件类型过滤器（显示名 to 通配符），空则不过滤
     * @return 选中文件绝对路径；取消返回 null
     */
    fun pickFile(filters: List<Pair<String, String>> = emptyList()): String? {
        val osName = System.getProperty("os.name", "").lowercase()
        val isMac = osName.contains("mac") || osName.contains("darwin")

        if (isMac) return awtPickFile("选择文件")

        if (WindowsTitleBar.isWindows) {
            return when (val r = WindowsFilePicker.pickFile(
                defaultDownloadDir.takeIf { it.isDirectory }, "选择文件", filters
            )) {
                is WindowsFilePicker.Result.Success -> r.path
                WindowsFilePicker.Result.Cancelled -> null
                WindowsFilePicker.Result.Unavailable -> awtPickFile("选择文件")
            }
        }

        return awtPickFile("选择文件")
    }

    /**
     * 弹出「另存为」对话框（导出备份等）。
     *
     * Windows：使用原生 COM IFileSaveDialog（资源管理器同款样式，WindowsFilePicker），
     *   初始定位到系统下载目录，COM 不可用时回退 AWT FileDialog(SAVE)。
     * macOS / Linux：AWT FileDialog(SAVE)。
     *
     * @param defaultName 预填文件名
     * @param filters 文件类型过滤器（显示名 to 通配符）
     * @param defaultExtension 用户省略扩展名时自动补全（null 不补）
     * @return 目标文件绝对路径；取消返回 null
     */
    fun saveFile(
        defaultName: String,
        title: String,
        filters: List<Pair<String, String>> = emptyList(),
        defaultExtension: String? = null
    ): String? {
        val osName = System.getProperty("os.name", "").lowercase()
        val isMac = osName.contains("mac") || osName.contains("darwin")

        if (isMac) return awtSaveFile(defaultName, title)

        if (WindowsTitleBar.isWindows) {
            return when (val r = WindowsFilePicker.saveFile(
                defaultDownloadDir.takeIf { it.isDirectory }, defaultName, title, filters, defaultExtension
            )) {
                is WindowsFilePicker.Result.Success -> r.path
                WindowsFilePicker.Result.Cancelled -> null
                WindowsFilePicker.Result.Unavailable -> awtSaveFile(defaultName, title)
            }
        }

        return awtSaveFile(defaultName, title)
    }

    /** AWT 旧式文件选择对话框（Windows COM 不可用 / 非 Windows 回退） */
    private fun awtPickFile(title: String): String? {
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
        dialog.isVisible = true
        val dir = dialog.directory
        val file = dialog.file
        dialog.dispose()
        return if (dir != null && file != null) File(dir, file).absolutePath else null
    }

    /** AWT 旧式另存为对话框（Windows COM 不可用 / 非 Windows 回退） */
    private fun awtSaveFile(defaultName: String, title: String): String? {
        val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE)
        dialog.file = defaultName
        dialog.directory = defaultDownloadDir.absolutePath
        dialog.isVisible = true
        val dir = dialog.directory
        val file = dialog.file
        dialog.dispose()
        return if (dir != null && file != null) File(dir, file).absolutePath else null
    }

    /**
     * 系统默认下载目录。
     *
     * 不再硬编码 <用户目录>/Downloads：Windows 用户可在资源管理器属性里把「下载」
     * 重定向到任意盘/目录，这里通过 SHGetKnownFolderPath(FOLDERID_Downloads) 取真实值，
     * 失败时逐级回退到注册表、最后才是 <用户目录>/Downloads。
     */
    val defaultDownloadDir: File by lazy { resolveDefaultDownloadDir() }

    private fun resolveDefaultDownloadDir(): File {
        val os = System.getProperty("os.name", "").lowercase()
        val home = File(System.getProperty("user.home"))
        val fallback = File(home, "Downloads")

        return when {
            // Windows：优先 Known Folder API，其次注册表，最后传统路径
            os.contains("win") -> windowsDownloadDir() ?: fallback
            // Linux：读 XDG 用户目录配置
            os.contains("linux") || os.contains("nix") -> xdgDownloadDir(home) ?: fallback
            // macOS：~/Downloads 即系统下载目录
            else -> fallback
        }
    }

    /** Windows：SHGetKnownFolderPath → 注册表 User/Shell Folders 兜底 */
    private fun windowsDownloadDir(): File? {
        // 1. 官方 Known Folder API（Vista+，最准确）
        runCatching {
            Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_Downloads)
        }.getOrNull()?.let { path ->
            if (path.isNotBlank()) return File(path)
        }
        // 2. 注册表兜底：{374DE290-...} 即 FOLDERID_Downloads
        return runCatching {
            val key = "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\"
            // Shell Folders 里的值已被系统展开，优先；User Shell Folders 需手动展开环境变量
            val expanded = Advapi32Util.registryGetStringValue(
                WinReg.HKEY_CURRENT_USER, key + "Shell Folders",
                DOWNLOADS_FOLDER_GUID
            )
            File(expandWindowsEnv(expanded))
        }.recoverCatching {
            val raw = Advapi32Util.registryGetStringValue(
                WinReg.HKEY_CURRENT_USER,
                "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\User Shell Folders",
                DOWNLOADS_FOLDER_GUID
            )
            File(expandWindowsEnv(raw))
        }.getOrNull()?.takeIf { it.absolutePath.isNotBlank() }
    }

    /** Linux：解析 ~/.config/user-dirs.dirs 的 XDG_DOWNLOAD_DIR（如 XDG_DOWNLOAD_DIR="$HOME/Downloads"） */
    private fun xdgDownloadDir(home: File): File? = runCatching {
        val config = File(home, ".config/user-dirs.dirs")
        if (!config.isFile) return@runCatching null
        val line = config.readLines().firstOrNull {
            it.trimStart().startsWith("XDG_DOWNLOAD_DIR=")
        } ?: return@runCatching null
        val raw = line.substringAfter('=').trim().trim('"', '\'')
        if (raw.isBlank()) return@runCatching null
        File(raw.replace("\$HOME", home.absolutePath).replace("^~".toRegex(), home.absolutePath))
    }.getOrNull()?.takeIf { it.absolutePath.isNotBlank() }

    /** 展开 Windows 路径里的 %ENV% 环境变量（注册表 User Shell Folders 用） */
    private fun expandWindowsEnv(path: String): String =
        Regex("%([A-Za-z_][A-Za-z0-9_]*)%").replace(path) { m ->
            System.getenv(m.groupValues[1]) ?: m.value
        }
}
