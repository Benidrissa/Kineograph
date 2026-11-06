package org.kineograph.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.kineograph.data.manifest.ManifestCodec
import org.kineograph.data.manifest.ManifestException
import org.kineograph.data.manifest.ManifestStore
import org.kineograph.model.AudioClip
import org.kineograph.model.AudioTrack
import org.kineograph.model.Background
import org.kineograph.model.BlendMode
import org.kineograph.model.Cel
import org.kineograph.model.CelKey
import org.kineograph.model.FrameGroup
import org.kineograph.model.FrameSlot
import org.kineograph.model.IntRect
import org.kineograph.model.Layer
import org.kineograph.model.OnionSkinSettings
import org.kineograph.model.PaperKind
import org.kineograph.model.Project
import org.kineograph.model.ProjectSettings
import java.io.File

class ManifestTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun rich(name: String = "Rich", background: Background = Background.Paper(PaperKind.GRID)): Project =
        Project(
            id = "p1",
            name = name,
            createdAt = 10,
            updatedAt = 20,
            width = 1280,
            height = 720,
            fps = 24,
            background = background,
            layers = listOf(
                Layer("a", "Ink", opacity = 0.5f, blendMode = BlendMode.MULTIPLY, locked = true),
                Layer("b", "Colour", isReference = true),
            ),
            frames = listOf(FrameSlot("f1", 3), FrameSlot("f2")),
            groups = listOf(FrameGroup("g", "Loop", "f1", "f2", 4)),
            audioTracks = listOf(AudioTrack("t", "Voice", clips = listOf(AudioClip("c", "audio/x.m4a", 2, 10, 500, fadeInMs = 5)))),
            settings = ProjectSettings(onionSkin = OnionSkinSettings(enabled = true, previousFrames = 3)),
            cels = mapOf(CelKey("a", "f1") to Cel("a", "f1", "cels/1.webp", IntRect(-5, 0, 10, 20))),
        )

    @Test
    fun `a project survives a round trip unchanged`() {
        val project = rich()
        assertEquals(project, ManifestCodec.decode(ManifestCodec.encode(project)))
    }

    @Test
    fun `every kind of background survives a round trip`() {
        listOf(
            Background.Color(0xFF123456.toInt()),
            Background.Image("media/bg.webp"),
            Background.Paper(PaperKind.DOTS),
            Background.Transparent,
        ).forEach { background ->
            assertEquals(background, ManifestCodec.decode(ManifestCodec.encode(rich(background = background))).background)
        }
    }

    @Test
    fun `the manifest carries a version and readable field names for other tools`() {
        val text = ManifestCodec.encode(rich())
        assertTrue(text.contains("\"version\":1"))
        assertTrue(text.contains("\"frames\""))
        assertTrue(text.contains("\"cels\":[{"))
        assertTrue(text.contains("\"type\":\"paper\""))
    }

    @Test
    fun `unknown fields from future minor additions are ignored`() {
        val text = ManifestCodec.encode(rich()).replaceFirst("\"name\":\"Rich\"", "\"name\":\"Rich\",\"somethingNew\":{\"a\":1}")
        assertEquals("Rich", ManifestCodec.decode(text).name)
    }

    @Test
    fun `a manifest from a newer app version is refused clearly`() {
        val text = ManifestCodec.encode(rich()).replace("\"version\":1", "\"version\":99")
        val error = assertThrows(ManifestException::class.java) { ManifestCodec.decode(text) }
        assertTrue(error.message?.contains("newer version") == true)
    }

    @Test
    fun `damaged manifests are refused rather than crashing`() {
        listOf(
            "",
            "not json",
            "{}",
            "[1,2,3]",
            "{\"version\":0,\"project\":{}}",
            "{\"version\":1}",
            "{\"version\":1,\"project\":{\"id\":\"x\"}}",
            "{\"version\":\"x\",\"project\":{}}",
        ).forEach { text ->
            assertThrows("for: $text", ManifestException::class.java) { ManifestCodec.decode(text) }
        }
    }

    @Test
    fun `a project with no frames or layers or size is refused`() {
        val good = rich()
        listOf(good.copy(frames = emptyList()), good.copy(layers = emptyList()), good.copy(width = 0)).forEach { bad ->
            assertThrows(ManifestException::class.java) { ManifestCodec.decode(ManifestCodec.encode(bad)) }
        }
    }

    @Test
    fun `migration steps run in order`() {
        val v1 = ManifestCodec.encode(rich())
        val steps =
            listOf<(kotlinx.serialization.json.JsonObject) -> kotlinx.serialization.json.JsonObject>(
                { manifest -> renameProject(manifest, "step1") },
                { manifest -> renameProject(manifest, "step2") },
            )
        val upgraded = ManifestCodec.decode(v1, steps, currentVersion = 3)
        assertEquals("step2", upgraded.name)
    }

    private fun renameProject(manifest: kotlinx.serialization.json.JsonObject, name: String): kotlinx.serialization.json.JsonObject {
        val project = manifest["project"] as kotlinx.serialization.json.JsonObject
        val renamed = kotlinx.serialization.json.JsonObject(project + ("name" to kotlinx.serialization.json.JsonPrimitive(name)))
        return kotlinx.serialization.json.JsonObject(manifest + ("project" to renamed))
    }

    // ----- Crash safety -----

    private fun store(): Pair<ManifestStore, File> {
        val directory = temp.newFolder()
        return ManifestStore(directory) to directory
    }

    private val allFilesExist: (String) -> Boolean = { true }

    @Test
    fun `save then load`() {
        val (store) = store()
        store.write(rich())
        val loaded = store.load(allFilesExist)
        assertNotNull(loaded)
        assertEquals(rich(), loaded?.project)
        assertFalse(loaded?.recoveredFromBackup == true)
    }

    @Test
    fun `nothing saved means nothing to load`() {
        val (store) = store()
        assertNull(store.load(allFilesExist))
    }

    @Test
    fun `a new revision replaces the old one and the old one becomes the backup`() {
        val (store, directory) = store()
        store.write(rich("first"))
        store.write(rich("second"))
        assertEquals("second", store.load(allFilesExist)?.project?.name)
        assertEquals("first", ManifestCodec.decode(File(directory, "manifest.prev.json").readText()).name)
        assertFalse(File(directory, "manifest.tmp").exists())
    }

    @Test
    fun `a corrupt current manifest falls back to the last good one`() {
        val (store, directory) = store()
        store.write(rich("first"))
        store.write(rich("second"))
        File(directory, "manifest.json").writeText("{\"version\":1,\"proj")
        val loaded = store.load(allFilesExist)
        assertEquals("first", loaded?.project?.name)
        assertTrue(loaded?.recoveredFromBackup == true)
    }

    @Test
    fun `a crash before the swap keeps the old revision`() {
        val (store, directory) = store()
        store.write(rich("good"))
        // The crash hit while the new revision was being written.
        File(directory, "manifest.tmp").writeText("{\"version\":1,\"pro")
        assertEquals("good", store.load(allFilesExist)?.project?.name)
    }

    @Test
    fun `a crash in the middle of the swap still finds the new revision`() {
        val (store, directory) = store()
        store.write(rich("old"))
        // State after step 2 of a save: the current manifest moved aside, and the finished new one not yet in place.
        File(directory, "manifest.json").renameTo(File(directory, "manifest.prev.json"))
        File(directory, "manifest.tmp").writeText(ManifestCodec.encode(rich("new")))
        assertEquals("new", store.load(allFilesExist)?.project?.name)
    }

    @Test
    fun `a half-written temporary file during a mid-swap crash falls back to the backup`() {
        val (store, directory) = store()
        store.write(rich("old"))
        File(directory, "manifest.json").renameTo(File(directory, "manifest.prev.json"))
        File(directory, "manifest.tmp").writeText("garbage")
        assertEquals("old", store.load(allFilesExist)?.project?.name)
    }

    @Test
    fun `cels whose images vanished are dropped and counted`() {
        val (store) = store()
        store.write(rich())
        val loaded = store.load { false }
        assertTrue(loaded?.project?.cels?.isEmpty() == true)
        assertEquals(1, loaded?.missingCels)
    }

    @Test
    fun `the backup's files are reported so they can be kept`() {
        val (store) = store()
        store.write(rich())
        store.write(rich().copy(cels = emptyMap()))
        assertEquals(setOf("cels/1.webp"), store.filesReferencedByBackup())
    }

    @Test
    fun `a newer app's project is identified rather than treated as damage`() {
        val (store, directory) = store()
        store.write(rich())
        assertFalse(store.needsNewerApp())
        val file = File(directory, "manifest.json")
        file.writeText(file.readText().replace("\"version\":1", "\"version\":99"))
        assertTrue(store.needsNewerApp())
    }
}
