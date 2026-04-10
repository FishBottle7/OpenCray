package com.opencray.app

import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.io.path.readBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtOnDeviceModelDownloadManagerTest {
  @Test
  fun runnerPersistsReadyRecordWhenHashMatches() {
    val modelBytes = "opencray-litert-model".encodeToByteArray()
    val entry = testCatalogEntry(
      sha256 = sha256Hex(modelBytes),
      fileSizeBytes = modelBytes.size.toLong(),
    )
    val filesDir = Files.createTempDirectory("litert-download-success").toFile()
    val store = InMemoryLiteRtOnDeviceModelInstallStore()
    val connection = FakeHttpURLConnection(
      rawUrl = entry.sourceUrl,
      payload = modelBytes,
    )
    val runner = LiteRtOnDeviceModelDownloadRunner(
      filesDir = filesDir,
      installStore = store,
      resolveCatalogEntry = { modelId -> entry.takeIf { it.id == modelId } },
      openConnection = { connection },
      probeSourceIntegrityHints = { null },
    )

    val result = runner.run(entry.id)

    assertEquals(LiteRtOnDeviceModelDownloadRunResult.SUCCESS, result)
    val record = checkNotNull(store.load(entry.id))
    assertEquals(OnDeviceLlmDownloadStates.READY, record.installState)
    assertEquals(entry.fileSizeBytes, record.downloadedBytes)
    assertEquals(0L, record.resumeBytes)
    assertEquals(true, record.sha256Verified)
    assertEquals(modelBytes.toList(), Files.readAllBytes(File(record.localFilePath).toPath()).toList())
    assertEquals(1, connection.connectCount)
  }

  @Test
  fun runnerPersistsFailedRecordWhenHashDoesNotMatch() {
    val modelBytes = "opencray-litert-model".encodeToByteArray()
    val entry = testCatalogEntry(
      sha256 = "deadbeef",
      fileSizeBytes = modelBytes.size.toLong(),
    )
    val filesDir = Files.createTempDirectory("litert-download-failed").toFile()
    val store = InMemoryLiteRtOnDeviceModelInstallStore()
    val runner = LiteRtOnDeviceModelDownloadRunner(
      filesDir = filesDir,
      installStore = store,
      resolveCatalogEntry = { modelId -> entry.takeIf { it.id == modelId } },
      openConnection = { FakeHttpURLConnection(entry.sourceUrl, modelBytes) },
      probeSourceIntegrityHints = { null },
    )

    val result = runner.run(entry.id)

    assertEquals(LiteRtOnDeviceModelDownloadRunResult.FAILURE, result)
    val record = checkNotNull(store.load(entry.id))
    assertEquals(OnDeviceLlmDownloadStates.FAILED, record.installState)
    assertEquals(false, record.sha256Verified)
    assertTrue(record.lastError?.contains("SHA-256", ignoreCase = true) == true)
    assertFalse(File(record.localFilePath).exists())
  }

  @Test
  fun runnerPrefersStrongSourceHashWhenCatalogHashIsStale() {
    val modelBytes = "opencray-litert-source-hash".encodeToByteArray()
    val sourceSha256 = sha256Hex(modelBytes)
    val entry = testCatalogEntry(
      sha256 = "deadbeef",
      fileSizeBytes = modelBytes.size.toLong(),
    )
    val filesDir = Files.createTempDirectory("litert-download-source-hash").toFile()
    val store = InMemoryLiteRtOnDeviceModelInstallStore()
    val runner = LiteRtOnDeviceModelDownloadRunner(
      filesDir = filesDir,
      installStore = store,
      resolveCatalogEntry = { modelId -> entry.takeIf { it.id == modelId } },
      openConnection = {
        FakeHttpURLConnection(
          rawUrl = entry.sourceUrl,
          payload = modelBytes,
        )
      },
      probeSourceIntegrityHints = {
        OnDeviceModelSourceIntegrityHints(linkedEtag = "\"$sourceSha256\"")
      },
    )

    val result = runner.run(entry.id)

    assertEquals(LiteRtOnDeviceModelDownloadRunResult.SUCCESS, result)
    val record = checkNotNull(store.load(entry.id))
    assertEquals(OnDeviceLlmDownloadStates.READY, record.installState)
    assertEquals(sourceSha256, record.sha256)
    assertTrue(record.sha256Verified)
  }

  @Test
  fun runnerFallsBackToCatalogHashWhenSourceEtagIsWeak() {
    val modelBytes = "opencray-litert-weak-etag".encodeToByteArray()
    val entry = testCatalogEntry(
      sha256 = sha256Hex(modelBytes),
      fileSizeBytes = modelBytes.size.toLong(),
    )
    val filesDir = Files.createTempDirectory("litert-download-weak-etag").toFile()
    val store = InMemoryLiteRtOnDeviceModelInstallStore()
    val runner = LiteRtOnDeviceModelDownloadRunner(
      filesDir = filesDir,
      installStore = store,
      resolveCatalogEntry = { modelId -> entry.takeIf { it.id == modelId } },
      openConnection = {
        FakeHttpURLConnection(
          rawUrl = entry.sourceUrl,
          payload = modelBytes,
          responseHeaders = mapOf("ETag" to "W/\"not-a-sha256\""),
        )
      },
      probeSourceIntegrityHints = { null },
    )

    val result = runner.run(entry.id)

    assertEquals(LiteRtOnDeviceModelDownloadRunResult.SUCCESS, result)
    val record = checkNotNull(store.load(entry.id))
    assertEquals(OnDeviceLlmDownloadStates.READY, record.installState)
    assertEquals(entry.sha256, record.sha256)
    assertTrue(record.sha256Verified)
  }

  @Test
  fun runnerIgnoresFinalResponseEtagWhenResolverLinkedHashIsUnavailable() {
    val modelBytes = "opencray-litert-final-etag".encodeToByteArray()
    val entry = testCatalogEntry(
      sha256 = sha256Hex(modelBytes),
      fileSizeBytes = modelBytes.size.toLong(),
    )
    val filesDir = Files.createTempDirectory("litert-download-final-etag").toFile()
    val store = InMemoryLiteRtOnDeviceModelInstallStore()
    val runner = LiteRtOnDeviceModelDownloadRunner(
      filesDir = filesDir,
      installStore = store,
      resolveCatalogEntry = { modelId -> entry.takeIf { it.id == modelId } },
      openConnection = {
        FakeHttpURLConnection(
          rawUrl = entry.sourceUrl,
          payload = modelBytes,
          responseHeaders = mapOf(
            "ETag" to "\"de7d0691f45362329dd9d120b750c54b57ea1b7cbc5d0bbd713a96bda41eab32\"",
          ),
        )
      },
      probeSourceIntegrityHints = { null },
    )

    val result = runner.run(entry.id)

    assertEquals(LiteRtOnDeviceModelDownloadRunResult.SUCCESS, result)
    val record = checkNotNull(store.load(entry.id))
    assertEquals(OnDeviceLlmDownloadStates.READY, record.installState)
    assertEquals(entry.sha256, record.sha256)
    assertTrue(record.sha256Verified)
  }

  @Test
  fun runnerReconcilesExistingReadyFileWithoutHttpRequest() {
    val modelBytes = "opencray-litert-existing".encodeToByteArray()
    val entry = testCatalogEntry(
      sha256 = sha256Hex(modelBytes),
      fileSizeBytes = modelBytes.size.toLong(),
    )
    val filesDir = Files.createTempDirectory("litert-download-existing").toFile()
    val modelsRoot = LiteRtOnDeviceModelInstallStore.modelsRootForFilesDir(filesDir).toPath()
    Files.createDirectories(modelsRoot)
    val destination = modelsRoot.resolve(entry.fileName)
    Files.write(destination, modelBytes)
    var openConnectionCount = 0
    val store = InMemoryLiteRtOnDeviceModelInstallStore()
    val runner = LiteRtOnDeviceModelDownloadRunner(
      filesDir = filesDir,
      installStore = store,
      resolveCatalogEntry = { modelId -> entry.takeIf { it.id == modelId } },
      openConnection = { _ ->
        openConnectionCount += 1
        FakeHttpURLConnection(entry.sourceUrl, modelBytes)
      },
      probeSourceIntegrityHints = { null },
    )

    val result = runner.run(entry.id)

    assertEquals(LiteRtOnDeviceModelDownloadRunResult.SUCCESS, result)
    val record = checkNotNull(store.load(entry.id))
    assertEquals(OnDeviceLlmDownloadStates.READY, record.installState)
    assertEquals(true, record.sha256Verified)
    assertEquals(0, openConnectionCount)
    assertEquals(modelBytes.toList(), destination.readBytes().toList())
  }

  @Test
  fun runnerPersistsProgressSpeedWhileDownloading() {
    val modelBytes = ByteArray(600 * 1024) { index -> (index % 251).toByte() }
    val entry = testCatalogEntry(
      sha256 = sha256Hex(modelBytes),
      fileSizeBytes = modelBytes.size.toLong(),
    )
    val filesDir = Files.createTempDirectory("litert-download-speed").toFile()
    val store = RecordingLiteRtOnDeviceModelInstallStore()
    var nowMs = 1_000L
    val runner = LiteRtOnDeviceModelDownloadRunner(
      filesDir = filesDir,
      installStore = store,
      resolveCatalogEntry = { modelId -> entry.takeIf { it.id == modelId } },
      openConnection = {
        FakeHttpURLConnection(
          rawUrl = entry.sourceUrl,
          payload = modelBytes,
          onRead = { nowMs += 250L },
        )
      },
      probeSourceIntegrityHints = { null },
      nowEpochMs = { nowMs },
    )

    val result = runner.run(entry.id)

    assertEquals(LiteRtOnDeviceModelDownloadRunResult.SUCCESS, result)
    val downloadingRecord = store.savedRecords.lastOrNull { record ->
      record.installState == OnDeviceLlmDownloadStates.DOWNLOADING &&
        record.downloadBytesPerSecond > 0L
    }
    assertNotNull(downloadingRecord)
    assertTrue((downloadingRecord?.downloadBytesPerSecond ?: 0L) > 0L)
    val readyRecord = checkNotNull(store.load(entry.id))
    assertEquals(OnDeviceLlmDownloadStates.READY, readyRecord.installState)
    assertEquals(0L, readyRecord.downloadBytesPerSecond)
    assertNull(readyRecord.lastError)
  }

  @Test
  fun runnerResumesFromPartialStagingWhenServerSupportsRange() {
    val modelBytes = "opencray-litert-model-resume".encodeToByteArray()
    val resumeBytes = modelBytes.copyOfRange(0, 10)
    val entry = testCatalogEntry(
      sha256 = sha256Hex(modelBytes),
      fileSizeBytes = modelBytes.size.toLong(),
    )
    val filesDir = Files.createTempDirectory("litert-download-resume").toFile()
    val modelsRoot = LiteRtOnDeviceModelInstallStore.modelsRootForFilesDir(filesDir).toPath()
    Files.createDirectories(modelsRoot)
    val staging = modelsRoot.resolve("${entry.fileName}.downloading")
    Files.write(staging, resumeBytes)
    val store = InMemoryLiteRtOnDeviceModelInstallStore(
      initialRecords = listOf(
        buildOnDeviceInstallRecord(
          entry = entry,
          localFilePath = modelsRoot.resolve(entry.fileName).toString(),
          stagingFilePath = staging.toString(),
          installState = OnDeviceLlmDownloadStates.DOWNLOADING,
          downloadedBytes = resumeBytes.size.toLong(),
          resumeBytes = resumeBytes.size.toLong(),
          etag = "\"etag-1\"",
          acceptRanges = true,
        ),
      ),
    )
    val connection = FakeHttpURLConnection(
      rawUrl = entry.sourceUrl,
      payload = modelBytes.copyOfRange(resumeBytes.size, modelBytes.size),
      statusCode = HttpURLConnection.HTTP_PARTIAL,
      responseHeaders = mapOf(
        "Accept-Ranges" to "bytes",
        "ETag" to "\"etag-1\"",
        "Content-Range" to "bytes ${resumeBytes.size}-${modelBytes.size - 1}/${modelBytes.size}",
      ),
    )
    val runner = LiteRtOnDeviceModelDownloadRunner(
      filesDir = filesDir,
      installStore = store,
      resolveCatalogEntry = { modelId -> entry.takeIf { it.id == modelId } },
      openConnection = { connection },
      probeSourceIntegrityHints = { null },
    )

    val result = runner.run(entry.id)

    assertEquals(LiteRtOnDeviceModelDownloadRunResult.SUCCESS, result)
    assertEquals("bytes=${resumeBytes.size}-", connection.capturedRequestProperties["Range"])
    assertEquals("\"etag-1\"", connection.capturedRequestProperties["If-Range"])
    val readyRecord = checkNotNull(store.load(entry.id))
    assertEquals(OnDeviceLlmDownloadStates.READY, readyRecord.installState)
    assertEquals(true, readyRecord.acceptRanges)
    assertEquals(entry.sha256, readyRecord.sha256)
    assertEquals(modelBytes.toList(), Files.readAllBytes(File(readyRecord.localFilePath).toPath()).toList())
  }

  @Test
  fun runnerFallsBackToFullRedownloadWhenServerIgnoresRange() {
    val modelBytes = "opencray-litert-model-full".encodeToByteArray()
    val partialBytes = "stale-partial".encodeToByteArray()
    val entry = testCatalogEntry(
      sha256 = sha256Hex(modelBytes),
      fileSizeBytes = modelBytes.size.toLong(),
    )
    val filesDir = Files.createTempDirectory("litert-download-fallback").toFile()
    val modelsRoot = LiteRtOnDeviceModelInstallStore.modelsRootForFilesDir(filesDir).toPath()
    Files.createDirectories(modelsRoot)
    val staging = modelsRoot.resolve("${entry.fileName}.downloading")
    Files.write(staging, partialBytes)
    val store = InMemoryLiteRtOnDeviceModelInstallStore(
      initialRecords = listOf(
        buildOnDeviceInstallRecord(
          entry = entry,
          localFilePath = modelsRoot.resolve(entry.fileName).toString(),
          stagingFilePath = staging.toString(),
          installState = OnDeviceLlmDownloadStates.DOWNLOADING,
          downloadedBytes = partialBytes.size.toLong(),
          resumeBytes = partialBytes.size.toLong(),
          acceptRanges = true,
        ),
      ),
    )
    val connection = FakeHttpURLConnection(
      rawUrl = entry.sourceUrl,
      payload = modelBytes,
      statusCode = HttpURLConnection.HTTP_OK,
      responseHeaders = mapOf("Accept-Ranges" to "none"),
    )
    val runner = LiteRtOnDeviceModelDownloadRunner(
      filesDir = filesDir,
      installStore = store,
      resolveCatalogEntry = { modelId -> entry.takeIf { it.id == modelId } },
      openConnection = { connection },
      probeSourceIntegrityHints = { null },
    )

    val result = runner.run(entry.id)

    assertEquals(LiteRtOnDeviceModelDownloadRunResult.SUCCESS, result)
    assertEquals("bytes=${partialBytes.size}-", connection.capturedRequestProperties["Range"])
    val readyRecord = checkNotNull(store.load(entry.id))
    assertEquals(OnDeviceLlmDownloadStates.READY, readyRecord.installState)
    assertEquals(false, readyRecord.acceptRanges)
    assertEquals(entry.sha256, readyRecord.sha256)
    assertEquals(modelBytes.toList(), Files.readAllBytes(File(readyRecord.localFilePath).toPath()).toList())
  }

  @Test
  fun managerDownloadPersistsInitialStateAndEnqueuesWork() {
    val entry = testCatalogEntry(
      sha256 = sha256Hex("manager".encodeToByteArray()),
      fileSizeBytes = 7L,
    )
    val filesDir = Files.createTempDirectory("litert-manager-enqueue").toFile()
    val scheduler = RecordingDownloadWorkScheduler()
    val store = InMemoryLiteRtOnDeviceModelInstallStore()
    val manager = LiteRtOnDeviceModelDownloadManager(
      filesDir = filesDir,
      installStore = store,
      workScheduler = scheduler,
      resolveCatalogEntry = { modelId -> entry.takeIf { it.id == modelId } },
    )

    manager.download(entry.id)

    assertEquals(listOf(entry.id), scheduler.enqueuedModelIds)
    val record = checkNotNull(store.load(entry.id))
    assertEquals(OnDeviceLlmDownloadStates.DOWNLOADING, record.installState)
    assertEquals(0L, record.resumeBytes)
    assertTrue(record.stagingFilePath?.endsWith(".downloading") == true)
  }

  @Test
  fun managerCancelKeepsResumeMetadataForNextAttempt() {
    val entry = testCatalogEntry(
      sha256 = sha256Hex("manager-cancel".encodeToByteArray()),
      fileSizeBytes = 14L,
    )
    val filesDir = Files.createTempDirectory("litert-manager-cancel").toFile()
    val modelsRoot = LiteRtOnDeviceModelInstallStore.modelsRootForFilesDir(filesDir).toPath()
    Files.createDirectories(modelsRoot)
    val staging = modelsRoot.resolve("${entry.fileName}.downloading")
    val partialBytes = "partial".encodeToByteArray()
    Files.write(staging, partialBytes)
    val scheduler = RecordingDownloadWorkScheduler()
    val store = InMemoryLiteRtOnDeviceModelInstallStore(
      initialRecords = listOf(
        buildOnDeviceInstallRecord(
          entry = entry,
          localFilePath = modelsRoot.resolve(entry.fileName).toString(),
          stagingFilePath = staging.toString(),
          installState = OnDeviceLlmDownloadStates.DOWNLOADING,
          downloadedBytes = partialBytes.size.toLong(),
          resumeBytes = partialBytes.size.toLong(),
          etag = "\"etag-2\"",
          acceptRanges = true,
        ),
      ),
    )
    val manager = LiteRtOnDeviceModelDownloadManager(
      filesDir = filesDir,
      installStore = store,
      workScheduler = scheduler,
      resolveCatalogEntry = { modelId -> entry.takeIf { it.id == modelId } },
    )

    manager.cancel(entry.id)

    assertEquals(listOf(entry.id), scheduler.cancelledModelIds)
    val record = checkNotNull(store.load(entry.id))
    assertEquals(OnDeviceLlmDownloadStates.NOT_DOWNLOADED, record.installState)
    assertEquals(partialBytes.size.toLong(), record.resumeBytes)
    assertEquals("\"etag-2\"", record.etag)
    assertEquals(true, record.acceptRanges)
  }

  private fun testCatalogEntry(
    sha256: String,
    fileSizeBytes: Long,
  ): OnDeviceLlmCatalogEntry = OnDeviceLlmCatalogEntry(
    id = "test-model",
    title = "Test Model",
    description = "Test model for download manager coverage.",
    runtimeId = OnDeviceLlmCatalog.RUNTIME_ID_LITERT_LM,
    sizeLabel = "${fileSizeBytes} B",
    sourceUrl = "https://example.com/test-model.litertlm",
    fileName = "test-model.litertlm",
    versionTag = "test-v1",
    sha256 = sha256,
    fileSizeBytes = fileSizeBytes,
    recommendedBackend = OnDeviceLlmAccelerators.GPU,
    minimumFreeSpaceBytes = 1L,
    experimental = false,
  )

  private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
      .digest(bytes)
      .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private class FakeHttpURLConnection(
  rawUrl: String,
  private val payload: ByteArray,
  private val statusCode: Int = 200,
  private val responseHeaders: Map<String, String> = emptyMap(),
  private val onRead: (() -> Unit)? = null,
) : HttpURLConnection(URL(rawUrl)) {
  var connectCount: Int = 0
    private set
  val capturedRequestProperties: MutableMap<String, String> = linkedMapOf()

  override fun connect() {
    connectCount += 1
    connected = true
  }

  override fun disconnect() {
    connected = false
  }

  override fun usingProxy(): Boolean = false

  override fun getResponseCode(): Int = statusCode

  override fun getInputStream(): ByteArrayInputStream = object : ByteArrayInputStream(payload) {
    override fun read(b: ByteArray, off: Int, len: Int): Int {
      val result = super.read(b, off, len)
      if (result > 0) {
        onRead?.invoke()
      }
      return result
    }
  }

  override fun getHeaderField(name: String?): String? =
    responseHeaders.entries.firstOrNull { entry ->
      entry.key.equals(name, ignoreCase = true)
    }?.value

  override fun setRequestMethod(method: String?) {
    if (method == null) {
      throw ProtocolException("method must not be null")
    }
    this.method = method
  }

  override fun setRequestProperty(key: String?, value: String?) {
    if (key != null && value != null) {
      capturedRequestProperties[key] = value
    }
  }
}

private class RecordingLiteRtOnDeviceModelInstallStore :
  InMemoryLiteRtOnDeviceModelInstallStore() {
  val savedRecords: MutableList<LiteRtOnDeviceModelInstallRecord> = mutableListOf()

  override fun save(record: LiteRtOnDeviceModelInstallRecord) {
    savedRecords += record
    super.save(record)
  }
}

private class RecordingDownloadWorkScheduler : LiteRtOnDeviceModelDownloadWorkScheduler {
  val enqueuedModelIds: MutableList<String> = mutableListOf()
  val cancelledModelIds: MutableList<String> = mutableListOf()

  override fun enqueue(modelId: String) {
    enqueuedModelIds += modelId
  }

  override fun cancel(modelId: String) {
    cancelledModelIds += modelId
  }
}
