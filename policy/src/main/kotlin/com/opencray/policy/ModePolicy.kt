package com.opencray.policy

import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyApprovalRisk
import com.opencray.core.contracts.PolicyDecisionOutcome
import java.nio.file.Files
import java.nio.file.Path

enum class ExecutionMode {
  SAFE,
  AUTO,
  DEVELOPER,
  ;

  companion object {
    fun fromLabelOrNull(label: String?): ExecutionMode? {
      val normalized = label?.trim()
      if (normalized.isNullOrEmpty()) {
        return null
      }
      return when {
        normalized.equals(SafetyAutomationMode.DEV.chatMetadataLabel, ignoreCase = true) ->
          DEVELOPER

        else -> values().firstOrNull { mode ->
          mode.name.equals(normalized, ignoreCase = true)
        }
      }
    }
  }
}

enum class PolicyToolClass {
  READ_FILE,
  WRITE_FILE,
  DELETE_FILE,
  MOVE_FILE,
  RENAME_FILE,
  EXECUTE_COMMAND,
  NETWORK_ACCESS,
  ;

  fun requiresTargetPath(): Boolean = when (this) {
    READ_FILE,
    WRITE_FILE,
    DELETE_FILE,
    MOVE_FILE,
    RENAME_FILE,
    -> true

    EXECUTE_COMMAND,
    NETWORK_ACCESS,
    -> false
  }

  fun requiresDestinationPath(): Boolean = this == MOVE_FILE || this == RENAME_FILE

  fun isDestructiveFileMutation(): Boolean =
    this == DELETE_FILE || this == MOVE_FILE || this == RENAME_FILE
}

data class PolicyRequest(
  val mode: ExecutionMode,
  val toolClass: PolicyToolClass,
  val workspaceRoot: Path,
  val targetPath: Path? = null,
  val destinationPath: Path? = null,
)

object PolicyReasonCode {
  const val ALLOW_SAFE_READ = "ALLOW_SAFE_READ"
  const val ASK_SAFE_WRITE = "ASK_SAFE_WRITE"
  const val ASK_SAFE_DESTRUCTIVE_HIGH_RISK = "ASK_SAFE_DESTRUCTIVE_HIGH_RISK"
  const val ASK_SAFE_COMMAND_HIGH_RISK = "ASK_SAFE_COMMAND_HIGH_RISK"
  const val ASK_SAFE_NETWORK_HIGH_RISK = "ASK_SAFE_NETWORK_HIGH_RISK"

  const val ALLOW_AUTO_STANDARD = "ALLOW_AUTO_STANDARD"
  const val ASK_AUTO_DESTRUCTIVE = "ASK_AUTO_DESTRUCTIVE"
  const val ASK_AUTO_COMMAND = "ASK_AUTO_COMMAND"
  const val ASK_AUTO_NETWORK = "ASK_AUTO_NETWORK"

  const val ALLOW_DEVELOPER_OVERRIDE = "ALLOW_DEVELOPER_OVERRIDE"

  const val DENY_INVALID_PATH = "DENY_INVALID_PATH"
  const val DENY_PATH_TRAVERSAL = "DENY_PATH_TRAVERSAL"
  const val DENY_PATH_ESCAPE = "DENY_PATH_ESCAPE"
  const val DENY_PROTECTED_FILE = "DENY_PROTECTED_FILE"
}

class ModePolicy(
  private val protectedRegistry: ProtectedRegistry = ProtectedRegistry(),
) {
  private data class MatrixRule(
    val outcome: PolicyDecisionOutcome,
    val reasonCode: String,
    val approvalRisk: PolicyApprovalRisk = PolicyApprovalRisk.STANDARD,
  )

  private val matrix: Map<ExecutionMode, Map<PolicyToolClass, MatrixRule>> =
    mapOf(
      ExecutionMode.SAFE to mapOf(
        PolicyToolClass.READ_FILE to MatrixRule(
          outcome = PolicyDecisionOutcome.ALLOW,
          reasonCode = PolicyReasonCode.ALLOW_SAFE_READ,
        ),
        PolicyToolClass.WRITE_FILE to MatrixRule(
          outcome = PolicyDecisionOutcome.ASK,
          reasonCode = PolicyReasonCode.ASK_SAFE_WRITE,
        ),
        PolicyToolClass.DELETE_FILE to MatrixRule(
          outcome = PolicyDecisionOutcome.ASK,
          reasonCode = PolicyReasonCode.ASK_SAFE_DESTRUCTIVE_HIGH_RISK,
          approvalRisk = PolicyApprovalRisk.HIGH_RISK,
        ),
        PolicyToolClass.MOVE_FILE to MatrixRule(
          outcome = PolicyDecisionOutcome.ASK,
          reasonCode = PolicyReasonCode.ASK_SAFE_DESTRUCTIVE_HIGH_RISK,
          approvalRisk = PolicyApprovalRisk.HIGH_RISK,
        ),
        PolicyToolClass.RENAME_FILE to MatrixRule(
          outcome = PolicyDecisionOutcome.ASK,
          reasonCode = PolicyReasonCode.ASK_SAFE_DESTRUCTIVE_HIGH_RISK,
          approvalRisk = PolicyApprovalRisk.HIGH_RISK,
        ),
        PolicyToolClass.EXECUTE_COMMAND to MatrixRule(
          outcome = PolicyDecisionOutcome.ASK,
          reasonCode = PolicyReasonCode.ASK_SAFE_COMMAND_HIGH_RISK,
          approvalRisk = PolicyApprovalRisk.HIGH_RISK,
        ),
        PolicyToolClass.NETWORK_ACCESS to MatrixRule(
          outcome = PolicyDecisionOutcome.ASK,
          reasonCode = PolicyReasonCode.ASK_SAFE_NETWORK_HIGH_RISK,
          approvalRisk = PolicyApprovalRisk.HIGH_RISK,
        ),
      ),
      ExecutionMode.AUTO to mapOf(
        PolicyToolClass.READ_FILE to MatrixRule(
          outcome = PolicyDecisionOutcome.ALLOW,
          reasonCode = PolicyReasonCode.ALLOW_AUTO_STANDARD,
        ),
        PolicyToolClass.WRITE_FILE to MatrixRule(
          outcome = PolicyDecisionOutcome.ALLOW,
          reasonCode = PolicyReasonCode.ALLOW_AUTO_STANDARD,
        ),
        PolicyToolClass.DELETE_FILE to MatrixRule(
          outcome = PolicyDecisionOutcome.ASK,
          reasonCode = PolicyReasonCode.ASK_AUTO_DESTRUCTIVE,
        ),
        PolicyToolClass.MOVE_FILE to MatrixRule(
          outcome = PolicyDecisionOutcome.ASK,
          reasonCode = PolicyReasonCode.ASK_AUTO_DESTRUCTIVE,
        ),
        PolicyToolClass.RENAME_FILE to MatrixRule(
          outcome = PolicyDecisionOutcome.ASK,
          reasonCode = PolicyReasonCode.ASK_AUTO_DESTRUCTIVE,
        ),
        PolicyToolClass.EXECUTE_COMMAND to MatrixRule(
          outcome = PolicyDecisionOutcome.ASK,
          reasonCode = PolicyReasonCode.ASK_AUTO_COMMAND,
        ),
        PolicyToolClass.NETWORK_ACCESS to MatrixRule(
          outcome = PolicyDecisionOutcome.ASK,
          reasonCode = PolicyReasonCode.ASK_AUTO_NETWORK,
        ),
      ),
      ExecutionMode.DEVELOPER to mapOf(
        PolicyToolClass.READ_FILE to MatrixRule(
          outcome = PolicyDecisionOutcome.ALLOW,
          reasonCode = PolicyReasonCode.ALLOW_DEVELOPER_OVERRIDE,
        ),
        PolicyToolClass.WRITE_FILE to MatrixRule(
          outcome = PolicyDecisionOutcome.ALLOW,
          reasonCode = PolicyReasonCode.ALLOW_DEVELOPER_OVERRIDE,
        ),
        PolicyToolClass.DELETE_FILE to MatrixRule(
          outcome = PolicyDecisionOutcome.ALLOW,
          reasonCode = PolicyReasonCode.ALLOW_DEVELOPER_OVERRIDE,
        ),
        PolicyToolClass.MOVE_FILE to MatrixRule(
          outcome = PolicyDecisionOutcome.ALLOW,
          reasonCode = PolicyReasonCode.ALLOW_DEVELOPER_OVERRIDE,
        ),
        PolicyToolClass.RENAME_FILE to MatrixRule(
          outcome = PolicyDecisionOutcome.ALLOW,
          reasonCode = PolicyReasonCode.ALLOW_DEVELOPER_OVERRIDE,
        ),
        PolicyToolClass.EXECUTE_COMMAND to MatrixRule(
          outcome = PolicyDecisionOutcome.ALLOW,
          reasonCode = PolicyReasonCode.ALLOW_DEVELOPER_OVERRIDE,
        ),
        PolicyToolClass.NETWORK_ACCESS to MatrixRule(
          outcome = PolicyDecisionOutcome.ALLOW,
          reasonCode = PolicyReasonCode.ALLOW_DEVELOPER_OVERRIDE,
        ),
      ),
    )

  init {
    val allToolClasses = PolicyToolClass.values().toSet()
    for ((mode, rules) in matrix) {
      val missingRules = allToolClasses - rules.keys
      require(missingRules.isEmpty()) {
        "Mode $mode is missing matrix rules for: $missingRules"
      }
    }
  }

  fun decide(request: PolicyRequest): PolicyDecision {
    validatePaths(request)?.let { return it }
    enforceProtectedInvariants(request)?.let { return it }

    val matrixRule = matrix.getValue(request.mode).getValue(request.toolClass)
    return PolicyDecision(
      outcome = matrixRule.outcome,
      reasonCode = matrixRule.reasonCode,
      approvalRisk = matrixRule.approvalRisk,
    )
  }

  private fun validatePaths(request: PolicyRequest): PolicyDecision? {
    if (!request.toolClass.requiresTargetPath()) {
      return null
    }

    val canonicalWorkspaceRoot = canonicalize(request.workspaceRoot)
    val targetPath = request.targetPath ?: return deny(
      reasonCode = PolicyReasonCode.DENY_INVALID_PATH,
      detail = "Target path is required for ${request.toolClass.name}.",
    )
    validatePathWithinWorkspace(canonicalWorkspaceRoot, targetPath, "target")?.let { return it }

    if (request.toolClass.requiresDestinationPath()) {
      val destinationPath = request.destinationPath ?: return deny(
        reasonCode = PolicyReasonCode.DENY_INVALID_PATH,
        detail = "Destination path is required for ${request.toolClass.name}.",
      )
      validatePathWithinWorkspace(canonicalWorkspaceRoot, destinationPath, "destination")?.let {
        return it
      }
    }

    return null
  }

  private fun enforceProtectedInvariants(request: PolicyRequest): PolicyDecision? {
    if (!request.toolClass.isDestructiveFileMutation()) {
      return null
    }

    val canonicalWorkspaceRoot = canonicalize(request.workspaceRoot)
    val targetPath = request.targetPath ?: return deny(
      reasonCode = PolicyReasonCode.DENY_INVALID_PATH,
      detail = "Target path is required for ${request.toolClass.name}.",
    )
    val canonicalTarget = canonicalize(resolveAgainstWorkspace(canonicalWorkspaceRoot, targetPath))
    if (protectedRegistry.isProtected(canonicalTarget)) {
      return deny(
        reasonCode = PolicyReasonCode.DENY_PROTECTED_FILE,
        detail = "Operation targets a protected file: ${canonicalTarget.fileName}",
      )
    }

    if (request.toolClass.requiresDestinationPath()) {
      val destinationPath = request.destinationPath ?: return deny(
        reasonCode = PolicyReasonCode.DENY_INVALID_PATH,
        detail = "Destination path is required for ${request.toolClass.name}.",
      )
      val canonicalDestination = canonicalize(
        resolveAgainstWorkspace(canonicalWorkspaceRoot, destinationPath),
      )
      if (protectedRegistry.isProtected(canonicalDestination)) {
        return deny(
          reasonCode = PolicyReasonCode.DENY_PROTECTED_FILE,
          detail = "Operation targets a protected destination: ${canonicalDestination.fileName}",
        )
      }
    }

    return null
  }

  private fun validatePathWithinWorkspace(
    canonicalWorkspaceRoot: Path,
    candidatePath: Path,
    label: String,
  ): PolicyDecision? {
    if (containsTraversalSegment(candidatePath)) {
      return deny(
        reasonCode = PolicyReasonCode.DENY_PATH_TRAVERSAL,
        detail = "$label path contains traversal segment '..'.",
      )
    }

    val resolvedPath = resolveAgainstWorkspace(canonicalWorkspaceRoot, candidatePath)
    val canonicalCandidatePath = canonicalize(resolvedPath)
    if (!canonicalCandidatePath.startsWith(canonicalWorkspaceRoot)) {
      return deny(
        reasonCode = PolicyReasonCode.DENY_PATH_ESCAPE,
        detail = "$label path escapes workspace root.",
      )
    }

    return null
  }

  private fun resolveAgainstWorkspace(workspaceRoot: Path, path: Path): Path =
    if (path.isAbsolute) path else workspaceRoot.resolve(path)

  private fun canonicalize(path: Path): Path {
    val absoluteNormalized = path.toAbsolutePath().normalize()
    val existingAncestor = findNearestExistingAncestor(absoluteNormalized) ?: return absoluteNormalized
    val relativeSuffix = existingAncestor.relativize(absoluteNormalized)

    return runCatching {
      val canonicalAncestor = existingAncestor.toRealPath()
      canonicalAncestor.resolve(relativeSuffix).normalize()
    }.getOrDefault(absoluteNormalized)
  }

  private fun findNearestExistingAncestor(path: Path): Path? {
    var current: Path? = path
    while (current != null) {
      if (Files.exists(current)) {
        return current
      }
      current = current.parent
    }
    return null
  }

  private fun containsTraversalSegment(path: Path): Boolean {
    for (segment in path) {
      if (segment.toString() == "..") {
        return true
      }
    }
    return false
  }

  private fun deny(reasonCode: String, detail: String): PolicyDecision = PolicyDecision(
    outcome = PolicyDecisionOutcome.DENY,
    reasonCode = reasonCode,
    detail = detail,
  )
}
