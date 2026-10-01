package net.pocketnai.core.platform

import java.io.File
import java.util.Locale

enum class HostPlatform {
    MACOS, LINUX, WINDOWS, OTHER;

    companion object {
        fun detect(name: String = System.getProperty("os.name")): HostPlatform {
            val os = name.lowercase(Locale.ROOT)
            return when {
                os.startsWith("mac") || os == "darwin" -> MACOS
                os.startsWith("windows") -> WINDOWS
                os.startsWith("linux") -> LINUX
                else -> OTHER
            }
        }
    }
}

/** Pure path selection: test other platforms without falsifying the JVM's os.name. */
class PlatformPaths(
    val platform: HostPlatform = HostPlatform.detect(),
    private val home: File = File(System.getProperty("user.home")),
    private val env: Map<String, String> = System.getenv(),
) {
    private fun absoluteEnv(name: String): File? = env[name]?.takeIf { it.isNotBlank() }
        ?.let(::File)?.takeIf { it.isAbsolute }

    fun dataDirectory(): File = when (platform) {
        HostPlatform.MACOS -> File(home, "Library/Application Support/MacNAI")
        HostPlatform.WINDOWS -> File(absoluteEnv("LOCALAPPDATA") ?: File(home, "AppData/Local"), "NAI-CLI")
        else -> File(absoluteEnv("XDG_DATA_HOME") ?: File(home, ".local/share"), "nai-cli")
    }

    fun configDirectory(): File = when (platform) {
        HostPlatform.WINDOWS -> File(absoluteEnv("APPDATA") ?: File(home, "AppData/Roaming"), "nai-cli")
        // Retain existing macOS grants; moving them would hide outstanding authorizations.
        else -> File(absoluteEnv("XDG_CONFIG_HOME") ?: File(home, ".config"), "nai-cli")
    }
}
