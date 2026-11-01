package com.kivora.music

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads the compact English Whisper model once. All files use the same immutable
 * Hugging Face revision so later upstream changes cannot silently change the model.
 */
internal object OfflineSubtitleModelManager {
    private const val MODEL_REVISION = "2ffb4044a6556d7c2b4b335845ed4135fd5913ac"
    private const val MODEL_REPOSITORY = "csukuangfj/sherpa-onnx-whisper-tiny.en"

    private data class ModelFile(
        val name: String,
        val sha256: String?,
        val minimumBytes: Long,
        val maximumBytes: Long,
    )

    private val modelFiles = listOf(
        ModelFile(
            "tiny.en-encoder.int8.onnx",
            "e567882ec0a73cbd40e24487905481efbffdfc9999c4ef93eff7c994f687850c",
            10_000_000L,
            20_000_000L,
        ),
        ModelFile(
            "tiny.en-decoder.int8.onnx",
            "28ecd4064009306dc0e7ed60a91610663213b3a127d0fb2ef0e637df3b260d00",
            80_000_000L,
            100_000_000L,
        ),
        // The vocabulary is pinned to the same immutable revision and size-checked.
        ModelFile("tiny.en-tokens.txt", null, 500_000L, 1_500_000L),
    )

    fun modelDirectory(context: Context): File =
        File(context.filesDir, "offline-subtitles/whisper-tiny-en")

    fun isModelReady(context: Context): Boolean {
        val directory = modelDirectory(context)
        return modelFiles.all { modelFile ->
            val file = File(directory, modelFile.name)
            file.isFile && file.length() in modelFile.minimumBytes..modelFile.maximumBytes &&
                (modelFile.sha256 == null || sha256(file) == modelFile.sha256)
        }
    }

    /** Must run on a worker thread. Progress callback: filename, downloaded bytes, total bytes. */
    @Throws(IOException::class)
    fun ensureModelDownloaded(
        context: Context,
        onProgress: (String, Long, Long) -> Unit = { _, _, _ -> },
    ): File {
        val directory = modelDirectory(context)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Could not create the subtitle model folder.")
        }

        modelFiles.forEach { modelFile ->
            val destination = File(directory, modelFile.name)
            val validExisting = destination.isFile &&
                destination.length() in modelFile.minimumBytes..modelFile.maximumBytes &&
                (modelFile.sha256 == null || sha256(destination) == modelFile.sha256)
            if (validExisting) return@forEach

            destination.delete()
            val temporary = File(directory, "${modelFile.name}.download")
            temporary.delete()
            val url = URL(
                "https://huggingface.co/$MODEL_REPOSITORY/resolve/$MODEL_REVISION/${modelFile.name}"
            )
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 120_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "A1FamilyMedia/1.4 offline-subtitles")
            }

            try {
                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    throw IOException("Subtitle model download failed (HTTP $responseCode).")
                }
                val contentLength = connection.contentLengthLong
                var downloaded = 0L
                connection.inputStream.use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            if (Thread.currentThread().isInterrupted) {
                                throw IOException("Subtitle model download was cancelled.")
                            }
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloaded += count
                            onProgress(modelFile.name, downloaded, contentLength)
                        }
                    }
                }
                if (downloaded !in modelFile.minimumBytes..modelFile.maximumBytes) {
                    throw IOException("The downloaded subtitle model file has an unexpected size.")
                }
                if (modelFile.sha256 != null && sha256(temporary) != modelFile.sha256) {
                    throw IOException("Subtitle model verification failed. Please retry the download.")
                }
                if (!temporary.renameTo(destination)) {
                    throw IOException("Could not save the downloaded subtitle model.")
                }
            } finally {
                connection.disconnect()
                temporary.delete()
            }
        }

        if (!isModelReady(context)) {
            throw IOException("Subtitle model verification did not complete.")
        }
        return directory
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** CPU-only English Whisper recognizer. Pass mono float PCM sampled at 16 kHz. */
internal class OfflineWhisperEngine(modelDirectory: File) : AutoCloseable {
    private val recognizer = OfflineRecognizer(
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = File(modelDirectory, "tiny.en-encoder.int8.onnx").absolutePath,
                    decoder = File(modelDirectory, "tiny.en-decoder.int8.onnx").absolutePath,
                    language = "en",
                    task = "transcribe",
                    enableTokenTimestamps = true,
                ),
                tokens = File(modelDirectory, "tiny.en-tokens.txt").absolutePath,
                numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 2),
                provider = "cpu",
                modelType = "whisper",
            ),
        ),
    )

    /** Recognizes one short audio window (Whisper's input window is about 30 seconds). */
    fun transcribeWindow(
        samples16kMono: FloatArray,
        absoluteStartSeconds: Float,
        windowDurationSeconds: Float,
    ): List<OfflineSubtitleCue> {
        require(samples16kMono.isNotEmpty()) { "There is no audio in this section." }
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples16kMono, 16_000)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            OfflineWhisperTokenFormatter.toCues(
                tokens = result.tokens.toList(),
                timestamps = result.timestamps.toList(),
                absoluteStartSeconds = absoluteStartSeconds,
                windowDurationSeconds = windowDurationSeconds,
            )
        } finally {
            stream.release()
        }
    }

    override fun close() {
        recognizer.release()
    }
}
