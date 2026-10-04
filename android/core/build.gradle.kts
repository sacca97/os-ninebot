plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
    api(libs.coroutines.core)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.junit)
}

tasks.test { useJUnit() }

// Profiles are validated and compiled into :core; no parser is shipped in the app.
val generatedProfiles = layout.buildDirectory.dir("generated/sources/profiles/kotlin")
val generateDeviceProfiles by tasks.registering(Exec::class) {
    val source = rootProject.file("profiles")
    val generator = rootProject.file("scripts/generate_profiles.py")
    inputs.dir(source)
    inputs.file(generator)
    outputs.dir(generatedProfiles)
    commandLine("python3", generator.absolutePath, source.absolutePath,
        generatedProfiles.get().file("com/sacca/openride/core/profile/DeviceProfiles.kt").asFile.absolutePath)
}
kotlin.sourceSets.named("main") { kotlin.srcDir(generatedProfiles) }
tasks.named("compileKotlin") { dependsOn(generateDeviceProfiles) }

val checkDeviceProfiles by tasks.registering(Exec::class) {
    commandLine("python3", rootProject.file("scripts/test_generate_profiles.py").absolutePath)
}
tasks.named("test") { dependsOn(checkDeviceProfiles) }
