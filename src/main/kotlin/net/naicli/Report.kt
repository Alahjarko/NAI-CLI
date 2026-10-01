package net.naicli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 输出层：同一条命令，`--json` 出结构化数据，否则出人读的文本。
 *
 * JSON 里恒有 `ok` 字段，失败时恒有 `error.code` / `error.message` / `error.exitCode`。
 * 这个形状是刻意的：调用方是 AI agent，它需要一个**不看退出码也能判断成败**的字段，
 * 因为管道与包装脚本经常把退出码吃掉。
 */
class Reporter(val json: Boolean) {

    private val printer: Json = Json {
        prettyPrint = false
        encodeDefaults = true
        explicitNulls = false
    }

    fun emit(data: JsonObject, human: () -> String) {
        if (json) {
            println(printer.encodeToString(JsonObject.serializer(), withOk(data, true)))
        } else {
            human().lines().forEach { if (it.isNotBlank()) println(it) }
        }
    }

    fun fail(failure: CliFailure) {
        if (json) {
            val error = buildJsonObject {
                put("code", failure.exitCode)
                put("message", failure.message ?: "")
                failure.extra.forEach { (key, value) -> put(key, value) }
            }
            println(printer.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("ok", false)
                put("error", error)
            }))
        } else {
            System.err.println("错误：${failure.message}")
            failure.extra["hint"]?.let { System.err.println("提示：${(it as? JsonPrimitive)?.content}") }
        }
    }

    private fun withOk(data: JsonObject, ok: Boolean): JsonObject = buildJsonObject {
        put("ok", ok)
        data.forEach { (key, value) -> put(key, value) }
    }

    companion object {
        /**
         * 把若干 `名字 to 值` 排成对齐的两列文本。
         *
         * 值为 null 的整行跳过 —— 这样调用方可以直接写 `"余额" to balance?.toString()`，
         * 不必为"这个字段可能没有"再套一层 if。
         */
        fun table(rows: List<Pair<String, String?>>): String {
            val kept = rows.mapNotNull { (name, value) -> value?.let { name to it } }
            if (kept.isEmpty()) return ""
            val width = kept.maxOf { it.first.length }
            return kept.joinToString("\n") { (name, text) -> "${name.padEnd(width)}  $text" }
        }
    }
}
