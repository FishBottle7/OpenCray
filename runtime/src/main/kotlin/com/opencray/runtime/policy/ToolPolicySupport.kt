package com.opencray.runtime.policy

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.PolicyApprovalRisk
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus

internal enum class ToolTargetKind(val wireValue: String) {
  FILE("file"),
  DIRECTORY("directory"),
  SEARCH_ROOT("search_root"),
  SCRIPT("script"),
  WORKING_DIRECTORY("working_directory"),
  PROCESS("process"),
  NETWORK("network"),
  NONE("none"),
}

internal data class ToolMetadataContext(
  val targetKind: ToolTargetKind = ToolTargetKind.NONE,
  val workspaceRelation: ToolWorkspaceRelation? = null,
  val primaryTargetPath: String? = null,
  val secondaryTargetPath: String? = null,
  val targetSummary: String? = null,
) {
  init {
    require(primaryTargetPath == null || primaryTargetPath.isNotBlank()) {
      "ToolMetadataContext primaryTargetPath must not be blank when provided."
    }
    require(secondaryTargetPath == null || secondaryTargetPath.isNotBlank()) {
      "ToolMetadataContext secondaryTargetPath must not be blank when provided."
    }
    require(targetSummary == null || targetSummary.isNotBlank()) {
      "ToolMetadataContext targetSummary must not be blank when provided."
    }
  }
}

internal data class ToolGateRequest(
  val task: AgentTask,
  val toolName: String,
  val policyDecision: PolicyDecision,
  val affectedPaths: Map<String, String> = emptyMap(),
  val metadataContext: ToolMetadataContext = ToolMetadataContext(),
  val askDetail: String,
  val denyDetail: String,
)

internal class ToolPolicySupport(
  private val toolCapabilityClassifier: ToolCapabilityClassifier = ToolCapabilityClassifier(),
  private val denyPolicyErrorCode: String = "DENY_POLICY",
  private val approvalRequiredErrorCode: String = "APPROVAL_REQUIRED",
  private val highRiskApprovalRequiredErrorCode: String = "HIGH_RISK_APPROVAL_REQUIRED",
) {
  fun inferExecutionMode(task: AgentTask) = ToolExecutionModeResolver.infer(task)

  fun approvalRiskMetadata(policyDecision: PolicyDecision): Map<String, String> =
    if (policyDecision.outcome == PolicyDecisionOutcome.ASK) {
      mapOf("approvalRisk" to policyDecision.approvalRisk.name)
    } else {
      emptyMap()
    }

  fun commonMetadata(
    toolName: String,
    metadataContext: ToolMetadataContext = ToolMetadataContext(),
  ): Map<String, String> = buildMap {
    put("capabilityKind", toolCapabilityClassifier.classifyCapabilityKind(toolName))
    if (metadataContext.targetKind != ToolTargetKind.NONE) {
      put("targetKind", metadataContext.targetKind.wireValue)
    }
    metadataContext.workspaceRelation?.let { put("workspaceRelation", it.wireValue) }
    metadataContext.primaryTargetPath?.let { put("primaryTargetPath", it) }
    metadataContext.secondaryTargetPath?.let { put("secondaryTargetPath", it) }
    metadataContext.targetSummary?.let { put("targetSummary", it) }
  }

  fun policyMetadata(
    task: AgentTask,
    toolName: String,
    policyDecision: PolicyDecision,
    metadataContext: ToolMetadataContext = ToolMetadataContext(),
    includeOutcome: Boolean = false,
  ): Map<String, String> = buildMap {
    putAll(commonMetadata(toolName = toolName, metadataContext = metadataContext))
    put("executionMode", inferExecutionMode(task).name)
    if (includeOutcome) {
      put("policyOutcome", policyDecision.outcome.name)
    }
    put("policyReasonCode", policyDecision.reasonCode)
    putAll(approvalRiskMetadata(policyDecision))
  }

  fun gateResult(request: ToolGateRequest): AgentToolResult? {
    if (request.policyDecision.outcome == PolicyDecisionOutcome.ALLOW) {
      return null
    }
    val detail = when (request.policyDecision.outcome) {
      PolicyDecisionOutcome.ASK -> approvalRequiredDetail(
        policyDecision = request.policyDecision,
        fallback = request.policyDecision.detail ?: request.askDetail,
      )
      PolicyDecisionOutcome.DENY -> request.policyDecision.detail ?: request.denyDetail
      PolicyDecisionOutcome.ALLOW -> error("ALLOW decisions should not be gated.")
    }
    return AgentToolResult(
      toolName = request.toolName,
      status = AgentToolResultStatus.DENIED,
      content = detail,
      errorCode = when (request.policyDecision.outcome) {
        PolicyDecisionOutcome.ASK -> approvalRequiredErrorCode(request.policyDecision)
        PolicyDecisionOutcome.DENY -> denyPolicyErrorCode
        PolicyDecisionOutcome.ALLOW -> error("ALLOW decisions should not be gated.")
      },
      errorMessage = detail,
      metadata = request.affectedPaths + policyMetadata(
        task = request.task,
        toolName = request.toolName,
        policyDecision = request.policyDecision,
        metadataContext = request.metadataContext,
        includeOutcome = true,
      ),
    )
  }

  fun approvalRequiredErrorCode(policyDecision: PolicyDecision): String =
    when (policyDecision.approvalRisk) {
      PolicyApprovalRisk.HIGH_RISK -> highRiskApprovalRequiredErrorCode
      PolicyApprovalRisk.STANDARD -> approvalRequiredErrorCode
    }

  fun approvalRequiredDetail(
    policyDecision: PolicyDecision,
    fallback: String,
  ): String = when (policyDecision.approvalRisk) {
    PolicyApprovalRisk.HIGH_RISK -> if (fallback.contains("high-risk", ignoreCase = true)) {
      fallback
    } else {
      "High-risk approval required. Review this request carefully before approving. $fallback"
    }

    PolicyApprovalRisk.STANDARD -> fallback
  }
}
