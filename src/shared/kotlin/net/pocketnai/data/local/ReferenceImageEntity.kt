package net.pocketnai.data.local

/**
 * 一次生成所用的参考图（《参考图功能规划书》5.4）。
 *
 * 独立成表而不是在 `generations` 上加几列：参考图数量可变、可选、带逐条参数，
 * 展平进父表会让父表的列数随功能增长而膨胀，也无法表达"挂了几张"。
 *
 * [relativePath] 是**内容寻址**的（`references/<sha256>.png`），因此不同历史可以指向
 * 同一个文件；删除某条历史只会级联删掉这里的行，文件由启动清理按"仍被引用的路径"回收，
 * 不会误删别的历史还在用的图。
 *
 * 枚举以名字符串存放，映射回领域模型的工作在 [Mappers]。
 *
 * 桌面端口说明：外键与索引改由 [SchemaDefinitions.CREATE_REFERENCE_IMAGES] /
 * [SchemaDefinitions.REFERENCE_IMAGES_INDEXES] 声明；级联删除同样依赖
 * 每条连接上的 `PRAGMA foreign_keys = ON`（见 [SqliteSupport]）。
 */
data class ReferenceImageEntity(
    val id: String,
    val generationId: String,
    /** [net.pocketnai.domain.model.ReferenceRole] 的名字。 */
    val role: String,
    /** 同一次生成内的顺序号，从 0 开始，与请求数组下标一一对应。 */
    val ordinal: Int,
    val relativePath: String,
    val width: Int,
    val height: Int,
    val byteSize: Long,
    val sha256: String,
    /** Image2Img 的 Strength；Vibe / Director 的 Reference Strength。 */
    val strength: Double?,
    /** Vibe / Director 的 Information Extracted。 */
    val informationExtracted: Double?,
    /** Precise Reference 的 Fidelity。 */
    val secondaryStrength: Double?,
    /** [net.pocketnai.domain.model.DirectorReferenceKind] 的 API 取值。 */
    val directorKind: String?,
    /** `encode-vibe` 产物的相对路径，仅 Vibe 使用。 */
    val vibeRelativePath: String?,
    val createdAt: Long,
)
