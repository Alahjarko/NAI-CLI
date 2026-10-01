package net.naicli.gate

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.naicli.CliFailure
import net.naicli.ExitCode
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom

/**
 * 一次"人授权了多少钱"的凭证。
 *
 * ## 为什么需要它
 * MacNAI 的 AGENTS.md 有一条硬规则：**自动化流程绝不能代发真实生成请求**。
 * 而这个 CLI 的调用方恰恰就是自动化流程。要让两者共存，就得把"能不能花钱"
 * 从代码路径里拿出来，变成一张**只有真人能签发**的凭证。
 *
 * 签发动作在 `nai approve` 里，那个命令要求 `System.console() != null` ——
 * AI agent 通过非交互 shell 调过来时 console 为 null，直接拒绝。
 * 于是 agent 能做的上限是"花掉人已经批过的额度"，而不是"自己决定花钱"。
 *
 * ## 已知局限（不要假装它更强）
 * grant id 是明文存在 `~/.config/nai-cli/grants/` 里的，agent 读得到也就能用。
 * 所以这张凭证的意义是**限额 + 限次 + 留痕**（限时是可选的），不是"绝对拦得住"：
 * 批出去的预算与次数就是损失上限。
 * 想要"完全不可能花钱"，用 `NAI_CLI_SPEND_DISABLED=1`。
 *
 * ## 为什么有效期是可选的
 * [expiresAt] 为 null 表示不过期。默认不过期，因为**真正界定损失的是预算与次数**：
 * 一张 `--anlas 0 --calls 3` 的 grant 放一年也只能生三张免费图。
 * 反过来，时间限制在实践中卡的是正常人 —— agent 迭代提示词、跑 dry-run、等人类回话，
 * 十分钟很容易就过去了，然后就得重新签一张。
 * 代价是没用完的 grant 不会自己消失：靠 `nai grants list` 看见它、`nai grants revoke` 收掉它。
 */
@Serializable
data class SpendGrant(
    val id: String,
    /** 签发时人写的用途说明。它进审计日志，是事后追责的唯一线索。 */
    val purpose: String,
    val createdAt: Long,
    /** null = 不过期，只受 [anlasBudget] 与 [maxCalls] 约束。 */
    val expiresAt: Long? = null,
    val anlasBudget: Long,
    val anlasSpent: Long,
    val maxCalls: Int,
    val callsUsed: Int,
    val revoked: Boolean,
) {
    val remainingAnlas: Long get() = (anlasBudget - anlasSpent).coerceAtLeast(0L)

    val remainingCalls: Int get() = (maxCalls - callsUsed).coerceAtLeast(0)

    fun isExpired(now: Long): Boolean = expiresAt != null && now >= expiresAt
}

/**
 * grant 的落盘与审计。
 *
 * 目录用 `XDG_CONFIG_HOME`（默认 `~/.config`）而不是 MacNAI 的 Application Support：
 * 闸门记录属于"这个 CLI 工具自己的事"，混进应用数据目录会让人误以为删应用数据也能清掉它。
 */
class GrantStore(private val rootDir: File) {

    private val grantsDir = File(rootDir, "grants")
    val auditLog: File = File(rootDir, "audit.log")

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    init {
        java.nio.file.Files.createDirectories(grantsDir.toPath())
        // Windows inherits the user's profile ACL; POSIX permission mutators can mark it read-only.
        if (java.nio.file.Files.getFileStore(rootDir.toPath()).supportsFileAttributeView("posix")) {
            val ownerOnly = java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")
            java.nio.file.Files.setPosixFilePermissions(rootDir.toPath(), ownerOnly)
            java.nio.file.Files.setPosixFilePermissions(grantsDir.toPath(), ownerOnly)
        }
    }

    /** [ttlMillis] 为 null 表示签一张不过期的 grant。 */
    fun create(
        purpose: String,
        anlasBudget: Long,
        maxCalls: Int,
        ttlMillis: Long? = null,
        now: Long = System.currentTimeMillis(),
    ): SpendGrant {
        val grant = SpendGrant(
            id = newId(),
            purpose = purpose,
            createdAt = now,
            expiresAt = ttlMillis?.let { now + it },
            anlasBudget = anlasBudget,
            anlasSpent = 0L,
            maxCalls = maxCalls,
            callsUsed = 0,
            revoked = false,
        )
        write(grant)
        return grant
    }

    fun load(id: String): SpendGrant? = fileOf(id).takeIf { it.isFile }
        ?.let { json.decodeFromString<SpendGrant>(it.readText()) }

    fun save(grant: SpendGrant) = write(grant)

    fun revoke(id: String): Boolean {
        val grant = load(id) ?: return false
        if (grant.revoked) return true
        write(grant.copy(revoked = true))
        return true
    }

    /** 全部 grant，按创建时间倒序。过期的也列出来 —— 人要能看到"批过什么"。 */
    fun all(): List<SpendGrant> = grantsDir.listFiles { file -> file.name.endsWith(".json") }
        .orEmpty()
        .mapNotNull { runCatching { json.decodeFromString<SpendGrant>(it.readText()) }.getOrNull() }
        .sortedByDescending { it.createdAt }

    /**
     * 跨进程互斥。
     *
     * 针对的具体场景：agent 并发跑两条 `nai generate`，两边都读到"还剩 1 次"，
     * 于是一张 1 次的 grant 花出去两次。文件锁让第二条阻塞到第一条结算完，
     * 那时它读到的已经是 `callsUsed = 1`。
     */
    fun <T> withLock(id: String, block: () -> T): T {
        // 必须先校验再拼路径：lock 文件名同样由 id 拼出来，
        // 漏了这一步，穿越 id 会先在 grantsDir 之外创建一个 .lock 文件。
        requireValidId(id)
        val lockFile = File(grantsDir, "$id.lock")
        RandomAccessFile(lockFile, "rw").use { handle ->
            handle.channel.lock().use { return block() }
        }
    }

    /**
     * 追加一行审计。
     *
     * 写失败**不抛**：审计是事后参考，不能因为日志写不进去就让已经成功的生成看起来失败了。
     * （生成结果的权威记录在 MacNAI 的数据库与 `anlas_transactions` 流水里，这里只是副本。）
     */
    fun audit(line: String) {
        runCatching { auditLog.appendText(line + System.lineSeparator()) }
    }

    private fun write(grant: SpendGrant) {
        net.pocketnai.core.platform.atomicWriteText(fileOf(grant.id), json.encodeToString(SpendGrant.serializer(), grant))
    }

    private fun fileOf(id: String): File {
        requireValidId(id)
        return File(grantsDir, "$id.json")
    }

    /**
     * id 是自己生成的十六进制串，但它是**外部传进来的路径片段**，
     * 因此必须挡住 `../` 这类穿越，否则会读写到 grantsDir 之外。
     */
    private fun requireValidId(id: String) {
        if (!id.matches(ID_PATTERN)) {
            throw CliFailure(ExitCode.USAGE, "grant id 非法：'$id'")
        }
    }

    private fun newId(): String {
        val bytes = ByteArray(12)
        SecureRandom().nextBytes(bytes)
        return "g_" + bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val ID_PATTERN = Regex("^g_[0-9a-f]{24}$")

        fun default(): GrantStore {
            return GrantStore(net.pocketnai.core.platform.PlatformPaths().configDirectory())
        }
    }
}
