package net.pocketnai.data.local

import java.io.File
import java.sql.Connection

/**
 * 本地历史数据库。
 *
 * 只保存文本与文件索引，绝不保存 PNG 二进制，也不保存 Token（规划书 7 与第 10 节）。
 * schema 与 Android 版第 7 版完全一致（DDL 见 [SchemaDefinitions]），升级走显式迁移，
 * **不用破坏性迁移回避问题**。
 *
 * 桌面端口说明：原本是 Room 的 `@Database` 抽象类，由注解处理器生成
 * `PocketNaiDatabase_Impl`（建库、迁移、校验、`Flow` 失效通知都在里面）。
 * 这里用 sqlite-jdbc 手写同一套行为，四件事逐条对上：
 *
 * 1. **建库**：新文件按"v1 建表 + 全链迁移"建到 v7 —— 与 Room 建库同构，
 *    不另抄一份 v7 的 `CREATE TABLE`（两条路径的列顺序迟早分叉）；
 * 2. **迁移**：读 `PRAGMA user_version`，从当前版本沿链升到 7，
 *    每一步带幂等探针，可从"迁移跑了一半"的状态安全接上（见 [migrate]）；
 * 3. **外键**：每条连接都执行 `PRAGMA foreign_keys = ON`（见 [SqliteConnectionPool]）——
 *    Room 生成实现做的事，漏掉它 `ON DELETE CASCADE` 会静默失效；
 * 4. **失效通知**：写入后经 [TableChangeNotifier] 唤醒所有可观察查询（见 [queryFlow]）。
 *
 * 构造入口是 [java.io.File]（应用传 `<appSupportDir>/pocketnai.db`），
 * **不再依赖 Android 的 `Context`**。
 *
 * 与 Room 的两处已知差异（都属于"桌面端没有对应设施"，不影响读写语义）：
 * - 不做打开的 schema 校验：Room 会拿实体与真实表逐列比对，不符就抛
 *   `IllegalStateException`。这里改成迁移时按探针判结构，原因是手写校验器容易
 *   比迁移本身还长；代价是"结构被外部改坏"不会在打开时就报错。
 * - 不支持多进程 / 多实例同时打开同一个文件。Room 也不支持（只是它有 `MultiInstanceInvalidationService`
 *   那套可选设施），桌面端单进程使用，不引入。
 */
class PocketNaiDatabase private constructor(
    val databaseFile: File,
    private val pool: SqliteConnectionPool,
) : AutoCloseable {

    /**
     * 表变更通知器。
     *
     * 四个 DAO 共用同一个实例：`generations` 的写入会级联影响
     * `generated_images` / `reference_images` / `favorite_images`，
     * 共用一份"全局版本号"是刻意的（理由见 [TableChangeNotifier]）。
     */
    private val notifier = TableChangeNotifier()

    private val connectionPool: SqliteConnectionPool get() = pool

    val generationDao: GenerationDao = JdbcGenerationDao(pool, notifier)

    val promptFavoriteDao: PromptFavoriteDao = JdbcPromptFavoriteDao(pool, notifier)

    val favoriteImageDao: FavoriteImageDao = JdbcFavoriteImageDao(pool, notifier)

    val anlasTransactionDao: AnlasTransactionDao = JdbcAnlasTransactionDao(pool, notifier)

    /** 供测试断言"写入确实唤醒了查询"；业务代码不需要它。 */
    internal fun changeNotifier(): TableChangeNotifier = notifier

    /**
     * 当前库里的 schema 版本（`PRAGMA user_version`）。
     *
     * 打开之后它恒为 [SchemaDefinitions.VERSION]；留成公开方法是为了让诊断与
     * 测试能直接断言"迁移真的写了版本号"，而不是靠再开一条 JDBC 连接去读。
     */
    fun schemaVersion(): Int = pool.use { connection -> SchemaProbe(connection).userVersion() }

    /** 当前连接上 `PRAGMA foreign_keys` 的实际取值（0 / 1）。 */
    fun foreignKeysEnabled(): Boolean = pool.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA foreign_keys").use { rows ->
                rows.next() && rows.getInt(1) == 1
            }
        }
    }

    override fun close() = pool.close()

    companion object {

        /**
         * 打开（必要时创建 / 升级）数据库。
         *
         * @param databaseFile 数据库文件路径，应用传 `<appSupportDir>/pocketnai.db`。
         *   父目录不存在时会自动创建（SQLite 自己不会建目录）。
         */
        fun build(databaseFile: File): PocketNaiDatabase {
            val pool = SqliteConnectionPool(databaseFile)
            try {
                migrate(pool)
            } catch (error: Throwable) {
                // 打开失败就把连接还回去，否则池子会泄漏到进程结束。
                runCatching { pool.close() }
                throw error
            }
            return PocketNaiDatabase(databaseFile, pool)
        }

        /**
         * 打开数据库并**保证它至少是 v7**；版本比 7 还新时直接报错。
         *
         * 报错而不是"硬着头皮打开"：那意味着用户装过更新版本的 MacNAI（或 Android 版），
         * 库里有本版本不认识的列与语义。继续读写会把"读不到的新字段"当成空值写回去，
         * 那是在**损坏**用户的库。宁可拒绝启动并让他升回新版本。
         */
        internal fun migrate(pool: SqliteConnectionPool) {
            pool.use { connection ->
                SchemaProbe(connection).userVersion().let { version ->
                    when {
                        version > SchemaDefinitions.VERSION -> throw IllegalStateException(
                            "数据库版本 $version 高于本版本支持的 ${SchemaDefinitions.VERSION}：" +
                                "$DATABASE_VERSION_HINT",
                        )
                        version == SchemaDefinitions.VERSION -> verifyExistingDatabase(connection)
                        else -> upgrade(connection, version)
                    }
                }
            }
        }

        /** 版本号已经对上了，但结构未必完整（迁移成功、写版本号之前掉电等极端情况）。 */
        private fun verifyExistingDatabase(connection: Connection) {
            val probe = SchemaProbe(connection)
            val missing = SchemaDefinitions.MIGRATION_STEPS.lastOrNull { !it.isApplied(probe) }
            if (missing != null) {
                // 结构比版本号旧：把缺的步骤补上（探针保证不会重复 ADD COLUMN）。
                upgrade(connection, missing.from)
            }
        }

        /**
         * 从 [fromVersion] 升到 v7，整体在**一个事务**里。
         *
         * 事务的意义：Room 的每次迁移都在独立事务里提交，中途失败时整体回滚；
         * 这里把整条链放进一个事务，语义更强 —— 要么整库到 v7，要么一点都不动。
         * 升级过程中掉电不会留下"v3 的表 + v5 的版本号"这种状态。
         *
         * [fromVersion] 为 0（新文件，或版本号没写上）时先跑一遍 v1 建表语句：
         * 所有 `CREATE ... IF NOT EXISTS` 对既有对象都是空操作，因此这一步对
         * "结构已经存在只是没记版本号"的库同样安全。
         */
        private fun upgrade(connection: Connection, fromVersion: Int) {
            // 事务形态与坑见 [inTransaction]：全程手动 BEGIN IMMEDIATE / COMMIT，
            // 不碰 JDBC 的 autoCommit（两者混用会报"事务里不能开事务"）。
            inTransaction(connection) {
                if (fromVersion < 1) {
                    execAll(connection, SchemaDefinitions.V1_STATEMENTS)
                }
                val probe = SchemaProbe(connection)
                SchemaDefinitions.MIGRATION_STEPS.forEach { step ->
                    if (step.from < fromVersion) return@forEach
                    // 探针为真说明这一步已经生效过（半升级恢复路径），跳过即可。
                    if (step.isApplied(probe)) return@forEach
                    execAll(connection, step.sql)
                }

                // identity hash：只在库里还没有记录时写。已有记录（Android 写下的）
                // 保持原样 —— 我们并不真的知道旧版本写的是哪个 hash，
                // 覆盖它反而会让用户回退到 Android 版时校验失败。
                if (!probe.hasTable("room_master_table")) {
                    execAll(connection, SchemaDefinitions.ROOM_MASTER_TABLE_SETUP)
                }

                connection.createStatement().use {
                    it.execute("PRAGMA user_version = ${SchemaDefinitions.USER_VERSION}")
                }
            }
        }

        private fun execAll(connection: Connection, statements: List<String>) {
            connection.createStatement().use { statement ->
                statements.forEach { statement.execute(it) }
            }
        }

        private const val DATABASE_VERSION_HINT: String =
            "请升级 MacNAI（或先用新版本打开一次）后再试；本版本拒绝以旧结构读写更新的库。"
    }
}
