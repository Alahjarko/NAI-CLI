package net.pocketnai.data.image

import net.pocketnai.core.Hashing
import net.pocketnai.data.network.ZipImageExtractor
import net.pocketnai.domain.image.PixelRegion
import net.pocketnai.domain.image.PixelSize
import net.pocketnai.domain.metadata.PngTextChunks
import net.pocketnai.domain.metadata.PngTextWriter
import net.pocketnai.domain.metadata.PocketNaiOutputMetadata
import java.awt.image.BufferedImage
import java.awt.image.RasterFormatException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import javax.imageio.ImageIO

/**
 * 生成结果的后处理：按自定义分辨率把画布裁成用户要的最终尺寸。
 *
 * ## 为什么在落盘之前做
 * 历史目录里的文件、数据库里的宽高、缩略图缓存与相册导出都以落盘那一刻的文件为准。
 * 先落盘再裁等于要把"已提交"的东西改一遍，而数据库事务、哈希与文件三者的一致性
 * 都要重新保证一次 —— 越晚改越容易留下不一致。
 *
 * ## 不裁切时一个字节都不动
 * 没有裁切请求时直接返回原列表：不重新编码 PNG，因此 SHA-256、体积与压缩痕迹
 * 都与服务端产物完全一致（预设尺寸的历史记录因此完全不受新功能影响）。
 *
 * ## 元数据必须保住
 * 裁切要解码再编码，而重新编码 PNG 会丢掉原图的 `tEXt`。
 * 因此这里先把白名单里的 NovelAI 文本块读出来，裁完再写回，并补一条
 * `pocketnai_output` 说明"这张图被本地裁过、原始画布多大"。
 * 不这么做的话，元数据导入功能会在用户裁过一次之后静默失效。
 *
 * ## 内存
 * 逐张处理并及时回收：四张 2 MP 的图同时驻留就是 ~32 MB 位图内存，
 * 低端机上足够触发 OOM。
 *
 * ## macOS 移植（API 对照）
 * | Android | JVM |
 * |---|---|
 * | `BitmapFactory.decodeFile(path, inPreferredConfig = ARGB_8888)` | `ImageIO.read(file)` |
 * | `Bitmap.createBitmap(src, x, y, w, h)` | `BufferedImage.getSubimage(x, y, w, h)` |
 * | `Bitmap.compress(PNG, 100, out)` | `ImageIO.write(img, "png", out)` |
 * | `Bitmap.recycle()` | 无对应操作，交给 GC |
 *
 * 解码**不做色彩空间转换**：直接把 `ImageIO` 读出来的像素交给编码器，因此
 * "裁切不改像素值"这条不变量在灰度 / 带 alpha / 调色板图上同样成立。
 * 代价是重编码后的 PNG 与原图在字节层面必然不同（编码器不同），这与 Android 侧一致 ——
 * 所以"不裁切"的那条路径才必须原样返回字节，而不是"裁一次恒等变换"。
 */
class OutputImageProcessor {

    /**
     * 对每张图应用裁切。
     *
     * [crop] 为空表示不裁切（返回原列表）；裁不出来（文件坏了）时**保留原图**并如实返回，
     * 不抛异常 —— 一次裁切失败不该让整批生成作废。
     */
    fun apply(
        images: List<ZipImageExtractor.ExtractedImage>,
        crop: PixelRegion?,
        canvas: PixelSize?,
    ): List<ZipImageExtractor.ExtractedImage> {
        if (crop == null || canvas == null) return images
        return images.map { image -> cropImage(image, crop, canvas) ?: image }
    }

    private fun cropImage(
        image: ZipImageExtractor.ExtractedImage,
        crop: PixelRegion,
        canvas: PixelSize,
    ): ZipImageExtractor.ExtractedImage? {
        val original = try {
            image.file.readBytes()
        } catch (e: IOException) {
            return null
        }

        val decoded = decode(image.file) ?: return null
        // 边界防护：服务端返回的尺寸与我们要裁的范围必须自洽，否则宁可不裁。
        if (crop.x + crop.width > decoded.width || crop.y + crop.height > decoded.height) {
            return null
        }

        val cropped = try {
            decoded.getSubimage(crop.x, crop.y, crop.width, crop.height)
        } catch (e: RasterFormatException) {
            // 对应 Android 侧 Bitmap.createBitmap 抛的 IllegalArgumentException：
            // 范围非法（例如负数原点）时同样宁可不裁。
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
        if (cropped == null) {
            return null
        }

        // Android 版在这里 finally { cropped.recycle(); if (cropped !== decoded) decoded.recycle() }。
        // JVM 侧 BufferedImage 没有显式回收，两份都交给 GC。cropped 是 decoded 的**视图**
        // （共享 raster），写文件时按子区域读取，因此不需要先拷一份。
        return try {
            val encoded = encodePng(cropped) ?: return null
            val withMetadata = PngTextWriter.withTextChunks(
                png = encoded,
                chunks = preservedChunks(original) + outputChunk(crop, canvas),
            )
            val bytes = withMetadata
            writeAtomically(image.file, bytes)
            // 裁切不改变像素值，但重新编码后字节数一定变，哈希必须重算。
            image.copy(
                byteSize = bytes.size.toLong(),
                sha256 = Hashing.sha256(bytes),
                width = cropped.width,
                height = cropped.height,
            )
        } catch (e: IOException) {
            null
        }
    }

    /** 只保留白名单里的 NovelAI 文本块，其余（含未知工具的块）一律不带走。 */
    private fun preservedChunks(original: ByteArray): List<PngTextChunks.TextChunk> =
        PngTextWriter.readTextChunks(original).filter { chunk ->
            PocketNaiOutputMetadata.normalizeKeyword(chunk.keyword) in
                PocketNaiOutputMetadata.PRESERVED_KEYWORDS
        }

    private fun outputChunk(crop: PixelRegion, canvas: PixelSize): PngTextChunks.TextChunk =
        PngTextChunks.TextChunk(
            keyword = PocketNaiOutputMetadata.KEYWORD,
            text = PocketNaiOutputMetadata.Entry(
                outputWidth = crop.width,
                outputHeight = crop.height,
                generationWidth = canvas.width,
                generationHeight = canvas.height,
                cropX = crop.x,
                cropY = crop.y,
            ).encode(),
        )

    /**
     * 解码原图。
     *
     * 对照 `BitmapFactory.decodeFile(path, Options(inPreferredConfig = ARGB_8888))`，
     * 但**刻意不把结果规整成 `TYPE_INT_ARGB`**：`ImageIO` 返回的是与文件位深 / 通道数
     * 匹配的图像，裁完直接写回，像素值一个都不动。多走一次 `drawImage` 反而会引入
     * 一次色彩空间转换（CMYK / 带 ICC 的图会改值），也会白白多占一份位图内存。
     */
    private fun decode(file: File): BufferedImage? = try {
        ImageIO.read(file)
    } catch (e: IOException) {
        // 文件损坏 / 不是图片：与 BitmapFactory 返回 null 同义。
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    private fun encodePng(image: BufferedImage): ByteArray? = try {
        ByteArrayOutputStream().use { buffer ->
            // ImageIO.write 找不到 writer 时返回 false，与 compress() 返回 false 同义。
            if (ImageIO.write(image, "png", buffer)) {
                buffer.toByteArray()
            } else {
                null
            }
        }
    } catch (e: OutOfMemoryError) {
        null
    } catch (e: IOException) {
        null
    }

    /** 先写 `.part` 再改名：与文件存储层同一套"不留半个文件"的做法。 */
    private fun writeAtomically(target: File, bytes: ByteArray) {
        val staging = File(target.parentFile, "${target.name}.crop.part")
        staging.outputStream().buffered().use { it.write(bytes) }
        if (!staging.renameTo(target)) {
            staging.copyTo(target, overwrite = true)
            staging.delete()
        }
    }
}
