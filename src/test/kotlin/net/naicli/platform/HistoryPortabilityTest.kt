package net.naicli.platform

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.Authenticator
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import net.naicli.*
import net.naicli.commands.ReadOnlyCommands
import net.pocketnai.data.local.PocketNaiDatabase
import net.pocketnai.data.security.*
import kotlin.test.*

class HistoryPortabilityTest {
    @Test fun `SQLite opens and reopens a Unicode path with foreign keys and an empty gallery`() {
        val root = Files.createTempDirectory("nai SQLite 空格 ")
        try {
            val file = root.resolve("历史.db").toFile()
            repeat(2) {
                PocketNaiDatabase.build(file).use { db ->
                    assertTrue(db.foreignKeysEnabled())
                    assertEquals(7, db.schemaVersion())
                    assertTrue(blocking { db.generationDao.observeGalleryImages().first() }.isEmpty())
                }
            }
            assertTrue(file.length() > 0)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `history uses the real database without reading credentials and returns valid empty JSON`() {
        val root = Files.createTempDirectory("nai history 空格 ")
        val keychain = object : Keychain by InMemoryKeychain() {
            override fun read(key: KeychainKey): String? = error("history must not read credentials")
            override fun accounts(service: String): List<String> = error("history must not enumerate credentials")
        }
        val context = NaiContext(null, root.toFile(), null) { KeychainCredentialStore(it, keychain) }
        val output = ByteArrayOutputStream()
        val previousOutput = System.out
        val previousAuthenticator = Authenticator.getDefault()
        try {
            System.setOut(PrintStream(output, true, "UTF-8"))
            ReadOnlyCommands.history.run(Args.parse(emptyList()), Reporter(true), context)
            val data = Json.parseToJsonElement(output.toString("UTF-8")).jsonObject
            assertTrue(data.getValue("ok").jsonPrimitive.boolean)
            assertEquals(0, data.getValue("count").jsonPrimitive.int)
            assertTrue(data.getValue("images").jsonArray.isEmpty())
        } finally {
            System.setOut(previousOutput)
            runCatching { context.container.database.close() }
            Authenticator.setDefault(previousAuthenticator)
            root.toFile().deleteRecursively()
        }
    }
}
