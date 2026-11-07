package org.kineograph.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.kineograph.media.export.ExportFiles
import java.io.File

class ExportFilesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun export(name: String, ageMs: Long): File {
        val directory = temp.root.resolve("exports/$name").apply { mkdirs() }
        val file = directory.resolve("Ball.mp4").apply { writeText("video") }
        val time = NOW - ageMs
        file.setLastModified(time)
        directory.setLastModified(time)
        return file
    }

    private val root get() = temp.root.resolve("exports")

    @Test
    fun `old exports go and recent ones stay, so a link already given out keeps working`() {
        val old = export("old", 2 * DAY)
        val recent = export("recent", DAY / 2)
        ExportFiles.prune(root, emptySet(), NOW - DAY)
        assertFalse(old.parentFile?.exists() == true)
        assertTrue(recent.exists())
    }

    @Test
    fun `an old export the user has not seen stays`() {
        val unseen = export("unseen", 5 * DAY)
        ExportFiles.prune(root, setOf("unseen"), NOW - DAY)
        assertTrue(unseen.exists())
    }

    @Test
    fun `a directory with a recent file counts as recent`() {
        val file = export("growing", 2 * DAY)
        file.setLastModified(NOW)
        ExportFiles.prune(root, emptySet(), NOW - DAY)
        assertTrue(file.exists())
    }

    @Test
    fun `a stray file left in the root goes too`() {
        root.mkdirs()
        val stray = root.resolve("old.mp4").apply { writeText("video") }
        stray.setLastModified(NOW - 2 * DAY)
        ExportFiles.prune(root, emptySet(), NOW - DAY)
        assertFalse(stray.exists())
    }

    @Test
    fun `nothing to prune is not a problem`() {
        ExportFiles.prune(temp.root.resolve("never made"), emptySet(), NOW)
    }

    @Test
    fun `deleting an export takes its directory but no other`() {
        val mine = export("mine", 0)
        val other = export("other", 0)
        ExportFiles.delete(mine)
        assertFalse(mine.parentFile?.exists() == true)
        assertTrue(other.exists())
    }

    @Test
    fun `deleting a file directly in the root leaves the rest of the root alone`() {
        val other = export("other", 0)
        val stray = root.resolve("stray.mp4").apply { writeText("video") }
        ExportFiles.delete(stray)
        assertFalse(stray.exists())
        assertTrue(other.exists())
    }

    private companion object {
        const val DAY = 24L * 60L * 60L * 1000L
        const val NOW = 1_700_000_000_000L
    }
}
