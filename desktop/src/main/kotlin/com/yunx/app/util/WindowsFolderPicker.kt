package com.yunx.app.util

import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Guid.GUID
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.PointerByReference
import java.io.File

/**
 * Windows Vista+ 原生「选择文件夹」对话框（资源管理器同款样式）。
 *
 * 实现：COM IFileOpenDialog + FOS_PICKFOLDERS 标志，JNA 裸调 vtable，
 * 无需 --add-opens 或 AWT peer 反射。失败时调用方可回退 JFileChooser。
 *
 * 必须在 UI 线程（EDT）调用：对话框自泵模态消息循环。
 */
object WindowsFolderPicker {

    sealed interface Result {
        /** 用户选择了目录 */
        data class Success(val path: String) : Result

        /** 用户取消了对话框（或对话框弹出后失败） */
        data object Cancelled : Result

        /** 系统不支持 / COM 创建失败，应回退旧式选择器 */
        data object Unavailable : Result
    }

    // 微软文档常量：CLSID_FileOpenDialog / IID_IFileOpenDialog / IID_IShellItem
    private const val CLSID_FILE_OPEN_DIALOG = "{DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7}"
    private const val IID_I_FILE_OPEN_DIALOG = "{D57C7288-D4AD-4768-BE02-9D969532D960}"
    private const val IID_I_SHELL_ITEM = "{43826D1E-E718-42EE-BC55-A1E261C37BFE}"

    private const val FOS_PICKFOLDERS = 0x20
    private const val FOS_FORCEFILESYSTEM = 0x40

    /** SIGDN_FILESYSPATH：要求返回文件系统绝对路径 */
    private const val SIGDN_FILESYSPATH = 0x80058000.toInt()

    private const val CLSCTX_ALL = 0x17
    private const val ERROR_CANCELLED = 0x800704C7.toInt()

    // IFileOpenDialog vtable 索引（0-2 为 IUnknown：QueryInterface/AddRef/Release）
    private const val VTB_SHOW = 3
    private const val VTB_SET_OPTIONS = 9
    private const val VTB_GET_OPTIONS = 10
    private const val VTB_SET_FOLDER = 12
    private const val VTB_SET_TITLE = 17
    private const val VTB_GET_RESULT = 20

    // IShellItem vtable：5 = GetDisplayName
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
     * 打开原生文件夹选择对话框。
     * @param initialDir 初始定位目录（null 用系统记忆位置）
     * @param title 对话框标题
     */
    fun pick(initialDir: File?, title: String): Result {
        if (!WindowsTitleBar.isWindows) return Result.Unavailable
        return runCatching {
            val hr = Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED).toInt()
            // S_OK(0)/S_FALSE(1) 时本线程持有 COM 引用，退出时配对释放；
            // RPC_E_CHANGED_MODE 等失败不释放，继续尝试（AWT EDT 通常已是 STA）
            val ownsComInit = hr == 0 || hr == 1
            try {
                pickInternal(initialDir, title)
            } finally {
                if (ownsComInit) Ole32.INSTANCE.CoUninitialize()
            }
        }.getOrElse { Result.Unavailable }
    }

    private fun pickInternal(initialDir: File?, title: String): Result {
        val pDialog = PointerByReference()
        val createHr = Ole32.INSTANCE.CoCreateInstance(
            GUID(CLSID_FILE_OPEN_DIALOG), null, CLSCTX_ALL,
            GUID(IID_I_FILE_OPEN_DIALOG), pDialog
        ).toInt()
        if (createHr < 0) return Result.Unavailable
        val dialog = pDialog.value ?: return Result.Unavailable

        try {
            // 选项：目录模式 + 仅文件系统路径
            val optsRef = WinDef.DWORDByReference()
            invokeVtbl(dialog, VTB_GET_OPTIONS, optsRef)
            val options = (optsRef.value?.toInt() ?: 0) or FOS_PICKFOLDERS or FOS_FORCEFILESYSTEM
            invokeVtbl(dialog, VTB_SET_OPTIONS, WinDef.DWORD(options.toLong()))

            if (title.isNotBlank()) {
                runCatching { invokeVtbl(dialog, VTB_SET_TITLE, WString(title)) }
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
