package com.opencray.app.agent

import com.opencray.app.WorkspaceSoulDocument
import com.opencray.app.WorkspaceSoulProfile
import com.opencray.app.WorkspaceSoulProfileStore
import com.opencray.runtime.OpenCraySoulVisualIdentity

internal class AgentSoulProfileStore(
  private val pathResolver: AgentPathResolver,
  private val delegate: WorkspaceSoulProfileStore = WorkspaceSoulProfileStore(),
) {
  fun loadSoulDocument(agentId: String): WorkspaceSoulDocument? =
    delegate.loadSoulDocumentFile(
      soulFile = pathResolver.resolve(agentId).privateSoulFile,
      relativePath = "${AgentPathResolver.PRIVATE_DIRECTORY_NAME}/${AgentPathResolver.PRIVATE_SOUL_FILE_NAME}",
    )

  fun loadSoulProfile(agentId: String): WorkspaceSoulProfile? =
    delegate.loadSoulProfileFile(pathResolver.resolve(agentId).privateSoulFile)

  fun loadSoulVisualIdentity(agentId: String): OpenCraySoulVisualIdentity? =
    delegate.loadSoulVisualIdentityFile(pathResolver.resolve(agentId).privateSoulFile)

  fun saveSoulProfile(
    agentId: String,
    profile: WorkspaceSoulProfile,
  ) {
    delegate.saveSoulProfileFile(
      soulFile = pathResolver.resolve(agentId).privateSoulFile,
      profile = profile,
    )
  }

  fun saveSoulVisualIdentity(
    agentId: String,
    visualIdentity: OpenCraySoulVisualIdentity?,
  ) {
    delegate.saveSoulVisualIdentityFile(
      soulFile = pathResolver.resolve(agentId).privateSoulFile,
      visualIdentity = visualIdentity,
    )
  }

  fun clearSoulProfile(agentId: String): Boolean =
    delegate.clearSoulProfileFile(pathResolver.resolve(agentId).privateSoulFile)
}
