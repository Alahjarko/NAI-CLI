package net.pocketnai.data.security

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.security.SecureRandom

/** CREDENTIALW layout from wincred.h. DWORD is 32 bits, pointers follow native alignment. */
@Structure.FieldOrder("flags", "type", "targetName", "comment", "lastWrittenLow", "lastWrittenHigh",
    "blobSize", "blob", "persist", "attributeCount", "attributes", "targetAlias", "userName")
class WindowsCredential(pointer: Pointer? = null) : Structure(pointer) {
    @JvmField var flags: Int = 0
    @JvmField var type: Int = 1 // CRED_TYPE_GENERIC
    @JvmField var targetName: WString? = null
    @JvmField var comment: WString? = null
    @JvmField var lastWrittenLow: Int = 0
    @JvmField var lastWrittenHigh: Int = 0
    @JvmField var blobSize: Int = 0
    @JvmField var blob: Pointer? = null
    @JvmField var persist: Int = 2 // CRED_PERSIST_LOCAL_MACHINE: current user's subsequent logons
    @JvmField var attributeCount: Int = 0
    @JvmField var attributes: Pointer? = null
    @JvmField var targetAlias: WString? = null
    @JvmField var userName: WString? = null
    init { if (pointer != null) read() }
}

interface CredentialApi : StdCallLibrary {
    fun CredReadW(target: WString, type: Int, flags: Int, result: PointerByReference): Boolean
    fun CredWriteW(credential: WindowsCredential, flags: Int): Boolean
    fun CredDeleteW(target: WString, type: Int, flags: Int): Boolean
    fun CredEnumerateW(filter: WString, flags: Int, count: IntByReference, result: PointerByReference): Boolean
    fun CredFree(buffer: Pointer)
}

/** Current-user Windows Credential Manager; no PowerShell, cmdkey, or plaintext credential files. */
class WindowsKeychain : Keychain {
    private val api: CredentialApi by lazy {
        try { Native.load("Advapi32", CredentialApi::class.java) }
        catch (_: LinkageError) { throw SecureStoreException("无法加载 Windows Credential Manager。") }
    }

    private fun target(key: KeychainKey) = WString("NAI-CLI/${key.service}/${key.account}")
    private fun failure(): Nothing = throw SecureStoreException("Windows Credential Manager 操作失败（状态 ${Native.getLastError()}）。")
    private fun absent(): Boolean = Native.getLastError() == 1168 // ERROR_NOT_FOUND

    override fun read(key: KeychainKey): String? {
        val result = PointerByReference()
        if (!api.CredReadW(target(key), 1, 0, result)) {
            if (absent()) return null
            failure()
        }
        val pointer = result.value
        try {
            val credential = WindowsCredential(pointer)
            if (credential.blobSize == 0) return ""
            return credential.blob!!.getByteArray(0, credential.blobSize).toString(Charsets.UTF_8)
        } finally { api.CredFree(pointer) }
    }

    override fun write(key: KeychainKey, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size > 2560) throw SecureStoreException("凭据超过 Windows Credential Manager 的 2560 字节限制。")
        try {
            Memory(bytes.size.coerceAtLeast(1).toLong()).use { memory ->
                memory.write(0, bytes, 0, bytes.size)
                val credential = WindowsCredential().apply {
                    targetName = target(key)
                    userName = WString("NAI-CLI")
                    blobSize = bytes.size
                    blob = memory
                }
                try { if (!api.CredWriteW(credential, 0)) failure() }
                finally { memory.clear() }
            }
        } finally { bytes.fill(0) }
    }

    override fun delete(key: KeychainKey) {
        if (!api.CredDeleteW(target(key), 1, 0) && !absent()) failure()
    }

    override fun accounts(service: String): List<String> {
        val prefix = "NAI-CLI/$service/"
        val count = IntByReference()
        val result = PointerByReference()
        if (!api.CredEnumerateW(WString("$prefix*"), 0, count, result)) {
            if (absent()) return emptyList()
            failure()
        }
        val pointer = result.value
        try {
            return (0 until count.value).mapNotNull { index ->
                val entry = WindowsCredential(pointer.getPointer(index.toLong() * Native.POINTER_SIZE))
                entry.targetName?.toString()?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
            }
        } finally { api.CredFree(pointer) }
    }

    override fun randomBytes(size: Int) = ByteArray(size).also { SecureRandom().nextBytes(it) }
}
