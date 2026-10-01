package net.naicli.commands

import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.naicli.Args
import net.naicli.CliFailure
import net.naicli.CommandSpec
import net.naicli.ExitCode
import net.naicli.NaiContext
import net.naicli.OptionSpec
import net.naicli.Reporter
import net.naicli.blocking
import net.naicli.gate.GrantStore
import net.naicli.gate.SpendCost
import net.naicli.gate.SpendGate
import net.naicli.gate.SpendOutcome
import net.naicli.gate.Ticket
import net.pocketnai.core.ErrorCode
import net.pocketnai.core.LogRedaction
import net.pocketnai.core.Outcome
import net.pocketnai.data.network.NovelAiRequestBuilder
import net.pocketnai.data.repo.GenerationEvent
import net.pocketnai.domain.billing.GenerationCostEstimate
import net.pocketnai.domain.billing.UpscaleCost
import net.pocketnai.domain.model.GeneratedImage
import net.pocketnai.domain.model.GenerationRequest
import net.pocketnai.domain.model.GenerationStatus
import net.pocketnai.domain.model.ModelCatalog
import java.io.File

/**
 * 花钱命令。
 *
 * 两条命令都遵循同一个骨架，顺序不可调换：
 *
 * 1. **先算价**（实时余额 + 与界面同一套公式）；
 * 2. **再过闸门**（[SpendGate.acquire] 预扣额度）；
 * 3. 才发请求；
 * 4. **最后结算**（[SpendGate.settle] 按结果退款并写审计）。
 *
 * 先算价再过闸门是必须的：闸门要拿"这次到底花多少"去比对预算，
 * 而价格算不出来时必须**拒绝**而不是"先发出去看看"（AGENTS.md：费用未知一律不得自动发起）。
 */
object SpendCommands {

    private val gate by lazy { SpendGate(GrantStore.default()) }

    /**
     * `nai generate`：文生图。
     *
     * 结果走 [net.pocketnai.data.repo.GenerationRepository]，因此图片会**同时**落进
     * MacNAI 的数据库与画廊、写一条 `anlas_transactions` 流水 —— 人在应用里能直接看到
     * agent 生成了什么。这是刻意的：CLI 的审计不该只存在于一个 agent 不会去读的日志里。
     */
    val generate = CommandSpec(
        name = "generate",
        summary = "文生图（POST /ai/generate-image）。**会花 Anlas**，必须带 --grant",
        spends = true,
        usage = "nai generate --prompt <...> --grant <id> [--out <目录>] [--dry-run]",
        options = ParamsFromArgs.options + listOf(
            OptionSpec("grant", "string", "由 `nai approve` 签发的花钱凭证 id", required = true),
            OptionSpec("out", "path", "把出图额外复制到这个目录"),
            OptionSpec(
                "dry-run",
                "flag",
                "只打印将要发送的请求体与报价，不发请求、不需要 grant",
            ),
        ),
    ) { args, reporter, context -> runGenerate(args, reporter, context) }

    /**
     * `nai upscale`：4 倍超分。
     *
     * 只接受 MacNAI 历史里的 imageId（用 `nai history` 查），不接受任意路径 ——
     * `GenerationRepository.upscaleImage` 本来就是按 imageId 从应用自己的文件存储里取源图的。
     */
    val upscale = CommandSpec(
        name = "upscale",
        summary = "把历史里的一张图超分放大 4 倍（POST /ai/upscale）。**会花 1-4 Anlas**，必须带 --grant",
        spends = true,
        usage = "nai upscale --image-id <id> --grant <id>",
        options = listOf(
            OptionSpec("image-id", "string", "要放大的图片 id（nai history 可查）", required = true),
            OptionSpec("grant", "string", "由 `nai approve` 签发的花钱凭证 id", required = true),
            OptionSpec("out", "path", "把结果额外复制到这个目录"),
            OptionSpec("dry-run", "flag", "只打印预计花费，不发请求、不需要 grant"),
        ),
    ) { args, reporter, context -> runUpscale(args, reporter, context) }

    val all: List<CommandSpec> = listOf(generate, upscale)

    // ---- generate ----

    private fun runGenerate(args: Args, reporter: Reporter, context: NaiContext) {
        val params = ParamsFromArgs.build(args)
        val profile = ModelCatalog.profileOf(params.model)
        val dryRun = args.has("dry-run")

        // 真实提交前**先**把 Token 定下来，两件事都取决于它：
        // - 花钱命令只认 Keychain，`--token` 必须在这里就被拒（而不是发完请求才发现）；
        // - **算价必须用与出图同一个 Token**，否则会按 A 账号的订阅报"免费"、却在 B 账号上真扣钱。
        // dry-run 不发请求，因此放宽成"能读到就读，读不到按无订阅保守估算"。
        val spendToken = if (dryRun) null else context.tokenForSpend()
        val pricingToken = spendToken ?: runCatching { context.tokenForReadOnly() }.getOrNull()

        // 余额读不到**不阻断**：按"没有订阅"算只会高估花费，闸门因此更严而不是更松。
        val balance = pricingToken?.let { token ->
            runCatching { Pricing.fetchBalance(context, token) }
                .map { (it as? Outcome.Success)?.value }
                .getOrNull()
        }
        val estimate = Pricing.estimate(context, params, balance)

        if (dryRun) {
            val payload: JsonObject = NovelAiRequestBuilder.build(profile, params)
            val characters = params.characters
            reporter.emit(
                buildJsonObject {
                    put("dryRun", true)
                    put("endpoint", "POST ${net.pocketnai.BuildConfig.NOVELAI_API_BASE_URL}/ai/generate-image")
                    put("balanceKnown", balance != null)
                    put("estimate", costJson(estimate))
                    if (characters.isNotEmpty()) {
                        put("characterCount", characters.size)
                        put("characters", buildJsonArray {
                            characters.forEach { character ->
                                add(buildJsonObject {
                                    put("prompt", character.prompt)
                                    put("negativePrompt", character.negativePrompt)
                                    put("centerX", character.centerX)
                                    put("centerY", character.centerY)
                                })
                            }
                        })
                    }
                    put("payload", payload)
                },
            ) {
                val caveat = if (balance == null) "（未读到余额，按无订阅保守估算）" else ""
                val body = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), payload)
                // 站位单独列出来：它是这套参数里唯一"看 JSON 看不出来"的东西 ——
                // char_captions 埋在 v4_prompt 两层之下，而构图对不对全看这几个坐标。
                val layout = if (characters.isEmpty()) {
                    ""
                } else {
                    "\n独立角色（${characters.size} 个，不额外花 Anlas）：\n" +
                        CharactersFromArgs.layoutLines(characters).joinToString("\n") + "\n"
                }
                "DRY RUN —— 没有发出任何生成请求。\n\n" +
                    "端点：POST /ai/generate-image\n" +
                    "报价：${Pricing.describe(estimate)}$caveat\n$layout\n" +
                    "请求体：\n$body"
            }
            return
        }

        val grantId = args.str("grant")
        val ticket = acquireOrAudit(grantId, "generate", Pricing.toSpendCost(estimate))

        val events = try {
            blocking {
                context.container.generationRepository
                    .generate(GenerationRequest(params = params), promptTemplate = params.prompt)
                    .toList()
            }
        } catch (t: Throwable) {
            // 本地异常（磁盘满、数据库锁）不等于"服务端可能已计费"，按明确失败退回额度。
            gate.settle(ticket, "generate", SpendOutcome.FAILED_DEFINITE, "exception=${t.javaClass.simpleName}")
            throw CliFailure(ExitCode.ERROR, "生成过程出现本地异常：${t.javaClass.simpleName}: ${t.message}")
        }

        val result = summarize(events)
        val outcome = result.outcome
        gate.settle(
            ticket,
            "generate",
            outcome,
            "generation=${result.generationId} status=${result.status} ${LogRedaction.describePrompt(params.prompt)}",
        )

        val copied = if (outcome == SpendOutcome.SUCCESS) copyOut(args.str("out"), result.images, context) else emptyList()

        if (outcome != SpendOutcome.SUCCESS) {
            val error = result.fatal
            throw CliFailure(
                ExitCode.ERROR,
                if (outcome == SpendOutcome.FAILED_UNCERTAIN) {
                    "生成超时或断流：服务端**可能已经接受并计费**，额度不予退回（${error?.code ?: result.status}）。"
                } else {
                    "生成失败：${error?.code ?: result.status}"
                },
                extra = buildJsonObject {
                    put("generationId", result.generationId)
                    put("grantId", ticket.grantId)
                    error?.code?.let { put("errorCode", it.name) }
                    error?.correlationId?.let { put("correlationId", it) }
                    error?.detail?.let { put("detail", it) }
                    put("uncertain", outcome == SpendOutcome.FAILED_UNCERTAIN)
                },
            )
        }

        reporter.emit(
            buildJsonObject {
                put("generationId", result.generationId)
                put("status", result.status?.name)
                put("grantId", ticket.grantId)
                put("chargedAnlas", ticket.charge)
                put("uncertain", result.uncertain)
                put("estimate", costJson(estimate))
                put("images", buildJsonArray {
                    result.images.forEach { image ->
                        add(buildJsonObject {
                            put("imageId", image.id)
                            put("ordinal", image.ordinal)
                            put("seed", image.seed)
                            put("width", image.width)
                            put("height", image.height)
                            put("path", context.container.generationRepository.fileOfRelativePath(image.privateFilePath).absolutePath)
                            put("sha256", image.sha256)
                        })
                    }
                })
                if (copied.isNotEmpty()) {
                    put("copiedTo", buildJsonArray { copied.forEach { add(it) } })
                }
            },
        ) {
            val note = if (result.uncertain) {
                "\n⚠ 本次出现过超时/断流：服务端可能已对其中某些图计费，额度未退回。"
            } else {
                ""
            }
            "生成完成（${result.status}）。本次预扣 ${ticket.charge} Anlas，grant ${ticket.grantId}。$note\n" +
                result.images.joinToString("\n") { image ->
                    val path = context.container.generationRepository.fileOfRelativePath(image.privateFilePath)
                    "  #${image.ordinal}  seed=${image.seed}  ${image.width}x${image.height}  $path"
                } +
                if (copied.isNotEmpty()) "\n已复制到：\n" + copied.joinToString("\n") { "  $it" } else ""
        }
    }

    // ---- upscale ----

    private fun runUpscale(args: Args, reporter: Reporter, context: NaiContext) {
        val imageId = args.req("image-id")
        val repository = context.container.generationRepository
        context.tokenForSpend()

        val detail = blocking { repository.loadDetail(imageId) }
            ?: throw CliFailure(ExitCode.USAGE, "找不到图片记录：$imageId（用 `nai history` 查 id）")

        val width = detail.image.width
        val height = detail.image.height
        val cost = UpscaleCost.anlas(width, height)
            ?: throw CliFailure(
                ExitCode.USAGE,
                "这张图超出官方超分价格表（源图 ${width}×${height}，面积上限 ${UpscaleCost.MAX_SOURCE_AREA}），官方前端同样会禁用按钮。",
            )

        if (args.has("dry-run")) {
            reporter.emit(
                buildJsonObject {
                    put("dryRun", true)
                    put("imageId", imageId)
                    put("sourceWidth", width)
                    put("sourceHeight", height)
                    put("scaleFactor", UpscaleCost.SCALE_FACTOR)
                    put("estimatedAnlas", cost)
                },
            ) {
                "DRY RUN —— 没有发出任何请求。\n源图 ${width}×${height} → 固定放大 ${UpscaleCost.SCALE_FACTOR} 倍，预计 $cost Anlas。"
            }
            return
        }

        val ticket = acquireOrAudit(args.str("grant"), "upscale", SpendCost.Anlas(cost))

        val result = try {
            blocking { repository.upscaleImage(imageId) }
        } catch (t: Throwable) {
            gate.settle(ticket, "upscale", SpendOutcome.FAILED_DEFINITE, "exception=${t.javaClass.simpleName}")
            throw CliFailure(ExitCode.ERROR, "超分过程出现本地异常：${t.javaClass.simpleName}: ${t.message}")
        }

        when (result) {
            is Outcome.Success -> {
                gate.settle(ticket, "upscale", SpendOutcome.SUCCESS, "source=$imageId result=${result.value}")
                val newImage = blocking { repository.loadDetail(result.value) }?.image
                val path = newImage?.let { repository.fileOfRelativePath(it.privateFilePath) }
                val copied = copyOut(args.str("out"), listOfNotNull(newImage), context)

                reporter.emit(
                    buildJsonObject {
                        put("sourceImageId", imageId)
                        put("resultImageId", result.value)
                        put("chargedAnlas", ticket.charge)
                        put("grantId", ticket.grantId)
                        put("path", path?.absolutePath)
                        if (copied.isNotEmpty()) put("copiedTo", buildJsonArray { copied.forEach { add(it) } })
                    },
                ) {
                    "超分完成，消耗 ${ticket.charge} Anlas。\n结果：${path?.absolutePath ?: result.value}" +
                        if (copied.isNotEmpty()) "\n已复制到：\n" + copied.joinToString("\n") { "  $it" } else ""
                }
            }

            is Outcome.Failure -> {
                val uncertain = result.error.code == ErrorCode.TIMEOUT_UNCERTAIN
                gate.settle(
                    ticket,
                    "upscale",
                    if (uncertain) SpendOutcome.FAILED_UNCERTAIN else SpendOutcome.FAILED_DEFINITE,
                    "source=$imageId errorCode=${result.error.code}",
                )
                throw CliFailure(
                    ExitCode.ERROR,
                    if (uncertain) "超分超时：服务端**可能已经计费**，额度不予退回。" else "超分失败：${result.error.code}",
                    extra = buildJsonObject {
                        put("errorCode", result.error.code.name)
                        result.error.correlationId?.let { put("correlationId", it) }
                        result.error.detail?.let { put("detail", it) }
                        put("grantId", ticket.grantId)
                        put("uncertain", uncertain)
                    },
                )
            }
        }
    }

    // ---- 共用 ----

    /** 闸门拒绝时也要留一行审计，否则"被拦下的尝试"在账上是空白。 */
    private fun acquireOrAudit(grantId: String?, command: String, cost: SpendCost): Ticket = try {
        gate.acquire(grantId, command, cost)
    } catch (failure: CliFailure) {
        gate.auditRefusal(command, "reason=${failure.message} grant=${grantId ?: "-"}")
        throw failure
    }

    internal data class RunSummary(
        val generationId: String?,
        val status: GenerationStatus?,
        val images: List<GeneratedImage>,
        val fatal: net.pocketnai.core.AppError?,
        val outcome: SpendOutcome,
        /**
         * 本次是否出现过 `TIMEOUT_UNCERTAIN`（超时/断流，服务端可能已接受并计费）。
         *
         * 即使整批最终判成功也要如实报出来：批量里有一张超时、其余成功时，
         * 结论是"拿到了图"，但"那一张到底扣没扣钱"是未知的，不该被 SUCCESS 掩盖掉。
         */
        val uncertain: Boolean,
    )

    /**
     * 把事件流折成一个结论。
     *
     * `internal` 是为了能被单测覆盖：这段逻辑决定**退不退款**，而真实生成要花钱、
     * 不能拿真账号试，所以它必须能在离线状态下被钉住。
     */
    internal fun summarize(events: List<GenerationEvent>): RunSummary {
        var generationId: String? = null
        var status: GenerationStatus? = null
        var fatal: net.pocketnai.core.AppError? = null
        var uncertain = false
        val images = mutableListOf<GeneratedImage>()

        events.forEach { event ->
            generationId = event.generationId
            when (event) {
                is GenerationEvent.Final -> images += event.image
                is GenerationEvent.FatalError -> {
                    fatal = event.error
                    if (event.error.code == ErrorCode.TIMEOUT_UNCERTAIN) uncertain = true
                }

                is GenerationEvent.ItemError ->
                    if (event.error.code == ErrorCode.TIMEOUT_UNCERTAIN) uncertain = true

                is GenerationEvent.Completed -> status = event.status
                else -> Unit
            }
        }

        val outcome = when {
            fatal != null -> if (uncertain) SpendOutcome.FAILED_UNCERTAIN else SpendOutcome.FAILED_DEFINITE
            status == GenerationStatus.SUCCEEDED || status == GenerationStatus.PARTIAL -> SpendOutcome.SUCCESS
            uncertain -> SpendOutcome.FAILED_UNCERTAIN
            else -> SpendOutcome.FAILED_DEFINITE
        }
        return RunSummary(generationId, status, images, fatal, outcome, uncertain)
    }

    private fun copyOut(outDir: String?, images: List<GeneratedImage>, context: NaiContext): List<String> {
        if (outDir.isNullOrBlank() || images.isEmpty()) return emptyList()
        val target = File(outDir)
        if (!target.exists() && !target.mkdirs()) {
            throw CliFailure(ExitCode.ERROR, "无法创建输出目录：${target.absolutePath}")
        }
        val repository = context.container.generationRepository
        return images.mapNotNull { image ->
            val source = repository.fileOfRelativePath(image.privateFilePath)
            if (!source.isFile) return@mapNotNull null
            // 文件名带上 sha256 前 12 位：同一批多张图不会互相覆盖，
            // 而且人能从文件名认出这是哪一张（历史里的 imageId 是 UUID，认不出来）。
            val destination = File(target, "${image.seed ?: "noseed"}_${image.sha256.take(12)}.png")
            source.copyTo(destination, overwrite = true)
            destination.absolutePath
        }
    }

    private fun costJson(estimate: GenerationCostEstimate): JsonObject = buildJsonObject {
        put("kind", estimate::class.simpleName)
        put("policyVersion", estimate.policyVersion)
        put("description", Pricing.describe(estimate))
        if (estimate is GenerationCostEstimate.EstimatedAnlas) {
            put("estimatedAnlas", estimate.batchTotal)
            put("imageCount", estimate.imageCount)
            put("freeImageCount", estimate.freeImageCount)
        }
        put("gateAllows", estimate !is GenerationCostEstimate.Unknown)
    }
}
