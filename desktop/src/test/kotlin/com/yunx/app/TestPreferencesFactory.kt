package com.yunx.app

import java.util.concurrent.ConcurrentHashMap
import java.util.prefs.AbstractPreferences
import java.util.prefs.Preferences
import java.util.prefs.PreferencesFactory

/** Windows Preferences 默认写注册表；测试改用内存，避免读取或修改用户配置。 */
class TestPreferencesFactory : PreferencesFactory {
    private val user = MemoryPreferences(null, "")
    private val system = MemoryPreferences(null, "")
    override fun userRoot(): Preferences = user
    override fun systemRoot(): Preferences = system
}

private class MemoryPreferences(parent: AbstractPreferences?, name: String) : AbstractPreferences(parent, name) {
    private val values = ConcurrentHashMap<String, String>()
    private val children = ConcurrentHashMap<String, MemoryPreferences>()
    override fun putSpi(key: String, value: String) { values[key] = value }
    override fun getSpi(key: String): String? = values[key]
    override fun removeSpi(key: String) { values.remove(key) }
    override fun keysSpi(): Array<String> = values.keys.toTypedArray()
    override fun childrenNamesSpi(): Array<String> = children.keys.toTypedArray()
    override fun childSpi(name: String): AbstractPreferences = children.getOrPut(name) { MemoryPreferences(this, name) }
    override fun removeNodeSpi() {
        values.clear()
        (parent() as? MemoryPreferences)?.children?.remove(name())
    }
    override fun syncSpi() {}
    override fun flushSpi() {}
}
