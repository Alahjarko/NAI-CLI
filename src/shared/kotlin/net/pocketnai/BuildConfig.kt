package net.pocketnai

/**
 * 构建期常量（对照 Android 的 `BuildConfig`）。
 *
 * 与 Android 版保持同源语义：
 * - 所有请求走 `https://image.novelai.net`（`api.novelai.net` 已拒绝第三方 PST）；
 * - `UPDATE_REPO` 是应用内检查更新的仓库，换仓库只改这一处；
 * - `DEBUG` 只控制诊断日志开关，**不改变任何日志脱敏行为**。
 */
object BuildConfig {
    const val DEBUG: Boolean = true

    const val NOVELAI_API_BASE_URL: String = "https://image.novelai.net"

    /**
     * macOS 端的更新分发**不做 APK 那条链路**（对照本仓库 iOS 版的同一决定）：
     * 桌面端没有"读包签名 → 与在装实例比对 → 交给系统静默覆盖安装"的等价能力，
     * 而且 Release 里挂的是 Android APK，在这里根本装不上。
     *
     * 因此这里保留仓库标识只用于两件事：启动后只读地检查"有没有更新的构建"，以及把用户
     * 引到 Release 页面。**不做签名校验、不调安装器**；下载只作为可选便利保留
     * （见 `UpdateDownloader`，其中已删掉签名与安装职责，并有中文说明为何删除）。
     */
    const val UPDATE_REPO: String = "Alahjarko/PocketNAI"

    /** 桌面端口自己的版本号，与 Android 的 versionCode 不同源。 */
    const val VERSION_NAME: String = "0.1.0"
    const val VERSION_CODE: Int = 1
}
