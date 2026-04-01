package com.opencray.runtime

import java.nio.file.Path

data class SandboxPreviewRequest(
  val workspaceRoot: Path,
  val port: Int? = null,
  val path: String? = null,
)

enum class SandboxPreviewProbeStatus(
  val wireValue: String,
) {
  READY("ready"),
  REACHABLE("reachable"),
  UNREACHABLE("unreachable"),
  SKIPPED("skipped"),
}

data class SandboxPreviewResult(
  val url: String,
  val providerId: String,
  val sandboxId: String? = null,
  val sandboxDomain: String? = null,
  val port: Int,
  val path: String? = null,
  val accessHeaderName: String? = null,
  val accessTokenConfigured: Boolean = false,
  val probeStatus: SandboxPreviewProbeStatus = SandboxPreviewProbeStatus.SKIPPED,
  val probeHttpStatusCode: Int? = null,
  val probeMessage: String? = null,
)

fun interface SandboxPreviewService {
  fun open(request: SandboxPreviewRequest): SandboxPreviewResult
}
