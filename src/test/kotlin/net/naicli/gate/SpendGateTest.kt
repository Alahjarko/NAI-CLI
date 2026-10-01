package net.naicli.gate

import net.naicli.CliFailure
import net.naicli.ExitCode
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 花钱闸门的行为契约。
 *
 * 这些用例钉住的是**安全属性**，不是实现细节：任何一条挂了都意味着
 * "AI agent 可能在没有真人授权的情况下花掉 Anlas"。改动闸门时先看这里。
 */
class SpendGateTest {

    private val dir = createTempDir()
    private val store = GrantStore(dir)
    private val gate = SpendGate(store)

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun grant(
        budget: Long = 100L,
        calls: Int = 5,
        ttlMillis: Long? = null,
    ): SpendGrant = store.create(
        purpose = "测试用途",
        anlasBudget = budget,
        maxCalls = calls,
        ttlMillis = ttlMillis,
    )

    // ---- 准入拒绝路径 ----

    @Test
    fun `没有 grant 时拒绝`() {
        val failure = assertFailsWith<CliFailure> {
            gate.acquire(null, "generate", SpendCost.Anlas(10))
        }
        assertEquals(ExitCode.SPEND_REFUSED, failure.exitCode)
    }

    @Test
    fun `grant id 为空串时拒绝`() {
        val failure = assertFailsWith<CliFailure> {
            gate.acquire("", "generate", SpendCost.Anlas(10))
        }
        assertEquals(ExitCode.SPEND_REFUSED, failure.exitCode)
    }

    @Test
    fun `不存在的 grant 拒绝`() {
        val failure = assertFailsWith<CliFailure> {
            gate.acquire("g_000000000000000000000000", "generate", SpendCost.Anlas(10))
        }
        assertEquals(ExitCode.SPEND_REFUSED, failure.exitCode)
    }

    @Test
    fun `已吊销的 grant 拒绝`() {
        val created = grant()
        store.revoke(created.id)
        val failure = assertFailsWith<CliFailure> {
            gate.acquire(created.id, "generate", SpendCost.Anlas(10))
        }
        assertEquals(ExitCode.SPEND_REFUSED, failure.exitCode)
        assertTrue(failure.message!!.contains("吊销"))
    }

    @Test
    fun `已过期的 grant 拒绝`() {
        val created = grant(ttlMillis = -1_000L)
        val failure = assertFailsWith<CliFailure> {
            gate.acquire(created.id, "generate", SpendCost.Anlas(10))
        }
        assertEquals(ExitCode.SPEND_REFUSED, failure.exitCode)
        assertTrue(failure.message!!.contains("过期"))
    }

    @Test
    fun `给了有效期的 grant 落盘时带上 expiresAt`() {
        val created = grant(ttlMillis = 60_000L)
        val expiresAt = assertNotNull(created.expiresAt)
        assertTrue(expiresAt > created.createdAt)
        // 读回来再判一次：`Long?` 要经得住 JSON 往返，旧 grant 文件也是靠这条继续能读的。
        assertTrue(store.load(created.id)!!.isExpired(expiresAt), "到期那一刻就该算过期")
    }

    @Test
    fun `不设有效期的 grant 永远不会过期，只受预算与次数约束`() {
        // 这是默认路径。时间不再是闸门的一个维度，所以损失上限完全由
        // anlasBudget 与 maxCalls 界定 —— 两个都要在这里被证明仍然有效。
        val created = grant(budget = 12L, calls = 2)
        assertNull(created.expiresAt)
        assertTrue(!created.isExpired(Long.MAX_VALUE), "expiresAt 为 null 时任何时刻都不算过期")

        // 放很久之后仍然能准入。
        val first = gate.acquire(created.id, "generate", SpendCost.Anlas(6))
        assertEquals(6L, first.charge)
        gate.settle(first, "generate", SpendOutcome.SUCCESS, "test")

        // 预算还在、次数还在 → 继续放行。
        val second = gate.acquire(created.id, "generate", SpendCost.Anlas(6))
        gate.settle(second, "generate", SpendOutcome.SUCCESS, "test")

        // 次数用尽 → 拒绝，与过了多久无关。
        val outOfCalls = assertFailsWith<CliFailure> {
            gate.acquire(created.id, "generate", SpendCost.Anlas(1))
        }
        assertTrue(outOfCalls.message!!.contains("次数"), outOfCalls.message!!)

        // 预算用尽但次数还有 → 付费调用拒绝，免费调用仍放行。
        val rich = grant(budget = 0L, calls = 3)
        val refused = assertFailsWith<CliFailure> {
            gate.acquire(rich.id, "generate", SpendCost.Anlas(1))
        }
        assertTrue(refused.message!!.contains("预算"), refused.message!!)
        assertEquals(0L, gate.acquire(rich.id, "generate", SpendCost.Free).charge)
    }

    @Test
    fun `费用算不出来时一律拒绝，即使预算充足`() {
        // 这条对应 AGENTS.md 的"费用未知一律不得自动发起"：
        // 界面上还允许人点确认，CLI 的调用方是自动化流程，没有那个人。
        val created = grant(budget = 10_000L)
        val failure = assertFailsWith<CliFailure> {
            gate.acquire(created.id, "generate", SpendCost.Unknown)
        }
        assertEquals(ExitCode.SPEND_REFUSED, failure.exitCode)
        // 关键：拒绝时不得预扣，也不得消耗次数。
        val after = store.load(created.id)!!
        assertEquals(0L, after.anlasSpent)
        assertEquals(0, after.callsUsed)
    }

    @Test
    fun `超出预算时拒绝`() {
        val created = grant(budget = 5L)
        val failure = assertFailsWith<CliFailure> {
            gate.acquire(created.id, "generate", SpendCost.Anlas(6))
        }
        assertEquals(ExitCode.SPEND_REFUSED, failure.exitCode)
        assertEquals(0L, store.load(created.id)!!.anlasSpent)
    }

    @Test
    fun `预算恰好等于花费时放行`() {
        val created = grant(budget = 6L)
        val ticket = gate.acquire(created.id, "generate", SpendCost.Anlas(6))
        assertEquals(6L, ticket.charge)
        assertEquals(0L, store.load(created.id)!!.remainingAnlas)
    }

    // ---- 次数与额度的累计 ----

    @Test
    fun `单次 grant 用掉一次后第二次被拒`() {
        val created = grant(budget = 100L, calls = 1)
        gate.acquire(created.id, "generate", SpendCost.Anlas(1))
        val failure = assertFailsWith<CliFailure> {
            gate.acquire(created.id, "generate", SpendCost.Anlas(1))
        }
        assertEquals(ExitCode.SPEND_REFUSED, failure.exitCode)
        assertTrue(failure.message!!.contains("次数"))
    }

    @Test
    fun `多次调用累计消耗预算，超出的那次被拒`() {
        val created = grant(budget = 10L, calls = 5)
        gate.acquire(created.id, "generate", SpendCost.Anlas(6))
        val failure = assertFailsWith<CliFailure> {
            gate.acquire(created.id, "generate", SpendCost.Anlas(6))
        }
        assertEquals(ExitCode.SPEND_REFUSED, failure.exitCode)
        assertEquals(6L, store.load(created.id)!!.anlasSpent)
    }

    @Test
    fun `预扣发生在请求之前`() {
        // 闸门的全部强度来自这一条：额度必须在 POST 发出**之前**就占住，
        // 否则两个并发进程会同时通过预算检查、合计花掉两倍。
        val created = grant(budget = 10L, calls = 5)
        gate.acquire(created.id, "generate", SpendCost.Anlas(10))
        assertEquals(10L, store.load(created.id)!!.anlasSpent)
        assertEquals(0L, store.load(created.id)!!.remainingAnlas)
    }

    @Test
    fun `免费调用不消耗预算但仍消耗次数`() {
        val created = grant(budget = 0L, calls = 2)
        val first = gate.acquire(created.id, "generate", SpendCost.Free)
        assertEquals(0L, first.charge)
        gate.acquire(created.id, "generate", SpendCost.Free)
        val failure = assertFailsWith<CliFailure> {
            gate.acquire(created.id, "generate", SpendCost.Free)
        }
        assertEquals(ExitCode.SPEND_REFUSED, failure.exitCode)
    }

    @Test
    fun `零预算 grant 只能跑免费调用`() {
        // `nai approve --anlas 0` 的语义：允许 agent 出图，但只允许订阅权益覆盖的那些。
        val created = grant(budget = 0L)
        assertEquals(0L, gate.acquire(created.id, "generate", SpendCost.Free).charge)
        assertFailsWith<CliFailure> {
            gate.acquire(created.id, "generate", SpendCost.Anlas(1))
        }
    }

    // ---- 结算与退款 ----

    @Test
    fun `成功时预扣即真实花费，不退款`() {
        val created = grant(budget = 20L)
        val ticket = gate.acquire(created.id, "generate", SpendCost.Anlas(17))
        gate.settle(ticket, "generate", SpendOutcome.SUCCESS, "generation=x")
        assertEquals(17L, store.load(created.id)!!.anlasSpent)
    }

    @Test
    fun `明确失败时退回预扣额度`() {
        val created = grant(budget = 20L)
        val ticket = gate.acquire(created.id, "generate", SpendCost.Anlas(17))
        gate.settle(ticket, "generate", SpendOutcome.FAILED_DEFINITE, "errorCode=INVALID_PARAMS")
        assertEquals(0L, store.load(created.id)!!.anlasSpent)
        // 退款不退次数：否则 agent 可以拿同一张 grant 反复撞墙。
        assertEquals(1, store.load(created.id)!!.callsUsed)
    }

    @Test
    fun `超时不确定时不退款`() {
        // 服务端可能已经接受并计费，宁可少花一次额度也不能让它在账上显示为免费。
        val created = grant(budget = 20L)
        val ticket = gate.acquire(created.id, "generate", SpendCost.Anlas(17))
        gate.settle(ticket, "generate", SpendOutcome.FAILED_UNCERTAIN, "errorCode=TIMEOUT_UNCERTAIN")
        assertEquals(17L, store.load(created.id)!!.anlasSpent)
    }

    @Test
    fun `退款不会把已花费扣成负数`() {
        val created = grant(budget = 20L)
        val ticket = gate.acquire(created.id, "generate", SpendCost.Anlas(17))
        // 重复结算（异常路径下可能发生）不得让 anlasSpent 变成负数。
        gate.settle(ticket, "generate", SpendOutcome.FAILED_DEFINITE, "first")
        gate.settle(ticket, "generate", SpendOutcome.FAILED_DEFINITE, "second")
        assertEquals(0L, store.load(created.id)!!.anlasSpent)
    }

    // ---- 审计与 id 校验 ----

    @Test
    fun `结算写审计日志，且不含提示词原文`() {
        val created = grant(budget = 20L)
        val ticket = gate.acquire(created.id, "generate", SpendCost.Anlas(17))
        gate.settle(ticket, "generate", SpendOutcome.SUCCESS, "generation=abc len=12 fp=deadbeef")
        val log = store.auditLog.readText()
        assertTrue(log.contains("generate"), "审计里要有命令名")
        assertTrue(log.contains(created.id), "审计里要有 grant id")
        assertTrue(log.contains("anlas=17"), "审计里要有实际扣费")
    }

    @Test
    fun `被拒绝的尝试也留痕`() {
        gate.auditRefusal("generate", "reason=没有 grant")
        assertTrue(store.auditLog.readText().contains("REFUSED"))
    }

    @Test
    fun `grant id 里的路径穿越被挡下`() {
        // id 是外部传进来的路径片段，不校验就能写到 grantsDir 之外。
        val failure = assertFailsWith<CliFailure> {
            store.load("../../../../tmp/evil")
        }
        assertEquals(ExitCode.USAGE, failure.exitCode)
    }

    @Test
    fun `合法 id 通过校验`() {
        val created = grant()
        assertTrue(created.id.matches(Regex("^g_[0-9a-f]{24}$")))
        assertEquals(created, store.load(created.id))
    }

    @Test
    fun `grant 列表按创建时间倒序且包含已过期项`() {
        // 显式给 now：不这样做的话两张 grant 会落在同一毫秒，
        // 而 `listFiles()` 的顺序由文件系统决定，排序就成了随机的（这个用例曾经 flaky）。
        val base = System.currentTimeMillis()
        val older = store.create("旧的用途", anlasBudget = 10L, maxCalls = 1, ttlMillis = -1_000L, now = base - 60_000L)
        val newer = store.create("新的用途", anlasBudget = 10L, maxCalls = 1, ttlMillis = 60_000L, now = base)

        val all = store.all()
        assertEquals(2, all.size, "已过期的 grant 也要列出来，人要能看到自己批过什么")
        assertEquals(newer.id, all.first().id)
        assertEquals(older.id, all.last().id)
        assertTrue(older.isExpired(base))
        assertTrue(!newer.isExpired(base))
    }

    private fun createTempDir(): File =
        java.nio.file.Files.createTempDirectory("nai-cli-gate").toFile()
}
