package net.pocketnai.data.local

/**
 * 单张最终图片表（规划书 7.2）。
 *
 * 只保存文件索引与参数，不保存 PNG 二进制。
 * 删除生成记录时图片行级联删除；磁盘文件由文件存储层单独清理。
 *
 * 桌面端口说明：外键 `generationId → generations(id) ON UPDATE NO ACTION ON DELETE CASCADE`
 * 在 [SchemaDefinitions.CREATE_GENERATED_IMAGES] 的 DDL 里声明。它**只有在每条连接上
 * 都执行了 `PRAGMA foreign_keys = ON` 之后才真正生效**（见 [SqliteSupport]）——
 * SQLite 默认把这个 pragma 关着，漏掉它不会报任何错，只会让子行在父行删除后
 * 静默残留，表现为"删了记录，图片行还在"。
 */
data class GeneratedImageEntity(
    val id: String,
    val generationId: String,
    /** 同批序号，从 1 开始，对应文件名 0001.png。 */
    val ordinal: Int,
    /** 仅固定 Seed 模式下非空；随机模式的真实 Seed 需解析 PNG 元数据（第二层能力）。 */
    val seed: Long?,
    /** 相对应用私有目录的相对路径，避免设备迁移后绝对路径失效。 */
    val relativePath: String,
    val width: Int,
    val height: Int,
    val byteSize: Long,
    val sha256: String,
    val metadataJson: String?,
    val createdAt: Long,
    /** 已复制到系统相册后的 MediaStore URI。 */
    val exportedUri: String?,
)
