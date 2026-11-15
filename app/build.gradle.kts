plugins {
    alias(libs.plugins.android.application)
}

android {
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    namespace = "com.example.offgrid"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.offgrid"
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        javaCompileOptions {
            annotationProcessorOptions {
                arguments["room.schemaLocation"] = "$projectDir/schemas"
            }
        }
    }

    buildTypes {
        release {
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
