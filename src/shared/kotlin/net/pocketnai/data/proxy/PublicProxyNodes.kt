package net.pocketnai.data.proxy

import net.pocketnai.core.platform.Log
import net.pocketnai.core.platform.PlatformEnvironment
import net.pocketnai.domain.proxy.ProxyEndpoint
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 内置公益节点清单：从打包资源里的**加密**文件加载（AES-256-GCM），加载后内存缓存。
 *
 * ## 关于"不泄露"的边界（重要）
 * 加密密钥派生自代码里的固定串 —— 它能挡住"随手提取"（strings / 反编译常量
 * 拿不到明文清单），但**挡不住专业逆向**。因此把这批凭据当作**可轮换**的：
 * 一旦怀疑外泄，去代理服务商后台换密码，再用 `scripts/encrypt-proxy-nodes.mjs`
 * 重新生成 [ASSET_NAME] 即可。真正的用量与并发约束应放在**代理服务商后台**，
 * 不要只依赖客户端的每日统计。
 *
 * 文件格式：`base64( iv(12B) || ciphertext+tag )`，明文为多行 `host:port:user:pass`。
 *
 * ## 移植对照
 * Android 从 `context.assets.open(ASSET_NAME)` 读；macOS 从
 * [PlatformEnvironment.openResource] 读 —— 打包资源放在
 * `composeApp/src/jvmMain/resources/public_proxy_nodes.enc`（classpath 资源，
 * 装进 .app 后依旧可读），另外在 `composeApp/resources/` 保留一份给
 * `compose.desktop` 的 `appResourcesRootDir` 打包路径。
 * **加密参数逐字节保持不变**：同样的 KEY_SEED、SHA-256 派生、12 字节 IV、128 位 GCM tag，
 * 因此 Android 版那份 `.enc` 可以直接拷过来用，不需要重新加密。
 * 资源缺失时只记日志并返回空清单 —— 公益模式没有节点可用，但应用不该因此起不来。
 */
class PublicProxyNodes(private val environment: PlatformEnvironment) {

    @Volatile
    private var cache: List<ProxyEndpoint>? = null

    fun all(): List<ProxyEndpoint> = cache ?: synchronized(this) {
        cache ?: load().also { cache = it }
    }

    private fun load(): List<ProxyEndpoint> {
        // 资源缺失是**可预期**的状态（打包漏文件、或用户删掉了 app-support 下的副本），
        // 不是异常：返回空清单，让"公益模式没有可用节点"走调用方既有的空集分支。
        val asset = environment.openResource(ASSET_NAME)
        if (asset == null) {
            Log.w(TAG, "公益节点清单缺失（$ASSET_NAME），公益模式下没有可用节点")
            return emptyList()
        }

        return runCatching {
            // 对照 Android 的 `context.assets.open(...).bufferedReader().use { it.readText() }.trim()`。
            val encoded = asset.bufferedReader().use { it.readText() }.trim()
            // 对照 Android 的 `Base64.decode(encoded, Base64.NO_WRAP)`：
            // 清单由 Node 的 `Buffer.toString('base64')` 生成（标准字母表、带填充、单行无换行），
            // java.util 的基础解码器与 NO_WRAP 逐字节等价。
            val packed = Base64.getDecoder().decode(encoded)
            require(packed.size > IV_BYTES) { "proxy nodes payload too short" }

            val key = MessageDigest.getInstance("SHA-256")
                .digest(KEY_SEED.toByteArray(Charsets.UTF_8))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, packed.copyOfRange(0, IV_BYTES)),
            )
            val plain = String(cipher.doFinal(packed.copyOfRange(IV_BYTES, packed.size)), Charsets.UTF_8)

            plain.lineSequence().mapNotNull(::parseEndpoint).toList()
        }.onFailure {
            // 只记"读/解密失败"这个结构事实与异常本身；**绝不解密结果、绝不记节点端点与凭据**。
            Log.w(TAG, "公益节点清单读取或解密失败，按空清单处理", it)
        }.getOrDefault(emptyList())
    }

    private fun parseEndpoint(line: String): ProxyEndpoint? {
        val parts = line.trim().split(':')
        if (parts.size != 4) return null
        val port = parts[1].toIntOrNull() ?: return null
        if (parts[0].isBlank() || port !in 1..65535) return null
        return ProxyEndpoint(host = parts[0], port = port, username = parts[2], password = parts[3])
    }

    private companion object {
        /** 与 `data/network/ProxyNetwork.kt` 同一个 tag：代理诊断只记结构。 */
        const val TAG = "PocketNai/Proxy"

        const val ASSET_NAME = "public_proxy_nodes.enc"

        /** 与 `scripts/encrypt-proxy-nodes.mjs` 中的 KEY_SEED 保持一致。 */
        const val KEY_SEED = "PocketNAI public proxy v1"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
