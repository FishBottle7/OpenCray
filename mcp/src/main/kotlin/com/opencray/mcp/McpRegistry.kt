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
}

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
  private var record: McpRegistryRecord = store.load() ?: McpRegistryRecord(createdAtEpochMs = now())

  fun snapshot(): McpRegistryRecord = record

  fun list(): List<McpRegistryServerRecord> = record.servers

  fun get(id: String): McpRegistryServerRecord? = record.servers.firstOrNull { it.id == id }

  fun add(spec: McpServerSpec): McpRegistryServerRecord {
    val timestamp = now()
    val existing = get(spec.id)
    val nextServer = existing?.refreshSpec(spec, timestamp) ?: McpRegistryServerRecord(
      spec = spec,
      registeredAtEpochMs = timestamp,
      updatedAtEpochMs = timestamp,
    )
    persist(server = nextServer, timestamp = timestamp)
    return nextServer
  }

  fun remove(id: String): Boolean {
    val remaining = record.servers.filterNot { it.id == id }
    if (remaining.size == record.servers.size) return false

    if (remaining.isEmpty()) {
      record = record.copy(
        servers = emptyList(),
        updatedAtEpochMs = now(),
        recordVersion = record.recordVersion + 1,
      )
      store.save(record)
      return true
    }

    record = record.copy(
      servers = remaining.sortedBy { it.id },
      updatedAtEpochMs = now(),
      recordVersion = record.recordVersion + 1,
    )
    store.save(record)
    return true
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
    val existing = get(id) ?: error("Unknown MCP server '$id'.")
    val timestamp = now()
    val updated = transform(existing, timestamp)
    persist(server = updated, timestamp = timestamp)
    return updated
  }

  private fun persist(
    server: McpRegistryServerRecord,
    timestamp: Long,
  ) {
    val updatedServers = (record.servers.filterNot { it.id == server.id } + server)
      .sortedBy { it.id }

    record = record.copy(
      servers = updatedServers,
      updatedAtEpochMs = timestamp,
      recordVersion = record.recordVersion + 1,
    )
    store.save(record)
  }
}
