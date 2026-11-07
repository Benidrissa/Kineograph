package org.kineograph.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** A settings file is the last thing that should be able to stop the app, or lose the user's drawings by forcing a reset. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRepositoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun repositoryAt(file: File) =
        SettingsRepository(
            PreferenceDataStoreFactory.create(corruptionHandler = settingsCorruptionHandler, scope = scope) { file },
        )

    private fun settingsFile() = File(temp.newFolder(), "settings.preferences_pb")

    @Test
    fun `settings can be changed and read back`() {
        val repository = repositoryAt(settingsFile())
        assertTrue(runBlocking { repository.setToolbarSide(ToolbarSide.RIGHT) })
        assertEquals(ToolbarSide.RIGHT, runBlocking { repository.settings.first() }.toolbarSide)
    }

    @Test
    fun `a damaged settings file becomes the defaults and can be written again`() {
        val file = settingsFile().apply { writeBytes(byteArrayOf(0x12, 0x34, 0x56, 0x78, -1, -1, -1)) }
        val repository = repositoryAt(file)
        assertEquals(AppSettings(), runBlocking { repository.settings.first() })
        assertTrue("the user should be told", repository.wereSettingsReset())
        assertTrue(runBlocking { repository.setSampleCreated() })
        assertTrue(runBlocking { repository.settings.first() }.sampleCreated)
    }

    @Test
    fun `settings that cannot be read at all fall back to the defaults, and writing them fails quietly`() {
        // A directory where the file should be cannot be read or written, which no repair can fix.
        val file = settingsFile().apply { mkdirs() }
        File(file, "in-the-way").writeText("x")
        val repository = repositoryAt(file)
        assertEquals(AppSettings(), runBlocking { repository.settings.first() })
        assertFalse(runBlocking { repository.setSampleCreated() })
        assertFalse(runBlocking { repository.setTheme(ThemeMode.DARK) })
    }

    @Test
    fun `the real store opens through a context`() {
        val repository = SettingsRepository(ApplicationProvider.getApplicationContext<Context>())
        assertEquals(AppSettings(), runBlocking { repository.settings.first() })
    }

    @Test
    fun `exports the user has seen are remembered, and forgotten once they no longer exist`() {
        val repository = repositoryAt(settingsFile())
        assertEquals(emptySet<String>(), runBlocking { repository.seenExports() })
        assertTrue(runBlocking { repository.markExportSeen("a") })
        assertTrue(runBlocking { repository.markExportSeen("b") })
        assertEquals(setOf("a", "b"), runBlocking { repository.seenExports() })
        assertTrue(runBlocking { repository.keepSeenExports(setOf("b", "c")) })
        assertEquals(setOf("b"), runBlocking { repository.seenExports() })
    }

    @Test
    fun `seen exports that cannot be read count as none, which only offers a result again`() {
        val file = settingsFile().apply { mkdirs() }
        File(file, "in-the-way").writeText("x")
        val repository = repositoryAt(file)
        assertEquals(emptySet<String>(), runBlocking { repository.seenExports() })
        assertFalse(runBlocking { repository.markExportSeen("a") })
    }
}
