package net.naicli.platform

import java.nio.file.Files
import kotlin.test.*
import net.naicli.*
import net.naicli.commands.AuthCommands
import net.pocketnai.data.security.*

class AuthCommandsTest {
    @Test fun `login rejects external credentials before opening store or network`() {
        val root = Files.createTempDirectory("nai-login-test").toFile()
        try {
            for ((explicit, env) in listOf("dummy" to null, null to "dummy")) {
                val context = NaiContext(explicit, root, env) { error("must not open store") }
                val failure = assertFailsWith<CliFailure> {
                    AuthCommands.login.run(Args.parse(emptyList()), Reporter(true), context)
                }
                assertEquals(ExitCode.USAGE, failure.exitCode)
                assertTrue(root.listFiles()!!.isEmpty())
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun `login refuses a noninteractive process without touching credentials`() {
        assertFalse(System.console()?.isTerminal == true, "This test must run in a noninteractive worker")
        val root = Files.createTempDirectory("nai-login-test").toFile()
        try {
            val context = NaiContext(null, root, null) { error("must not open store") }
            val failure = assertFailsWith<CliFailure> {
                AuthCommands.login.run(Args.parse(emptyList()), Reporter(true), context)
            }
            assertEquals(ExitCode.GATE_UNAVAILABLE, failure.exitCode)
            assertTrue(root.listFiles()!!.isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun `account selection changes the saved spend token without network`() {
        val root = Files.createTempDirectory("nai-account-test").toFile()
        try {
            val context = NaiContext(null, root, null) { KeychainCredentialStore(it, InMemoryKeychain()) }
            val first = context.credentialStore.saveAccount("first", StoredCredential("dummy-first", CredentialType.PERSISTENT_API_TOKEN))
            val second = context.credentialStore.saveAccount("second", StoredCredential("dummy-second", CredentialType.PERSISTENT_API_TOKEN))
            assertEquals(first.id, context.credentialStore.activeAccountId())
            AuthCommands.account.run(Args.parse(listOf("use", second.id)), Reporter(true), context)
            assertEquals("dummy-second", context.tokenForSpend())
            val failure = assertFailsWith<CliFailure> {
                AuthCommands.account.run(Args.parse(listOf("use", "missing")), Reporter(true), context)
            }
            assertEquals(ExitCode.USAGE, failure.exitCode)
            assertEquals("dummy-second", context.tokenForSpend())
        } finally { root.deleteRecursively() }
    }
}
