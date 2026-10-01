package net.naicli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.naicli.commands.AuthCommands
import net.pocketnai.data.security.SecureStoreException
import net.naicli.commands.ApproveCommand
import net.naicli.commands.CharactersFromArgs
import net.naicli.commands.GrantsCommand
import net.naicli.commands.ReadOnlyCommands
import net.naicli.commands.SpendCommands
import net.pocketnai.BuildConfig
import java.io.File
import kotlin.system.exitProcess

/** 全部命令。`help` 与 `schema` 都从这张表生成，因此不会与实现脱节。 */
private val commands: List<CommandSpec> =
    ReadOnlyCommands.all + AuthCommands.all + SpendCommands.all + listOf(ApproveCommand.spec, GrantsCommand.spec)

private val prettyJson = Json { prettyPrint = true }

fun main(argv: Array<String>) {
    System.setProperty("java.awt.headless", "true")
    val code = try {
        dispatch(argv.toList())
    } catch (failure: CliFailure) {
        // CliFailure 可能出现在解析 --json 之前（例如命令名就不认识），
        // 那时只能按文本报错；已经进到命令里的，由命令自己的 reporter 负责。
        System.err.println("错误：${failure.message}")
        failure.exitCode
    }
    System.out.flush()
    System.err.flush()
    exitProcess(code)
}

private fun dispatch(argv: List<String>): Int {
    if (argv.isEmpty()) {
        System.err.println(overview())
        return ExitCode.USAGE
    }

    val head = argv.first()
    val rest = argv.drop(1)

    when (head) {
        "-h", "--help", "help" -> {
            val target = rest.firstOrNull()
            if (target == null) {
                println(overview())
                return ExitCode.OK
            }
            val spec = find(target)
            println(commandHelp(spec))
            return ExitCode.OK
        }

        "--version", "version" -> {
            println("NAI-CLI ${CLI_VERSION}（复用 MacNAI ${BuildConfig.VERSION_NAME} 的领域与网络层）")
            return ExitCode.OK
        }

        "schema" -> {
            println(prettyJson.encodeToString(JsonObject.serializer(), schema()))
            return ExitCode.OK
        }
    }

    val spec = find(head)
    val args = Args.parse(rest, switches = spec.switchNames + GlobalOptions.switchNames)

    if (args.has(GlobalOptions.HELP)) {
        println(commandHelp(spec))
        return ExitCode.OK
    }

    val reporter = Reporter(json = args.has(GlobalOptions.JSON))
    val context = NaiContext(
        explicitToken = args.str(GlobalOptions.TOKEN),
        dataDir = args.str(GlobalOptions.DATA_DIR)?.let(::File),
    )

    return try {
        spec.run(args, reporter, context)
        ExitCode.OK
    } catch (failure: CliFailure) {
        reporter.fail(failure)
        failure.exitCode
    } catch (failure: SecureStoreException) {
        reporter.fail(CliFailure(ExitCode.NO_CREDENTIAL, failure.message ?: "系统安全存储不可用。"))
        ExitCode.NO_CREDENTIAL
    } catch (throwable: Throwable) {
        // 只打类名与消息，不打堆栈：堆栈里可能带着调用方传进来的路径与参数，
        // 而这个工具的调用方是 AI agent，输出会被原样喂回模型上下文。
        // 需要排查时设 NAI_CLI_DEBUG=1 取完整堆栈。
        reporter.fail(
            CliFailure(
                ExitCode.ERROR,
                "未预期的错误：${throwable.javaClass.name}: ${throwable.message}",
            ),
        )
        if (System.getenv("NAI_CLI_DEBUG") == "1") throwable.printStackTrace()
        ExitCode.ERROR
    }
}

private fun find(name: String): CommandSpec =
    commands.firstOrNull { it.name == name }
        ?: throw CliFailure(
            ExitCode.USAGE,
            "未知命令 '$name'。可用：${commands.joinToString(", ") { it.name }}（另有 help / schema / version）",
        )

private fun schema(): JsonObject = buildJsonObject {
    put("tool", "nai")
    put("version", CLI_VERSION)
    put("macnaiApiBaseUrl", BuildConfig.NOVELAI_API_BASE_URL)
    put("commands", buildJsonArray { commands.forEach { add(it.toJson()) } })
    put("globalOptions", buildJsonArray {
        GlobalOptions.specs.forEach { option ->
            add(buildJsonObject {
                put("name", option.name)
                put("type", option.type)
                put("description", option.description)
            })
        }
    })
    put("exitCodes", buildJsonObject {
        put("0", "成功")
        put("1", "未分类失败（网络、服务端错误、本地 IO）")
        put("2", "参数用法错误，改参数即可重试")
        put("3", "没有可用凭据")
        put("4", "花钱闸门拒绝：缺 grant、grant 过期/用尽、预算不足，或费用算不出确定值")
        put("5", "交互终端不可用：approve / login 需要真实 TTY")
    })
    put("spendPolicy", buildJsonObject {
        put(
            "summary",
            "标了 spendsAnlas=true 的命令必须由真人先在终端里 `nai approve` 签发 grant；" +
                "AI agent 无法自签（approve 要求 System.console() 非空），只能消费已批出的限额。",
        )
        put("disabledByEnv", NaiContext.ENV_SPEND_DISABLED)
        put("grantDefaults", buildJsonObject {
            put("calls", ApproveCommand.DEFAULT_CALLS)
            put(
                "minutes",
                "可选。省略或填 0 = 不设有效期，此时 grant 只受 anlas 预算与 calls 次数约束。" +
                    "没用完的 grant 不会自动失效，需要 `nai grants revoke <id>` 收掉。",
            )
        })
        put("unknownCostPolicy", "费用算不出确定值时一律拒绝发起，不给'先跑跑看'的余地")
    })
    put("multiCharacter", CharactersFromArgs.schemaJson())
    put("notImplemented", buildJsonArray {
        add("图生图 / Precise Reference / Vibe Transfer / 局部重绘：CLI 只做纯文生图（含独立角色）")
        add("自定义分辨率的裁切（--out-width/--out-height）")
    })
}

private fun overview(): String = buildString {
    appendLine("NAI-CLI $CLI_VERSION —— 跨平台 NovelAI 命令行工具，供人与 AI agent 调用。")
    appendLine()
    appendLine("用法：nai <命令> [选项]      所有命令都支持 --json")
    appendLine()
    appendLine("只读（不花 Anlas，不需要授权）：")
    commands.filterNot { it.spends }.filter { it.name !in setOf("approve", "grants", "login", "account") }
        .forEach { appendLine("  ${it.name.padEnd(10)}  ${it.summary}") }
    appendLine()
    appendLine("花钱（必须由真人先 `nai approve` 签发 grant）：")
    commands.filter { it.spends }.forEach { appendLine("  ${it.name.padEnd(10)}  ${it.summary}") }
    appendLine()
    appendLine("账号管理：")
    AuthCommands.all.forEach { appendLine("  ${it.name.padEnd(10)}  ${it.summary}") }
    appendLine()
    appendLine("授权闸门：")
    appendLine("  ${"approve".padEnd(10)}  ${ApproveCommand.spec.summary}")
    appendLine("  ${"grants".padEnd(10)}  ${GrantsCommand.spec.summary}")
    appendLine()
    appendLine("自省：")
    appendLine("  ${"schema".padEnd(10)}  以 JSON 输出全部命令、选项、退出码与花钱策略（给 agent 读的）")
    appendLine("  ${"help".padEnd(10)}  nai help <命令>")
    appendLine()
    appendLine("环境：${NaiContext.ENV_TOKEN}（只读命令的 Token）、${NaiContext.ENV_SPEND_DISABLED}=1（彻底禁止花钱）")
}

private fun commandHelp(spec: CommandSpec): String = buildString {
    appendLine("${spec.name} —— ${spec.summary}")
    appendLine()
    appendLine("用法：${spec.usage}")
    if (spec.spends) {
        appendLine()
        appendLine("⚠ 本命令会真实花费 Anlas，必须带 --grant；grant 只能由真人在终端里 `nai approve` 签发。")
    }
    if (spec.options.isNotEmpty()) {
        appendLine()
        appendLine("选项：")
        val width = spec.options.maxOf { it.name.length }
        spec.options.forEach { option ->
            val required = if (option.required) "（必填）" else ""
            val default = option.default?.let { "，默认 $it" } ?: ""
            appendLine("  --${option.name.padEnd(width)}  ${option.description}$required$default")
        }
    }
    appendLine()
    appendLine("全局选项：")
    GlobalOptions.specs.forEach { option ->
        appendLine("  --${option.name.padEnd(10)}  ${option.description}")
    }
}

/** CLI 自己的版本号，与 MacNAI 的 [BuildConfig.VERSION_NAME] 不同源。 */
const val CLI_VERSION: String = "0.2.0"
