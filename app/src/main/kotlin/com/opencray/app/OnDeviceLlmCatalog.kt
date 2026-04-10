package com.opencray.app

internal object LlmProviderModes {
  const val CLOUD: String = "cloud"
  const val ON_DEVICE_MODEL: String = "on_device_model"

  fun normalize(rawValue: String): String = when (rawValue.trim().lowercase()) {
    ON_DEVICE_MODEL -> ON_DEVICE_MODEL
    else -> CLOUD
  }
}

internal object OnDeviceLlmAccelerators {
  const val GPU: String = "gpu"
  const val CPU: String = "cpu"

  fun normalize(rawValue: String): String = when (rawValue.trim().lowercase()) {
    CPU -> CPU
    else -> GPU
  }
}

internal object OnDeviceLlmDownloadStates {
  const val NOT_DOWNLOADED: String = "not_downloaded"
  const val DOWNLOADING: String = "downloading"
  const val DOWNLOADED: String = "downloaded"
  const val VERIFYING: String = "verifying"
  const val READY: String = "ready"
  const val FAILED: String = "failed"

  fun normalize(rawValue: String): String = when (rawValue.trim().lowercase()) {
    DOWNLOADING -> DOWNLOADING
    DOWNLOADED -> DOWNLOADED
    VERIFYING -> VERIFYING
    READY -> READY
    FAILED -> FAILED
    else -> NOT_DOWNLOADED
  }
}

internal data class OnDeviceLlmCatalogEntry(
  val id: String,
  val title: String,
  val description: String,
  val runtimeId: String,
  val sizeLabel: String,
  val sourceUrl: String,
  val fileName: String,
  val versionTag: String,
  val sha256: String,
  val fileSizeBytes: Long,
  val recommendedBackend: String,
  val minimumFreeSpaceBytes: Long,
  val experimental: Boolean,
)

internal object OnDeviceLlmCatalog {
  const val GEMMA_4_E2B_IT: String = "gemma-4-e2b-it"
  const val GEMMA_4_E4B_IT: String = "gemma-4-e4b-it"
  const val DEFAULT_MODEL_ID: String = GEMMA_4_E2B_IT
  const val RUNTIME_ID_LITERT_LM: String = "litert_lm"

  private const val DEFAULT_VERSION_TAG: String = "v1"
  private const val EXTRA_FREE_SPACE_BYTES: Long = 1_500_000_000L

  private val modelEntries: List<OnDeviceLlmCatalogEntry> = listOf(
    OnDeviceLlmCatalogEntry(
      id = GEMMA_4_E2B_IT,
      title = "Gemma 4 E2B",
      description = "Instruction-tuned Gemma 4 E2B packaged for LiteRT-LM.",
      runtimeId = RUNTIME_ID_LITERT_LM,
      sizeLabel = "2.58 GB",
      sourceUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
      fileName = "gemma-4-E2B-it.litertlm",
      versionTag = DEFAULT_VERSION_TAG,
      sha256 = "ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42",
      fileSizeBytes = 2_583_085_056L,
      recommendedBackend = OnDeviceLlmAccelerators.GPU,
      minimumFreeSpaceBytes = 2_583_085_056L + EXTRA_FREE_SPACE_BYTES,
      experimental = false,
    ),
    OnDeviceLlmCatalogEntry(
      id = GEMMA_4_E4B_IT,
      title = "Gemma 4 E4B",
      description = "Instruction-tuned Gemma 4 E4B packaged for LiteRT-LM.",
      runtimeId = RUNTIME_ID_LITERT_LM,
      sizeLabel = "3.65 GB",
      sourceUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
      fileName = "gemma-4-E4B-it.litertlm",
      versionTag = DEFAULT_VERSION_TAG,
      sha256 = "f335f2bfd1b758dc6476db16c0f41854bd6237e2658d604cbe566bcefd00a7bc",
      fileSizeBytes = 3_654_467_584L,
      recommendedBackend = OnDeviceLlmAccelerators.GPU,
      minimumFreeSpaceBytes = 3_654_467_584L + EXTRA_FREE_SPACE_BYTES,
      experimental = false,
    ),
  )

  fun entries(): List<OnDeviceLlmCatalogEntry> = modelEntries

  fun entry(id: String): OnDeviceLlmCatalogEntry? =
    modelEntries.firstOrNull { entry -> entry.id == id.trim().lowercase() }

  fun hasModel(id: String): Boolean = entry(id) != null

  fun titleFor(id: String): String =
    entry(id)?.title ?: "On-device"
}
