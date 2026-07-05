package com.opencray.app

internal fun openCrayLocalRuntimeServerProviders(
  localGatewayProvider: () -> OpenCrayLocalHostGateway,
  shellGatewayProvider: () -> OpenCrayShellGateway,
  chatRuntimeGatewayProvider: () -> OpenCrayChatRuntimeGateway,
  skillsGatewayProvider: () -> OpenCraySkillsGateway,
  settingsGatewayProvider: () -> OpenCraySettingsGateway,
  runtimeOwnerWriteGuard: () -> Boolean = { true },
): OpenCrayLocalRuntimeServerProviders = OpenCrayLocalRuntimeServerProviders(
  localGatewayProvider = localGatewayProvider,
  shellGatewayProvider = shellGatewayProvider,
  chatRuntimeGatewayProvider = chatRuntimeGatewayProvider,
  skillsGatewayProvider = skillsGatewayProvider,
  settingsGatewayProvider = settingsGatewayProvider,
  runtimeOwnerWriteGuard = runtimeOwnerWriteGuard,
)

internal fun openCrayLocalRuntimeServerProviders(
  gatewayBundle: OpenCrayClientGatewayBundle,
): OpenCrayLocalRuntimeServerProviders = openCrayLocalRuntimeServerProviders(
  localGatewayProvider = { gatewayBundle.localHostGateway },
  shellGatewayProvider = { gatewayBundle.shellGateway },
  chatRuntimeGatewayProvider = { gatewayBundle.chatRuntimeGateway },
  skillsGatewayProvider = { gatewayBundle.skillsGateway },
  settingsGatewayProvider = { gatewayBundle.settingsGateway },
)

internal fun openCrayLocalRuntimeServerProviders(
  localGatewayProvider: () -> OpenCrayLocalHostGateway,
  gatewayBundle: OpenCrayRuntimeServiceGatewayBundle,
  runtimeOwnerWriteGuard: () -> Boolean = { true },
): OpenCrayLocalRuntimeServerProviders = openCrayLocalRuntimeServerProviders(
  localGatewayProvider = localGatewayProvider,
  shellGatewayProvider = { gatewayBundle.shellGateway },
  chatRuntimeGatewayProvider = { gatewayBundle.chatRuntimeGateway },
  skillsGatewayProvider = { gatewayBundle.skillsGateway },
  settingsGatewayProvider = { gatewayBundle.settingsGateway },
  runtimeOwnerWriteGuard = runtimeOwnerWriteGuard,
)
