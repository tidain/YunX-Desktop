package com.yunx.app.data.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 加密等级切换的**核心不变量**：切档只重新包裹 32 字节主密钥，密钥本身不变 ——
 * 库里那些用主密钥加密的凭证因此始终可解，切档不需要重加密、也不会丢数据。
 *
 * 只测不依赖 Windows 的两档（宽松 / 普通）；DPAPI 档的往返已用真实运行验证过。
 */
class DbKeyStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `宽松写入的是裸密钥_普通档写入的是包裹格式`() {
        val file = File(tmp.root, "credential.key")
        val key = DbKeyStore.loadOrCreate(file, DbEncryptionLevel.LOOSE)
        assertEquals(32, key.size)
        assertFalse("宽松档不应包裹密钥", DbKeyStore.isWrapped(file))

        DbKeyStore.switchLevel(file, DbEncryptionLevel.LOOSE, DbEncryptionLevel.NORMAL)
        assertTrue("普通档应包裹密钥", DbKeyStore.isWrapped(file))
        val text = file.readText()
        assertTrue("普通档应带本机派生密钥标识", text.contains(":mk:v1:"))
    }

    @Test
    fun `切档后主密钥不变（库内密文不会失效）`() {
        val file = File(tmp.root, "credential.key")
        val original = DbKeyStore.loadOrCreate(file, DbEncryptionLevel.LOOSE)

        DbKeyStore.switchLevel(file, DbEncryptionLevel.LOOSE, DbEncryptionLevel.NORMAL)
        assertArrayEquals(original, DbKeyStore.loadOrCreate(file, DbEncryptionLevel.NORMAL))

        DbKeyStore.switchLevel(file, DbEncryptionLevel.NORMAL, DbEncryptionLevel.LOOSE)
        assertFalse(DbKeyStore.isWrapped(file))
        assertArrayEquals(original, DbKeyStore.loadOrCreate(file, DbEncryptionLevel.LOOSE))
    }

    @Test
    fun `默认档位是普通_严格档暂未支持`() {
        assertEquals(DbEncryptionLevel.NORMAL, DbEncryptionLevel.fromId(null))
        assertEquals(DbEncryptionLevel.NORMAL, DbEncryptionLevel.fromId("不存在的档位"))
        assertFalse(DbEncryptionLevel.STRICTEST.isSupported)
        assertTrue(DbEncryptionLevel.NORMAL.isMachineBound)
        assertTrue(DbEncryptionLevel.STRICT.isMachineBound)
        assertEquals(System.getProperty("os.name").lowercase().contains("win"), DbEncryptionLevel.STRICT.isSupported)
        assertFalse("宽松档必须能跨机器使用，不能被判为绑定本机", DbEncryptionLevel.LOOSE.isMachineBound)
    }
}
