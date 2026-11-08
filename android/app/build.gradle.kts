import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
}

val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

// Release signing: env vars in CI, android/keystore.properties locally. Never committed.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val ksFile: File? = System.getenv("KFH_KEYSTORE_FILE")?.let { file(it) }
    ?: keystoreProps.getProperty("storeFile")?.let { rootProject.file(it) }
val ksPassword: String? = System.getenv("KFH_KEYSTORE_PASSWORD") ?: keystoreProps.getProperty("storePassword")
val ksAlias: String? = System.getenv("KFH_KEY_ALIAS") ?: keystoreProps.getProperty("keyAlias")
val ksKeyPassword: String? = System.getenv("KFH_KEY_PASSWORD") ?: keystoreProps.getProperty("keyPassword")
val canSign = ksFile?.exists() == true && ksPassword != null && ksAlias != null && ksKeyPassword != null

android {
    namespace = "ru.kfh.tabel"
    compileSdk = 36

    defaultConfig {
        applicationId = "ru.kfh.tabel"
        minSdk = 26
        targetSdk = 36
        versionCode = runNumber
        versionName = "1.0.$runNumber"
    }

    signingConfigs {
        if (canSign) {
            create("release") {
                storeFile = ksFile
                storePassword = ksPassword
                keyAlias = ksAlias
                keyPassword = ksKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (canSign) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.room3.runtime)
    ksp(libs.androidx.room3.compiler)

    testImplementation(libs.junit)
}
