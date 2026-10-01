package net.naicli

import kotlinx.serialization.json.JsonObject

/**
 * 退出码。
 *
 * 分得比"0 与 1"细，是因为调用方是 AI agent：它需要靠退出码判断
 * "这次失败是我参数写错了、还是没有凭据、还是被花钱闸门拦下了"，
 * 三者的下一步动作完全不同（改参数重试 / 让人去连账号 / 让人去 approve）。
 */
object ExitCode {
    const val OK = 0

    /** 未分类失败（网络、服务端错误、本地 IO）。 */
    const val ERROR = 1

    /** 参数用法错误。改参数就能重试，不涉及账号与花费。 */
    const val USAGE = 2

    /** 没有可用凭据。需要人先在 MacNAI 里连账号，或用 --token / NAI_TOKEN。 */
    const val NO_CREDENTIAL = 3

    /** 花钱闸门拒绝：没有 grant、grant 过期、预算不够，或费用算不出来。 */
    const val SPEND_REFUSED = 4

    /** 闸门本身不可用（approve 需要真实 TTY，而当前进程没有）。 */
    const val GATE_UNAVAILABLE = 5
}

/**
 * 一次可预期的失败。
 *
 * 用它而不是异常堆栈：CLI 的失败绝大多数是"参数不对 / 没凭据 / 被闸门拦下"，
 * 打堆栈只会把真正的原因埋掉。[extra] 允许各命令附带结构化的诊断字段，
 * 在 `--json` 模式下原样进 JSON（例如闸门拒绝时附上 grant id 与剩余额度）。
 */
class CliFailure(
    val exitCode: Int,
    message: String,
    val extra: JsonObject = JsonObject(emptyMap()),
) : RuntimeException(message)

/**
 * 极简参数解析。
 *
 * 刻意不用第三方 CLI 库：这个工具的主要调用方是 AI agent，
 * 它需要的是"命令 + 长选项"这一种形态，而 `nai schema` 会把全部选项
 * 以 JSON 吐出来供 agent 自省。多引一个依赖换不到什么，反而让
 * `--help` 的格式脱离本项目控制。
 *
 * 支持：`--name value`、`--name=value`、`--switch`（在 [switches] 里声明的布尔项）、
 * `--` 之后全部当位置参数。同一选项重复出现时 [all] 取全部、[str] 取最后一个。
 */
class Args private constructor(
    private val values: Map<String, List<String>>,
    val switches: Set<String>,
    val positional: List<String>,
) {

    fun has(name: String): Boolean = name in switches

    fun str(name: String): String? = values[name]?.lastOrNull()

    fun all(name: String): List<String> = values[name].orEmpty()

    fun req(name: String): String =
        str(name) ?: throw CliFailure(ExitCode.USAGE, "缺少必填选项 --$name")

    fun int(name: String): Int? = str(name)?.let { raw ->
        raw.toIntOrNull() ?: throw CliFailure(ExitCode.USAGE, "--$name 需要整数，收到 '$raw'")
    }

    fun reqInt(name: String): Int = int(name) ?: throw CliFailure(ExitCode.USAGE, "缺少必填选项 --$name")

    fun long(name: String): Long? = str(name)?.let { raw ->
        raw.toLongOrNull() ?: throw CliFailure(ExitCode.USAGE, "--$name 需要整数，收到 '$raw'")
    }

    fun dbl(name: String): Double? = str(name)?.let { raw ->
        raw.toDoubleOrNull() ?: throw CliFailure(ExitCode.USAGE, "--$name 需要小数，收到 '$raw'")
    }

    fun reqDbl(name: String): Double = dbl(name) ?: throw CliFailure(ExitCode.USAGE, "缺少必填选项 --$name")

    companion object {

        fun parse(argv: List<String>, switches: Set<String> = emptySet()): Args {
            val values = mutableMapOf<String, MutableList<String>>()
            val seen = mutableSetOf<String>()
            val positional = mutableListOf<String>()
            var index = 0
            var noMoreOptions = false

            while (index < argv.size) {
                val token = argv[index]
                if (noMoreOptions || !token.startsWith("--") || token == "-") {
                    positional += token
                    index++
                    continue
                }
                if (token == "--") {
                    noMoreOptions = true
                    index++
                    continue
                }

                val body = token.removePrefix("--")
                val equals = body.indexOf('=')
                val name = if (equals >= 0) body.substring(0, equals) else body
                val inline = if (equals >= 0) body.substring(equals + 1) else null
                if (name.isEmpty()) {
                    throw CliFailure(ExitCode.USAGE, "空的选项名：'$token'")
                }

                when {
                    inline != null -> values.getOrPut(name) { mutableListOf() } += inline
                    name in switches -> seen += name
                    else -> {
                        val next = argv.getOrNull(index + 1)
                            ?: throw CliFailure(ExitCode.USAGE, "选项 --$name 后面缺少取值")
                        values.getOrPut(name) { mutableListOf() } += next
                        index++
                    }
                }
                index++
            }

            return Args(values = values, switches = seen, positional = positional)
        }
    }
}
