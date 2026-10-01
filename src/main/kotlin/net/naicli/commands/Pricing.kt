package net.naicli.commands

import net.naicli.CliFailure
import net.naicli.ExitCode
import net.naicli.NaiContext
import net.naicli.blocking
import net.naicli.gate.SpendCost
import net.pocketnai.core.Outcome
import net.pocketnai.domain.billing.AnlasCostCalculator
import net.pocketnai.domain.billing.AnlasPricingContext
import net.pocketnai.domain.billing.GenerationCostEstimate
import net.pocketnai.domain.billing.GenerationKind
import net.pocketnai.domain.billing.NovelAiPaidAnlasFormula
import net.pocketnai.domain.billing.SubscriptionBalance
import net.pocketnai.domain.billing.SubscriptionOverride
import net.pocketnai.domain.billing.SubscriptionStatus
import net.pocketnai.domain.billing.SubscriptionStatusResolver
import net.pocketnai.domain.model.GenerationParams

/**
 * 计价的唯一入口。
 *
 * `cost`（只读试算）与 `generate`（真实提交前的闸门校验）都走这里，
 * 因此"报的价"与"扣的价"不可能脱钩。组装方式逐字段对照
 * `ui/generate/GenerateViewModel.pricingContextOf` —— 那边是界面上给人看的报价，
 * 这边是决定放不放行的报价，两者必须是同一套输入。
 */
object Pricing {

    /**
     * 计价器自己构造，不从 [net.pocketnai.di.AppContainer] 拿。
     *
     * 两者完全等价（AppContainer 里就是 `AnlasCostCalculator(NovelAiPaidAnlasFormula())`），
     * 但走自己这份能让 `nai cost --assume-tier ...` **完全不装配 AppContainer** ——
     * 于是它既不联网也不碰 Keychain，可以在没有凭据的环境里跑。
     */
    private val calculator = AnlasCostCalculator(NovelAiPaidAnlasFormula())

    /**
     * 读一次实时余额（`GET /user/subscription`，只读、不花 Anlas）。
     *
     * **Token 由调用方显式传入**，不走 `AccountBalanceRepository`。两个原因：
     * 1. 那个仓库内部自己从 `credentialStore` 取 Token，会**静默忽略** `--token`，
     *    于是"我以为在用 A 账号查余额"实际查的是 Keychain 里的 B 账号；
     * 2. 它只多提供一层 5 分钟内存缓存，而 CLI 是一次性进程，缓存永远是空的。
     *
     * 更重要的是花钱路径：`generate` 必须用**与出图同一个 Token**算价，
     * 否则会出现"按 A 账号的订阅算出免费、却在 B 账号上真实扣费"。
     */
    fun fetchBalance(context: NaiContext, token: String): Outcome<SubscriptionBalance> = blocking {
        context.container.api.fetchSubscriptionBalance(token)
    }

    fun status(
        context: NaiContext,
        balance: SubscriptionBalance?,
        override: SubscriptionOverride = SubscriptionOverride.AUTO,
    ): SubscriptionStatus = SubscriptionStatusResolver.resolve(
        rawTier = balance?.rawTier,
        accountType = balance?.accountType,
        expiresAtEpochSeconds = balance?.expiresAtEpochSeconds,
        nowEpochSeconds = System.currentTimeMillis() / 1000L,
        // AUTO 时用 MacNAI 设置页里的手动覆盖值（两边共用同一份首选项），
        // 显式指定 --assume-tier 时用命令行给的那个。
        override = if (override == SubscriptionOverride.AUTO) {
            context.container.settingsStore.subscriptionOverride.value
        } else {
            override
        },
    )

    fun estimate(
        context: NaiContext,
        params: GenerationParams,
        balance: SubscriptionBalance?,
        kind: GenerationKind = GenerationKind.TEXT_TO_IMAGE,
        override: SubscriptionOverride = SubscriptionOverride.AUTO,
    ): GenerationCostEstimate {
        val status = status(context, balance, override)
        return calculator.estimate(
            AnlasPricingContext(
                params = params,
                subscriptionTier = status.tier,
                hasSubscription = status.subscribed,
                v5UsageLimit = balance?.v5UsageLimit,
                generationKind = kind,
                // CLI 目前只做纯文生图：没有起点图、没有参考图、没有 Vibe。
                // 因此这三项恒为默认值，strength 也恒为 1.0。
                hasBaseImage = false,
                referenceImageCount = 0,
                vibeCount = 0,
                uncachedVibeCount = 0,
                strengthMultiplier = 1.0,
                pricingPolicyVersion = calculator.policyVersion,
            ),
        )
    }

    /** 把四态报价折成闸门的三态。`UsesV5Allowance` 不扣 Anlas，按免费放行。 */
    fun toSpendCost(estimate: GenerationCostEstimate): SpendCost = when (estimate) {
        is GenerationCostEstimate.Free -> SpendCost.Free
        is GenerationCostEstimate.UsesV5Allowance -> SpendCost.Free
        is GenerationCostEstimate.EstimatedAnlas -> SpendCost.Anlas(estimate.batchTotal)
        is GenerationCostEstimate.Unknown -> SpendCost.Unknown
    }

    /** 报价的人类可读形式。界面上那一套四态文案在 CLI 里的对应物。 */
    fun describe(estimate: GenerationCostEstimate): String = when (estimate) {
        is GenerationCostEstimate.Free -> "免费（订阅权益覆盖，policy ${estimate.policyVersion}）"
        is GenerationCostEstimate.UsesV5Allowance ->
            "不花 Anlas，消耗 V5 独立额度（policy ${estimate.policyVersion}）"

        is GenerationCostEstimate.EstimatedAnlas ->
            "预计 ${estimate.batchTotal} Anlas" +
                "（${estimate.imageCount} 张，其中 ${estimate.freeImageCount} 张由订阅权益免掉）"

        is GenerationCostEstimate.Unknown -> "费用算不出来（${estimate.reason}）—— 闸门会拒绝发起"
    }

    /**
     * `--assume-tier` 的解析：给了它就**不联网**，按指定的订阅等级算价。
     *
     * 用途是让 `nai cost` 在离线或无凭据时也能跑（例如核对参数）。
     * 代价是报价可能与真实账号不符 —— 因此这条路径产出的报价**不能**用于放行花钱调用，
     * `generate` 永远读实时余额。
     */
    fun parseAssumedOverride(raw: String?): SubscriptionOverride = when (raw?.lowercase()) {
        null, "auto" -> SubscriptionOverride.AUTO
        "none" -> SubscriptionOverride.NONE
        "tablet" -> SubscriptionOverride.TABLET
        "scroll" -> SubscriptionOverride.SCROLL
        "opus" -> SubscriptionOverride.OPUS
        else -> throw CliFailure(
            ExitCode.USAGE,
            "--assume-tier 只支持 auto / none / tablet / scroll / opus，收到 '$raw'",
        )
    }
}
