package org.kineograph.media

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteFullException
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.kineograph.media.export.ExportFormat
import org.kineograph.media.export.GalleryWriter
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GalleryWriterTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var source: File
    private lateinit var album: File

    @Before
    fun setUp() {
        source = temp.newFile("Bouncing Ball.mp4").apply { writeText("the new export") }
        album = temp.newFolder("album")
    }

    // ----- Android 8 and 9 -----

    @Test
    fun `an earlier export with the same name is not overwritten`() {
        val earlier = File(album, "Bouncing Ball.mp4").apply { writeText("a drawing that has since changed") }
        val saved = GalleryWriter.copyToNewFile(source, album, "Bouncing Ball.mp4")
        assertEquals("a drawing that has since changed", earlier.readText())
        assertEquals("Bouncing Ball (1).mp4", saved.name)
        assertEquals("the new export", saved.readText())
    }

    @Test
    fun `numbering carries on past the names already taken`() {
        File(album, "a.gif").createNewFile()
        File(album, "a (1).gif").createNewFile()
        assertEquals("a (2).gif", GalleryWriter.copyToNewFile(source, album, "a.gif").name)
    }

    @Test
    fun `a failed copy removes only what it made`() {
        val earlier = File(album, "a.mp4").apply { writeText("keep me") }
        val missing = File(temp.root, "not there.mp4")
        assertThrows(FileNotFoundException::class.java) { GalleryWriter.copyToNewFile(missing, album, "a.mp4") }
        assertEquals("keep me", earlier.readText())
        assertFalse(File(album, "a (1).mp4").exists())
    }

    // ----- Android 10 and up -----

    @Test
    fun `saves a second apart get different names`() {
        val first = GalleryWriter.galleryName(source, 1_000_000L)
        val second = GalleryWriter.galleryName(source, 1_001_000L)
        assertNotEquals(first, second)
        assertTrue(first, first.startsWith("Bouncing Ball "))
        assertTrue(first, first.endsWith(".mp4"))
    }

    /** Fails however each test asks, the way the real one throws across from its own process. */
    class FakeMediaProvider : ContentProvider() {
        override fun onCreate() = true

        override fun query(
            uri: Uri,
            columns: Array<out String>?,
            selection: String?,
            args: Array<out String>?,
            sort: String?,
        ): Cursor? = null

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            insertFailure?.let { throw it }
            insertedName = values?.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)
            return Uri.withAppendedPath(uri, "1")
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val file = File.createTempFile("gallery", null)
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_WRITE_ONLY)
        }

        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
            updateFailure?.let { throw it }
            published = true
            return 1
        }

        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
            deletes++
            deleteFailure?.let { throw it }
            return 1
        }
    }

    private fun installProvider(
        insertThrows: RuntimeException? = null,
        updateThrows: RuntimeException? = null,
        deleteThrows: RuntimeException? = null,
    ) {
        insertFailure = insertThrows
        updateFailure = updateThrows
        deleteFailure = deleteThrows
        insertedName = null
        published = false
        deletes = 0
        Robolectric.setupContentProvider(FakeMediaProvider::class.java, MediaStore.AUTHORITY)
    }

    @Test
    fun `a gallery save publishes the entry under a name of its own`() {
        installProvider()
        GalleryWriter.save(context, source, ExportFormat.MP4)
        assertTrue(published)
        assertEquals(0, deletes)
        assertNotEquals(source.name, insertedName)
    }

    @Test
    fun `a provider that refuses the new entry is a failed save, not a crash`() {
        installProvider(insertThrows = IllegalStateException("Failed to build unique file"))
        assertThrows(IOException::class.java) { GalleryWriter.save(context, source, ExportFormat.MP4) }
    }

    @Test
    fun `a provider that refuses to publish leaves no half-made entry behind`() {
        installProvider(updateThrows = IllegalStateException("Failed to build unique file"))
        assertThrows(IOException::class.java) { GalleryWriter.save(context, source, ExportFormat.MP4) }
        assertFalse(published)
        assertEquals(1, deletes)
    }

    @Test
    fun `failing to tidy up cannot hide why the save failed`() {
        installProvider(updateThrows = SQLiteFullException("disk is full"), deleteThrows = IllegalStateException("gone"))
        val failure = assertThrows(IOException::class.java) { GalleryWriter.save(context, source, ExportFormat.MP4) }
        assertEquals("Could not finish the gallery entry", failure.message)
    }

    private companion object {
        var insertFailure: RuntimeException? = null
        var updateFailure: RuntimeException? = null
        var deleteFailure: RuntimeException? = null
        var insertedName: String? = null
        var published = false
        var deletes = 0
    }
}
