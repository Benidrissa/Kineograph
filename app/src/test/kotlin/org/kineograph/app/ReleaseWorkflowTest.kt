package org.kineograph.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path

/**
 * Holds the publish job to what people can use. Android refuses a split APK on any other kind of phone with just "App not
 * installed", and nobody opening Releases knows which kind they have, so that page must offer only the APK that installs anywhere.
 */
class ReleaseWorkflowTest {
    /** The file patterns given to `gh release create`: what comes after the tag, up to the first option. */
    private val publishedPatterns: List<String> =
        File("../.github/workflows/android.yml")
            .readText()
            .substringAfter("gh release create ")
            .substringBefore(" --")
            .split(Regex("""[\s\\]+"""))
            .filter { it.isNotEmpty() }
            .drop(1)

    @Test
    fun `Releases offers only the APK that installs on every phone`() {
        val matchers = publishedPatterns.map { FileSystems.getDefault().getPathMatcher("glob:$it") }
        val published = RELEASE_FILES.filter { file -> matchers.any { it.matches(Path.of(file)) } }

        assertEquals(listOf("dist/apk/release/app-universal-release.apk"), published)
    }

    private companion object {
        /** What the release job hands to the publish job, by the names the ABI splits in build.gradle.kts give them. */
        val RELEASE_FILES =
            listOf(
                "dist/apk/release/app-arm64-v8a-release.apk",
                "dist/apk/release/app-armeabi-v7a-release.apk",
                "dist/apk/release/app-universal-release.apk",
                "dist/apk/release/app-x86-release.apk",
                "dist/apk/release/app-x86_64-release.apk",
                "dist/bundle/release/app-release.aab",
            )
    }
}
