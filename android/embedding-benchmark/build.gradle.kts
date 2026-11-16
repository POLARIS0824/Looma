plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.looma.embeddingbenchmark"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.looma.embeddingbenchmark"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1"
        ndk {
            abiFilters += setOf("arm64-v8a", "x86_64")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.benchmark.ort)
    implementation(libs.benchmark.mediapipe)
    implementation(libs.benchmark.litertlm)
    implementation(libs.kotlinx.coroutines.core)
}

val python = providers.gradleProperty("embeddingPython").orElse("python")
val script = layout.projectDirectory.file("benchmark.py")
val apk = layout.buildDirectory.file("outputs/apk/debug/embedding-benchmark-debug.apk")
val model = providers.gradleProperty("embeddingModel").orElse("gemma-cpu")
val target = providers.gradleProperty("embeddingTarget")
    .orElse(model.map { if (it == "qwen4") "host" else "android" })
val offline = gradle.startParameter.isOffline
val ragScript = layout.projectDirectory.file("rag_benchmark.py")

fun Exec.benchmarkCommand(action: String) {
    workingDir(layout.projectDirectory)
    executable = python.get()
    args("-B", script.asFile.absolutePath, action)
    val options = mapOf(
        "embeddingCorpus" to "--corpus",
        "embeddingChunkChars" to "--chunk-chars",
        "embeddingOverlapChars" to "--overlap-chars",
    )
    options.forEach { (property, flag) ->
        providers.gradleProperty(property).orNull?.let { args(flag, it) }
    }
}

tasks.register<Exec>("chunking") {
    group = "verification"
    description = "Measure chunk coverage, extraction time, and cache reuse without a model or device."
    benchmarkCommand("chunking")
}

tasks.register<Exec>("rag") {
    group = "verification"
    description = "Compare RAG retrieval on the labeled corpus with Gemma CPU or Bekko embeddings."
    dependsOn("assembleDebug")
    workingDir(layout.projectDirectory)
    executable = python.get()
    args("-B", ragScript.asFile.absolutePath, "--model", model.get(), "--apk", apk.get().asFile.absolutePath)
    providers.gradleProperty("embeddingSerial").orNull?.let { args("--serial", it) }
    providers.gradleProperty("embeddingAssets").orNull?.let { args("--assets", it) }
    if (offline) args("--offline")
}

tasks.register<Exec>("benchmark") {
    group = "verification"
    description = "Measure embedding latency, memory, and retrieval. Use -PembeddingModel, -PembeddingStrategy, and -PembeddingSerial."
    benchmarkCommand("run")
    args("--model", model.get(), "--target", target.get(), "--apk", apk.get().asFile.absolutePath)
    if (target.get() == "android" && !model.get().startsWith("qwen")) {
        dependsOn("assembleDebug")
    }
    val options = mapOf(
        "embeddingSerial" to "--serial",
        "embeddingAssets" to "--assets",
        "embeddingThreads" to "--threads",
        "embeddingRounds" to "--rounds",
        "embeddingWorkloads" to "--workloads",
        "embeddingStrategy" to "--strategy",
    )
    options.forEach { (property, flag) ->
        providers.gradleProperty(property).orNull?.let { args(flag, it) }
    }
    if (offline) args("--offline")
}
