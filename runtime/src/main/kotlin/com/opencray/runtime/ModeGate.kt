package com.opencray.runtime

import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyApprovalRisk
import com.opencray.core.contracts.PolicyDecisionOutcome
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CommandExecutionRequest(
  val taskId: String,
  val command: String,
  val args: List<String> = emptyList(),
  val workingDirectory: String? = null,
  val requestedAtEpochMs: Long,
  val metadata: Map<String, String> = emptyMap(),
) {
  init {
    require(taskId.isNotBlank()) { "CommandExecutionRequest taskId must not be blank." }
    require(command.isNotBlank()) { "CommandExecutionRequest command must not be blank." }
  }
}

@Serializable
data class CommandApprovalToken(
  val tokenId: String,
  val taskId: String,
  val approvedAtEpochMs: Long,
  val approvedBy: String? = null,
  val note: String? = null,
  val approvedRequestFingerprint: String? = null,
) {
  init {
    require(tokenId.isNotBlank()) { "CommandApprovalToken tokenId must not be blank." }
    require(taskId.isNotBlank()) { "CommandApprovalToken taskId must not be blank." }
  }
}

@Serializable
data class CommandApprovalEvent(
  val tokenId: String,
  val taskId: String,
  val approvedAtEpochMs: Long,
  val approvedBy: String? = null,
  val note: String? = null,
) {
  init {
    require(tokenId.isNotBlank()) { "CommandApprovalEvent tokenId must not be blank." }
    require(taskId.isNotBlank()) { "CommandApprovalEvent taskId must not be blank." }
  }
}

@Serializable
enum class CommandGateStatus {
  @SerialName("allowed") ALLOWED,
  @SerialName("blocked") BLOCKED,
  @SerialName("denied") DENIED,
}

object CommandGateReasonCode {
  const val ALLOW_POLICY_ALLOW = "ALLOW_POLICY_ALLOW"
  const val ALLOW_APPROVAL_TOKEN = "ALLOW_APPROVAL_TOKEN"
  const val BLOCK_APPROVAL_REQUIRED = "BLOCK_APPROVAL_REQUIRED"
  const val BLOCK_APPROVAL_TASK_MISMATCH = "BLOCK_APPROVAL_TASK_MISMATCH"
  const val BLOCK_APPROVAL_EXPIRED = "BLOCK_APPROVAL_EXPIRED"
  const val BLOCK_APPROVAL_CONTENT_MISMATCH = "BLOCK_APPROVAL_CONTENT_MISMATCH"
  const val BLOCK_APPROVAL_TOKEN_CONSUMED = "BLOCK_APPROVAL_TOKEN_CONSUMED"
  const val DENY_POLICY_DECISION = "DENY_POLICY_DECISION"
}

@Serializable
data class CommandGateAuditRecord(
  val taskId: String,
  val command: String,
  val args: List<String> = emptyList(),
  val workingDirectory: String? = null,
  val gateStatus: CommandGateStatus,
  val gateReasonCode: String,
  val policyOutcome: PolicyDecisionOutcome,
  val policyReasonCode: String,
  val approvalTokenId: String? = null,
  val approvalProvided: Boolean = false,
  val approvedBy: String? = null,
  val requestedAtEpochMs: Long,
  val decidedAtEpochMs: Long,
  val detail: String? = null,
  val metadata: Map<String, String> = emptyMap(),
) {
  init {
    require(taskId.isNotBlank()) { "CommandGateAuditRecord taskId must not be blank." }
    require(command.isNotBlank()) { "CommandGateAuditRecord command must not be blank." }
    require(gateReasonCode.isNotBlank()) { "CommandGateAuditRecord gateReasonCode must not be blank." }
    require(policyReasonCode.isNotBlank()) { "CommandGateAuditRecord policyReasonCode must not be blank." }
    require(decidedAtEpochMs >= requestedAtEpochMs) {
      "CommandGateAuditRecord decidedAtEpochMs must be >= requestedAtEpochMs."
    }
  }
}

@Serializable
data class CommandGateDecision(
  val taskId: String,
  val status: CommandGateStatus,
  val reasonCode: String,
  val shouldExecute: Boolean,
  val policyDecision: PolicyDecision,
  val approvalToken: CommandApprovalToken? = null,
  val auditRecord: CommandGateAuditRecord,
  val detail: String? = null,
) {
  init {
    require(taskId.isNotBlank()) { "CommandGateDecision taskId must not be blank." }
    require(reasonCode.isNotBlank()) { "CommandGateDecision reasonCode must not be blank." }
  }
}

fun CommandExecutionRequest.approvalFingerprint(): String {
  val payload = listOf(
    command,
    args.joinToString("\u0000"),
    workingDirectory.orEmpty(),
  ).joinToString("\u0000")
  return MessageDigest.getInstance("SHA-256")
    .digest(payload.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
}

object ModeGate {
  const val APPROVAL_TOKEN_VALIDITY_MS = 10L * 60L * 1000L

  private const val CONSUMED_APPROVAL_TOKEN_RETENTION_MS = 2L * APPROVAL_TOKEN_VALIDITY_MS

  private val consumedApprovalTokenIds = ConcurrentHashMap<String, Long>()

  fun evaluatePreExec(
    request: CommandExecutionRequest,
    policyDecision: PolicyDecision,
    approvalToken: CommandApprovalToken? = null,
    decidedAtEpochMs: Long = System.currentTimeMillis(),
    approvalRequiredDetail: String = "Approval token is required before command execution.",
    approvalTaskMismatchDetail: String = "Approval token does not match the requested task.",
    denyDetail: String = "Policy denied command execution.",
  ): CommandGateDecision {
    val resolvedGate = when (policyDecision.outcome) {
      PolicyDecisionOutcome.ALLOW -> ResolvedGate(
        status = CommandGateStatus.ALLOWED,
        reasonCode = CommandGateReasonCode.ALLOW_POLICY_ALLOW,
        shouldExecute = true,
        detail = policyDecision.detail,
      )

      PolicyDecisionOutcome.ASK -> when {
        approvalToken == null -> ResolvedGate(
          status = CommandGateStatus.BLOCKED,
          reasonCode = CommandGateReasonCode.BLOCK_APPROVAL_REQUIRED,
          shouldExecute = false,
          detail = approvalRequiredDetail(policyDecision, approvalRequiredDetail),
        )

        approvalToken.taskId != request.taskId -> ResolvedGate(
          status = CommandGateStatus.BLOCKED,
          reasonCode = CommandGateReasonCode.BLOCK_APPROVAL_TASK_MISMATCH,
          shouldExecute = false,
          detail = approvalTaskMismatchDetail,
        )

        isApprovalTokenExpired(approvalToken, decidedAtEpochMs) -> ResolvedGate(
          status = CommandGateStatus.BLOCKED,
          reasonCode = CommandGateReasonCode.BLOCK_APPROVAL_EXPIRED,
          shouldExecute = false,
          detail = "Approval token expired before command execution.",
        )

        isApprovalContentMismatch(approvalToken, request) -> ResolvedGate(
          status = CommandGateStatus.BLOCKED,
          reasonCode = CommandGateReasonCode.BLOCK_APPROVAL_CONTENT_MISMATCH,
          shouldExecute = false,
          detail = "Approval token does not cover this command content.",
        )

        !consumeApprovalToken(approvalToken.tokenId, decidedAtEpochMs) -> ResolvedGate(
          status = CommandGateStatus.BLOCKED,
          reasonCode = CommandGateReasonCode.BLOCK_APPROVAL_TOKEN_CONSUMED,
          shouldExecute = false,
          detail = "Approval token was already consumed by an earlier execution.",
        )

        else -> ResolvedGate(
          status = CommandGateStatus.ALLOWED,
          reasonCode = CommandGateReasonCode.ALLOW_APPROVAL_TOKEN,
          shouldExecute = true,
          detail = approvalToken.note ?: policyDecision.detail,
        )
      }

      PolicyDecisionOutcome.DENY -> ResolvedGate(
        status = CommandGateStatus.DENIED,
        reasonCode = CommandGateReasonCode.DENY_POLICY_DECISION,
        shouldExecute = false,
        detail = policyDecision.detail ?: denyDetail,
      )
    }

    val auditRecord = CommandGateAuditRecord(
      taskId = request.taskId,
      command = request.command,
      args = request.args,
      workingDirectory = request.workingDirectory,
      gateStatus = resolvedGate.status,
      gateReasonCode = resolvedGate.reasonCode,
      policyOutcome = policyDecision.outcome,
      policyReasonCode = policyDecision.reasonCode,
      approvalTokenId = approvalToken?.tokenId,
      approvalProvided = approvalToken != null,
      approvedBy = approvalToken?.approvedBy,
      requestedAtEpochMs = request.requestedAtEpochMs,
      decidedAtEpochMs = decidedAtEpochMs,
      detail = resolvedGate.detail,
      metadata = request.metadata,
    )

    return CommandGateDecision(
      taskId = request.taskId,
      status = resolvedGate.status,
      reasonCode = resolvedGate.reasonCode,
      shouldExecute = resolvedGate.shouldExecute,
      policyDecision = policyDecision,
      approvalToken = approvalToken,
      auditRecord = auditRecord,
      detail = resolvedGate.detail,
    )
  }

  private fun isApprovalTokenExpired(
    approvalToken: CommandApprovalToken,
    decidedAtEpochMs: Long,
  ): Boolean = decidedAtEpochMs - approvalToken.approvedAtEpochMs > APPROVAL_TOKEN_VALIDITY_MS

  private fun isApprovalContentMismatch(
    approvalToken: CommandApprovalToken,
    request: CommandExecutionRequest,
  ): Boolean = approvalToken.approvedRequestFingerprint != null &&
    approvalToken.approvedRequestFingerprint != request.approvalFingerprint()

  private fun consumeApprovalToken(tokenId: String, decidedAtEpochMs: Long): Boolean {
    pruneConsumedApprovalTokens(decidedAtEpochMs)
    return consumedApprovalTokenIds.putIfAbsent(tokenId, decidedAtEpochMs) == null
  }

  private fun pruneConsumedApprovalTokens(nowEpochMs: Long) {
    consumedApprovalTokenIds.entries.removeIf { entry ->
      nowEpochMs - entry.value > CONSUMED_APPROVAL_TOKEN_RETENTION_MS
    }
  }

  private data class ResolvedGate(
    val status: CommandGateStatus,
    val reasonCode: String,
    val shouldExecute: Boolean,
    val detail: String? = null,
  )

  private fun approvalRequiredDetail(
    policyDecision: PolicyDecision,
    fallback: String,
  ): String {
    val detail = policyDecision.detail ?: fallback
    return when (policyDecision.approvalRisk) {
      PolicyApprovalRisk.HIGH_RISK -> if (detail.contains("high-risk", ignoreCase = true)) {
        detail
      } else {
        "High-risk approval required. Review this request carefully before approving. $detail"
      }

      PolicyApprovalRisk.STANDARD -> detail
    }
  }
}
