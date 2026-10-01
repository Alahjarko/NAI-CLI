package net.pocketnai.data.local

import kotlinx.coroutines.flow.Flow

/**
 * 画廊收藏的读写接口。
 *
 * 桌面端口说明：由 [JdbcFavoriteImageDao] 手写 JDBC 实现，签名与返回类型保持不变。
 */
interface FavoriteImageDao {

    /**
     * 全部收藏的图片 id。
     *
     * 画廊一次性取回整份集合（而不是每张卡片查一次）：收藏量级是"用户手动点的"，
     * 几百条字符串在内存里判存在比发几百次查询便宜得多，
     * 而且瀑布流滚动时不会再碰数据库。
     */
    fun observeFavoriteImageIds(): Flow<List<String>>

    /** 详情页只关心这一张，单独查一次即可。 */
    fun observeIsFavorite(imageId: String): Flow<Boolean>

    /**
     * 用 `INSERT OR IGNORE`（原 `OnConflictStrategy.IGNORE`）：重复收藏同一个 id 是无操作。
     *
     * 收藏是幂等的开关，没有"后写覆盖先写"的语义，用 REPLACE 只会带来
     * SQLite "先 DELETE 再 INSERT" 那种不必要的写入 —— 而这里删的是被
     * `favorite_images` 通过外键引用的 `generated_images` 行时，级联后果更严重。
     */
    suspend fun add(entity: FavoriteImageEntity)

    suspend fun remove(imageId: String)

    suspend fun count(): Int
}
