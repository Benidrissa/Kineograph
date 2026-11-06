package org.kineograph.data.manifest

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.kineograph.model.Project

/** The manifest's schema version. Bump it, and add a step to [ManifestCodec.migrations], whenever the format changes. */
public const val MANIFEST_VERSION: Int = 1

/** The manifest is damaged, or was written by a newer version of the app than this one. */
public class ManifestException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Converts projects to and from `manifest.json`, documented in docs/file-format.md. Old files are upgraded
 * by running the raw JSON through [migrations] one version at a time before it is decoded.
 */
public object ManifestCodec {
    @OptIn(ExperimentalSerializationApi::class)
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
            classDiscriminator = "type"
        }

    private val pretty =
        Json(json) {
            prettyPrint = false
        }

    /**
     * Steps that upgrade a manifest. The entry at index `n` turns version `n + 1` into version `n + 2`.
     * Version 1 is the first format, so the list is empty for now.
     */
    internal val migrations: List<(JsonObject) -> JsonObject> = emptyList()

    public fun encode(project: Project, generator: String = "Kineograph"): String {
        val body = json.encodeToJsonElement(Project.serializer(), project)
        val manifest =
            buildJsonObject {
                put(KEY_VERSION, MANIFEST_VERSION)
                put(KEY_GENERATOR, generator)
                put(KEY_PROJECT, body)
            }
        return pretty.encodeToString(JsonObject.serializer(), manifest)
    }

    @Throws(ManifestException::class)
    public fun decode(text: String): Project = decode(text, migrations, MANIFEST_VERSION)

    @Throws(ManifestException::class)
    internal fun decode(text: String, steps: List<(JsonObject) -> JsonObject>, currentVersion: Int): Project {
        try {
            var manifest = json.parseToJsonElement(text).jsonObject
            var version = (manifest[KEY_VERSION] as? JsonPrimitive)?.int ?: throw ManifestException("The manifest has no version")
            if (version < 1) throw ManifestException("Invalid manifest version $version")
            if (version > currentVersion) {
                throw ManifestException("This project was saved by a newer version of the app (format $version)")
            }
            while (version < currentVersion) {
                manifest = steps[version - 1](manifest)
                version++
            }
            val body = manifest[KEY_PROJECT] ?: throw ManifestException("The manifest has no project")
            return validated(json.decodeFromJsonElement(Project.serializer(), body))
        } catch (e: ManifestException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw ManifestException("The manifest is damaged", e)
        } catch (e: IllegalStateException) {
            throw ManifestException("The manifest is damaged", e)
        }
    }

    /** Refuses projects the app could not work with. A file from elsewhere must not be able to crash the editor. */
    private fun validated(project: Project): Project {
        if (project.width <= 0 || project.height <= 0) throw ManifestException("Invalid canvas size")
        if (project.layers.isEmpty()) throw ManifestException("A project needs at least one layer")
        if (project.frames.isEmpty()) throw ManifestException("A project needs at least one frame")
        return project
    }

    private const val KEY_VERSION = "version"
    private const val KEY_GENERATOR = "generator"
    private const val KEY_PROJECT = "project"
}
