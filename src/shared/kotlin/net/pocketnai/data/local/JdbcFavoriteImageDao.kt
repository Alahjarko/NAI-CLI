package net.pocketnai.data.local

import kotlinx.coroutines.flow.Flow

/**
 * [FavoriteImageDao] 的 sqlite-jdbc 实现。
 *
 * 两条可观察查询都是"一次取回整份集合/单个布尔"，与 Room 时代一致：画廊不在
 * 滚动时为每张卡片查一次数据库。
 */
internal class JdbcFavoriteImageDao(
    private val pool: SqliteConnectionPool,
    private val notifier: TableChangeNotifier,
) : FavoriteImageDao {

    override fun observeFavoriteImageIds(): Flow<List<String>> = queryFlow(notifier) {
        pool.use { connection -> readStrings(connection, "SELECT imageId FROM favorite_images") }
    }

    /**
     * 原语句是 `SELECT EXISTS(SELECT 1 FROM favorite_images WHERE imageId = :imageId)`。
     *
     * SQLite 里 `EXISTS` 返回 0 / 1，这里直接读第一列的整数再转布尔，语义与
     * Room 生成的实现相同（Room 也是按 `cursor.getInt(0) != 0` 转的）。
     */
    override fun observeIsFavorite(imageId: String): Flow<Boolean> = queryFlow(notifier) {
        pool.use { connection ->
            connection.prepareStatement(
                "SELECT EXISTS(SELECT 1 FROM favorite_images WHERE imageId = ?)",
            ).use { statement ->
                statement.setString(1, imageId)
                statement.executeQuery().use { rows ->
                    rows.next() && rows.getInt(1) != 0
                }
            }
        }
    }

    /**
     * `INSERT OR IGNORE`（原 `OnConflictStrategy.IGNORE`）：重复收藏同一个 id 是无操作。
     *
     * 收藏是幂等的开关，没有"后写覆盖先写"的语义；用 REPLACE 会走 SQLite 的
     * "先 DELETE 再 INSERT"，而这一行正是 `favorite_images` 自己的主键行，
     * 删除它没有任何好处，只会多一次写入。
     */
    override suspend fun add(entity: FavoriteImageEntity) {
        io {
            pool.use { connection ->
                connection.prepareStatement(
                    "INSERT OR IGNORE INTO favorite_images (`imageId`, `createdAt`) VALUES (?, ?)",
                ).use { statement ->
                    statement.setString(1, entity.imageId)
                    statement.setLong(2, entity.createdAt)
                    statement.executeUpdate()
                }
            }
        }
        notifier.notifyChanged()
    }

    override suspend fun remove(imageId: String) {
        io {
            pool.use { connection ->
                connection.prepareStatement("DELETE FROM favorite_images WHERE imageId = ?").use { statement ->
                    statement.setString(1, imageId)
                    statement.executeUpdate()
                }
            }
        }
        notifier.notifyChanged()
    }

    override suspend fun count(): Int = io {
        pool.use { connection -> readInt(connection, "SELECT COUNT(*) FROM favorite_images") }
    }
}
