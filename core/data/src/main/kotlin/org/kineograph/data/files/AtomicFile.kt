package org.kineograph.data.files

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Crash-safe file writing: a reader sees either the old content or all of the new content, never a mix. */
internal object AtomicFile {
    /** Writes [bytes] to [target] by writing a temporary file, flushing it to disk, then renaming it into place. */
    @Throws(IOException::class)
    fun write(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.tmp")
        try {
            writeDurably(temporary, bytes)
            move(temporary, target)
        } catch (e: IOException) {
            // A half-written leftover would hold on to the very space a full disk is short of.
            temporary.delete()
            throw e
        }
    }

    /** Writes [bytes] to [file] and only returns once the system has flushed them to storage. */
    @Throws(IOException::class)
    fun writeDurably(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { stream ->
            stream.write(bytes)
            stream.flush()
            stream.fd.sync()
        }
    }

    /** Renames [from] over [to] in one step. */
    @Throws(IOException::class)
    fun move(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
