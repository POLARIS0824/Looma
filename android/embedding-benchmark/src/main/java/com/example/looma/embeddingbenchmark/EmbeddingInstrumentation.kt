package com.example.looma.embeddingbenchmark

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

class EmbeddingInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val context = targetContext
        val files = context.filesDir
        val output = File(files, "result.json")
        val temporary = File(files, "result.json.tmp")
        val report = Bundle()
        try {
            Files.deleteIfExists(output.toPath())
            Files.deleteIfExists(temporary.toPath())
            val request = Request.parse(files)
            val result = runBenchmark(context, request)
            temporary.writeText(result.toString())
            Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.ATOMIC_MOVE)
            report.putString("stream", "\nWrote ${output.absolutePath}\n")
            finish(Activity.RESULT_OK, report)
        } catch (failure: Exception) {
            temporary.delete()
            finishFailed(report, failure)
        } catch (failure: LinkageError) {
            temporary.delete()
            finishFailed(report, failure)
        }
    }

    private fun finishFailed(report: Bundle, failure: Throwable) {
        Log.e(TAG, "Embedding benchmark failed", failure)
        report.putString("stream", "\n${Log.getStackTraceString(failure)}\n")
        finish(Activity.RESULT_CANCELED, report)
    }
}

internal enum class Model(val id: String, val dimensions: Int, val tokenizerName: String?) {
    BGE("bge", 512, "vocab.txt"),
    BEKKO("bekko", 128, "tokenizer.json"),
    GEMMA_CPU("gemma-cpu", 768, null),
    GEMMA_GPU("gemma-gpu", 768, null),
}

internal enum class Role(val id: String) {
    QUERY("query"),
    DOCUMENT("doc"),
}

internal data class Input(val id: String, val text: String, val role: Role)

internal data class TokenizerReference(val text: String, val ids: LongArray)

internal interface Embedder : Closeable {
    val backend: String
    fun embed(input: Input): FloatArray
}

private data class Request(
    val model: Model,
    val weights: File,
    val tokenizer: File?,
    val threads: Int,
    val inputs: List<Input>,
    val latency: List<Input>,
    val repeats: Int,
    val workload: List<Int>,
    val tokenizerReferences: List<TokenizerReference>,
) {
    companion object {
        fun parse(files: File): Request {
            val json = JSONObject(File(files, "request.json").readText())
            val modelId = json.getString("model")
            val model = Model.entries.firstOrNull { it.id == modelId }
                ?: error("Unsupported model: $modelId")
            val weightsDirectory = File(files, "weights").canonicalFile
            val modelName = json.getString("weights")
            val modelExtension = if (model.tokenizerName == null) "litertlm" else "onnx"
            require(modelName.endsWith(".$modelExtension")) { "Expected .$modelExtension weights for $modelId" }
            val tokenizer = model.tokenizerName?.let { name ->
                require(json.getString("tokenizer") == name) { "Expected $name for $modelId" }
                weightsDirectory.readableFile(name)
            }
            val inputs = json.getJSONArray("inputs").inputs()
            require(inputs.map(Input::id).toSet().size == inputs.size) { "Duplicate corpus input id" }

            val workloads = json.getJSONArray("workload")
            val references = json.getJSONArray("tokenizerReferences")
            require(tokenizer != null || references.length() == 0) { "Tokenizer parity requires an ORT model" }
            return Request(
                model = model,
                weights = weightsDirectory.readableFile(modelName),
                tokenizer = tokenizer,
                threads = json.optInt("threads", 2).also {
                    require(it > 0) { "Expected positive thread count" }
                },
                inputs = inputs,
                latency = json.getJSONArray("latency").inputs(),
                repeats = json.optInt("repeats", 5).also {
                    require(it > 0) { "Expected positive repeat count" }
                },
                workload = List(workloads.length()) { index ->
                    workloads.getInt(index).also { require(it > 0) { "Expected positive workload size" } }
                },
                tokenizerReferences = List(references.length()) { index ->
                    val reference = references.getJSONObject(index)
                    val ids = reference.getJSONArray("ids")
                    TokenizerReference(reference.getString("text"), LongArray(ids.length()) { ids.getLong(it) })
                },
            )
        }

        private fun File.readableFile(name: String): File {
            require(name.isNotBlank() && '/' !in name && '\\' !in name) { "Expected file name under weights: $name" }
            val file = File(this, name).canonicalFile
            require(file.parentFile == this && file.isFile && file.canRead()) { "Cannot read weights file: $name" }
            return file
        }

        private fun JSONArray.inputs(): List<Input> {
            require(length() > 0) { "Expected at least one input" }
            return List(length()) { index ->
                val entry = getJSONObject(index)
                val id = entry.getString("id")
                val text = entry.getString("text")
                val roleId = entry.getString("role")
                require(id.isNotBlank() && text.isNotBlank()) { "Expected input id and text at index $index" }
                val role = Role.entries.firstOrNull { it.id == roleId } ?: error("Unexpected input role: $roleId")
                Input(id, text, role)
            }
        }
    }
}

private fun runBenchmark(context: Context, request: Request): JSONObject {
    fun thermalStatus() = if (Build.VERSION.SDK_INT >= 29) {
        context.getSystemService(PowerManager::class.java).currentThermalStatus
    } else {
        null
    }

    fun batteryTemperature() = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        ?.takeUnless { it == Int.MIN_VALUE }
        ?.div(10.0)

    val abis = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
    val beforeRss = memoryKiB("VmRSS")
    val thermalBefore = thermalStatus()
    val batteryBefore = batteryTemperature()
    val loadStart = SystemClock.elapsedRealtimeNanos()
    val tokenizer = request.tokenizer?.let { WordPieceTokenizer(it, request.model) }
    val provider = if (tokenizer == null) {
        GemmaEmbedder(context, request.weights, request.model)
    } else {
        OrtEmbedder(request.weights, tokenizer, request.model, request.threads)
    }

    return provider.use {
        val loadMs = elapsedMs(loadStart)
        fun embed(input: Input): FloatArray {
            val vector = provider.embed(input)
            check(vector.size == request.model.dimensions) {
                "${input.id}: expected ${request.model.dimensions} dimensions, got ${vector.size}"
            }
            check(vector.all(Float::isFinite)) { "${input.id}: embedding contained a non-finite value" }
            val magnitudeSquared = vector.sumOf { it.toDouble() * it }
            check(abs(magnitudeSquared - 1.0) <= 1e-4) { "${input.id}: embedding did not have unit norm" }
            return vector
        }

        val latencyInputs = request.latency
        val firstStart = SystemClock.elapsedRealtimeNanos()
        embed(latencyInputs.first())
        val firstEmbeddingMs = elapsedMs(firstStart)
        for (input in latencyInputs.drop(1)) {
            embed(input)
        }
        val samples = JSONArray()
        val roundMedians = JSONArray()
        repeat(request.repeats) {
            val round = latencyInputs.map { input ->
                val start = SystemClock.elapsedRealtimeNanos()
                embed(input)
                elapsedMs(start)
            }
            round.forEach { samples.put(it) }
            roundMedians.put(round.median())
        }

        tokenizer?.verify(request.tokenizerReferences)
        val corpusStart = SystemClock.elapsedRealtimeNanos()
        val vectors = request.inputs.associate { input -> input.id to embed(input) }
        val corpusMs = elapsedMs(corpusStart)

        val workloads = JSONArray()
        if (request.workload.isNotEmpty()) {
            val workloadInputs = tokenizer?.let { listOf(it.workloadInput()) } ?: latencyInputs
            for (count in request.workload) {
                Log.i(TAG, "Running $count workload embeddings for ${request.model.id}")
                val start = SystemClock.elapsedRealtimeNanos()
                repeat(count) { index -> embed(workloadInputs[index % workloadInputs.size]) }
                workloads.put(JSONObject().put("chunks", count).put("timeMs", elapsedMs(start)))
            }
        }

        val peakRss = memoryKiB("VmHWM")
        val thermalAfter = thermalStatus()
        val batteryAfter = batteryTemperature()
        val vectorsJson = JSONObject()
        for ((id, values) in vectors) {
            val vector = JSONArray()
            values.forEach { vector.put(it.toDouble()) }
            vectorsJson.put(id, vector)
        }
        JSONObject()
            .put("model", request.model.id)
            .put("backend", provider.backend)
            .put("device", Build.MODEL)
            .put("abi", abis.first())
            .put("api", Build.VERSION.SDK_INT)
            .put("dimension", request.model.dimensions)
            .put("threads", request.threads.takeIf { tokenizer != null })
            .put("loadMs", loadMs)
            .put("firstEmbeddingMs", firstEmbeddingMs)
            .put("samplesMs", samples)
            .put("roundMedianMs", roundMedians)
            .put("corpusMs", corpusMs)
            .put("peakRssKiB", peakRss)
            .put("beforeRssKiB", beforeRss)
            .put("workloads", workloads)
            .put("tokenizerParityCases", request.tokenizerReferences.size)
            .put("thermalStatusBefore", thermalBefore)
            .put("thermalStatusAfter", thermalAfter)
            .put("batteryTempCBefore", batteryBefore)
            .put("batteryTempCAfter", batteryAfter)
            .put("vectors", vectorsJson)
    }
}

private fun List<Double>.median(): Double {
    val sorted = sorted()
    val midpoint = size / 2
    return if (size % 2 == 0) (sorted[midpoint - 1] + sorted[midpoint]) / 2 else sorted[midpoint]
}

private fun memoryKiB(field: String): Long = File("/proc/self/status").useLines { lines ->
    val value = lines.firstOrNull { it.startsWith("$field:") } ?: error("Missing $field in /proc/self/status")
    value.substringAfter(':').trim().substringBefore(' ').toLong()
}

private fun elapsedMs(start: Long): Double = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0

private const val TAG = "LoomaEmbeddingBenchmark"
