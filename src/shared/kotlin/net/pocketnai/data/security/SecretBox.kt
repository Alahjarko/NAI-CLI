package net.pocketnai.data.security

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 通用小密钥盒：给"非 NovelAI Token 的敏感值"（例如代理密码）做加密。
 *
 * 与 [KeychainCredentialStore] 刻意分开：
 * - 它**不碰**凭据那套东西（`pocketnai_secure` 键值存储、`token_ciphertext` 这类顶层键），
 *   改动互不影响；
 * - 自己的 alias 独立，删掉它只会丢失代理密码，不会牵动已保存的 Token。
 *
 * 输出格式：`base64( iv(12B) || ciphertext+tag )` —— 与 Android 版逐字节同构
 * （IV 12 字节、GCM tag 128 位、标准 base64 带 padding、无换行），参数一位都没动。
 *
 * ## macOS 上的取舍：为什么这里**保留**了 AES-GCM，而 Token 却是直接进密钥链的
 * 两者的秘密与密文住在**不同的地方**，这是唯一的判据：
 * - Token（[KeychainCredentialStore]）：值本身存进密钥链，由 OS 加密并保管密钥 ——
 *   自己再套一层就必须把 AES 密钥和密文存在一起，等于没加密；
 * - 代理密码（这里）：密文要留在**调用方自己的首选项文件**里（`ProxyStore` 把
 *   [encrypt] 的返回值写进 `pocketnai_proxy.json` 的 `password_enc` 键），
 *   因此需要一个长期存在的加密密钥 —— 放在**密钥链**里，与密文分离：
 *   只拿到首选项文件的人解不开，密钥由系统看管。这不是"双层加密"，
 *   而是"密文在 A、密钥在 B"的常规做法，也正是 Android 版
 *   （密文在 SharedPreferences、密钥在 Keystore）的结构在 macOS 上的对应物。
 *
 * 因此这里用 JDK 的 `Cipher`（"AES/GCM/NoPadding"）而不是把值直接塞进密钥链：
 * 后者的前提是"密文不存在别处"，而这里密文恰恰就在调用方的首选项里。
 *
 * 密钥来源：**256 位 AES 密钥用 `SecRandomCopyBytes`（经 [Keychain] 接口）生成一次**，
 * 之后一直从密钥链读；每次加密的 IV 由 JDK 的 GCM 实现随机生成 ——
 * 与 Android 版 `cipher.iv` 的行为一致（GCM 不允许 IV 复用，所以不能把它固定下来）。
 */
class SecretBox(
    private val alias: String = DEFAULT_ALIAS,
    private val keychain: Keychain = Keychain.system(),
) {

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, aesKey())
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + ciphertext
        return Base64.getEncoder().encodeToString(packed)
    }

    /** 解密失败（数据被清、alias 变了）返回 null，由调用方当作"没设置过"。 */
    fun decrypt(encoded: String): String? = runCatching {
        val packed = Base64.getDecoder().decode(encoded)
        if (packed.size <= IV_BYTES) return null
        val iv = packed.copyOfRange(0, IV_BYTES)
        val ciphertext = packed.copyOfRange(IV_BYTES, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, aesKey(), GCMParameterSpec(TAG_BITS, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }.getOrNull()

    /**
     * 取本 alias 的 AES 密钥：读不到就生成一把并写进密钥链。
     *
     * 全程同步：两个线程同时加密时若各自生成一把密钥，后写的那把会覆盖先写的，
     * 先加密出来的密文就再也解不开了。
     *
     * 密钥链里的内容坏掉（解码失败、长度不对）时当成"没设置过"重新生成 ——
     * 与 [decrypt] 的降级方向一致：宁可丢掉代理密码，也不要卡在启动路径上。
     */
    private fun aesKey(): SecretKey = synchronized(keyLock) {
        val stored = keychain.read(keyHandle())
        val existing = stored?.let { encoded ->
            runCatching { Base64.getDecoder().decode(encoded) }
                .getOrNull()
                ?.takeIf { it.size == KEY_SIZE_BYTES }
        }
        val keyBytes = existing ?: keychain.randomBytes(KEY_SIZE_BYTES).also { fresh ->
            keychain.write(keyHandle(), Base64.getEncoder().encodeToString(fresh))
        }
        SecretKeySpec(keyBytes, ALGORITHM)
    }

    /** 密钥在密钥链里的条目名（保留 alias，便于与 Android 的 Keystore alias 对照）。 */
    private fun keyHandle(): KeychainKey = KeychainKey(KEYCHAIN_SERVICE, "$KEY_ACCOUNT_PREFIX$alias")

    private val keyLock = Any()

    private companion object {
        /**
         * SecretBox 专属的密钥链 service。
         *
         * 与凭据的 service 分开（对照 Android 上"自己的 alias 独立"那条纪律）：
         * `clear()` 清凭据时扫的是凭据 service，不会顺手删掉这里的代理密钥。
         */
        const val KEYCHAIN_SERVICE = "net.pocketnai.macos.secretbox"

        /** 条目名 = 前缀 + Android 的 alias，两边可以对照着看。 */
        const val KEY_ACCOUNT_PREFIX = "secretbox_key_"

        /** 与 Android 版同名：`pocketnai_extra_secret_key`。 */
        const val DEFAULT_ALIAS = "pocketnai_extra_secret_key"

        const val ALGORITHM = "AES"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val KEY_SIZE_BYTES = 32
    }
}
