import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// Release signing is optional and never committed: keystore.properties in the project root
// (storeFile, storePassword, keyAlias, keyPassword).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "app.pocketagent"
    compileSdk {
        version = release(37) { minorApiLevel = 0 }
    }

    defaultConfig {
        applicationId = "app.mypai"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "1.1.1"
        // A later build of the same version: people who already have 1.1.1 are offered it, and the version they see stays
        // the same. Keep in step with appRevision in desktop/build.gradle.kts; back to 0 when versionName goes up.
        buildConfigField("int", "REVISION", "1")
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3:1.5.0-rc01")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.fragment:fragment-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")
    implementation("androidx.datastore:datastore-preferences:1.3.0")
    implementation("androidx.biometric:biometric:1.1.0")
    // Wakes the app for scheduled tasks when it is closed.
    implementation("androidx.work:work-runtime-ktx:2.12.0")

    implementation("androidx.room:room-runtime:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
    implementation("com.squareup.okhttp3:okhttp:5.1.0")
    // QR codes for linking devices: drawn on one, read from a photo on the other. Apache-2.0, no network, no Play services.
    implementation("com.google.zxing:core:3.5.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
