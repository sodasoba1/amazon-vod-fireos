plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val releaseStoreFile = providers.environmentVariable("RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

val requireReleaseSigning = tasks.register("requireReleaseSigning") {
    doLast {
        val required = mapOf(
            "RELEASE_STORE_FILE" to releaseStoreFile,
            "RELEASE_STORE_PASSWORD" to releaseStorePassword,
            "RELEASE_KEY_ALIAS" to releaseKeyAlias,
            "RELEASE_KEY_PASSWORD" to releaseKeyPassword
        )
        val missing = required.filterValues { it.isNullOrBlank() }.keys
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Release signing is not configured. Missing environment variables: ${missing.joinToString()}"
            )
        }
        val keystore = file(requireNotNull(releaseStoreFile))
        if (!keystore.isFile) {
            throw GradleException("Release keystore file does not exist: ${keystore.absolutePath}")
        }
    }
}

tasks.configureEach {
    if (name == "assembleRelease" || name == "bundleRelease") {
        dependsOn(requireReleaseSigning)
    }
}

android {
    namespace = "com.scriptgod.fireos.avod"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.scriptgod.fireos.avod"
        minSdk = 25
        targetSdk = 34
        versionCode = (findProperty("versionCodeOverride") as? String)?.toIntOrNull() ?: 1
        versionName = (findProperty("versionNameOverride") as? String) ?: "1.0-dev"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
        }
        getByName("debug") {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil)
    implementation(libs.material)
    implementation(libs.androidx.cardview)
    implementation(libs.shimmer)
    testImplementation(libs.junit4)
}
