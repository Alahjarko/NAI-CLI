package net.pocketnai.data.local

import kotlinx.coroutines.flow.Flow
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * [AnlasTransactionDao] 的 sqlite-jdbc 实现。
 *
 * 这条流水是"本机观察到的"账目参考，扣费事实以服务端为准（AGENTS.md「余额与费用」）：
 * 因此这里的读写都刻意保持最简单 —— 没有外键、没有级联，`clearAll` 就是清表。
 */
internal class JdbcAnlasTransactionDao(
    private val pool: SqliteConnectionPool,
    private val notifier: TableChangeNotifier,
) : AnlasTransactionDao {

    /**
     * `INSERT OR REPLACE`（原 `OnConflictStrategy.REPLACE`）。
     *
     * 流水行的 id 由仓库层每次现生成（UUID），重复只可能来自"同一条流水写两次"，
     * 那时覆盖才是正确语义。这张表没有被任何子表引用，所以 REPLACE 的
     * "先 DELETE 再 INSERT" 不会波及其他行。
     */
    override suspend fun insert(entity: AnlasTransactionEntity) {
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

    override fun observeRecent(limit: Int): Flow<List<AnlasTransactionEntity>> = queryFlow(notifier) {
        pool.use { connection ->
            connection.prepareStatement(
                "SELECT * FROM anlas_transactions ORDER BY createdAt DESC LIMIT ?",
            ).use { statement ->
                statement.setInt(1, limit)
                statement.executeQuery().use { rows -> readTransactions(rows) }
            }
        }
    }

    override suspend fun allTransactions(): List<AnlasTransactionEntity> = io {
        pool.use { connection ->
            connection.prepareStatement(
                "SELECT * FROM anlas_transactions ORDER BY createdAt DESC",
            ).use { statement ->
                statement.executeQuery().use { rows -> readTransactions(rows) }
            }
        }
    }

    override suspend fun clearAll() {
        io {
            pool.use { connection ->
                connection.createStatement().use { it.executeUpdate("DELETE FROM anlas_transactions") }
            }
        }
        notifier.notifyChanged()
    }

    override suspend fun count(): Int = io {
        pool.use { connection -> readInt(connection, "SELECT COUNT(*) FROM anlas_transactions") }
    }

    private fun readTransactions(rows: ResultSet): List<AnlasTransactionEntity> = buildList {
        while (rows.next()) {
            add(
                AnlasTransactionEntity(
                    id = rows.getString("id"),
                    accountFingerprint = rows.getString("accountFingerprint"),
                    // 没有外键：生成记录被清理后这一列可能指向已不存在的 id，照样读出来。
                    generationId = rows.getStringOrNull("generationId"),
                    actionType = rows.getString("actionType"),
                    anlasSpent = rows.getLong("anlasSpent"),
                    v5AllowanceDelta = rows.getIntOrNull("v5AllowanceDelta"),
                    balanceAfter = rows.getLongOrNull("balanceAfter"),
                    description = rows.getString("description"),
                    createdAt = rows.getLong("createdAt"),
                ),
            )
        }
    }

    private fun bind(statement: PreparedStatement, entity: AnlasTransactionEntity) {
        statement.setString(1, entity.id)
        statement.setString(2, entity.accountFingerprint)
        statement.setString(3, entity.generationId)
        statement.setString(4, entity.actionType)
        statement.setLong(5, entity.anlasSpent)
        statement.setNullable(6, entity.v5AllowanceDelta)
        statement.setNullable(7, entity.balanceAfter)
        statement.setString(8, entity.description)
        statement.setLong(9, entity.createdAt)
    }

    private companion object {
        val SQL_INSERT: String =
            """
            INSERT OR REPLACE INTO anlas_transactions (
                `id`, `accountFingerprint`, `generationId`, `actionType`, `anlasSpent`,
                `v5AllowanceDelta`, `balanceAfter`, `description`, `createdAt`
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
    }
}
