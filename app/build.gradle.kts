plugins {
    alias(libs.plugins.android.application)
}

val releaseKeystore = providers.environmentVariable("OFFGRID_KEYSTORE_FILE")
val releaseStorePassword = providers.environmentVariable("OFFGRID_STORE_PASSWORD")
val releaseKeyPassword = providers.environmentVariable("OFFGRID_KEY_PASSWORD")

val requireReleaseSigning = tasks.register("requireReleaseSigning") {
    doLast {
        val missing = listOf(
            "OFFGRID_KEYSTORE_FILE" to releaseKeystore.orNull,
            "OFFGRID_STORE_PASSWORD" to releaseStorePassword.orNull,
            "OFFGRID_KEY_PASSWORD" to releaseKeyPassword.orNull
        ).filter { it.second.isNullOrBlank() }.map { it.first }
        check(missing.isEmpty()) {
            "Release signing is required. Missing: ${missing.joinToString()}. See SIGNING.md."
        }
        check(file(releaseKeystore.get()).isFile) { "Release keystore file is unavailable. See SIGNING.md." }
    }
}

tasks.configureEach {
    if (name == "preReleaseBuild") dependsOn(requireReleaseSigning)
}

android {
    buildFeatures {
        resValues = true
    }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    namespace = "com.example.offgrid"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.mittalsukhansh.offgrid"
        minSdk = 26
        targetSdk = 37
        versionCode = 6
        versionName = "0.3-dev"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        javaCompileOptions {
            annotationProcessorOptions {
                arguments["room.schemaLocation"] = "$projectDir/schemas"
            }
        }
    }

    signingConfigs {
        create("offgridRelease") {
            storeFile = releaseKeystore.orNull?.let { file(it) }
                ?: file("release-keystore-not-configured.p12")
            storeType = "PKCS12"
            storePassword = releaseStorePassword.orNull ?: ""
            keyAlias = "offgrid-release"
            keyPassword = releaseKeyPassword.orNull ?: ""
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "OffGrid Debug")
        }
        release {
            isDebuggable = false
            signingConfig = signingConfigs.getByName("offgridRelease")
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.zxing.embedded)
    implementation(libs.zxing.core)
    implementation(libs.room.runtime)
    annotationProcessor(libs.room.compiler)
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.room.testing)
}
