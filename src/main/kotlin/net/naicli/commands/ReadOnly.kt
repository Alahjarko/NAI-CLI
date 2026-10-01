package net.naicli.commands

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.naicli.CommandSpec
import net.naicli.OptionSpec
import net.naicli.Reporter
import net.naicli.blocking
import net.naicli.unwrap
import net.pocketnai.core.Outcome
import net.pocketnai.domain.billing.GenerationCostEstimate
import net.pocketnai.domain.billing.SubscriptionOverride
import net.pocketnai.domain.model.ImageModel
import net.pocketnai.domain.model.ModelCatalog
import net.pocketnai.domain.model.shortDisplayName

/**
 * 只读命令。
 *
 * 这一组**永不发起花钱请求**，因此不需要 grant。它们是这个 CLI 对 AI agent 最有价值的部分：
 * agent 可以查余额、查订阅、试算价格、翻历史，把一次生成的所有前提核对清楚，
 * 再把最终那条花钱命令交给持有 grant 的流程去执行。
 */
object ReadOnlyCommands {

    /** `nai status`：验证凭据是否有效（`GET /user/data`）。 */
    val status = CommandSpec(
        name = "status",
        summary = "验证当前凭据并读取账户状态（GET /user/data，只读）",
        spends = false,
        usage = "nai status",
        options = emptyList(),
    ) { _, reporter, context ->
        val token = context.tokenForReadOnly()
        val result = blocking { context.container.api.fetchAccountStatus(token) }.unwrap()
        val hint = context.credentialHint()

        reporter.emit(
            buildJsonObject {
                put("connected", result.connected)
                put("tier", result.tier)
                put("subscriptionActive", result.subscriptionActive)
                put("loginMethod", result.loginMethod)
                hint?.let {
                    put("credentialType", it.type.name)
                    put("tokenLength", it.length)
                    put("tokenFingerprint", it.fingerprint)
                }
            },
        ) {
            Reporter.table(
                listOf(
                    "凭据有效" to result.connected.toString(),
                    "登录方式" to result.loginMethod,
                    "订阅 tier（原始整数）" to result.tier?.toString(),
                    "凭据类型" to hint?.type?.name,
                    "Token 指纹" to hint?.fingerprint,
                ),
            )
        }
    }

    /** `nai balance`：Anlas 余额与订阅权益（`GET /user/subscription`）。 */
    val balance = CommandSpec(
        name = "balance",
        summary = "读取 Anlas 余额、订阅等级与 V5 额度（GET /user/subscription，只读）",
        spends = false,
        usage = "nai balance",
        options = emptyList(),
    ) { _, reporter, context ->
        val token = context.tokenForReadOnly()
        val value = Pricing.fetchBalance(context, token).unwrap()
        val subscription = Pricing.status(context, value)

        reporter.emit(
            buildJsonObject {
                put("subscriptionAnlas", value.subscriptionAnlas)
                put("purchasedAnlas", value.purchasedAnlas)
                put("totalAnlas", value.totalAnlas)
                put("rawTier", value.rawTier)
                put("tierName", subscription.tier.toString())
                put("subscribed", subscription.subscribed)
                put("subscriptionSource", subscription.source.name)
                put("isOpus", subscription.isOpus)
                put("expiresAtEpochSeconds", value.expiresAtEpochSeconds)
                value.v5UsageLimit?.let { usage ->
                    put("v5UsagePercent", usage.rawPercent)
                    put("v5UsageAvailable", usage.available)
                    put("v5UsageIsNegative", usage.isNegative)
                }
            },
        ) {
            Reporter.table(
                listOf(
                    "订阅 Anlas" to value.subscriptionAnlas.toString(),
                    "购买 Anlas" to value.purchasedAnlas.toString(),
                    "合计" to value.totalAnlas.toString(),
                    "订阅等级" to "${subscription.tier}（subscribed=${subscription.subscribed}，来源 ${subscription.source}）",
                    "V5 额度" to value.v5UsageLimit?.let { "${it.rawPercent}%（available=${it.available}）" },
                ),
            )
        }
    }

    /** `nai accounts`：本机已保存的账号（纯本地，不联网）。 */
    val accounts = CommandSpec(
        name = "accounts",
        summary = "列出本机系统安全存储里已保存的账号（不联网）",
        spends = false,
        usage = "nai accounts",
        options = emptyList(),
    ) { _, reporter, context ->
        val store = context.credentialStore
        val list = store.listAccounts()
        val activeId = store.activeAccountId()

        reporter.emit(
            buildJsonObject {
                put("activeAccountId", activeId)
                put("accounts", buildJsonArray {
                    list.forEach { account ->
                        add(buildJsonObject {
                            put("id", account.id)
                            put("name", account.name)
                            put("type", account.type.name)
                            put("tokenFingerprint", account.tokenFingerprint)
                            put("isActive", account.isActive)
                            put("createdAt", account.createdAtEpochMillis)
                        })
                    }
                })
            },
        ) {
            if (list.isEmpty()) {
                "本机没有已保存的账号。"
            } else {
                list.joinToString("\n") { account ->
                    val mark = if (account.id == activeId) "*" else " "
                    "$mark ${account.name}  [${account.type.name}]  指纹 ${account.tokenFingerprint}  id=${account.id}"
                } + "\n\n（* 为当前激活账号；切换账号：nai account use <id>）"
            }
        }
    }

    /** `nai models`：模型目录与能力位（纯本地，不联网、不碰 Keychain）。 */
    val models = CommandSpec(
        name = "models",
        summary = "列出四个图像模型、API id、默认参数与能力位（纯本地）",
        spends = false,
        usage = "nai models",
        options = emptyList(),
    ) { _, reporter, _ ->
        reporter.emit(
            buildJsonObject {
                put("configVersion", ModelCatalog.CONFIG_VERSION)
                put("defaultModel", ImageModel.DEFAULT.apiModelId)
                put("models", buildJsonArray {
                    ModelCatalog.models.forEach { model ->
                        val profile = ModelCatalog.profileOf(model)
                        add(buildJsonObject {
                            put("alias", model.shortDisplayName)
                            put("apiModelId", model.apiModelId)
                            put("inpaintingApiModelId", model.inpaintingApiModelId)
                            put("family", model.family.name)
                            put("defaultSize", "${profile.defaultSize.width}x${profile.defaultSize.height}")
                            put("defaultSteps", profile.defaultSteps)
                            put("defaultGuidance", profile.defaultGuidance)
                            put("maxSampleCount", profile.maxSampleCount)
                            put("supportsImg2Img", profile.supportsImg2Img)
                            put("supportsVibeTransfer", profile.supportsVibeTransfer)
                            put("supportsDirectorReference", profile.supportsDirectorReference)
                            put("supportsInpaint", profile.supportsInpaint)
                        })
                    }
                })
            },
        ) {
            ModelCatalog.models.joinToString("\n\n") { model ->
                val profile = ModelCatalog.profileOf(model)
                """
                ${model.displayName}
                  apiModelId : ${model.apiModelId}
                  默认尺寸   : ${profile.defaultSize.label}   steps ${profile.defaultSteps}   guidance ${profile.defaultGuidance}
                  能力       : img2img=${profile.supportsImg2Img} vibe=${profile.supportsVibeTransfer} precise=${profile.supportsDirectorReference} inpaint=${profile.supportsInpaint}
                """.trimIndent()
            }
        }
    }

    /**
     * `nai tags`：标签建议（`GET /ai/generate-image/suggest-tags`）。
     *
     * 不消耗 Anlas。AGENTS.md 还记着它的第二个用途：这个端点会校验模型 id，
     * 因此也是零成本核对模型 id 是否存在的探针。
     */
    val tags = CommandSpec(
        name = "tags",
        summary = "标签自动补全（GET /ai/generate-image/suggest-tags，不花 Anlas）",
        spends = false,
        usage = "nai tags --prompt <前缀> [--model <模型>] [--limit <N>]",
        options = listOf(
            OptionSpec("prompt", "string", "要补全的标签前缀", required = true),
            OptionSpec("model", "string", "模型（可用 nai models 查别名）", default = ImageModel.DEFAULT.apiModelId),
            OptionSpec("limit", "int", "最多返回几条", default = "20"),
        ),
    ) { args, reporter, context ->
        val token = context.tokenForReadOnly()
        val model = ParamsFromArgs.resolveModel(args)
        val prompt = args.req("prompt")
        val limit = (args.int("limit") ?: 20).coerceAtLeast(1)

        val suggestions = blocking {
            context.container.api.suggestTags(token, model, prompt)
        }.unwrap()

        val shown = suggestions.take(limit)
        reporter.emit(
            buildJsonObject {
                put("model", model.apiModelId)
                put("query", prompt)
                put("suggestions", buildJsonArray { shown.forEach { add(it) } })
                put("total", suggestions.size)
            },
        ) {
            if (shown.isEmpty()) "没有建议。" else shown.joinToString("\n")
        }
    }

    /** `nai history`：本机生成历史与图片路径（纯本地，不联网）。 */
    val history = CommandSpec(
        name = "history",
        summary = "列出本机生成历史与图片的绝对路径（读 MacNAI 的数据库，不联网）",
        spends = false,
        usage = "nai history [--limit <N>] [--generation <id>]",
        options = listOf(
            OptionSpec("limit", "int", "最多返回几张图", default = "20"),
            OptionSpec("generation", "string", "只看某一次生成的图片"),
        ),
    ) { args, reporter, context ->
        val limit = (args.int("limit") ?: 20).coerceAtLeast(1)
        val generationId = args.str("generation")
        val repository = context.container.generationRepository

        val items = blocking { repository.observeGallery().first() }
            .filter { generationId == null || it.generationId == generationId }
            .take(limit)

        reporter.emit(
            buildJsonObject {
                put("count", items.size)
                put("images", buildJsonArray {
                    items.forEach { item ->
                        add(buildJsonObject {
                            put("imageId", item.imageId)
                            put("generationId", item.generationId)
                            put("ordinal", item.ordinal)
                            put("path", repository.fileOf(item).absolutePath)
                            put("width", item.width)
                            put("height", item.height)
                            put("seed", item.seed)
                            put("model", item.model?.apiModelId)
                            put("mode", item.mode.name)
                            put("status", item.status.name)
                            put("favorite", item.favorite)
                            put("createdAt", item.createdAt)
                        })
                    }
                })
            },
        ) {
            if (items.isEmpty()) {
                "没有历史记录。"
            } else {
                items.joinToString("\n") { item ->
                    "${item.createdAt}  ${item.model?.shortDisplayName ?: "?"}  ${item.width}x${item.height}" +
                        "  seed=${item.seed ?: "-"}  ${item.status}\n" +
                        "  ${repository.fileOf(item).absolutePath}"
                }
            }
        }
    }

    /**
     * `nai cost`：试算这次参数要花多少 Anlas（本地公式，不发起生成）。
     *
     * 默认读一次实时余额来定订阅权益；给了 `--assume-tier` 就完全不联网、不碰 Keychain。
     * **它只是试算**：`generate` 在放行前会用实时余额重新算一遍，不采信这里的结论。
     */
    val cost = CommandSpec(
        name = "cost",
        summary = "试算一组生成参数的 Anlas 花费（本地公式；不发起生成）",
        spends = false,
        usage = "nai cost --prompt <...> [--model ...] [--assume-tier none|tablet|scroll|opus]",
        options = ParamsFromArgs.options + listOf(
            OptionSpec(
                "assume-tier",
                "enum:auto|none|tablet|scroll|opus",
                "跳过联网、按指定订阅等级算价。非 auto 时本命令完全离线",
                default = "auto（读实时余额）",
            ),
        ),
    ) { args, reporter, context ->
        val params = ParamsFromArgs.build(args)
        val assumed = Pricing.parseAssumedOverride(args.str("assume-tier"))

        val balance = if (assumed == SubscriptionOverride.AUTO) {
            // 读不到余额不该让试算失败：按"没有订阅"算，并把这件事如实说出来。
            // 这与 AGENTS.md 的"余额读取失败不得阻止生成"是同一个取舍方向，
            // 而且方向是保守的 —— 少算订阅权益只会**高估**花费，不会低估。
            runCatching { Pricing.fetchBalance(context, context.tokenForReadOnly()) }
                .map { (it as? Outcome.Success)?.value }
                .getOrNull()
        } else {
            null
        }

        val estimate = Pricing.estimate(context, params, balance, override = assumed)
        val subscription = Pricing.status(context, balance, assumed)
        val estimated = estimate as? GenerationCostEstimate.EstimatedAnlas

        reporter.emit(
            buildJsonObject {
                put("model", params.model.apiModelId)
                put("width", params.size.width)
                put("height", params.size.height)
                put("steps", params.steps)
                put("guidance", params.guidance)
                put("sampleCount", params.sampleCount)
                put("seedMode", params.seedMode.name)
                // 独立角色是免费的，报价不会因为它们变化；但"没变化"和"根本没解析到"在
                // 输出上长得一模一样，所以把解析到的条数如实报出来。
                put("characterCount", params.characters.size)
                put("subscribed", subscription.subscribed)
                put("tier", subscription.tier.toString())
                put("subscriptionSource", subscription.source.name)
                put("balanceKnown", balance != null)
                put("totalAnlas", balance?.totalAnlas)
                put("policyVersion", estimate.policyVersion)
                put("estimateKind", estimate::class.simpleName)
                put("description", Pricing.describe(estimate))
                put("estimatedAnlas", estimated?.batchTotal)
                put("freeImageCount", estimated?.freeImageCount)
                put("gateWouldAllow", estimate !is GenerationCostEstimate.Unknown)
            },
        ) {
            Reporter.table(
                listOf(
                    "模型" to params.model.displayName,
                    "画布" to "${params.size.width}×${params.size.height}",
                    "steps / guidance" to "${params.steps} / ${params.guidance}",
                    "张数" to params.sampleCount.toString(),
                    // 标签只用两个字：Reporter.table 按字符数补齐，而 CJK 在终端里占两列。
                    "角色" to (params.characters.takeIf { it.isNotEmpty() }?.let { list ->
                        "${list.size} 个（免费）：" +
                            list.joinToString("；") { "${it.prompt}@${it.centerX},${it.centerY}" }
                    } ?: "无"),
                    "订阅" to "${subscription.tier}（subscribed=${subscription.subscribed}，来源 ${subscription.source}）",
                    "余额" to (balance?.totalAnlas?.toString() ?: "未读取"),
                    "报价" to Pricing.describe(estimate),
                ),
            ) + "\n\n提示：这只是试算。generate 放行前会用实时余额重新算一遍。"
        }
    }

    val all: List<CommandSpec> = listOf(status, balance, accounts, models, tags, history, cost)
}
