package com.opencray.runtime.policy

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.PolicyApprovalRisk
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.policy.ModePolicy
import com.opencray.policy.PolicyRequest
import com.opencray.policy.PolicyToolClass
import com.opencray.policy.SafetySettingsMetadataKeys
import com.opencray.policy.ToolPolicyOverride
import java.nio.file.Path

data class ToolPolicyEvaluationRequest(
  val task: AgentTask,
  val toolName: String,
  val toolClass: PolicyToolClass,
  val workspaceRoot: Path,
  val targetPath: Path? = null,
  val destinationPath: Path? = null,
  val approvedReadRoots: Set<Path> = setOf(workspaceRoot),
  val approvedWriteRoots: Set<Path> = setOf(workspaceRoot),
  val approvedHostManagedReadRoots: Set<Path> = emptySet(),
) {
  constructor(
    task: AgentTask,
    toolName: String,
    toolClass: PolicyToolClass,
    workspaceRoot: Path,
    targetPath: Path? = null,
    destinationPath: Path? = null,
  ) : this(
    task = task,
    toolName = toolName,
    toolClass = toolClass,
    workspaceRoot = workspaceRoot,
    targetPath = targetPath,
    destinationPath = destinationPath,
    approvedReadRoots = setOf(workspaceRoot),
    approvedWriteRoots = setOf(workspaceRoot),
    approvedHostManagedReadRoots = emptySet(),
  )
}

internal class ToolPolicyEvaluator(
  private val modePolicy: ModePolicy,
  private val approvedTaskId: String? = null,
  private val approvedToolName: String? = null,
  private val rejectedTaskId: String? = null,
  private val rejectedToolName: String? = null,
) {
  fun evaluate(request: ToolPolicyEvaluationRequest): PolicyDecision {
    val baseDecision = modePolicy.decide(
      PolicyRequest(
        mode = ToolExecutionModeResolver.infer(request.task),
        toolClass = request.toolClass,
        workspaceRoot = request.workspaceRoot,
        targetPath = request.targetPath,
        destinationPath = request.destinationPath,
        approvedReadRoots = request.approvedReadRoots,
        approvedWriteRoots = request.approvedWriteRoots,
        approvedHostManagedReadRoots = request.approvedHostManagedReadRoots,
      ),
    )
    val overriddenDecision = applySettingsPolicyOverride(
      task = request.task,
      toolClass = request.toolClass,
      policyDecision = baseDecision,
    )
    val mergedDecision = mergePolicyDecisions(
      coarseDecision = request.task.policyDecision,
      fineGrainedDecision = overriddenDecision,
    )
    val approvedTaskDecision = applyApprovedTaskOverride(
      task = request.task,
      policyDecision = mergedDecision,
    )
    val approvedToolDecision = applyApprovedToolOverride(
      task = request.task,
      toolName = request.toolName,
      policyDecision = approvedTaskDecision,
    )
    val rejectedTaskDecision = applyRejectedTaskOverride(
      task = request.task,
      policyDecision = approvedToolDecision,
    )
    return applyRejectedToolOverride(
      task = request.task,
      toolName = request.toolName,
      policyDecision = rejectedTaskDecision,
    )
  }

  private fun applySettingsPolicyOverride(
    task: AgentTask,
    toolClass: PolicyToolClass,
    policyDecision: PolicyDecision,
  ): PolicyDecision {
    if (policyDecision.outcome == PolicyDecisionOutcome.DENY) {
      return policyDecision
    }
    val override = settingsPolicyOverrideFor(task = task, toolClass = toolClass)
    if (override == ToolPolicyOverride.INHERIT) {
      return policyDecision
    }
    return when (override) {
      ToolPolicyOverride.INHERIT -> policyDecision
      ToolPolicyOverride.ALLOW -> PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "SETTINGS_OVERRIDE_ALLOW",
      )
      ToolPolicyOverride.BLOCK -> PolicyDecision(
        outcome = PolicyDecisionOutcome.DENY,
        reasonCode = "SETTINGS_OVERRIDE_BLOCK",
        detail = "Blocked by Safety settings.",
        approvalRisk = policyDecision.approvalRisk,
      )
      ToolPolicyOverride.ASK -> PolicyDecision(
        outcome = PolicyDecisionOutcome.ASK,
        reasonCode = "SETTINGS_OVERRIDE_ASK",
        detail = "Approval is required by Safety settings.",
        approvalRisk = approvalRiskForSettingsOverride(toolClass, policyDecision),
      )
    }
  }

  private fun settingsPolicyOverrideFor(
    task: AgentTask,
    toolClass: PolicyToolClass,
  ): ToolPolicyOverride {
    val metadataKey = when (toolClass) {
      PolicyToolClass.WRITE_FILE -> SafetySettingsMetadataKeys.FILE_CHANGES_POLICY_ID
      PolicyToolClass.DELETE_FILE,
      PolicyToolClass.MOVE_FILE,
      PolicyToolClass.RENAME_FILE,
      -> SafetySettingsMetadataKeys.FILE_DELETES_POLICY_ID
      PolicyToolClass.EXECUTE_COMMAND -> SafetySettingsMetadataKeys.SHELL_COMMANDS_POLICY_ID
      PolicyToolClass.READ_FILE,
      PolicyToolClass.NETWORK_ACCESS,
      -> null
    } ?: return ToolPolicyOverride.INHERIT
    return ToolPolicyOverride.fromWireValue(task.metadata[metadataKey])
  }

  private fun approvalRiskForSettingsOverride(
    toolClass: PolicyToolClass,
    policyDecision: PolicyDecision,
  ): PolicyApprovalRisk {
    if (policyDecision.outcome == PolicyDecisionOutcome.ASK) {
      return policyDecision.approvalRisk
    }
    return when (toolClass) {
      PolicyToolClass.WRITE_FILE -> PolicyApprovalRisk.STANDARD
      PolicyToolClass.DELETE_FILE,
      PolicyToolClass.MOVE_FILE,
      PolicyToolClass.RENAME_FILE,
      PolicyToolClass.EXECUTE_COMMAND,
      PolicyToolClass.NETWORK_ACCESS,
      -> PolicyApprovalRisk.HIGH_RISK
      PolicyToolClass.READ_FILE -> PolicyApprovalRisk.STANDARD
    }
  }

  private fun mergePolicyDecisions(
    coarseDecision: PolicyDecision,
    fineGrainedDecision: PolicyDecision,
  ): PolicyDecision {
    val coarseRank = policyRank(coarseDecision.outcome)
    val fineRank = policyRank(fineGrainedDecision.outcome)
    val winningDecision = when {
      fineRank > coarseRank -> fineGrainedDecision
      coarseRank > fineRank -> coarseDecision
      coarseDecision.outcome == PolicyDecisionOutcome.ASK &&
        fineGrainedDecision.outcome == PolicyDecisionOutcome.ASK -> when {
          approvalRiskRank(fineGrainedDecision.approvalRisk) > approvalRiskRank(coarseDecision.approvalRisk) -> fineGrainedDecision
          approvalRiskRank(coarseDecision.approvalRisk) > approvalRiskRank(fineGrainedDecision.approvalRisk) -> coarseDecision
          else -> fineGrainedDecision
        }
      else -> fineGrainedDecision
    }
    return winningDecision.copy(
      detail = winningDecision.detail ?: coarseDecision.detail ?: fineGrainedDecision.detail,
    )
  }

  private fun policyRank(outcome: PolicyDecisionOutcome): Int = when (outcome) {
    PolicyDecisionOutcome.ALLOW -> 0
    PolicyDecisionOutcome.ASK -> 1
    PolicyDecisionOutcome.DENY -> 2
  }

  private fun approvalRiskRank(approvalRisk: PolicyApprovalRisk): Int = when (approvalRisk) {
    PolicyApprovalRisk.STANDARD -> 0
    PolicyApprovalRisk.HIGH_RISK -> 1
  }

  private fun applyApprovedTaskOverride(
    task: AgentTask,
    policyDecision: PolicyDecision,
  ): PolicyDecision {
    if (!approvedToolName.isNullOrBlank()) {
      return policyDecision
    }
    val approvedTaskId = approvedTaskId
      ?.takeIf(String::isNotBlank)
      ?: return policyDecision
    if (approvedTaskId != task.id || policyDecision.outcome != PolicyDecisionOutcome.ASK) {
      return policyDecision
    }
    return PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "USER_APPROVED_RETRY",
      detail = "User approved this task retry.",
      approvalRisk = policyDecision.approvalRisk,
    )
  }

  private fun applyApprovedToolOverride(
    task: AgentTask,
    toolName: String,
    policyDecision: PolicyDecision,
  ): PolicyDecision {
    val approvedTaskId = approvedTaskId
      ?.takeIf(String::isNotBlank)
      ?: return policyDecision
    val approvedToolName = approvedToolName
      ?.takeIf(String::isNotBlank)
      ?: return policyDecision
    if (approvedTaskId != task.id || approvedToolName != toolName || policyDecision.outcome != PolicyDecisionOutcome.ASK) {
      return policyDecision
    }
    return PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "USER_APPROVED_RETRY",
      detail = "User approved this task retry for $toolName.",
      approvalRisk = policyDecision.approvalRisk,
    )
  }

  private fun applyRejectedTaskOverride(
    task: AgentTask,
    policyDecision: PolicyDecision,
  ): PolicyDecision {
    if (!rejectedToolName.isNullOrBlank()) {
      return policyDecision
    }
    val rejectedTaskId = rejectedTaskId
      ?.takeIf(String::isNotBlank)
      ?: return policyDecision
    if (rejectedTaskId != task.id || policyDecision.outcome != PolicyDecisionOutcome.ASK) {
      return policyDecision
    }
    return PolicyDecision(
      outcome = PolicyDecisionOutcome.DENY,
      reasonCode = "USER_REJECTED_APPROVAL",
      detail = "User rejected approval for this task retry. Do not execute the blocked action.",
      approvalRisk = policyDecision.approvalRisk,
    )
  }

  private fun applyRejectedToolOverride(
    task: AgentTask,
    toolName: String,
    policyDecision: PolicyDecision,
  ): PolicyDecision {
    val rejectedTaskId = rejectedTaskId
      ?.takeIf(String::isNotBlank)
      ?: return policyDecision
    val rejectedToolName = rejectedToolName
      ?.takeIf(String::isNotBlank)
      ?: return policyDecision
    if (rejectedTaskId != task.id || rejectedToolName != toolName || policyDecision.outcome != PolicyDecisionOutcome.ASK) {
      return policyDecision
    }
    return PolicyDecision(
      outcome = PolicyDecisionOutcome.DENY,
      reasonCode = "USER_REJECTED_APPROVAL",
      detail = "User rejected approval for $toolName. Do not execute it.",
      approvalRisk = policyDecision.approvalRisk,
    )
  }
}
