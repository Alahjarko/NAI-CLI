package net.pocketnai.data.local

import kotlinx.coroutines.flow.Flow
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * [PromptFavoriteDao] 的 sqlite-jdbc 实现。
 *
 * 排序完全交给 SQL（与原 `@Query` 的 `ORDER BY lastUsedAt DESC, createdAt DESC` 一致），
 * 不在内存里重排 —— 收藏列表的"常用几条稳定停在前几位"就是靠这个排序。
 */
internal class JdbcPromptFavoriteDao(
    private val pool: SqliteConnectionPool,
    private val notifier: TableChangeNotifier,
) : PromptFavoriteDao {

    override fun observeFavorites(): Flow<List<PromptFavoriteEntity>> = queryFlow(notifier) {
        pool.use { connection ->
            connection.prepareStatement(
                "SELECT * FROM prompt_favorites ORDER BY lastUsedAt DESC, createdAt DESC",
            ).use { statement ->
                statement.executeQuery().use { rows -> readFavorites(rows) }
            }
        }
    }

    override suspend fun count(): Int = io {
        pool.use { connection -> readInt(connection, "SELECT COUNT(*) FROM prompt_favorites") }
    }

    /** `INSERT OR IGNORE`（原 `OnConflictStrategy.IGNORE`）：重复 id 是无操作。 */
    override suspend fun insert(entity: PromptFavoriteEntity) {
        io {
            pool.use { connection ->
                connection.prepareStatement(SQL_INSERT).use { statement ->
                    bind(statement, entity)
                    statement.executeUpdate()
                }
            }
        }
        notifier.notifyChanged()
    }

    override suspend fun markUsed(id: String, usedAt: Long) {
        io {
            pool.use { connection ->
                connection.prepareStatement(
                    "UPDATE prompt_favorites SET lastUsedAt = ? WHERE id = ?",
                ).use { statement ->
                    statement.setLong(1, usedAt)
                    statement.setString(2, id)
                    statement.executeUpdate()
                }
            }
        }
        notifier.notifyChanged()
    }

    override suspend fun countSame(target: String, kind: String, content: String): Int = io {
        pool.use { connection ->
            connection.prepareStatement(
                """
                SELECT COUNT(*) FROM prompt_favorites
                WHERE target = ? AND kind = ? AND content = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, target)
                statement.setString(2, kind)
                statement.setString(3, content)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 0 }
            }
        }
    }

    override suspend fun delete(id: String) {
        io {
            pool.use { connection ->
                connection.prepareStatement("DELETE FROM prompt_favorites WHERE id = ?").use { statement ->
                    statement.setString(1, id)
                    statement.executeUpdate()
                }
            }
        }
        notifier.notifyChanged()
    }

    private fun readFavorites(rows: ResultSet): List<PromptFavoriteEntity> = buildList {
        while (rows.next()) {
            add(
                PromptFavoriteEntity(
                    id = rows.getString("id"),
                    kind = rows.getString("kind"),
                    name = rows.getString("name"),
                    content = rows.getString("content"),
                    category = rows.getString("category"),
                    target = rows.getString("target"),
                    createdAt = rows.getLong("createdAt"),
                    updatedAt = rows.getLong("updatedAt"),
                    lastUsedAt = rows.getLong("lastUsedAt"),
                ),
            )
        }
    }

    private fun bind(statement: PreparedStatement, entity: PromptFavoriteEntity) {
        statement.setString(1, entity.id)
        statement.setString(2, entity.kind)
        statement.setString(3, entity.name)
        statement.setString(4, entity.content)
        statement.setString(5, entity.category)
        statement.setString(6, entity.target)
        statement.setLong(7, entity.createdAt)
        statement.setLong(8, entity.updatedAt)
        statement.setLong(9, entity.lastUsedAt)
    }

    private companion object {
        val SQL_INSERT: String =
            """
            INSERT OR IGNORE INTO prompt_favorites (
                `id`, `kind`, `name`, `content`, `category`, `target`,
                `createdAt`, `updatedAt`, `lastUsedAt`
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
    }
}
