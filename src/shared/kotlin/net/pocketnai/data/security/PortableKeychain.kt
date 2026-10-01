package net.pocketnai.data.security

import net.pocketnai.core.platform.HostPlatform
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Safe, actionable messages only: never attach command output or secret values as causes. */
class SecureStoreException(message: String) : RuntimeException(message)

object PortableKeychains {
    fun system(platform: HostPlatform = HostPlatform.detect()): Keychain = when (platform) {
        HostPlatform.MACOS -> MacKeychain()
        HostPlatform.WINDOWS -> WindowsKeychain()
        HostPlatform.LINUX -> LinuxKeychain()
        HostPlatform.OTHER -> throw SecureStoreException("此系统尚无安全凭据后端；只读联网命令可使用 NAI_TOKEN。")
    }
}

// Deliberately not a data class: toString must never reveal subprocess output.
class SecretToolResult(val exitCode: Int, val stdout: String, val stderr: String)

fun interface SecretToolRunner {
    fun run(arguments: List<String>, input: String?): SecretToolResult
}

/** Pipes only: no shell, token in argv/environment, temp files, or inherited output streams. */
class SecretToolProcess : SecretToolRunner {
    override fun run(arguments: List<String>, input: String?): SecretToolResult {
        val process = try {
            ProcessBuilder(listOf("secret-tool") + arguments).start()
        } catch (_: Exception) {
            throw SecureStoreException("无法启动 secret-tool；Linux 请安装 libsecret-tools，并启用已解锁的 Secret Service。")
        }
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        try {
            fun capture(stream: java.io.InputStream) = executor.submit<String> {
                stream.use {
                    val bytes = it.readNBytes(1024 * 1024 + 1)
                    if (bytes.size > 1024 * 1024) throw SecureStoreException("安全存储响应过大。")
                    bytes.toString(Charsets.UTF_8)
                }
            }
            val stdout = capture(process.inputStream)
            val stderr = capture(process.errorStream)
            val writer = executor.submit {
                process.outputStream.use { stream -> input?.let { stream.write(it.toByteArray(Charsets.UTF_8)) } }
            }
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                throw SecureStoreException("安全存储等待超时；请解锁 Linux Secret Service 后重试。")
            }
            writer.get(2, TimeUnit.SECONDS)
            return SecretToolResult(process.exitValue(), stdout.get(2, TimeUnit.SECONDS), stderr.get(2, TimeUnit.SECONDS))
        } catch (e: SecureStoreException) {
            throw e
        } catch (_: Exception) {
            throw SecureStoreException("无法访问 Linux 安全存储；请检查 D-Bus 会话和已解锁的 Secret Service。")
        } finally {
            process.destroyForcibly()
            executor.shutdownNow()
        }
    }
}

/** Uses the desktop Secret Service (GNOME Keyring / compatible providers), never plaintext fallback. */
class LinuxKeychain(private val runner: SecretToolRunner = SecretToolProcess()) : Keychain {
    private fun attributes(key: KeychainKey) = listOf("service", key.service, "account", key.account)
    private fun failed(): Nothing = throw SecureStoreException(
        "Linux 安全存储不可用；请检查 secret-tool、D-Bus 会话与已解锁的 Secret Service。",
    )

    override fun read(key: KeychainKey): String? {
        val result = runner.run(listOf("lookup") + attributes(key), null)
        return when {
            result.exitCode == 0 -> result.stdout // lookup adds no newline when stdout is a pipe
            result.exitCode == 1 && result.stderr.isBlank() -> null // absent, not service failure
            else -> failed()
        }
    }

    override fun write(key: KeychainKey, value: String) {
        // secret-tool's stdin reader is bounded; reject oversize values before any write.
        if (value.toByteArray(Charsets.UTF_8).size > 8191) throw SecureStoreException("凭据超过 Linux 安全存储大小限制。")
        val result = runner.run(listOf("store", "--label=NAI-CLI") + attributes(key), value)
        if (result.exitCode != 0) failed()
    }

    override fun delete(key: KeychainKey) {
        val result = runner.run(listOf("clear") + attributes(key), null)
        if (result.exitCode != 0 && !(result.exitCode == 1 && result.stderr.isBlank())) failed()
    }

    override fun accounts(service: String): List<String> {
        val result = runner.run(listOf("search", "--all", "--unlock", "service", service), null)
        if (result.exitCode != 0) failed()
        // libsecret writes attributes to stderr; some older builds used stdout. Neither is logged.
        return (result.stderr + "\n" + result.stdout).lineSequence()
            .filter { it.startsWith("attribute.account = ") }
            .map { it.removePrefix("attribute.account = ") }.distinct().toList()
    }

    override fun randomBytes(size: Int) = ByteArray(size).also { SecureRandom().nextBytes(it) }
}
