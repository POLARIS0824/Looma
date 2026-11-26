plugins {
    id("com.android.library")
}

android {
    namespace = "com.example.looma.embedding"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.mediapipe.retrieval)
    implementation(libs.litertlm.android)
    implementation(libs.litert.npu.runtime.qualcomm)
    implementation(libs.litert.npu.runtime.mediatek)
    implementation(libs.litert.npu.runtime.google.tensor)
}
