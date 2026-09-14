package dev.local.arcaea.apkmanager.data

import android.content.Context

/**
 * 包名预设的持久化存储（SharedPreferences）。
 *
 * 导出时经常需要反复填写同一批包名，这里把常用包名保存下来，导出对话框里可一键填入。
 * 同时记住「上次实际使用过的包名」，下次打开导出对话框时直接预填。
 */
class PackageNamePresets(context: Context) {

    private val prefs = context.getSharedPreferences("package-name-presets", Context.MODE_PRIVATE)

    /** 已保存的预设（最近保存的排在最前） */
    fun all(): List<String> =
        prefs.getString(KEY_PRESETS, null)
            ?.split('\n')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    /** 保存一个预设；已存在则提到最前。返回最新列表。 */
    fun add(name: String): List<String> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return all()
        val next = (listOf(trimmed) + all().filter { it != trimmed }).take(MAX_PRESETS)
        prefs.edit().putString(KEY_PRESETS, next.joinToString("\n")).apply()
        return next
    }

    /** 删除一个预设，返回最新列表 */
    fun remove(name: String): List<String> {
        val next = all().filter { it != name }
        prefs.edit().putString(KEY_PRESETS, next.joinToString("\n")).apply()
        return next
    }

    /** 清空全部预设 */
    fun clear(): List<String> {
        prefs.edit().remove(KEY_PRESETS).apply()
        return emptyList()
    }

    /** 上次实际使用过的包名（用于打开导出对话框时预填） */
    var lastUsed: String?
        get() = prefs.getString(KEY_LAST_USED, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().putString(KEY_LAST_USED, value?.trim()?.takeIf { it.isNotEmpty() }).apply()
        }

    companion object {
        private const val KEY_PRESETS = "presets"
        private const val KEY_LAST_USED = "lastUsed"
        private const val MAX_PRESETS = 20
    }
}
