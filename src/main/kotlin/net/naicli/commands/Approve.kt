package net.naicli.commands

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.naicli.Args
import net.naicli.CliFailure
import net.naicli.CommandSpec
import net.naicli.ExitCode
import net.naicli.NaiContext
import net.naicli.OptionSpec
import net.naicli.Reporter
import net.naicli.gate.GrantStore
import net.naicli.gate.SpendGate
import net.naicli.gate.SpendGrant

/**
 * 签发花钱凭证。
 *
 * ## 这条命令**必须**在真实终端里由人运行
 * `System.console()` 在 stdin 或 stdout 被重定向时返回 null —— 而 AI agent 通过
 * 非交互 shell 调过来正是这种情况。这不是可以绕的实现细节，是闸门的全部强度来源：
 * 只要"签发"这一步要求 console，agent 就只能消费人已经批过的额度，不能自己批。
 *
 * 因此这里刻意**不提供** `--yes` / `--non-interactive` 之类的跳过开关，
 * 也不接受从 stdin 管道喂进来的确认词（`console.readLine()` 只读终端，不读管道）。
 */
object ApproveCommand {

    private const val CONFIRM_WORD = "APPROVE"

    const val DEFAULT_CALLS = 1

    val spec = CommandSpec(
        name = "approve",
        summary = "由真人在终端里签发授权；直接运行可逐步填写预算、次数与用途",
        spends = false,
        usage = "nai approve [--anlas <上限>] [--purpose \"<用途>\"] [--calls <次数>] [--minutes <分钟>]",
        options = listOf(
            OptionSpec("anlas", "int", "Anlas 总额上限；0 表示仅免费调用；省略时进入向导填写"),
            OptionSpec("purpose", "string", "用途说明，会写进审计日志；省略时进入向导填写"),
            OptionSpec("calls", "int", "允许发起的花钱调用次数", default = "$DEFAULT_CALLS"),
            OptionSpec(
                "minutes",
                "int",
                "有效期（分钟）。省略或填 0 = 不设有效期，此时 grant 只受 --anlas 与 --calls 约束；" +
                    "没用完的部分不会自动失效，要 `nai grants revoke <id>` 才收得掉",
            ),
        ),
    ) { args, reporter, _ -> run(args, reporter) }

    private fun run(args: Args, reporter: Reporter) {
        val console = System.console()
        if (console == null || !console.isTerminal) {
            throw CliFailure(
                ExitCode.GATE_UNAVAILABLE,
                "approve 需要在真实终端里交互运行，当前进程没有可用的 TTY（stdin/stdout 被重定向）。",
                extra = buildJsonObject {
                    put("hint", "请由人打开一个终端窗口直接运行本命令；自动化流程无法代签，这是设计如此。")
                },
            )
        }
        // JDK 22 起 Console 不再有 print/println，只能拿 writer()。
        // 用 console 自己的 writer 而不是 println：确认对话必须出现在终端上，
        // 不能跟着被重定向的 stdout 跑掉。
        val out = console.writer()

        val input = ApproveInput.collect(
            args,
            readLine = { prompt ->
                out.print(prompt)
                out.flush()
                console.readLine()
            },
            explain = { message -> out.println(message); out.flush() },
        )
        val budget = input.budget
        val maxCalls = input.calls
        val purpose = input.purpose
        val minutes = input.minutes
        val ttlMillis = input.ttlMillis
        val expiryText = if (ttlMillis == null) "不限期" else "$minutes 分钟"

        out.println()
        out.println("即将签发一张花钱凭证：")
        out.println("  Anlas 预算上限 : $budget")
        out.println("  允许调用次数   : $maxCalls")
        out.println("  有效期         : $expiryText")
        out.println("  用途           : $purpose")
        out.println()
        out.println("持有这张凭证的进程（包括 AI agent）可以在上述额度内发起真实付费请求。")
        if (ttlMillis == null) {
            out.println()
            out.println("⚠ 不设有效期意味着：没用完的次数与预算会**一直有效**，")
            out.println("  而 grant id 是明文存在 CLI 配置目录的 grants/ 里的，同机的进程都读得到。")
            out.println("  用完请收掉它：nai grants revoke <id>（nai grants list 可看还剩多少）")
        }
        out.println()
        out.print("确认请输入 $CONFIRM_WORD，其它任意输入取消：")
        out.flush()

        val answer = console.readLine()?.trim()
        if (answer != CONFIRM_WORD) {
            out.println("已取消，未签发任何凭证。")
            out.flush()
            throw CliFailure(ExitCode.SPEND_REFUSED, "用户在交互确认时取消了签发。")
        }

        val grant = GrantStore.default().create(
            purpose = purpose,
            anlasBudget = budget,
            maxCalls = maxCalls,
            ttlMillis = ttlMillis,
        )

        reporter.emit(
            buildJsonObject {
                put("grant", grantJson(grant))
                put("nextStep", "nai generate --grant ${grant.id} ...")
            },
        ) {
            """
            已签发 grant：${grant.id}
            预算 ${grant.anlasBudget} Anlas / ${grant.maxCalls} 次 / $expiryText

            下一步把它交给要执行的那条命令，例如：
              nai generate --grant ${grant.id} --prompt "..."
            """.trimIndent() + if (ttlMillis == null) "\n\n用完记得吊销：nai grants revoke ${grant.id}" else ""
        }
    }

    fun grantJson(grant: SpendGrant) = buildJsonObject {
        val now = System.currentTimeMillis()
        put("id", grant.id)
        put("purpose", grant.purpose)
        put("createdAt", grant.createdAt)
        // null 表示这张 grant 不过期，只受 anlasBudget 与 maxCalls 约束。
        put("expiresAt", grant.expiresAt)
        put("expired", grant.isExpired(now))
        put("revoked", grant.revoked)
        put("anlasBudget", grant.anlasBudget)
        put("anlasSpent", grant.anlasSpent)
        put("anlasRemaining", grant.remainingAnlas)
        put("maxCalls", grant.maxCalls)
        put("callsUsed", grant.callsUsed)
        put("callsRemaining", grant.remainingCalls)
        put("usable", !grant.revoked && !grant.isExpired(now) && grant.remainingCalls > 0)
    }
}

/** `nai grants list|revoke`：查看与吊销已签发的凭证。 */
object GrantsCommand {

    val spec = CommandSpec(
        name = "grants",
        summary = "查看或吊销花钱凭证",
        spends = false,
        usage = "nai grants list | nai grants revoke <id>",
        options = emptyList(),
    ) { args, reporter, _ -> run(args, reporter) }

    private fun run(args: Args, reporter: Reporter) {
        val store = GrantStore.default()
        when (val action = args.positional.firstOrNull() ?: "list") {
            "list" -> {
                val grants = store.all()
                reporter.emit(
                    buildJsonObject {
                        put("grants", kotlinx.serialization.json.buildJsonArray {
                            grants.forEach { add(ApproveCommand.grantJson(it)) }
                        })
                        put("auditLog", store.auditLog.absolutePath)
                    },
                ) {
                    if (grants.isEmpty()) {
                        "还没有签发过 grant。运行 `nai approve` 签发一张。"
                    } else {
                        val now = System.currentTimeMillis()
                        grants.joinToString("\n") { grant ->
                            // 不设有效期之后，这张表就是"我还留着哪些能花钱的凭证"的唯一视图，
                            // 所以状态要说准：预算用完但次数还剩的 grant 依然能跑免费调用。
                            val state = when {
                                grant.revoked -> "已吊销"
                                grant.isExpired(now) -> "已过期"
                                grant.remainingCalls <= 0 -> "次数已用尽"
                                grant.remainingAnlas <= 0 -> "预算已用尽（仍可发起免费调用）"
                                else -> "可用"
                            }
                            val expiry = grant.expiresAt?.let { "至 ${SpendGate.formatTime(it)}" } ?: "不限期"
                            "${grant.id}  [$state]  预算 ${grant.anlasBudget}／已花 ${grant.anlasSpent}" +
                                "  次数 ${grant.callsUsed}/${grant.maxCalls}  $expiry\n" +
                                "    用途：${grant.purpose}"
                        } + "\n\n审计日志：${store.auditLog.absolutePath}"
                    }
                }
            }

            "revoke" -> {
                val id = args.positional.getOrNull(1)
                    ?: throw CliFailure(ExitCode.USAGE, "用法：nai grants revoke <id>")
                val revoked = store.revoke(id)
                store.audit("${SpendGate.formatTime(System.currentTimeMillis())} | grants revoke | grant=$id | ok=$revoked")
                reporter.emit(
                    buildJsonObject { put("id", id); put("revoked", revoked) },
                ) { if (revoked) "已吊销 $id" else "找不到 grant：$id" }
                if (!revoked) throw CliFailure(ExitCode.USAGE, "找不到 grant：$id")
            }

            else -> throw CliFailure(ExitCode.USAGE, "未知子命令 '$action'，只支持 list / revoke")
        }
    }
}
