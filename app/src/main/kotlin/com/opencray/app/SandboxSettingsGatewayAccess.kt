package com.opencray.app

internal interface SandboxSettingsGatewayAccess {
  fun load(): ResolvedSandboxSettings

  fun save(
    state: SandboxSettingsState,
    e2bApiKey: String?,
  ): ResolvedSandboxSettings
}

internal class RepositoryBackedSandboxSettingsGatewayAccess(
  private val repository: SandboxSettingsRepository,
) : SandboxSettingsGatewayAccess {
  override fun load(): ResolvedSandboxSettings = repository.load()

  override fun save(
    state: SandboxSettingsState,
    e2bApiKey: String?,
  ): ResolvedSandboxSettings = repository.save(
    state = state,
    e2bApiKey = e2bApiKey,
  )
}
