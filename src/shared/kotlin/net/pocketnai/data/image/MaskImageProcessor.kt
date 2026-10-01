package net.pocketnai.data.image

import net.pocketnai.core.AppError
import net.pocketnai.core.ErrorCode
import net.pocketnai.core.Hashing
import net.pocketnai.core.Outcome
import net.pocketnai.data.files.GenerationFileStore
import net.pocketnai.domain.image.ImageGeometry
import net.pocketnai.domain.image.PreparedReference
import net.pocketnai.domain.image.PixelSize
import net.pocketnai.domain.inpaint.MaskConvention
import net.pocketnai.domain.inpaint.MaskGeometry
import net.pocketnai.domain.inpaint.MaskPoint
import net.pocketnai.domain.inpaint.MaskStroke
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.ImageIO

/**
 * 把笔画渲染成提交给服务端的蒙版 PNG（局部重绘规划书 §5.2）。
 *
 * ## 硬边，没有抗锯齿；提交前对齐隐空间网格
 * 蒙版是"涂了 / 没涂"的二值语义。抗锯齿会在边界产生一圈半透明像素，
 * 而服务端怎么解释这些像素是未知的。因此画笔一律 `isAntiAlias = false`，
 * 落盘前再做一次 8×8 网格对齐（见 [snapToLatentGrid]）—— 否则任意精度的
 * 边界经服务端下采样后会产生"半涂半不涂"的隐空间边缘格，模型在那里
 * 发明过渡材质（用户实测的"白色不明材质边界"就是这么来的）。
 * 编辑器的实时预览保持平滑（差距最多半格 4px），网格对齐只作用于落盘的提交图。
 *
 * ## 扩张就是"半径加大"
 * 见 [MaskGeometry.effectiveRadius]：圆头笔刷的 Minkowski 和等价于形态学膨胀，
 * 所以扩张不需要真的对位图做卷积，改半径重画一遍即可 —— 这也是界面上能实时预览的原因。
 *
 * ## 约定可翻转
 * 涂抹区域画成白色还是留成透明，取决于 [MaskConvention]（探针确认前按白色实现）。
 *
 * ## macOS 移植（API 对照）
 * | Android | JVM |
 * |---|---|
 * | `Bitmap.createBitmap(w, h, ARGB_8888)` | `BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)` |
 * | `Canvas` + `Paint` | `Graphics2D` + `BasicStroke` |
 * | `Paint.isAntiAlias = false` | `KEY_ANTIALIASING = VALUE_ANTIALIAS_OFF` |
 * | `Path` + `moveTo` / `lineTo` | `Path2D.Float` |
 * | `PorterDuff.Mode.CLEAR` | `AlphaComposite.Clear` |
 * | `Bitmap.createScaledBitmap(..., filter = false)` | [scaledNearest] |
 * | `Bitmap.compress(PNG, 100, out)` | `ImageIO.write(img, "png", out)` |
 *
 * 另外显式关掉 Java2D 独有的描边归一化（`VALUE_STROKE_PURE`）：默认值会把画笔
 * 挪动最多半个像素去对齐像素中心，那是 Android 侧没有的行为，会让蒙版边界整体偏移。
 */
class MaskImageProcessor(
    private val fileStore: GenerationFileStore,
    private val convention: MaskConvention = MaskConvention.CURRENT,
) {

    /** 渲染并落盘。返回的路径与尺寸可直接当作参考图入库。 */
    fun render(
        strokes: List<MaskStroke>,
        size: PixelSize,
        dilationPx: Float,
    ): Outcome<PreparedReference> {
        if (size.width <= 0 || size.height <= 0) {
            return Outcome.Failure(AppError.of(ErrorCode.INVALID_PARAMS))
        }

        var bitmap: BufferedImage? = null
        return try {
            bitmap = BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB)
            paintStrokes(bitmap, strokes, dilationPx)
            // 提交前把蒙版对齐到隐空间网格（8×8 像素一格）。编辑器里的实时预览
            // 仍是平滑的；落盘的这份才是提交图，必须与服务端的下采样对齐。
            val output = snapToLatentGrid(bitmap)
            if (output !== bitmap) {
                // Android 侧在这里 recycle 掉未对齐的那一份；JVM 没有显式回收，直接换引用。
                bitmap = output
            }

            val bytes = ByteArrayOutputStream().use { buffer ->
                if (!ImageIO.write(output, "png", buffer)) {
                    return Outcome.Failure(AppError.of(ErrorCode.REFERENCE_DECODE_FAILED))
                }
                buffer.toByteArray()
            }
            if (!ImageGeometry.isWithinReferenceBudget(bytes.size.toLong())) {
                return Outcome.Failure(AppError.of(ErrorCode.REFERENCE_TOO_LARGE))
            }

            val sha256 = Hashing.sha256(bytes)
            fileStore.writeReference(sha256, bytes)
            Outcome.Success(
                PreparedReference(
                    relativePath = fileStore.referenceRelativePath(sha256),
                    width = size.width,
                    height = size.height,
                    byteSize = bytes.size.toLong(),
                    sha256 = sha256,
                ),
            )
        } catch (e: OutOfMemoryError) {
            Outcome.Failure(AppError.of(ErrorCode.REFERENCE_DECODE_FAILED, detail = "蒙版画布过大"))
        } catch (e: IOException) {
            Outcome.Failure(AppError.of(ErrorCode.STORAGE_FULL, detail = e.message.orEmpty()))
        }
        // Android 侧的 finally { bitmap?.recycle() } 在 JVM 上没有对应操作。
    }

    private fun paintStrokes(bitmap: BufferedImage, strokes: List<MaskStroke>, dilationPx: Float) {
        val graphics = bitmap.createGraphics()
        try {
            // 背景：白色约定下背景是黑的；透明约定下背景是不透明的，涂抹处再用 CLEAR 擦成透明。
            val background = when (convention) {
                MaskConvention.PAINTED_IS_WHITE -> Color.BLACK
                MaskConvention.PAINTED_IS_TRANSPARENT -> Color.WHITE
            }
            // 对应 Android 的 Canvas.drawColor：整幅铺满。用 Src 合成保证 alpha 也是满的
            // （不透明色在 SrcOver 下结果相同，写成 Src 只是不依赖底图的初值）。
            graphics.composite = AlphaComposite.Src
            graphics.color = background
            graphics.fillRect(0, 0, bitmap.width, bitmap.height)
            graphics.composite = AlphaComposite.SrcOver

            // 硬边：关抗锯齿；再关掉 Java2D 的描边归一化（Android 侧没有这一步）。
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
            graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

            strokes.forEach { stroke ->
                if (!stroke.isDrawable) return@forEach
                val width = MaskGeometry.effectiveRadius(stroke, dilationPx) * 2f
                graphics.stroke = BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                applyStyle(graphics, stroke.isErase)

                val path = Path2D.Float()
                val first = stroke.points.first()
                path.moveTo(first.x, first.y)
                if (stroke.points.size == 1) {
                    // 单点：用一段极短的线配合圆头端点画出圆点。
                    path.lineTo(first.x + 0.01f, first.y)
                } else {
                    stroke.points.drop(1).forEach { point -> path.lineTo(point.x, point.y) }
                }
                graphics.draw(path)
            }
        } finally {
            graphics.dispose()
        }
    }

    /**
     * 画笔与橡皮各自"涂成什么"。
     *
     * 两种约定下正好互为镜像：白色约定里画笔给白色、橡皮给黑色；
     * 透明约定里画笔把像素擦成透明、橡皮再用不透明色补回来。
     */
    private fun applyStyle(graphics: Graphics2D, isErase: Boolean) {
        // Android 侧用 xfermode 而不是 Paint.blendMode（后者是 API 29+，minSdk 是 26）。
        // JVM 侧对应的是 AlphaComposite：SrcOver 是默认的"盖上去"，Clear 才是擦成透明。
        when (convention) {
            MaskConvention.PAINTED_IS_WHITE -> {
                graphics.composite = AlphaComposite.SrcOver
                graphics.color = if (isErase) Color.BLACK else Color.WHITE
            }

            MaskConvention.PAINTED_IS_TRANSPARENT -> {
                graphics.color = Color.WHITE
                graphics.composite = if (isErase) AlphaComposite.SrcOver else AlphaComposite.Clear
            }
        }
    }

    /**
     * 把蒙版对齐到 8×8 隐空间网格：最近邻缩到 1/8 再最近邻放回。
     *
     * 这是官方前端蒙版管线的等价物（它们提交前先把蒙版量化到 1/8）：
     * 服务端在隐空间（分辨率的 1/8）解释蒙版，我们的任意精度边界经服务端
     * 下采样后会产生"半涂半不涂"的边缘格，模型就在那圈里发明过渡材质
     * （2026-09-14 用户实测：蒙版边缘生成一坨白色不明材质）。
     * 预先把每个格子定死成纯黑或纯白，无论服务端怎么下采样，看到的都是确定值。
     *
     * 输入是硬边二值图（无抗锯齿），最近邻缩放保持二值，不需要再阈值化。
     * 尺寸不能被 8 整除时原样返回（生成尺寸都是 64 的倍数，这只是防御）。
     *
     * 可见性刻意是 `internal` 而不是 `private`：JVM 单测要能直接喂一张位图进来，
     * 钉住"已对齐的蒙版是不动点"这条不变量（走公开 API 只能拿到落盘后的 PNG，
     * 构造不出一张"每个 8×8 块都同色"的输入）。
     */
    internal fun snapToLatentGrid(source: BufferedImage): BufferedImage {
        if (source.width % LATENT_CELL != 0 || source.height % LATENT_CELL != 0) {
            return source
        }
        val cells = scaledNearest(source, source.width / LATENT_CELL, source.height / LATENT_CELL)
        return scaledNearest(cells, source.width, source.height)
    }

    /**
     * `Bitmap.createScaledBitmap(src, width, height, filter = false)` 的等价物：最近邻缩放。
     *
     * 取样规则必须写死，不能借用 `Graphics2D` 的
     * `VALUE_INTERPOLATION_NEAREST_NEIGHBOR` 或 `AffineTransformOp`：它们各自的取整
     * 约定不同，而起点差一个像素就会让"已对齐的蒙版经过一次对齐后不变"这条不变量破掉
     * （那正是网格对齐要保证的东西）。这里用的规则与 Skia 无滤波缩放一致：
     * 目标像素中心 `dst + 0.5` 映射回源图坐标再向下取整，即
     * `src = floor((dst + 0.5) * srcLen / dstLen)`（整数形式见 [nearestIndex]）。
     *
     * 对本处的 1/8 缩来说，格子 `i` 取到的是源图 `8i + 4` 那个像素 —— 8×8 格子的**中心**；
     * 放回时 `floor((x + 0.5) / 8) = x / 8`，每格正好铺满 8 像素。
     * 于是整条链路等价于"把每个 8×8 格子量化成格中心像素的值"。
     */
    private fun scaledNearest(source: BufferedImage, width: Int, height: Int): BufferedImage {
        val sourceWidth = source.width
        val sourceHeight = source.height
        // 整幅取出来：TYPE_INT_ARGB 下这是纯粹的像素搬运，不做任何色彩转换。
        val pixels = source.getRGB(0, 0, sourceWidth, sourceHeight, null, 0, sourceWidth)
        val columnMap = IntArray(width) { nearestIndex(it, sourceWidth, width) }

        val out = IntArray(width * height)
        for (y in 0 until height) {
            val sourceRow = nearestIndex(y, sourceHeight, height) * sourceWidth
            val outRow = y * width
            for (x in 0 until width) {
                out[outRow + x] = pixels[sourceRow + columnMap[x]]
            }
        }

        val output = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        output.setRGB(0, 0, width, height, out, 0, width)
        return output
    }

    /**
     * 目标下标 [dst] 取源图的哪个下标：`floor((dst + 0.5) * srcLength / dstLength)`。
     *
     * 用整数乘除写出来，避免浮点取整在不同实现下漂移；`+ 0.5` 就是半像素偏移。
     */
    private fun nearestIndex(dst: Int, srcLength: Int, dstLength: Int): Int =
        (((2L * dst + 1) * srcLength) / (2L * dstLength)).toInt().coerceIn(0, srcLength - 1)

    /** 便于诊断：把笔画渲染成一张仅用于界面预览的位图（不落盘，因此**不做**网格对齐）。 */
    fun renderPreview(
        strokes: List<MaskStroke>,
        size: PixelSize,
        dilationPx: Float,
    ): BufferedImage? = try {
        BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB).also { bitmap ->
            paintStrokes(bitmap, strokes, dilationPx)
        }
    } catch (e: OutOfMemoryError) {
        null
    }

    companion object {
        /** 编辑器里笔刷半径的可选范围（位图像素）。 */
        val BRUSH_RANGE: ClosedFloatingPointRange<Float> = 4f..160f

        /** 蒙版扩张的可选范围（位图像素）。上限对应官方那句"涂太靠边会泄漏，把蒙版扩大一些"。 */
        val DILATION_RANGE: ClosedFloatingPointRange<Float> = 0f..24f

        /** 隐空间网格的边长（像素）：蒙版按它对齐，与官方前端一致。 */
        private const val LATENT_CELL = 8
    }
}

/** 仅供界面显示：把位图坐标点四舍五入，避免累积浮点误差。 */
internal fun MaskPoint.rounded(): MaskPoint =
    MaskPoint(kotlin.math.round(x), kotlin.math.round(y))
