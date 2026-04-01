package com.opencray.app.agent

import android.content.Context

internal class AppAgentHostServices(
  private val registryStore: AgentRegistryStore,
  private val bootstrapService: AgentBootstrapService,
  private val configStore: AgentConfigStore,
) {
  fun listAgents(): List<Map<String, Any?>> {
    val activeAgentId = registryStore.activeAgentId()
    return registryStore.list().map { descriptor ->
      descriptor.toHostMap(
        config = configStore.load(descriptor.agentId),
        isActive = descriptor.agentId == activeAgentId,
      )
    }
  }

  fun loadActiveAgent(): Map<String, Any?>? {
    val descriptor = registryStore.loadActiveAgent() ?: return null
    return descriptor.toHostMap(
      config = configStore.load(descriptor.agentId),
      isActive = true,
    )
  }

  fun createAgent(payload: Map<String, Any?>): Map<String, Any?> {
    val result = bootstrapService.createAgent(parseAgentCreateRequestPayload(payload))
    return result.descriptor.toHostMap(
      config = result.config,
      isActive = registryStore.activeAgentId() == result.descriptor.agentId,
    )
  }

  fun selectAgent(agentId: String): Map<String, Any?>? {
    val record = registryStore.select(agentId)
    val activeAgentId = record.activeAgentId ?: return null
    val descriptor = record.agents.firstOrNull { candidate ->
      candidate.agentId == activeAgentId
    } ?: return null
    return descriptor.toHostMap(
      config = configStore.load(descriptor.agentId),
      isActive = true,
    )
  }

  companion object {
    fun fromContext(context: Context): AppAgentHostServices {
      val pathResolver = AgentPathResolver.fromContext(context)
      val registryStore = AgentRegistryStore(pathResolver.registryDirectory().toFile())
      return AppAgentHostServices(
        registryStore = registryStore,
        bootstrapService = AgentBootstrapService(
          pathResolver = pathResolver,
          registryStore = registryStore,
        ),
        configStore = AgentConfigStore(pathResolver),
      )
    }
  }
}
