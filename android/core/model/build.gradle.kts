plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}

// FlagsTest checks the defaults against the backend's Remote Config template (one source: CONTRACT §10).
tasks.withType<Test>().configureEach {
    val template = rootProject.layout.projectDirectory.file("../backend/remoteconfig.template.json")
    inputs.file(template)
    systemProperty("remoteconfig.template", template.asFile.absolutePath)
}
