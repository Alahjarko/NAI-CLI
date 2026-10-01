package net.pocketnai.core.platform

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Replace on Windows as well as Unix; never truncate the destination as a rename fallback. */
fun atomicWriteText(file: File, text: String) {
    val target = file.toPath().toAbsolutePath()
    Files.createDirectories(target.parent)
    val temporary = Files.createTempFile(target.parent, ".${file.name}.", ".tmp")
    try {
        Files.writeString(temporary, text, Charsets.UTF_8)
        try {
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target, REPLACE_EXISTING)
        }
    } finally { Files.deleteIfExists(temporary) }
}
