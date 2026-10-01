package net.naicli

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 一个选项的声明。既用于解析，也用于 `help` 与 `schema` 的输出。 */
data class OptionSpec(
    val name: String,
    /** `string` / `int` / `double` / `flag` / `path` / `enum:<a|b|c>`。 */
    val type: String,
    val description: String,
    val required: Boolean = false,
    val default: String? = null,
)

/**
 * 一条命令的声明。
 *
 * [spends] 是全表最重要的一个字段：它为 true 的命令一律要过 [net.naicli.gate.SpendGate]，
 * 而 `schema` 会把它如实告诉调用方 —— agent 因此在**发起之前**就知道哪条命令会花钱，
 * 不必靠读文档或试错。
 */
data class CommandSpec(
    val name: String,
    val summary: String,
    val spends: Boolean,
    val usage: String,
    val options: List<OptionSpec>,
    val run: (args: Args, reporter: Reporter, context: NaiContext) -> Unit,
) {
    /** 布尔开关的名字集合，交给 [Args.parse]。 */
    val switchNames: Set<String>
        get() = options.filter { it.type == "flag" }.map { it.name }.toSet()

    fun toJson(): JsonObject = buildJsonObject {
        put("name", name)
        put("summary", summary)
        put("spendsAnlas", spends)
        put("usage", usage)
        put("options", buildJsonArray {
            options.forEach { option ->
                add(buildJsonObject {
                    put("name", option.name)
                    put("type", option.type)
                    put("description", option.description)
                    put("required", option.required)
                    option.default?.let { put("default", it) }
                })
            }
        })
    }
}

/** 全局选项：每条命令都接受，因此不出现在各命令的 [CommandSpec.options] 里。 */
object GlobalOptions {
    const val JSON = "json"
    const val TOKEN = "token"
    const val DATA_DIR = "data-dir"
    const val HELP = "help"

    val specs: List<OptionSpec> = listOf(
        OptionSpec(JSON, "flag", "以 JSON 输出结果与错误（供程序调用）"),
        OptionSpec(TOKEN, "string", "NovelAI Token；只对只读命令生效，花钱命令一律拒绝"),
        OptionSpec(
            DATA_DIR,
            "path",
            "覆盖 files/cache/prefs/数据库；macOS 默认共享 MacNAI，Linux 使用 XDG_DATA_HOME/nai-cli，" +
                "Windows 使用 LOCALAPPDATA/NAI-CLI。此选项不隔离系统安全存储或授权目录",
        ),
        OptionSpec(HELP, "flag", "显示本命令的用法"),
    )

    val switchNames: Set<String> = setOf(JSON, HELP)
}
