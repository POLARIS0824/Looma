plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(25)
    sourceSets.named("main") {
        kotlin.srcDir(rootProject.layout.projectDirectory.dir("app/src/main/java"))
        kotlin.include(
            "com/example/looma/benchmark/**",
            "com/example/looma/domain/markdown/Frontmatter.kt",
            "com/example/looma/domain/markdown/MarkdownDocument.kt",
            "com/example/looma/domain/markdown/MarkdownIndex.kt",
            "com/example/looma/domain/markdown/MarkdownMath.kt",
            "com/example/looma/domain/markdown/PermissiveMathParser.kt",
            "com/example/looma/domain/markdown/WikiImageFlavour.kt",
            "com/example/looma/domain/markdown/WikiLinks.kt",
            "com/example/looma/ui/markdown/PreparedMarkdown.kt",
        )
    }
}

dependencies {
    implementation(libs.markdown.renderer.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.jmh.core)
    annotationProcessor(libs.jmh.generator)
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
