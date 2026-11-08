package org.kineograph.feature.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

class ModelStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun model(bytes: Int = 3 shl 20, seed: Int = 0): ByteArray =
        ModelStore.MAGIC + ByteArray(bytes) { ((it + seed) % 251).toByte() }

    private fun source(data: ByteArray, name: String = "gemma-4-E2B-it.litertlm") = ModelSource(name, data.size.toLong()) {
        ByteArrayInputStream(data)
    }

    private fun store(directory: File, free: Long = Long.MAX_VALUE) = ModelStore(directory, Dispatchers.IO) { free }

    @Test
    fun `a model is copied in whole, under the name it was picked by`() {
        val directory = temp.newFolder()
        val data = model()

        val outcome = runBlocking { store(directory).import(source(data)) }

        val expected = InstalledModel("gemma-4-E2B-it.litertlm", data.size.toLong())
        assertEquals(ImportOutcome.Done(expected), outcome)
        assertTrue(store(directory).file.readBytes().contentEquals(data))
        assertEquals(expected, store(directory).installed())
        assertEquals(listOf("model.litertlm", "model.name"), directory.list()?.sorted())
    }

    @Test
    fun `progress climbs to the whole size`() {
        val data = model(40 shl 20)
        val seen = ArrayList<Long>()

        runBlocking { store(temp.newFolder()).import(source(data)) { seen += it } }

        assertTrue(seen.size >= 2)
        assertEquals(seen.sorted(), seen)
        assertEquals(data.size.toLong(), seen.last())
    }

    @Test
    fun `a file that is not a model is refused, and the model already there stays`() {
        val directory = temp.newFolder()
        val first = model()
        runBlocking { store(directory).import(source(first)) }

        val outcome = runBlocking { store(directory).import(source("not a model at all".toByteArray(), "photo.jpg")) }

        assertEquals(ImportOutcome.NotAModel, outcome)
        assertTrue(store(directory).file.readBytes().contentEquals(first))
        assertEquals("gemma-4-E2B-it.litertlm", store(directory).installed()?.name)
        assertFalse(File(directory, "model.litertlm.part").exists())
    }

    @Test
    fun `a file shorter than the header is refused`() {
        assertEquals(ImportOutcome.NotAModel, runBlocking { store(temp.newFolder()).import(source("LITE".toByteArray())) })
    }

    @Test
    fun `a model that would not fit is refused before anything is copied`() {
        val directory = temp.newFolder()
        val data = model()

        val outcome = runBlocking { store(directory, free = 1000).import(source(data)) }

        assertEquals(ImportOutcome.NotEnoughSpace(data.size + ModelStore.SPARE_BYTES, 1000), outcome)
        assertEquals(emptyList<String>(), directory.list()?.toList())
    }

    @Test
    fun `a read error part way leaves the old model as it was`() {
        val directory = temp.newFolder()
        val first = model()
        runBlocking { store(directory).import(source(first)) }
        val broken =
            ModelSource("new.litertlm", 10L shl 20) {
                object : InputStream() {
                    var sent = 0

                    override fun read(): Int = throw UnsupportedOperationException()

                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (sent > 2 shl 20) throw IOException("The provider went away")
                        val header = ModelStore.MAGIC
                        val n = minOf(len, 64 * 1024)
                        for (i in 0 until n) b[off + i] = if (sent + i < header.size) header[sent + i] else 7
                        sent += n
                        return n
                    }
                }
            }

        val outcome = runBlocking { store(directory).import(broken) }

        assertEquals(ImportOutcome.Failed, outcome)
        assertTrue(store(directory).file.readBytes().contentEquals(first))
        assertFalse(File(directory, "model.litertlm.part").exists())
    }

    @Test
    fun `a cancelled import leaves no part file and keeps the old model`() {
        val directory = temp.newFolder()
        val first = model()
        runBlocking { store(directory).import(source(first)) }
        lateinit var job: Job
        val endless =
            ModelSource("new.litertlm", -1) {
                object : InputStream() {
                    var reads = 0

                    override fun read(): Int = throw UnsupportedOperationException()

                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (reads++ == 0) {
                            ModelStore.MAGIC.copyInto(b, off)
                            return ModelStore.MAGIC.size
                        }
                        if (reads == 3) job.cancel()
                        return len
                    }
                }
            }

        runBlocking {
            job = launch(Dispatchers.IO) { store(directory).import(endless) }
            job.join()
        }

        assertTrue(store(directory).file.readBytes().contentEquals(first))
        assertFalse(File(directory, "model.litertlm.part").exists())
    }

    @Test
    fun `a new model replaces the old one and clears what the engine worked out for it`() {
        val directory = temp.newFolder()
        val store = store(directory)
        runBlocking { store.import(source(model())) }
        File(store.cacheDirectory, "gpu.bin").apply { parentFile?.mkdirs() }.writeBytes(ByteArray(10))
        val second = model(seed = 5)

        runBlocking { store.import(source(second, "gemma-4-E4B-it.litertlm")) }

        assertTrue(store.file.readBytes().contentEquals(second))
        assertEquals("gemma-4-E4B-it.litertlm", store.installed()?.name)
        assertFalse(store.cacheDirectory.exists())
    }

    @Test
    fun `deleting removes the model, its name and the engine's cache`() {
        val directory = temp.newFolder()
        val store = store(directory)
        runBlocking { store.import(source(model())) }
        File(store.cacheDirectory, "gpu.bin").apply { parentFile?.mkdirs() }.writeBytes(ByteArray(10))

        runBlocking { store.delete() }

        assertNull(store.installed())
        assertEquals(emptyList<String>(), directory.list()?.toList())
    }
}
