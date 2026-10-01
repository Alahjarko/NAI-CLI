package net.pocketnai.core.platform

import java.io.File
import java.io.InputStream

/**
 * 应用运行环境（对照 Android 的 `Context` 在移植代码里被用到的部分）。
 *
 * Android 版通过 `Context` 拿到 `filesDir` / `cacheDir` / `assets` / `SharedPreferences`；
 * 移植时把这四项收进一个对象，**其余业务代码保持原样**。
 *
 * 目录布局刻意与 Android 同名，便于与 `GenerationFileStore` 的相对路径互换：
 * ```
 * ~/Library/Application Support/MacNAI/
 *   files/       ← 对照 Context.filesDir（生成图片、参考图、vibe 缓存）
 *   cache/       ← 对照 Context.cacheDir（incoming、previews，可随时清理）
 *   prefs/       ← 对照 SharedPreferences（非敏感设置；Token 不在这里，见 CredentialStore）
 *   resources/   ← 对照 assets（public_proxy_nodes.enc 等打包资源）
 * ```
 */
class PlatformEnvironment(
    val appSupportDir: File,
    val bundleResourcesDir: File? = null,
) {
    /** 对照 `Context.filesDir`。 */
    val filesDir: File = File(appSupportDir, "files").ensureDir()

    /** 对照 `Context.cacheDir`。 */
    val cacheDir: File = File(appSupportDir, "cache").ensureDir()

    /** 非敏感首选项根目录（对照 `Context.getSharedPreferences`）。 */
    val prefsDir: File = File(appSupportDir, "prefs").ensureDir()

    /**
     * 对照 `Context.assets.open(...)`，返回可读取的字节流；找不到时返回 null。
     *
     * 查找顺序：
     * 1. classpath 资源（`src/jvmMain/resources/`，打包进 .app 后依旧可读）——
     *    移植时 `public_proxy_nodes.enc` 走这条；
     * 2. bundle 内显式指定的资源目录（如果有）；
     * 3. app-support 目录下的 `resources/`（允许用户就地替换，便于代理节点轮换）。
     */
    fun openResource(name: String): InputStream? {
        javaClass.classLoader?.getResourceAsStream(name)?.let { return it }
        bundleResourcesDir?.let { dir -> File(dir, name).takeIf { it.isFile }?.let { return it.inputStream() } }
        File(appSupportDir, "resources/$name").takeIf { it.isFile }?.let { return it.inputStream() }
        return null
    }

    /** [openResource] 的文件形式；调用方需要 `File`（例如要计算哈希）时用这个。 */
    fun resourceFile(name: String): File? {
        bundleResourcesDir?.let { dir -> File(dir, name).takeIf { it.isFile }?.let { return it } }
        return File(appSupportDir, "resources/$name").takeIf { it.isFile }
    }

    /** 对照 `Context.getSharedPreferences(name, MODE_PRIVATE)`。 */
    fun keyValueStore(name: String): KeyValueStore =
        JsonFileKeyValueStore(File(prefsDir, "$name.json"))

    private fun File.ensureDir(): File = apply { if (!exists()) mkdirs() }

    companion object {
        /**
         * 默认位置由 PlatformPaths 按系统选择；macOS 保持 `~/Library/Application Support/MacNAI`。
         *
         * macOS 上 `Application Support` 是用户级数据目录的正规位置（对照 Android 的应用私有目录）；
         * 不写 `~/Documents`（那是用户文档，不该被应用塞私有数据）。
         */
        fun default(): PlatformEnvironment {
            val root = PlatformPaths().dataDirectory()
            return PlatformEnvironment(appSupportDir = root)
        }

        /** 测试用：把全部状态放进一个临时目录，互不干扰。 */
        fun inDirectory(dir: File): PlatformEnvironment = PlatformEnvironment(appSupportDir = dir)
    }
}
