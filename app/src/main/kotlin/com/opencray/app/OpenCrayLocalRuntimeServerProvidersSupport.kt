package com.opencray.app

internal fun openCrayLocalRuntimeServerProviders(
  localGatewayProvider: () -> OpenCrayLocalHostGateway,
  shellGatewayProvider: () -> OpenCrayShellGateway,
  chatRuntimeGatewayProvider: () -> OpenCrayChatRuntimeGateway,
  skillsGatewayProvider: () -> OpenCraySkillsGateway,
  settingsGatewayProvider: () -> OpenCraySettingsGateway,
): OpenCrayLocalRuntimeServerProviders = OpenCrayLocalRuntimeServerProviders(
  localGatewayProvider = localGatewayProvider,
  shellGatewayProvider = shellGatewayProvider,
  chatRuntimeGatewayProvider = chatRuntimeGatewayProvider,
  skillsGatewayProvider = skillsGatewayProvider,
  settingsGatewayProvider = settingsGatewayProvider,
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
): OpenCrayLocalRuntimeServerProviders = openCrayLocalRuntimeServerProviders(
  localGatewayProvider = localGatewayProvider,
  shellGatewayProvider = { gatewayBundle.shellGateway },
  chatRuntimeGatewayProvider = { gatewayBundle.chatRuntimeGateway },
  skillsGatewayProvider = { gatewayBundle.skillsGateway },
  settingsGatewayProvider = { gatewayBundle.settingsGateway },
)
