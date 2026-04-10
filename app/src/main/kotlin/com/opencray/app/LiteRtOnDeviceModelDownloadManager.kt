package com.opencray.app

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

internal class LiteRtOnDeviceModelDownloadManager(
  private val filesDir: File,
  private val installStore: LiteRtOnDeviceModelInstallStore,
  private val workScheduler: LiteRtOnDeviceModelDownloadWorkScheduler = NoOpLiteRtOnDeviceModelDownloadWorkScheduler,
  private val resolveCatalogEntry: (String) -> OnDeviceLlmCatalogEntry? = OnDeviceLlmCatalog::entry,
  private val integrityStrategy: OnDeviceModelIntegrityStrategy =
    SourceHashPreferredOnDeviceModelIntegrityStrategy,
  private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
  fun download(modelId: String) {
    val entry = requireCatalogEntry(modelId)
    val modelRoot = modelsRoot()
    Files.createDirectories(modelRoot)
    val destination = modelPath(entry)
    val staging = stagingPath(entry)
    val existing = installStore.load(entry.id)
    if (reconcileExistingReadyFile(entry, destination, existing)) {
      return
    }
    if (!hasRequiredFreeSpace(modelRoot, entry.minimumFreeSpaceBytes, existingFileSize(staging))) {
      installStore.save(
        buildOnDeviceFailedInstallRecord(
          entry = entry,
          localFilePath = destination.toString(),
          stagingFilePath = staging.toString(),
          downloadedBytes = existingFileSize(staging),
          lastError = "Not enough free space to download ${entry.title}.",
          etag = existing?.etag,
          lastModified = existing?.lastModified,
          acceptRanges = existing?.acceptRanges,
        ),
      )
      return
    }
    val resumeBytes = existingFileSize(staging)
    installStore.save(
      buildOnDeviceInstallRecord(
        entry = entry,
        localFilePath = destination.toString(),
        stagingFilePath = staging.toString(),
        installState = OnDeviceLlmDownloadStates.DOWNLOADING,
        downloadedBytes = resumeBytes,
        resumeBytes = resumeBytes,
        etag = existing?.etag,
        lastModified = existing?.lastModified,
        acceptRanges = existing?.acceptRanges,
      ),
    )
    workScheduler.enqueue(entry.id)
  }

  fun cancel(modelId: String) {
    val entry = requireCatalogEntry(modelId)
    workScheduler.cancel(entry.id)
    val destination = modelPath(entry)
    val staging = stagingPath(entry)
    val existing = installStore.load(entry.id)
    val resumeBytes = existingFileSize(staging)
    if (resumeBytes <= 0L) {
      installStore.delete(entry.id)
      return
    }
    installStore.save(
      buildOnDeviceInstallRecord(
        entry = entry,
        localFilePath = destination.toString(),
        stagingFilePath = staging.toString(),
        installState = OnDeviceLlmDownloadStates.NOT_DOWNLOADED,
        downloadedBytes = 0L,
        resumeBytes = resumeBytes,
        etag = existing?.etag,
        lastModified = existing?.lastModified,
        acceptRanges = existing?.acceptRanges,
      ),
    )
  }

  fun delete(modelId: String) {
    val entry = requireCatalogEntry(modelId)
    workScheduler.cancel(entry.id)
    deleteIfExists(modelPath(entry))
    deleteIfExists(stagingPath(entry))
    installStore.delete(entry.id)
  }

  private fun reconcileExistingReadyFile(
    entry: OnDeviceLlmCatalogEntry,
    destination: Path,
    existing: LiteRtOnDeviceModelInstallRecord?,
  ): Boolean {
    if (!destination.exists() || !destination.isRegularFile()) {
      return false
    }
    val actualSha256 = runCatching {
      val digest = java.security.MessageDigest.getInstance("SHA-256")
      Files.newInputStream(destination).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
          val read = input.read(buffer)
          if (read < 0) {
            break
          }
          digest.update(buffer, 0, read)
        }
      }
      digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }.getOrElse {
      deleteIfExists(destination)
      return false
    }
    val expectedSha256 = integrityStrategy.resolveExpectedSha256(
      entry = entry,
      installRecord = existing,
      sourceLinkedEtag = null,
    )
    return if (actualSha256.equals(expectedSha256, ignoreCase = true)) {
      installStore.save(
        buildOnDeviceInstallRecord(
          entry = entry,
          localFilePath = destination.toString(),
          stagingFilePath = null,
          installState = OnDeviceLlmDownloadStates.READY,
          downloadedBytes = existingFileSize(destination),
          resumeBytes = 0L,
          installedAtEpochMs = existing?.installedAtEpochMs ?: nowEpochMs(),
          sha256Verified = true,
          resolvedSha256 = expectedSha256,
          etag = existing?.etag,
          lastModified = existing?.lastModified,
          acceptRanges = existing?.acceptRanges,
        ),
      )
      true
    } else {
      deleteIfExists(destination)
      false
    }
  }

  private fun requireCatalogEntry(modelId: String): OnDeviceLlmCatalogEntry =
    requireNotNull(resolveCatalogEntry(modelId)) {
      "Unsupported on-device model '$modelId'."
    }

  private fun modelsRoot(): Path =
    LiteRtOnDeviceModelInstallStore.modelsRootForFilesDir(filesDir).toPath().toAbsolutePath().normalize()

  private fun modelPath(entry: OnDeviceLlmCatalogEntry): Path =
    modelsRoot().resolve(entry.fileName).normalize()

  private fun stagingPath(entry: OnDeviceLlmCatalogEntry): Path =
    modelsRoot().resolve("${entry.fileName}.downloading").normalize()

  private fun existingFileSize(path: Path): Long = runCatching {
    if (path.exists() && path.isRegularFile()) {
      Files.size(path)
    } else {
      0L
    }
  }.getOrDefault(0L)

  private fun hasRequiredFreeSpace(
    root: Path,
    minimumFreeSpaceBytes: Long,
    existingBytes: Long,
  ): Boolean = root.toFile().usableSpace.coerceAtLeast(0L) + existingBytes.coerceAtLeast(0L) >=
    minimumFreeSpaceBytes

  private fun deleteIfExists(path: Path) {
    runCatching { Files.deleteIfExists(path) }
  }

  companion object {
    @Volatile
    private var instance: LiteRtOnDeviceModelDownloadManager? = null

    fun fromContext(context: Context): LiteRtOnDeviceModelDownloadManager =
      instance ?: synchronized(this) {
        instance ?: LiteRtOnDeviceModelDownloadManager(
          filesDir = context.applicationContext.filesDir,
          installStore = LiteRtOnDeviceModelInstallStore.fromContext(context.applicationContext),
          workScheduler = WorkManagerLiteRtOnDeviceModelDownloadWorkScheduler
            .fromContext(context.applicationContext),
        ).also { created ->
          instance = created
        }
      }
  }
}

internal object NoOpLiteRtOnDeviceModelDownloadWorkScheduler :
  LiteRtOnDeviceModelDownloadWorkScheduler {
  override fun enqueue(modelId: String) = Unit

  override fun cancel(modelId: String) = Unit
}
