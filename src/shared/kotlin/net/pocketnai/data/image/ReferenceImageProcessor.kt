package net.pocketnai.data.image

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.pocketnai.core.AppError
import net.pocketnai.core.ErrorCode
import net.pocketnai.core.Hashing
import net.pocketnai.core.Outcome
import net.pocketnai.data.files.GenerationFileStore
import net.pocketnai.domain.image.ImageGeometry
import net.pocketnai.domain.image.ImageTransform
import net.pocketnai.domain.image.PixelSize
import net.pocketnai.domain.image.PreparedReference
import net.pocketnai.domain.image.ReferenceImageEncoder
import net.pocketnai.domain.image.ReferenceImageImporter
import net.pocketnai.domain.image.ReferenceSource
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Base64
import javax.imageio.ImageIO
import javax.imageio.ImageReader

/**
 * 参考图处理管线（《参考图功能规划书》5.3）。
 *
 * ## 职责划分：导入时只归一化，提交时才按当前尺寸裁剪
 * 导入（[import]）把用户选的图**原样保留**（只做降采样与统一 PNG），
 * 裁剪与缩放发生在提交时（[encodeBase64]）。这样做的原因是尺寸是可变的：
 * 用户在界面上一改 Resolution，起点图就要按新尺寸重新裁切。
 * 如果导入时就把图裁死，改尺寸时就必须回头找原始图重新处理 ——
 * Android 侧那个 URI 的授权在应用重启后未必还在，macOS 侧虽然路径一直有效，
 * 但"草稿里存的是归一化后的参考图、几何留到提交时算"这条约定两边一致。
 *
 * 代价是每次提交多一次解码与绘制。只有一张图、且在 IO 线程上，可以接受。
 *
 * ## 为什么这里薄
 * 所有几何计算都在 [ImageGeometry]（纯 Kotlin、有单元测试），本类只负责调用平台的
 * 解码 / 绘制 / 编码 API 并把失败翻译成错误码。这样"算错尺寸"这类最难查的问题不会藏在这一层。
 *
 * ## base64 的生命周期
 * [encodeBase64] 返回的字符串只允许用于构造当次请求体，不写数据库、不写日志、不长期驻留
 * （见 AGENTS.md 的安全约束）。
 *
 * ## 已知限制
 * 不处理 EXIF 旋转：相机拍摄的照片若带方向标记，这里会按原始像素方向处理。
 * JVM 的 `ImageIO` 同样不会自动应用 EXIF 方向（它压根不解析 EXIF），因此两边行为一致；
 * 若真机上发现方向不对，再引入显式的 EXIF 解析。
 *
 * ## macOS 移植（API 对照）
 * | Android | JVM |
 * |---|---|
 * | `contentResolver.openInputStream(uri)` | `File(path).inputStream()`（选择器给的就是本机路径） |
 * | `BitmapFactory.Options(inJustDecodeBounds = true)` | `ImageReader.getWidth/getHeight`（只读文件头） |
 * | `BitmapFactory.decodeStream(..., inSampleSize)` | `ImageReader.read` + `setSourceSubsampling` |
 * | `Bitmap.createBitmap(w, h, ARGB_8888)` | `BufferedImage(w, h, TYPE_INT_ARGB)` |
 * | `Canvas.drawColor(Color.BLACK)` | `Graphics2D` + `AlphaComposite.Src` + `fillRect` |
 * | `Canvas.drawBitmap(src, srcRect, dstRect, FILTER_BITMAP_FLAG)` | `Graphics2D.drawImage(...)` + 双线性插值 |
 * | `Bitmap.compress(PNG, 100, out)` | `ImageIO.write(img, "png", out)` |
 * | `Base64.encodeToString(bytes, NO_WRAP)` | `Base64.getEncoder().encodeToString(bytes)` |
 */
class ReferenceImageProcessor(
    private val fileStore: GenerationFileStore,
) : ReferenceImageEncoder, ReferenceImageImporter {

    override suspend fun import(
        source: ReferenceSource,
        transform: ImageTransform?,
    ): Outcome<PreparedReference> = when (source) {
        is ReferenceSource.PickedUri -> importPickedFile(source.uri, transform)
        is ReferenceSource.LocalPath -> importFile(fileStore.resolve(source.relativePath), transform)
    }

    /**
     * 文件选择器给出的**绝对路径**。
     *
     * Android 版这里是 `Uri` + `contentResolver.openInputStream(uri)`；macOS 没有 content URI，
     * 选择器（Compose Desktop / AWT 的 `FileDialog`）直接给本机路径，
     * 因此 `ReferenceSource.PickedUri` 里装的就是路径本身，当文件直接读即可。
     *
     * 与 [importFile] 的差异保留着：路径不存在时这里走"解码失败"，而不是"文件缺失" ——
     * 两者在界面上的文案不同，Android 侧也是这个分工。
     */
    private suspend fun importPickedFile(
        path: String,
        transform: ImageTransform?,
    ): Outcome<PreparedReference> = withContext(Dispatchers.IO) {
        storeNormalized({ factor -> decodeSampled(File(path), factor) }, transform)
    }

    private suspend fun importFile(
        file: File,
        transform: ImageTransform?,
    ): Outcome<PreparedReference> = withContext(Dispatchers.IO) {
        if (!file.isFile) {
            return@withContext Outcome.Failure(AppError.of(ErrorCode.REFERENCE_MISSING))
        }
        storeNormalized({ factor -> decodeSampled(file, factor) }, transform)
    }

    /** 按 [transform] 裁切并编码成请求体里的 base64。 */
    override suspend fun encodeBase64(
        relativePath: String,
        transform: ImageTransform?,
    ): Outcome<String> = withContext(Dispatchers.IO) {
        val file = fileStore.resolve(relativePath)
        if (!file.isFile) {
            return@withContext Outcome.Failure(AppError.of(ErrorCode.REFERENCE_MISSING))
        }

        val decoded = decodeSampled(file, factor = 1)
            ?: return@withContext Outcome.Failure(AppError.of(ErrorCode.REFERENCE_DECODE_FAILED))

        // Android 版在这里 finally { decoded.recycle() }：rendered 可能是 decoded 本身，
        // 也可能是 render() 新开的画布。JVM 没有显式回收，两份都交给 GC。
        val rendered = if (transform == null) {
            decoded
        } else {
            render(decoded, transform)
                ?: return@withContext Outcome.Failure(
                    AppError.of(ErrorCode.REFERENCE_DECODE_FAILED),
                )
        }
        val bytes = encodePng(rendered)
            ?: return@withContext Outcome.Failure(
                AppError.of(ErrorCode.REFERENCE_DECODE_FAILED),
            )
        if (!ImageGeometry.isWithinReferenceBudget(bytes.size.toLong())) {
            return@withContext Outcome.Failure(AppError.of(ErrorCode.REFERENCE_TOO_LARGE))
        }
        // Android 的 NO_WRAP 与 java.util.Base64 的基础编码器完全一致：
        // 标准字母表、带填充、不换行。
        Outcome.Success(Base64.getEncoder().encodeToString(bytes))
    }

    // ---- 导入 ----

    /**
     * 解码 → 变换（可选）→ 统一成 PNG → 落盘。超预算就降采样重来。
     *
     * 降采样重试而不是直接拒绝，是因为用户无法判断"这张图为什么太大"；
     * 多试一次的成本只是一次解码，而重试次数有上界，不会在病态输入上打转。
     */
    private inline fun storeNormalized(
        decode: (Int) -> BufferedImage?,
        transform: ImageTransform?,
    ): Outcome<PreparedReference> {
        var factor = 1
        for (attempt in 0..MAX_DOWNSAMPLE_ATTEMPTS) {
            val decoded = decode(factor)
                ?: return Outcome.Failure(AppError.of(ErrorCode.REFERENCE_DECODE_FAILED))

            val result = try {
                val prepared = if (transform == null) {
                    decoded
                } else {
                    render(decoded, transform)
                        ?: return Outcome.Failure(AppError.of(ErrorCode.REFERENCE_DECODE_FAILED))
                }
                // Android 版在这里对 prepared / decoded 分别 recycle：变换产出的画布是自己
                // new 出来的，源图是另一份。JVM 没有显式回收，两份都交给 GC ——
                // 但两者的所有权区分不变：prepared 可能就是 decoded 本身。
                val bytes = encodePng(prepared)
                    ?: return Outcome.Failure(AppError.of(ErrorCode.REFERENCE_DECODE_FAILED))
                if (!ImageGeometry.isWithinReferenceBudget(bytes.size.toLong())) {
                    null
                } else {
                    val sha256 = Hashing.sha256(bytes)
                    fileStore.writeReference(sha256, bytes)
                    PreparedReference(
                        relativePath = fileStore.referenceRelativePath(sha256),
                        width = prepared.width,
                        height = prepared.height,
                        byteSize = bytes.size.toLong(),
                        sha256 = sha256,
                    )
                }
            } catch (e: IOException) {
                return Outcome.Failure(AppError.of(ErrorCode.STORAGE_FULL))
            }

            if (result != null) return Outcome.Success(result)
            factor *= 2
        }
        return Outcome.Failure(AppError.of(ErrorCode.REFERENCE_TOO_LARGE))
    }

    // ---- 解码 ----

    private fun decodeSampled(file: File, factor: Int): BufferedImage? = try {
        val bounds = readBounds(file) ?: return null
        val sample = maxOf(factor, ImageGeometry.downsampleFactor(bounds))
        decodeWithSampleSize(file, sample)
    } catch (e: OutOfMemoryError) {
        null
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        // Android 侧这一条兜的是"相册 URI 的临时授权在进程重启后失效"。
        // macOS 没有 URI 授权，但沙箱 / 权限拒绝同样会以这个异常出现，语义一致。
        null
    }

    /**
     * 只读文件头拿尺寸，对照 `BitmapFactory.Options(inJustDecodeBounds = true)`。
     *
     * 判据与 Android 侧一致：宽高都大于 0 才算读到了，否则当作"解码失败"
     * （Android 那边读不到时 `outWidth` / `outHeight` 停在 -1）。
     */
    private fun readBounds(file: File): PixelSize? = try {
        val stream = ImageIO.createImageInputStream(file) ?: return null
        stream.use { input ->
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) return null
            val reader = readers.next()
            try {
                reader.setInput(input, true, true)
                sizeOf(reader)
            } finally {
                reader.dispose()
            }
        }
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }

    private fun sizeOf(reader: ImageReader): PixelSize? {
        val width = reader.getWidth(0)
        val height = reader.getHeight(0)
        return if (width > 0 && height > 0) {
            PixelSize(width, height)
        } else {
            null
        }
    }

    /**
     * 按 [sampleSize] 抽样解码。
     *
     * 对应 `BitmapFactory.decodeFile(path, options(inSampleSize = sampleSize))`：
     * 优先让解码器自己按 `setSourceSubsampling` 抽样（PNG / JPEG 读取器都支持），
     * 尺寸因此与 Android 的 `ceil(边长 / sampleSize)` 一致，也不会把整张大图读进内存。
     *
     * 读取器不支持抽样时（罕见格式）退回 [nearestSubsample] —— 取样规则与
     * `inSampleSize` 相同（左上原点、等距抽取），只是内存峰值没有保护。
     */
    private fun decodeWithSampleSize(file: File, sampleSize: Int): BufferedImage? {
        val sample = sampleSize.coerceAtLeast(1)
        val stream = ImageIO.createImageInputStream(file) ?: return null
        stream.use { input ->
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) return null
            val reader = readers.next()
            try {
                reader.setInput(input, true, true)
                val param = readParam(reader, sample)
                // 抽样生效时 getSourceXSubsampling 就是我们设的值；没生效则停在 1。
                val subsampling = param.sourceXSubsampling > 1
                val decoded = reader.read(0, param)
                return if (sample > 1 && !subsampling) nearestSubsample(decoded, sample) else decoded
            } finally {
                reader.dispose()
            }
        }
    }

    /**
     * 对应 Android 的 `BitmapFactory.Options`：抽样倍数（`inSampleSize`）。
     *
     * JDK 没有"这个读取器支不支持源抽样"的查询接口（`canSetSourceSubsampling` 不存在），
     * 只能设一次试试：不支持时 `setSourceSubsampling` 抛 `UnsupportedOperationException`。
     * PNG / JPEG 读取器都支持，因此正常情况下走的就是解码器自己抽样的那条路。
     */
    private fun readParam(reader: ImageReader, sampleSize: Int) =
        reader.defaultReadParam.apply {
            val sample = sampleSize.coerceAtLeast(1)
            if (sample > 1) {
                try {
                    // 偏移取 0：从左上角开始每隔 sample 个像素取一个，与 inSampleSize 同源。
                    setSourceSubsampling(sample, sample, 0, 0)
                } catch (e: UnsupportedOperationException) {
                    // 保持 1：调用方据此退回逐点抽取。
                }
            }
        }

    /**
     * 逐点抽取：输出 `(x, y)` 取输入 `(x·n, y·n)`，尺寸同样是 `ceil(边长 / n)`。
     *
     * 这条路径只在读取器不支持抽样时走到。**已知差异**：Android 的解码器按格式各有
     * 一套降采样实现（JPEG 走 libjpeg 的降采样 IDCT，PNG 走跳行跳列），JDK 只提供
     * "抽取"这一种。长边超过 [ImageGeometry.MAX_DECODE_DIMENSION] 或体积超预算重试时
     * 才会走到这里，规则的**几何**（原点与输出尺寸）与 Android 一致，只是滤波核不同。
     */
    private fun nearestSubsample(image: BufferedImage, factor: Int): BufferedImage {
        val width = (image.width + factor - 1) / factor
        val height = (image.height + factor - 1) / factor

        // 原样搬运像素，不做任何色彩空间转换：用 Raster 的 getDataElements / setDataElements
        // 而不是 getRGB / setRGB —— 后者会经过 ColorModel，对灰度 / CMYK 这类图会顺手换算一次颜色，
        // 像素值就不是原文件里的值了。目标图沿用源图的 ColorModel，两边 SampleModel 一致。
        val output = BufferedImage(
            image.colorModel,
            image.colorModel.createCompatibleWritableRaster(width, height),
            image.isAlphaPremultiplied,
            null,
        )
        val sourceRaster = image.raster
        val targetRaster = output.raster
        for (y in 0 until height) {
            val sourceY = minOf(y * factor, image.height - 1)
            for (x in 0 until width) {
                val sourceX = minOf(x * factor, image.width - 1)
                targetRaster.setDataElements(
                    x,
                    y,
                    sourceRaster.getDataElements(sourceX, sourceY, null),
                )
            }
        }
        return output
    }

    // ---- 绘制与编码 ----

    private fun render(source: BufferedImage, transform: ImageTransform): BufferedImage? {
        val placement = ImageGeometry.placementFor(
            transform = transform,
            source = PixelSize(source.width, source.height),
        )
        val canvasSize = placement.canvas

        return try {
            val output = BufferedImage(
                canvasSize.width,
                canvasSize.height,
                BufferedImage.TYPE_INT_ARGB,
            )
            val graphics = output.createGraphics()
            try {
                // 先铺黑：Letterbox 留下的区域必须与官方要求一致（黑边），
                // 而 Cover 铺满整个画布，这一步等于没做。
                // 用 Src 合成，保证留边是**不透明**的黑（Android 的 drawColor 不透明色同义）。
                graphics.composite = AlphaComposite.Src
                graphics.color = Color.BLACK
                graphics.fillRect(0, 0, canvasSize.width, canvasSize.height)
                graphics.composite = AlphaComposite.SrcOver

                // Paint.FILTER_BITMAP_FLAG → 双线性插值。
                // 已知差异：Skia 在大幅缩小时会按比例改用更宽的滤波核（mipmap 式），
                // Java2D 只有 2×2 双线性，因此**缩放路径**的重采样结果与 Android 不逐像素相同。
                graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR,
                )
                graphics.drawImage(
                    source,
                    placement.destination.x,
                    placement.destination.y,
                    placement.destination.x + placement.destination.width,
                    placement.destination.y + placement.destination.height,
                    placement.source.x,
                    placement.source.y,
                    placement.source.x + placement.source.width,
                    placement.source.y + placement.source.height,
                    null,
                )
            } finally {
                graphics.dispose()
            }
            output
        } catch (e: OutOfMemoryError) {
            // 画布与源图同时驻留内存，低端机上有可能撑不住。
            null
        }
    }

    /** 编码成 PNG 字节；编码失败返回 null。 */
    private fun encodePng(image: BufferedImage): ByteArray? = try {
        ByteArrayOutputStream().use { buffer ->
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

    private companion object {
        /** 超预算后最多再降采样两次（即最多解码三次），避免病态输入下的长时间重试。 */
        const val MAX_DOWNSAMPLE_ATTEMPTS = 2
    }
}
