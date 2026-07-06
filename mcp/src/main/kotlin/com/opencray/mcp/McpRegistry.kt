package com.opencray.mcp

import com.opencray.core.contracts.McpAuthSpec
import com.opencray.core.contracts.McpServerSpec
import com.opencray.core.contracts.McpServerTrustState
import com.opencray.persistence.PersistenceMigrationVersion
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.TermuxMetadataSchemaVersion
import com.opencray.persistence.model.VersionedRecord
import com.opencray.persistence.security.CredentialRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val DEFAULT_AUTH_HEADER_NAME = "Authorization"

@Serializable
enum class McpServerAuthStatus {
  @SerialName("not_required") NOT_REQUIRED,
  @SerialName("configured") CONFIGURED,
  @SerialName("missing") MISSING,
  @SerialName("error") ERROR,
}

@Serializable
data class McpServerAuthState(
  val status: McpServerAuthStatus,
  val credentialRef: CredentialRef? = null,
  val headerName: String? = null,
  val errorCode: String? = null,
) {
  init {
    when (status) {
      McpServerAuthStatus.NOT_REQUIRED -> {
        require(credentialRef == null) {
          "McpServerAuthState credentialRef must be null when auth is not required."
        }
        require(headerName == null) {
          "McpServerAuthState headerName must be null when auth is not required."
        }
      }

      McpServerAuthStatus.CONFIGURED -> {
        require(credentialRef != null) {
          "McpServerAuthState credentialRef is required when auth is configured."
        }
        require(!headerName.isNullOrBlank()) {
          "McpServerAuthState headerName is required when auth is configured."
        }
        require(errorCode == null) {
          "McpServerAuthState errorCode must be null when auth is configured."
        }
      }

      McpServerAuthStatus.MISSING -> {
        require(credentialRef == null) {
          "McpServerAuthState credentialRef must be null when auth is missing."
        }
        require(!headerName.isNullOrBlank()) {
          "McpServerAuthState headerName is required when auth is missing."
        }
      }

      McpServerAuthStatus.ERROR -> {
        require(!errorCode.isNullOrBlank()) {
          "McpServerAuthState errorCode is required when auth is in error."
        }
        require(headerName == null || headerName.isNotBlank()) {
          "McpServerAuthState headerName must not be blank when provided."
        }
      }
    }
  }

  companion object {
    fun fromSpec(auth: McpAuthSpec?): McpServerAuthState = when (auth) {
      null -> McpServerAuthState(status = McpServerAuthStatus.NOT_REQUIRED)
      else -> McpServerAuthState(
        status = McpServerAuthStatus.CONFIGURED,
        credentialRef = CredentialRef(auth.credentialRef),
        headerName = auth.headerName,
      )
    }

    fun configured(
      credentialRef: CredentialRef,
      headerName: String = DEFAULT_AUTH_HEADER_NAME,
    ): McpServerAuthState = McpServerAuthState(
      status = McpServerAuthStatus.CONFIGURED,
      credentialRef = credentialRef,
      headerName = headerName,
    )

    fun missing(headerName: String = DEFAULT_AUTH_HEADER_NAME): McpServerAuthState = McpServerAuthState(
      status = McpServerAuthStatus.MISSING,
      headerName = headerName,
    )

    fun error(
      code: String,
      credentialRef: CredentialRef? = null,
      headerName: String? = null,
    ): McpServerAuthState = McpServerAuthState(
      status = McpServerAuthStatus.ERROR,
      credentialRef = credentialRef,
      headerName = headerName,
      errorCode = code,
    )
  }
}

@Serializable
data class McpRegistryServerRecord(
  val spec: McpServerSpec,
  val declaredTrustState: McpServerTrustState = spec.trustState,
  val trustState: McpServerTrustState = initialTrustState(spec.trustState),
  val manuallyEnabled: Boolean = false,
  val authState: McpServerAuthState = McpServerAuthState.fromSpec(spec.auth),
  val registeredAtEpochMs: Long,
  val updatedAtEpochMs: Long = registeredAtEpochMs,
) {
  val id: String
    get() = spec.id

  init {
    require(updatedAtEpochMs >= registeredAtEpochMs) {
      "McpRegistryServerRecord updatedAtEpochMs must be >= registeredAtEpochMs."
    }
    require(authState.usesOnlySecureReferences()) {
      "McpRegistryServerRecord authState must persist only secure references and metadata."
    }
    require(
      trustState != McpServerTrustState.ENABLED ||
        declaredTrustState == McpServerTrustState.ENABLED ||
        manuallyEnabled,
    ) {
      "McpRegistryServerRecord must not silently enable servers that still require manual enable."
    }
  }

  fun enable(now: Long): McpRegistryServerRecord {
    require(canEnable()) {
      "MCP server '$id' is blocked until it is manually enabled."
    }
    return copy(
      trustState = McpServerTrustState.ENABLED,
      updatedAtEpochMs = now,
    )
  }

  fun disable(now: Long): McpRegistryServerRecord = copy(
    trustState = McpServerTrustState.DISABLED,
    updatedAtEpochMs = now,
  )

  fun manualEnable(now: Long): McpRegistryServerRecord = copy(
    trustState = McpServerTrustState.ENABLED,
    manuallyEnabled = true,
    updatedAtEpochMs = now,
  )

  fun refreshSpec(spec: McpServerSpec, now: Long): McpRegistryServerRecord = copy(
    spec = spec,
    declaredTrustState = spec.trustState,
    trustState = refreshedTrustState(spec.trustState),
    authState = McpServerAuthState.fromSpec(spec.auth),
    updatedAtEpochMs = now,
  )

  private fun canEnable(): Boolean =
    declaredTrustState == McpServerTrustState.ENABLED || manuallyEnabled

  private fun refreshedTrustState(nextDeclaredTrustState: McpServerTrustState): McpServerTrustState = when {
    manuallyEnabled -> trustState
    trustState == McpServerTrustState.DISABLED -> McpServerTrustState.DISABLED
    nextDeclaredTrustState == McpServerTrustState.ENABLED -> McpServerTrustState.ENABLED
    nextDeclaredTrustState == McpServerTrustState.DISABLED -> McpServerTrustState.DISABLED
    else -> McpServerTrustState.REQUIRES_MANUAL_ENABLE
  }

  private fun McpServerAuthState.usesOnlySecureReferences(): Boolean = when (status) {
    McpServerAuthStatus.NOT_REQUIRED -> credentialRef == null
    McpServerAuthStatus.CONFIGURED -> credentialRef != null
    McpServerAuthStatus.MISSING -> credentialRef == null
    McpServerAuthStatus.ERROR -> true
  }

  companion object {
    private fun initialTrustState(declaredTrustState: McpServerTrustState): McpServerTrustState = when (declaredTrustState) {
      McpServerTrustState.ENABLED -> McpServerTrustState.ENABLED
      McpServerTrustState.DISABLED -> McpServerTrustState.DISABLED
      McpServerTrustState.REQUIRES_MANUAL_ENABLE -> McpServerTrustState.REQUIRES_MANUAL_ENABLE
    }
  }
}

@Serializable
data class McpRegistryRecord(
  val servers: List<McpRegistryServerRecord> = emptyList(),
  override val recordVersion: Long = 1,
  override val createdAtEpochMs: Long,
  override val updatedAtEpochMs: Long = createdAtEpochMs,
  override val schemaVersion: Int = PersistenceSchemaVersion.CURRENT,
  override val migrationVersion: Int = PersistenceMigrationVersion.CURRENT,
  override val termuxMetadataVersion: Int = TermuxMetadataSchemaVersion.CURRENT,
  override val termuxMetadata: Map<String, String> = emptyMap(),
  override val extensions: Map<String, String> = emptyMap(),
) : VersionedRecord {
  init {
    require(recordVersion >= 1) { "McpRegistryRecord recordVersion must be >= 1." }
    require(updatedAtEpochMs >= createdAtEpochMs) {
      "McpRegistryRecord updatedAtEpochMs must be >= createdAtEpochMs."
    }
    require(servers.map { it.id }.distinct().size == servers.size) {
      "McpRegistryRecord server ids must be unique."
    }
  }
}

interface McpRegistryStore {
  fun load(): McpRegistryRecord?
  fun save(record: McpRegistryRecord)
  fun clear(): Boolean

  fun <T> update(
    transform: (McpRegistryRecord?) -> McpRegistryStoreUpdate<T>,
  ): T {
    val updated = transform(load())
    if (updated.write) {
      val record = updated.record
      if (record == null) {
        clear()
      } else {
        save(record)
      }
    }
    return updated.result
  }
}

data class McpRegistryStoreUpdate<T>(
  val record: McpRegistryRecord?,
  val result: T,
  val write: Boolean = true,
)

class InMemoryMcpRegistryStore(
  initialRecord: McpRegistryRecord? = null,
) : McpRegistryStore {
  private var record: McpRegistryRecord? = initialRecord

  override fun load(): McpRegistryRecord? = record

  override fun save(record: McpRegistryRecord) {
    this.record = record
  }

  override fun clear(): Boolean {
    val existed = record != null
    record = null
    return existed
  }
}

class McpRegistry(
  private val store: McpRegistryStore,
  private val now: () -> Long = { System.currentTimeMillis() },
) {
  private var record: McpRegistryRecord = loadRecord()

  fun snapshot(): McpRegistryRecord {
    record = loadRecord()
    return record
  }

  fun list(): List<McpRegistryServerRecord> = snapshot().servers

  fun get(id: String): McpRegistryServerRecord? =
    snapshot().servers.firstOrNull { it.id == id }

  fun add(spec: McpServerSpec): McpRegistryServerRecord {
    val timestamp = now()
    return updateRegistry(timestamp) { current ->
      val existing = current.servers.firstOrNull { it.id == spec.id }
      val nextServer = existing?.refreshSpec(spec, timestamp) ?: McpRegistryServerRecord(
        spec = spec,
        registeredAtEpochMs = timestamp,
        updatedAtEpochMs = timestamp,
      )
      val updatedServers = (current.servers.filterNot { it.id == nextServer.id } + nextServer)
        .sortedBy { it.id }
      current.copy(
        servers = updatedServers,
        updatedAtEpochMs = timestamp,
        recordVersion = current.recordVersion + 1,
      ) to nextServer
    }
  }

  fun remove(id: String): Boolean {
    val timestamp = now()
    return store.update { currentRecord ->
      val current = currentRecord ?: McpRegistryRecord(createdAtEpochMs = timestamp)
      val remaining = current.servers.filterNot { it.id == id }
      if (remaining.size == current.servers.size) {
        record = current
        return@update McpRegistryStoreUpdate(
          record = current,
          result = false,
          write = false,
        )
      }
      val nextRecord = current.copy(
        servers = remaining.sortedBy { it.id },
        updatedAtEpochMs = timestamp,
        recordVersion = current.recordVersion + 1,
      )
      record = nextRecord
      McpRegistryStoreUpdate(
        record = nextRecord,
        result = true,
      )
    }
  }

  fun enable(id: String): McpRegistryServerRecord = mutate(id) { server, timestamp ->
    server.enable(timestamp)
  }

  fun disable(id: String): McpRegistryServerRecord = mutate(id) { server, timestamp ->
    server.disable(timestamp)
  }

  fun manualEnable(id: String): McpRegistryServerRecord = mutate(id) { server, timestamp ->
    server.manualEnable(timestamp)
  }

  private fun mutate(
    id: String,
    transform: (McpRegistryServerRecord, Long) -> McpRegistryServerRecord,
  ): McpRegistryServerRecord {
    val timestamp = now()
    return updateRegistry(timestamp) { current ->
      val existing = current.servers.firstOrNull { it.id == id }
        ?: error("Unknown MCP server '$id'.")
      val updated = transform(existing, timestamp)
      val updatedServers = (current.servers.filterNot { it.id == updated.id } + updated)
        .sortedBy { it.id }
      current.copy(
        servers = updatedServers,
        updatedAtEpochMs = timestamp,
        recordVersion = current.recordVersion + 1,
      ) to updated
    }
  }

  private fun loadRecord(): McpRegistryRecord =
    store.load() ?: McpRegistryRecord(createdAtEpochMs = now())

  private fun <T> updateRegistry(
    timestamp: Long,
    transform: (McpRegistryRecord) -> Pair<McpRegistryRecord, T>,
  ): T = store.update { currentRecord ->
    val current = currentRecord ?: McpRegistryRecord(createdAtEpochMs = timestamp)
    val (nextRecord, result) = transform(current)
    record = nextRecord
    McpRegistryStoreUpdate(
      record = nextRecord,
      result = result,
    )
  }
}
