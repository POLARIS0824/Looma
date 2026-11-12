package com.example.looma.benchmark;

import java.util.concurrent.TimeUnit;
import org.intellij.markdown.ast.ASTNode;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx1g"})
public class MarkdownParsingBenchmark {
    @Param({"MIXED", "DISPLAY_MATH"})
    public MarkdownWorkloads.Profile profile;

    @Param({"16", "256", "1024"})
    public int targetKiB;

    private MarkdownWorkloads workload;

    @Setup(Level.Trial)
    public void setup() {
        workload = new MarkdownWorkloads(profile, targetKiB);
        workload.verify();
    }

    @Benchmark
    public Object previewBaseline() {
        return workload.previewBaseline();
    }

    @Benchmark
    public Object previewSharedAst() {
        return workload.previewSharedAst();
    }

    @Benchmark
    public Object previewOpening() {
        return workload.previewOpening();
    }

    @Benchmark
    public ASTNode jetBrainsGfmAst() {
        return workload.jetBrainsGfmAst();
    }

    @Benchmark
    public ASTNode jetBrainsWikiAst() {
        return workload.jetBrainsWikiAst();
    }
}
