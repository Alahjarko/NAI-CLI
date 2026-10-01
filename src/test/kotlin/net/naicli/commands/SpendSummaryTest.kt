package net.naicli.commands

import net.naicli.gate.SpendOutcome
import net.pocketnai.core.AppError
import net.pocketnai.core.ErrorCode
import net.pocketnai.data.repo.GenerationEvent
import net.pocketnai.domain.model.GeneratedImage
import net.pocketnai.domain.model.GenerationStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 事件流 → 花钱结论的映射。
 *
 * 这段逻辑决定**退不退款**，而真实生成要花 Anlas、不能拿真账号试，
 * 因此它必须能在离线状态下被钉住。每条用例都对应一个会真实发生的收尾形态。
 */
class SpendSummaryTest {

    private fun image(ordinal: Int = 1) = GeneratedImage(
        id = "img-$ordinal",
        generationId = "gen-1",
        ordinal = ordinal,
        seed = 42L,
        privateFilePath = "generations/gen-1/000$ordinal.png",
        width = 1024,
        height = 1024,
        byteSize = 1_000L,
        sha256 = "ab".repeat(32),
        createdAt = 0L,
    )

    private fun error(code: ErrorCode) = AppError.of(code)

    private fun summarize(vararg events: GenerationEvent) = SpendCommands.summarize(events.toList())

    @Test
    fun `正常收尾算成功，图片被收集`() {
        val result = summarize(
            GenerationEvent.Started("gen-1"),
            GenerationEvent.Final("gen-1", image(1)),
            GenerationEvent.Completed("gen-1", GenerationStatus.SUCCEEDED),
        )
        assertEquals(SpendOutcome.SUCCESS, result.outcome)
        assertEquals(GenerationStatus.SUCCEEDED, result.status)
        assertEquals(1, result.images.size)
        assertEquals("gen-1", result.generationId)
        assertFalse(result.uncertain)
    }

    @Test
    fun `部分成功仍算成功，但张数如实反映`() {
        // PARTIAL 意味着"出了图但没出满"。仍然按成功计费：
        // 图已经产出了，服务端也确实在做这件事，退款会是少报花费。
        val result = summarize(
            GenerationEvent.Final("gen-1", image(1)),
            GenerationEvent.ItemError("gen-1", 2, error(ErrorCode.SERVER_ERROR)),
            GenerationEvent.Completed("gen-1", GenerationStatus.PARTIAL),
        )
        assertEquals(SpendOutcome.SUCCESS, result.outcome)
        assertEquals(1, result.images.size)
        assertFalse(result.uncertain, "普通服务端错误不属于'可能已计费'")
    }

    @Test
    fun `明确失败的致命错误算明确失败，可退款`() {
        val result = summarize(
            GenerationEvent.Started("gen-1"),
            GenerationEvent.FatalError("gen-1", error(ErrorCode.INVALID_PARAMS)),
        )
        assertEquals(SpendOutcome.FAILED_DEFINITE, result.outcome)
        assertEquals(ErrorCode.INVALID_PARAMS, result.fatal?.code)
        assertFalse(result.uncertain)
    }

    @Test
    fun `余额不足是明确失败，可退款`() {
        val result = summarize(GenerationEvent.FatalError("gen-1", error(ErrorCode.INSUFFICIENT_ANLAS)))
        assertEquals(SpendOutcome.FAILED_DEFINITE, result.outcome)
    }

    @Test
    fun `超时的致命错误算不确定失败，不退款`() {
        // 这是整组用例里最要紧的一条：TIMEOUT_UNCERTAIN 意味着"服务端可能已经接受并计费"。
        // 判成明确失败就会退款，账上于是显示"没花钱"，而实际可能花了。
        val result = summarize(
            GenerationEvent.Started("gen-1"),
            GenerationEvent.FatalError("gen-1", error(ErrorCode.TIMEOUT_UNCERTAIN)),
        )
        assertEquals(SpendOutcome.FAILED_UNCERTAIN, result.outcome)
        assertTrue(result.uncertain)
    }

    @Test
    fun `批量里某张超时但整批完成，仍标出不确定`() {
        // 结论是"拿到了图"（SUCCESS），但"那一张到底扣没扣钱"是未知的，
        // 不能被 SUCCESS 掩盖掉 —— 因此 uncertain 单独成为一个字段。
        val result = summarize(
            GenerationEvent.Final("gen-1", image(1)),
            GenerationEvent.ItemError("gen-1", 2, error(ErrorCode.TIMEOUT_UNCERTAIN)),
            GenerationEvent.Completed("gen-1", GenerationStatus.PARTIAL),
        )
        assertEquals(SpendOutcome.SUCCESS, result.outcome)
        assertTrue(result.uncertain)
    }

    @Test
    fun `失败收尾且没有致命错误时按明确失败处理`() {
        val result = summarize(
            GenerationEvent.Started("gen-1"),
            GenerationEvent.ItemError("gen-1", 1, error(ErrorCode.SERVER_ERROR)),
            GenerationEvent.Completed("gen-1", GenerationStatus.FAILED),
        )
        assertEquals(SpendOutcome.FAILED_DEFINITE, result.outcome)
    }

    @Test
    fun `失败收尾但出现过超时按不确定处理`() {
        val result = summarize(
            GenerationEvent.ItemError("gen-1", 1, error(ErrorCode.TIMEOUT_UNCERTAIN)),
            GenerationEvent.Completed("gen-1", GenerationStatus.FAILED),
        )
        assertEquals(SpendOutcome.FAILED_UNCERTAIN, result.outcome)
        assertTrue(result.uncertain)
    }

    @Test
    fun `空事件流按明确失败处理`() {
        // 一条事件都没有说明流根本没跑起来（本地异常），不涉及服务端计费。
        val result = summarize()
        assertEquals(SpendOutcome.FAILED_DEFINITE, result.outcome)
        assertEquals(0, result.images.size)
    }

    @Test
    fun `中间预览事件不影响结论`() {
        val result = summarize(
            GenerationEvent.Started("gen-1"),
            GenerationEvent.Intermediate("gen-1", 1, "cache/previews/gen-1/0001.png", 0.5),
            GenerationEvent.Final("gen-1", image(1)),
            GenerationEvent.Completed("gen-1", GenerationStatus.SUCCEEDED),
        )
        assertEquals(SpendOutcome.SUCCESS, result.outcome)
        assertEquals(1, result.images.size, "中间预览不算最终图片")
    }
}
