plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
    api(libs.coroutines.core)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.junit)
}

tasks.test { useJUnit() }
