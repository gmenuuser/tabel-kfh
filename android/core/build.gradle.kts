plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // In the public API of :core (Op.fields, Api(OkHttpClient), SyncEngine.status): the app gets them too.
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    api(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    // shared/ lives at the repository root, one level above android/.
    systemProperty("kfh.repoRoot", rootProject.projectDir.parentFile.absolutePath)
}
