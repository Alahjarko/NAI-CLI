package net.naicli.commands

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.naicli.Args
import net.naicli.CliFailure
import net.naicli.ExitCode
import net.naicli.NaiContext
import net.pocketnai.data.network.NovelAiRequestBuilder
import net.pocketnai.domain.billing.GenerationCostEstimate
import net.pocketnai.domain.billing.SubscriptionOverride
import net.pocketnai.domain.model.CharacterPosition
import net.pocketnai.domain.model.CharacterPrompt
import net.pocketnai.domain.model.ModelCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `--character` 的解析。
 *
 * 这些用例大部分在钉"报错"而不是"通过"：领域层对 char_captions 没有任何校验
 * （`ModelProfile.validate` 与 `GenerationRequest.validate` 都不看 characters），
 * 命令行是唯一能把非法值拦在请求之外的地方。拦不住的话不会崩，只会得到一张
 * 构图不对的图 —— 而这种失败在花 Anlas 之后才被发现。
 */
class CharactersParseTest {

    private fun parse(vararg raw: String): List<CharacterPrompt> =
        CharactersFromArgs.parseAll(raw.toList())

    private fun failure(vararg raw: String): CliFailure =
        assertFailsWith<CliFailure> { parse(*raw) }

    @Test
    fun `只有提示词时默认居中`() {
        val character = parse("1girl, blonde hair").single()
        assertEquals("1girl, blonde hair", character.prompt)
        assertEquals("", character.negativePrompt)
        assertEquals(0.5, character.centerX)
        assertEquals(0.5, character.centerY)
    }

    @Test
    fun `五档站位预设解析成官方坐标`() {
        val expected = mapOf(
            "left" to CharacterPosition.FAR_LEFT,
            "mid-left" to CharacterPosition.MID_LEFT,
            "center" to CharacterPosition.CENTER,
            "mid-right" to CharacterPosition.MID_RIGHT,
            "right" to CharacterPosition.FAR_RIGHT,
        )
        expected.forEach { (alias, position) ->
            val character = parse("1girl@$alias").single()
            assertEquals(position.x, character.centerX, "$alias 的 x 不对")
            assertEquals(position.y, character.centerY, "$alias 的 y 不对")
        }
    }

    @Test
    fun `中文标签与枚举名与短名等价`() {
        val byShort = parse("1girl@right").single()
        val byLabel = parse("1girl@右").single()
        val byEnum = parse("1girl@far-right").single()
        assertEquals(byShort.centerX, byLabel.centerX)
        assertEquals(byShort.centerX, byEnum.centerX)
        assertEquals(CharacterPosition.FAR_RIGHT.x, byEnum.centerX)
    }

    @Test
    fun `显式坐标原样透传`() {
        val character = parse("1girl@0.2,0.75").single()
        assertEquals(0.2, character.centerX)
        assertEquals(0.75, character.centerY)
    }

    @Test
    fun `独立负面用双感叹号分隔`() {
        val character = parse("1girl!!bad eyes, extra arms").single()
        assertEquals("1girl", character.prompt)
        assertEquals("bad eyes, extra arms", character.negativePrompt)
    }

    @Test
    fun `单个感叹号留在提示词里`() {
        // 分隔符选 `!!` 而不是 `!` 的全部理由就在这一个用例里：
        // 用 `!` 的话，"1girl! smiling" 会被静默拆成 提示词"1girl" + 负面"smiling"。
        val character = parse("1girl! smiling@left").single()
        assertEquals("1girl! smiling", character.prompt)
        assertEquals("", character.negativePrompt)
        assertEquals(CharacterPosition.FAR_LEFT.x, character.centerX)
    }

    @Test
    fun `站位与负面可以同时给`() {
        val character = parse("1boy, black hair@right!!extra arms").single()
        assertEquals("1boy, black hair", character.prompt)
        assertEquals("extra arms", character.negativePrompt)
        assertEquals(CharacterPosition.FAR_RIGHT.x, character.centerX)
    }

    @Test
    fun `站位写在负面之后是用法错误`() {
        // 不拦的话会得到一个内容为 "extra arms@right" 的负面提示词，站位悄悄丢失。
        val failure = failure("1girl!!extra arms@right")
        assertEquals(ExitCode.USAGE, failure.exitCode)
        assertTrue(failure.message!!.contains("顺序"), "要指出是字段顺序问题：${failure.message}")
    }

    @Test
    fun `坐标越界被拒绝而不是夹取`() {
        listOf("1girl@2,0.5", "1girl@0.5,-1", "1girl@1.5,0.5").forEach { raw ->
            assertEquals(ExitCode.USAGE, failure(raw).exitCode, "'$raw' 应该被拒绝")
        }
    }

    @Test
    fun `不认识的站位被拒绝`() {
        val failure = failure("1girl@middle")
        assertEquals(ExitCode.USAGE, failure.exitCode)
        assertTrue(failure.message!!.contains("站位"), "要指出是站位问题：${failure.message}")
    }

    @Test
    fun `坐标不是两个小数时被拒绝`() {
        assertEquals(ExitCode.USAGE, failure("1girl@abc").exitCode)
        assertEquals(ExitCode.USAGE, failure("1girl@0.2,0.3,0.4").exitCode)
        assertEquals(ExitCode.USAGE, failure("1girl@0.2,x").exitCode)
    }

    @Test
    fun `at 后面缺站位是被拒绝`() {
        assertEquals(ExitCode.USAGE, failure("1girl@").exitCode)
    }

    @Test
    fun `提示词与负面都为空是被拒绝`() {
        // 领域层的 captionBlock 会把 isBlank 的条目**静默过滤掉**：
        // 调用方以为放了个角色，请求里其实一个都没有。
        assertEquals(ExitCode.USAGE, failure("").exitCode)
        assertEquals(ExitCode.USAGE, failure("   ").exitCode)
        assertEquals(ExitCode.USAGE, failure("@left").exitCode)
        assertEquals(ExitCode.USAGE, failure("!!").exitCode)
    }

    @Test
    fun `超过上限被拒绝而不是静默截断`() {
        val tooMany = (1..CharacterPrompt.MAX_COUNT + 1).map { "1girl$it" }
        val failure = assertFailsWith<CliFailure> { parse(*tooMany.toTypedArray()) }
        assertEquals(ExitCode.USAGE, failure.exitCode)
        assertTrue(failure.message!!.contains("${CharacterPrompt.MAX_COUNT}"), failure.message!!)
    }

    @Test
    fun `刚好五个角色通过`() {
        val five = (1..CharacterPrompt.MAX_COUNT).map { "1girl$it" }
        assertEquals(CharacterPrompt.MAX_COUNT, parse(*five.toTypedArray()).size)
    }

    @Test
    fun `首尾空白被裁掉`() {
        val character = parse("  1girl  @  left  !!  bad eyes  ").single()
        assertEquals("1girl", character.prompt)
        assertEquals("bad eyes", character.negativePrompt)
        assertEquals(CharacterPosition.FAR_LEFT.x, character.centerX)
    }

    @Test
    fun `顺序与出现顺序一致`() {
        // 请求里 use_order 恒为 true，char_captions 的下标就是这里的顺序，因此顺序即语义。
        val characters = parse("1girl@left", "1boy@center", "1cat@right")
        assertEquals(listOf("1girl", "1boy", "1cat"), characters.map { it.prompt })
        assertEquals(
            listOf(CharacterPosition.FAR_LEFT.x, CharacterPosition.CENTER.x, CharacterPosition.FAR_RIGHT.x),
            characters.map { it.centerX },
        )
    }
}

/**
 * 从命令行一路走到请求体。
 *
 * 解析对了不等于发对了：中间还隔着 `GenerationParams`、`ModelProfile.normalize`
 * 与 `NovelAiRequestBuilder`。这一段把整条链钉住 —— 尤其是 `normalize`，
 * 它用 `params.copy(...)` 重建对象，一旦哪天它开始"顺手清理" characters，
 * 这里的用例会立刻红。
 */
class CharactersRequestTest {

    private val freeCombo = listOf(
        "--prompt", "1girl",
        "--model", "nai-diffusion-4-5-curated",
        "--preset", "normal",
        "--orientation", "square",
        "--steps", "23",
        "--guidance", "7.0",
    )

    private val context = NaiContext(
        explicitToken = null,
        dataDir = java.nio.file.Files.createTempDirectory("nai-cli-characters").toFile(),
    )

    private fun paramsOf(vararg argv: String) = ParamsFromArgs.build(Args.parse(argv.toList()))

    private fun payloadOf(vararg argv: String): JsonObject {
        val params = paramsOf(*argv)
        return NovelAiRequestBuilder.build(ModelCatalog.profileOf(params.model), params)
    }

    private fun JsonObject.parameters(): JsonObject =
        getValue("parameters").jsonObject

    private fun JsonObject.v4(field: String): JsonObject =
        parameters().getValue(field).jsonObject

    @Test
    fun `独立角色进入 char_captions 并打开 use_coords`() {
        val payload = payloadOf(
            *(freeCombo + listOf(
                "--character", "1girl, blonde hair@left!!bad eyes",
                "--character", "1boy, black hair@right",
            )).toTypedArray(),
        )
        val prompt = payload.v4("v4_prompt")
        assertTrue(prompt.getValue("use_coords").jsonPrimitive.boolean, "有角色时 use_coords 必须为 true")
        assertTrue(prompt.getValue("use_order").jsonPrimitive.boolean)

        val captions = prompt.getValue("caption").jsonObject.getValue("char_captions").jsonArray
        assertEquals(2, captions.size)

        val first = captions[0].jsonObject
        assertEquals("1girl, blonde hair", first.getValue("char_caption").jsonPrimitive.content)
        val center = first.getValue("centers").jsonArray.single().jsonObject
        assertEquals(CharacterPosition.FAR_LEFT.x, center.getValue("x").jsonPrimitive.double)
        assertEquals(CharacterPosition.FAR_LEFT.y, center.getValue("y").jsonPrimitive.double)

        val second = captions[1].jsonObject
        assertEquals("1boy, black hair", second.getValue("char_caption").jsonPrimitive.content)
        assertEquals(
            CharacterPosition.FAR_RIGHT.x,
            second.getValue("centers").jsonArray.single().jsonObject.getValue("x").jsonPrimitive.double,
        )
    }

    @Test
    fun `独立负面进入 v4_negative_prompt 且 legacy_uc 为 false`() {
        val payload = payloadOf(*(freeCombo + listOf("--character", "1girl@left!!bad eyes")).toTypedArray())
        val negative = payload.v4("v4_negative_prompt")
        assertEquals(false, negative.getValue("legacy_uc").jsonPrimitive.boolean)
        val captions = negative.getValue("caption").jsonObject.getValue("char_captions").jsonArray
        assertEquals("bad eyes", captions.single().jsonObject.getValue("char_caption").jsonPrimitive.content)
    }

    @Test
    fun `没有独立角色时 use_coords 为 false 且 char_captions 为空`() {
        val prompt = payloadOf(*freeCombo.toTypedArray()).v4("v4_prompt")
        assertEquals(false, prompt.getValue("use_coords").jsonPrimitive.boolean)
        assertTrue(prompt.getValue("caption").jsonObject.getValue("char_captions").jsonArray.isEmpty())
    }

    @Test
    fun `normalize 不会丢掉独立角色`() {
        val argv = freeCombo + listOf("--character", "1girl@left")
        assertEquals(1, paramsOf(*argv.toTypedArray()).characters.size)
    }

    @Test
    fun `独立角色不产生 Anlas 附加费`() {
        // 官方公式的附加费只有三项：Precise Reference 每张 5、超额 Vibe 每张 2、Vibe 编码每张 2。
        // char_captions 不在其中，因此加不加角色报价必须一模一样 —— 这条同时守住了
        // "免费组合 + 多角色" 仍然免费这个结论（AGENTS.md 的免费四要素不含角色）。
        val plain = paramsOf(*freeCombo.toTypedArray())
        val withCharacters = paramsOf(
            *(freeCombo + listOf("--character", "1girl@left", "--character", "1boy@right")).toTypedArray(),
        )

        assertIs<GenerationCostEstimate.Free>(
            Pricing.estimate(context, plain, balance = null, override = SubscriptionOverride.OPUS),
        )
        assertIs<GenerationCostEstimate.Free>(
            Pricing.estimate(context, withCharacters, balance = null, override = SubscriptionOverride.OPUS),
        )

        val paidPlain = Pricing.estimate(context, plain, balance = null, override = SubscriptionOverride.NONE)
        val paidWithCharacters = Pricing.estimate(
            context,
            withCharacters,
            balance = null,
            override = SubscriptionOverride.NONE,
        )
        assertIs<GenerationCostEstimate.EstimatedAnlas>(paidPlain)
        assertIs<GenerationCostEstimate.EstimatedAnlas>(paidWithCharacters)
        assertEquals(paidPlain.batchTotal, paidWithCharacters.batchTotal, "独立角色不该改变报价")
    }

    @Test
    fun `非法站位不会走到构造请求体这一步`() {
        val failure = assertFailsWith<CliFailure> {
            payloadOf(*(freeCombo + listOf("--character", "1girl@nowhere")).toTypedArray())
        }
        assertEquals(ExitCode.USAGE, failure.exitCode)
    }
}
