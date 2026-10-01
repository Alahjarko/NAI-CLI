package net.naicli.commands

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.naicli.CliFailure
import net.naicli.ExitCode
import net.naicli.OptionSpec
import net.pocketnai.domain.model.CharacterPosition
import net.pocketnai.domain.model.CharacterPrompt

/**
 * `--character` → [CharacterPrompt]，也就是 MacNAI 界面里的「独立角色」。
 *
 * 一个角色 = 一段只属于它的提示词（长相、动作、姿势）+ 一个站位中心点 + 可选的独立负面。
 * 序列化落在 `v4_prompt.caption.char_captions` 与 `v4_negative_prompt.caption.char_captions`，
 * 由 `NovelAiRequestBuilder.captionBlock` 负责；本文件只管解析与校验。
 *
 * ## 语法
 * ```
 * 提示词[@站位][!!独立负面]
 * ```
 * 站位取五档预设（`left` / `mid-left` / `center` / `mid-right` / `right`，也认中文名与枚举名），
 * 或显式中心坐标 `X,Y`（0..1，缺省 `center`）。字段顺序固定：站位在前、负面在后。
 *
 * ## 为什么校验必须写在这里
 * `char_captions` 的坐标在 MacNAI 里是**一路裸传进请求体**的：界面上那五档按钮产不出非法值，
 * 所以领域层从没为它写过校验（`ModelProfile.validate` 里没有 characters 这一项，
 * `GenerationRequest.validate` 里也没有）。命令行能产出任何值，于是范围检查只能加在这一层 ——
 * 否则 `--character 'x@2,0.5'` 会被原样发出去，换回一张构图完全不对的图，且没有任何报错。
 * 这与 [ParamsFromArgs] 拒绝让 `normalize` 静默改尺寸是同一条原则。
 *
 * ## 负面分隔符为什么是 `!!` 而不是 `!`
 * 单个 `!` 在自由文本提示词里太容易出现，一旦撞上就会把提示词的后半段**静默**当成独立负面
 * 发出去 —— 图照出，只是画错了东西，最难查。`!!` 几乎不会自然出现。
 * 站位用 `@`：撞车概率低得多，而且撞了会走到"站位不认得"这条硬错误上（见 [resolvePosition]）。
 */
object CharactersFromArgs {

    const val OPTION: String = "character"

    private const val SYNTAX: String = "提示词[@站位][!!独立负面]"
    private const val POSITION_SEPARATOR: Char = '@'
    private const val NEGATIVE_SEPARATOR: String = "!!"

    /**
     * 站位别名 → 预设。
     *
     * 三类都收：枚举名（`far-left`，与 MacNAI 代码对得上）、中文标签（`左`，与界面对得上）、
     * 短名（`left`，人与 agent 顺手写的那个）。
     *
     * 声明在 [optionSpec] 之前不是排版偏好：object 的属性按声明顺序初始化，
     * 而选项描述里要用到它的 key 集合，放反了会拿到 null（`MODEL_ALIASES` 上踩过一次）。
     */
    private val POSITION_ALIASES: Map<String, CharacterPosition> = buildMap {
        CharacterPosition.entries.forEach { position ->
            put(position.name.lowercase().replace('_', '-'), position)
            put(position.label, position)
        }
        put("left", CharacterPosition.FAR_LEFT)
        put("mid-left", CharacterPosition.MID_LEFT)
        put("center", CharacterPosition.CENTER)
        put("mid-right", CharacterPosition.MID_RIGHT)
        put("right", CharacterPosition.FAR_RIGHT)
    }

    /**
     * 给人和 agent 看的那五个短名。
     *
     * 枚举名（`far-left`）与中文标签（`左`）同样能解析，但不在提示里逐个列出 ——
     * 十一个别名混在一行错误信息里，反而看不出到底该写什么。完整对照表在
     * `nai schema` 的 `multiCharacter.positions` 里，那才是给机器读的。
     */
    private val CANONICAL_POSITIONS: List<String> = listOf("left", "mid-left", "center", "mid-right", "right")

    /** `--character` 在 `nai schema` / `nai help` 里的自我说明。 */
    fun optionSpec(): OptionSpec = OptionSpec(
        OPTION,
        "string",
        "独立角色，可重复（最多 ${CharacterPrompt.MAX_COUNT} 个，出现顺序即 char_captions 下标）。" +
            "格式 '$SYNTAX'；站位取 ${CANONICAL_POSITIONS.joinToString("|")}（也认中文名与枚举名），" +
            "或显式中心坐标 'X,Y'（0..1），缺省 center。不额外花 Anlas。",
    )

    /**
     * 解析全部 `--character`。
     *
     * 数量超限直接拒绝，不像元数据导入那样静默截断到 5 条：调用方写了 6 个角色，
     * 就该知道第 6 个没有被画进去。
     */
    fun parseAll(values: List<String>): List<CharacterPrompt> {
        if (values.size > CharacterPrompt.MAX_COUNT) {
            throw CliFailure(
                ExitCode.USAGE,
                "--$OPTION 最多 ${CharacterPrompt.MAX_COUNT} 个，收到 ${values.size} 个",
                extra = buildJsonObject {
                    put("maxCount", CharacterPrompt.MAX_COUNT)
                    put("received", values.size)
                },
            )
        }
        return values.mapIndexed { index, raw -> parse(raw, index + 1) }
    }

    private fun parse(raw: String, nth: Int): CharacterPrompt {
        val bang = raw.indexOf(NEGATIVE_SEPARATOR)
        val head = if (bang >= 0) raw.substring(0, bang) else raw
        val negative = if (bang >= 0) raw.substring(bang + NEGATIVE_SEPARATOR.length) else ""

        val at = head.lastIndexOf(POSITION_SEPARATOR)
        val prompt = if (at >= 0) head.substring(0, at) else head
        val positionSpec = if (at >= 0) head.substring(at + 1) else null

        // 字段顺序写反（把站位塞到负面里）会静默变成一个带 '@' 的负面提示词。
        // 只在"head 里没有站位、负面里却有"时报错，正常使用不受影响。
        if (positionSpec == null && negative.contains(POSITION_SEPARATOR)) {
            throw usage(nth, "字段顺序应为 '$SYNTAX'，站位不能写在 '$NEGATIVE_SEPARATOR' 之后")
        }
        if (prompt.isBlank() && negative.isBlank()) {
            throw usage(nth, "提示词与独立负面都是空的")
        }
        if (positionSpec != null && positionSpec.isBlank()) {
            throw usage(nth, "'$POSITION_SEPARATOR' 后面缺少站位")
        }

        val position: Pair<Double, Double> = positionSpec?.let { resolvePosition(it, nth) }
            ?: (CharacterPosition.CENTER.x to CharacterPosition.CENTER.y)
        return CharacterPrompt(
            prompt = prompt.trim(),
            negativePrompt = negative.trim(),
            centerX = position.first,
            centerY = position.second,
        )
    }

    private fun resolvePosition(spec: String, nth: Int): Pair<Double, Double> {
        val key = spec.trim()
        // 中文标签没有大小写，`lowercase()` 对它是恒等，因此一次查表就够。
        POSITION_ALIASES[key.lowercase()]?.let { return it.x to it.y }

        val parts = key.split(',')
        if (parts.size != 2) {
            throw usage(
                nth,
                "不认识的站位 '$key'。可用：${CANONICAL_POSITIONS.joinToString(", ")}" +
                    "（也认中文名与枚举名），或显式坐标 'X,Y'",
            )
        }
        val x = parts[0].trim().toDoubleOrNull()
        val y = parts[1].trim().toDoubleOrNull()
        if (x == null || y == null) {
            throw usage(nth, "站位坐标 '$key' 不是两个小数，应形如 '0.15,0.5'")
        }
        // 官方是归一化的画面中心坐标。界面上五档预设的 y 恒为 0.5，但官方网页是 5×5 网格、
        // y 会变，所以两个轴都放行 0..1；超出一律拒 —— 领域层不会替我们夹。
        if (x !in 0.0..1.0 || y !in 0.0..1.0) {
            throw usage(nth, "站位坐标必须在 0..1 之间，收到 '$key'")
        }
        return x to y
    }

    private fun usage(nth: Int, message: String) = CliFailure(
        ExitCode.USAGE,
        "第 $nth 个 --$OPTION 不合法：$message",
        extra = buildJsonObject {
            put("index", nth)
            put("syntax", SYNTAX)
        },
    )

    /** 站位速览，给 `generate --dry-run` 的人类可读输出用。 */
    fun layoutLines(characters: List<CharacterPrompt>): List<String> =
        characters.mapIndexed { index, character ->
            val preset = presetOf(character.centerX, character.centerY)
            val negative = if (character.negativePrompt.isBlank()) "" else "  ‖ 负面：${character.negativePrompt}"
            "  #${index + 1}  x=${"%.2f".format(character.centerX)} y=${"%.2f".format(character.centerY)}" +
                (preset?.let { "（$it）" } ?: "") +
                "  ${character.prompt}$negative"
        }

    /** 只在坐标**正好**等于某档预设时才报预设名：0.20 报成"左"是误导。 */
    private fun presetOf(x: Double, y: Double): String? =
        CharacterPosition.entries.firstOrNull { it.x == x && it.y == y }?.label

    /**
     * `nai schema` 里的独立角色段。
     *
     * agent 靠它拿到语法与**具体坐标**，不必去猜"偏左"是 0.32 还是 0.3 ——
     * 这类常量只存在于 MacNAI 的枚举里，猜错不会报错，只会画出构图不对的图。
     */
    fun schemaJson(): JsonObject = buildJsonObject {
        put("option", "--$OPTION")
        put("syntax", SYNTAX)
        put("maxCount", CharacterPrompt.MAX_COUNT)
        put(
            "cost",
            "免费：官方 Anlas 公式的附加费清单里没有 char_captions 这一项（NovelAiPaidAnlasFormula）",
        )
        put(
            "order",
            "--character 的出现顺序就是 char_captions 的下标；请求里 use_order 恒为 true，" +
                "因此顺序会影响构图",
        )
        put(
            "notPersisted",
            "独立角色**不会**写进历史数据库：MacNAI 的 generations 表没有这一列，" +
                "nai history 看不到它们，也无法从历史里复用",
        )
        put("explicitCoords", "站位也可写 'X,Y'（0..1 的归一化中心点），超出范围直接报错、不夹取")
        put("positions", buildJsonArray {
            CharacterPosition.entries.forEach { position ->
                add(buildJsonObject {
                    put("aliases", buildJsonArray {
                        POSITION_ALIASES.entries
                            .filter { it.value == position }
                            .forEach { add(it.key) }
                    })
                    put("x", position.x)
                    put("y", position.y)
                })
            }
        })
    }
}
