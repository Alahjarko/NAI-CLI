package net.pocketnai.data.local

import java.sql.Connection

/**
 * 数据库结构定义：表、索引、以及 v1 → v7 的完整迁移链。
 *
 * **这里的 DDL 逐字抄自 `app/schemas/net.pocketnai.data.local.PocketNaiDatabase/<n>.json`**
 * （只把 `${TABLE_NAME}` 换成真实表名）。抄写而不是"按实体重新生成"是有意的：
 * 导出文件是 Room 编译器留下的既成事实，唯有照抄才谈得上"schema 与 Android 版一致"。
 * 手写一份"看着等价"的 DDL 迟早会在列顺序、NOT NULL、外键动作这些细节上跑偏，
 * 而那种偏差在真机上表现为打开数据库直接抛异常。
 *
 * 目标版本 **7**，与 Android 版当前版本一致（AGENTS.md「数据库」一节）。
 *
 * 三条铁律（改本文件前先读 AGENTS.md 的「数据库」小节）：
 * 1. **`generations` 表绝不能重建（DROP / RENAME）。** `generated_images` 以
 *    `ON DELETE CASCADE` 引用它，重建会连带删除用户的图片记录；
 * 2. 加列一律 `ALTER TABLE ... ADD COLUMN`，**不删遗留列**（`error_message`、
 *    `qualityTagsEnabled` 都是遗留列；删列需要 SQLite 3.35+，且会让库文件
 *    与 Android 版不兼容）；
 * 3. 新表一律 `CREATE TABLE IF NOT EXISTS`、新索引 `CREATE INDEX IF NOT EXISTS`，
 *    升级路径**非破坏性**。
 *
 * 列顺序已核对：v1 的列顺序 + 这条迁移链追加的 `qualityTags` / `mode` /
 * `outputWidth` / `outputHeight`，与第 7 版导出文件的列顺序**逐列一致**。
 */
internal object SchemaDefinitions {

    /** 当前 schema 版本，与 Android 版一致。 */
    const val VERSION: Int = 7

    /** 对照 Android 的数据库文件名（`PocketNaiDatabase.DATABASE_NAME`）。 */
    const val DATABASE_NAME: String = "pocketnai.db"

    /** 验收用：本端口期望的 SQLite `user_version`。与 [VERSION] 是同一个数，分开写只是为了让 pragma 的用途在调用处显式。 */
    const val USER_VERSION: Int = VERSION

    // ---- v1 就存在的两张表 ---------------------------------------------------------
    //
    // `generations` 在 v1 就有全部列（含后来成为"遗留列"的 `qualityTagsEnabled`
    // 与 `error_message`）；v1→v7 只在它身上**加**过四列：`qualityTags`（v2）、
    // `mode`（v4）、`outputWidth` / `outputHeight`（v5）。
    // 列顺序必须严格按导出文件，因为 `ALTER TABLE ADD COLUMN` 只能追加到末尾。

    /** `generations` 的 v1 建表语句（`${TABLE_NAME}` = `generations`）。 */
    const val CREATE_GENERATIONS: String =
        "CREATE TABLE IF NOT EXISTS `generations` (`id` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL, `status` TEXT NOT NULL, `title` TEXT NOT NULL, " +
            "`promptTemplate` TEXT NOT NULL, `prompt` TEXT NOT NULL, `negativePrompt` TEXT NOT NULL, " +
            "`modelApiId` TEXT NOT NULL, `width` INTEGER NOT NULL, `height` INTEGER NOT NULL, " +
            "`sampleCount` INTEGER NOT NULL, `steps` INTEGER NOT NULL, `guidance` REAL NOT NULL, " +
            "`cfgRescale` REAL NOT NULL, `sampler` TEXT NOT NULL, `noiseSchedule` TEXT NOT NULL, " +
            "`seedMode` TEXT NOT NULL, `baseSeed` INTEGER NOT NULL, `qualityTagsEnabled` INTEGER NOT NULL, " +
            "`ucPresetIndex` INTEGER NOT NULL, `modelConfigVersion` TEXT NOT NULL, " +
            "`requestSnapshotVersion` INTEGER NOT NULL, `errorCode` TEXT, `error_message` TEXT, " +
            "`correlationId` TEXT, `deletedAt` INTEGER, PRIMARY KEY(`id`))"

    /** `generated_images` 的建表语句（自 v1 起未变）。级联外键见 DDL 末尾。 */
    const val CREATE_GENERATED_IMAGES: String =
        "CREATE TABLE IF NOT EXISTS `generated_images` (`id` TEXT NOT NULL, `generationId` TEXT NOT NULL, " +
            "`ordinal` INTEGER NOT NULL, `seed` INTEGER, `relativePath` TEXT NOT NULL, `width` INTEGER NOT NULL, " +
            "`height` INTEGER NOT NULL, `byteSize` INTEGER NOT NULL, `sha256` TEXT NOT NULL, " +
            "`metadataJson` TEXT, `createdAt` INTEGER NOT NULL, `exportedUri` TEXT, PRIMARY KEY(`id`), " +
            "FOREIGN KEY(`generationId`) REFERENCES `generations`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE )"

    /** v1 就有的三个索引。 */
    val INITIAL_INDEXES: List<String> = listOf(
        "CREATE INDEX IF NOT EXISTS `index_generations_createdAt` ON `generations` (`createdAt`)",
        "CREATE INDEX IF NOT EXISTS `index_generations_deletedAt` ON `generations` (`deletedAt`)",
        "CREATE INDEX IF NOT EXISTS `index_generated_images_generationId` " +
            "ON `generated_images` (`generationId`)",
    )

    /** v1 的全部结构（两张表 + 三个索引）。 */
    val V1_STATEMENTS: List<String> = listOf(CREATE_GENERATIONS, CREATE_GENERATED_IMAGES) + INITIAL_INDEXES

    /** `prompt_favorites`：v2 → v3 新增。 */
    const val CREATE_PROMPT_FAVORITES: String =
        "CREATE TABLE IF NOT EXISTS `prompt_favorites` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
            "`name` TEXT NOT NULL, `content` TEXT NOT NULL, `category` TEXT NOT NULL, `target` TEXT NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `lastUsedAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`id`))"

    /** `reference_images`：v3 → v4 新增。 */
    const val CREATE_REFERENCE_IMAGES: String =
        "CREATE TABLE IF NOT EXISTS `reference_images` (`id` TEXT NOT NULL, `generationId` TEXT NOT NULL, " +
            "`role` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, `relativePath` TEXT NOT NULL, " +
            "`width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `byteSize` INTEGER NOT NULL, " +
            "`sha256` TEXT NOT NULL, `strength` REAL, `informationExtracted` REAL, `secondaryStrength` REAL, " +
            "`directorKind` TEXT, `vibeRelativePath` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
            "FOREIGN KEY(`generationId`) REFERENCES `generations`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE )"

    /** `favorite_images`：v5 → v6 新增。主键是图片 id，外键指向 `generated_images`。 */
    const val CREATE_FAVORITE_IMAGES: String =
        "CREATE TABLE IF NOT EXISTS `favorite_images` (`imageId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`imageId`), FOREIGN KEY(`imageId`) REFERENCES `generated_images`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE )"

    /** `anlas_transactions`：v6 → v7 新增。`generationId` **没有**外键（流水是快照）。 */
    const val CREATE_ANLAS_TRANSACTIONS: String =
        "CREATE TABLE IF NOT EXISTS `anlas_transactions` (`id` TEXT NOT NULL, " +
            "`accountFingerprint` TEXT NOT NULL, `generationId` TEXT, `actionType` TEXT NOT NULL, " +
            "`anlasSpent` INTEGER NOT NULL, `v5AllowanceDelta` INTEGER, `balanceAfter` INTEGER, " +
            "`description` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"

    /** `reference_images` 的索引：外键子列必须有索引，否则 Room 的 schema 校验会失败。 */
    val REFERENCE_IMAGES_INDEXES: List<String> = listOf(
        "CREATE INDEX IF NOT EXISTS `index_reference_images_generationId` " +
            "ON `reference_images` (`generationId`)",
    )

    /**
     * `favorite_images` **刻意没有**额外索引。
     *
     * `imageId` 是主键，本身已是索引；再给外键子列建一个纯属浪费
     * （Room 的"子列未建索引"检查认主键）。留一条空列表是为了让
     * "本表确实不需要索引"这件事在代码里有落点，而不是靠读者自己推断。
     */
    val FAVORITE_IMAGES_INDEXES: List<String> = emptyList()

    /** `anlas_transactions` 的索引：账单按时间倒序翻。 */
    val ANLAS_TRANSACTIONS_INDEXES: List<String> = listOf(
        "CREATE INDEX IF NOT EXISTS `index_anlas_transactions_createdAt` " +
            "ON `anlas_transactions` (`createdAt`)",
    )

    // ---- v7 全集（诊断 / 表校验用） -------------------------------------------------

    /**
     * v7 的全部结构：按"先父表后子表"的顺序排列。
     *
     * 顺序有意义：`generated_images` / `reference_images` / `favorite_images` 都带外键，
     * 父表先建；索引放最后，避免引用到还不存在的表。
     *
     * 注意：新库的**建库路径**走的是 [MIGRATION_STEPS]（见 [PocketNaiDatabase]），
     * 与 Android 版"v1 建库 + 一路升级"完全同构；本列表只用于诊断输出与测试断言。
     */
    val V7_STATEMENTS: List<String> =
        listOf(CREATE_GENERATIONS, CREATE_GENERATED_IMAGES) +
            INITIAL_INDEXES +
            listOf(CREATE_PROMPT_FAVORITES) +
            listOf(CREATE_REFERENCE_IMAGES) +
            REFERENCE_IMAGES_INDEXES +
            listOf(CREATE_FAVORITE_IMAGES) +
            FAVORITE_IMAGES_INDEXES +
            listOf(CREATE_ANLAS_TRANSACTIONS) +
            ANLAS_TRANSACTIONS_INDEXES

    // ---- 迁移链 --------------------------------------------------------------------
    //
    // 每一步都在**一个事务**里执行（Room 也是这样：中途失败整体回滚，不留半升级的库）。
    //
    // 除了要执行的 SQL，每一步还带一个 [MigrationStep.isApplied] 探针：它回答
    // "这一步在库里是不是已经生效了"。作用见 [PocketNaiDatabase.migrate]：
    // 让升级可以从任意版本、甚至从"迁移跑了一半"的状态安全接上，
    // 而不是靠"重跑一遍 ADD COLUMN 让它报 duplicate column name"。

    /**
     * 一步迁移。
     *
     * @param from 起始版本（含）；
     * @param sql 要执行的语句，与 Android 版 `MIGRATION_n_m` 逐字相同；
     * @param isApplied 探针：给出该步是否已经落到库里。只在"版本号落后但结构已新"
     *   这种半升级状态下才会用到 —— 正常升级路径上它恒为 false。
     */
    internal class MigrationStep(
        val from: Int,
        val sql: List<String>,
        val isApplied: (SchemaProbe) -> Boolean,
    ) {
        val to: Int get() = from + 1
    }

    /**
     * v1 → v2：新增质量标签档位列。
     *
     * 用 `ADD COLUMN` 而不是重建表。原因见 [GenerationEntity.qualityTags]：
     * `generated_images` 通过外键 ON DELETE CASCADE 引用 `generations`，
     * 一旦 DROP 或 RENAME 这张表，已生成的图片记录会被连带删除 —— 对用户来说
     * 就是历史凭空消失。增量加列没有这个风险。
     *
     * 回填规则：v1 只有布尔开关，打开时对应 Standard，关闭时对应 None。
     */
    private val MIGRATION_1_2: List<String> = listOf(
        "ALTER TABLE generations ADD COLUMN qualityTags TEXT",
        """
        UPDATE generations
        SET qualityTags = CASE WHEN qualityTagsEnabled = 1 THEN 'STANDARD' ELSE 'NONE' END
        """.trimIndent(),
    )

    /**
     * v2 → v3：新增收藏提示词的独立新表。
     *
     * 这次只 `CREATE TABLE`，不触碰 `generations` / `generated_images`，
     * 因此没有 v1→v2 那种"重建父表会级联删掉用户图片"的风险 ——
     * 但**新增表同样必须写出与 Room 期望完全一致的列定义**
     * （列名、类型、NOT NULL、主键），否则打开数据库时的 schema 校验会失败并抛出异常。
     */
    private val MIGRATION_2_3: List<String> = listOf(CREATE_PROMPT_FAVORITES)

    /**
     * v3 → v4：新增参考图表，并给 `generations` 加一列生成模式。
     *
     * 两处都是纯加法：
     * - 新表 `reference_images` 用 `CREATE TABLE`，列定义必须与
     *   [ReferenceImageEntity] 完全一致（列名、类型、NOT NULL、主键），否则打开数据库时校验会失败；
     * - `generations` 只 `ADD COLUMN`，**不重建**（原因见 v1→v2）。
     *   新列不带默认值、声明成可空，这是默认值比对陷阱的规避方式；
     *   老记录靠一次 `UPDATE` 回填成 TXT2IMG，语义上它们确实都是纯文生图。
     */
    private val MIGRATION_3_4: List<String> = listOf(
        "ALTER TABLE generations ADD COLUMN mode TEXT",
        "UPDATE generations SET mode = 'TXT2IMG' WHERE mode IS NULL",
        CREATE_REFERENCE_IMAGES,
        "CREATE INDEX IF NOT EXISTS `index_reference_images_generationId` " +
            "ON `reference_images` (`generationId`)",
    )

    /**
     * 自定义分辨率：`generations` 上补两列记录**最终尺寸**。
     *
     * - 只 `ADD COLUMN`，**不重建表**（`generated_images` 有级联外键，重建会连带删图）；
     * - 两列都可空且不带默认值，老记录保持 NULL，读取时按"不裁切"降级；
     * - `width/height` 仍然是**请求画布**，计费与复现都以它为准；
     *   `outputWidth/outputHeight` 才是用户要的最终尺寸。
     *
     * 刻意不存裁切原点：居中裁切是确定规则（奇数像素固定多给右下 1 px），
     * 由 `ResolutionPlanner` 从两个尺寸算出来，存下来反而多一个可能与规则不一致的事实源。
     */
    private val MIGRATION_4_5: List<String> = listOf(
        "ALTER TABLE generations ADD COLUMN outputWidth INTEGER",
        "ALTER TABLE generations ADD COLUMN outputHeight INTEGER",
    )

    /**
     * 画廊收藏：新增收藏图片表。
     *
     * 只有 `CREATE TABLE` + `CREATE INDEX`，**不触碰任何既有表** —— 收藏是纯增量功能，
     * 没有理由让 `generations` / `generated_images` 承担迁移风险
     * （那两张表被 `ON DELETE CASCADE` 拴在一起，重建父表会连带删掉用户的图片记录）。
     *
     * 列定义必须与 [FavoriteImageEntity] 完全一致（列名、类型、NOT NULL、主键、外键），
     * 否则打开数据库时的 schema 校验会失败。外键的三个属性也不能省：
     * Room 会把 `onDelete` 一起比对，写成 NO ACTION 就会校验不过。
     *
     * 刻意不给 imageId 建额外索引：它是主键，本身已是索引，
     * 外键的"子列未建索引"检查认主键，多建一个纯属浪费。
     */
    private val MIGRATION_5_6: List<String> = listOf(CREATE_FAVORITE_IMAGES) + FAVORITE_IMAGES_INDEXES

    /**
     * v6 → v7：新增 Anlas 消耗流水账目表。
     *
     * 只 `CREATE TABLE` + `CREATE INDEX`，不触碰任何既有表。
     * 列定义必须与 [AnlasTransactionEntity] 完全一致。
     */
    private val MIGRATION_6_7: List<String> = listOf(CREATE_ANLAS_TRANSACTIONS) + ANLAS_TRANSACTIONS_INDEXES

    /**
     * 完整迁移链 v1 → v7，**按顺序执行**。
     *
     * 每一步的探针只查"这一步新增的东西是否已存在"：
     * - v1→v2 查 `generations.qualityTags`；
     * - v2→v3 查 `prompt_favorites` 表；
     * - v3→v4 查 `generations.mode`（参考图表由同一句 `CREATE TABLE IF NOT EXISTS` 兜住）；
     * - v4→v5 查 `generations.outputWidth`；
     * - v5→v6 查 `favorite_images` 表；
     * - v6→v7 查 `anlas_transactions` 表。
     *
     * 探针全部为真时说明"结构已经是 v7"，此时只需要把 `user_version` 补上。
     */
    val MIGRATION_STEPS: List<MigrationStep> = listOf(
        MigrationStep(1, MIGRATION_1_2) { it.hasColumn("generations", "qualityTags") },
        MigrationStep(2, MIGRATION_2_3) { it.hasTable("prompt_favorites") },
        MigrationStep(3, MIGRATION_3_4) { it.hasColumn("generations", "mode") },
        MigrationStep(4, MIGRATION_4_5) { it.hasColumn("generations", "outputWidth") },
        MigrationStep(5, MIGRATION_5_6) { it.hasTable("favorite_images") },
        MigrationStep(6, MIGRATION_6_7) { it.hasTable("anlas_transactions") },
    )

    /**
     * 从一个**全新文件**建到 v7 的语句序列。
     *
     * 就是"v1 建表 + 全部迁移"，与 Android 版 Room 用
     * `CREATE TABLE` 建 v1 再一路迁移得到的结果同构 —— 不是另抄一份 v7 的
     * `CREATE TABLE`，那样两条路径的列顺序迟早会分叉。列顺序已用导出文件核对过。
     */
    val FRESH_DATABASE_STATEMENTS: List<String> = V1_STATEMENTS + MIGRATION_STEPS.flatMap { it.sql }

    /**
     * `room_master_table`：Room 记录 identity hash 的地方。
     *
     * 桌面端**不依赖**它（版本判据是 `PRAGMA user_version`），但建库时仍然写上：
     * 用户日后把库文件带回 Android 版打开时，Room 会先查这张表确认
     * "这份文件就是我建的那份"；缺了它，Room 会认为表结构对不上。
     * hash 值抄自 `app/schemas/.../7.json` 的 `setupQueries`。
     */
    val ROOM_MASTER_TABLE_SETUP: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
        "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '$ROOM_IDENTITY_HASH_V7')",
    )

    /** 第 7 版导出文件里的 identity hash（`7.json` 的 `setupQueries`）。 */
    const val ROOM_IDENTITY_HASH_V7: String = "355e55a3a15dfac3b50f62043d5203a2"

    /** 表名常量，供诊断与测试使用。 */
    val TABLE_NAMES: List<String> = listOf(
        "generations",
        "generated_images",
        "prompt_favorites",
        "reference_images",
        "favorite_images",
        "anlas_transactions",
    )
}

/**
 * 探测既有库文件的结构（迁移的幂等判据）。
 *
 * sqlite-jdbc 没有 `SupportSQLiteDatabase` 那种包装，所以直接查
 * `sqlite_master` 与 `PRAGMA table_info`。查询都限制在元数据上，
 * 不会触碰任何业务数据。
 */
internal class SchemaProbe(private val connection: Connection) {

    /** 表是否存在（大小写不敏感，与 SQLite 的标识符规则一致）。 */
    fun hasTable(table: String): Boolean =
        connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
        ).use { statement ->
            statement.setString(1, table)
            statement.executeQuery().use { it.next() }
        }

    /** 列是否存在。表本身不存在时返回 false。 */
    fun hasColumn(table: String, column: String): Boolean {
        if (!hasTable(table)) return false
        // 表名不能参数化（PRAGMA 不接受占位符），这里的表名全部来自本文件的常量，
        // 不接受任何外部输入 —— 没有注入面。
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(`$table`)").use { rows ->
                while (rows.next()) {
                    if (rows.getString("name") == column) return true
                }
            }
        }
        return false
    }

    /** 当前 `PRAGMA user_version`。 */
    fun userVersion(): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA user_version").use { rows ->
                if (rows.next()) rows.getInt(1) else 0
            }
        }

    /** 库里是否存在任何一张本应用的业务表（用来判断"新库"还是"老库"）。 */
    fun hasAnyApplicationTable(): Boolean =
        SchemaDefinitions.TABLE_NAMES.any { hasTable(it) }
}
