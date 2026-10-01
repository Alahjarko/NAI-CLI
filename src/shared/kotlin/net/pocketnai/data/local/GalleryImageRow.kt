package net.pocketnai.data.local

/** 生成记录 + 已有图片数，用于瀑布流里的占位卡片与详情页。 */
data class GenerationWithCount(
    /**
     * 整行 `generations` 记录。
     *
     * Room 时代这里是 `@Embedded`，列由 Room 自动铺平；现在由
     * [JdbcGenerationDao.readGeneration] 显式读列，语义不变：
     * 查询仍然是 `SELECT g.* , (子查询) AS imageCount`。
     */
    val generation: GenerationEntity,
    val imageCount: Int,
)

/**
 * 瀑布流卡片需要的联表投影：一次查询拿到图片与它所属任务的展示信息，
 * 避免在列表滚动时为每张卡片再查一次数据库。
 *
 * 这里的**字段名不是列名**，属性与结果集列的对应关系写在
 * [JdbcGenerationDao.observeGalleryImages] 的映射代码里（对照 Room 时代的
 * `@ColumnInfo(name = ...)`）。列别名保持原样，看清哪一列来自哪张表：
 *
 * | 属性 | 结果集列 |
 * |---|---|
 * | `relativePath` | `relative_path`（注意：列名里的下划线**只在这条投影里**，不是为了改名） |
 * | `byteSize` | `byte_size` |
 * | `exportedUri` | `exported_uri` |
 * | `imageCreatedAt` | `image_created_at` |
 * | `modelApiId` | `model_api_id` |
 * | `sampleCount` | `sample_count` |
 *
 * 表里真实的列名是驼峰的（`relativePath` / `byteSize` / …），别名只是查询投影的
 * 输出名字。**不要照着这里的别名去改 DDL**，那会让既有库文件读不出数据。
 */
data class GalleryImageRow(
    val imageId: String,
    val generationId: String,
    val ordinal: Int,
    val relativePath: String,
    val width: Int,
    val height: Int,
    val byteSize: Long,
    val seed: Long?,
    val exportedUri: String?,
    val imageCreatedAt: Long,
    val status: String,
    /** [net.pocketnai.domain.model.GenerationMode] 的名字；v3 之前的记录为空。 */
    val mode: String?,
    val title: String,
    val modelApiId: String,
    val prompt: String,
    val negativePrompt: String,
    val sampleCount: Int,
)
