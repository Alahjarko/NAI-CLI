package net.pocketnai.data.local

/**
 * Anlas 点数消耗流水账目。
 *
 * 记录每次生成、局部重绘、高清放大等操作所观察到的 Anlas 消耗、操作类型及变动后余额。
 *
 * 桌面端口说明：表结构见 [SchemaDefinitions.CREATE_ANLAS_TRANSACTIONS]，
 * `createdAt` 上的索引见 [SchemaDefinitions.ANLAS_TRANSACTIONS_INDEXES]。
 * 注意 `generationId` 此处**没有**外键约束（与 Android 版一致）：流水是账目快照，
 * 不该因为用户清理历史图片而消失。
 */
data class AnlasTransactionEntity(
    val id: String,
    val accountFingerprint: String,
    val generationId: String?,
    val actionType: String,
    val anlasSpent: Long,
    val v5AllowanceDelta: Int?,
    val balanceAfter: Long?,
    val description: String,
    val createdAt: Long,
)
