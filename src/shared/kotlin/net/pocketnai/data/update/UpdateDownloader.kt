package net.pocketnai.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.pocketnai.core.AppError
import net.pocketnai.core.ErrorCode
import net.pocketnai.core.Outcome
import net.pocketnai.core.platform.PlatformEnvironment
import net.pocketnai.domain.update.UpdateRelease
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 把 Release 附件下载到应用 cache 目录。
 *
 * ## 为什么落在 cache
 * 更新包**不是用户数据**：不进 files 目录、不参与任何历史与清理逻辑，
 * 系统需要空间时可以整个回收，下次点"下载"重新拉一遍即可。
 *
 * ## macOS 上**没有**签名校验，也没有"交给安装器"这一步（刻意删除，不是漏了）
 * Android 版原来做两件 macOS 做不到的事：
 * 1. 用 `PackageManager.getPackageArchiveInfo` 读出下载包的签名证书，与当前安装比对，
 *    不一致就以 [ErrorCode.UPDATE_SIGNATURE_MISMATCH] 丢弃；
 * 2. 把文件交给系统安装器（`Intent` + FileProvider）覆盖安装。
 *
 * macOS 侧两者都**没有等价物**，因此这里直接把它们移除，而不是留一个"永远通过"的假校验：
 * - 没有"读一个 .app/.dmg 的代码签名再与当前运行实例比对"的通用 API，更没有
 *   "把任意未知来源的包交给系统静默覆盖安装"的通道 —— macOS 的应用更新走
 *   Sparkle 式的签名更新源，或者干脆由用户把新的 `.app` 拖进"应用程序"；
 * - 而且我们发布的是 Android APK 附件，它在本机**根本装不上**，校验它毫无意义；
 * - 一个恒真的 `signatureMatchesInstalled()` 比没有更糟：它会让后来的人以为
 *   "信任链已经检查过了"，而实际上什么都没检查（`docs/architecture/06-原生移植边界与验收.md`
 *   的 A10 已经点明 Android 那套判据本身就不能直接复制成别的平台的严格信任模型）。
 *
 * **用户该怎么做**：界面在发现新版本时引导用户打开 Release 页面
 * （`https://github.com/<UPDATE_REPO>/releases/latest`），自行下载并替换 macOS 安装包。
 * 本类只负责把附件按原样取回本地，供界面展示或用户自行处置，**不代表这个包可以装**。
 *
 * ## 剩下真正实现的东西
 * 匿名 GET（不带任何凭据）、`.part` 中转再改名、进度回调、以及"已下好的文件直接复用"。
 * 复用判据从"签名一致"退化成"文件在 + 大小与 Release 声明的一致"：它能挡住半截文件与
 * "HTTP 200 但其实是错误页"这类情况，**但它不建立任何信任**，别把它当签名校验用。
 */
class UpdateDownloader(
    private val environment: PlatformEnvironment,
    private val client: OkHttpClient,
) {

    suspend fun download(
        release: UpdateRelease,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Outcome<File> = withContext(Dispatchers.IO) {
        if (!release.downloadUrl.startsWith("https://")) {
            return@withContext Outcome.Failure(
                AppError.of(ErrorCode.UPDATE_DOWNLOAD_FAILED, detail = "insecure url"),
            )
        }

        val dir = File(environment.cacheDir, DIR_NAME).apply { mkdirs() }
        val target = File(dir, "PocketNAI-${release.versionCode}.apk")

        // 已经下好的文件直接复用：用户在界面上再点一次"下载"时，
        // 不该把 20+ MB 重新下一遍（对照 Android 版"授权回来后再点一次安装"的场景）。
        if (isCompleteDownload(target, release.sizeBytes)) {
            return@withContext Outcome.Success(target)
        }

        val request = Request.Builder()
            .url(release.downloadUrl)
            .header("User-Agent", USER_AGENT)
            .build()

        // 先写 .part 再改名：中断留下的半截文件不会被当成完整产物。
        val partial = File(dir, "${target.name}.part")
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Outcome.Failure(
                        AppError.of(ErrorCode.UPDATE_DOWNLOAD_FAILED, detail = "http ${response.code}"),
                    )
                }
                val body = response.body
                    ?: return@withContext Outcome.Failure(
                        AppError.of(ErrorCode.UPDATE_DOWNLOAD_FAILED, detail = "empty body"),
                    )

                val total = body.contentLength().takeIf { it > 0 } ?: release.sizeBytes
                body.byteStream().use { input ->
                    FileOutputStream(partial).use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        var downloaded = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            partial.delete()
            return@withContext Outcome.Failure(AppError.of(ErrorCode.NETWORK_UNAVAILABLE))
        }

        if (!partial.renameTo(target)) {
            partial.delete()
            return@withContext Outcome.Failure(
                AppError.of(ErrorCode.UPDATE_DOWNLOAD_FAILED, detail = "rename failed"),
            )
        }

        // 唯一的完整性下限：长度要与 Release 声明一致。**这不是信任校验** ——
        // 长度对的包同样可能是被替换过的，macOS 上没有任何可用的等价判据能发现这一点。
        if (!isCompleteDownload(target, release.sizeBytes)) {
            target.delete()
            return@withContext Outcome.Failure(
                AppError.of(ErrorCode.UPDATE_DOWNLOAD_FAILED, detail = "size mismatch"),
            )
        }

        Outcome.Success(target)
    }

    /**
     * 这份文件是否可以作为"已经下好"直接复用。
     *
     * - `target` 只在 `.part` 下载完整之后才改名出现，所以"文件在"已经排除了中断的半截文件；
     * - GitHub 的 asset 元数据带 `size`，有它就再比一次长度，挡住"HTTP 200 + 错误页"；
     * - 长度未知（`sizeBytes` 为 null）时不做判断，直接复用。
     */
    private fun isCompleteDownload(file: File, expectedBytes: Long?): Boolean {
        if (!file.isFile) return false
        if (expectedBytes == null || expectedBytes <= 0L) return true
        return file.length() == expectedBytes
    }

    private companion object {
        const val DIR_NAME = "updates"

        /**
         * GitHub 要求请求带 `User-Agent`，缺了会直接 403。
         *
         * 值保持与 Android 版一致（`GitHubReleaseApi` 用的是同一个串）：
         * 它只用于满足 GitHub 的要求，不参与任何路由或鉴权。
         */
        const val USER_AGENT = "PocketNAI-Android"
        const val BUFFER_BYTES = 64 * 1024
    }
}
