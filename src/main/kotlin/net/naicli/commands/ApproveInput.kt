package net.naicli.commands

import net.naicli.Args
import net.naicli.CliFailure
import net.naicli.ExitCode

/** Collects fields only: no terminal bypass, grant creation, or confirmation capability. */
internal data class ApproveInput(
    val budget: Long,
    val calls: Int,
    val purpose: String,
    val minutes: Int?,
) {
    val ttlMillis: Long? get() = minutes?.takeIf { it > 0 }?.let { it * 60_000L }

    companion object {
        fun collect(args: Args, readLine: (String) -> String?, explain: (String) -> Unit): ApproveInput {
            // Validate supplied options first; never replace an invalid explicit budget with a default.
            val budget = args.long("anlas")?.also {
                if (it < 0) invalid("--anlas 不能为负")
            }
            val calls = args.int("calls")?.also {
                if (it < 1) invalid("--calls 至少为 1")
            }
            val purpose = args.str("purpose")?.trim()?.also {
                if (it.isEmpty()) invalid("--purpose 不能为空")
            }
            val minutes = args.int("minutes")?.also {
                if (it < 0) invalid("--minutes 不能为负（0 或省略表示不设有效期）")
            }
            if (args.positional.isNotEmpty()) invalid("用法：nai approve，或使用 --anlas / --purpose 等选项")

            // Fully specified legacy invocations go straight to the existing final confirmation.
            if (budget != null && purpose != null) {
                return ApproveInput(budget, calls ?: ApproveCommand.DEFAULT_CALLS, purpose, minutes)
            }
            explain("授权填写向导：回车采用括号内默认值，Ctrl+C 可取消。")
            fun line(prompt: String): String = readLine(prompt)?.trim()
                ?: throw CliFailure(ExitCode.SPEND_REFUSED, "输入已结束，未签发任何凭证。")
            fun number(prompt: String, default: Long, min: Long, max: Long): Long {
                while (true) {
                    val raw = line(prompt)
                    val value = if (raw.isEmpty()) default else raw.toLongOrNull()
                    if (value != null && value in min..max) return value
                    explain("请输入 $min 到 $max 之间的整数。")
                }
            }
            val chosenBudget = budget ?: number("Anlas 预算上限 [0，仅免费调用]：", 0, 0, Long.MAX_VALUE)
            val chosenCalls = calls ?: number("允许调用次数 [${ApproveCommand.DEFAULT_CALLS}]：",
                ApproveCommand.DEFAULT_CALLS.toLong(), 1, Int.MAX_VALUE.toLong()).toInt()
            var chosenPurpose = purpose
            while (chosenPurpose == null) {
                val value = line("用途说明（必填）：")
                if (value.isNotEmpty()) chosenPurpose = value else explain("请填写用途说明，便于之后查看授权记录。")
            }
            val chosenMinutes = minutes ?: number("有效期（分钟，可选；直接回车不限期）[0]：",
                0, 0, Int.MAX_VALUE.toLong()).toInt().takeIf { it > 0 }
            return ApproveInput(chosenBudget, chosenCalls, chosenPurpose, chosenMinutes)
        }

        private fun invalid(message: String): Nothing = throw CliFailure(ExitCode.USAGE, message)
    }
}
