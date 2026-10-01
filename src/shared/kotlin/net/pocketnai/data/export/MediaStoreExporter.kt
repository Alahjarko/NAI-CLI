package net.pocketnai.data.export

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.pocketnai.core.AppError
import net.pocketnai.core.ErrorCode
import net.pocketnai.core.Outcome
import java.io.File
import java.io.IOException

/**
 * 把私有目录里的 PNG 复制到用户选定的目录（规划书 4.4）。
 *
 * 语义要点：“保存”是**复制**，不是移动。PocketNAI 私有历史保持不变；
 * 反之，删除私有历史也不会删除用户已经导出出去的副本。
 *
 * ## 移植对照：MediaStore → 用户选的目录
 * Android 走 `ContentResolver` + `MediaStore.Images`（Android 10+，系统决定落盘位置）
 * 或公共 `Pictures/<相册名>` 目录 + 媒体扫描（Android 9 及以下），两者都**不需要**用户选位置。
 * macOS 没有"应用可写的系统相册"这种东西，等价且更符合桌面习惯的做法是：
 * **界面先让用户选一个目录**（`ui/platform/FileDialogs.pickDirectory`），
 * 再把那个目录作为普通 [File] 传进来，这里只做最普通的文件复制。
 *
 * 类名保留 `MediaStoreExporter`（与 Android 版同名，便于逐文件对照移植进度；
 * `MediaStore` 在本平台不存在，但类型名不是契约）。真正跨平台共享的语义是
 * [uniqueFile] 的“重名不覆盖”，这条与 Android 9 那套实现逐字一致。
 *
 * 构造函数不带 `Context`/`PlatformEnvironment`：目标目录完全由调用方给出，
 * 本类除了 [File] 之外什么都不需要。
 */
class MediaStoreExporter {

    /**
     * 把 [source] 复制进 [destinationDir]，文件名用 [displayName]（重名则追加 ` (2)`、` (3)`）。
     *
     * 返回成功落盘的那个 [File]（绝对路径可供界面展示/写入"已导出"标记）。
     */
    suspend fun export(source: File, displayName: String, destinationDir: File): Outcome<File> =
        withContext(Dispatchers.IO) {
            if (!source.isFile) {
                return@withContext Outcome.Failure(
                    AppError.of(ErrorCode.SAVE_TO_GALLERY_FAILED, detail = "源文件不存在: ${source.name}"),
                )
            }
            try {
                // 目标目录不存在就创建（对照 Android 版 "无法创建相册目录" 那一条分支）。
                if (!destinationDir.exists() && !destinationDir.mkdirs()) {
                    return@withContext Outcome.Failure(
                        AppError.of(ErrorCode.SAVE_TO_GALLERY_FAILED, detail = "无法创建目标目录"),
                    )
                }

                val target = uniqueFile(destinationDir, safeName(displayName))
                source.inputStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                Outcome.Success(target)
            } catch (e: IOException) {
                Outcome.Failure(
                    AppError.of(ErrorCode.SAVE_TO_GALLERY_FAILED, detail = e.message.orEmpty()),
                )
            } catch (e: SecurityException) {
                Outcome.Failure(
                    AppError.of(ErrorCode.SAVE_TO_GALLERY_FAILED, detail = "没有写入目标目录的权限"),
                )
            }
        }

    /**
     * 只保留文件名部分，丢掉任何目录成分。
     *
     * Android 把 `displayName` 交给 `MediaStore`，由系统拒绝路径分隔符；
     * 这里换成普通 [File] 写入后，`displayName` 里若混进 `../` 就会写到**用户没选的位置**。
     * 展示名来自用户自己写的标题（`PromptTitle.exportFileName`），
     * 所以这不是假想的攻击面，而是"能不能越出所选目录"这个真实问题。
     */
    private fun safeName(displayName: String): String =
        displayName.substringAfterLast('/').substringAfterLast('\\').ifBlank { DEFAULT_NAME }

    /** 重名时追加 ` (2)`、` (3)`，绝不覆盖。 */
    private fun uniqueFile(directory: File, displayName: String): File {
        val base = displayName.substringBeforeLast('.', displayName)
        val extension = displayName.substringAfterLast('.', "png")
        var candidate = File(directory, displayName)
        var index = 2
        while (candidate.exists()) {
            candidate = File(directory, "$base ($index).$extension")
            index++
        }
        return candidate
    }

    private companion object {
        const val DEFAULT_NAME = "image.png"
    }
}
