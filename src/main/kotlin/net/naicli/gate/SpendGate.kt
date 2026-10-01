package net.naicli.gate

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.naicli.CliFailure
import net.naicli.ExitCode
import net.naicli.NaiContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 本次调用的花费，三态。 */
sealed interface SpendCost {
    /** 订阅权益或 V5 额度全额覆盖，Anlas 消耗为 0。 */
    data object Free : SpendCost

    /** 已校准公式算出的确定 Anlas。 */
    data class Anlas(val amount: Long) : SpendCost

    /**
     * 算不出确定费用。
     *
     * **一律拒绝**，不给"先跑跑看"的余地 —— 这条直接来自 AGENTS.md：
     * "费用未知或确定要花钱的调用一律不得自动发起"。CLI 的调用方是自动化流程，
     * 没有人在旁边看着报价，所以这里比界面更严：界面上"费用待确认"还允许人点确认，
     * CLI 上没有那个"人"。
     */
    data object Unknown : SpendCost
}

/** 花钱之后怎么收场，决定要不要退回预扣的额度。 */
enum class SpendOutcome {
    /** 成功出图。预扣的额度就是真实花费。 */
    SUCCESS,

    /**
     * 明确失败（400 / 401 / 402 / 参数非法 / 本地校验没过）。
     *
     * 服务端没有接受任务，因此**退回**预扣额度。仍然消耗一次调用次数，
     * 免得 agent 拿着同一张 grant 反复撞墙。
     */
    FAILED_DEFINITE,

    /**
     * 超时或断流：对应 `ErrorCode.TIMEOUT_UNCERTAIN`。
     *
     * 服务端**可能已经接受并计费**，因此不退款 —— 宁可少花一次额度，
     * 也不能让"其实扣了钱"的那次在账上显示为免费。
     */
    FAILED_UNCERTAIN,
}

/** 一次已通过的准入。[charge] 是 acquire 时预扣的 Anlas。 */
data class Ticket(val grantId: String, val charge: Long)

/**
 * 花钱闸门的执行者。
 *
 * 两段式（[acquire] 预扣 → [settle] 结算）而不是一次判定，原因是并发：
 * 额度必须在**请求发出之前**就从 grant 上扣掉，否则两个并发进程会同时通过预算检查，
 * 合计花掉两倍额度。扣完再退，比退完再补要安全得多。
 */
class SpendGate(private val store: GrantStore) {

    /**
     * 准入检查 + 预扣额度。
     *
     * @throws CliFailure 退出码 [ExitCode.SPEND_REFUSED]，任何一条不满足都拒绝。
     */
    fun acquire(grantId: String?, command: String, cost: SpendCost): Ticket {
        if (spendDisabled()) {
            throw CliFailure(
                ExitCode.SPEND_REFUSED,
                "${NaiContext.ENV_SPEND_DISABLED} 已设置，本进程禁止任何花钱调用。",
            )
        }
        if (grantId.isNullOrBlank()) {
            throw CliFailure(
                ExitCode.SPEND_REFUSED,
                "花钱命令需要 --grant <id>，而 grant 只能由真人在终端里执行 `nai approve` 签发。",
                extra = buildJsonObject {
                    put("hint", "请让你的人类操作者在真实终端里运行：nai approve --anlas <上限> --purpose \"<用途>\"")
                    put("approveCommand", "nai approve --anlas <上限> --purpose \"<用途>\"")
                },
            )
        }

        return store.withLock(grantId) {
            val now = System.currentTimeMillis()
            val grant = store.load(grantId)
                ?: throw refused("grant 不存在：$grantId", grantId)

            if (grant.revoked) throw refused("grant 已被吊销：$grantId", grantId)
            val expiresAt = grant.expiresAt
            if (expiresAt != null && now >= expiresAt) {
                throw refused(
                    "grant 已过期：$grantId（有效期到 ${formatTime(expiresAt)}）",
                    grantId,
                )
            }
            if (grant.remainingCalls <= 0) {
                throw refused(
                    "grant 的调用次数已用尽：$grantId（上限 ${grant.maxCalls} 次）",
                    grantId,
                )
            }

            val charge = when (cost) {
                SpendCost.Unknown -> throw CliFailure(
                    ExitCode.SPEND_REFUSED,
                    "本次调用的费用算不出确定数字，闸门拒绝发起。",
                    extra = buildJsonObject {
                        put("grantId", grantId)
                        put("hint", "参数可能超出官方报价范围。先用 `nai cost ...` 看报价，或改用已知可计价的参数。")
                    },
                )

                SpendCost.Free -> 0L

                is SpendCost.Anlas -> {
                    if (cost.amount > grant.remainingAnlas) {
                        throw refused(
                            "预算不足：本次需要 ${cost.amount} Anlas，grant $grantId 还剩 ${grant.remainingAnlas}。",
                            grantId,
                        )
                    }
                    cost.amount
                }
            }

            // 预扣：请求还没发出就把额度占住，结算时再按结果退回。
            store.save(
                grant.copy(
                    anlasSpent = grant.anlasSpent + charge,
                    callsUsed = grant.callsUsed + 1,
                ),
            )
            Ticket(grantId = grantId, charge = charge)
        }
    }

    /**
     * 结算：按结果决定退款，并写审计。
     *
     * [detail] 只放脱敏信息（提示词长度与哈希指纹、生成 id），**不放提示词原文** ——
     * 与 AGENTS.md 的日志约束保持一致。
     */
    fun settle(ticket: Ticket, command: String, outcome: SpendOutcome, detail: String) {
        val refunded = if (outcome == SpendOutcome.FAILED_DEFINITE && ticket.charge > 0) {
            store.withLock(ticket.grantId) {
                val grant = store.load(ticket.grantId) ?: return@withLock false
                store.save(grant.copy(anlasSpent = (grant.anlasSpent - ticket.charge).coerceAtLeast(0L)))
                true
            }
        } else {
            false
        }

        val charged = if (refunded) 0L else ticket.charge
        store.audit(
            listOf(
                formatTime(System.currentTimeMillis()),
                command,
                "grant=${ticket.grantId}",
                "anlas=$charged",
                if (refunded) "refunded=${ticket.charge}" else null,
                "outcome=${outcome.name}",
                detail,
            ).filterNotNull().joinToString(" | "),
        )
    }

    /** 没有预扣成功（acquire 抛了）时也要留痕，否则"被拦下的尝试"在审计里是空白。 */
    fun auditRefusal(command: String, reason: String) {
        store.audit(
            listOf(
                formatTime(System.currentTimeMillis()),
                command,
                "outcome=REFUSED",
                reason,
            ).joinToString(" | "),
        )
    }

    private fun spendDisabled(): Boolean {
        val raw = System.getenv(NaiContext.ENV_SPEND_DISABLED) ?: return false
        return raw.equals("1", true) || raw.equals("true", true) || raw.equals("yes", true)
    }

    private fun refused(message: String, grantId: String): CliFailure = CliFailure(
        ExitCode.SPEND_REFUSED,
        message,
        extra = buildJsonObject {
            put("grantId", grantId)
            put("hint", "用 `nai grants list` 查看现有 grant，或让真人重新 `nai approve`。")
        },
    )

    companion object {
        private val TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

        fun formatTime(epochMillis: Long): String = TIME_FORMAT.format(Instant.ofEpochMilli(epochMillis))
    }
}
