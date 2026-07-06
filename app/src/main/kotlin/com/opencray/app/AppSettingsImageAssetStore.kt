package com.opencray.app

import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.DurableTextUpdate
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import java.io.File
import java.net.URLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

internal data class AppSettingsImageAsset(
  val assetId: String,
  val displayName: String,
  val relativePath: String,
  val mimeType: String,
  val sha256: String,
  val sizeBytes: Long,
  val createdAtEpochMs: Long,
) {
  init {
    require(assetId.isNotBlank()) { "AppSettingsImageAsset assetId must not be blank." }
    require(displayName.isNotBlank()) { "AppSettingsImageAsset displayName must not be blank." }
    require(relativePath.isNotBlank()) { "AppSettingsImageAsset relativePath must not be blank." }
    require(mimeType.isNotBlank()) { "AppSettingsImageAsset mimeType must not be blank." }
    require(sha256.matches(SHA256_REGEX)) {
      "AppSettingsImageAsset sha256 must be a 64-character hex string."
    }
    require(sizeBytes >= 0L) { "AppSettingsImageAsset sizeBytes must be >= 0." }
    require(createdAtEpochMs >= 0L) { "AppSettingsImageAsset createdAtEpochMs must be >= 0." }
  }

  companion object {
    private val SHA256_REGEX: Regex = Regex("[0-9a-f]{64}")
  }
}

internal class AppSettingsImageAssetStore(
  directory: File,
  private val nowEpochMs: () -> Long = System::currentTimeMillis,
  private val json: Json = Json { ignoreUnknownKeys = true },
) {
  private val root: Path = directory.toPath().toAbsolutePath().normalize()
  private val storage: DurableTextStorage = DirectoryDurableTextStorage(directory)
  private var entries: MutableMap<String, AppSettingsImageAssetStoreEntry> = linkedMapOf()

  @Synchronized
  fun importImage(
    sourcePath: Path,
    displayName: String? = null,
    mimeType: String? = null,
  ): AppSettingsImageAsset? {
    val normalizedSourcePath = sourcePath.toAbsolutePath().normalize()
    if (!Files.exists(normalizedSourcePath) || !normalizedSourcePath.isRegularFile()) {
      return null
    }
    val normalizedDisplayName = displayName
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: normalizedSourcePath.name
        .trim()
        .takeIf(String::isNotBlank)
      ?: return null
    val normalizedMimeType = resolveMimeType(
      preferredMimeType = mimeType,
      fileName = normalizedDisplayName,
    ) ?: return null
    if (!isSupportedImage(fileName = normalizedDisplayName, mimeType = normalizedMimeType)) {
      return null
    }
    val sha256 = sha256Hex(normalizedSourcePath)
    val assetId = "settings-${sha256.take(12)}"
    refreshEntriesLocked()
    entries[assetId]?.let { existingEntry ->
      resolveEntryPath(existingEntry.relativePath)?.let {
        return existingEntry.toAsset()
      }
    }
    val destination = root
      .resolve(ASSET_DIRECTORY_NAME)
      .resolve(assetId)
      .resolve(safeFileName(normalizedDisplayName))
      .normalize()
    require(destination.startsWith(root)) {
      "Settings image asset destination must stay inside the settings asset root."
    }
    Files.createDirectories(requireNotNull(destination.parent))
    if (normalizedSourcePath != destination) {
      Files.copy(
        normalizedSourcePath,
        destination,
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.COPY_ATTRIBUTES,
      )
    }
    val entry = AppSettingsImageAssetStoreEntry(
      assetId = assetId,
      displayName = normalizedDisplayName,
      relativePath = root.relativize(destination).toString().replace('\\', '/'),
      mimeType = normalizedMimeType,
      sha256 = sha256,
      sizeBytes = Files.size(destination),
      createdAtEpochMs = nowEpochMs(),
    )
    return persistEntryLocked(entry)
  }

  @Synchronized
  fun load(assetId: String): AppSettingsImageAsset? {
    val normalizedAssetId = assetId.trim().takeIf(String::isNotBlank) ?: return null
    refreshEntriesLocked()
    val entry = entries[normalizedAssetId] ?: return null
    return resolveEntryPath(entry.relativePath)?.let { entry.toAsset() }
  }

  @Synchronized
  fun resolveImageHandle(assetId: String): AppResolvedImageAssetHandle? {
    val normalizedAssetId = assetId.trim().takeIf(String::isNotBlank) ?: return null
    refreshEntriesLocked()
    val entry = entries[normalizedAssetId] ?: return null
    val resolvedPath = resolveEntryPath(entry.relativePath) ?: return null
    return AppResolvedImageAssetHandle(
      path = resolvedPath,
      displayName = entry.displayName,
      mimeType = entry.mimeType,
    )
  }

  @Synchronized
  fun list(): List<AppSettingsImageAsset> {
    refreshEntriesLocked()
    return entries.values
      .filter { entry -> resolveEntryPath(entry.relativePath) != null }
      .map(AppSettingsImageAssetStoreEntry::toAsset)
  }

  private fun refreshEntriesLocked() {
    entries = LinkedHashMap(decodeSnapshot(storage.readText(INDEX_FILE_NAME)).entries)
  }

  private fun persistEntryLocked(
    entry: AppSettingsImageAssetStoreEntry,
  ): AppSettingsImageAsset = storage.updateText(INDEX_FILE_NAME) { currentText ->
    val currentEntries = LinkedHashMap(decodeSnapshot(currentText).entries)
    val retainedEntry = currentEntries[entry.assetId]
      ?.takeIf { existingEntry -> resolveEntryPath(existingEntry.relativePath) != null }
      ?: entry
    currentEntries[entry.assetId] = retainedEntry
    entries = currentEntries
    DurableTextUpdate(
      text = json.encodeToString(
        AppSettingsImageAssetStoreSnapshot(
          entries = currentEntries.toMap(),
        ),
      ),
      result = retainedEntry.toAsset(),
    )
  }

  private fun decodeSnapshot(raw: String?): AppSettingsImageAssetStoreSnapshot {
    val normalized = raw?.takeIf(String::isNotBlank) ?: return AppSettingsImageAssetStoreSnapshot()
    return runCatching {
      json.decodeFromString<AppSettingsImageAssetStoreSnapshot>(normalized)
    }.getOrNull() ?: AppSettingsImageAssetStoreSnapshot()
  }

  private fun resolveEntryPath(relativePath: String): Path? {
    val normalizedRelativePath = relativePath
      .trim()
      .replace('\\', '/')
      .removePrefix("/")
      .takeIf(String::isNotBlank)
      ?: return null
    val resolved = root.resolve(normalizedRelativePath).normalize()
    if (!resolved.startsWith(root)) {
      return null
    }
    return resolved.takeIf { path -> Files.exists(path) && path.isRegularFile() }
  }

  private fun resolveMimeType(
    preferredMimeType: String?,
    fileName: String,
  ): String? {
    val normalizedPreferred = preferredMimeType?.trim()?.takeIf(String::isNotBlank)
    if (normalizedPreferred != null) {
      return normalizedPreferred
    }
    val guessed = URLConnection.guessContentTypeFromName(fileName)
      ?.trim()
      ?.takeIf(String::isNotBlank)
    if (guessed != null) {
      return guessed
    }
    return FALLBACK_MIME_TYPES[fileName.substringAfterLast('.', "").lowercase(Locale.US)]
  }

  private fun isSupportedImage(
    fileName: String,
    mimeType: String,
  ): Boolean {
    val extension = fileName.substringAfterLast('.', "").lowercase(Locale.US)
    val normalizedMimeType = mimeType.trim().lowercase(Locale.US)
    return extension in SUPPORTED_IMAGE_EXTENSIONS || normalizedMimeType in SUPPORTED_IMAGE_MIME_TYPES
  }

  private fun safeFileName(value: String): String = value
    .trim()
    .replace(Regex("""[\\/:*?"<>|]"""), "_")
    .ifBlank { "image.bin" }

  private fun sha256Hex(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) {
          break
        }
        if (read > 0) {
          digest.update(buffer, 0, read)
        }
      }
    }
    return digest.digest().joinToString(separator = "") { byte ->
      "%02x".format(byte)
    }
  }

  companion object {
    private const val INDEX_FILE_NAME: String = "settings-image-assets-index.json"
    private const val ASSET_DIRECTORY_NAME: String = "assets"
    private const val DIRECTORY_NAME: String = "opencray-settings-image-assets"

    private val SUPPORTED_IMAGE_EXTENSIONS: Set<String> = setOf(
      "bmp",
      "gif",
      "heic",
      "heif",
      "jpeg",
      "jpg",
      "png",
      "webp",
    )

    private val SUPPORTED_IMAGE_MIME_TYPES: Set<String> = setOf(
      "image/bmp",
      "image/gif",
      "image/heic",
      "image/heif",
      "image/jpeg",
      "image/png",
      "image/webp",
    )

    private val FALLBACK_MIME_TYPES: Map<String, String> = mapOf(
      "bmp" to "image/bmp",
      "gif" to "image/gif",
      "heic" to "image/heic",
      "heif" to "image/heif",
      "jpeg" to "image/jpeg",
      "jpg" to "image/jpeg",
      "png" to "image/png",
      "webp" to "image/webp",
    )

    fun fromFilesDir(
      filesDir: File,
      nowEpochMs: () -> Long = System::currentTimeMillis,
    ): AppSettingsImageAssetStore = AppSettingsImageAssetStore(
      directory = File(filesDir, DIRECTORY_NAME),
      nowEpochMs = nowEpochMs,
    )
  }
}

@Serializable
internal data class AppSettingsImageAssetStoreSnapshot(
  val entries: Map<String, AppSettingsImageAssetStoreEntry> = emptyMap(),
)

@Serializable
internal data class AppSettingsImageAssetStoreEntry(
  val assetId: String,
  val displayName: String,
  val relativePath: String,
  val mimeType: String,
  val sha256: String,
  val sizeBytes: Long,
  val createdAtEpochMs: Long,
) {
  fun toAsset(): AppSettingsImageAsset = AppSettingsImageAsset(
    assetId = assetId,
    displayName = displayName,
    relativePath = relativePath,
    mimeType = mimeType,
    sha256 = sha256,
    sizeBytes = sizeBytes,
    createdAtEpochMs = createdAtEpochMs,
  )
}
