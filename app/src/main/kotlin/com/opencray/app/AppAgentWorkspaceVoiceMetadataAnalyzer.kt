package com.opencray.app

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class AppAgentWorkspaceVoiceMetadata(
  val durationMs: Long? = null,
  val waveformBars: List<Int> = emptyList(),
  val transcriptText: String? = null,
)

internal fun interface AppAgentWorkspaceVoiceMetadataAnalyzer {
  fun analyze(
    path: Path,
    mimeType: String?,
  ): AppAgentWorkspaceVoiceMetadata?
}

internal object DefaultAppAgentWorkspaceVoiceMetadataAnalyzer : AppAgentWorkspaceVoiceMetadataAnalyzer {
  override fun analyze(
    path: Path,
    mimeType: String?,
  ): AppAgentWorkspaceVoiceMetadata? {
    val durationMs = AppAgentWorkspaceVoiceMetadataSupport.readDurationMs(path)
    val waveformBars = AppAgentWorkspaceVoiceMetadataSupport.readWaveformBars(
      path = path,
      durationMs = durationMs,
    )
    if (durationMs == null && waveformBars.isEmpty()) {
      return null
    }
    return AppAgentWorkspaceVoiceMetadata(
      durationMs = durationMs,
      waveformBars = waveformBars,
    )
  }
}

internal object AppAgentWorkspaceVoiceMetadataSupport {
  fun readDurationMs(path: Path): Long? = runCatching {
    val retriever = MediaMetadataRetriever()
    try {
      retriever.setDataSource(path.toString())
      retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        ?.trim()
        ?.toLongOrNull()
        ?.takeIf { durationMs -> durationMs >= 0L }
    } finally {
      runCatching {
        retriever.release()
      }
    }
  }.getOrNull()

  fun readWaveformBars(
    path: Path,
    durationMs: Long?,
  ): List<Int> = runCatching {
    if (durationMs == null || durationMs <= 0L) {
      return@runCatching emptyList()
    }
    decodeWaveformBars(
      path = path,
      totalDurationUs = durationMs * 1_000L,
    )
  }.getOrDefault(emptyList())

  private fun decodeWaveformBars(
    path: Path,
    totalDurationUs: Long,
  ): List<Int> {
    require(totalDurationUs > 0L) { "Voice waveform duration must be positive." }
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    try {
      extractor.setDataSource(path.toString())
      val trackIndex = findAudioTrackIndex(extractor)
      if (trackIndex < 0) {
        return emptyList()
      }
      extractor.selectTrack(trackIndex)
      val inputFormat = extractor.getTrackFormat(trackIndex)
      val mimeType = inputFormat.getString(MediaFormat.KEY_MIME)
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: return emptyList()
      val accumulator = WaveformAccumulator(
        totalDurationUs = totalDurationUs,
        barCount = DEFAULT_WAVEFORM_BAR_COUNT,
      )
      codec = MediaCodec.createDecoderByType(mimeType)
      codec.configure(inputFormat, null, null, 0)
      codec.start()
      val bufferInfo = MediaCodec.BufferInfo()
      var outputFormat = codec.outputFormat
      var inputDone = false
      var outputDone = false
      while (!outputDone) {
        if (!inputDone) {
          val inputBufferIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
          if (inputBufferIndex >= 0) {
            val inputBuffer = codec.getInputBuffer(inputBufferIndex)
              ?: return emptyList()
            val sampleSize = extractor.readSampleData(inputBuffer, 0)
            if (sampleSize < 0) {
              codec.queueInputBuffer(
                inputBufferIndex,
                0,
                0,
                0L,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
              )
              inputDone = true
            } else {
              codec.queueInputBuffer(
                inputBufferIndex,
                0,
                sampleSize,
                maxOf(0L, extractor.sampleTime),
                0,
              )
              extractor.advance()
            }
          }
        }
        when (val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)) {
          MediaCodec.INFO_TRY_AGAIN_LATER -> {
            if (inputDone) {
              continue
            }
          }

          MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
            outputFormat = codec.outputFormat
          }

          MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit

          else -> {
            if (outputBufferIndex >= 0) {
              if (bufferInfo.size > 0) {
                codec.getOutputBuffer(outputBufferIndex)?.let { outputBuffer ->
                  accumulator.addBuffer(
                    buffer = outputBuffer,
                    offset = bufferInfo.offset,
                    size = bufferInfo.size,
                    presentationTimeUs = maxOf(0L, bufferInfo.presentationTimeUs),
                    outputFormat = outputFormat,
                  )
                }
              }
              codec.releaseOutputBuffer(outputBufferIndex, false)
              if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                outputDone = true
              }
            }
          }
        }
      }
      return accumulator.build()
    } finally {
      runCatching {
        codec?.stop()
      }
      runCatching {
        codec?.release()
      }
      runCatching {
        extractor.release()
      }
    }
  }

  private fun findAudioTrackIndex(extractor: MediaExtractor): Int {
    for (index in 0 until extractor.trackCount) {
      val format = extractor.getTrackFormat(index)
      val mimeType = format.getString(MediaFormat.KEY_MIME).orEmpty()
      if (mimeType.startsWith("audio/")) {
        return index
      }
    }
    return -1
  }

  private class WaveformAccumulator(
    private val totalDurationUs: Long,
    private val barCount: Int,
  ) {
    private val peaks = FloatArray(barCount)

    fun addBuffer(
      buffer: ByteBuffer,
      offset: Int,
      size: Int,
      presentationTimeUs: Long,
      outputFormat: MediaFormat,
    ) {
      val channelCount = outputFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)
        .coerceAtLeast(1)
      val sampleRate = outputFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 0)
      if (sampleRate <= 0 || size <= 0) {
        return
      }
      val pcmEncoding = outputFormat.getIntegerOrDefault(
        MediaFormat.KEY_PCM_ENCODING,
        AudioFormat.ENCODING_PCM_16BIT,
      )
      val bytesPerSample = bytesPerSampleFor(pcmEncoding)
      val bytesPerFrame = bytesPerSample * channelCount
      if (bytesPerFrame <= 0) {
        return
      }
      val duplicate = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
      duplicate.position(offset)
      duplicate.limit(offset + size)
      val frameCount = size / bytesPerFrame
      if (frameCount <= 0) {
        return
      }
      for (frameIndex in 0 until frameCount) {
        val frameTimestampUs = presentationTimeUs +
          (frameIndex.toLong() * 1_000_000L / sampleRate.toLong())
        var amplitude = 0f
        repeat(channelCount) {
          amplitude = maxOf(
            amplitude,
            sampleAmplitude(
              buffer = duplicate,
              pcmEncoding = pcmEncoding,
            ),
          )
        }
        val barIndex = (((frameTimestampUs.coerceIn(0L, totalDurationUs) * barCount) / totalDurationUs)
          .toInt())
          .coerceIn(0, barCount - 1)
        peaks[barIndex] = maxOf(peaks[barIndex], amplitude)
      }
    }

    fun build(): List<Int> {
      val maxPeak = peaks.maxOrNull() ?: 0f
      if (maxPeak <= 0f) {
        return List(barCount) { MIN_SILENT_BAR_LEVEL }
      }
      return peaks.map { peak ->
        ((peak / maxPeak) * 100f).roundToInt().coerceIn(0, 100)
      }
    }

    private fun sampleAmplitude(
      buffer: ByteBuffer,
      pcmEncoding: Int,
    ): Float = when (pcmEncoding) {
      AudioFormat.ENCODING_PCM_8BIT -> abs((buffer.get().toInt() - 128) / 128f)
      AudioFormat.ENCODING_PCM_FLOAT -> abs(buffer.float).coerceIn(0f, 1f)
      else -> abs(buffer.short.toInt() / 32767f).coerceIn(0f, 1f)
    }
  }

  private fun bytesPerSampleFor(pcmEncoding: Int): Int = when (pcmEncoding) {
    AudioFormat.ENCODING_PCM_8BIT -> 1
    AudioFormat.ENCODING_PCM_FLOAT -> 4
    else -> 2
  }

  private fun MediaFormat.getIntegerOrDefault(
    key: String,
    defaultValue: Int,
  ): Int = if (containsKey(key)) {
    runCatching {
      getInteger(key)
    }.getOrDefault(defaultValue)
  } else {
    defaultValue
  }

  private const val CODEC_TIMEOUT_US: Long = 10_000L
  private const val DEFAULT_WAVEFORM_BAR_COUNT: Int = 48
  private const val MIN_SILENT_BAR_LEVEL: Int = 8
}
