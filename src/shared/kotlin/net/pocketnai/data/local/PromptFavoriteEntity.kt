package net.pocketnai.data.local

/**
 * 本地收藏的提示词片段（规划书 8.4 的 Prompt Chunk）。
 *
 * 刻意不加索引：这张表只有用户手动收藏的几十到几百条，
 * 而为它建索引会让数据库迁移多出"索引名必须与期望完全一致"的校验负担，
 * 收益与风险不成比例。
 *
 * 枚举以名字符串存放，映射回领域模型的工作放在仓库层（与 generations 表一致）。
 *
 * 桌面端口说明：表结构见 [SchemaDefinitions.CREATE_PROMPT_FAVORITES]，主键是 `id`。
 */
data class PromptFavoriteEntity(
    val id: String,
    /** [net.pocketnai.domain.model.PromptFavoriteKind] 的名字。 */
    val kind: String,
    val name: String,
    val content: String,
    /** 分组名；空字符串表示未分组。 */
    val category: String,
    /** [net.pocketnai.domain.model.PromptTarget] 的名字。 */
    val target: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long,
)
