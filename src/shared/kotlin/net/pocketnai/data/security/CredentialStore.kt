package net.pocketnai.data.security

import net.pocketnai.core.Hashing
import net.pocketnai.core.platform.KeyValueStore
import net.pocketnai.core.platform.Log
import net.pocketnai.core.platform.PlatformEnvironment
import net.pocketnai.domain.account.SavedAccount
import java.util.UUID

/** 凭据来源。决定界面文案、以及失效后该引导用户做什么。 */
enum class CredentialType {
    /** 用户在 NovelAI 网页生成的 Persistent API Token。官方推荐的第三方接入方式。 */
    PERSISTENT_API_TOKEN,

    /** 邮箱 + 密码在本地派生出 Access Key 后换来的短期 Access Token。 */
    ACCOUNT_SESSION,
    ;

    companion object {
        /**
         * 从存储里的名字解析类型。
         *
         * 缺失或无法识别时一律按 [PERSISTENT_API_TOKEN] 处理：
         * 升级前保存的凭据本来就只可能是 PST，这是唯一安全且不打扰用户的降级方向。
         */
        fun fromNameOrDefault(raw: String?): CredentialType =
            entries.firstOrNull { it.name == raw } ?: PERSISTENT_API_TOKEN
    }
}

/**
 * 一份已保存的凭据。
 *
 * [token] 是真正放进 `Authorization: Bearer` 的值 —— 生成链路只关心这一个字段，
 * 因此不需要为两种凭据复制两套 API。
 */
data class StoredCredential(
    val token: String,
    val type: CredentialType,
    /** 仅用于会话信息与诊断，**不作为拒绝请求的依据**（服务端返回 401 才是）。 */
    val createdAtEpochMillis: Long? = null,
)

/** 用于界面展示的脱敏标识，不含任何 Token 片段。 */
data class CredentialHint(
    val type: CredentialType,
    val length: Int,
    val fingerprint: String,
)

/**
 * 凭据的本地存储抽象。
 *
 * 只允许把 Token 放在受保护的本地存储里，禁止写入普通首选项、数据库、
 * 图片元数据、剪贴板或日志（规划书第 10 节）。
 * Access Key、密码等中间值**一律不允许**出现在这里。
 *
 * ## 多账号那几个方法上的"默认假实现"不要依赖（AGENTS.md）
 * [listAccounts] / [activeAccountId] / [saveAccount] / [switchAccount] / [renameAccount] /
 * [deleteAccount] 在接口上带着"返回空 / 返回 null / 直接返回 false"的占位实现，
 * 那是给"只关心单账号"的调用方留的编译便利，**不是可用行为**：调用方一旦落到默认实现上，
 * 表现是"功能静默失效且没有编译错误"（切账号点了没反应、账号列表永远为空）。
 * 真实行为只在实现类里 —— macOS 上是 [KeychainCredentialStore]（六个方法**全部**重写）。
 * 以后加方法请直接写进实现类，不要再往这个接口里塞占位默认值。
 */
interface CredentialStore {

    fun hasCredential(): Boolean

    /** 读取凭据；密钥失效或数据损坏时返回 null 并清理残留。 */
    fun load(): StoredCredential?

    fun save(credential: StoredCredential)

    fun clear()

    /** 界面展示用的脱敏标识，不包含任何 Token 片段。 */
    fun hint(): CredentialHint?

    /** 获取所有已保存的账号列表。 */
    fun listAccounts(): List<SavedAccount> = emptyList()

    /** 当前处于激活状态的账号 ID。 */
    fun activeAccountId(): String? = null

    /** 新增或更新一个命名账号，并返回保存后的账号。 */
    fun saveAccount(name: String, credential: StoredCredential): SavedAccount {
        save(credential)
        val hint = hint()
        return SavedAccount(
            id = "default",
            name = name,
            type = credential.type,
            tokenFingerprint = hint?.fingerprint ?: "unknown",
            createdAtEpochMillis = credential.createdAtEpochMillis ?: System.currentTimeMillis(),
            isActive = true,
        )
    }

    /** 切换激活账号，成功返回 true。 */
    fun switchAccount(accountId: String): Boolean = false

    /** 重命名已保存的账号。 */
    fun renameAccount(accountId: String, newName: String): Boolean = false

    /** 删除指定账号。如果删除的是当前激活账号，会自动切换到下一个或清空。 */
    fun deleteAccount(accountId: String): Boolean = false
}

/**
 * 基于 **macOS 密钥链**（Keychain）的实现 —— 对照 Android 版的 `KeystoreCredentialStore`。
 *
 * ## Android → macOS 的存储映射（逻辑键名刻意保持不变，两边可以逐键对照排查）
 *
 * | Android（AndroidKeyStore + SharedPreferences `pocketnai_secure`） | macOS 移植 |
 * |---|---|
 * | Keystore AES 密钥 alias `pocketnai_token_key` | **不存在**：不再需要客户端密钥 |
 * | `token_iv`（客户端自己加密时的 IV，本身不是秘密） | **无对应物**：没有客户端加密就没有 IV 要存 |
 * | `token_ciphertext`（AES-GCM 密文，唯一承载秘密的顶层键） | 密钥链 service [KEYCHAIN_SERVICE] + account `token_ciphertext`，值**就是 Token 本身**（交给系统加密） |
 * | `account_<id>_iv` | 无对应物 |
 * | `account_<id>_ciphertext` | 同一个 service 下 account `account_<id>_ciphertext` |
 * | `credential_type` / `credential_created_at` / `token_hint_length` / `token_hint_fingerprint` | 键值存储 `pocketnai_secure` 的**同名键**（非敏感，原样保留） |
 * | `accounts_index` / `active_account_id` | 同上，同名键 |
 * | `account_<id>_name` / `_type` / `_created_at` / `_fingerprint` | 同上，同名键 |
 *
 * 一句话：**密钥链只装秘密**（顶层镜像一条 + 每个账号各一条），
 * 索引与脱敏提示仍然留在与 Android 同名的 `pocketnai_secure` 里 ——
 * Android 版本来也只有密文在 Keystore，类型/长度/短哈希这些提示都在首选项里。
 * 注意顶层类型键在 Android 上叫 `credential_type`（**不是** `token_type`），这里沿用同一个名字。
 *
 * ## 为什么值直接进密钥链，而不是自己再 AES 一遍
 * 密钥链的密文由系统用登录钥匙串的密钥加密，**那把密钥也在钥匙串里、由 OS 看管**。
 * 自己再加密就必须把 AES 密钥与密文存在一起（否则重启后解不开），
 * 于是攻击者拿到文件的同时也就拿到了钥匙 —— 等于没加密，却多出一堆要维护的代码，
 * 还会把 IV、格式、迁移这些 Android 时代的包袱一起搬过来。OS 已经做了这件事，直接用它。
 *
 * ## 兼容层（阶段 C 的硬要求）
 * - 顶层镜像（`token_ciphertext` + `credential_type` / `token_hint_*` / `credential_created_at`）
 *   **必须继续同步维护**：旧代码只从顶层读凭据，切换账号就是"把目标账号搬到顶层"；
 * - [ensureLegacyMigrated] 把"只有顶层凭据、还没有账号索引"的状态补成 `default` 账号，
 *   旧凭据继续可用（值不重新加密，只是多登记一条 `account_default_ciphertext`）；
 * - 类型缺失或元数据损坏时按 [CredentialType.PERSISTENT_API_TOKEN] 降级 ——
 *   凭据本身读得回来就继续可用。
 *
 * ## 与 Android 行为的两处**刻意**差异（都不涉及保密性，写在这里免得被当成 bug）
 * 1. 读失败**不**清库：Android 在解密异常时 `clear()` 会把**所有**账号一起删掉
 *    （规划书里记着这个已知取舍）。macOS 上"钥匙串锁定/用户拒绝授权"是一过性的，
 *    把它当成"凭据损坏"会把用户存的两个账号一起送走，所以读不到就只返回 null；
 *    只有实现类真的抛异常时才走 Android 那条 clear 路径。
 * 2. 删除激活账号后如果**切不到**下一个（残余索引指向的条目已经不在），这里会 [clear]，
 *    Android 会留下"已删除账号的密文还挂在顶层镜像上"的残局。正常路径两边完全一致。
 *
 * ## 失败策略
 * 写入密钥链失败会抛 [KeychainException]（消息里只有 OSStatus）：**绝不能在没存进去的情况下
 * 让界面显示"已连接"**。调用方若要把它变成错误文案，请包一层 `runCatching`。
 */
class KeychainCredentialStore(
    environment: PlatformEnvironment,
    private val keychain: Keychain = Keychain.system(),
) : CredentialStore {

    /** 非敏感元数据。**不得改名**：`pocketnai_secure` 是 AGENTS.md 钉住的兼容性常量。 */
    private val kv: KeyValueStore = environment.keyValueStore(PREFS_NAME)

    /**
     * 有没有激活凭据。
     *
     * Android 是 `prefs.contains(token_ciphertext) && prefs.contains(token_iv)`；
     * macOS 只看密钥链里 `token_ciphertext` 这条在不在（没有 IV 这回事，见映射表）。
     * 这里会把值读进内存再丢掉 —— 密钥链没有"只判断存在"的廉价查询，
     * 多一条只为省几个字节的路径不值得维护。它在应用启动路径上，任何失败都按"没有"处理。
     */
    override fun hasCredential(): Boolean = readSecretOrNull(mirrorSecretKey) != null

    override fun load(): StoredCredential? {
        val plaintext = try {
            keychain.read(mirrorSecretKey)
        } catch (e: SecureStoreException) {
            // Locked/unavailable service is transient; never delete stored accounts on read failure.
            throw e
        } catch (e: RuntimeException) {
            Log.w(TAG, "凭据读取失败：${e.javaClass.simpleName}")
            return null
        } ?: return null
        if (plaintext.isEmpty()) return null

        return StoredCredential(
            token = plaintext,
            type = CredentialType.fromNameOrDefault(kv.getString(KEY_TYPE, null)),
            createdAtEpochMillis = kv.getLong(KEY_CREATED_AT, 0L).takeIf { it > 0L },
        )
    }

    override fun save(credential: StoredCredential) {
        val active = activeAccountId()
        if (active != null) {
            val accounts = listAccounts()
            val name = accounts.firstOrNull { it.id == active }?.name ?: DEFAULT_ACCOUNT_NAME
            val fingerprint = fingerprintOf(credential.token)
            val now = System.currentTimeMillis()

            // 先写秘密、再写元数据：密钥链写失败会抛异常，此时索引/提示还没动，
            // 不会留下"界面说有凭据、实际读不回来"的错位。
            keychain.write(accountSecretKey(active), credential.token)
            // 顶层镜像 = 激活账号（兼容层：旧读取路径只看这里）。
            keychain.write(mirrorSecretKey, credential.token)

            kv.edit {
                putString(accountNameKey(active), name)
                putString(accountTypeKey(active), credential.type.name)
                putLong(accountCreatedAtKey(active), now)
                putString(accountFingerprintKey(active), fingerprint)
                putString(KEY_TYPE, credential.type.name)
                putInt(KEY_HINT_LENGTH, credential.token.length)
                putString(KEY_HINT_FINGERPRINT, fingerprint)
                putLong(KEY_CREATED_AT, now)
            }
        } else {
            saveAccount(DEFAULT_ACCOUNT_NAME, credential)
        }
    }

    override fun clear() {
        // "断开连接"清的是**整份**凭据（所有已保存账号 + 顶层镜像），与 Android 的
        // prefs.clear() 等价。分三步，任一步失败都不影响后面：
        // 1) 点得出名字的（镜像 + 索引里的每个账号）；
        // 2) 按 service 枚举兜底 —— 索引丢了（键值存储被清过）也不能把 Token 留在钥匙串里；
        // 3) 最后才清非敏感元数据。
        val indexed = kv.getStringSet(KEY_ACCOUNTS_INDEX, emptySet())
        keychain.delete(mirrorSecretKey)
        indexed.forEach { id -> keychain.delete(accountSecretKey(id)) }
        keychain.accounts(KEYCHAIN_SERVICE).forEach { account ->
            keychain.delete(KeychainKey(KEYCHAIN_SERVICE, account))
        }
        kv.edit { clear() }
    }

    override fun hint(): CredentialHint? {
        if (!hasCredential()) return null
        val length = kv.getInt(KEY_HINT_LENGTH, -1)
        val fingerprint = kv.getString(KEY_HINT_FINGERPRINT, null) ?: return null
        if (length <= 0) return null
        return CredentialHint(
            type = CredentialType.fromNameOrDefault(kv.getString(KEY_TYPE, null)),
            length = length,
            fingerprint = fingerprint,
        )
    }

    /**
     * 兼容层：把"只有顶层凭据、还没有账号索引"的旧状态补成 `default` 账号。
     *
     * Android 从顶层的 `token_iv` / `token_ciphertext` 把密文搬进 `account_default_*`；
     * macOS 上顶层秘密就是密钥链里的 `token_ciphertext`，因此把**同一个值**复制到
     * `account_default_ciphertext` —— 语义一致：旧凭据继续可用，索引里多出一个默认账号。
     *
     * 复制失败（钥匙串此刻不可用）就**不建索引**：下次读取会再试一遍，顶层凭据始终可用。
     * 这样这个方法虽然会被 [listAccounts] / [activeAccountId] 这类只读入口调用，
     * 也不会因为写密钥链失败而把只读调用变成异常。
     */
    private fun ensureLegacyMigrated() {
        if (!hasCredential()) return
        // KeyValueStore 的 getStringSet 与 SharedPreferences 一样"不存在就给默认值"，
        // 因此"索引还没建"要同时看 contains：缺键与空集合在这里是同一件事。
        val hasIndex = kv.contains(KEY_ACCOUNTS_INDEX) && kv.getStringSet(KEY_ACCOUNTS_INDEX).isNotEmpty()
        if (!hasIndex) {
            val defaultId = DEFAULT_ACCOUNT_ID
            val secret = readSecretOrNull(mirrorSecretKey) ?: return
            val copied = runCatching { keychain.write(accountSecretKey(defaultId), secret) }
                .fold(onSuccess = { true }, onFailure = { error ->
                    Log.w(TAG, "旧凭据迁移失败：${error.javaClass.simpleName}")
                    false
                })
            if (!copied) return

            kv.edit {
                putStringSet(KEY_ACCOUNTS_INDEX, setOf(defaultId))
                putString(KEY_ACTIVE_ACCOUNT_ID, defaultId)
                putString(accountNameKey(defaultId), DEFAULT_ACCOUNT_NAME)
                putString(
                    accountTypeKey(defaultId),
                    kv.getString(KEY_TYPE, CredentialType.PERSISTENT_API_TOKEN.name),
                )
                putLong(accountCreatedAtKey(defaultId), kv.getLong(KEY_CREATED_AT, System.currentTimeMillis()))
                putString(accountFingerprintKey(defaultId), kv.getString(KEY_HINT_FINGERPRINT, "") ?: "")
            }
        }
    }

    override fun listAccounts(): List<SavedAccount> {
        ensureLegacyMigrated()
        val index = kv.getStringSet(KEY_ACCOUNTS_INDEX, emptySet())
        val activeId = kv.getString(KEY_ACTIVE_ACCOUNT_ID, null)
        return index.mapNotNull { id ->
            val name = kv.getString(accountNameKey(id), null) ?: return@mapNotNull null
            SavedAccount(
                id = id,
                name = name,
                type = CredentialType.fromNameOrDefault(kv.getString(accountTypeKey(id), null)),
                tokenFingerprint = kv.getString(accountFingerprintKey(id), "") ?: "",
                createdAtEpochMillis = kv.getLong(accountCreatedAtKey(id), 0L),
                isActive = (id == activeId),
            )
        }.sortedBy { it.createdAtEpochMillis }
    }

    override fun activeAccountId(): String? {
        ensureLegacyMigrated()
        return kv.getString(KEY_ACTIVE_ACCOUNT_ID, null)
    }

    override fun saveAccount(name: String, credential: StoredCredential): SavedAccount {
        ensureLegacyMigrated()
        val fingerprint = fingerprintOf(credential.token)
        val now = System.currentTimeMillis()

        val currentSet = kv.getStringSet(KEY_ACCOUNTS_INDEX, emptySet()).toMutableSet()
        val id = UUID.randomUUID().toString().take(8)
        currentSet.add(id)

        val accountName = name.trim().ifBlank { "账号 ${currentSet.size}" }

        val hasActive = kv.contains(KEY_ACTIVE_ACCOUNT_ID) &&
            currentSet.contains(kv.getString(KEY_ACTIVE_ACCOUNT_ID, null))

        // 同样是先写秘密再写索引：写不进去就抛，索引里不会出现"没有条目的账号"。
        keychain.write(accountSecretKey(id), credential.token)
        if (!hasActive) {
            keychain.write(mirrorSecretKey, credential.token)
        }

        kv.edit {
            putStringSet(KEY_ACCOUNTS_INDEX, currentSet)
            putString(accountNameKey(id), accountName)
            putString(accountTypeKey(id), credential.type.name)
            putLong(accountCreatedAtKey(id), now)
            putString(accountFingerprintKey(id), fingerprint)

            if (!hasActive) {
                putString(KEY_ACTIVE_ACCOUNT_ID, id)
                putString(KEY_TYPE, credential.type.name)
                putInt(KEY_HINT_LENGTH, credential.token.length)
                putString(KEY_HINT_FINGERPRINT, fingerprint)
                putLong(KEY_CREATED_AT, now)
            }
        }

        return SavedAccount(
            id = id,
            name = accountName,
            type = credential.type,
            tokenFingerprint = fingerprint,
            createdAtEpochMillis = now,
            isActive = !hasActive || id == kv.getString(KEY_ACTIVE_ACCOUNT_ID, null),
        )
    }

    override fun switchAccount(accountId: String): Boolean {
        // 读得到才切：Android 也是先解密成功、再改动顶层。
        val plaintext = readSecretOrNull(accountSecretKey(accountId)) ?: return false
        if (plaintext.isEmpty()) return false

        val type = kv.getString(accountTypeKey(accountId), null)
        val createdAt = kv.getLong(accountCreatedAtKey(accountId), 0L)
        val fingerprint = kv.getString(accountFingerprintKey(accountId), "") ?: ""

        keychain.write(mirrorSecretKey, plaintext)
        kv.edit {
            putString(KEY_ACTIVE_ACCOUNT_ID, accountId)
            putString(KEY_TYPE, type)
            putInt(KEY_HINT_LENGTH, plaintext.length)
            putString(KEY_HINT_FINGERPRINT, fingerprint)
            putLong(KEY_CREATED_AT, createdAt)
        }
        return true
    }

    override fun renameAccount(accountId: String, newName: String): Boolean {
        if (newName.isBlank()) return false
        if (!kv.contains(accountNameKey(accountId))) return false
        kv.edit { putString(accountNameKey(accountId), newName.trim()) }
        return true
    }

    override fun deleteAccount(accountId: String): Boolean {
        val currentSet = kv.getStringSet(KEY_ACCOUNTS_INDEX, emptySet()).toMutableSet()
        if (!currentSet.contains(accountId)) return false
        currentSet.remove(accountId)
        val wasActive = kv.getString(KEY_ACTIVE_ACCOUNT_ID, null) == accountId

        kv.edit {
            putStringSet(KEY_ACCOUNTS_INDEX, currentSet)
            remove(accountNameKey(accountId))
            remove(accountTypeKey(accountId))
            remove(accountCreatedAtKey(accountId))
            remove(accountFingerprintKey(accountId))
        }
        // Android 在这里删掉 `account_<id>_iv` / `_ciphertext`，macOS 对应删掉密钥链条目。
        keychain.delete(accountSecretKey(accountId))

        if (wasActive) {
            val nextId = currentSet.firstOrNull()
            // 切不过去就清干净（差异点 2，见类注释）：宁可回到"未连接"，
            // 也不能把已删除账号的 Token 继续留在顶层镜像里。
            if (nextId == null || !switchAccount(nextId)) clear()
        }
        return true
    }

    /** 顶层镜像的秘密键：Android 的 `token_ciphertext`。 */
    private val mirrorSecretKey: KeychainKey get() = KeychainKey(KEYCHAIN_SERVICE, KEY_CIPHERTEXT)

    private fun accountSecretKey(accountId: String): KeychainKey =
        KeychainKey(KEYCHAIN_SERVICE, "account_${accountId}_ciphertext")

    private fun accountNameKey(accountId: String) = "account_${accountId}_name"

    private fun accountTypeKey(accountId: String) = "account_${accountId}_type"

    private fun accountCreatedAtKey(accountId: String) = "account_${accountId}_created_at"

    private fun accountFingerprintKey(accountId: String) = "account_${accountId}_fingerprint"

    /** 脱敏短指纹：SHA-256 前 8 个十六进制字符，不可逆，用于界面区分账号。 */
    private fun fingerprintOf(token: String): String = Hashing.sha256(token.toByteArray(Charsets.UTF_8)).take(8)

    /**
     * 给"只想看看有没有 / 只想取一个值"的路径用：**不抛异常**。
     *
     * [load] 是唯一需要区分"读失败"与"没有凭据"的地方（它要对齐 Android 的 clear 语义），
     * 其余调用方（`hasCredential` / `hint` / `switchAccount`）读不到就按"没有"处理更安全 ——
     * 尤其是 [hasCredential]：它在应用启动路径上，抛异常等于启动即崩。
     */
    private fun readSecretOrNull(key: KeychainKey): String? = try {
        keychain.read(key)
    } catch (e: SecureStoreException) {
        throw e
    } catch (e: RuntimeException) {
        Log.w(TAG, "密钥链读取失败：${e.javaClass.simpleName}")
        null
    }

    private companion object {
        const val TAG = "PocketNai/CredentialStore"

        /**
         * 非敏感元数据所在的键值存储名。
         *
         * **不得改名**：AGENTS.md 把这四个常量钉死为兼容性契约（Android 上它是首选项文件名）。
         * 在 macOS 上这个文件里**没有任何秘密** —— 秘密全在 [KEYCHAIN_SERVICE]。
         */
        const val PREFS_NAME = "pocketnai_secure"

        /**
         * 凭据所在的密钥链 service。
         *
         * 用 bundle id 作前缀（`net.pocketnai.macos`）是为了在全机器共用的钥匙串里不撞名；
         * 末尾的 `.credential` 把它与 `SecretBox` 的 service 分开 ——
         * 断开连接只清这个 service，不会连带删掉代理密码（对照 Android：清 `pocketnai_secure`
         * 首选项不会动 `pocketnai_extra_secret_key` 那把 alias）。
         */
        const val KEYCHAIN_SERVICE = "net.pocketnai.macos.credential"

        const val KEY_CIPHERTEXT = "token_ciphertext"

        // 以下为非敏感元数据（键名与 Android 版逐字相同）。
        const val KEY_TYPE = "credential_type"
        const val KEY_CREATED_AT = "credential_created_at"
        const val KEY_HINT_LENGTH = "token_hint_length"
        const val KEY_HINT_FINGERPRINT = "token_hint_fingerprint"

        const val KEY_ACCOUNTS_INDEX = "accounts_index"
        const val KEY_ACTIVE_ACCOUNT_ID = "active_account_id"

        const val DEFAULT_ACCOUNT_ID = "default"
        const val DEFAULT_ACCOUNT_NAME = "默认账号"
    }
}

/**
 * 名字兼容层。
 *
 * Android 版实现类叫 `KeystoreCredentialStore`，装配代码（`AppContainer`）沿用旧名；
 * macOS 上不再有 Keystore，实现类是 [KeychainCredentialStore]。保留这个别名是为了让
 * 移植 `di/` 时的改动只剩一处：把构造参数从 `Context`/`Application` 换成
 * [PlatformEnvironment]（`KeystoreCredentialStore(application)` → `KeystoreCredentialStore(environment)`）。
 *
 * 新代码请直接用 [KeychainCredentialStore]。
 */
typealias KeystoreCredentialStore = KeychainCredentialStore
