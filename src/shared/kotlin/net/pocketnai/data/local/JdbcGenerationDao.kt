package net.pocketnai.data.local

import kotlinx.coroutines.flow.Flow
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * [GenerationDao] 的 sqlite-jdbc 实现（替代 Room 生成的 `GenerationDao_Impl`）。
 *
 * 每条 SQL 都是从注解里逐字搬过来的，**包括列别名**（`relative_path`、`byte_size`、
 * `model_api_id` … 见 [GalleryImageRow]）。别名与属性的对应关系写在
 * [readGalleryRow] 里；改列名时要同步改这两处 —— Room 时代由注解处理器替我们盯着，
 * 现在编译器不会提醒。
 *
 * 线程模型：[queryFlow] 与 [io] 把阻塞的 JDBC 调用放到 `Dispatchers.IO`；
 * 这里的方法体本身是同步的，**不要在方法内部再切线程**（会多一层无意义的调度）。
 */
internal class JdbcGenerationDao(
    private val pool: SqliteConnectionPool,
    private val notifier: TableChangeNotifier,
) : GenerationDao {

    // ---- 可观察查询 ---------------------------------------------------------------

    override fun observeGenerations(): Flow<List<GenerationWithCount>> = queryFlow(notifier) {
        pool.use { connection ->
            connection.prepareStatement(SQL_OBSERVE_GENERATIONS).use { statement ->
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                GenerationWithCount(
                                    generation = readGeneration(rows),
                                    imageCount = rows.getInt("imageCount"),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    override fun observeGalleryImages(): Flow<List<GalleryImageRow>> = queryFlow(notifier) {
        pool.use { connection ->
            connection.prepareStatement(SQL_OBSERVE_GALLERY).use { statement ->
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) add(readGalleryRow(rows))
                    }
                }
            }
        }
    }

    // ---- 单行查询 -----------------------------------------------------------------

    override suspend fun findGeneration(generationId: String): GenerationEntity? = io {
        pool.use { connection ->
            connection.prepareStatement("SELECT * FROM generations WHERE id = ?").use { statement ->
                statement.setString(1, generationId)
                statement.executeQuery().use { rows ->
                    if (rows.next()) readGeneration(rows) else null
                }
            }
        }
    }

    override suspend fun findGenerationsByStatus(status: String): List<GenerationEntity> = io {
        pool.use { connection ->
            connection.prepareStatement(
                "SELECT * FROM generations WHERE status = ? AND deletedAt IS NULL",
            ).use { statement ->
                statement.setString(1, status)
                statement.executeQuery().use { rows -> readGenerations(rows) }
            }
        }
    }

    override suspend fun allGenerationIds(): List<String> = io {
        pool.use { connection -> readStrings(connection, "SELECT id FROM generations") }
    }

    override suspend fun findDeletedBefore(before: Long): List<String> = io {
        pool.use { connection ->
            connection.prepareStatement(
                "SELECT id FROM generations WHERE deletedAt IS NOT NULL AND deletedAt < ?",
            ).use { statement ->
                statement.setLong(1, before)
                statement.executeQuery().use { rows -> readStringColumn(rows, "id") }
            }
        }
    }

    override suspend fun allDeletedIds(): List<String> = io {
        pool.use { connection ->
            readStrings(connection, "SELECT id FROM generations WHERE deletedAt IS NOT NULL")
        }
    }

    override suspend fun countGenerations(): Int = io {
        pool.use { connection -> readInt(connection, "SELECT COUNT(*) FROM generations") }
    }

    override suspend fun findEmptyGenerationIds(): List<String> = io {
        pool.use { connection ->
            readStrings(
                connection,
                """
                SELECT g.id FROM generations g
                WHERE (SELECT COUNT(*) FROM generated_images i WHERE i.generationId = g.id) = 0
                """.trimIndent(),
            )
        }
    }

    // ---- 图片查询 -----------------------------------------------------------------

    override suspend fun findImages(generationId: String): List<GeneratedImageEntity> = io {
        pool.use { connection ->
            connection.prepareStatement(
                "SELECT * FROM generated_images WHERE generationId = ? ORDER BY ordinal ASC",
            ).use { statement ->
                statement.setString(1, generationId)
                statement.executeQuery().use { rows -> readImages(rows) }
            }
        }
    }

    override suspend fun findImage(imageId: String): GeneratedImageEntity? = io {
        pool.use { connection ->
            connection.prepareStatement("SELECT * FROM generated_images WHERE id = ?").use { statement ->
                statement.setString(1, imageId)
                statement.executeQuery().use { rows ->
                    if (rows.next()) readImage(rows) else null
                }
            }
        }
    }

    override suspend fun allImages(): List<GeneratedImageEntity> = io {
        pool.use { connection ->
            connection.prepareStatement("SELECT * FROM generated_images").use { statement ->
                statement.executeQuery().use { rows -> readImages(rows) }
            }
        }
    }

    override suspend fun allUnfavoritedImages(): List<GeneratedImageEntity> = io {
        pool.use { connection ->
            connection.prepareStatement(
                "SELECT * FROM generated_images WHERE id NOT IN (SELECT imageId FROM favorite_images)",
            ).use { statement ->
                statement.executeQuery().use { rows -> readImages(rows) }
            }
        }
    }

    // ---- 参考图 -------------------------------------------------------------------

    /**
     * 插入参考图行。
     *
     * `INSERT OR IGNORE`（原 `OnConflictStrategy.IGNORE`）：
     * - 主键冲突 → 无操作，不覆盖已有行；
     * - **会**触发外键检查：父记录不存在时这一行被静默忽略（而不是抛异常），
     *   与 Room 的行为一致。
     */
    override suspend fun insertReferences(entities: List<ReferenceImageEntity>) {
        if (entities.isEmpty()) return
        io {
            pool.transaction { connection ->
                connection.prepareStatement(SQL_INSERT_REFERENCE).use { statement ->
                    entities.forEach { entity ->
                        statement.clearParameters()
                        bindReference(statement, entity)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
            }
        }
        notifier.notifyChanged()
    }

    override suspend fun findReferences(generationId: String): List<ReferenceImageEntity> = io {
        pool.use { connection ->
            connection.prepareStatement(
                "SELECT * FROM reference_images WHERE generationId = ? ORDER BY ordinal ASC",
            ).use { statement ->
                statement.setString(1, generationId)
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(readReference(rows)) }
                }
            }
        }
    }

    override suspend fun allReferencePaths(): List<String> = io {
        pool.use { connection ->
            readStrings(
                connection,
                """
                SELECT relativePath FROM reference_images
                UNION
                SELECT vibeRelativePath FROM reference_images WHERE vibeRelativePath IS NOT NULL
                """.trimIndent(),
            )
        }
    }

    // ---- 写入 ---------------------------------------------------------------------

    /**
     * `INSERT OR IGNORE`（原 `OnConflictStrategy.IGNORE`）。
     *
     * SQLite 的 `REPLACE` 是"先 DELETE 再 INSERT"，而 `generated_images` 对
     * `generations` 有 `ON DELETE CASCADE` —— 对已存在的生成记录重插一次，
     * 该记录下所有图片行都会被级联删除。`IGNORE` 时重复插入是无操作，
     * 已存在的主键保持原样，不会波及子表。（这段解释原本写在 DAO 的注解上方，
     * 实现搬到 JDBC 之后跟着搬到这里，别删。）
     */
    override suspend fun upsertGeneration(entity: GenerationEntity) {
        io {
            pool.use { connection ->
                connection.prepareStatement(SQL_INSERT_GENERATION).use { statement ->
                    bindGeneration(statement, entity)
                    statement.executeUpdate()
                }
            }
        }
        notifier.notifyChanged()
    }

    override suspend fun upsertImages(entities: List<GeneratedImageEntity>) {
        if (entities.isEmpty()) return
        io {
            pool.transaction { connection ->
                connection.prepareStatement(SQL_INSERT_IMAGE).use { statement ->
                    entities.forEach { entity ->
                        statement.clearParameters()
                        bindImage(statement, entity)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
            }
        }
        notifier.notifyChanged()
    }

    override suspend fun updateStatus(
        generationId: String,
        status: String,
        updatedAt: Long,
        errorCode: String?,
        errorMessage: String?,
        correlationId: String?,
    ) {
        io {
            pool.use { connection ->
                connection.prepareStatement(SQL_UPDATE_STATUS).use { statement ->
                    statement.setString(1, status)
                    statement.setLong(2, updatedAt)
                    statement.setString(3, errorCode)
                    // 列名是蛇形的 error_message（遗留命名），不要改。
                    statement.setString(4, errorMessage)
                    statement.setString(5, correlationId)
                    statement.setString(6, generationId)
                    statement.executeUpdate()
                }
            }
        }
        notifier.notifyChanged()
    }

    override suspend fun updateExportedUri(imageId: String, exportedUri: String?) {
        io {
            pool.use { connection ->
                connection.prepareStatement(
                    "UPDATE generated_images SET exportedUri = ? WHERE id = ?",
                ).use { statement ->
                    statement.setString(1, exportedUri)
                    statement.setString(2, imageId)
                    statement.executeUpdate()
                }
            }
        }
        notifier.notifyChanged()
    }

    override suspend fun markDeleted(generationId: String, deletedAt: Long) {
        io {
            pool.use { connection ->
                connection.prepareStatement(
                    "UPDATE generations SET deletedAt = ?, updatedAt = ? WHERE id = ?",
                ).use { statement ->
                    statement.setLong(1, deletedAt)
                    statement.setLong(2, deletedAt)
                    statement.setString(3, generationId)
                    statement.executeUpdate()
                }
            }
        }
        notifier.notifyChanged()
    }

    override suspend fun restore(generationId: String, updatedAt: Long) {
        io {
            pool.use { connection ->
                connection.prepareStatement(
                    "UPDATE generations SET deletedAt = NULL, updatedAt = ? WHERE id = ?",
                ).use { statement ->
                    statement.setLong(1, updatedAt)
                    statement.setString(2, generationId)
                    statement.executeUpdate()
                }
            }
        }
        notifier.notifyChanged()
    }

    /** 删除第二步：确认无法撤销后物理删除父行，**图片行由外键级联删除**。 */
    override suspend fun purgeGeneration(generationId: String) {
        io {
            pool.use { connection ->
                connection.prepareStatement("DELETE FROM generations WHERE id = ?").use { statement ->
                    statement.setString(1, generationId)
                    statement.executeUpdate()
                }
            }
        }
        notifier.notifyChanged()
    }

    override suspend fun deleteImages(imageIds: List<String>) {
        if (imageIds.isEmpty()) return
        io {
            pool.transaction { connection ->
                connection.prepareStatement(
                    "DELETE FROM generated_images WHERE id IN (${placeholders(imageIds.size)})",
                ).use { statement ->
                    imageIds.forEachIndexed { index, id -> statement.setString(index + 1, id) }
                    statement.executeUpdate()
                }
            }
        }
        notifier.notifyChanged()
    }

    override suspend fun purgeGenerations(generationIds: List<String>) {
        if (generationIds.isEmpty()) return
        io {
            pool.transaction { connection ->
                connection.prepareStatement(
                    "DELETE FROM generations WHERE id IN (${placeholders(generationIds.size)})",
                ).use { statement ->
                    generationIds.forEachIndexed { index, id -> statement.setString(index + 1, id) }
                    statement.executeUpdate()
                }
            }
        }
        notifier.notifyChanged()
    }

    /**
     * 清空全部生成记录。
     *
     * 两件事在**同一个事务**里做：
     * 1. 删空 `anlas_transactions` —— Android 版里这张表也由"清理生成图片"的
     *    全清分支清掉（`AnlasLedgerRepository.clearLedger()`），而本端口的
     *    `anlas_transactions.generationId` 没有外键，**不会**随父行级联删除。
     *    少了这一步会留下指向已消失记录的孤儿流水，账单里出现对不上的条目；
     * 2. 删空 `generations` —— `generated_images` / `reference_images` /
     *    `favorite_images` 靠 `ON DELETE CASCADE` 跟着消失，前提是连接上的
     *    `PRAGMA foreign_keys = ON` 生效（见 [SqliteConnectionPool]）。
     */
    override suspend fun purgeAllGenerations() {
        io {
            pool.transaction { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate("DELETE FROM anlas_transactions")
                    statement.executeUpdate("DELETE FROM generations")
                }
            }
        }
        notifier.notifyChanged()
    }

    // ---- 结果集映射 ---------------------------------------------------------------
    //
    // 一律按**列名**取值（不是下标）：`SELECT *` 的列顺序由表结构决定，
    // 用下标会把"以后加了一列"变成静默的错位读取。
    // 可空列必须走 getXxxOrNull —— JDBC 把 SQL NULL 读成 0 / false，
    // 直接用会让 `outputWidth = NULL` 变成 0（界面上就是 0×0 的尺寸）。

    private fun readGenerations(rows: ResultSet): List<GenerationEntity> = buildList {
        while (rows.next()) add(readGeneration(rows))
    }

    private fun readGeneration(rows: ResultSet): GenerationEntity = GenerationEntity(
        id = rows.getString("id"),
        createdAt = rows.getLong("createdAt"),
        updatedAt = rows.getLong("updatedAt"),
        status = rows.getString("status"),
        title = rows.getString("title"),
        promptTemplate = rows.getString("promptTemplate"),
        mode = rows.getStringOrNull("mode"),
        prompt = rows.getString("prompt"),
        negativePrompt = rows.getString("negativePrompt"),
        modelApiId = rows.getString("modelApiId"),
        width = rows.getInt("width"),
        height = rows.getInt("height"),
        outputWidth = rows.getIntOrNull("outputWidth"),
        outputHeight = rows.getIntOrNull("outputHeight"),
        sampleCount = rows.getInt("sampleCount"),
        steps = rows.getInt("steps"),
        guidance = rows.getDouble("guidance"),
        cfgRescale = rows.getDouble("cfgRescale"),
        sampler = rows.getString("sampler"),
        noiseSchedule = rows.getString("noiseSchedule"),
        seedMode = rows.getString("seedMode"),
        baseSeed = rows.getLong("baseSeed"),
        qualityTags = rows.getStringOrNull("qualityTags"),
        // 遗留列：v1 是 INTEGER NOT NULL，继续按布尔语义读。
        qualityTagsEnabled = rows.getInt("qualityTagsEnabled") != 0,
        ucPresetIndex = rows.getInt("ucPresetIndex"),
        modelConfigVersion = rows.getString("modelConfigVersion"),
        requestSnapshotVersion = rows.getInt("requestSnapshotVersion"),
        errorCode = rows.getStringOrNull("errorCode"),
        // ★ 蛇形遗留列名，与 Room 的 @ColumnInfo(name = "error_message") 一致。
        errorMessage = rows.getStringOrNull("error_message"),
        correlationId = rows.getStringOrNull("correlationId"),
        deletedAt = rows.getLongOrNull("deletedAt"),
    )

    private fun readImages(rows: ResultSet): List<GeneratedImageEntity> = buildList {
        while (rows.next()) add(readImage(rows))
    }

    private fun readImage(rows: ResultSet): GeneratedImageEntity = GeneratedImageEntity(
        id = rows.getString("id"),
        generationId = rows.getString("generationId"),
        ordinal = rows.getInt("ordinal"),
        seed = rows.getLongOrNull("seed"),
        relativePath = rows.getString("relativePath"),
        width = rows.getInt("width"),
        height = rows.getInt("height"),
        byteSize = rows.getLong("byteSize"),
        sha256 = rows.getString("sha256"),
        metadataJson = rows.getStringOrNull("metadataJson"),
        createdAt = rows.getLong("createdAt"),
        exportedUri = rows.getStringOrNull("exportedUri"),
    )

    private fun readReference(rows: ResultSet): ReferenceImageEntity = ReferenceImageEntity(
        id = rows.getString("id"),
        generationId = rows.getString("generationId"),
        role = rows.getString("role"),
        ordinal = rows.getInt("ordinal"),
        relativePath = rows.getString("relativePath"),
        width = rows.getInt("width"),
        height = rows.getInt("height"),
        byteSize = rows.getLong("byteSize"),
        sha256 = rows.getString("sha256"),
        strength = rows.getDoubleOrNull("strength"),
        informationExtracted = rows.getDoubleOrNull("informationExtracted"),
        secondaryStrength = rows.getDoubleOrNull("secondaryStrength"),
        directorKind = rows.getStringOrNull("directorKind"),
        vibeRelativePath = rows.getStringOrNull("vibeRelativePath"),
        createdAt = rows.getLong("createdAt"),
    )

    /** 瀑布流投影：列别名 → 属性，见 [GalleryImageRow] 的表格。 */
    private fun readGalleryRow(rows: ResultSet): GalleryImageRow = GalleryImageRow(
        imageId = rows.getString("imageId"),
        generationId = rows.getString("generationId"),
        ordinal = rows.getInt("ordinal"),
        relativePath = rows.getString("relative_path"),
        width = rows.getInt("width"),
        height = rows.getInt("height"),
        byteSize = rows.getLong("byte_size"),
        seed = rows.getLongOrNull("seed"),
        exportedUri = rows.getStringOrNull("exported_uri"),
        imageCreatedAt = rows.getLong("image_created_at"),
        status = rows.getString("status"),
        mode = rows.getStringOrNull("mode"),
        title = rows.getString("title"),
        modelApiId = rows.getString("model_api_id"),
        prompt = rows.getString("prompt"),
        negativePrompt = rows.getString("negativePrompt"),
        sampleCount = rows.getInt("sample_count"),
    )

    // ---- 参数绑定 -----------------------------------------------------------------
    //
    // 列清单与 [GenerationEntity] 的字段顺序**必须一致**：
    // bind* 用固定下标，错位不会报错，只会把值写到错误的列里。

    private fun bindGeneration(statement: PreparedStatement, entity: GenerationEntity) {
        statement.setString(1, entity.id)
        statement.setLong(2, entity.createdAt)
        statement.setLong(3, entity.updatedAt)
        statement.setString(4, entity.status)
        statement.setString(5, entity.title)
        statement.setString(6, entity.promptTemplate)
        statement.setString(7, entity.mode)
        statement.setString(8, entity.prompt)
        statement.setString(9, entity.negativePrompt)
        statement.setString(10, entity.modelApiId)
        statement.setInt(11, entity.width)
        statement.setInt(12, entity.height)
        statement.setNullable(13, entity.outputWidth)
        statement.setNullable(14, entity.outputHeight)
        statement.setInt(15, entity.sampleCount)
        statement.setInt(16, entity.steps)
        statement.setDouble(17, entity.guidance)
        statement.setDouble(18, entity.cfgRescale)
        statement.setString(19, entity.sampler)
        statement.setString(20, entity.noiseSchedule)
        statement.setString(21, entity.seedMode)
        statement.setLong(22, entity.baseSeed)
        statement.setString(23, entity.qualityTags)
        statement.setInt(24, if (entity.qualityTagsEnabled) 1 else 0)
        statement.setInt(25, entity.ucPresetIndex)
        statement.setString(26, entity.modelConfigVersion)
        statement.setInt(27, entity.requestSnapshotVersion)
        statement.setString(28, entity.errorCode)
        statement.setString(29, entity.errorMessage)
        statement.setString(30, entity.correlationId)
        statement.setNullable(31, entity.deletedAt)
    }

    private fun bindImage(statement: PreparedStatement, entity: GeneratedImageEntity) {
        statement.setString(1, entity.id)
        statement.setString(2, entity.generationId)
        statement.setInt(3, entity.ordinal)
        statement.setNullable(4, entity.seed)
        statement.setString(5, entity.relativePath)
        statement.setInt(6, entity.width)
        statement.setInt(7, entity.height)
        statement.setLong(8, entity.byteSize)
        statement.setString(9, entity.sha256)
        statement.setString(10, entity.metadataJson)
        statement.setLong(11, entity.createdAt)
        statement.setString(12, entity.exportedUri)
    }

    private fun bindReference(statement: PreparedStatement, entity: ReferenceImageEntity) {
        statement.setString(1, entity.id)
        statement.setString(2, entity.generationId)
        statement.setString(3, entity.role)
        statement.setInt(4, entity.ordinal)
        statement.setString(5, entity.relativePath)
        statement.setInt(6, entity.width)
        statement.setInt(7, entity.height)
        statement.setLong(8, entity.byteSize)
        statement.setString(9, entity.sha256)
        statement.setNullable(10, entity.strength)
        statement.setNullable(11, entity.informationExtracted)
        statement.setNullable(12, entity.secondaryStrength)
        statement.setString(13, entity.directorKind)
        statement.setString(14, entity.vibeRelativePath)
        statement.setLong(15, entity.createdAt)
    }

    private companion object {

        /**
         * 与原 `@Query` 逐字相同。
         *
         * `SELECT g.*` 展开成 `GenerationEntity` 的列（照抄 Room 的字段顺序），
         * 用列名取值因此不受顺序影响，但保持与实体一致便于人工对照。
         */
        const val GENERATION_COLUMNS: String =
            "g.`id`, g.`createdAt`, g.`updatedAt`, g.`status`, g.`title`, g.`promptTemplate`, " +
                "g.`mode`, g.`prompt`, g.`negativePrompt`, g.`modelApiId`, g.`width`, g.`height`, " +
                "g.`outputWidth`, g.`outputHeight`, g.`sampleCount`, g.`steps`, g.`guidance`, " +
                "g.`cfgRescale`, g.`sampler`, g.`noiseSchedule`, g.`seedMode`, g.`baseSeed`, " +
                "g.`qualityTags`, g.`qualityTagsEnabled`, g.`ucPresetIndex`, g.`modelConfigVersion`, " +
                "g.`requestSnapshotVersion`, g.`errorCode`, g.`error_message`, g.`correlationId`, " +
                "g.`deletedAt`"

        val SQL_OBSERVE_GENERATIONS: String =
            """
            SELECT $GENERATION_COLUMNS, (
                SELECT COUNT(*) FROM generated_images i WHERE i.generationId = g.id
            ) AS imageCount
            FROM generations g
            WHERE g.deletedAt IS NULL
            ORDER BY g.createdAt DESC
            """.trimIndent()

        val SQL_OBSERVE_GALLERY: String =
            """
            SELECT
                i.id AS imageId,
                i.generationId AS generationId,
                i.ordinal AS ordinal,
                i.relativePath AS relative_path,
                i.width AS width,
                i.height AS height,
                i.byteSize AS byte_size,
                i.seed AS seed,
                i.exportedUri AS exported_uri,
                i.createdAt AS image_created_at,
                g.status AS status,
                g.mode AS mode,
                g.title AS title,
                g.modelApiId AS model_api_id,
                g.prompt AS prompt,
                g.negativePrompt AS negativePrompt,
                g.sampleCount AS sample_count
            FROM generated_images i
            JOIN generations g ON g.id = i.generationId
            WHERE g.deletedAt IS NULL
            ORDER BY i.createdAt DESC, i.ordinal ASC
            """.trimIndent()

        /** 列清单与 [bindGeneration] 的下标一一对应。 */
        val SQL_INSERT_GENERATION: String =
            "INSERT OR IGNORE INTO generations (" +
                "`id`, `createdAt`, `updatedAt`, `status`, `title`, `promptTemplate`, `mode`, `prompt`, " +
                "`negativePrompt`, `modelApiId`, `width`, `height`, `outputWidth`, `outputHeight`, " +
                "`sampleCount`, `steps`, `guidance`, `cfgRescale`, `sampler`, `noiseSchedule`, `seedMode`, " +
                "`baseSeed`, `qualityTags`, `qualityTagsEnabled`, `ucPresetIndex`, `modelConfigVersion`, " +
                "`requestSnapshotVersion`, `errorCode`, `error_message`, `correlationId`, `deletedAt`" +
                ") VALUES (${placeholders(31)})"

        val SQL_INSERT_IMAGE: String =
            "INSERT OR IGNORE INTO generated_images (" +
                "`id`, `generationId`, `ordinal`, `seed`, `relativePath`, `width`, `height`, `byteSize`, " +
                "`sha256`, `metadataJson`, `createdAt`, `exportedUri`" +
                ") VALUES (${placeholders(12)})"

        val SQL_INSERT_REFERENCE: String =
            "INSERT OR IGNORE INTO reference_images (" +
                "`id`, `generationId`, `role`, `ordinal`, `relativePath`, `width`, `height`, `byteSize`, " +
                "`sha256`, `strength`, `informationExtracted`, `secondaryStrength`, `directorKind`, " +
                "`vibeRelativePath`, `createdAt`" +
                ") VALUES (${placeholders(15)})"

        val SQL_UPDATE_STATUS: String =
            """
            UPDATE generations
            SET status = ?,
                updatedAt = ?,
                errorCode = ?,
                error_message = ?,
                correlationId = ?
            WHERE id = ?
            """.trimIndent()
    }
}

// ---- 结果集 / 语句小工具（只在本包的 Jdbc*Dao 之间共用） -----------------------------

/** 可空的 INTEGER 列。JDBC 把 SQL NULL 读成 0，因此必须问一次 `wasNull()`。 */
internal fun ResultSet.getIntOrNull(column: String): Int? =
    getInt(column).takeUnless { wasNull() }

/** 单列单值的 `Int` 查询（`SELECT COUNT(*)` 这类）。 */
internal fun readInt(connection: Connection, sql: String): Int =
    connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> if (rows.next()) rows.getInt(1) else 0 }
    }

/** 单列多值的 `String` 查询。 */
internal fun readStrings(connection: Connection, sql: String): List<String> =
    connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> readStringColumn(rows, 1) }
    }

/** 把结果集的第一列读成 `List<String>`（按列名或下标）。 */
internal fun readStringColumn(rows: ResultSet, column: String): List<String> = buildList {
    while (rows.next()) add(rows.getString(column))
}

/** 把结果集的第一列读成 `List<String>`（按列下标）。 */
internal fun readStringColumn(rows: ResultSet, columnIndex: Int): List<String> = buildList {
    while (rows.next()) add(rows.getString(columnIndex))
}

/**
 * `IN (?, ?, …)` 的占位符。
 *
 * 刻意**不用字符串拼接值**：那是 SQL 注入的标准入口，而且值里一旦出现引号就会
 * 变成语法错误。占位符个数只依赖列表长度，与内容无关。
 */
internal fun placeholders(count: Int): String = List(count) { "?" }.joinToString(", ")
