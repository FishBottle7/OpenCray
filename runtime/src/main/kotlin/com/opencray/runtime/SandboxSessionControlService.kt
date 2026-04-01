package com.opencray.runtime

import java.nio.file.Path

data class SandboxSessionCloseRequest(
  val workspaceRoot: Path,
)

enum class SandboxSessionCloseOutcome(
  val wireValue: String,
) {
  TERMINATED("terminated"),
  NOT_FOUND("not_found"),
  BUSY("busy"),
}

data class SandboxSessionCloseResult(
  val providerId: String,
  val outcome: SandboxSessionCloseOutcome,
  val sandboxId: String? = null,
  val sandboxDomain: String? = null,
  val previewCandidatePorts: List<Int> = emptyList(),
  val blockingRequestId: String? = null,
)

fun interface SandboxSessionControlService {
  fun close(request: SandboxSessionCloseRequest): SandboxSessionCloseResult
}
