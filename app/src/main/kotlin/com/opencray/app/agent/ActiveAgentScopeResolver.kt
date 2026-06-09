package com.opencray.app.agent

import android.content.Context

internal data class ActiveAgentScope(
  val descriptor: AgentDescriptor,
  val config: AgentConfig?,
  val storagePaths: AgentStoragePaths,
)

internal class ActiveAgentScopeResolver(
  private val registryStore: AgentRegistryStore,
  private val pathResolver: AgentPathResolver,
  private val configStore: AgentConfigStore = AgentConfigStore(pathResolver),
) {
  fun loadActiveScope(): ActiveAgentScope? {
    val descriptor = registryStore.loadActiveAgent() ?: return null
    return loadScope(descriptor.agentId)
  }

  fun loadScope(agentId: String): ActiveAgentScope? {
    val descriptor = registryStore.loadAgent(agentId) ?: return null
    return ActiveAgentScope(
      descriptor = descriptor,
      config = configStore.load(descriptor.agentId),
      storagePaths = pathResolver.resolve(descriptor.agentId),
    )
  }

  companion object {
    fun fromContext(context: Context): ActiveAgentScopeResolver {
      val pathResolver = AgentPathResolver.fromContext(context)
      val registryStore = AgentRegistryStore(pathResolver.registryDirectory().toFile())
      return ActiveAgentScopeResolver(
        registryStore = registryStore,
        pathResolver = pathResolver,
        configStore = AgentConfigStore(pathResolver),
      )
    }
  }
}
