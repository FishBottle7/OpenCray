package com.opencray.app

internal class ServiceBackedOpenCraySkillsGateway(
  private val serviceClient: OpenCrayRuntimeServiceClient,
  fallbackGateway: OpenCraySkillsGateway? = null,
  fallbackGatewayProvider: (() -> OpenCraySkillsGateway)? = null,
) : OpenCraySkillsGateway {
  private val resolvedFallbackGatewayProvider: () -> OpenCraySkillsGateway = cachedGatewayProvider(
    fallbackGatewayProvider ?: fallbackGateway?.let { gateway -> { gateway } }
      ?: error("Service-backed skills gateway requires a fallback gateway."),
  )

  override fun loadSkillsSnapshot(
    query: String,
    suggestedLimit: Int,
  ): Map<String, Any?> = currentLoadGateway().loadSkillsSnapshot(
    query = query,
    suggestedLimit = suggestedLimit,
  )

  override fun observeSkills(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    observeWithDynamicGateway(
      currentGateway = ::currentObservedGateway,
      observeConnectionState = serviceClient::observeConnectionState,
      observe = { gateway, callback -> gateway.observeSkills(callback) },
      listener = listener,
    )

  override fun setSkillEnabled(skillId: String, enabled: Boolean) {
    dispatchWriteCommand(
      operation = "setSkillEnabled",
      command = OpenCraySkillsWriteCommand.SetSkillEnabled(
        skillId = skillId,
        enabled = enabled,
      ),
    )
  }

  override fun installSuggestedSkill(skillId: String): String =
    dispatchWriteCommand(
      operation = "installSuggestedSkill",
      command = OpenCraySkillsWriteCommand.InstallSuggestedSkill(skillId),
    ).messageOrNull()

  override fun installSkillSource(
    sourceRef: String,
    selectedSkillName: String,
  ): String = dispatchWriteCommand(
    operation = "installSkillSource",
    command = OpenCraySkillsWriteCommand.InstallSkillSource(
      sourceRef = sourceRef,
      selectedSkillName = selectedSkillName,
    ),
  ).messageOrNull()

  override fun installSkillSourceBatch(
    sourceRef: String,
    selectedSkillNames: List<String>,
  ): String = dispatchWriteCommand(
    operation = "installSkillSourceBatch",
    command = OpenCraySkillsWriteCommand.InstallSkillSourceBatch(
      sourceRef = sourceRef,
      selectedSkillNames = selectedSkillNames,
    ),
  ).messageOrNull()

  override fun inspectSkillSource(sourceRef: String): Map<String, Any?> =
    dispatchWriteCommand(
      operation = "inspectSkillSource",
      command = OpenCraySkillsWriteCommand.InspectSkillSource(sourceRef),
    ).payloadOrNull()

  override fun deleteInstalledSkill(skillId: String): String =
    dispatchWriteCommand(
      operation = "deleteInstalledSkill",
      command = OpenCraySkillsWriteCommand.DeleteInstalledSkill(skillId),
    ).messageOrNull()

  override fun refreshSkills(): String =
    dispatchWriteCommand(
      operation = "refreshSkills",
      command = OpenCraySkillsWriteCommand.RefreshSkills,
    ).messageOrNull()

  override fun checkInstalledSkillUpdates(skillId: String): String =
    dispatchWriteCommand(
      operation = "checkInstalledSkillUpdates",
      command = OpenCraySkillsWriteCommand.CheckInstalledSkillUpdates(skillId),
    ).messageOrNull()

  override fun updateInstalledSkill(skillId: String): String =
    dispatchWriteCommand(
      operation = "updateInstalledSkill",
      command = OpenCraySkillsWriteCommand.UpdateInstalledSkill(skillId),
  ).messageOrNull()

  override fun loadSkillInstructions(skillId: String): Map<String, Any?> =
    currentLoadGateway().loadSkillInstructions(skillId)

  override fun loadSuggestedSkillInstructions(
    sourceRef: String,
    selectedSkillName: String,
  ): Map<String, Any?> = currentLoadGateway().loadSuggestedSkillInstructions(
    sourceRef = sourceRef,
    selectedSkillName = selectedSkillName,
  )

  override fun activateSkillsInstallSource(sourceId: String): String =
    dispatchWriteCommand(
      operation = "activateSkillsInstallSource",
      command = OpenCraySkillsWriteCommand.ActivateSkillsInstallSource(sourceId),
    ).messageOrNull()

  private fun currentLoadGateway(): OpenCraySkillsGateway =
    serviceClient.loadSkillsGateway() ?: resolvedFallbackGatewayProvider()

  private fun currentObservedGateway(): OpenCraySkillsGateway =
    serviceClient.peekSkillsGateway() ?: resolvedFallbackGatewayProvider()

  private fun dispatchWriteCommand(
    operation: String,
    command: OpenCraySkillsWriteCommand,
  ): OpenCraySkillsWriteDispatchResult =
    requireBinderBackedGateway(
      surface = "Skills",
      operation = operation,
      gateway = serviceClient.dispatchSkillsWriteCommand(command),
      connectionState = serviceClient.peekConnectionState(),
    )
}

private fun OpenCraySkillsWriteDispatchResult.messageOrNull(): String = when (this) {
  OpenCraySkillsWriteDispatchResult.Completed ->
    error("Skills operation completed without a message payload.")

  is OpenCraySkillsWriteDispatchResult.Message -> value
  is OpenCraySkillsWriteDispatchResult.Payload ->
    error("Skills operation returned an object payload where a message was expected.")
}

private fun OpenCraySkillsWriteDispatchResult.payloadOrNull(): Map<String, Any?> = when (this) {
  OpenCraySkillsWriteDispatchResult.Completed ->
    error("Skills operation completed without an object payload.")

  is OpenCraySkillsWriteDispatchResult.Message ->
    error("Skills operation returned a message payload where an object was expected.")

  is OpenCraySkillsWriteDispatchResult.Payload -> value
}

internal fun serviceBackedOpenCraySkillsGateway(
  serviceClient: OpenCrayRuntimeServiceClient,
  fallbackGateway: OpenCraySkillsGateway,
): OpenCraySkillsGateway = ServiceBackedOpenCraySkillsGateway(
  serviceClient = serviceClient,
  fallbackGateway = fallbackGateway,
)

internal fun serviceBackedOpenCraySkillsGateway(
  serviceClient: OpenCrayRuntimeServiceClient,
  fallbackGatewayProvider: () -> OpenCraySkillsGateway,
): OpenCraySkillsGateway = ServiceBackedOpenCraySkillsGateway(
  serviceClient = serviceClient,
  fallbackGatewayProvider = fallbackGatewayProvider,
)
