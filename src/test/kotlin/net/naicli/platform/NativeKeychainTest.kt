package net.naicli.platform

import net.pocketnai.data.security.*
import java.util.UUID
import kotlin.test.*

/** Opt-in only: creates dummy entries in a unique service, never reads application credentials. */
class NativeKeychainTest {
    @Test fun `native backend stores replaces enumerates and deletes Unicode values`() {
        check(System.getenv("NAI_CLI_TEST_NATIVE_STORE") == "1")
        val store = PortableKeychains.system()
        val service = "net.naicli.test.${UUID.randomUUID()}"
        val first = KeychainKey(service, "first")
        val second = KeychainKey(service, "second")
        try {
            assertNull(store.read(first))
            store.write(first, "dummy only 空格 🔑")
            assertEquals("dummy only 空格 🔑", store.read(first))
            store.write(first, "replacement")
            store.write(second, "another dummy")
            assertEquals("replacement", store.read(first))
            assertEquals(setOf("first", "second"), store.accounts(service).toSet())
            store.delete(first)
            assertNull(store.read(first))
            store.delete(first) // idempotent
            assertEquals(listOf("second"), store.accounts(service))
        } finally {
            store.delete(first)
            store.delete(second)
        }
        assertTrue(store.accounts(service).isEmpty())
    }
}
