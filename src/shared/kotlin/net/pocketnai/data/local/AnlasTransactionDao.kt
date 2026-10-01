package net.pocketnai.data.local

import kotlinx.coroutines.flow.Flow

/**
 * Anlas 流水账目的读写接口。
 *
 * 桌面端口说明：由 [JdbcAnlasTransactionDao] 手写 JDBC 实现，签名与返回类型保持不变
 * （含 [observeRecent] 的默认参数 `limit = 100`）。
 */
interface AnlasTransactionDao {

    /**
     * 写入一条流水。
     *
     * 用 `INSERT OR REPLACE`（原 `OnConflictStrategy.REPLACE`）：账目行的 id 是
     * 每次写入现生成的新 UUID，重复只可能来自"同一条流水被写两次"，
     * 此时覆盖是正确语义。这里没有子表引用流水，所以 REPLACE 的"先删后插"
     * 不会波及任何其他行。
     */
    suspend fun insert(entity: AnlasTransactionEntity)

    fun observeRecent(limit: Int = 100): Flow<List<AnlasTransactionEntity>>

    suspend fun allTransactions(): List<AnlasTransactionEntity>

    suspend fun clearAll()

    suspend fun count(): Int
}
