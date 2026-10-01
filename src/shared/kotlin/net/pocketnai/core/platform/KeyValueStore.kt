package net.pocketnai.core.platform

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 键值存储（对照 Android 的 `SharedPreferences`）。
 *
 * 只保存**非敏感**内容：草稿、设置、代理配置、多账号索引与提示信息。
 * Token / 密码 / Access Key 一律不进这里（见 `CredentialStore` 与 AGENTS.md 的安全红线）。
 *
 * 接口形态刻意与 `SharedPreferences` 一致，这样 `SettingsStore` / `GenerationDraftPreferences` /
 * `ProxyStore` 三个文件的移植只需要把取 store 的方式从 Context 换成 [PlatformEnvironment]。
 */
interface KeyValueStore {
    fun getString(key: String, defValue: String? = null): String?
    fun getBoolean(key: String, defValue: Boolean = false): Boolean
    fun getInt(key: String, defValue: Int = 0): Int
    fun getLong(key: String, defValue: Long = 0L): Long
    fun getFloat(key: String, defValue: Float = 0f): Float
    fun getStringSet(key: String, defValue: Set<String> = emptySet()): Set<String>
    fun contains(key: String): Boolean
    fun all(): Map<String, Any?>

    /** 对照 `SharedPreferences.edit().putX().apply()`：写入后立即落盘。 */
    fun edit(block: Editor.() -> Unit)

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun putInt(key: String, value: Int): Editor
        fun putLong(key: String, value: Long): Editor
        fun putFloat(key: String, value: Float): Editor
        fun putStringSet(key: String, value: Set<String>): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
    }
}

/**
 * JSON 文件实现。
 *
 * 为什么是 JSON 文件而不是 `java.util.prefs`：后者在 macOS 上写的是
 * `~/Library/Preferences/com.apple.java.util.prefs.plist`，既不去重也难迁移；
 * JSON 文件可以跟着应用数据目录一起备份/清理，与 Android 版的"一个 prefs 文件"语义最接近。
 */
class JsonFileKeyValueStore(private val file: File) : KeyValueStore {

    private val lock = Any()
    private val values = ConcurrentHashMap<String, Any?>()

    init {
        load()
    }

    private fun load() {
        if (!file.isFile) return
        val text = runCatching { file.readText() }.getOrNull() ?: return
        if (text.isBlank()) return
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
        root.forEach { (key, element) -> values[key] = decode(element) }
    }

    private fun decode(element: JsonElement): Any? = when (element) {
        is JsonPrimitive -> when {
            element.isString -> element.content
            else -> element.booleanOrNull ?: element.longOrNull ?: element.doubleOrNull ?: element.content
        }
        is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
        is JsonObject -> element.toString()
    }

    private fun encode(value: Any?): JsonElement = when (value) {
        null -> JsonPrimitive(null as String?)
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Float -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is Set<*> -> JsonArray(value.mapNotNull { it?.let { v -> JsonPrimitive(v.toString()) } })
        else -> JsonPrimitive(value.toString())
    }

    private fun persist() {
        val snapshot = synchronized(lock) { values.toMap() }
        val obj = buildJsonObject { snapshot.forEach { (k, v) -> put(k, encode(v)) } }
        atomicWriteText(file, json.encodeToString(JsonObject.serializer(), obj))
    }

    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue

    override fun getInt(key: String, defValue: Int): Int = when (val v = values[key]) {
        is Int -> v
        is Long -> v.toInt()
        is Number -> v.toInt()
        else -> defValue
    }

    override fun getLong(key: String, defValue: Long): Long = when (val v = values[key]) {
        is Long -> v
        is Int -> v.toLong()
        is Number -> v.toLong()
        else -> defValue
    }

    override fun getFloat(key: String, defValue: Float): Float = when (val v = values[key]) {
        is Number -> v.toFloat()
        else -> defValue
    }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValue: Set<String>): Set<String> =
        (values[key] as? Set<String>) ?: defValue

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun all(): Map<String, Any?> = values.toMap()

    override fun edit(block: KeyValueStore.Editor.() -> Unit) {
        val editor = EditorImpl()
        editor.block()
        synchronized(lock) {
            editor.removals.forEach { values.remove(it) }
            if (editor.cleared) values.clear()
            editor.writes.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
        }
        persist()
    }

    private class EditorImpl : KeyValueStore.Editor {
        val writes = LinkedHashMap<String, Any?>()
        val removals = mutableListOf<String>()
        var cleared = false

        override fun putString(key: String, value: String?) = apply { writes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { writes[key] = value }
        override fun putInt(key: String, value: Int) = apply { writes[key] = value }
        override fun putLong(key: String, value: Long) = apply { writes[key] = value }
        override fun putFloat(key: String, value: Float) = apply { writes[key] = value }
        override fun putStringSet(key: String, value: Set<String>) = apply { writes[key] = value }
        override fun remove(key: String) = apply { removals += key }
        override fun clear() = apply { cleared = true }

        private fun apply(block: () -> Unit): KeyValueStore.Editor {
            block()
            return this
        }
    }

    private companion object {
        val json = Json { prettyPrint = false; encodeDefaults = true }
    }
}
