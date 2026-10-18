plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

// Shared test data lives at the repo root (CLAUDE.md rule 6): one copy for all three codebases.
val fixturesDir = rootProject.layout.projectDirectory.dir("../fixtures")

// Ship the catalog seed inside the jar so the local splitter knows item words offline.
val copyCatalogSeed = tasks.register<Copy>("copyCatalogSeed") {
    from(fixturesDir.file("catalog_seed.json"))
    into(layout.buildDirectory.dir("generated/fixtureResources"))
}

sourceSets {
    main {
        resources.srcDir(copyCatalogSeed)
    }
}

tasks.test {
    systemProperty("fixtures.dir", fixturesDir.asFile.absolutePath)
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
    inputs.dir(fixturesDir).withPropertyName("fixtures")
    testLogging {
        events("failed")
        showStandardStreams = true
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
