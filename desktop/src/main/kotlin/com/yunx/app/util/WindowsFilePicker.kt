package com.yunx.app.util

import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Guid.GUID
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.PointerByReference
import java.io.File

/**
 * Windows Vista+ 原生「打开文件 / 另存为」对话框（资源管理器同款样式）。
 *
 * 实现：COM IFileOpenDialog / IFileSaveDialog（共用 IFileDialog vtable 段），
 * JNA 裸调 vtable，无需 --add-opens 或 AWT peer 反射（同 WindowsFolderPicker）。
 * 失败时调用方可回退 AWT FileDialog。
 *
 * 必须在 UI 线程（EDT）调用：对话框自泵模态消息循环。
 */
object WindowsFilePicker {

    sealed interface Result {
        /** 用户确认了文件路径 */
        data class Success(val path: String) : Result

        /** 用户取消了对话框 */
        data object Cancelled : Result

        /** 系统不支持 / COM 创建失败，应回退旧式对话框 */
        data object Unavailable : Result
    }

    // 微软文档常量：CLSID_FileOpenDialog / CLSID_FileSaveDialog / 对应 IID / IID_IShellItem
    private const val CLSID_FILE_OPEN_DIALOG = "{DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7}"
    private const val CLSID_FILE_SAVE_DIALOG = "{C0B4E2F3-BA21-4773-8DBA-335EC946EB8B}"
    private const val IID_I_FILE_OPEN_DIALOG = "{D57C7288-D4AD-4768-BE02-9D969532D960}"
    private const val IID_I_FILE_SAVE_DIALOG = "{84BCCD23-5FDE-4CDB-AEA4-AF64B83D78AB}"
    private const val IID_I_SHELL_ITEM = "{43826D1E-E718-42EE-BC55-A1E261C37BFE}"

    // FILEOPENDIALOGOPTIONS 标志
    private const val FOS_OVERWRITEPROMPT = 0x2
    private const val FOS_FORCEFILESYSTEM = 0x40
    private const val FOS_PATHMUSTEXIST = 0x800
    private const val FOS_FILEMUSTEXIST = 0x1000

    /** SIGDN_FILESYSPATH：要求返回文件系统绝对路径 */
    private const val SIGDN_FILESYSPATH = 0x80058000.toInt()

    private const val CLSCTX_ALL = 0x17

    // IFileDialog vtable 索引（0-2 为 IUnknown：QueryInterface/AddRef/Release；
    // IFileOpenDialog / IFileSaveDialog 均直接继承 IFileDialog，此段一致）
    private const val VTB_SHOW = 3
    private const val VTB_SET_FILE_TYPES = 4
    private const val VTB_SET_OPTIONS = 9
    private const val VTB_GET_OPTIONS = 10
    private const val VTB_SET_FOLDER = 12
    private const val VTB_SET_FILE_NAME = 15
    private const val VTB_SET_TITLE = 16
    private const val VTB_GET_RESULT = 19
    private const val VTB_SET_DEFAULT_EXTENSION = 20

    // IShellItem vtable：5 = GetDisplayName，2 = Release
    private const val VTB_ITEM_GET_DISPLAY_NAME = 5
    private const val VTB_RELEASE = 2

    /** jna-platform 未封装 SHCreateItemFromParsingName，自行声明 */
    private interface Shell32Ex : Library {
        fun SHCreateItemFromParsingName(pszPath: WString, pbc: Pointer?, riid: GUID, ppv: PointerByReference): Int
    }

    private val shell32: Shell32Ex? by lazy {
        runCatching { Native.load("shell32", Shell32Ex::class.java) }.getOrNull()
    }

    /**
     * 打开原生「选择文件」对话框。
     * @param initialDir 初始定位目录（null 用系统记忆位置）
     * @param title 对话框标题
     * @param filters 文件类型过滤器（显示名 to 通配符），空则不过滤
     */
    fun pickFile(
        initialDir: File?,
        title: String,
        filters: List<Pair<String, String>> = emptyList()
    ): Result = showDialog(
        clsid = CLSID_FILE_OPEN_DIALOG,
        iid = IID_I_FILE_OPEN_DIALOG,
        title = title,
        initialDir = initialDir,
        filters = filters,
        defaultName = null,
        defaultExtension = null,
        extraOptions = FOS_FILEMUSTEXIST or FOS_PATHMUSTEXIST
    )

    /**
     * 打开原生「另存为」对话框。
     * @param initialDir 初始定位目录（null 用系统记忆位置）
     * @param defaultName 预填文件名
     * @param title 对话框标题
     * @param filters 文件类型过滤器（显示名 to 通配符），空则不过滤
     * @param defaultExtension 用户省略扩展名时自动补全（null 不补）
     */
    fun saveFile(
        initialDir: File?,
        defaultName: String,
        title: String,
        filters: List<Pair<String, String>> = emptyList(),
        defaultExtension: String? = null
    ): Result = showDialog(
        clsid = CLSID_FILE_SAVE_DIALOG,
        iid = IID_I_FILE_SAVE_DIALOG,
        title = title,
        initialDir = initialDir,
        filters = filters,
        defaultName = defaultName.takeIf { it.isNotBlank() },
        defaultExtension = defaultExtension?.takeIf { it.isNotBlank() },
        extraOptions = FOS_OVERWRITEPROMPT
    )

    private fun showDialog(
        clsid: String,
        iid: String,
        title: String,
        initialDir: File?,
        filters: List<Pair<String, String>>,
        defaultName: String?,
        defaultExtension: String?,
        extraOptions: Int
    ): Result = runCatching {
        val hr = Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED).toInt()
        // S_OK(0)/S_FALSE(1) 时本线程持有 COM 引用，退出时配对释放；
        // RPC_E_CHANGED_MODE 等失败不释放，继续尝试（AWT EDT 通常已是 STA）
        val ownsComInit = hr == 0 || hr == 1
        try {
            showDialogInternal(clsid, iid, title, initialDir, filters, defaultName, defaultExtension, extraOptions)
        } finally {
            if (ownsComInit) Ole32.INSTANCE.CoUninitialize()
        }
    }.getOrElse { Result.Unavailable }

    private fun showDialogInternal(
        clsid: String,
        iid: String,
        title: String,
        initialDir: File?,
        filters: List<Pair<String, String>>,
        defaultName: String?,
        defaultExtension: String?,
        extraOptions: Int
    ): Result {
        val pDialog = PointerByReference()
        val createHr = Ole32.INSTANCE.CoCreateInstance(
            GUID(clsid), null, CLSCTX_ALL, GUID(iid), pDialog
        ).toInt()
        if (createHr < 0) return Result.Unavailable
        val dialog = pDialog.value ?: return Result.Unavailable

        // 过滤器数组与字符串内存在 Show 返回前必须存活，声明在方法帧内防 GC 回收
        val filterSpecs = buildFilterSpecs(filters)

        try {
            // 选项：仅文件系统路径 + 调用方标志（打开=必须存在；保存=覆盖确认）
            val optsRef = WinDef.DWORDByReference()
            invokeVtbl(dialog, VTB_GET_OPTIONS, optsRef)
            val options = (optsRef.value?.toInt() ?: 0) or FOS_FORCEFILESYSTEM or extraOptions
            invokeVtbl(dialog, VTB_SET_OPTIONS, WinDef.DWORD(options.toLong()))

            if (title.isNotBlank()) {
                runCatching { invokeVtbl(dialog, VTB_SET_TITLE, WString(title)) }
            }

            // 文件类型过滤器（SetFileTypes(UINT cFileTypes, const COMDLG_FILTERSPEC*)）
            if (filterSpecs != null) {
                runCatching {
                    invokeVtbl(dialog, VTB_SET_FILE_TYPES, WinDef.UINT(filters.size.toLong()), filterSpecs.first)
                }
            }

            // 初始定位目录（失败不影响主流程）
            initialDir?.takeIf { it.isDirectory }?.let { dir ->
                runCatching {
                    val shell = shell32 ?: return@runCatching
                    val pItem = PointerByReference()
                    if (shell.SHCreateItemFromParsingName(
                            WString(dir.absolutePath), null, GUID(IID_I_SHELL_ITEM), pItem
                        ) == 0 && pItem.value != null
                    ) {
                        invokeVtbl(dialog, VTB_SET_FOLDER, pItem.value)
                        release(pItem.value)
                    }
                }
            }

            // 预填文件名 / 默认扩展名（保存模式；失败不影响主流程）
            defaultName?.let { runCatching { invokeVtbl(dialog, VTB_SET_FILE_NAME, WString(it)) } }
            defaultExtension?.let {
                runCatching { invokeVtbl(dialog, VTB_SET_DEFAULT_EXTENSION, WString(it)) }
            }

            // 模态阻塞：弹出对话框
            val showHr = invokeVtbl(dialog, VTB_SHOW, null as WinDef.HWND?)
            if (showHr < 0) return Result.Cancelled

            val itemRef = PointerByReference()
            if (invokeVtbl(dialog, VTB_GET_RESULT, itemRef) < 0) return Result.Cancelled
            val item = itemRef.value ?: return Result.Cancelled

            try {
                val nameRef = PointerByReference()
                if (invokeVtbl(
                        item, VTB_ITEM_GET_DISPLAY_NAME, WinDef.DWORD(SIGDN_FILESYSPATH.toLong()), nameRef
                    ) < 0
                ) {
                    return Result.Cancelled
                }
                val namePtr = nameRef.value ?: return Result.Cancelled
                val path = namePtr.getWideString(0)
                Ole32.INSTANCE.CoTaskMemFree(namePtr)
                return if (path.isBlank()) Result.Cancelled else Result.Success(path)
            } finally {
                release(item)
            }
        } finally {
            release(dialog)
        }
    }

    /** 构造 COMDLG_FILTERSPEC 数组原生内存；返回 数组 + 全部字符串块（调用帧持有防 GC） */
    private fun buildFilterSpecs(filters: List<Pair<String, String>>): Pair<Memory, List<Memory>>? {
        if (filters.isEmpty()) return null
        val ptrSize = Native.POINTER_SIZE.toLong()
        val strings = mutableListOf<Memory>()
        val array = Memory(filters.size * 2L * ptrSize)
        filters.forEachIndexed { i, (name, spec) ->
            val nameMem = wideStringMemory(name)
            val specMem = wideStringMemory(spec)
            strings += nameMem
            strings += specMem
            array.setPointer(i * 2L * ptrSize, nameMem)
            array.setPointer(i * 2L * ptrSize + ptrSize, specMem)
        }
        return array to strings
    }

    /** 分配一块以 NUL 结尾的宽字符字符串原生内存 */
    private fun wideStringMemory(text: String): Memory =
        Memory((text.length + 1) * 2L).apply { setWideString(0, text) }

    /** 调用 COM 对象 vtable 第 index 个方法（index 0 = QueryInterface），返回 HRESULT */
    private fun invokeVtbl(target: Pointer, index: Int, vararg args: Any?): Int {
        val vtbl = target.getPointer(0)
        val fn = Function.getFunction(
            vtbl.getPointer(index.toLong() * Native.POINTER_SIZE),
            Function.ALT_CONVENTION
        )
        return fn.invokeInt(arrayOf<Any?>(target, *args))
    }

    private fun release(comPtr: Pointer) {
        runCatching { invokeVtbl(comPtr, VTB_RELEASE) }
    }
}
