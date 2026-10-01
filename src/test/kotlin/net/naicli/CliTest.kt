package net.naicli

import net.naicli.commands.ParamsFromArgs
import net.naicli.commands.Pricing
import net.naicli.gate.SpendCost
import net.pocketnai.domain.billing.GenerationCostEstimate
import net.pocketnai.domain.billing.SubscriptionOverride
import net.pocketnai.domain.billing.UnknownCostReason
import net.pocketnai.domain.model.ImageModel
import net.pocketnai.domain.model.SeedMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArgsTest {

    @Test
    fun `空格分隔与等号写法等价`() {
        assertEquals("x", Args.parse(listOf("--prompt", "x")).str("prompt"))
        assertEquals("x", Args.parse(listOf("--prompt=x")).str("prompt"))
    }

    @Test
    fun `未声明为开关的选项会吞掉下一个 token 当取值`() {
        val args = Args.parse(listOf("--prompt", "--steps", "23"))
        assertEquals("--steps", args.str("prompt"))
        assertNull(args.str("steps"))
    }

    @Test
    fun `声明为开关的选项不吃下一个 token`() {
        val args = Args.parse(listOf("--json", "--prompt", "x"), switches = setOf("json"))
        assertTrue(args.has("json"))
        assertEquals("x", args.str("prompt"))
    }

    @Test
    fun `双横线之后全部当位置参数`() {
        val args = Args.parse(listOf("--", "--prompt", "x"))
        assertEquals(listOf("--prompt", "x"), args.positional)
        assertNull(args.str("prompt"))
    }

    @Test
    fun `重复选项 all 取全部 str 取最后一个`() {
        val args = Args.parse(listOf("--tag", "a", "--tag", "b"))
        assertEquals(listOf("a", "b"), args.all("tag"))
        assertEquals("b", args.str("tag"))
    }

    @Test
    fun `缺少取值是用法错误`() {
        val failure = assertFailsWith<CliFailure> { Args.parse(listOf("--prompt")) }
        assertEquals(ExitCode.USAGE, failure.exitCode)
    }

    @Test
    fun `必填项缺失是用法错误`() {
        val failure = assertFailsWith<CliFailure> { Args.parse(emptyList()).req("prompt") }
        assertEquals(ExitCode.USAGE, failure.exitCode)
    }

    @Test
    fun `数值类型不匹配是用法错误`() {
        val args = Args.parse(listOf("--steps", "abc"))
        val failure = assertFailsWith<CliFailure> { args.int("steps") }
        assertEquals(ExitCode.USAGE, failure.exitCode)
    }
}

/**
 * 计价的不变量。
 *
 * 这几条直接对应 AGENTS.md 里"优先级最高"的那一节：免费组合的四个值任何一项偏离
 * 都会产生真实 Anlas 消耗。CLI 的闸门完全建立在这个报价之上，
 * 报价错了闸门就是坏的 —— 因此在这里把它钉死。
 */
class PricingTest {

    private val context = NaiContext(
        explicitToken = null,
        dataDir = java.nio.file.Files.createTempDirectory("nai-cli-pricing").toFile(),
    )

    private fun paramsOf(vararg argv: String) =
        ParamsFromArgs.build(Args.parse(argv.toList()))

    private fun estimate(override: SubscriptionOverride, vararg argv: String) = Pricing.estimate(
        context = context,
        params = paramsOf(*argv),
        balance = null,
        override = override,
    )

    private val freeCombo = arrayOf(
        "--prompt", "1girl",
        "--model", "nai-diffusion-4-5-curated",
        "--preset", "normal",
        "--orientation", "square",
        "--steps", "23",
        "--guidance", "7.0",
    )

    @Test
    fun `Opus 加免费组合报价为免费`() {
        assertIs<GenerationCostEstimate.Free>(estimate(SubscriptionOverride.OPUS, *freeCombo))
    }

    @Test
    fun `没有订阅时同一组参数要花钱`() {
        val result = estimate(SubscriptionOverride.NONE, *freeCombo)
        assertIs<GenerationCostEstimate.EstimatedAnlas>(result)
        assertTrue(result.batchTotal > 0, "无订阅时不可能免费")
    }

    @Test
    fun `步数超过 28 就失去免费资格`() {
        val argv = freeCombo.toMutableList().also { it[it.indexOf("--steps") + 1] = "29" }
        assertIs<GenerationCostEstimate.EstimatedAnlas>(estimate(SubscriptionOverride.OPUS, *argv.toTypedArray()))
    }

    @Test
    fun `面积超过 1024 平方就失去免费资格`() {
        val argv = freeCombo + arrayOf("--preset", "large", "--orientation", "square")
        assertIs<GenerationCostEstimate.EstimatedAnlas>(estimate(SubscriptionOverride.OPUS, *argv))
    }

    @Test
    fun `批量只免一张，其余照价`() {
        // AGENTS.md：**不是"整单免费"**，一次生成多张时只免掉 1 张。
        val result = estimate(SubscriptionOverride.OPUS, *(freeCombo + arrayOf("--count", "4")))
        assertIs<GenerationCostEstimate.EstimatedAnlas>(result)
        assertEquals(4, result.imageCount)
        assertEquals(1, result.freeImageCount)
        assertTrue(result.batchTotal > 0)
    }

    @Test
    fun `不给 seed 时是随机模式`() {
        assertEquals(SeedMode.RANDOM, paramsOf(*freeCombo).seedMode)
    }

    @Test
    fun `给了 seed 就固定下来`() {
        val params = paramsOf(*(freeCombo + arrayOf("--seed", "12345")))
        assertEquals(SeedMode.FIXED, params.seedMode)
        assertEquals(12345L, params.baseSeed)
    }

    @Test
    fun `模型短名与 API id 解析到同一个模型`() {
        assertEquals(
            ImageModel.V4_5_CURATED,
            ParamsFromArgs.resolveModel(Args.parse(listOf("--model", "v4.5-curated"))),
        )
        assertEquals(
            ImageModel.V4_5_CURATED,
            ParamsFromArgs.resolveModel(Args.parse(listOf("--model", "nai-diffusion-4-5-curated"))),
        )
    }

    @Test
    fun `不认识的模型是用法错误`() {
        val failure = assertFailsWith<CliFailure> {
            ParamsFromArgs.resolveModel(Args.parse(listOf("--model", "v9-ultra")))
        }
        assertEquals(ExitCode.USAGE, failure.exitCode)
    }

    @Test
    fun `尺寸非法在本地就被拦下`() {
        // 1000 不是 64 的倍数。normalize 本来会把它**静默换成默认尺寸**，
        // 于是 `--width 1000` 会安静地在 832×1216 出图 —— 必须在 normalize 之前拦住。
        val failure = assertFailsWith<CliFailure> {
            paramsOf(*freeCombo, "--width", "1000", "--height", "1000")
        }
        assertEquals(ExitCode.USAGE, failure.exitCode)
        assertTrue(failure.message!!.contains("尺寸"), "错误信息要指出是尺寸问题：${failure.message}")
    }

    @Test
    fun `不支持的采样器与调度组合被拦下而不是静默换掉`() {
        // DDIM 只支持 native 调度；normalize 会静默换成 karras，
        // 调用方于是拿到一张"用了自己没选的调度"的图。
        val failure = assertFailsWith<CliFailure> {
            paramsOf(*freeCombo, "--sampler", "ddim", "--schedule", "karras")
        }
        assertEquals(ExitCode.USAGE, failure.exitCode)
    }

    @Test
    fun `合法的采样器与调度组合通过`() {
        val params = paramsOf(*freeCombo, "--sampler", "ddim", "--schedule", "native")
        assertEquals(SeedMode.RANDOM, params.seedMode)
    }

    @Test
    fun `只给宽不给高是用法错误`() {
        val failure = assertFailsWith<CliFailure> { paramsOf(*freeCombo, "--width", "1024") }
        assertEquals(ExitCode.USAGE, failure.exitCode)
    }

    @Test
    fun `步数越界在本地就被拦下`() {
        val failure = assertFailsWith<CliFailure> { paramsOf(*freeCombo, "--steps", "999") }
        assertEquals(ExitCode.USAGE, failure.exitCode)
    }

    @Test
    fun `闸门三态映射正确`() {
        assertEquals(
            SpendCost.Free,
            Pricing.toSpendCost(estimate(SubscriptionOverride.OPUS, *freeCombo)),
        )
        val paid = estimate(SubscriptionOverride.NONE, *freeCombo)
        val mapped = Pricing.toSpendCost(paid)
        assertIs<SpendCost.Anlas>(mapped)
        assertEquals((paid as GenerationCostEstimate.EstimatedAnlas).batchTotal, mapped.amount)
    }

    @Test
    fun `费用算不出来时映射成 Unknown，闸门据此拒绝`() {
        // 张数为 0 会让计算器返回 Unknown（参数非法）—— 闸门对它的处理见 SpendGateTest。
        val broken = GenerationCostEstimate.Unknown(policyVersion = "test", reason = UnknownCostReason.INVALID_PARAMETERS)
        assertEquals(SpendCost.Unknown, Pricing.toSpendCost(broken))
    }
}
