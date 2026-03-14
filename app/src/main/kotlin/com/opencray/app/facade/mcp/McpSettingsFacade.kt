package com.opencray.app.facade.mcp

import android.content.Context
import com.opencray.app.AppMcpRegistryStore
import com.opencray.app.McpSettingsStore
import com.opencray.app.OpenCrayLocaleManager
import com.opencray.core.contracts.McpServerSpec
import com.opencray.core.contracts.McpServerTrustState
import com.opencray.core.contracts.McpTransportDescriptor
import com.opencray.mcp.McpBlockedClientDescriptor
import com.opencray.mcp.McpClientAuthDescriptor
import com.opencray.mcp.McpClientBlockReason
import com.opencray.mcp.McpClientDescriptor
import com.opencray.mcp.McpClientExposureReport
import com.opencray.mcp.McpClientFactory
import com.opencray.mcp.McpClientTransportDescriptor
import com.opencray.mcp.McpRegistry
import com.opencray.mcp.McpRegistryRecord
import com.opencray.mcp.McpRegistryServerRecord
import com.opencray.mcp.McpRegistryStore
import com.opencray.mcp.McpServerAuthState
import com.opencray.mcp.McpServerAuthStatus
import com.opencray.mcp.McpToolExposure
import com.opencray.persistence.security.CredentialRef
import org.opencray.app.R

data class McpServerSettingsSnapshot(
  val id: String,
  val title: String,
  val statusLabel: String,
  val statusTone: String,
  val trustLine: String,
  val authLine: String,
  val readinessLine: String,
  val transportLine: String,
  val exposureLine: String,
  val guidance: String,
  val actionLabel: String,
  val actionTurnsOn: Boolean,
  val isActionEnabled: Boolean,
)

data class McpSettingsSnapshot(
  val title: String,
  val subtitle: String,
  val masterTitle: String,
  val masterSummary: String,
  val masterEnabled: Boolean,
  val summaryLine: String,
  val serversTitle: String,
  val serversHelper: String,
  val masterDisabledTitle: String?,
  val masterDisabledBody: String?,
  val servers: List<McpServerSettingsSnapshot>,
)

interface McpSettingsFacade {
  fun load(): McpSettingsSnapshot

  fun setMasterEnabled(enabled: Boolean): McpSettingsSnapshot

  fun setServerEnabled(serverId: String, enabled: Boolean): McpSettingsSnapshot

  fun currentExposureReport(): McpClientExposureReport
}

internal object EmptyMcpSettingsFacade : McpSettingsFacade {
  override fun load(): McpSettingsSnapshot = McpSettingsSnapshot(
    title = "",
    subtitle = "",
    masterTitle = "",
    masterSummary = "",
    masterEnabled = false,
    summaryLine = "",
    serversTitle = "",
    serversHelper = "",
    masterDisabledTitle = null,
    masterDisabledBody = null,
    servers = emptyList(),
  )

  override fun setMasterEnabled(enabled: Boolean): McpSettingsSnapshot = load()

  override fun setServerEnabled(serverId: String, enabled: Boolean): McpSettingsSnapshot = load()

  override fun currentExposureReport(): McpClientExposureReport = McpClientExposureReport(
    activeClients = emptyList(),
    blockedClients = emptyList(),
  )
}

internal class LocalMcpSettingsFacade private constructor(
  private val context: Context,
  private val settingsStore: McpSettingsStore,
  private val registryStore: McpRegistryStore,
  private val clientFactory: McpClientFactory,
  private val nowEpochMs: () -> Long,
) : McpSettingsFacade {
  override fun load(): McpSettingsSnapshot = snapshot()

  override fun setMasterEnabled(enabled: Boolean): McpSettingsSnapshot {
    settingsStore.saveMasterEnabled(enabled)
    return snapshot()
  }

  override fun setServerEnabled(serverId: String, enabled: Boolean): McpSettingsSnapshot {
    val registry = registry()
    val record = requireNotNull(registry.get(serverId)) {
      "Unknown MCP server '$serverId'."
    }
    when {
      enabled && record.trustState == McpServerTrustState.REQUIRES_MANUAL_ENABLE ->
        registry.manualEnable(serverId)

      enabled -> registry.enable(serverId)
      else -> registry.disable(serverId)
    }
    return snapshot()
  }

  override fun currentExposureReport(): McpClientExposureReport {
    val rawReport = clientFactory.load(registry())
    return effectiveReport(rawReport, masterEnabled())
  }

  private fun snapshot(): McpSettingsSnapshot {
    val masterEnabled = masterEnabled()
    val rawReport = clientFactory.load(registry())
    val effectiveReport = effectiveReport(rawReport, masterEnabled)
    val serverSnapshots = buildServerSnapshots(
      rawReport = rawReport,
      effectiveReport = effectiveReport,
    )
    val attentionCount = attentionCount(rawReport)
    return McpSettingsSnapshot(
      title = context.getString(R.string.settings_card_mcp),
      subtitle = context.getString(R.string.settings_mcp_subtitle),
      masterTitle = context.getString(R.string.mcp_home_master_title),
      masterSummary = context.getString(R.string.mcp_home_master_summary),
      masterEnabled = masterEnabled,
      summaryLine = context.getString(
        R.string.mcp_summary_line,
        effectiveReport.activeClients.size,
        effectiveReport.blockedClients.size,
        attentionCount,
      ),
      serversTitle = context.getString(R.string.mcp_settings_guidance_title),
      serversHelper = context.getString(R.string.mcp_settings_guidance_body),
      masterDisabledTitle = if (masterEnabled) null else context.getString(R.string.mcp_master_disabled_title),
      masterDisabledBody = if (masterEnabled) null else context.getString(R.string.mcp_master_disabled_body),
      servers = serverSnapshots,
    )
  }

  private fun buildServerSnapshots(
    rawReport: McpClientExposureReport,
    effectiveReport: McpClientExposureReport,
  ): List<McpServerSettingsSnapshot> {
    val blockedById = effectiveReport.blockedClients.associateBy(McpBlockedClientDescriptor::id)
    val activeById = effectiveReport.activeClients.associateBy(McpClientDescriptor::id)
    val rawBlockedById = rawReport.blockedClients.associateBy(McpBlockedClientDescriptor::id)
    val rawActiveById = rawReport.activeClients.associateBy(McpClientDescriptor::id)
    val serverIds = (rawReport.activeClients.map(McpClientDescriptor::id) +
      rawReport.blockedClients.map(McpBlockedClientDescriptor::id)).distinct().sorted()

    return serverIds.map { serverId ->
      val effectiveActive = activeById[serverId]
      val effectiveBlocked = blockedById[serverId]
      val baseActive = rawActiveById[serverId]
      val baseBlocked = rawBlockedById[serverId]
      val auth = effectiveActive?.auth ?: effectiveBlocked?.auth ?: baseActive?.auth ?: baseBlocked?.auth
        ?: McpClientAuthDescriptor(
          status = McpServerAuthStatus.NOT_REQUIRED,
          isReady = true,
        )
      val displayName = effectiveActive?.displayName
        ?: effectiveBlocked?.displayName
        ?: baseActive?.displayName
        ?: requireNotNull(baseBlocked?.displayName)
      val trustState = effectiveActive?.trustState
        ?: effectiveBlocked?.trustState
        ?: baseActive?.trustState
        ?: requireNotNull(baseBlocked?.trustState)
      val manuallyEnabled = effectiveActive?.manuallyEnabled
        ?: effectiveBlocked?.manuallyEnabled
        ?: baseActive?.manuallyEnabled
        ?: requireNotNull(baseBlocked?.manuallyEnabled)
      val transport = effectiveActive?.transport
        ?: effectiveBlocked?.transport
        ?: baseActive?.transport
        ?: requireNotNull(baseBlocked?.transport)
      val toolExposure = effectiveActive?.toolExposure ?: effectiveBlocked?.toolExposure ?: McpToolExposure.BLOCKED
      val blockReason = effectiveBlocked?.blockReason
      val rawTrustState = baseActive?.trustState ?: baseBlocked?.trustState ?: trustState
      val actionTurnsOn = rawTrustState != McpServerTrustState.ENABLED
      val actionLabel = if (!actionTurnsOn) {
        context.getString(R.string.mcp_action_disable_server)
      } else {
        context.getString(R.string.mcp_action_enable_server)
      }
      McpServerSettingsSnapshot(
        id = serverId,
        title = displayName,
        statusLabel = context.getString(
          if (effectiveActive != null) {
            R.string.mcp_status_active
          } else {
            R.string.mcp_status_blocked
          },
        ),
        statusTone = if (effectiveActive != null) "active" else "blocked",
        trustLine = trustLine(
          trustState = trustState,
          manuallyEnabled = manuallyEnabled,
        ),
        authLine = context.getString(
          R.string.mcp_auth_line,
          authLabel(auth),
        ),
        readinessLine = context.getString(
          R.string.mcp_readiness_line,
          context.getString(
            if (auth.isReady) {
              R.string.mcp_readiness_ready
            } else {
              R.string.mcp_readiness_needs_attention
            },
          ),
        ),
        transportLine = transportLabel(transport),
        exposureLine = context.getString(
          if (toolExposure == McpToolExposure.ACTIVE) {
            R.string.mcp_exposure_active
          } else {
            R.string.mcp_exposure_blocked
          },
        ),
        guidance = guidanceFor(
          activeClient = effectiveActive,
          auth = auth,
          blockReason = blockReason,
        ),
        actionLabel = actionLabel,
        actionTurnsOn = actionTurnsOn,
        isActionEnabled = true,
      )
    }
  }

  private fun guidanceFor(
    activeClient: McpClientDescriptor?,
    auth: McpClientAuthDescriptor,
    blockReason: McpClientBlockReason?,
  ): String = when {
    activeClient != null && auth.isReady ->
      context.getString(R.string.mcp_guidance_active_ready)

    activeClient != null ->
      context.getString(R.string.mcp_guidance_active_needs_auth)

    blockReason == McpClientBlockReason.REQUIRES_MANUAL_ENABLE ->
      context.getString(R.string.mcp_guidance_blocked_manual)

    auth.isReady ->
      context.getString(R.string.mcp_guidance_blocked_disabled_ready)

    else ->
      context.getString(R.string.mcp_guidance_blocked_disabled_needs_auth)
  }

  private fun masterEnabled(): Boolean = settingsStore.loadMasterEnabled(defaultValue = true)

  private fun registry(): McpRegistry {
    ensureSeeded()
    return McpRegistry(registryStore, nowEpochMs)
  }

  private fun ensureSeeded() {
    val existing = registryStore.load()
    val seededServers = seedRecords(
      createdAtEpochMs = existing?.createdAtEpochMs ?: nowEpochMs(),
    )
    val mergedServers = (seededServers + existing?.servers.orEmpty())
      .associateBy(McpRegistryServerRecord::id)
      .values
      .sortedBy(McpRegistryServerRecord::id)
    if (existing != null && mergedServers.size == existing.servers.size) {
      return
    }
    val now = nowEpochMs()
    registryStore.save(
      McpRegistryRecord(
        servers = mergedServers,
        createdAtEpochMs = existing?.createdAtEpochMs ?: now,
        updatedAtEpochMs = now,
        recordVersion = (existing?.recordVersion ?: 0L) + 1L,
        termuxMetadata = existing?.termuxMetadata.orEmpty(),
        extensions = existing?.extensions.orEmpty(),
      ),
    )
  }

  private fun seedRecords(createdAtEpochMs: Long): List<McpRegistryServerRecord> = listOf(
    McpRegistryServerRecord(
      spec = McpServerSpec(
        id = "assistant-local",
        displayName = context.getString(R.string.mcp_server_name_assistant_local),
        transport = McpTransportDescriptor.LocalStdio(
          command = "opencray-mcp",
          args = listOf("assistant-local"),
          workingDirectory = context.filesDir.absolutePath,
        ),
        trustState = McpServerTrustState.ENABLED,
      ),
      registeredAtEpochMs = createdAtEpochMs,
      updatedAtEpochMs = createdAtEpochMs,
    ),
    McpRegistryServerRecord(
      spec = McpServerSpec(
        id = "docs-proxy",
        displayName = context.getString(R.string.mcp_server_name_docs_proxy),
        transport = McpTransportDescriptor.RemoteHttp(
          url = "https://docs.opencray.local/mcp",
        ),
        trustState = McpServerTrustState.ENABLED,
      ),
      authState = McpServerAuthState.missing(),
      registeredAtEpochMs = createdAtEpochMs + 1L,
      updatedAtEpochMs = createdAtEpochMs + 1L,
    ),
    McpRegistryServerRecord(
      spec = McpServerSpec(
        id = "community-bridge",
        displayName = context.getString(R.string.mcp_server_name_community_bridge),
        transport = McpTransportDescriptor.RemoteSse(
          eventsUrl = "https://community.opencray.local/mcp/events",
          postUrl = "https://community.opencray.local/mcp",
        ),
        trustState = McpServerTrustState.REQUIRES_MANUAL_ENABLE,
      ),
      authState = McpServerAuthState.configured(
        credentialRef = CredentialRef("secret://mcp/community-bridge-token"),
      ),
      registeredAtEpochMs = createdAtEpochMs + 2L,
      updatedAtEpochMs = createdAtEpochMs + 2L,
    ),
  )

  private fun attentionCount(report: McpClientExposureReport): Int {
    val activeAttention = report.activeClients.count { client ->
      !client.auth.isReady || client.trustState == McpServerTrustState.REQUIRES_MANUAL_ENABLE
    }
    val blockedAttention = report.blockedClients.count { client ->
      !client.auth.isReady || client.trustState == McpServerTrustState.REQUIRES_MANUAL_ENABLE
    }
    return activeAttention + blockedAttention
  }

  private fun effectiveReport(
    rawReport: McpClientExposureReport,
    masterEnabled: Boolean,
  ): McpClientExposureReport {
    if (masterEnabled) {
      return rawReport
    }
    return McpClientExposureReport(
      activeClients = emptyList(),
      blockedClients = rawReport.blockedClients + rawReport.activeClients.map { client ->
        McpBlockedClientDescriptor(
          id = client.id,
          displayName = client.displayName,
          transport = client.transport,
          auth = client.auth,
          declaredTrustState = client.declaredTrustState,
          trustState = client.trustState,
          manuallyEnabled = client.manuallyEnabled,
          blockReason = McpClientBlockReason.DISABLED,
          toolExposure = McpToolExposure.BLOCKED,
          registeredAtEpochMs = client.registeredAtEpochMs,
          updatedAtEpochMs = client.updatedAtEpochMs,
        )
      },
    )
  }

  private fun trustLine(
    trustState: McpServerTrustState,
    manuallyEnabled: Boolean,
  ): String {
    val trustLabel = context.getString(
      when (trustState) {
        McpServerTrustState.ENABLED -> R.string.mcp_trust_state_enabled
        McpServerTrustState.DISABLED -> R.string.mcp_trust_state_disabled
        McpServerTrustState.REQUIRES_MANUAL_ENABLE ->
          R.string.mcp_trust_state_requires_manual_enable
      },
    )
    return if (manuallyEnabled) {
      context.getString(
        R.string.mcp_trust_line_with_manual,
        trustLabel,
        context.getString(R.string.mcp_manual_consent_saved),
      )
    } else {
      context.getString(R.string.mcp_trust_line, trustLabel)
    }
  }

  private fun authLabel(auth: McpClientAuthDescriptor): String = context.getString(
    when (auth.status) {
      McpServerAuthStatus.NOT_REQUIRED -> R.string.mcp_auth_status_not_required
      McpServerAuthStatus.CONFIGURED -> R.string.mcp_auth_status_configured
      McpServerAuthStatus.MISSING -> R.string.mcp_auth_status_missing
      McpServerAuthStatus.ERROR -> R.string.mcp_auth_status_error
    },
  )

  private fun transportLabel(transport: McpClientTransportDescriptor): String = context.getString(
    when (transport) {
      is McpClientTransportDescriptor.LocalStdio -> R.string.mcp_transport_local_stdio
      is McpClientTransportDescriptor.RemoteHttp -> R.string.mcp_transport_remote_http
      is McpClientTransportDescriptor.RemoteSse -> R.string.mcp_transport_remote_sse
    },
  )

  companion object {
    fun fromContext(context: Context): McpSettingsFacade = LocalMcpSettingsFacade(
      context = OpenCrayLocaleManager.wrap(context.applicationContext),
      settingsStore = McpSettingsStore.fromContext(context.applicationContext),
      registryStore = AppMcpRegistryStore.fromContext(context.applicationContext),
      clientFactory = McpClientFactory(),
      nowEpochMs = System::currentTimeMillis,
    )

    internal fun createForTest(
      context: Context,
      settingsStore: McpSettingsStore,
      registryStore: McpRegistryStore,
      clientFactory: McpClientFactory = McpClientFactory(),
      nowEpochMs: () -> Long = System::currentTimeMillis,
    ): McpSettingsFacade = LocalMcpSettingsFacade(
      context = context,
      settingsStore = settingsStore,
      registryStore = registryStore,
      clientFactory = clientFactory,
      nowEpochMs = nowEpochMs,
    )
  }
}
