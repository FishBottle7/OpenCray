package com.opencray.filesystem

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

enum class SafGrantPermissionState {
  GRANTED,
  REVOKED,
}

data class PersistedSafGrantSnapshot(
  val workspaceId: String,
  val treeUri: String,
  val rootDocumentId: String,
  val workspaceRelativeRootPath: String = "",
  val permissionState: SafGrantPermissionState = SafGrantPermissionState.GRANTED,
  val persistedAtEpochMillis: Long,
  val revokedAtEpochMillis: Long? = null,
) {
  init {
    require(workspaceId.isNotBlank()) { "workspaceId must not be blank." }
    require(treeUri.isNotBlank()) { "treeUri must not be blank." }
    require(normalizeRelativePathOrNull(workspaceRelativeRootPath) != null) {
      "workspaceRelativeRootPath must be empty or a normalized relative path."
    }
    require(normalizeDocumentIdOrNull(rootDocumentId) != null) {
      "rootDocumentId must be a normalized SAF document identifier."
    }

    if (permissionState == SafGrantPermissionState.GRANTED) {
      require(revokedAtEpochMillis == null) {
        "revokedAtEpochMillis must be null while permission is granted."
      }
    } else {
      require(revokedAtEpochMillis != null) {
        "revokedAtEpochMillis is required when permission is revoked."
      }
    }
  }

  val normalizedWorkspaceRelativeRootPath: String
    get() = requireNotNull(normalizeRelativePathOrNull(workspaceRelativeRootPath)) {
      "workspaceRelativeRootPath was validated during initialization."
    }

  val normalizedRootDocumentId: String
    get() = requireNotNull(normalizeDocumentIdOrNull(rootDocumentId)) {
      "rootDocumentId was validated during initialization."
    }

  fun asRevoked(revokedAtEpochMillis: Long): PersistedSafGrantSnapshot = copy(
    permissionState = SafGrantPermissionState.REVOKED,
    revokedAtEpochMillis = revokedAtEpochMillis,
  )

  fun asGranted(): PersistedSafGrantSnapshot = copy(
    permissionState = SafGrantPermissionState.GRANTED,
    revokedAtEpochMillis = null,
  )

  companion object {
    fun fromTreeUri(
      workspaceId: String,
      treeUri: String,
      persistedAtEpochMillis: Long,
      workspaceRelativeRootPath: String = "",
    ): PersistedSafGrantSnapshot {
      val rootDocumentId = requireNotNull(
        extractTreeDocumentId(treeUri),
      ) { "treeUri must be a valid SAF tree URI." }

      return PersistedSafGrantSnapshot(
        workspaceId = workspaceId,
        treeUri = treeUri,
        rootDocumentId = rootDocumentId,
        workspaceRelativeRootPath = workspaceRelativeRootPath,
        persistedAtEpochMillis = persistedAtEpochMillis,
      )
    }
  }
}

sealed class SafAccessRequest(open val rawValue: String) {
  data class RelativePath(
    override val rawValue: String,
  ) : SafAccessRequest(rawValue)

  data class DocumentUri(
    override val rawValue: String,
  ) : SafAccessRequest(rawValue)
}

sealed class SafAccessState(
  open val workspaceId: String,
  open val request: SafAccessRequest,
) {
  data class Granted(
    override val workspaceId: String,
    override val request: SafAccessRequest,
    val snapshot: PersistedSafGrantSnapshot,
  ) : SafAccessState(workspaceId, request)

  data class NotGranted(
    override val workspaceId: String,
    override val request: SafAccessRequest,
  ) : SafAccessState(workspaceId, request)

  data class Revoked(
    override val workspaceId: String,
    override val request: SafAccessRequest,
    val snapshot: PersistedSafGrantSnapshot,
  ) : SafAccessState(workspaceId, request) {
    fun recoverableGrant(): PersistedSafGrantSnapshot = snapshot.asGranted()
  }

  data class OutsideGrantedRoot(
    override val workspaceId: String,
    override val request: SafAccessRequest,
    val snapshot: PersistedSafGrantSnapshot,
  ) : SafAccessState(workspaceId, request)

  data class InvalidPath(
    override val workspaceId: String,
    override val request: SafAccessRequest,
    val snapshot: PersistedSafGrantSnapshot,
    val reasonCode: String = FileOpsReasonCode.DENY_PATH_TRAVERSAL,
  ) : SafAccessState(workspaceId, request)
}

interface SafWorkspaceGrantStore {
  fun getGrant(workspaceId: String): PersistedSafGrantSnapshot?

  fun saveGrant(snapshot: PersistedSafGrantSnapshot): PersistedSafGrantSnapshot

  fun revokeGrant(workspaceId: String, revokedAtEpochMillis: Long): PersistedSafGrantSnapshot?

  fun clearGrant(workspaceId: String)

  fun allGrants(): List<PersistedSafGrantSnapshot>
}

class InMemorySafWorkspaceGrantStore(
  initialGrants: Iterable<PersistedSafGrantSnapshot> = emptyList(),
) : SafWorkspaceGrantStore {
  private val grantsByWorkspaceId = LinkedHashMap<String, PersistedSafGrantSnapshot>()

  init {
    for (snapshot in initialGrants.sortedBy { it.workspaceId }) {
      grantsByWorkspaceId[snapshot.workspaceId] = snapshot
    }
  }

  @Synchronized
  override fun getGrant(workspaceId: String): PersistedSafGrantSnapshot? = grantsByWorkspaceId[workspaceId]

  @Synchronized
  override fun saveGrant(snapshot: PersistedSafGrantSnapshot): PersistedSafGrantSnapshot {
    grantsByWorkspaceId[snapshot.workspaceId] = snapshot
    return snapshot
  }

  @Synchronized
  override fun revokeGrant(workspaceId: String, revokedAtEpochMillis: Long): PersistedSafGrantSnapshot? {
    val existing = grantsByWorkspaceId[workspaceId] ?: return null
    val revoked = existing.asRevoked(revokedAtEpochMillis)
    grantsByWorkspaceId[workspaceId] = revoked
    return revoked
  }

  @Synchronized
  override fun clearGrant(workspaceId: String) {
    grantsByWorkspaceId.remove(workspaceId)
  }

  @Synchronized
  override fun allGrants(): List<PersistedSafGrantSnapshot> = grantsByWorkspaceId.values
    .sortedBy { it.workspaceId }
}

interface SafWorkspaceBridge {
  fun currentGrant(workspaceId: String): PersistedSafGrantSnapshot?

  fun registerGrant(snapshot: PersistedSafGrantSnapshot): PersistedSafGrantSnapshot

  fun revokeGrant(workspaceId: String, revokedAtEpochMillis: Long): PersistedSafGrantSnapshot?

  fun clearGrant(workspaceId: String)

  fun checkRelativePath(workspaceId: String, relativePath: String): SafAccessState

  fun checkDocumentUri(workspaceId: String, documentUri: String): SafAccessState
}

class DefaultSafWorkspaceBridge(
  private val store: SafWorkspaceGrantStore = InMemorySafWorkspaceGrantStore(),
) : SafWorkspaceBridge {
  override fun currentGrant(workspaceId: String): PersistedSafGrantSnapshot? = store.getGrant(workspaceId)

  override fun registerGrant(snapshot: PersistedSafGrantSnapshot): PersistedSafGrantSnapshot =
    store.saveGrant(snapshot.asGranted())

  override fun revokeGrant(workspaceId: String, revokedAtEpochMillis: Long): PersistedSafGrantSnapshot? =
    store.revokeGrant(workspaceId, revokedAtEpochMillis)

  override fun clearGrant(workspaceId: String) {
    store.clearGrant(workspaceId)
  }

  override fun checkRelativePath(workspaceId: String, relativePath: String): SafAccessState {
    val request = SafAccessRequest.RelativePath(relativePath)
    val snapshot = store.getGrant(workspaceId) ?: return SafAccessState.NotGranted(workspaceId, request)

    if (snapshot.permissionState == SafGrantPermissionState.REVOKED) {
      return SafAccessState.Revoked(workspaceId, request, snapshot)
    }

    val normalizedRelativePath = normalizeRelativePathOrNull(relativePath)
      ?: return SafAccessState.InvalidPath(workspaceId, request, snapshot)

    return if (isWithinRelativeRoot(snapshot.normalizedWorkspaceRelativeRootPath, normalizedRelativePath)) {
      SafAccessState.Granted(workspaceId, request, snapshot)
    } else {
      SafAccessState.OutsideGrantedRoot(workspaceId, request, snapshot)
    }
  }

  override fun checkDocumentUri(workspaceId: String, documentUri: String): SafAccessState {
    val request = SafAccessRequest.DocumentUri(documentUri)
    val snapshot = store.getGrant(workspaceId) ?: return SafAccessState.NotGranted(workspaceId, request)

    if (snapshot.permissionState == SafGrantPermissionState.REVOKED) {
      return SafAccessState.Revoked(workspaceId, request, snapshot)
    }

    val documentId = extractRequestedDocumentId(documentUri)
    val normalizedDocumentId = documentId?.let(::normalizeDocumentIdOrNull)

    return if (
      normalizedDocumentId != null &&
      isWithinDocumentRoot(snapshot.normalizedRootDocumentId, normalizedDocumentId)
    ) {
      SafAccessState.Granted(workspaceId, request, snapshot)
    } else {
      SafAccessState.OutsideGrantedRoot(workspaceId, request, snapshot)
    }
  }
}

private fun isWithinRelativeRoot(rootPath: String, candidatePath: String): Boolean {
  if (rootPath.isEmpty()) {
    return true
  }

  return candidatePath == rootPath || candidatePath.startsWith("$rootPath/")
}

private fun isWithinDocumentRoot(rootDocumentId: String, candidateDocumentId: String): Boolean {
  if (candidateDocumentId == rootDocumentId) {
    return true
  }

  return if (rootDocumentId.endsWith(':')) {
    candidateDocumentId.startsWith(rootDocumentId)
  } else {
    candidateDocumentId.startsWith("$rootDocumentId/")
  }
}

private fun extractRequestedDocumentId(documentUri: String): String? =
  extractEncodedSafId(documentUri, marker = "/document/")
    ?: extractEncodedSafId(documentUri, marker = "/tree/")

private fun extractTreeDocumentId(treeUri: String): String? =
  extractEncodedSafId(treeUri, marker = "/tree/")

private fun extractEncodedSafId(uri: String, marker: String): String? {
  val sanitized = uri.substringBefore('?').substringBefore('#')
  val markerIndex = sanitized.indexOf(marker)
  if (markerIndex < 0) {
    return null
  }

  val encodedValue = sanitized
    .substring(markerIndex + marker.length)
    .substringBefore('/')
    .takeIf { it.isNotBlank() }
    ?: return null

  return runCatching {
    URLDecoder.decode(encodedValue, StandardCharsets.UTF_8.name())
  }.getOrNull()
}

private fun normalizeRelativePathOrNull(relativePath: String): String? {
  val normalized = relativePath.trim().replace('\\', '/')
  if (normalized.isEmpty()) {
    return ""
  }
  if (normalized.startsWith('/') || hasWindowsDrivePrefix(normalized)) {
    return null
  }

  val segments = mutableListOf<String>()
  for (segment in normalized.split('/')) {
    when (segment) {
      "", "." -> Unit
      ".." -> return null
      else -> segments += segment
    }
  }

  return segments.joinToString(separator = "/")
}

private fun normalizeDocumentIdOrNull(documentId: String): String? {
  val trimmed = documentId.trim()
  if (trimmed.isEmpty()) {
    return null
  }

  val separatorIndex = trimmed.indexOf(':')
  val volumeId = if (separatorIndex >= 0) trimmed.substring(0, separatorIndex) else ""
  val rawPath = if (separatorIndex >= 0) trimmed.substring(separatorIndex + 1) else trimmed
  val normalizedPath = normalizeDocumentPathOrNull(rawPath) ?: return null

  if (volumeId.isEmpty() && normalizedPath.isEmpty()) {
    return null
  }

  return when {
    volumeId.isEmpty() -> normalizedPath
    normalizedPath.isEmpty() -> "$volumeId:"
    else -> "$volumeId:$normalizedPath"
  }
}

private fun normalizeDocumentPathOrNull(documentPath: String): String? {
  val normalized = documentPath.trim().replace('\\', '/').trimStart('/')
  if (normalized.isEmpty()) {
    return ""
  }

  val segments = mutableListOf<String>()
  for (segment in normalized.split('/')) {
    when (segment) {
      "", "." -> Unit
      ".." -> return null
      else -> segments += segment
    }
  }

  return segments.joinToString(separator = "/")
}

private fun hasWindowsDrivePrefix(value: String): Boolean =
  value.length >= 2 && value[0].isLetter() && value[1] == ':'
