package com.opencray.runtime

import java.nio.file.Path

data class SandboxSessionInfoRequest(
  val workspaceRoot: Path,
)

enum class SandboxSessionInfoSource(
  val wireValue: String,
) {
  NONE("none"),
  ACTIVE_MEMORY("active_memory"),
  PERSISTED("persisted"),
  ACTIVE_AND_PERSISTED("active_memory_and_persisted"),
}

enum class SandboxSessionLifecycleStatus(
  val wireValue: String,
) {
  NONE("none"),
  ACTIVE("active"),
  STALE("stale"),
  RECLAIMED("reclaimed"),
}

data class SandboxSessionInfoResult(
  val providerId: String,
  val source: SandboxSessionInfoSource,
  val sandboxId: String? = null,
  val sandboxDomain: String? = null,
  val templateId: String? = null,
  val workspaceRoot: String? = null,
  val updatedAtEpochMs: Long? = null,
  val previewCandidatePorts: List<Int> = emptyList(),
  val runningRequestIds: List<String> = emptyList(),
  val lifecycleStatus: SandboxSessionLifecycleStatus = if (
    source == SandboxSessionInfoSource.NONE
  ) {
    SandboxSessionLifecycleStatus.NONE
  } else {
    SandboxSessionLifecycleStatus.ACTIVE
  },
  val sessionLastActivityAtEpochMs: Long? = null,
  val sessionStaleAfterEpochMs: Long? = null,
  val sessionIsStale: Boolean = false,
  val recommendedRefreshAfterMs: Long? = null,
  val remoteWorkspaceRoot: String? = null,
  val lastPreviewUrl: String? = null,
  val lastPreviewPort: Int? = null,
  val lastPreviewPath: String? = null,
  val lastPreviewProbeStatus: String? = null,
  val lastPreviewProbeHttpStatusCode: Int? = null,
  val lastPreviewProbeMessage: String? = null,
  val lastPreviewOpenedAtEpochMs: Long? = null,
  val lastPreviewProbeObservedAtEpochMs: Long? = null,
  val lastPreviewProbeSource: String? = null,
  val previewAutoProbeAttempted: Boolean = false,
) {
  val sessionPresent: Boolean
    get() = source != SandboxSessionInfoSource.NONE
}

fun interface SandboxSessionInfoService {
  fun inspect(request: SandboxSessionInfoRequest): SandboxSessionInfoResult
}
