package net.naicli.commands

import kotlinx.serialization.json.*
import net.naicli.*
import net.pocketnai.domain.model.*
import net.pocketnai.domain.prompt.PromptComposition
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Explicit prompt files only; no application data, native credentials or network access. */
internal object PromptInputs {
    const val MAX_CHARS = 128 * 1024
    private const val MAX_BYTES = MAX_CHARS * 4
    data class Pair(val positive: String, val negative: String)

    fun read(args: Args): Pair = read(args, System.`in`)

    fun read(args: Args, stdin: java.io.InputStream): Pair {
        if ((args.str("prompt-file") != null) && ((args.str("prompt") != null) || args.positional.isNotEmpty())) {
            usage("--prompt-file 与 --prompt/位置提示词互斥")
        }
        if ((args.str("negative-file") != null) && (args.str("negative") != null)) usage("--negative-file 与 --negative 互斥")
        val positive = args.str("prompt-file") ?: args.str("prompt") ?: args.positional.firstOrNull()
            ?: usage("缺少提示词：使用 --prompt、位置提示词或 --prompt-file；- 表示标准输入")
        val negative = args.str("negative-file") ?: args.str("negative").orEmpty()
        val positiveStdin = positive == "-"
        val negativeStdin = (args.str("negative-file") != null) && negative == "-"
        if (positiveStdin && negativeStdin) usage("正向与负向不能同时读取标准输入")
        fun load(value: String, file: Boolean, label: String, fromStdin: Boolean): String = when {
            fromStdin -> decode(stdin.readNBytes(MAX_BYTES + 1), label).trim()
            file -> readFile(value, label).trim()
            else -> bounded(value, label)
        }
        var p = load(positive, (args.str("prompt-file") != null), "正向提示词", positiveStdin)
        var n = load(negative, (args.str("negative-file") != null), "负向提示词", negativeStdin)
        for (piece in args.all("append")) p = bounded(PromptComposition.append(p, piece), "正向提示词")
        for (piece in args.all("negative-append")) n = bounded(PromptComposition.append(n, piece), "负向提示词")
        if (p.isBlank()) usage("正向提示词不能为空")
        return Pair(p, n)
    }

    fun macros(args: Args): List<PromptFavorite> {
        val file = args.str("macros-file") ?: return emptyList()
        if (file == "-") usage("--macros-file 必须是文件，不能读取标准输入")
        val objectValue = try { Json.parseToJsonElement(readFile(file, "宏文件")) as? JsonObject }
            catch (_: kotlinx.serialization.SerializationException) { usage("宏文件必须是有效 JSON 对象") }
            ?: usage("宏文件必须是名称到文本的 JSON 对象")
        return objectValue.map { (name, value) ->
            if (name.isBlank() || name.any { it in "!⌟" }) usage("宏名称不能为空或包含 !/⌟")
            val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: usage("宏文件的每个值必须是字符串")
            PromptFavorite(name, PromptFavoriteKind.PROMPT, name, text, "", PromptTarget.POSITIVE, 0, 0, 0)
        }
    }

    private fun readFile(name: String, label: String): String = try {
        Files.newInputStream(Path.of(name)).use { decode(it.readNBytes(MAX_BYTES + 1), label) }
    } catch (_: java.io.IOException) { usage("无法读取$label 文件") }
      catch (_: java.nio.file.InvalidPathException) { usage("$label 文件路径不合法") }

    private fun decode(bytes: ByteArray, label: String): String {
        if (bytes.size > MAX_BYTES) usage("$label 超过 $MAX_BYTES 字节限制")
        val text = try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) { usage("$label 必须使用有效 UTF-8 编码") }
        return bounded(text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n'), label)
    }

    private fun bounded(value: String, label: String): String {
        if (value.length > MAX_CHARS) usage("$label 超过 $MAX_CHARS 字符限制")
        return value
    }
    private fun usage(message: String): Nothing = throw CliFailure(ExitCode.USAGE, message)
}
