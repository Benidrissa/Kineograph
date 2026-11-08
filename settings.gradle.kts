pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Kineograph"

include(
    ":app",
    ":core:model",
    ":core:engine",
    ":core:data",
    ":core:media",
    ":core:ui",
    ":feature:gallery",
    ":feature:editor",
    ":feature:settings",
    // Only linked into the app by the AI build: ./gradlew -Pkineograph.ai=true. See docs/decisions.md.
    ":feature:ai",
)
