plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.poselandmarker"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.poselandmarker"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

// ── Auto-download MediaPipe pose landmarker model ──
val assetsDir = "$projectDir/src/main/assets"
val modelUrl =
    "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/latest/pose_landmarker_full.task"

tasks.register("downloadModel") {
    doLast {
        val modelFile = File(assetsDir, "pose_landmarker_full.task")
        if (!modelFile.exists()) {
            modelFile.parentFile.mkdirs()
            println("Downloading pose_landmarker_full.task (~31 MB)...")
            val url = uri(modelUrl).toURL()
            url.openStream().use { input ->
                modelFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            println("Download complete!")
        } else {
            println("Model already exists, skipping download.")
        }
    }
}

tasks.named("preBuild") {
    dependsOn("downloadModel")
}

dependencies {
    // MediaPipe Tasks Vision
    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    // CameraX
    val cameraxVersion = "1.4.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // AndroidX
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}
