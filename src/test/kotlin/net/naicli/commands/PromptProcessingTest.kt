package net.naicli.commands

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlinx.serialization.json.*
import net.naicli.*
import net.pocketnai.domain.prompt.PromptWeightScanner
import kotlin.test.*

class PromptProcessingTest {
    private fun args(vararg values: String) = Args.parse(values.toList())
    private fun prepare(vararg values: String) = ParamsFromArgs.prepare(args(*values))
    private fun <T> files(block: (java.nio.file.Path) -> T): T {
        val root = Files.createTempDirectory("nai prompt 空格")
        return try { block(root) } finally { root.toFile().deleteRecursively() }
    }
    private fun JsonObject.parameters() = getValue("parameters").jsonObject
    private fun inspect(vararg argv: String): JsonObject = files { root ->
        val context = NaiContext(null, root.toFile(), null) { error("must not open store") }
        val bytes = ByteArrayOutputStream(); val original = System.out
        try {
            System.setOut(PrintStream(bytes, true, "UTF-8"))
            PromptCommand.spec.run(args(*argv), Reporter(true), context)
        } finally { System.setOut(original) }
        Json.parseToJsonElement(bytes.toString("UTF-8")).jsonObject
    }

    @Test fun `UTF8 files normalize BOM and line endings while repeated pieces retain order`() = files<Unit> { root ->
        val p = root.resolve("正向.txt"); Files.writeString(p, "\uFEFFcat\r\nblue eyes\rsmile\r\n")
        val n = root.resolve("负向.txt"); Files.writeString(n, "blurry\r\n")
        val input = PromptInputs.read(args("--prompt-file", p.toString(), "--negative-file", n.toString(),
            "--append", " garden ", "--append", "night", "--negative-append", "bad hands"))
        assertEquals("cat\nblue eyes\nsmile, garden, night", input.positive)
        assertEquals("blurry, bad hands", input.negative)
    }

    @Test fun `conflicting sources and two stdin consumers fail before reading stdin`() {
        val unreadable = object : java.io.InputStream() { override fun read(): Int = error("must not read") }
        for (a in listOf(args("--prompt", "cat", "--prompt-file", "x"),
            args("--prompt", "-", "--negative-file", "-"),
            args("--prompt", "cat", "--negative", "x", "--negative-file", "y"))) {
            assertEquals(ExitCode.USAGE, assertFailsWith<CliFailure> { PromptInputs.read(a, unreadable) }.exitCode)
        }
    }

    @Test fun `stdin supports either caption and inline negative hyphen remains literal`() {
        val input = PromptInputs.read(args("--prompt", "-", "--negative", "-"),
            ByteArrayInputStream("猫\r\nsmile".toByteArray()))
        assertEquals("猫\nsmile", input.positive); assertEquals("-", input.negative)
        val negative = PromptInputs.read(args("--prompt", "cat", "--negative-file", "-"),
            ByteArrayInputStream("blurry".toByteArray()))
        assertEquals("blurry", negative.negative)
    }

    @Test fun `invalid UTF8 missing files empty sources and oversized input are usage errors`() = files<Unit> { root ->
        val bad = root.resolve("bad.txt"); Files.write(bad, byteArrayOf(0xC3.toByte(), 0x28))
        val blank = root.resolve("blank.txt"); Files.writeString(blank, "\uFEFF \r\n\t")
        for (a in listOf(args("--prompt-file", bad.toString()), args("--prompt-file", root.resolve("missing").toString()),
            args("--prompt-file", blank.toString()), args("--prompt", ""), args("--prompt", "x".repeat(PromptInputs.MAX_CHARS + 1)))) {
            assertEquals(ExitCode.USAGE, assertFailsWith<CliFailure> { PromptInputs.read(a) }.exitCode)
        }
        assertFailsWith<CliFailure> { PromptInputs.read(args("--prompt", "-"), ByteArrayInputStream(byteArrayOf())) }
    }

    @Test fun `one prompt seed freezes macros and legacy choices in all caption targets`() = files<Unit> { root ->
        val m = root.resolve("macros.json"); Files.writeString(m, """{"scene":"<forest|garden>","bad":"<fog|rain>","name@tag":"hero"}""")
        val argv = arrayOf("--prompt", "!macro:scene!", "--negative", "!macro:bad!", "--macros-file", m.toString(),
            "--character", "!macro:name@tag!!macro:scene!@left!!!macro:bad!", "--prompt-random-seed", "42", "--seed", "99")
        val first = prepare(*argv); val second = prepare(*argv)
        assertEquals(first.payload(), second.payload())
        assertTrue(first.params.prompt in listOf("forest", "garden"))
        assertTrue(first.params.negativePrompt in listOf("fog", "rain"))
        assertTrue(first.params.characters.single().prompt.startsWith("hero"))
        assertTrue(first.params.characters.single().negativePrompt in listOf("fog", "rain"))
        assertEquals(99L, first.params.baseSeed)
    }

    @Test fun `official randomizers and single pipe captions are not reinterpreted`() {
        val value = "<cat|wolf>, ||<red|blue>|green|| |other caption"
        val p = prepare("--prompt", value, "--prompt-random-seed", "3", "--quality-tags", "none", "--uc-preset", "3")
        assertTrue(p.params.prompt.startsWith("cat,") || p.params.prompt.startsWith("wolf,"))
        assertTrue(p.params.prompt.endsWith("||<red|blue>|green|| |other caption"))
        assertEquals(p.params.prompt, p.payload().getValue("input").jsonPrimitive.content)
    }

    @Test fun `escaped legacy syntax is resolved once and never randomly expanded in rendering`() {
        val p = prepare("--prompt", "\\<cat|wolf>", "--quality-tags", "none", "--uc-preset", "3")
        assertEquals("<cat|wolf>", p.params.prompt)
        assertEquals("<cat|wolf>", p.payload().getValue("input").jsonPrimitive.content)
    }

    @Test fun `missing circular and oversized macro expansion fail locally`() = files<Unit> { root ->
        val m = root.resolve("macros.json")
        for ((json, prompt) in listOf("""{"a":"!macro:a!"}""" to "!macro:a!",
            "{}" to "!macro:missing!",
            Json.encodeToString(JsonObject.serializer(), buildJsonObject { put("a", "x".repeat(70000)) }) to "!macro:a!!macro:a!")) {
            Files.writeString(m, json)
            assertEquals(ExitCode.USAGE, assertFailsWith<CliFailure> {
                prepare("--prompt", prompt, "--macros-file", m.toString())
            }.exitCode)
        }
    }

    @Test fun `malformed macro JSON and empty expanded positive captions are rejected`() = files<Unit> { root ->
        val m = root.resolve("macros.json")
        for (json in listOf("not json", "[]", """{"bad":123}""", """{"bad":""}""")) {
            Files.writeString(m, json)
            assertEquals(ExitCode.USAGE, assertFailsWith<CliFailure> {
                prepare("--prompt", "!macro:bad!", "--macros-file", m.toString())
            }.exitCode)
        }
    }

    @Test fun `model quality precedes explicit Text without decorating later caption chunks`() {
        val p = prepare("--prompt", "poster, Text: HELLO|other caption", "--model", "v4.5-curated", "--negative", "custom|other negative")
        val payload = p.payload(); val text = payload.getValue("input").jsonPrimitive.content
        assertTrue(text.indexOf("rating:general") < text.indexOf("Text:"))
        assertTrue(text.endsWith("|other caption"))
        assertTrue(payload.parameters().getValue("negative_prompt").jsonPrimitive.content.endsWith("custom|other negative"))
        assertFalse(payload.parameters().getValue("qualityToggle").jsonPrimitive.boolean)
    }

    @Test fun `V5 quoted text follows coordinate reading order and is shared by both positive fields`() {
        val p = prepare("--prompt", "poster saying \"TITLE\", don't change", "--model", "v5-full", "--quality-tags", "none",
            "--uc-preset", "3", "--character", "hero saying \"RIGHT\"@right", "--character", "hero saying \"LEFT\"@left")
        val payload = p.payload(); val text = payload.getValue("input").jsonPrimitive.content
        assertTrue(text.endsWith("teXt: TITLE\n\nLEFT\n\nRIGHT"))
        assertEquals(text, payload.parameters().getValue("v4_prompt").jsonObject.getValue("caption").jsonObject.getValue("base_caption").jsonPrimitive.content)
        assertFalse(p.params.prompt.contains("teXt:"))
    }

    @Test fun `V4 does not extract quotes and explicit Text in any positive caption opts out on V5`() {
        for (argv in listOf(arrayOf("--model", "v4.5-full"), arrayOf("--model", "v5-full", "--character", "Text: KEEP@left"))) {
            val p = prepare("--prompt", "poster \"HELLO\"", *argv)
            assertFalse(p.payload().getValue("input").jsonPrimitive.content.contains("teXt:"))
        }
    }

    @Test fun `numeric negative open and wrapped weights retain their literal text and UTF16 offsets`() {
        val text = "🐱, -0.8::feet::, {cat}, 1.2::hair, eyes"
        val spans = PromptWeightScanner.scan(text)
        assertEquals(listOf(-0.8, 1.05, 1.2), spans.map { it.weight })
        assertEquals(4, spans.first().start)
        assertEquals(text.length, spans.last().endExclusive)
        assertEquals("1.2::hair, eyes", text.substring(spans.last().start, spans.last().endExclusive))
    }

    @Test fun `unrecognized weight syntax is preserved and never advertised as validated`() {
        val text = "NaN::cat::, Infinity::eyes::, 1.2.3::hair::, (dog:1.2), {unfinished"
        val data = inspect("--prompt", text, "--quality-tags", "none", "--uc-preset", "3")
        assertEquals(text, data.getValue("payload").jsonObject.getValue("input").jsonPrimitive.content)
        assertEquals("not-performed", data.getValue("weightValidation").jsonPrimitive.content)
    }

    @Test fun `overflowing wrapped weight remains serializable without rewriting the caption`() {
        val text = "{".repeat(14600) + "cat" + "}".repeat(14600)
        val data = inspect("--prompt", text, "--quality-tags", "none", "--uc-preset", "3")
        assertTrue(data.getValue("ok").jsonPrimitive.boolean)
        val weight = data.getValue("weights").jsonArray.single().jsonObject
        assertEquals(JsonNull, weight["weight"])
        assertEquals("non-finite calculated weight", weight.getValue("diagnostic").jsonPrimitive.content)
        assertEquals(text, data.getValue("payload").jsonObject.getValue("input").jsonPrimitive.content)
    }

    @Test fun `image seed changes do not affect any local prompt choices`() {
        val common = arrayOf("--prompt", "<cat|wolf>", "--negative", "<fog|rain>",
            "--character", "<hero|mage>@left!!<blur|noise>", "--prompt-random-seed", "42")
        val first = prepare(*common, "--seed", "99").payload()
        val second = prepare(*common, "--seed", "100").payload()
        assertEquals(first["input"], second["input"])
        for (name in listOf("negative_prompt", "v4_prompt", "v4_negative_prompt")) {
            assertEquals(first.parameters()[name], second.parameters()[name])
        }
        assertEquals(99L, first.parameters().getValue("seed").jsonPrimitive.long)
        assertEquals(100L, second.parameters().getValue("seed").jsonPrimitive.long)
    }

    @Test fun `deep acyclic macros hit a bounded recursion limit`() = files<Unit> { root ->
        val file = root.resolve("macros.json")
        fun chain(depth: Int) = buildJsonObject {
            for (i in 0 until depth) put("m$i", if (i == depth - 1) "cat" else "!macro:m${i + 1}!")
        }
        Files.writeString(file, chain(28).toString())
        assertEquals("cat", prepare("--prompt", "!macro:m0!", "--macros-file", file.toString()).params.prompt)
        Files.writeString(file, chain(34).toString())
        assertEquals(ExitCode.USAGE, assertFailsWith<CliFailure> {
            prepare("--prompt", "!macro:m0!", "--macros-file", file.toString())
        }.exitCode)
    }

    @Test fun `expanded macro delimiters stay in their own caption and cannot inject role fields`() = files<Unit> { root ->
        val file = root.resolve("macros.json")
        Files.writeString(file, """{"positive":"hero@right!!injected","negative":"bad eyes@left!!more bad"}""")
        val p = prepare("--prompt", "BASE_POS", "--negative", "BASE_NEG", "--macros-file", file.toString(),
            "--character", "!macro:positive!@left!!!macro:negative!", "--quality-tags", "none", "--uc-preset", "3")
        val c = p.params.characters.single()
        assertEquals("hero@right!!injected", c.prompt)
        assertEquals("bad eyes@left!!more bad", c.negativePrompt)
        assertEquals(0.15, c.centerX)
        val payload = p.payload()
        assertEquals("BASE_POS", payload.getValue("input").jsonPrimitive.content)
        assertEquals("BASE_NEG", payload.parameters().getValue("negative_prompt").jsonPrimitive.content)
        val captions = payload.parameters().getValue("v4_negative_prompt").jsonObject.getValue("caption").jsonObject
        assertEquals("bad eyes@left!!more bad", captions.getValue("char_captions").jsonArray.single().jsonObject.getValue("char_caption").jsonPrimitive.content)
    }

    @Test fun `stable preset IDs retain old indices and V4 light aliases standard`() {
        val p = prepare("--prompt", "cat", "--model", "v4.5-full", "--uc-preset", "4", "--quality-tags", "light")
        val parameters = p.payload().parameters()
        assertEquals("furryFocus", parameters.getValue("ucPresetId").jsonPrimitive.content)
        assertEquals("standard", parameters.getValue("qualityPresetId").jsonPrimitive.content)
        assertEquals(4, parameters.getValue("params_version").jsonPrimitive.int)
        assertFalse(parameters.containsKey("ucPreset"))
        assertFailsWith<CliFailure> { prepare("--prompt", "cat", "--model", "v4.5-curated", "--uc-preset", "4") }
    }

    @Test fun `offline command never opens credential store and reports resolved character weights`() = files<Unit> { root ->
        val context = NaiContext(null, root.toFile(), null) { error("must not open store") }
        val bytes = ByteArrayOutputStream(); val original = System.out
        try {
            System.setOut(PrintStream(bytes, true, "UTF-8"))
            PromptCommand.spec.run(args("--prompt", "cat", "--character", "1.2::hero::@left!!-0.8::feet::"), Reporter(true), context)
        } finally { System.setOut(original) }
        val data = Json.parseToJsonElement(bytes.toString("UTF-8")).jsonObject
        assertTrue(data.getValue("offline").jsonPrimitive.boolean)
        assertTrue(data.getValue("ok").jsonPrimitive.boolean)
        assertEquals(2, data.getValue("weights").jsonArray.size)
        assertTrue(root.toFile().listFiles()!!.isEmpty())
        assertFailsWith<CliFailure> { PromptCommand.spec.run(args("--prompt", "cat", "--unknown", "x"), Reporter(true), context) }
    }
}
