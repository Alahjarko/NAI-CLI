package net.pocketnai.data.security

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import net.pocketnai.core.platform.Log
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * 密钥链条目的逻辑定位：`service` + `account` 唯一确定一条 generic password。
 *
 * 对照 Android：`service` 相当于"用的是哪一个 Keystore / 哪一份首选项"，
 * `account` 相当于"那一份存储里的哪一个键"。两者合起来才是主键 ——
 * 密钥链本身是**全机器共用**的一张表，少了 service 前缀就会和别的程序撞名。
 *
 * 约定：`account` 一律沿用 Android 版的逻辑键名（`token_ciphertext` /
 * `account_<id>_ciphertext`），这样两边的行为可以逐键对照（见 `KeychainCredentialStore` 的映射表）。
 */
data class KeychainKey(val service: String, val account: String)

/**
 * 密钥链操作失败。
 *
 * 消息里**只有**逻辑操作名与 OSStatus —— 绝不含被读写的值、也不含它的长度或摘要：
 * 异常会被上层打进日志或显示给用户，任何形式的秘密都不允许从这里漏出去（AGENTS.md 安全红线）。
 */
class KeychainException(val status: Int, val operation: String) :
    RuntimeException("密钥链 $operation 失败（OSStatus $status）")

/**
 * 本机密钥链的最小抽象（macOS 上是 [MacKeychain]，测试里是 [InMemoryKeychain]）。
 *
 * ## 为什么要有这层接口
 * 1. `CredentialStore` 与 `SecretBox` 只依赖这个接口，真实实现与内存实现可以互换；
 * 2. **单元测试必须能在没有解锁钥匙串的机器上跑**（headless / CI）：
 *    真实钥匙串在无人登录、屏幕锁定或换了一次签名的场景下会直接失败
 *    （`errSecAuthFailed` / `errSecUserCanceled`），测试依赖它就会时绿时红。
 *
 * ## 安全约定（谁实现谁负责）
 * - [read] / [write] 搬运的是**明文秘密**：实现不得把它写进日志、异常消息或任何非密钥链存储；
 * - 失败时按下面的签名语义返回/抛出，异常里只允许有 OSStatus；
 * - 这里**不做客户端加密**：密钥链由系统加密落盘、密钥也由系统保管，
 *   再叠一层"密钥和密文一起保存"的 AES 等于把钥匙放在锁旁边的抽屉里（详见 `CredentialStore`）。
 */
interface Keychain {

    /** 不存在返回 null。macOS 授权失败也返回 null；Linux/Windows 服务失败抛 SecureStoreException，不能清库。 */
    fun read(key: KeychainKey): String?

    /** 新增或覆盖。**失败抛 [KeychainException]**：写不进去绝不能装作写成功了。 */
    fun write(key: KeychainKey, value: String)

    /** 删除一条，不存在视为成功。跨平台后端失败会抛异常，不能报告删除成功。 */
    fun delete(key: KeychainKey)

    /** 某个 service 下的全部 account —— `clear()` 靠它兜底清扫"索引丢了但条目还在"的残留。 */
    fun accounts(service: String): List<String>

    /** 密码学安全随机数（macOS 上走 `SecRandomCopyBytes`）。 */
    fun randomBytes(size: Int): ByteArray

    companion object {
        /**
         * 应用装配用的真实密钥链。
         *
         * 构造它**不加载原生库**（JNA 的库与常量都是懒加载），因此
         * `KeychainCredentialStore(environment)` 在没有钥匙串的环境里也能被构造出来，
         * 只有真正读写时才需要 Security.framework。
         */
        fun system(): Keychain = SystemKeychainHolder.instance

        private object SystemKeychainHolder {
            val instance: Keychain by lazy { PortableKeychains.system() }
        }
    }
}

/**
 * 内存实现 —— **只给单元测试与开发期用**，应用装配必须走 [Keychain.system]。
 *
 * 存在的理由：真实钥匙串在没有解锁登录钥匙串的机器上会直接失败，单元测试不能依赖它。
 * 换掉它之后，`CredentialStore` / `SecretBox` 的全部语义（多账号、镜像、清空、往返）
 * 都能在无网络、无钥匙串、无原生库的 JVM 里断言。
 *
 * 它**刻意不做任何加密**（就是一张 ConcurrentHashMap）：假实现越"聪明"，
 * 越容易把真实实现的问题盖住。
 */
class InMemoryKeychain : Keychain {

    private val values = ConcurrentHashMap<KeychainKey, String>()

    override fun read(key: KeychainKey): String? = values[key]

    override fun write(key: KeychainKey, value: String) {
        values[key] = value
    }

    override fun delete(key: KeychainKey) {
        values.remove(key)
    }

    override fun accounts(service: String): List<String> =
        values.keys.filter { it.service == service }.map { it.account }

    override fun randomBytes(size: Int): ByteArray = ByteArray(size).also { SHARED_RANDOM.nextBytes(it) }

    /**
     * 测试用：把某个 service 下的秘密原样列出来（account → value）。
     *
     * 测试拿它断言两件事：秘密**确实**落在密钥链里；两个账号各自的条目互不相同。
     * 生产代码不要调用它 —— 它把明文秘密交回给调用方，绕过了一整条受控读取路径。
     */
    fun secrets(service: String): Map<String, String> =
        values.entries.filter { it.key.service == service }.associate { it.key.account to it.value }

    private companion object {
        val SHARED_RANDOM = SecureRandom()
    }
}

/**
 * macOS 密钥链实现：JNA 调 `Security.framework`（`SecItem*` + `SecRandomCopyBytes`），
 * 字典用 `CoreFoundation` 现搭。
 *
 * ## 为什么是 `kSecClassGenericPassword`
 * generic password 就是"应用自己存一小段秘密"的标准类别：系统按
 * **service + account** 检索，负责加密落盘、负责在别的程序来读时弹授权框。
 * Token / 代理密码这类东西没有比它更合适的容器（internet password 是给 URL 认证用的，
 * 会话/证书类别则要求额外的属性）。
 *
 * ## 为什么不在上面再套一层 AES
 * 密钥链由系统用登录钥匙串的密钥加密，**密钥本身也在钥匙串里、由 OS 看管**。
 * 若再自己 AES 一遍，那个 AES 密钥就必须和密文存在一起（否则重启后解不开），
 * 于是"加密"退化成"把钥匙贴在锁上"：攻击者拿到文件的同时也就拿到了密钥。
 * 所以这里把 Token **直接**作为 `kSecValueData` 存进去 —— OS 已经做了这件事，
 * 而且做得比我们好（密钥受硬件/登录口令保护）。
 *
 * ## 为什么没有 `kSecAttrAccessible`
 * `kSecAttrAccessible*` 只对 **data protection keychain**（`kSecUseDataProtectionKeychain`）
 * 生效，而那条路径要求二进制带 access group / 签名与 provisioning，
 * 一个未签名的 JVM 应用走进去只会得到 `errSecMissingEntitlement`。
 * 因此这里用默认的登录钥匙串（`~/Library/Keychains/login.keychain-db`）：
 * 它在磁盘上同样是加密的，解锁由用户登录完成 —— 与 Android 版"不需要用户认证即可后台生成"
 * 的取舍（`setUserAuthenticationRequired(false)`）一致：生成任务可能在应用退到后台之外完成，
 * 不能要求用户每次读 Token 都先解锁一次。
 *
 * ## 线程安全
 * `SecItem*` 本身是线程安全的；本类不持有可变状态（只有懒加载的常量），因此可以直接并发调用。
 */
class MacKeychain : Keychain {

    override fun read(key: KeychainKey): String? = withCfScope { scope ->
        val query = scope.dictionary(
            Frameworks.secClass to Frameworks.secClassGenericPassword,
            Frameworks.secAttrService to scope.string(key.service),
            Frameworks.secAttrAccount to scope.string(key.account),
            Frameworks.secReturnData to Frameworks.cfBooleanTrue,
            Frameworks.secMatchLimit to Frameworks.secMatchLimitOne,
        )
        val result = PointerByReference()
        when (val status = Frameworks.security.SecItemCopyMatching(query, result)) {
            STATUS_SUCCESS -> {
                val data = result.value ?: return@withCfScope null
                // 返回值是框架新建的 CFData，同样归我们释放（登记进作用域，close 时统一处理）。
                scope.own(data)
                val length = Frameworks.coreFoundation.CFDataGetLength(data)
                if (length <= 0L) return@withCfScope null
                val bytes = Frameworks.coreFoundation.CFDataGetBytePtr(data)
                    ?.getByteArray(0, length.toInt())
                    ?: return@withCfScope null
                String(bytes, Charsets.UTF_8)
            }

            // 条目不存在是正常情况（"还没连过账号"），不是错误。
            STATUS_ITEM_NOT_FOUND -> null

            else -> {
                // 例如 errSecAuthFailed（钥匙串锁定 / 用户拒绝授权）、errSecInteractionNotAllowed。
                // 只记状态码：键名与值都不进日志。
                Log.w(TAG, "密钥链读取失败 status=$status")
                null
            }
        }
    }

    override fun write(key: KeychainKey, value: String) {
        withCfScope { scope ->
            val query = scope.dictionary(
                Frameworks.secClass to Frameworks.secClassGenericPassword,
                Frameworks.secAttrService to scope.string(key.service),
                Frameworks.secAttrAccount to scope.string(key.account),
            )
            // 先试更新：SecItemUpdate 在条目不存在时返回 errSecItemNotFound，
            // 正好当作"要不要新增"的判据（省掉一次额外的存在性查询）。
            val update = scope.dictionary(
                Frameworks.secValueData to scope.data(value.toByteArray(Charsets.UTF_8)),
            )
            when (val updated = Frameworks.security.SecItemUpdate(query, update)) {
                STATUS_SUCCESS -> return@withCfScope
                STATUS_ITEM_NOT_FOUND -> Unit // 落到下面的新增
                else -> throw KeychainException(updated, "写入")
            }

            val add = scope.dictionary(
                Frameworks.secClass to Frameworks.secClassGenericPassword,
                Frameworks.secAttrService to scope.string(key.service),
                Frameworks.secAttrAccount to scope.string(key.account),
                Frameworks.secValueData to scope.data(value.toByteArray(Charsets.UTF_8)),
            )
            val added = Frameworks.security.SecItemAdd(add, null)
            if (added != STATUS_SUCCESS) throw KeychainException(added, "写入")
        }
    }

    override fun delete(key: KeychainKey) {
        withCfScope { scope ->
            val query = scope.dictionary(
                Frameworks.secClass to Frameworks.secClassGenericPassword,
                Frameworks.secAttrService to scope.string(key.service),
                Frameworks.secAttrAccount to scope.string(key.account),
            )
            val status = Frameworks.security.SecItemDelete(query)
            if (status != STATUS_SUCCESS && status != STATUS_ITEM_NOT_FOUND) {
                Log.w(TAG, "密钥链删除失败 status=$status")
            }
        }
    }

    override fun accounts(service: String): List<String> = runCatching {
        withCfScope { scope ->
            val query = scope.dictionary(
                Frameworks.secClass to Frameworks.secClassGenericPassword,
                Frameworks.secAttrService to scope.string(service),
                // 只要属性、不要数据：清扫残留时没有任何理由把秘密读进内存。
                Frameworks.secReturnAttributes to Frameworks.cfBooleanTrue,
                Frameworks.secMatchLimit to Frameworks.secMatchLimitAll,
            )
            val result = PointerByReference()
            when (val status = Frameworks.security.SecItemCopyMatching(query, result)) {
                STATUS_SUCCESS -> {
                    val array = result.value ?: return@withCfScope emptyList()
                    scope.own(array)
                    (0 until Frameworks.coreFoundation.CFArrayGetCount(array)).mapNotNull { index ->
                        val item = Frameworks.coreFoundation.CFArrayGetValueAtIndex(array, index)
                            ?: return@mapNotNull null
                        val account = Frameworks.coreFoundation.CFDictionaryGetValue(item, Frameworks.secAttrAccount)
                            ?: return@mapNotNull null
                        cfStringToKotlin(account)
                    }
                }

                STATUS_ITEM_NOT_FOUND -> emptyList()

                else -> {
                    Log.w(TAG, "密钥链枚举失败 status=$status")
                    emptyList()
                }
            }
        }
    }.getOrElse {
        // 枚举只用于 clear() 的兜底清扫，不该让它把"断开连接"本身搞失败。
        Log.w(TAG, "密钥链枚举异常：${it.javaClass.simpleName}")
        emptyList()
    }

    override fun randomBytes(size: Int): ByteArray {
        require(size > 0) { "size 必须为正" }
        val memory = Memory(size.toLong())
        val status = Frameworks.security.SecRandomCopyBytes(null, size.toLong(), memory)
        if (status != STATUS_SUCCESS) {
            // 实际见不到这个分支；真出现（系统 CSPRNG 不可用）时退回 JDK 的 SecureRandom，
            // 也比抛异常让用户存不下凭据好 —— 两者都是操作系统级的密码学安全熵源。
            Log.w(TAG, "SecRandomCopyBytes 失败 status=$status，回退 SecureRandom")
            return ByteArray(size).also { FALLBACK_RANDOM.nextBytes(it) }
        }
        return memory.getByteArray(0, size)
    }

    private companion object {
        const val TAG = "PocketNai/Keychain"

        /** `errSecSuccess`。 */
        const val STATUS_SUCCESS = 0

        /** `errSecItemNotFound`。 */
        const val STATUS_ITEM_NOT_FOUND = -25300

        val FALLBACK_RANDOM = SecureRandom()
    }
}

// ---------------------------------------------------------------------------
// 以下都是本文件的实现细节：CoreFoundation 字典的搭与拆、JNA 绑定、常量。
// ---------------------------------------------------------------------------

/** `kCFStringEncodingUTF8`。 */
private const val CF_STRING_ENCODING_UTF8 = 0x08000100

/**
 * 读 CFString 用的缓冲区大小。
 *
 * 这里只会读到密钥链条目的 account 名（`token_ciphertext` / `account_<8位>_ciphertext`
 * 这种我们自己起的短名），1 KiB 绰绰有余；真遇到没完没了的长名，`CFStringGetCString`
 * 会返回 false，我们按"这条读不出来"跳过而不是截断。
 */
private const val CF_STRING_BUFFER_BYTES = 1024

private const val SECURITY_FRAMEWORK_PATH = "/System/Library/Frameworks/Security.framework/Security"
private const val CORE_FOUNDATION_FRAMEWORK_PATH = "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation"

/** `Security.framework` 的函数表（只用得到这五个）。 */
private interface SecurityFramework : Library {
    /** `OSStatus SecItemAdd(CFDictionaryRef attributes, CFTypeRef *result)`。 */
    fun SecItemAdd(attributes: Pointer, result: PointerByReference?): Int

    /** `OSStatus SecItemCopyMatching(CFDictionaryRef query, CFTypeRef *result)`。 */
    fun SecItemCopyMatching(query: Pointer, result: PointerByReference): Int

    /** `OSStatus SecItemUpdate(CFDictionaryRef query, CFDictionaryRef attributesToUpdate)`。 */
    fun SecItemUpdate(query: Pointer, attributesToUpdate: Pointer): Int

    /** `OSStatus SecItemDelete(CFDictionaryRef query)`。 */
    fun SecItemDelete(query: Pointer): Int

    /** `int SecRandomCopyBytes(SecRandomRef rnd, size_t count, void *bytes)`；`rnd` 传 NULL 即 `kSecRandomDefault`。 */
    fun SecRandomCopyBytes(random: Pointer?, count: Long, bytes: Pointer): Int
}

/** `CoreFoundation.framework` 里用到的那几个函数。 */
private interface CoreFoundationFramework : Library {
    fun CFDictionaryCreateMutable(
        allocator: Pointer?,
        capacity: Long,
        keyCallBacks: Pointer?,
        valueCallBacks: Pointer?,
    ): Pointer?

    fun CFDictionaryAddValue(theDict: Pointer, key: Pointer, value: Pointer)

    fun CFDictionaryGetValue(theDict: Pointer, key: Pointer): Pointer?

    fun CFStringCreateWithCString(alloc: Pointer?, cStr: Pointer, encoding: Int): Pointer?

    fun CFStringGetCString(theString: Pointer, buffer: ByteArray, bufferSize: Long, encoding: Int): Boolean

    fun CFDataCreate(allocator: Pointer?, bytes: Pointer, length: Long): Pointer?

    fun CFDataGetLength(theData: Pointer): Long

    fun CFDataGetBytePtr(theData: Pointer): Pointer?

    fun CFArrayGetCount(theArray: Pointer): Long

    fun CFArrayGetValueAtIndex(theArray: Pointer, idx: Long): Pointer?

    fun CFRelease(cf: Pointer?)
}

/**
 * 框架句柄与全局常量。
 *
 * 两个刻意的选择：
 * 1. **用绝对路径加载框架**：JNA 默认按 `libXxx.dylib` 搜索，找不到 macOS 的框架目录；
 *    目标机固定是 macOS，写死框架路径最稳，失败时再让 JNA 自己按名字找一遍。
 * 2. **常量从框架的全局变量里取**（`kSecClass` 等），而不是硬编码 `"class"` / `"genp"`
 *    这类字符串：写错一个字母的后果是"永远查不到条目"，而且完全没有编译期提示。
 *    取常量失败会直接抛错（`error(...)`），不会退化成"静默地查不到"。
 *
 * 全部成员都是 `by lazy`：仅仅构造 [MacKeychain] 不会触发 `dlopen`。
 */
private object Frameworks {

    val security: SecurityFramework by lazy {
        loadInterface(SECURITY_FRAMEWORK_PATH, "Security", SecurityFramework::class.java)
    }

    val coreFoundation: CoreFoundationFramework by lazy {
        loadInterface(CORE_FOUNDATION_FRAMEWORK_PATH, "CoreFoundation", CoreFoundationFramework::class.java)
    }

    val secClass: Pointer by lazy { securityConstant("kSecClass") }
    val secClassGenericPassword: Pointer by lazy { securityConstant("kSecClassGenericPassword") }
    val secAttrService: Pointer by lazy { securityConstant("kSecAttrService") }
    val secAttrAccount: Pointer by lazy { securityConstant("kSecAttrAccount") }
    val secValueData: Pointer by lazy { securityConstant("kSecValueData") }
    val secReturnData: Pointer by lazy { securityConstant("kSecReturnData") }
    val secReturnAttributes: Pointer by lazy { securityConstant("kSecReturnAttributes") }
    val secMatchLimit: Pointer by lazy { securityConstant("kSecMatchLimit") }
    val secMatchLimitOne: Pointer by lazy { securityConstant("kSecMatchLimitOne") }
    val secMatchLimitAll: Pointer by lazy { securityConstant("kSecMatchLimitAll") }
    val cfBooleanTrue: Pointer by lazy { globalPointer(coreFoundationLibrary, "kCFBooleanTrue") }

    /**
     * `kCFTypeDictionaryKeyCallBacks` / `kCFTypeDictionaryValueCallBacks`。
     *
     * 注意它们是**结构体**而不是指针（`const CFDictionaryKeyCallBacks kCFType...`），
     * 所以全局变量地址本身就是 `CFDictionaryCreateMutable` 要的那个结构体指针 —— 不需要再解引用。
     * 用真回调（而不是传 NULL）的意义：字典会自己 retain/release 键值，
     * 键的哈希与相等按 CFString 的**内容**比较 —— 这正是 Security.framework 检索条目的依据。
     */
    val dictionaryKeyCallbacks: Pointer by lazy {
        coreFoundationLibrary.getGlobalVariableAddress("kCFTypeDictionaryKeyCallBacks")
    }

    val dictionaryValueCallbacks: Pointer by lazy {
        coreFoundationLibrary.getGlobalVariableAddress("kCFTypeDictionaryValueCallBacks")
    }

    private val securityLibrary: NativeLibrary by lazy {
        loadLibrary(SECURITY_FRAMEWORK_PATH, "Security")
    }

    private val coreFoundationLibrary: NativeLibrary by lazy {
        loadLibrary(CORE_FOUNDATION_FRAMEWORK_PATH, "CoreFoundation")
    }

    private fun securityConstant(name: String): Pointer = globalPointer(securityLibrary, name)

    private fun globalPointer(library: NativeLibrary, name: String): Pointer =
        library.getGlobalVariableAddress(name).getPointer(0)
            ?: error("$name 为空，无法继续")

    private fun <T : Library> loadInterface(path: String, name: String, iface: Class<T>): T =
        runCatching { Native.load(path, iface) }.getOrElse { Native.load(name, iface) }

    private fun loadLibrary(path: String, name: String): NativeLibrary =
        runCatching { NativeLibrary.getInstance(path) }.getOrElse { NativeLibrary.getInstance(name) }
}

/**
 * 一次 CF 调用的临时对象池。
 *
 * CoreFoundation 是手动引用计数，JNA 不会替我们管理。这里把本次调用创建的
 * CFString / CFData / CFDictionary 全部登记下来，在 [close] 里按**相反顺序**释放，
 * 于是查询逻辑不必在每个 return 分支上写 `CFRelease`（漏一个就是每次读写都泄漏一点）。
 *
 * 释放顺序反过来只求心安：CF 的引用计数与顺序无关，但如果哪天有人加了
 * "先 release 子对象"的优化，反序是唯一安全的写法。
 */
private class CfScope : AutoCloseable {

    private val created = ArrayList<Pointer>(8)

    private val cf: CoreFoundationFramework get() = Frameworks.coreFoundation

    fun string(value: String): Pointer {
        // 显式转 UTF-8 再交给 CFString：JNA 把 Kotlin String 映射成 `const char *` 时用的是
        // 平台默认编码，不能把"正好也是 UTF-8"当成契约（CFStringCreateWithCString 认的是
        // 我们传进去的那个 encoding 参数所指的字节）。
        val bytes = value.toByteArray(Charsets.UTF_8)
        val cString = Memory(bytes.size.toLong() + 1L)
        cString.write(0, bytes, 0, bytes.size)
        cString.setByte(bytes.size.toLong(), 0) // NUL 结尾
        val pointer = cf.CFStringCreateWithCString(null, cString, CF_STRING_ENCODING_UTF8)
            ?: error("CFStringCreateWithCString 失败")
        return own(pointer)
    }

    fun data(bytes: ByteArray): Pointer {
        // CFDataCreate 会复制一份；这里的 buffer 只是为了把字节交给它。
        val buffer = Memory(bytes.size.toLong().coerceAtLeast(1L))
        if (bytes.isNotEmpty()) buffer.write(0, bytes, 0, bytes.size)
        val pointer = cf.CFDataCreate(null, buffer, bytes.size.toLong())
            ?: error("CFDataCreate 失败")
        return own(pointer)
    }

    fun dictionary(vararg entries: Pair<Pointer, Pointer>): Pointer {
        val dictionary = cf.CFDictionaryCreateMutable(
            null,
            entries.size.toLong(),
            Frameworks.dictionaryKeyCallbacks,
            Frameworks.dictionaryValueCallbacks,
        ) ?: error("CFDictionaryCreateMutable 失败")
        own(dictionary)
        entries.forEach { (key, value) -> cf.CFDictionaryAddValue(dictionary, key, value) }
        return dictionary
    }

    fun own(pointer: Pointer): Pointer {
        created += pointer
        return pointer
    }

    override fun close() {
        created.asReversed().forEach { pointer ->
            runCatching { cf.CFRelease(pointer) }
        }
        created.clear()
    }
}

private inline fun <T> withCfScope(block: (CfScope) -> T): T {
    val scope = CfScope()
    return try {
        block(scope)
    } finally {
        scope.close()
    }
}

/** CFString → Kotlin String。读不出来（缓冲不够等）返回 null，由调用方跳过这一条。 */
private fun cfStringToKotlin(value: Pointer): String? {
    val buffer = ByteArray(CF_STRING_BUFFER_BYTES)
    val ok = Frameworks.coreFoundation.CFStringGetCString(
        value,
        buffer,
        buffer.size.toLong(),
        CF_STRING_ENCODING_UTF8,
    )
    if (!ok) return null
    val terminator = buffer.indexOf(0.toByte())
    val length = if (terminator < 0) buffer.size else terminator
    return String(buffer, 0, length, Charsets.UTF_8)
}
