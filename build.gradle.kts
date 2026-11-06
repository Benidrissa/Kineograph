plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}

val ktlintCli: Configuration by configurations.creating {
    // ktlint publishes a normal and a self-contained variant. Only the self-contained one runs as a tool.
    attributes { attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.SHADOWED)) }
}

dependencies {
    ktlintCli(libs.ktlint.cli)
}

// ktlint runs as a standalone tool so it cannot conflict with the build's Kotlin version.
val ktlintCheck by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Checks Kotlin sources against the official code style."
    classpath = ktlintCli
    mainClass.set("com.pinterest.ktlint.Main")
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
    args("**/src/**/*.kt", "**/*.gradle.kts", "!**/build/**")
}

val ktlintFormat by tasks.registering(JavaExec::class) {
    group = "formatting"
    description = "Applies the official code style to Kotlin sources."
    classpath = ktlintCli
    mainClass.set("com.pinterest.ktlint.Main")
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
    args("--format", "**/src/**/*.kt", "**/*.gradle.kts", "!**/build/**")
    isIgnoreExitValue = true
}

// Section 9 of the specification: the engine never uses the not-null assertion operator.
val checkNoBangBangInEngine by tasks.registering {
    group = "verification"
    description = "Fails if :core:engine main sources contain the !! operator."
    val sources = fileTree("core/engine/src/main") { include("**/*.kt") }
    inputs.files(sources)
    doLast {
        val offenders =
            sources.files.flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val code = line.substringBefore("//")
                    if ("!!" in code) "${file.relativeTo(rootDir)}:${index + 1}" else null
                }
            }
        check(offenders.isEmpty()) { "The !! operator is banned in :core:engine:\n" + offenders.joinToString("\n") }
    }
}
