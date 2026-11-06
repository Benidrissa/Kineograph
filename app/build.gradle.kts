import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing comes from the environment, so keys never live in the repository. See .github/workflows/android.yml.
val releaseKeystore: String? = System.getenv("KINEOGRAPH_KEYSTORE_PATH")

android {
    namespace = "org.kineograph.app"
    compileSdk = libs.versions.compileSdk
        .get()
        .toInt()

    defaultConfig {
        applicationId = "org.kineograph.app"
        minSdk = libs.versions.minSdk
            .get()
            .toInt()
        targetSdk = libs.versions.targetSdk
            .get()
            .toInt()
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("KINEOGRAPH_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KINEOGRAPH_KEY_ALIAS")
                keyPassword = System.getenv("KINEOGRAPH_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    // F-Droid and reproducible builds need the APK free of this encrypted dependency blob.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("DebugProbesKt.bin", "kotlin-tooling-metadata.json", "META-INF/*.version")
    }
}

// Section 3 of the specification: no INTERNET permission, ever, even if a library tries to add one.
androidComponents {
    onVariants { variant ->
        val manifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
        val check =
            tasks.register("check${variant.name.replaceFirstChar(Char::uppercase)}Permissions") {
                group = "verification"
                description = "Fails if the ${variant.name} manifest asks for a permission the app must not hold."
                inputs.file(manifest)
                doLast {
                    val text = manifest.get().asFile.readText()
                    val banned = listOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE", "com.google.android.gms")
                    val found = banned.filter { it in text }
                    check(found.isEmpty()) { "The merged manifest contains banned entries: $found" }
                }
            }
        tasks.matching { it.name == "check" }.configureEach { dependsOn(check) }
    }
}

dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:model"))
    implementation(project(":core:engine"))
    implementation(project(":core:data"))
    implementation(project(":core:media"))
    implementation(project(":feature:gallery"))
    implementation(project(":feature:editor"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.compose.material3.window.size)
    implementation(libs.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
