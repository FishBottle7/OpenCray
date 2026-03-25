package com.opencray.runtime.subagent

import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.runtime.OpenCrayPromptResumeMetadata
import kotlinx.serialization.Serializable

@Serializable
enum class SubAgentExecutionState(
  val wireValue: String,
) {
  RUNNING("running"),
  BACKGROUND_QUEUED("background_queued"),
  BACKGROUND_RUNNING("background_running"),
  WAITING_APPROVAL("waiting_approval"),
  WAITING_HIGH_RISK_APPROVAL("waiting_high_risk_approval"),
  COMPLETED("completed"),
  FAILED("failed"),
  CANCELLED("cancelled");

  companion object {
    fun fromWireValue(rawValue: String?): SubAgentExecutionState? = entries.firstOrNull { state ->
      state.wireValue.equals(rawValue?.trim(), ignoreCase = true)
    }

    fun fromExecutionResult(result: ExecutionResult): SubAgentExecutionState = when (result.status) {
      ExecutionStatus.SUCCESS -> COMPLETED
      ExecutionStatus.CANCELLED -> CANCELLED
      ExecutionStatus.DENIED -> when (result.errorCode?.trim()) {
        HIGH_RISK_APPROVAL_REQUIRED -> WAITING_HIGH_RISK_APPROVAL
        APPROVAL_REQUIRED -> WAITING_APPROVAL
        else -> FAILED
      }

      ExecutionStatus.FAILED,
      ExecutionStatus.TIMEOUT,
      -> FAILED
    }

    private const val APPROVAL_REQUIRED: String = "APPROVAL_REQUIRED"
    private const val HIGH_RISK_APPROVAL_REQUIRED: String = "HIGH_RISK_APPROVAL_REQUIRED"
  }
}

@Serializable
enum class SubAgentContinuationKind(
  val wireValue: String,
) {
  NONE("none"),
  PROMPT_RESUME("prompt_resume"),
  BACKGROUND_RESUME("background_resume"),

  ;

  companion object {
    fun fromWireValue(rawValue: String?): SubAgentContinuationKind? = entries.firstOrNull { kind ->
      kind.wireValue.equals(rawValue?.trim(), ignoreCase = true)
    }
  }
}

@Serializable
data class SubAgentExecutionSnapshot(
  val state: SubAgentExecutionState,
  val continuationKind: SubAgentContinuationKind,
  val resumable: Boolean,
  val requiresUserAction: Boolean,
  val isHighRisk: Boolean,
  val headline: String,
  val detailLines: List<String> = emptyList(),
  val childErrorCode: String? = null,
) {
  fun summaryText(): String = buildString {
    append(summaryPrefix(state))
    append(": ")
    append(headline)
    if (detailLines.isNotEmpty()) {
      appendLine()
      appendLine("Details:")
      detailLines.forEach { line ->
        append("- ")
        appendLine(line)
      }
    }
  }.trim()

  fun metadata(): Map<String, String> = buildMap {
    put(SubAgentResultMetadataKeys.EXECUTION_STATE, state.wireValue)
    put(SubAgentResultMetadataKeys.CONTINUATION_KIND, continuationKind.wireValue)
    put(SubAgentResultMetadataKeys.CONTINUATION_RESUMABLE, resumable.toString())
    put(SubAgentResultMetadataKeys.CONTINUATION_REQUIRES_USER_ACTION, requiresUserAction.toString())
    put(SubAgentResultMetadataKeys.CONTINUATION_IS_HIGH_RISK, isHighRisk.toString())
    put(SubAgentResultMetadataKeys.SUMMARY_HEADLINE, headline)
    put(SubAgentResultMetadataKeys.SUMMARY_DETAIL_COUNT, detailLines.size.toString())
    if (detailLines.isNotEmpty()) {
      put(SubAgentResultMetadataKeys.SUMMARY_DETAILS, detailLines.joinToString(separator = "\n"))
    }
    childErrorCode
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { errorCode ->
        put(SubAgentResultMetadataKeys.ERROR_CODE, errorCode)
      }
  }

  private fun summaryPrefix(state: SubAgentExecutionState): String = when (state) {
    SubAgentExecutionState.RUNNING -> "Subagent running"
    SubAgentExecutionState.BACKGROUND_QUEUED -> "Subagent queued"
    SubAgentExecutionState.BACKGROUND_RUNNING -> "Subagent running in background"
    SubAgentExecutionState.WAITING_APPROVAL -> "Subagent waiting for approval"
    SubAgentExecutionState.WAITING_HIGH_RISK_APPROVAL -> "Subagent waiting for high-risk approval"
    SubAgentExecutionState.COMPLETED -> "Subagent completed"
    SubAgentExecutionState.FAILED -> "Subagent failed"
    SubAgentExecutionState.CANCELLED -> "Subagent cancelled"
  }

  companion object {
    fun running(
      headline: String = "Delegated child run started.",
    ): SubAgentExecutionSnapshot = SubAgentExecutionSnapshot(
      state = SubAgentExecutionState.RUNNING,
      continuationKind = SubAgentContinuationKind.NONE,
      resumable = false,
      requiresUserAction = false,
      isHighRisk = false,
      headline = headline,
    )

    fun backgroundQueued(
      headline: String = "Delegated child run queued.",
    ): SubAgentExecutionSnapshot = SubAgentExecutionSnapshot(
      state = SubAgentExecutionState.BACKGROUND_QUEUED,
      continuationKind = SubAgentContinuationKind.BACKGROUND_RESUME,
      resumable = true,
      requiresUserAction = false,
      isHighRisk = false,
      headline = headline,
    )

    fun backgroundRunning(
      headline: String = "Delegated child run is running in the background.",
    ): SubAgentExecutionSnapshot = SubAgentExecutionSnapshot(
      state = SubAgentExecutionState.BACKGROUND_RUNNING,
      continuationKind = SubAgentContinuationKind.BACKGROUND_RESUME,
      resumable = true,
      requiresUserAction = false,
      isHighRisk = false,
      headline = headline,
    )
  }
}

object SubAgentResultCompressor {
  private const val MAX_HEADLINE_CHARS: Int = 240
  private const val MAX_DETAIL_LINE_CHARS: Int = 240
  private const val MAX_DETAIL_LINES: Int = 3
  private const val HIGH_RISK_APPROVAL_REQUIRED: String = "HIGH_RISK_APPROVAL_REQUIRED"

  fun compress(result: ExecutionResult): SubAgentExecutionSnapshot {
    val state = explicitState(result) ?: SubAgentExecutionState.fromExecutionResult(result)
    val continuationKind = explicitContinuationKind(result) ?: continuationKind(
      result = result,
      state = state,
    )
    val resumable = explicitBoolean(
      result = result,
      key = SubAgentResultMetadataKeys.CONTINUATION_RESUMABLE,
    ) ?: when (continuationKind) {
      SubAgentContinuationKind.NONE -> false
      SubAgentContinuationKind.PROMPT_RESUME -> result.metadata[OpenCrayPromptResumeMetadata.KEY_PROMPT_RESUME_JSON]
        ?.trim()
        ?.isNotEmpty() == true || explicitContinuationKind(result) != null
      SubAgentContinuationKind.BACKGROUND_RESUME -> true
    }
    val rawLines = rawSummaryLines(result)
    val fallbackHeadline = fallbackHeadline(state)
    val headline = explicitHeadline(result)
      ?: rawLines.firstOrNull()?.take(MAX_HEADLINE_CHARS)
      ?: fallbackHeadline
    val detailLines = explicitDetailLines(result) ?: rawLines
      .drop(1)
      .take(MAX_DETAIL_LINES)
      .map { line -> line.take(MAX_DETAIL_LINE_CHARS) }
    val requiresUserAction = explicitBoolean(
      result = result,
      key = SubAgentResultMetadataKeys.CONTINUATION_REQUIRES_USER_ACTION,
    ) ?: (state == SubAgentExecutionState.WAITING_APPROVAL ||
      state == SubAgentExecutionState.WAITING_HIGH_RISK_APPROVAL)
    val isHighRisk = explicitBoolean(
      result = result,
      key = SubAgentResultMetadataKeys.CONTINUATION_IS_HIGH_RISK,
    ) ?: (state == SubAgentExecutionState.WAITING_HIGH_RISK_APPROVAL ||
      explicitErrorCode(result) == HIGH_RISK_APPROVAL_REQUIRED ||
      result.errorCode?.trim() == HIGH_RISK_APPROVAL_REQUIRED)
    return SubAgentExecutionSnapshot(
      state = state,
      continuationKind = continuationKind,
      resumable = resumable,
      requiresUserAction = requiresUserAction,
      isHighRisk = isHighRisk,
      headline = headline,
      detailLines = detailLines,
      childErrorCode = explicitErrorCode(result) ?: result.errorCode,
    )
  }

  private fun explicitState(result: ExecutionResult): SubAgentExecutionState? =
    result.metadata[SubAgentResultMetadataKeys.EXECUTION_STATE]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let(SubAgentExecutionState::fromWireValue)

  private fun explicitContinuationKind(result: ExecutionResult): SubAgentContinuationKind? =
    result.metadata[SubAgentResultMetadataKeys.CONTINUATION_KIND]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let(SubAgentContinuationKind::fromWireValue)

  private fun explicitBoolean(
    result: ExecutionResult,
    key: String,
  ): Boolean? = result.metadata[key]
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?.toBooleanStrictOrNull()

  private fun explicitHeadline(result: ExecutionResult): String? =
    result.metadata[SubAgentResultMetadataKeys.SUMMARY_HEADLINE]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.take(MAX_HEADLINE_CHARS)

  private fun explicitDetailLines(result: ExecutionResult): List<String>? {
    val value = result.metadata[SubAgentResultMetadataKeys.SUMMARY_DETAILS]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return null
    return value.lines()
      .map(String::trim)
      .filter(String::isNotBlank)
      .take(MAX_DETAIL_LINES)
      .map { line -> line.take(MAX_DETAIL_LINE_CHARS) }
  }

  private fun explicitErrorCode(result: ExecutionResult): String? =
    result.metadata[SubAgentResultMetadataKeys.ERROR_CODE]
      ?.trim()
      ?.takeIf(String::isNotBlank)

  private fun continuationKind(
    result: ExecutionResult,
    state: SubAgentExecutionState,
  ): SubAgentContinuationKind = when {
    result.metadata[OpenCrayPromptResumeMetadata.KEY_PROMPT_RESUME_JSON]
      ?.trim()
      ?.isNotEmpty() == true -> SubAgentContinuationKind.PROMPT_RESUME
    state == SubAgentExecutionState.BACKGROUND_QUEUED ||
      state == SubAgentExecutionState.BACKGROUND_RUNNING -> SubAgentContinuationKind.BACKGROUND_RESUME
    else -> SubAgentContinuationKind.NONE
  }

  private fun rawSummaryLines(result: ExecutionResult): List<String> = sourceText(result)
    .lines()
    .map(String::trim)
    .filter(String::isNotBlank)

  private fun sourceText(result: ExecutionResult): String = when (result.status) {
    ExecutionStatus.SUCCESS -> result.stdout
    else -> result.errorMessage
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: result.stderr
  }.trim()

  private fun fallbackHeadline(state: SubAgentExecutionState): String = when (state) {
    SubAgentExecutionState.RUNNING -> "Delegated child run is still running."
    SubAgentExecutionState.BACKGROUND_QUEUED -> "Delegated child run is queued for background execution."
    SubAgentExecutionState.BACKGROUND_RUNNING -> "Delegated child run is running in the background."
    SubAgentExecutionState.WAITING_APPROVAL -> "Delegated child run needs approval before it can continue."
    SubAgentExecutionState.WAITING_HIGH_RISK_APPROVAL -> "Delegated child run needs high-risk approval before it can continue."
    SubAgentExecutionState.COMPLETED -> "Delegated child run completed without a final summary."
    SubAgentExecutionState.FAILED -> "Delegated child run failed."
    SubAgentExecutionState.CANCELLED -> "Delegated child run was cancelled."
  }
}

typealias SubAgentCompressedResult = SubAgentExecutionSnapshot
