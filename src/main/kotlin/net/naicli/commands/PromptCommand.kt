package net.naicli.commands

import kotlinx.serialization.json.*
import net.naicli.*
import net.pocketnai.domain.prompt.PromptWeightScanner

/** The payload renderer is shared with generate, but this command never opens a context store. */
object PromptCommand {
    private val prettyJson = Json { prettyPrint = true }
    val spec = CommandSpec(
        name = "prompt",
        summary = "完全离线处理提示词：展开本地宏/随机模板、预设与 V5 文字，检查权重和请求体",
        spends = false,
        usage = "nai prompt (--prompt <...> | --prompt-file <UTF-8文件>) [--macros-file <JSON文件>] [--json]",
        options = ParamsFromArgs.options,
    ) { args, reporter, _ ->
        val allowed = ParamsFromArgs.options.map { it.name }.toSet() + GlobalOptions.specs.map { it.name }
        val unknown = args.optionNames() - allowed
        if (unknown.isNotEmpty()) throw CliFailure(ExitCode.USAGE, "prompt 未知选项：${unknown.joinToString { "--$it" }}")
        val prepared = ParamsFromArgs.prepare(args)
        val params = prepared.params
        val payload = prepared.payload()
        val output = buildJsonObject {
            put("offline", true)
            put("model", params.model.apiModelId)
            put("imageSeedMode", params.seedMode.name.lowercase())
            put("imageSeedResolved", params.seedMode == net.pocketnai.domain.model.SeedMode.FIXED)
            args.long("prompt-random-seed")?.let { put("promptRandomSeed", it) }
            put("source", buildJsonObject {
                put("prompt", prepared.source.prompt); put("negativePrompt", prepared.source.negativePrompt)
            })
            put("resolved", buildJsonObject {
                put("prompt", params.prompt); put("negativePrompt", params.negativePrompt)
                put("characters", buildJsonArray {
                    params.characters.forEach { c -> add(buildJsonObject {
                        put("prompt", c.prompt); put("negativePrompt", c.negativePrompt)
                        put("centerX", c.centerX); put("centerY", c.centerY)
                    }) }
                })
            })
            put("weightOffsetUnit", "UTF-16")
            put("weightValidation", "not-performed")
            put("weights", buildJsonArray {
                fun scan(target: String, text: String) {
                    PromptWeightScanner.scan(text).forEach { span -> add(buildJsonObject {
                        put("target", target); put("start", span.start); put("endExclusive", span.endExclusive)
                        if (span.weight.isFinite()) {
                            put("weight", span.weight); put("direction", span.direction.name.lowercase())
                        } else {
                            put("weight", JsonNull); put("direction", JsonNull)
                            put("diagnostic", "non-finite calculated weight")
                        }
                        put("text", text.substring(span.start, span.endExclusive))
                    }) }
                }
                scan("prompt", params.prompt); scan("negativePrompt", params.negativePrompt)
                params.characters.forEachIndexed { i, c ->
                    scan("characters[$i].prompt", c.prompt); scan("characters[$i].negativePrompt", c.negativePrompt)
                }
            })
            put("payload", payload)
        }
        reporter.emit(output) {
            "OFFLINE —— 无凭据、无请求。\n" +
                "正向：${params.prompt}\n负向：${params.negativePrompt}\n" +
                "权重区间：${output.getValue("weights").jsonArray.size}（UTF-16 半开区间，未验证权重语法，不改写文本）\n" +
                "图像 Seed：${params.seedMode}（随机模式在实际生成时才抽取）\n" +
                prettyJson.encodeToString(JsonObject.serializer(), payload)
        }
    }
}
