package com.opencray.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.math.absoluteValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencray.app.R

internal interface LiteRtOnDeviceModelDownloadWorkScheduler {
  fun enqueue(modelId: String)

  fun cancel(modelId: String)
}

internal class WorkManagerLiteRtOnDeviceModelDownloadWorkScheduler(
  private val workManager: WorkManager,
) : LiteRtOnDeviceModelDownloadWorkScheduler {
  override fun enqueue(modelId: String) {
    val normalizedModelId = normalizeOnDeviceModelId(modelId) ?: return
    val request = OneTimeWorkRequestBuilder<LiteRtOnDeviceModelDownloadWorker>()
      .setInputData(
        Data.Builder()
          .putString(WORK_DATA_MODEL_ID, normalizedModelId)
          .build(),
      )
      .setConstraints(
        Constraints.Builder()
          .setRequiredNetworkType(NetworkType.CONNECTED)
          .build(),
      )
      .setBackoffCriteria(
        BackoffPolicy.EXPONENTIAL,
        INITIAL_BACKOFF_MILLIS,
        TimeUnit.MILLISECONDS,
      )
      .addTag(modelDownloadWorkName(normalizedModelId))
      .build()
    workManager.enqueueUniqueWork(
      modelDownloadWorkName(normalizedModelId),
      ExistingWorkPolicy.KEEP,
      request,
    )
  }

  override fun cancel(modelId: String) {
    val normalizedModelId = normalizeOnDeviceModelId(modelId) ?: return
    workManager.cancelUniqueWork(modelDownloadWorkName(normalizedModelId))
  }

  companion object {
    private const val INITIAL_BACKOFF_MILLIS: Long = 10_000L

    fun fromContext(context: Context): WorkManagerLiteRtOnDeviceModelDownloadWorkScheduler =
      WorkManagerLiteRtOnDeviceModelDownloadWorkScheduler(
        workManager = WorkManager.getInstance(context.applicationContext),
      )
  }
}

internal class LiteRtOnDeviceModelDownloadWorker(
  appContext: Context,
  workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
  private val installStore = LiteRtOnDeviceModelInstallStore.fromContext(appContext)
  private val runner = LiteRtOnDeviceModelDownloadRunner.fromContext(appContext)
  private val notificationFactory = LiteRtOnDeviceModelDownloadNotificationFactory(appContext)
  private val notificationManager =
    appContext.getSystemService(NotificationManager::class.java)

  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    val modelId = normalizeOnDeviceModelId(inputData.getString(WORK_DATA_MODEL_ID))
      ?: return@withContext Result.failure()
    RuntimeNotificationChannelRegistry.ensureRegistered(applicationContext)
    installStore.load(modelId)?.let { record ->
      setForeground(initialForegroundInfo(record))
    }
    return@withContext when (
      runner.run(
        modelId = modelId,
        stopRequested = ::isStopped,
        onRecordSaved = { record ->
          notificationManager?.notify(
            modelDownloadNotificationId(record.modelId),
            notificationFactory.build(record),
          )
        },
      )
    ) {
      LiteRtOnDeviceModelDownloadRunResult.SUCCESS -> Result.success()
      LiteRtOnDeviceModelDownloadRunResult.CANCELLED -> Result.success()
      LiteRtOnDeviceModelDownloadRunResult.FAILURE -> Result.failure()
      LiteRtOnDeviceModelDownloadRunResult.RETRY -> {
        if (runAttemptCount >= MAX_AUTO_RETRY_ATTEMPTS - 1) {
          markRetryExhausted(modelId)
          Result.failure()
        } else {
          Result.retry()
        }
      }
    }
  }

  private fun initialForegroundInfo(record: LiteRtOnDeviceModelInstallRecord): ForegroundInfo =
    ForegroundInfo(
      modelDownloadNotificationId(record.modelId),
      notificationFactory.build(record),
    )

  private fun markRetryExhausted(modelId: String) {
    val existing = installStore.load(modelId) ?: return
    val entry = OnDeviceLlmCatalog.entry(modelId) ?: return
    installStore.save(
      buildOnDeviceFailedInstallRecord(
        entry = entry,
        localFilePath = existing.localFilePath,
        stagingFilePath = existing.stagingFilePath,
        downloadedBytes = currentResumeBytes(existing.stagingFilePath),
        lastError = existing.lastError ?: "Model download failed after repeated retries.",
        etag = existing.etag,
        lastModified = existing.lastModified,
        acceptRanges = existing.acceptRanges,
      ),
    )
  }

  private fun currentResumeBytes(stagingFilePath: String?): Long {
    val path = stagingFilePath
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let(::File)
      ?.toPath()
      ?: return 0L
    return runCatching {
      if (path.exists() && path.isRegularFile()) {
        Files.size(path)
      } else {
        0L
      }
    }.getOrDefault(0L)
  }

  companion object {
    private const val MAX_AUTO_RETRY_ATTEMPTS: Int = 10
  }
}

internal enum class LiteRtOnDeviceModelDownloadRunResult {
  SUCCESS,
  RETRY,
  FAILURE,
  CANCELLED,
}

internal class LiteRtOnDeviceModelDownloadRunner(
  private val filesDir: File,
  private val installStore: LiteRtOnDeviceModelInstallStore,
  private val resolveCatalogEntry: (String) -> OnDeviceLlmCatalogEntry? = OnDeviceLlmCatalog::entry,
  private val openConnection: (String) -> HttpURLConnection = ::openDefaultOnDeviceDownloadConnection,
  private val probeSourceIntegrityHints: (String) -> OnDeviceModelSourceIntegrityHints? =
    ::probeOnDeviceModelSourceIntegrityHints,
  private val integrityStrategy: OnDeviceModelIntegrityStrategy =
    SourceHashPreferredOnDeviceModelIntegrityStrategy,
  private val createDigest: () -> MessageDigest = { MessageDigest.getInstance("SHA-256") },
  private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
  fun run(
    modelId: String,
    stopRequested: () -> Boolean = { false },
    onRecordSaved: (LiteRtOnDeviceModelInstallRecord) -> Unit = {},
  ): LiteRtOnDeviceModelDownloadRunResult {
    val entry = requireCatalogEntry(modelId)
    val modelRoot = modelsRoot()
    Files.createDirectories(modelRoot)
    val destination = modelPath(entry)
    val initialRecord = installStore.load(entry.id)
    if (reconcileExistingReadyFile(entry, destination, initialRecord, onRecordSaved)) {
      return LiteRtOnDeviceModelDownloadRunResult.SUCCESS
    }
    val staging = stagingPath(entry, initialRecord)
    Files.createDirectories(requireNotNull(staging.parent))
    var resumeBytes = existingFileSize(staging)
    if (resumeBytes > entry.fileSizeBytes) {
      deleteIfExists(staging)
      resumeBytes = 0L
    }
    if (!hasRequiredFreeSpace(modelRoot, entry.minimumFreeSpaceBytes, resumeBytes)) {
      saveRecord(
        buildOnDeviceFailedInstallRecord(
          entry = entry,
          localFilePath = destination.toString(),
          stagingFilePath = staging.toString(),
          downloadedBytes = resumeBytes,
          lastError = "Not enough free space to download ${entry.title}.",
          etag = initialRecord?.etag,
          lastModified = initialRecord?.lastModified,
          acceptRanges = initialRecord?.acceptRanges,
        ),
        onRecordSaved,
      )
      return LiteRtOnDeviceModelDownloadRunResult.FAILURE
    }
    var connection: HttpURLConnection? = null
    var effectiveDownloadedBytes = resumeBytes
    var effectiveEtag = initialRecord?.etag
    var effectiveLastModified = initialRecord?.lastModified
    var effectiveAcceptRanges = initialRecord?.acceptRanges
    val sourceIntegrityHints = runCatching {
      probeSourceIntegrityHints(entry.sourceUrl)
    }.getOrNull()
    var effectiveExpectedSha256 = integrityStrategy.resolveExpectedSha256(
      entry = entry,
      installRecord = initialRecord,
      sourceLinkedEtag = sourceIntegrityHints?.linkedEtag,
    )
    try {
      var requestRange = resumeBytes > 0L
      while (true) {
        connection = openConfiguredConnection(
          entry = entry,
          installRecord = initialRecord,
          resumeBytes = resumeBytes,
          requestRange = requestRange,
        )
        connection.connect()
        val responseCode = connection.responseCode
        if (responseCode == HTTP_RANGE_NOT_SATISFIABLE && requestRange) {
          connection.disconnect()
          deleteIfExists(staging)
          resumeBytes = 0L
          requestRange = false
          continue
        }
        if (responseCode == HttpURLConnection.HTTP_PARTIAL && requestRange && resumeBytes > 0L) {
          if (!integrityStrategy.isResumeContentRangeValid(
              contentRange = connection.getHeaderField("Content-Range"),
              expectedResumeBytes = resumeBytes,
            )
          ) {
            connection.disconnect()
            deleteIfExists(staging)
            resumeBytes = 0L
            requestRange = false
            continue
          }
        }
        break
      }
      val responseCode = connection.responseCode
      if (responseCode !in 200..299) {
        return handleHttpFailure(
          entry = entry,
          destination = destination,
          staging = staging,
          responseCode = responseCode,
          downloadedBytes = effectiveDownloadedBytes,
          onRecordSaved = onRecordSaved,
        )
      }
      effectiveEtag = sanitizeHeaderValue(connection.getHeaderField("ETag")) ?: effectiveEtag
      effectiveLastModified =
        sanitizeHeaderValue(connection.getHeaderField("Last-Modified")) ?: effectiveLastModified
      effectiveExpectedSha256 = integrityStrategy.resolveExpectedSha256(
        entry = entry,
        installRecord = initialRecord,
        sourceLinkedEtag = sourceIntegrityHints?.linkedEtag,
      )
      val resumeAccepted = resumeBytes > 0L && responseCode == HttpURLConnection.HTTP_PARTIAL
      effectiveAcceptRanges = parseAcceptRanges(connection.getHeaderField("Accept-Ranges"))
        ?: if (resumeAccepted) {
          true
        } else if (resumeBytes > 0L) {
          false
        } else {
          effectiveAcceptRanges
        }
      if (!resumeAccepted && resumeBytes > 0L) {
        deleteIfExists(staging)
        resumeBytes = 0L
      }
      val digest = createDigest()
      if (resumeAccepted && resumeBytes > 0L) {
        updateDigestFromFile(staging, digest)
      }
      effectiveDownloadedBytes = resumeBytes
      val downloadStartedAtEpochMs = nowEpochMs()
      var downloadedThisAttempt = 0L
      var lastPersistedBytes = effectiveDownloadedBytes
      saveRecord(
        buildOnDeviceInstallRecord(
          entry = entry,
          localFilePath = destination.toString(),
          stagingFilePath = staging.toString(),
          installState = OnDeviceLlmDownloadStates.DOWNLOADING,
          downloadedBytes = effectiveDownloadedBytes,
          resumeBytes = effectiveDownloadedBytes,
          downloadBytesPerSecond = 0L,
          resolvedSha256 = effectiveExpectedSha256,
          etag = effectiveEtag,
          lastModified = effectiveLastModified,
          acceptRanges = effectiveAcceptRanges,
        ),
        onRecordSaved,
      )
      connection.inputStream.use { input ->
        Files.newOutputStream(
          staging,
          StandardOpenOption.CREATE,
          StandardOpenOption.WRITE,
          if (resumeAccepted && effectiveDownloadedBytes > 0L) {
            StandardOpenOption.APPEND
          } else {
            StandardOpenOption.TRUNCATE_EXISTING
          },
        ).use { output ->
          val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
          while (true) {
            if (stopRequested()) {
              persistCancelledRecord(
                entry = entry,
                destination = destination,
                staging = staging,
                downloadedBytes = effectiveDownloadedBytes,
                sourceLinkedEtag = sourceIntegrityHints?.linkedEtag,
                etag = effectiveEtag,
                lastModified = effectiveLastModified,
                acceptRanges = effectiveAcceptRanges,
                onRecordSaved = onRecordSaved,
              )
              return LiteRtOnDeviceModelDownloadRunResult.CANCELLED
            }
            val read = input.read(buffer)
            if (read < 0) {
              break
            }
            output.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            effectiveDownloadedBytes += read
            downloadedThisAttempt += read
            val downloadBytesPerSecond = calculateDownloadBytesPerSecond(
              downloadedBytes = downloadedThisAttempt,
              downloadStartedAtEpochMs = downloadStartedAtEpochMs,
              currentEpochMs = nowEpochMs(),
            )
            if (effectiveDownloadedBytes - lastPersistedBytes >= PROGRESS_PERSIST_GRANULARITY_BYTES) {
              lastPersistedBytes = effectiveDownloadedBytes
              saveRecord(
                buildOnDeviceInstallRecord(
                  entry = entry,
                  localFilePath = destination.toString(),
                  stagingFilePath = staging.toString(),
                  installState = OnDeviceLlmDownloadStates.DOWNLOADING,
                  downloadedBytes = effectiveDownloadedBytes,
                  resumeBytes = effectiveDownloadedBytes,
                  downloadBytesPerSecond = downloadBytesPerSecond,
                  resolvedSha256 = effectiveExpectedSha256,
                  etag = effectiveEtag,
                  lastModified = effectiveLastModified,
                  acceptRanges = effectiveAcceptRanges,
                ),
                onRecordSaved,
              )
            }
          }
          output.flush()
        }
      }
      if (stopRequested()) {
        persistCancelledRecord(
          entry = entry,
          destination = destination,
          staging = staging,
          downloadedBytes = effectiveDownloadedBytes,
          sourceLinkedEtag = sourceIntegrityHints?.linkedEtag,
          etag = effectiveEtag,
          lastModified = effectiveLastModified,
          acceptRanges = effectiveAcceptRanges,
          onRecordSaved = onRecordSaved,
        )
        return LiteRtOnDeviceModelDownloadRunResult.CANCELLED
      }
      saveRecord(
        buildOnDeviceInstallRecord(
          entry = entry,
          localFilePath = destination.toString(),
          stagingFilePath = staging.toString(),
          installState = OnDeviceLlmDownloadStates.VERIFYING,
          downloadedBytes = effectiveDownloadedBytes,
          resumeBytes = effectiveDownloadedBytes,
          resolvedSha256 = effectiveExpectedSha256,
          etag = effectiveEtag,
          lastModified = effectiveLastModified,
          acceptRanges = effectiveAcceptRanges,
        ),
        onRecordSaved,
      )
      val actualSha256 = digest.digest().toHex()
      if (!actualSha256.equals(effectiveExpectedSha256, ignoreCase = true)) {
        deleteIfExists(staging)
        saveRecord(
          buildOnDeviceFailedInstallRecord(
            entry = entry,
            localFilePath = destination.toString(),
            stagingFilePath = null,
            downloadedBytes = effectiveDownloadedBytes,
            lastError = "SHA-256 verification failed for ${entry.title}.",
            resolvedSha256 = effectiveExpectedSha256,
            etag = effectiveEtag,
            lastModified = effectiveLastModified,
            acceptRanges = effectiveAcceptRanges,
          ),
          onRecordSaved,
        )
        return LiteRtOnDeviceModelDownloadRunResult.FAILURE
      }
      moveIntoPlace(staging, destination)
      saveRecord(
        buildOnDeviceInstallRecord(
          entry = entry,
          localFilePath = destination.toString(),
          stagingFilePath = null,
          installState = OnDeviceLlmDownloadStates.READY,
          downloadedBytes = entry.fileSizeBytes,
          resumeBytes = 0L,
          installedAtEpochMs = nowEpochMs(),
          sha256Verified = true,
          resolvedSha256 = effectiveExpectedSha256,
          etag = effectiveEtag,
          lastModified = effectiveLastModified,
          acceptRanges = effectiveAcceptRanges,
        ),
        onRecordSaved,
      )
      return LiteRtOnDeviceModelDownloadRunResult.SUCCESS
    } catch (throwable: Throwable) {
      if (stopRequested()) {
        persistCancelledRecord(
          entry = entry,
          destination = destination,
          staging = staging,
          downloadedBytes = existingFileSize(staging).coerceAtLeast(effectiveDownloadedBytes),
          sourceLinkedEtag = sourceIntegrityHints?.linkedEtag,
          etag = effectiveEtag,
          lastModified = effectiveLastModified,
          acceptRanges = effectiveAcceptRanges,
          onRecordSaved = onRecordSaved,
        )
        return LiteRtOnDeviceModelDownloadRunResult.CANCELLED
      }
      val currentDownloadedBytes = existingFileSize(staging).coerceAtLeast(effectiveDownloadedBytes)
      return if (isRetryableDownloadThrowable(throwable)) {
        saveRecord(
          buildOnDeviceInstallRecord(
            entry = entry,
            localFilePath = destination.toString(),
            stagingFilePath = staging.toString(),
            installState = OnDeviceLlmDownloadStates.DOWNLOADING,
            downloadedBytes = currentDownloadedBytes,
            resumeBytes = currentDownloadedBytes,
            resolvedSha256 = effectiveExpectedSha256,
            etag = effectiveEtag,
            lastModified = effectiveLastModified,
            acceptRanges = effectiveAcceptRanges,
            lastError = throwable.message ?: throwable::class.java.simpleName,
          ),
          onRecordSaved,
        )
        LiteRtOnDeviceModelDownloadRunResult.RETRY
      } else {
        deleteIfExists(staging)
        saveRecord(
          buildOnDeviceFailedInstallRecord(
            entry = entry,
            localFilePath = destination.toString(),
            stagingFilePath = null,
            downloadedBytes = currentDownloadedBytes,
            lastError = throwable.message ?: throwable::class.java.simpleName,
            resolvedSha256 = effectiveExpectedSha256,
            etag = effectiveEtag,
            lastModified = effectiveLastModified,
            acceptRanges = effectiveAcceptRanges,
          ),
          onRecordSaved,
        )
        LiteRtOnDeviceModelDownloadRunResult.FAILURE
      }
    } finally {
      connection?.disconnect()
    }
  }

  private fun handleHttpFailure(
    entry: OnDeviceLlmCatalogEntry,
    destination: Path,
    staging: Path,
    responseCode: Int,
    downloadedBytes: Long,
    onRecordSaved: (LiteRtOnDeviceModelInstallRecord) -> Unit,
  ): LiteRtOnDeviceModelDownloadRunResult {
    val message = "Model download failed with HTTP $responseCode."
    return if (isRetryableHttpStatus(responseCode)) {
      saveRecord(
        buildOnDeviceInstallRecord(
          entry = entry,
          localFilePath = destination.toString(),
          stagingFilePath = staging.toString(),
          installState = OnDeviceLlmDownloadStates.DOWNLOADING,
          downloadedBytes = downloadedBytes,
          resumeBytes = downloadedBytes,
          lastError = message,
        ),
        onRecordSaved,
      )
      LiteRtOnDeviceModelDownloadRunResult.RETRY
    } else {
      deleteIfExists(staging)
      saveRecord(
        buildOnDeviceFailedInstallRecord(
          entry = entry,
          localFilePath = destination.toString(),
          stagingFilePath = null,
          downloadedBytes = downloadedBytes,
          lastError = message,
        ),
        onRecordSaved,
      )
      LiteRtOnDeviceModelDownloadRunResult.FAILURE
    }
  }

  private fun persistCancelledRecord(
    entry: OnDeviceLlmCatalogEntry,
    destination: Path,
    staging: Path,
    downloadedBytes: Long,
    sourceLinkedEtag: String?,
    etag: String?,
    lastModified: String?,
    acceptRanges: Boolean?,
    onRecordSaved: (LiteRtOnDeviceModelInstallRecord) -> Unit,
  ) {
    val resumeBytes = existingFileSize(staging).coerceAtLeast(downloadedBytes)
    if (resumeBytes <= 0L) {
      installStore.delete(entry.id)
      return
    }
    saveRecord(
      buildOnDeviceInstallRecord(
        entry = entry,
        localFilePath = destination.toString(),
        stagingFilePath = staging.toString(),
        installState = OnDeviceLlmDownloadStates.NOT_DOWNLOADED,
        downloadedBytes = 0L,
        resumeBytes = resumeBytes,
        resolvedSha256 = integrityStrategy.resolveExpectedSha256(
          entry = entry,
          installRecord = installStore.load(entry.id),
          sourceLinkedEtag = sourceLinkedEtag,
        ),
        etag = etag,
        lastModified = lastModified,
        acceptRanges = acceptRanges,
      ),
      onRecordSaved,
    )
  }

  private fun reconcileExistingReadyFile(
    entry: OnDeviceLlmCatalogEntry,
    destination: Path,
    installRecord: LiteRtOnDeviceModelInstallRecord?,
    onRecordSaved: (LiteRtOnDeviceModelInstallRecord) -> Unit,
  ): Boolean {
    if (!destination.exists() || !destination.isRegularFile()) {
      return false
    }
    val actualSha256 = runCatching { sha256Hex(destination) }.getOrElse {
      deleteIfExists(destination)
      return false
    }
    val expectedSha256 = integrityStrategy.resolveExpectedSha256(
      entry = entry,
      installRecord = installRecord,
      sourceLinkedEtag = null,
    )
    return if (actualSha256.equals(expectedSha256, ignoreCase = true)) {
      saveRecord(
        buildOnDeviceInstallRecord(
          entry = entry,
          localFilePath = destination.toString(),
          stagingFilePath = null,
          installState = OnDeviceLlmDownloadStates.READY,
          downloadedBytes = Files.size(destination),
          resumeBytes = 0L,
          installedAtEpochMs = installRecord?.installedAtEpochMs ?: nowEpochMs(),
          sha256Verified = true,
          resolvedSha256 = expectedSha256,
          etag = installRecord?.etag,
          lastModified = installRecord?.lastModified,
          acceptRanges = installRecord?.acceptRanges,
        ),
        onRecordSaved,
      )
      true
    } else {
      deleteIfExists(destination)
      saveRecord(
        buildOnDeviceFailedInstallRecord(
          entry = entry,
          localFilePath = destination.toString(),
          stagingFilePath = null,
          downloadedBytes = 0L,
          lastError = "Existing file hash does not match ${entry.title}.",
          etag = installRecord?.etag,
          lastModified = installRecord?.lastModified,
          acceptRanges = installRecord?.acceptRanges,
        ),
        onRecordSaved,
      )
      false
    }
  }

  private fun openConfiguredConnection(
    entry: OnDeviceLlmCatalogEntry,
    installRecord: LiteRtOnDeviceModelInstallRecord?,
    resumeBytes: Long,
    requestRange: Boolean,
  ): HttpURLConnection = openConnection(entry.sourceUrl).apply {
    if (requestRange && resumeBytes > 0L) {
      setRequestProperty("Range", "bytes=$resumeBytes-")
      installRecord?.etag?.takeIf(String::isNotBlank)?.let { headerValue ->
        setRequestProperty("If-Range", headerValue)
      } ?: installRecord?.lastModified?.takeIf(String::isNotBlank)?.let { headerValue ->
        setRequestProperty("If-Range", headerValue)
      }
    }
  }

  private fun saveRecord(
    record: LiteRtOnDeviceModelInstallRecord,
    onRecordSaved: (LiteRtOnDeviceModelInstallRecord) -> Unit,
  ) {
    installStore.save(record)
    onRecordSaved(record)
  }

  private fun requireCatalogEntry(modelId: String): OnDeviceLlmCatalogEntry =
    requireNotNull(resolveCatalogEntry(modelId)) {
      "Unsupported on-device model '$modelId'."
    }

  private fun modelsRoot(): Path =
    LiteRtOnDeviceModelInstallStore.modelsRootForFilesDir(filesDir).toPath().toAbsolutePath().normalize()

  private fun modelPath(entry: OnDeviceLlmCatalogEntry): Path =
    modelsRoot().resolve(entry.fileName).normalize()

  private fun stagingPath(
    entry: OnDeviceLlmCatalogEntry,
    installRecord: LiteRtOnDeviceModelInstallRecord?,
  ): Path {
    val defaultPath = modelsRoot().resolve("${entry.fileName}.downloading").normalize()
    val persistedPath = installRecord?.stagingFilePath
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { rawPath ->
        runCatching { File(rawPath).toPath().toAbsolutePath().normalize() }.getOrNull()
      }
    return if (persistedPath != null && persistedPath.startsWith(modelsRoot())) {
      persistedPath
    } else {
      defaultPath
    }
  }

  private fun hasRequiredFreeSpace(
    root: Path,
    minimumFreeSpaceBytes: Long,
    existingBytes: Long,
  ): Boolean = root.toFile().usableSpace.coerceAtLeast(0L) + existingBytes.coerceAtLeast(0L) >=
    minimumFreeSpaceBytes

  private fun existingFileSize(path: Path): Long = runCatching {
    if (path.exists() && path.isRegularFile()) {
      Files.size(path)
    } else {
      0L
    }
  }.getOrDefault(0L)

  private fun updateDigestFromFile(
    path: Path,
    digest: MessageDigest,
  ) {
    Files.newInputStream(path).use { input ->
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) {
          break
        }
        digest.update(buffer, 0, read)
      }
    }
  }

  private fun sha256Hex(path: Path): String {
    val digest = createDigest()
    updateDigestFromFile(path, digest)
    return digest.digest().toHex()
  }

  private fun moveIntoPlace(
    staging: Path,
    destination: Path,
  ) {
    try {
      Files.move(
        staging,
        destination,
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE,
      )
    } catch (_: AtomicMoveNotSupportedException) {
      Files.move(
        staging,
        destination,
        StandardCopyOption.REPLACE_EXISTING,
      )
    }
  }

  private fun deleteIfExists(path: Path) {
    runCatching { Files.deleteIfExists(path) }
  }

  private fun calculateDownloadBytesPerSecond(
    downloadedBytes: Long,
    downloadStartedAtEpochMs: Long,
    currentEpochMs: Long,
  ): Long {
    val elapsedMs = (currentEpochMs - downloadStartedAtEpochMs).coerceAtLeast(0L)
    if (downloadedBytes <= 0L || elapsedMs <= 0L) {
      return 0L
    }
    return ((downloadedBytes * 1000.0) / elapsedMs)
      .toLong()
      .coerceAtLeast(0L)
  }

  companion object {
    private const val PROGRESS_PERSIST_GRANULARITY_BYTES: Long = 256L * 1024L

    fun fromContext(context: Context): LiteRtOnDeviceModelDownloadRunner =
      LiteRtOnDeviceModelDownloadRunner(
        filesDir = context.applicationContext.filesDir,
        installStore = LiteRtOnDeviceModelInstallStore.fromContext(context.applicationContext),
      )
  }
}

internal class LiteRtOnDeviceModelDownloadNotificationFactory(
  private val context: Context,
) {
  fun build(record: LiteRtOnDeviceModelInstallRecord): Notification {
    val entry = OnDeviceLlmCatalog.entry(record.modelId)
    val modelTitle = entry?.title ?: OnDeviceLlmCatalog.titleFor(record.modelId)
    val contentText = when (OnDeviceLlmDownloadStates.normalize(record.installState)) {
      OnDeviceLlmDownloadStates.VERIFYING,
      OnDeviceLlmDownloadStates.DOWNLOADED -> context.getString(
        R.string.model_download_notification_verifying_text,
      )

      else -> {
        val downloadedLabel = formatBytes(record.downloadedBytes.coerceAtLeast(0L))
        val totalLabel = formatBytes(record.fileSizeBytes.coerceAtLeast(0L))
        val speedSuffix = when {
          record.downloadBytesPerSecond > 0L -> " • ${formatBytes(record.downloadBytesPerSecond)}/s"
          else -> ""
        }
        context.getString(
          R.string.model_download_notification_progress_text,
          downloadedLabel,
          totalLabel,
        ) + speedSuffix
      }
    }
    val progress = when {
      record.fileSizeBytes <= 0L -> 0
      else -> ((record.downloadedBytes.coerceAtLeast(0L).coerceAtMost(record.fileSizeBytes) * 100L) /
        record.fileSizeBytes).toInt()
    }
    return NotificationCompat.Builder(
      context,
      RuntimeNotificationChannelRegistry.CHANNEL_MODEL_DOWNLOAD,
    )
      .setSmallIcon(android.R.drawable.stat_sys_download)
      .setContentTitle(
        context.getString(
          R.string.model_download_notification_title,
          modelTitle,
        ),
      )
      .setContentText(contentText)
      .setContentIntent(createOpenAppPendingIntent(context))
      .addAction(
        0,
        context.getString(R.string.runtime_notification_action_open),
        createOpenAppPendingIntent(context),
      )
      .setOnlyAlertOnce(true)
      .setOngoing(true)
      .setCategory(NotificationCompat.CATEGORY_PROGRESS)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .setProgress(100, progress, false)
      .build()
  }

  private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) {
      return "0 B"
    }
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
      value /= 1024
      unitIndex += 1
    }
    val digits = when {
      value >= 100 -> 0
      value >= 10 -> 1
      else -> 2
    }
    return "%.${digits}f %s".format(value, units[unitIndex])
  }
}

internal fun buildOnDeviceInstallRecord(
  entry: OnDeviceLlmCatalogEntry,
  localFilePath: String,
  stagingFilePath: String?,
  installState: String,
  downloadedBytes: Long = 0L,
  resumeBytes: Long = 0L,
  downloadBytesPerSecond: Long = 0L,
  installedAtEpochMs: Long? = null,
  sha256Verified: Boolean = false,
  resolvedSha256: String = entry.sha256,
  etag: String? = null,
  lastModified: String? = null,
  acceptRanges: Boolean? = null,
  lastError: String? = null,
): LiteRtOnDeviceModelInstallRecord = LiteRtOnDeviceModelInstallRecord(
  modelId = entry.id,
  versionTag = entry.versionTag,
  sourceUrl = entry.sourceUrl,
  localFilePath = localFilePath,
  stagingFilePath = stagingFilePath,
  fileSizeBytes = entry.fileSizeBytes,
  sha256 = resolvedSha256,
  installState = installState,
  downloadedBytes = downloadedBytes,
  resumeBytes = resumeBytes,
  downloadBytesPerSecond = downloadBytesPerSecond,
  etag = etag,
  lastModified = lastModified,
  acceptRanges = acceptRanges,
  lastError = lastError,
  installedAtEpochMs = installedAtEpochMs,
  sha256Verified = sha256Verified,
)

internal fun buildOnDeviceFailedInstallRecord(
  entry: OnDeviceLlmCatalogEntry,
  localFilePath: String,
  stagingFilePath: String?,
  downloadedBytes: Long,
  lastError: String,
  resolvedSha256: String = entry.sha256,
  etag: String? = null,
  lastModified: String? = null,
  acceptRanges: Boolean? = null,
): LiteRtOnDeviceModelInstallRecord = buildOnDeviceInstallRecord(
  entry = entry,
  localFilePath = localFilePath,
  stagingFilePath = stagingFilePath,
  installState = OnDeviceLlmDownloadStates.FAILED,
  downloadedBytes = downloadedBytes,
  resumeBytes = 0L,
  downloadBytesPerSecond = 0L,
  resolvedSha256 = resolvedSha256,
  etag = etag,
  lastModified = lastModified,
  acceptRanges = acceptRanges,
  lastError = lastError,
)

internal fun normalizeOnDeviceModelId(modelId: String?): String? =
  modelId?.trim()?.lowercase()?.takeIf(String::isNotBlank)

internal fun modelDownloadWorkName(modelId: String): String =
  "litert-model-download-${modelId.filter { ch -> ch.isLetterOrDigit() || ch == '-' || ch == '_' }}"

internal const val WORK_DATA_MODEL_ID: String = "model_id"
private const val HTTP_RANGE_NOT_SATISFIABLE: Int = 416

private fun sanitizeHeaderValue(value: String?): String? =
  value?.trim()?.takeIf(String::isNotBlank)

internal interface OnDeviceModelIntegrityStrategy {
  fun resolveExpectedSha256(
    entry: OnDeviceLlmCatalogEntry,
    installRecord: LiteRtOnDeviceModelInstallRecord?,
    sourceLinkedEtag: String?,
  ): String

  fun isResumeContentRangeValid(
    contentRange: String?,
    expectedResumeBytes: Long,
  ): Boolean
}

internal data class OnDeviceModelSourceIntegrityHints(
  val linkedEtag: String? = null,
)

internal object SourceHashPreferredOnDeviceModelIntegrityStrategy :
  OnDeviceModelIntegrityStrategy {
  override fun resolveExpectedSha256(
    entry: OnDeviceLlmCatalogEntry,
    installRecord: LiteRtOnDeviceModelInstallRecord?,
    sourceLinkedEtag: String?,
  ): String = parseStrongSha256FromEtag(sourceLinkedEtag)
    ?: installRecord?.sha256
      ?.trim()
      ?.lowercase()
      ?.takeIf(::isStrongSha256)
    ?: entry.sha256.trim().lowercase()

  override fun isResumeContentRangeValid(
    contentRange: String?,
    expectedResumeBytes: Long,
  ): Boolean = parseContentRangeStart(contentRange) == expectedResumeBytes

  private fun parseStrongSha256FromEtag(etag: String?): String? {
    val normalized = sanitizeHeaderValue(etag) ?: return null
    if (normalized.startsWith("W/")) {
      return null
    }
    return normalized
      .removePrefix("\"")
      .removeSuffix("\"")
      .trim()
      .lowercase()
      .takeIf(::isStrongSha256)
  }

  private fun isStrongSha256(value: String): Boolean =
    value.length == 64 && value.all { character ->
      character in '0'..'9' || character in 'a'..'f'
    }

  private fun parseContentRangeStart(contentRange: String?): Long? {
    val normalized = sanitizeHeaderValue(contentRange) ?: return null
    val match = Regex("""^bytes\s+(\d+)-(\d+)/(\d+|\*)$""").find(normalized) ?: return null
    return match.groupValues[1].toLongOrNull()
  }
}

private fun preferredSourceLinkedEtag(connection: HttpURLConnection): String? {
  val linkedEtag = sanitizeHeaderValue(connection.getHeaderField("X-Linked-ETag"))
  if (!linkedEtag.isNullOrBlank()) {
    return linkedEtag
  }
  val xetHash = sanitizeHeaderValue(connection.getHeaderField("X-Xet-Hash"))
  return if (!xetHash.isNullOrBlank()) {
    null
  } else {
    sanitizeHeaderValue(connection.getHeaderField("ETag"))
  }
}

private fun probeOnDeviceModelSourceIntegrityHints(
  url: String,
): OnDeviceModelSourceIntegrityHints? {
  val connection = openOnDeviceMetadataConnection(url)
  return try {
    connection.connect()
    OnDeviceModelSourceIntegrityHints(
      linkedEtag = preferredSourceLinkedEtag(connection),
    )
  } finally {
    connection.disconnect()
  }
}

private fun parseAcceptRanges(value: String?): Boolean? =
  when (value?.trim()?.lowercase()) {
    "bytes" -> true
    "none" -> false
    else -> null
  }

private fun isRetryableHttpStatus(statusCode: Int): Boolean =
  statusCode == HttpURLConnection.HTTP_CLIENT_TIMEOUT ||
    statusCode == HttpURLConnection.HTTP_GATEWAY_TIMEOUT ||
    statusCode == HttpURLConnection.HTTP_UNAVAILABLE ||
    statusCode == HttpURLConnection.HTTP_BAD_GATEWAY ||
    statusCode == HttpURLConnection.HTTP_INTERNAL_ERROR ||
    statusCode == 429

private fun isRetryableDownloadThrowable(throwable: Throwable): Boolean =
  throwable is SocketTimeoutException ||
    throwable is SocketException ||
    throwable is IOException

private fun openDefaultOnDeviceDownloadConnection(url: String): HttpURLConnection =
  (URL(url).openConnection() as HttpURLConnection).apply {
    requestMethod = "GET"
    connectTimeout = 30_000
    readTimeout = 30_000
    instanceFollowRedirects = true
    doInput = true
    useCaches = false
    setRequestProperty("Accept", "application/octet-stream")
  }

private fun openOnDeviceMetadataConnection(url: String): HttpURLConnection =
  (URL(url).openConnection() as HttpURLConnection).apply {
    requestMethod = "HEAD"
    connectTimeout = 15_000
    readTimeout = 15_000
    instanceFollowRedirects = false
    doInput = true
    useCaches = false
    setRequestProperty("Accept", "application/octet-stream")
  }

private fun modelDownloadNotificationId(modelId: String): Int =
  43_000 + (modelId.hashCode().absoluteValue % 1_000)

private fun createOpenAppPendingIntent(
  context: Context,
): PendingIntent {
  val intent = Intent(context, OpenCrayFlutterActivity::class.java).apply {
    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
  }
  return PendingIntent.getActivity(
    context,
    0,
    intent,
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
  )
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
  "%02x".format(byte)
}
