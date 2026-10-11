package net.naicli.commands

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.naicli.Args
import net.naicli.CliFailure
import net.naicli.ExitCode
import net.naicli.OptionSpec
import net.pocketnai.domain.model.GenerationParams
import net.pocketnai.domain.model.ImageModel
import net.pocketnai.domain.model.ImageOrientation
import net.pocketnai.domain.model.ImageSizePreset
import net.pocketnai.domain.model.ModelCatalog
import net.pocketnai.domain.model.ModelProfile
import net.pocketnai.domain.model.NoiseSchedule
import net.pocketnai.domain.model.ParamViolation
import net.pocketnai.domain.model.QualityTagsOption
import net.pocketnai.domain.model.ResolutionTier
import net.pocketnai.domain.model.Sampler
import net.pocketnai.domain.model.SeedMode
import net.pocketnai.domain.prompt.PromptTemplates
import net.pocketnai.domain.model.GenerationRequest
import net.pocketnai.data.network.NovelAiRequestBuilder

/**
 * 命令行选项 → [GenerationParams]。
 *
 * `cost` 与 `generate` 共用这一份，两者因此**永远算的是同一组参数** ——
 * 报价与实际提交脱钩是这个项目里最容易出的错（AGENTS.md 专门写过一条：
 * 模板与 params 必须同时写），在 CLI 里从源头上只留一个入口。
 */
object ParamsFromArgs {

    /**
     * 模型别名：既认 API id，也认短名，省得调用方去记 `nai-diffusion-4-5-curated`。
     *
     * 声明在 [options] 之前不是排版偏好：object 的属性按声明顺序初始化，
     * 而 [options] 里的枚举提示要用到它的 key 集合，放反了会拿到 null。
     */
    private val MODEL_ALIASES: Map<String, ImageModel> = buildMap {
        ImageModel.entries.forEach { model ->
            put(model.apiModelId, model)
            put(model.name.lowercase().replace('_', '-'), model)
            put(model.shortKey(), model)
        }
    }

    val options: List<OptionSpec> = listOf(
        OptionSpec("prompt", "string", "提示词；也可用位置参数、--prompt-file 或标准输入（--prompt -）；必须选择一个来源"),
        OptionSpec("negative", "string", "负面提示词", default = "（空）"),
        OptionSpec("prompt-file", "path", "UTF-8 正向提示词文件，与 --prompt/位置提示词互斥；- 表示标准输入"),
        OptionSpec("negative-file", "path", "UTF-8 负向提示词文件，与 --negative 互斥；- 表示标准输入"),
        OptionSpec("append", "string", "追加正向片段，可重复，按出现顺序用 PocketNAI 拼接规则连接"),
        OptionSpec("negative-append", "string", "追加负向片段，可重复"),
        OptionSpec("macros-file", "path", "本地 UTF-8 JSON 宏文件：名称到文本的对象，不读取账号收藏"),
        OptionSpec("prompt-random-seed", "int", "本地 <a|b> 展开的随机种子，与图像 --seed 独立；官方 ||a|b|| 保留"),
        OptionSpec(
            "model",
            "enum:${MODEL_ALIASES.keys.sorted().joinToString("|")}",
            "图像模型",
            default = ImageModel.DEFAULT.apiModelId,
        ),
        OptionSpec("preset", "enum:normal|large", "官方分辨率档位；给了 --width/--height 时忽略", default = "normal"),
        OptionSpec("orientation", "enum:portrait|landscape|square", "画幅方向；缺省用该模型的默认方向"),
        OptionSpec("width", "int", "自定义画布宽（64 的倍数，受模型尺寸约束）"),
        OptionSpec("height", "int", "自定义画布高（64 的倍数，受模型尺寸约束）"),
        OptionSpec("count", "int", "一次生成几张", default = "1（上限 4）"),
        OptionSpec("steps", "int", "采样步数", default = "23"),
        OptionSpec("guidance", "double", "Prompt Guidance（请求里的 scale）", default = "7.0"),
        OptionSpec("cfg-rescale", "double", "CFG Rescale", default = "0.0"),
        OptionSpec("sampler", "string", "采样器，接受 API 值或枚举名", default = "k_euler_ancestral"),
        OptionSpec("schedule", "string", "噪声调度，接受 API 值或枚举名", default = "karras"),
        OptionSpec("seed", "int", "固定 seed；不给则每次随机抽取（抽出的值会同时进请求与历史）"),
        OptionSpec("quality-tags", "enum:standard|light|none", "模型质量标签；V4.5 的 light 兼容为 standard；none 不追加", default = "standard"),
        OptionSpec("uc-preset", "int", "0/1/2/3 = Heavy/Light/Human Focus/None；支持的模型可用 4 = Furry Focus", default = "0"),
        CharactersFromArgs.optionSpec(),
    )

    fun modelAliases(): List<String> = MODEL_ALIASES.keys.sorted()

    fun resolveModel(args: Args): ImageModel {
        val raw = args.str("model") ?: return ImageModel.DEFAULT
        return MODEL_ALIASES[raw.lowercase()]
            ?: throw CliFailure(
                ExitCode.USAGE,
                "不认识的模型 '$raw'。可用：${MODEL_ALIASES.keys.sorted().joinToString(", ")}",
            )
    }

    data class Prepared(
        val source: GenerationParams,
        val frozen: PromptTemplates.Frozen,
    ) {
        val params: GenerationParams get() = frozen.params
        fun payload() = NovelAiRequestBuilder.build(
            ModelCatalog.profileOf(params.model), GenerationRequest(params = params),
            upstreamImages = emptyMap(), preparedPrompts = frozen.prepared,
        )
    }

    fun build(args: Args): GenerationParams = prepare(args).params

    fun prepare(args: Args): Prepared {
        val model = resolveModel(args)
        val profile = ModelCatalog.profileOf(model)
        val input = PromptInputs.read(args)
        val prompt = input.positive

        val params = GenerationParams(
            model = model,
            prompt = prompt,
            negativePrompt = input.negative,
            size = resolveSize(args, profile),
            sampleCount = (args.int("count") ?: profile.defaultSampleCount)
                .also { requireRange(it, 1, profile.maxSampleCount, "--count") },
            steps = (args.int("steps") ?: profile.defaultSteps)
                .also { requireRange(it, profile.stepsRange.min.toInt(), profile.stepsRange.max.toInt(), "--steps") },
            guidance = (args.dbl("guidance") ?: profile.defaultGuidance)
                .also { requireRange(it, profile.guidanceRange.min, profile.guidanceRange.max, "--guidance") },
            cfgRescale = (args.dbl("cfg-rescale") ?: profile.defaultCfgRescale)
                .also { requireRange(it, profile.cfgRescaleRange.min, profile.cfgRescaleRange.max, "--cfg-rescale") },
            sampler = args.str("sampler")?.let(::parseSampler) ?: profile.defaultSampler,
            noiseSchedule = args.str("schedule")?.let(::parseSchedule) ?: profile.defaultNoiseSchedule,
            seedMode = if (args.str("seed") != null) SeedMode.FIXED else SeedMode.RANDOM,
            baseSeed = args.long("seed")?.also {
                if (it < 0 || it > GenerationParams.MAX_SEED) {
                    throw CliFailure(ExitCode.USAGE, "--seed 必须在 0..${GenerationParams.MAX_SEED} 之间")
                }
            } ?: 0L,
            qualityTags = args.str("quality-tags")?.let(::parseQualityTags) ?: profile.defaultQualityTags,
            undesiredContentPresetIndex = (args.int("uc-preset") ?: profile.defaultUndesiredContentPresetIndex)
                .also { index ->
                    if (profile.undesiredContentPresets.none { it.index == index }) {
                        throw CliFailure(ExitCode.USAGE, "--uc-preset 不适用于所选模型，可用：${profile.undesiredContentPresets.joinToString { it.index.toString() }}")
                    }
                },
            // 独立角色（char_captions）：下面的 validate 与 normalize **都不碰**它 ——
            // 领域层假设这些值来自界面上的五档按钮，因此从没为它写过校验。
            // 命令行是唯一的把关处，见 CharactersFromArgs。
            characters = CharactersFromArgs.parseAll(args.all(CharactersFromArgs.OPTION)),
        )

        // CLI 与界面有一处本质差别：界面的控件本来就产不出非法值，而命令行可以。
        // `normalize` 的设计是"把非法值修好"——非法尺寸**静默换成模型默认尺寸**、
        // 越界数值**静默夹到边界**、不支持的采样器组合**静默换回默认**。
        // 在界面上这没问题，在 CLI 上则是灾难：`--width 1000` 会安静地在 832×1216 出图，
        // 调用方（AI agent）拿到一张与自己请求不符的图，而且**没有任何报错**。
        // 因此这里先按**原始值**校验、拒绝，再 normalize。
        //
        // PromptTooLong 按软限制处理（只提示不拦），与 `GenerationRepository.generate` 一致：
        // 那边同样把它从阻塞项里 filterNot 掉了。
        val blocking = profile.validate(params).filterNot { it is ParamViolation.PromptTooLong }
        if (blocking.isNotEmpty()) {
            throw CliFailure(
                ExitCode.USAGE,
                "参数不合法：${describe(blocking)}",
                extra = buildJsonObject { put("model", model.apiModelId) },
            )
        }
        if (params.prompt.length > profile.promptSoftLimitChars) {
            System.err.println(
                "提示：提示词 ${params.prompt.length} 字，超过 ${profile.model.apiModelId} 的软上限 " +
                    "${profile.promptSoftLimitChars} 字（不阻断提交）。",
            )
        }
        val source = profile.normalize(params)
        val frozen = try {
            PromptTemplates.prepare(source, PromptInputs.macros(args),
                args.long("prompt-random-seed")?.let { kotlin.random.Random(it) } ?: kotlin.random.Random.Default)
        } catch (failure: IllegalArgumentException) {
            throw CliFailure(ExitCode.USAGE, "提示词宏展开失败：${failure.message}")
        }
        if (frozen.params.prompt.isBlank()) throw CliFailure(ExitCode.USAGE, "展开后的正向提示词不能为空")
        return Prepared(source, frozen)
    }

    private fun resolveSize(args: Args, profile: ModelProfile): ImageSizePreset {
        val width = args.int("width")
        val height = args.int("height")
        if (width != null || height != null) {
            if (width == null || height == null) {
                throw CliFailure(ExitCode.USAGE, "--width 与 --height 必须同时给出")
            }
            return ImageSizePreset(width, height)
        }
        val tier = when (args.str("preset")?.lowercase() ?: "normal") {
            "normal" -> ResolutionTier.NORMAL
            "large" -> ResolutionTier.LARGE
            else -> throw CliFailure(ExitCode.USAGE, "--preset 只支持 normal / large")
        }
        val orientation = args.str("orientation")?.lowercase()?.let { raw ->
            ImageOrientation.entries.firstOrNull { it.name.lowercase() == raw }
                ?: throw CliFailure(ExitCode.USAGE, "--orientation 只支持 portrait / landscape / square")
        } ?: profile.defaultSize.orientation
        return profile.sizeFor(tier, orientation)
            ?: throw CliFailure(
                ExitCode.USAGE,
                "${profile.model.apiModelId} 没有 ${tier.displayName} / ${orientation.name} 这个尺寸组合",
            )
    }

    private fun parseSampler(raw: String): Sampler =
        Sampler.fromApiValue(raw)
            ?: Sampler.entries.firstOrNull { it.name.equals(raw, true) }
            ?: throw CliFailure(
                ExitCode.USAGE,
                "不认识的采样器 '$raw'。可用：${Sampler.entries.joinToString(", ") { it.apiValue }}",
            )

    private fun parseSchedule(raw: String): NoiseSchedule =
        NoiseSchedule.fromApiValue(raw)
            ?: NoiseSchedule.entries.firstOrNull { it.name.equals(raw, true) }
            ?: throw CliFailure(
                ExitCode.USAGE,
                "不认识的噪声调度 '$raw'。可用：${NoiseSchedule.entries.joinToString(", ") { it.apiValue }}",
            )

    private fun parseQualityTags(raw: String): QualityTagsOption =
        QualityTagsOption.entries.firstOrNull { it.name.equals(raw, true) || it.displayName.equals(raw, true) }
            ?: throw CliFailure(ExitCode.USAGE, "--quality-tags 只支持 standard / light / none")

    private fun requireRange(value: Int, min: Int, max: Int, name: String) {
        if (value !in min..max) throw CliFailure(ExitCode.USAGE, "$name 必须在 $min..$max 之间，收到 $value")
    }

    private fun requireRange(value: Double, min: Double, max: Double, name: String) {
        if (value !in min..max) throw CliFailure(ExitCode.USAGE, "$name 必须在 $min..$max 之间，收到 $value")
    }

    private fun describe(violations: List<ParamViolation>): String =
        violations.joinToString("; ") { violation ->
            when (violation) {
                is ParamViolation.UnsupportedCombination ->
                    "${violation.sampler.apiValue} 不支持 ${violation.noiseSchedule.apiValue} 调度"

                is ParamViolation.OutOfRange ->
                    "${violation.field} 超出范围 ${violation.min}..${violation.max}（当前 ${violation.actual}）"

                is ParamViolation.InvalidSize ->
                    "尺寸非法：${violation.width}×${violation.height}" +
                        "（边长需为 64 的倍数，且满足模型的尺寸约束）"

                is ParamViolation.PromptTooLong ->
                    "提示词过长：${violation.length} > ${violation.limit}"
            }
        }

    private fun ImageModel.shortKey(): String = when (this) {
        ImageModel.V4_5_CURATED -> "v4.5-curated"
        ImageModel.V4_5_FULL -> "v4.5-full"
        ImageModel.V5_CURATED -> "v5-curated"
        ImageModel.V5_FULL -> "v5-full"
    }
}
