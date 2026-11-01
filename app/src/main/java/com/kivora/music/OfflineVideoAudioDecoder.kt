package com.kivora.music

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.IOException
import java.nio.ByteOrder

/**
 * Decodes the selected video's audio track to bounded 30-second mono/16 kHz windows.
 * Audio is decoded locally; no media bytes are uploaded or sent to a network service.
 */
internal object OfflineVideoAudioDecoder {
    private const val TARGET_SAMPLE_RATE = 16_000
    private const val WINDOW_SAMPLES = TARGET_SAMPLE_RATE * 30
    private const val DEQUEUE_TIMEOUT_US = 10_000L

    /**
     * Calls [onWindow] on the calling worker thread for each decoded audio window.
     * The callback must finish processing the window before returning.
     */
    @Throws(IOException::class)
    fun decode(
        context: Context,
        videoUri: Uri,
        onWindow: (FloatArray, Float, Float) -> Unit,
        onProgress: (Float, Float) -> Unit = { _, _ -> },
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, videoUri, null)
            var audioTrack = -1
            var audioFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                val mime = candidate.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrack = index
                    audioFormat = candidate
                    break
                }
            }
            val inputFormat = audioFormat
                ?: throw IOException("This video does not contain an audio track.")
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IOException("The video's audio format is not supported.")

            extractor.selectTrack(audioTrack)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            var outputSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var outputChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var inputFinished = false
            var outputFinished = false
            var emittedAnySamples = false
            var windowStartSeconds = -1f
            val window = ArrayList<Float>(WINDOW_SAMPLES)

            fun emitSample(value: Float, timestampSeconds: Float) {
                if (windowStartSeconds < 0f) windowStartSeconds = timestampSeconds
                window.add(value.coerceIn(-1f, 1f))
                if (window.size >= WINDOW_SAMPLES) {
                    val samples = FloatArray(window.size) { window[it] }
                    val duration = samples.size.toFloat() / TARGET_SAMPLE_RATE
                    onWindow(samples, windowStartSeconds, duration)
                    window.clear()
                    windowStartSeconds += duration
                }
                emittedAnySamples = true
            }

            val resampler = LinearMonoResampler(::emitSample)
            val info = MediaCodec.BufferInfo()
            while (!outputFinished) {
                if (Thread.currentThread().isInterrupted) {
                    throw IOException("Subtitle generation was cancelled.")
                }

                if (!inputFinished) {
                    val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                            ?: throw IOException("Could not read the video's audio stream.")
                        inputBuffer.clear()
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputFinished = true
                        } else {
                            val presentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, presentationTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        outputSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        outputChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                    }
                    else -> if (outputIndex >= 0) {
                        if (info.size > 0) {
                            val outputBuffer = codec.getOutputBuffer(outputIndex)
                                ?: throw IOException("Could not read decoded audio samples.")
                            outputBuffer.order(ByteOrder.nativeOrder())
                            outputBuffer.position(info.offset)
                            outputBuffer.limit(info.offset + info.size)

                            val bytesPerSample = when (pcmEncoding) {
                                AudioFormat.ENCODING_PCM_FLOAT -> 4
                                AudioFormat.ENCODING_PCM_16BIT -> 2
                                else -> throw IOException("The decoded audio format is not supported.")
                            }
                            val bytesPerFrame = bytesPerSample * outputChannels
                            val frameCount = info.size / bytesPerFrame
                            val bufferStartSeconds = info.presentationTimeUs / 1_000_000f

                            for (frame in 0 until frameCount) {
                                var mono = 0f
                                repeat(outputChannels) {
                                    val channelSample = when (pcmEncoding) {
                                        AudioFormat.ENCODING_PCM_FLOAT -> outputBuffer.getFloat()
                                        else -> outputBuffer.getShort() / 32768f
                                    }
                                    mono += channelSample
                                }
                                mono /= outputChannels.coerceAtLeast(1)
                                val frameTime = bufferStartSeconds + frame.toFloat() / outputSampleRate
                                resampler.accept(mono, frameTime)
                            }
                            val mediaDuration = inputFormat.getLongOrNull(MediaFormat.KEY_DURATION)
                                ?.div(1_000_000f) ?: 0f
                            onProgress(bufferStartSeconds, mediaDuration)
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputFinished = true
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }

            if (window.isNotEmpty()) {
                val samples = FloatArray(window.size) { window[it] }
                onWindow(
                    samples,
                    windowStartSeconds.coerceAtLeast(0f),
                    samples.size.toFloat() / TARGET_SAMPLE_RATE,
                )
            }
            if (!emittedAnySamples) {
                throw IOException("No decodable audio was found in this video.")
            }
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException(error.message ?: "Couldn't decode this video's audio.", error)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun MediaFormat.getLongOrNull(key: String): Long? =
        if (containsKey(key)) runCatching { getLong(key) }.getOrNull() else null

    /** Streaming linear resampler; it keeps only the previous source frame. */
    private class LinearMonoResampler(
        private val onSample: (Float, Float) -> Unit,
    ) {
        private var previousValue: Float? = null
        private var previousTime = 0f
        private var nextOutputTime = 0f
        private val outputStep = 1f / TARGET_SAMPLE_RATE

        fun accept(value: Float, timeSeconds: Float) {
            val previous = previousValue
            if (previous == null) {
                previousValue = value
                previousTime = timeSeconds
                nextOutputTime = timeSeconds
                onSample(value, timeSeconds)
                nextOutputTime += outputStep
                return
            }

            if (timeSeconds <= previousTime) return
            while (nextOutputTime <= timeSeconds) {
                val fraction = ((nextOutputTime - previousTime) / (timeSeconds - previousTime))
                    .coerceIn(0f, 1f)
                val interpolated = previous + (value - previous) * fraction
                onSample(interpolated, nextOutputTime)
                nextOutputTime += outputStep
            }
            previousValue = value
            previousTime = timeSeconds
        }
    }
}
