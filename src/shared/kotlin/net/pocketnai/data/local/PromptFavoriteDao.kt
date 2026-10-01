package net.pocketnai.data.local

import kotlinx.coroutines.flow.Flow

/**
 * 收藏提示词的读写接口。
 *
 * 桌面端口说明：由 [JdbcPromptFavoriteDao] 手写 JDBC 实现，签名与返回类型保持不变。
 * 与 Room 时代一致的语义：
 * - [observeFavorites] 的排序由 SQL 的 `ORDER BY` 决定，不在内存里重排；
 * - [insert] 走 `INSERT OR IGNORE`（原 `OnConflictStrategy.IGNORE`），重复 id 是无操作。
 */
interface PromptFavoriteDao {

    /**
     * 收藏列表：最近用过的排在前面，其次按创建时间倒序。
     *
     * "快速填入"的体验主要由这个排序决定 —— 常用的几条会稳定停在前几位。
     */
    fun observeFavorites(): Flow<List<PromptFavoriteEntity>>

    suspend fun count(): Int

    suspend fun insert(entity: PromptFavoriteEntity)

    suspend fun markUsed(id: String, usedAt: Long)

    /** 同一目标的同名收藏视为重复，用来避免反复收藏同一条内容堆出一串一样的条目。 */
    suspend fun countSame(target: String, kind: String, content: String): Int

    suspend fun delete(id: String)
}
