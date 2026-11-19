plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(project(":markdown-core"))
    implementation(libs.jmh.core)
    annotationProcessor(libs.jmh.generator)
    testImplementation(kotlin("test"))
}

tasks.register<JavaExec>("jmh") {
    group = "verification"
    description = "Compare Markdown parsing workloads on the JVM."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "org.openjdk.jmh.Main"
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
    val report = layout.buildDirectory.file("jmh-results.json")
    args(
        "MarkdownParsingBenchmark",
        "-foe",
        "true",
        "-rf",
        "json",
        "-rff",
        report.get().asFile.absolutePath
    )
}
