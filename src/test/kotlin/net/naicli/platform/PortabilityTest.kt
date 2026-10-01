package net.naicli.platform

import java.io.File
import java.nio.file.Files
import kotlin.test.*
import net.naicli.*
import net.pocketnai.core.platform.*
import net.pocketnai.data.security.*

class PlatformPathsTest {
    @Test fun `detect OS without mistaking Darwin for Windows`() {
        assertEquals(HostPlatform.MACOS, HostPlatform.detect("Darwin"))
        assertEquals(HostPlatform.MACOS, HostPlatform.detect("Mac OS X"))
        assertEquals(HostPlatform.WINDOWS, HostPlatform.detect("Windows 11"))
        assertEquals(HostPlatform.LINUX, HostPlatform.detect("Linux"))
        assertEquals(HostPlatform.OTHER, HostPlatform.detect("FreeBSD"))
    }
    @Test fun `macOS paths retain existing data and grants`() {
        val home = File(System.getProperty("java.io.tmpdir"), "portable-home")
        val paths = PlatformPaths(HostPlatform.MACOS, home, emptyMap())
        assertEquals(File(home, "Library/Application Support/MacNAI"), paths.dataDirectory())
        assertEquals(File(home, ".config/nai-cli"), paths.configDirectory())
    }
    @Test fun `Linux respects absolute XDG paths and rejects relative ones`() {
        val home = File(System.getProperty("java.io.tmpdir"), "portable-home")
        val data = File(home, "data with 空格")
        val paths = PlatformPaths(HostPlatform.LINUX, home, mapOf("XDG_DATA_HOME" to data.path, "XDG_CONFIG_HOME" to "relative"))
        assertEquals(File(data, "nai-cli"), paths.dataDirectory())
        assertEquals(File(home, ".config/nai-cli"), paths.configDirectory())
    }
    @Test fun `Windows uses local data and roaming configuration`() {
        val home = File(System.getProperty("java.io.tmpdir"), "portable-home")
        val local = File(home, "Local")
        val roaming = File(home, "Roaming")
        val paths = PlatformPaths(HostPlatform.WINDOWS, home, mapOf("LOCALAPPDATA" to local.path, "APPDATA" to roaming.path))
        assertEquals(File(local, "NAI-CLI"), paths.dataDirectory())
        assertEquals(File(roaming, "nai-cli"), paths.configDirectory())
        assertEquals(File(home, "AppData/Local/NAI-CLI"), PlatformPaths(HostPlatform.WINDOWS, home, emptyMap()).dataDirectory())
    }
    @Test fun `atomic writes replace existing files including Unicode paths`() {
        val root = Files.createTempDirectory("nai 空格").toFile()
        try {
            val file = File(root, "首选项.json")
            atomicWriteText(file, "old")
            atomicWriteText(file, "新的内容")
            assertEquals("新的内容", file.readText())
            assertEquals(listOf(file.name), root.list()!!.toList())
        } finally { root.deleteRecursively() }
    }
}

class LinuxKeychainTest {
    private val key = KeychainKey("nai.test", "account_test")
    @Test fun `token travels only through stdin and roundtrip keeps exact bytes`() {
        val secret = "not-a-real-token\n"
        val store = LinuxKeychain { args, input ->
            assertFalse(args.any { it.contains(secret) })
            if (args[0] == "store") {
                assertEquals(secret, input)
                assertEquals(listOf("store", "--label=NAI-CLI", "service", key.service, "account", key.account), args)
                SecretToolResult(0, "", "")
            } else SecretToolResult(0, secret, "")
        }
        store.write(key, secret)
        assertEquals(secret, store.read(key))
    }
    @Test fun `missing item is distinguished from unavailable service`() {
        assertNull(LinuxKeychain { _, _ -> SecretToolResult(1, "", "") }.read(key))
        val error = assertFailsWith<SecureStoreException> {
            LinuxKeychain { _, _ -> SecretToolResult(1, "sensitive output", "sensitive diagnostic") }.read(key)
        }
        assertFalse(error.toString().contains("sensitive"))
    }
    @Test fun `failed writes and deletes never claim success`() {
        val store = LinuxKeychain { _, _ -> SecretToolResult(1, "", "locked") }
        assertFailsWith<SecureStoreException> { store.write(key, "dummy") }
        assertFailsWith<SecureStoreException> { store.delete(key) }
    }
    @Test fun `enumeration accepts libsecret stderr attributes only returning account names`() {
        val store = LinuxKeychain { args, _ ->
            assertEquals(listOf("search", "--all", "--unlock", "service", "nai.test"), args)
            SecretToolResult(0, "secret = hidden\n", "attribute.account = first\nattribute.service = nai.test\nattribute.account = second\n")
        }
        assertEquals(listOf("first", "second"), store.accounts("nai.test"))
    }
    @Test fun `oversize secret rejected before launching helper`() {
        val store = LinuxKeychain { _, _ -> error("must not run") }
        assertFailsWith<SecureStoreException> { store.write(key, "x".repeat(8192)) }
    }
}

class CredentialPortabilityTest {
    @Test fun `read failures do not delete saved credentials or metadata`() {
        val root = Files.createTempDirectory("nai-credential-test").toFile()
        try {
            val env = PlatformEnvironment.inDirectory(root)
            val backend = InMemoryKeychain()
            val store = KeychainCredentialStore(env, backend)
            store.save(StoredCredential("dummy-not-a-token", CredentialType.PERSISTENT_API_TOKEN))
            val before = File(env.prefsDir, "pocketnai_secure.json").readText()
            val failing = object : Keychain by backend {
                override fun read(key: KeychainKey): String? = throw SecureStoreException("locked")
                override fun delete(key: KeychainKey) { error("must not delete") }
            }
            assertFailsWith<SecureStoreException> { KeychainCredentialStore(env, failing).load() }
            assertEquals(before, File(env.prefsDir, "pocketnai_secure.json").readText())
            assertEquals("dummy-not-a-token", store.load()?.token)
        } finally { root.deleteRecursively() }
    }
    @Test fun `external readonly credentials do not touch a native store or return unrelated hint`() {
        val root = Files.createTempDirectory("nai-readonly-test").toFile()
        try {
            for ((explicit, env) in listOf("explicit" to "env", null to "env")) {
                val context = NaiContext(explicit, root, env) { error("must not open native credential store") }
                assertEquals(explicit ?: env, context.tokenForReadOnly())
                assertNull(context.credentialHint())
                val failure = assertFailsWith<CliFailure> { context.tokenForSpend() }
                assertEquals(ExitCode.USAGE, failure.exitCode)
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun `saved account remains the only source for spend token`() {
        val root = Files.createTempDirectory("nai-spend-test").toFile()
        try {
            val store = KeychainCredentialStore(PlatformEnvironment.inDirectory(root), InMemoryKeychain())
            val context = NaiContext(null, root, null) { store }
            assertEquals(ExitCode.NO_CREDENTIAL, assertFailsWith<CliFailure> { context.tokenForSpend() }.exitCode)
            store.save(StoredCredential("dummy-not-a-token", CredentialType.PERSISTENT_API_TOKEN))
            assertEquals("dummy-not-a-token", context.tokenForSpend())
        } finally { root.deleteRecursively() }
    }
    @Test fun `Windows credential struct matches native 64 bit layout`() {
        if (com.sun.jna.Native.POINTER_SIZE == 8) assertEquals(80, WindowsCredential().size())
    }
}
