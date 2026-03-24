package com.opencray.app

import android.content.Context

internal class ServiceBackedOpenCraySkillsGateway(
  private val serviceClient: OpenCrayRuntimeServiceClient,
  private val fallbackGateway: OpenCraySkillsGateway,
) : OpenCraySkillsGateway {
  override fun loadSkillsSnapshot(query: String): Map<String, Any?> =
    currentReadGateway().loadSkillsSnapshot(query)

  override fun observeSkills(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    observeWithDynamicGateway(
      currentGateway = ::currentReadGateway,
      observeConnectionState = serviceClient::observeConnectionState,
      observe = { gateway, callback -> gateway.observeSkills(callback) },
      listener = listener,
    )

  override fun setSkillEnabled(skillId: String, enabled: Boolean) {
    currentWriteGateway("setSkillEnabled").setSkillEnabled(skillId, enabled)
  }

  override fun installSuggestedSkill(skillId: String): String =
    currentWriteGateway("installSuggestedSkill").installSuggestedSkill(skillId)

  override fun installSkillSource(
    sourceRef: String,
    selectedSkillName: String,
  ): String = currentWriteGateway("installSkillSource").installSkillSource(sourceRef, selectedSkillName)

  override fun installSkillSourceBatch(
    sourceRef: String,
    selectedSkillNames: List<String>,
  ): String = currentWriteGateway("installSkillSourceBatch")
    .installSkillSourceBatch(sourceRef, selectedSkillNames)

  override fun inspectSkillSource(sourceRef: String): Map<String, Any?> =
    currentWriteGateway("inspectSkillSource").inspectSkillSource(sourceRef)

  override fun deleteInstalledSkill(skillId: String): String =
    currentWriteGateway("deleteInstalledSkill").deleteInstalledSkill(skillId)

  override fun refreshSkills(): String =
    currentWriteGateway("refreshSkills").refreshSkills()

  override fun checkInstalledSkillUpdates(skillId: String): String =
    currentWriteGateway("checkInstalledSkillUpdates").checkInstalledSkillUpdates(skillId)

  override fun updateInstalledSkill(skillId: String): String =
    currentWriteGateway("updateInstalledSkill").updateInstalledSkill(skillId)

  override fun loadSkillInstructions(skillId: String): Map<String, Any?> =
    currentReadGateway().loadSkillInstructions(skillId)

  override fun activateSkillsInstallSource(sourceId: String): String =
    currentWriteGateway("activateSkillsInstallSource").activateSkillsInstallSource(sourceId)

  private fun currentReadGateway(): OpenCraySkillsGateway =
    serviceClient.loadSkillsGateway() ?: fallbackGateway

  private fun currentWriteGateway(operation: String): OpenCraySkillsGateway =
    requireBinderBackedGateway(
      surface = "Skills",
      operation = operation,
      gateway = serviceClient.loadSkillsGateway(),
      connectionState = serviceClient.loadConnectionState(),
    )
}

internal fun serviceBackedOpenCraySkillsGateway(
  context: Context,
): OpenCraySkillsGateway {
  val appContext = context.applicationContext
  val serviceClient = OpenCrayAgentRuntimeService.ensureClient(appContext)
  return ServiceBackedOpenCraySkillsGateway(
    serviceClient = serviceClient,
    fallbackGateway = projectionOnlyOpenCraySkillsGateway(
      context = appContext,
      connectionStateProvider = serviceClient::loadConnectionState,
    ),
  )
}

internal fun serviceBackedOpenCraySkillsGateway(
  context: Context,
  fallbackGateway: OpenCraySkillsGateway,
): OpenCraySkillsGateway = ServiceBackedOpenCraySkillsGateway(
  serviceClient = OpenCrayAgentRuntimeService.ensureClient(context.applicationContext),
  fallbackGateway = fallbackGateway,
)
