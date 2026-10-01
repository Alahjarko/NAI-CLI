package net.pocketnai.data.local

import kotlinx.coroutines.flow.Flow

/**
 * 生成历史的读写接口。
 *
 * 桌面端口说明：原本是 Room 的 `@Dao` 接口，实现由注解处理器生成；
 * 现在由 [JdbcGenerationDao] 手写 JDBC 实现。**方法签名与返回类型一字未改**，
 * 所以上层仓库不需要任何改动。两条契约必须守住：
 *
 * 1. 返回 `Flow` 的查询是可观察查询：写操作提交后会重新查询并重新发射
 *    （见 [TableChangeNotifier]），不是"查一次就结束"；
 * 2. `suspend` 方法把阻塞的 JDBC 调用放到 IO 线程池上，调用方照旧直接 await。
 *
 * SQL 本体（与 Room 时代逐字相同的那些语句）现在住在 [JdbcGenerationDao] 里，
 * 与结果集映射挨在一起；这里的注释解释"为什么这么查"。
 */
interface GenerationDao {

    /** 未删除的生成记录，按时间倒序，带图片数量。 */
    fun observeGenerations(): Flow<List<GenerationWithCount>>

    /** 瀑布流的图片卡片：只包含属于未删除记录、且状态为成功或部分成功的最终图片。 */
    fun observeGalleryImages(): Flow<List<GalleryImageRow>>

    suspend fun findGeneration(generationId: String): GenerationEntity?

    // ---- 参考图 ----
    //
    // 参考图与生成记录分开查询，而不是在画廊 / 历史的查询里 join：
    // 那两条查询已经被瀑布流依赖，加 join 会让"一张图对应多行"的语义无处安放。
    // 需要参考图的只有详情页与"复用参数"，按需查一次即可。

    /**
     * 按"哪条生成记录 + 什么角色 + 第几张"插入参考图行。
     *
     * 冲突策略是 **IGNORE**（`INSERT OR IGNORE`），与 Room 的
     * `OnConflictStrategy.IGNORE` 一致：重复插入是无操作，不覆盖已有行。
     */
    suspend fun insertReferences(entities: List<ReferenceImageEntity>)

    suspend fun findReferences(generationId: String): List<ReferenceImageEntity>

    /**
     * 所有仍被引用的参考图 / vibe 文件路径，供启动清理使用。
     *
     * 返回的是"路径集合"而不是文件名：文件商店按相对路径判活，
     * 这样它不需要知道内容寻址的命名规则。
     */
    suspend fun allReferencePaths(): List<String>

    suspend fun findImages(generationId: String): List<GeneratedImageEntity>

    suspend fun findImage(imageId: String): GeneratedImageEntity?

    suspend fun allGenerationIds(): List<String>

    suspend fun findGenerationsByStatus(status: String): List<GenerationEntity>

    /**
     * 刻意用 IGNORE（`INSERT OR IGNORE`）而不是 REPLACE。
     *
     * SQLite 的 REPLACE 实现是“先 DELETE 再 INSERT”，而 `generated_images` 对
     * `generations` 有 ON DELETE CASCADE —— 一旦对已存在的生成记录重插一次，
     * 该记录下所有图片行都会被级联删除。用 IGNORE 时重复插入是无操作，
     * 已存在的主键保持原样，不会波及子表。
     */
    suspend fun upsertGeneration(entity: GenerationEntity)

    suspend fun upsertImages(entities: List<GeneratedImageEntity>)

    suspend fun updateStatus(
        generationId: String,
        status: String,
        updatedAt: Long,
        errorCode: String?,
        errorMessage: String?,
        correlationId: String?,
    )

    suspend fun updateExportedUri(imageId: String, exportedUri: String?)

    /** 删除第一步：只打标记，界面立即隐藏，文件与记录都还在，因此可以撤销。 */
    suspend fun markDeleted(generationId: String, deletedAt: Long)

    suspend fun restore(generationId: String, updatedAt: Long)

    /** 删除第二步：确认无法撤销后，物理删除记录（图片行级联删除）。 */
    suspend fun purgeGeneration(generationId: String)

    suspend fun findDeletedBefore(before: Long): List<String>

    suspend fun allDeletedIds(): List<String>

    suspend fun countGenerations(): Int

    suspend fun allUnfavoritedImages(): List<GeneratedImageEntity>

    suspend fun allImages(): List<GeneratedImageEntity>

    suspend fun deleteImages(imageIds: List<String>)

    suspend fun findEmptyGenerationIds(): List<String>

    suspend fun purgeGenerations(generationIds: List<String>)

    /**
     * 清空全部生成记录（"清理生成图片"里勾了"同时清理已收藏"的那条分支）。
     *
     * 桌面端口说明：这里**刻意多做了一步** —— 在同一个事务里先删空
     * `anlas_transactions` 再删 `generations`。原因：Android 版的
     * `anlas_transactions.generationId` 没有外键约束，而本端口是纯粹按 Room 的
     * `DELETE FROM generations` 语义实现的，删父行只会级联删掉
     * `generated_images` / `reference_images` / `favorite_images` 三张子表，
     * 流水行会留下来指向已经不存在的生成记录。Android 版的用户预期是
     * "整库清空"，多删这两步是与它对齐，不是新增行为。
     */
    suspend fun purgeAllGenerations()
}
