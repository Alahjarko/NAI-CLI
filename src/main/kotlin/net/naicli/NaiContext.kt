package net.naicli

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.pocketnai.core.Outcome
import net.pocketnai.core.platform.PlatformEnvironment
import net.pocketnai.di.AppContainer
import java.io.File

/**
 * 一次 CLI 调用的运行环境。
 *
 * [container] 是懒加载的：`models` / `cost` 这类纯本地命令根本不需要装配网络与数据库，
 * 安全存储与会话状态同样延迟读取，离线命令不需要已解锁的钥匙串。
 * 让只算本地数据的命令保持零副作用，是这个工具能被反复调用的前提。
 */
class NaiContext(
    /** 命令行传入的 Token；为空时回落到环境变量 [ENV_TOKEN]，再回落到 Keychain。 */
    val explicitToken: String?,
    dataDir: File?,
    private val environmentToken: String? = System.getenv(ENV_TOKEN),
    private val storeFactory: (PlatformEnvironment) -> net.pocketnai.data.security.CredentialStore =
        { net.pocketnai.data.security.KeychainCredentialStore(it) },
) {

    val environment: PlatformEnvironment by lazy {
        if (dataDir != null) PlatformEnvironment.inDirectory(dataDir) else PlatformEnvironment.default()
    }

    val credentialStore by lazy { storeFactory(environment) }

    val container: AppContainer by lazy { AppContainer(environment, credentialStore) }

    fun hasExternalToken(): Boolean = !explicitToken.isNullOrBlank() || !environmentToken.isNullOrBlank()

    /**
     * 只读命令用的 Token。
     *
     * 三级来源，优先级从高到低：`--token` → `NAI_TOKEN` → 系统安全存储里已保存的凭据。
     * 前两级让 CI 与临时排查不必动 Keychain；第三级是常态（macOS 与 MacNAI 共用同一份凭据）。
     */
    fun tokenForReadOnly(): String {
        explicitToken?.takeIf { it.isNotBlank() }?.let { return it }
        environmentToken?.takeIf { it.isNotBlank() }?.let { return it }
        return credentialStore.load()?.token
            ?: throw CliFailure(
                ExitCode.NO_CREDENTIAL,
                "没有可用凭据：系统安全存储里没有已保存的 Token，也没有传 --token / $ENV_TOKEN。",
            )
    }

    /**
     * 花钱命令用的 Token：**只认系统安全存储**。
     *
     * 这不是省事，是闸门的一部分 —— `GenerationRepository` 本来就只从
     * `credentialStore.load()` 取 Token，而那份凭据由真人通过 login（或 macOS 上的 MacNAI）连接。
     * 如果允许 `--token` 走花钱路径，任何拿到一串 Token 的脚本都能绕过
     * "这台机器上有个真人连过账号"这个前提。
     */
    fun tokenForSpend(): String {
        if (hasExternalToken()) {
            throw CliFailure(
                ExitCode.USAGE,
                "花钱命令不接受 --token / $ENV_TOKEN，必须使用系统安全存储里已保存的账号。",
                extra = buildJsonObject {
                    put("hint", "去掉 --token 后重试；若还没连账号，由真人运行 nai login 连接。")
                },
            )
        }
        return credentialStore.load()?.token
            ?: throw CliFailure(
                ExitCode.NO_CREDENTIAL,
                "系统安全存储里没有已保存的凭据，花钱命令无法继续。请由真人运行 nai login 连接账号。",
            )
    }

    /** 当前凭据的脱敏提示，用于回显。不含任何 Token 片段。 */
    fun credentialHint() = if (hasExternalToken()) null else credentialStore.hint()

    companion object {
        const val ENV_TOKEN = "NAI_TOKEN"
        const val ENV_SPEND_DISABLED = "NAI_CLI_SPEND_DISABLED"
    }
}

/** `runBlocking` 的统一入口：CLI 没有事件循环，也不需要取消语义。 */
fun <T> blocking(block: suspend () -> T): T = runBlocking { block() }

/** 把 [Outcome] 拆成"值或抛出 [CliFailure]"。只读命令都走这一条路。 */
fun <T> Outcome<T>.unwrap(): T = when (this) {
    is Outcome.Success -> value
    is Outcome.Failure -> throw CliFailure(
        ExitCode.ERROR,
        "NovelAI 请求失败：${error.code}",
        extra = buildJsonObject {
            put("errorCode", error.code.name)
            error.correlationId?.let { put("correlationId", it) }
            error.detail?.let { put("detail", it) }
        },
    )
}
