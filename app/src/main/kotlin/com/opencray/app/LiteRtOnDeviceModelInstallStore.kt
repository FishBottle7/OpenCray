package com.opencray.app

import android.content.Context
import com.opencray.persistence.PersistenceJson
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import java.io.File
import kotlinx.serialization.Serializable

@Serializable
internal data class LiteRtOnDeviceModelInstallRecord(
  val modelId: String,
  val versionTag: String,
  val sourceUrl: String,
  val localFilePath: String,
  val stagingFilePath: String? = null,
  val fileSizeBytes: Long,
  val sha256: String,
  val installState: String = OnDeviceLlmDownloadStates.NOT_DOWNLOADED,
  val downloadedBytes: Long = 0L,
  val resumeBytes: Long = 0L,
  val downloadBytesPerSecond: Long = 0L,
  val etag: String? = null,
  val lastModified: String? = null,
  val acceptRanges: Boolean? = null,
  val lastError: String? = null,
  val installedAtEpochMs: Long? = null,
  val sha256Verified: Boolean = false,
) {
  fun sanitized(): LiteRtOnDeviceModelInstallRecord = copy(
    modelId = modelId.trim().lowercase(),
    versionTag = versionTag.trim(),
    sourceUrl = sourceUrl.trim(),
    localFilePath = localFilePath.trim(),
    stagingFilePath = stagingFilePath?.trim()?.takeIf(String::isNotBlank),
    fileSizeBytes = fileSizeBytes.coerceAtLeast(0L),
    sha256 = sha256.trim().lowercase(),
    installState = OnDeviceLlmDownloadStates.normalize(installState),
    downloadedBytes = downloadedBytes.coerceAtLeast(0L),
    resumeBytes = resumeBytes.coerceAtLeast(0L),
    downloadBytesPerSecond = downloadBytesPerSecond.coerceAtLeast(0L),
    etag = etag?.trim()?.takeIf(String::isNotBlank),
    lastModified = lastModified?.trim()?.takeIf(String::isNotBlank),
    acceptRanges = acceptRanges,
    lastError = lastError?.trim()?.takeIf(String::isNotBlank),
    installedAtEpochMs = installedAtEpochMs?.takeIf { epochMs -> epochMs >= 0L },
    sha256Verified = sha256Verified,
  )
}

@Serializable
private data class LiteRtOnDeviceModelInstallManifest(
  val records: List<LiteRtOnDeviceModelInstallRecord> = emptyList(),
)

internal open class LiteRtOnDeviceModelInstallStore(
  private val directory: File,
) {
  private val storage = DirectoryDurableTextStorage(directory)
  private val lock = Any()

  open fun load(modelId: String): LiteRtOnDeviceModelInstallRecord? {
    val normalizedModelId = modelId.trim().lowercase().takeIf(String::isNotBlank) ?: return null
    return loadAll().firstOrNull { record -> record.modelId == normalizedModelId }
  }

  open fun loadAll(): List<LiteRtOnDeviceModelInstallRecord> = synchronized(lock) {
    val raw = storage.readText(INDEX_FILE_NAME)
      ?.takeIf(String::isNotBlank)
      ?: return@synchronized emptyList()
    val decoded = runCatching {
      PersistenceJson.instance.decodeFromString(
        LiteRtOnDeviceModelInstallManifest.serializer(),
        raw,
      )
    }.getOrElse {
      return@synchronized emptyList()
    }
    decoded.records
      .map(LiteRtOnDeviceModelInstallRecord::sanitized)
      .distinctBy(LiteRtOnDeviceModelInstallRecord::modelId)
      .sortedBy(LiteRtOnDeviceModelInstallRecord::modelId)
  }

  open fun save(record: LiteRtOnDeviceModelInstallRecord) = synchronized(lock) {
    val normalized = record.sanitized()
    val updated = loadAll()
      .filterNot { existing -> existing.modelId == normalized.modelId }
      .plus(normalized)
      .sortedBy(LiteRtOnDeviceModelInstallRecord::modelId)
    persistLocked(updated)
  }

  open fun delete(modelId: String) = synchronized(lock) {
    val normalizedModelId = modelId.trim().lowercase().takeIf(String::isNotBlank) ?: return@synchronized
    val updated = loadAll().filterNot { record -> record.modelId == normalizedModelId }
    persistLocked(updated)
  }

  open fun clear(): Boolean = synchronized(lock) {
    storage.delete(INDEX_FILE_NAME)
  }

  private fun persistLocked(records: List<LiteRtOnDeviceModelInstallRecord>) {
    storage.writeText(
      INDEX_FILE_NAME,
      PersistenceJson.instance.encodeToString(
        LiteRtOnDeviceModelInstallManifest.serializer(),
        LiteRtOnDeviceModelInstallManifest(records = records),
      ),
    )
  }

  companion object {
    private const val INDEX_FILE_NAME: String = "install-state.json"
    private const val MODELS_DIRECTORY_NAME: String = "models"
    private const val LITERT_MODELS_DIRECTORY_NAME: String = "litert-lm"

    fun modelsRootForFilesDir(filesDir: File): File =
      File(File(filesDir, MODELS_DIRECTORY_NAME), LITERT_MODELS_DIRECTORY_NAME)

    fun fromContext(context: Context): LiteRtOnDeviceModelInstallStore =
      LiteRtOnDeviceModelInstallStore(
        directory = modelsRootForFilesDir(context.applicationContext.filesDir),
      )
  }
}

internal open class InMemoryLiteRtOnDeviceModelInstallStore(
  initialRecords: List<LiteRtOnDeviceModelInstallRecord> = emptyList(),
) : LiteRtOnDeviceModelInstallStore(directory = File(".")) {
  private val lock = Any()
  private var records: MutableList<LiteRtOnDeviceModelInstallRecord> = initialRecords
    .map(LiteRtOnDeviceModelInstallRecord::sanitized)
    .sortedBy(LiteRtOnDeviceModelInstallRecord::modelId)
    .toMutableList()

  override fun load(modelId: String): LiteRtOnDeviceModelInstallRecord? {
    val normalizedModelId = modelId.trim().lowercase().takeIf(String::isNotBlank) ?: return null
    return synchronized(lock) {
      records.firstOrNull { record -> record.modelId == normalizedModelId }
    }
  }

  override fun loadAll(): List<LiteRtOnDeviceModelInstallRecord> = synchronized(lock) {
    records.toList()
  }

  override fun save(record: LiteRtOnDeviceModelInstallRecord) {
    val normalized = record.sanitized()
    synchronized(lock) {
      records = records
        .filterNot { existing -> existing.modelId == normalized.modelId }
        .plus(normalized)
        .sortedBy(LiteRtOnDeviceModelInstallRecord::modelId)
        .toMutableList()
    }
  }

  override fun delete(modelId: String) {
    val normalizedModelId = modelId.trim().lowercase().takeIf(String::isNotBlank) ?: return
    synchronized(lock) {
      records = records
        .filterNot { record -> record.modelId == normalizedModelId }
        .toMutableList()
    }
  }

  override fun clear(): Boolean = synchronized(lock) {
    return@synchronized if (records.isEmpty()) {
      false
    } else {
      records = mutableListOf()
      true
    }
  }
}
