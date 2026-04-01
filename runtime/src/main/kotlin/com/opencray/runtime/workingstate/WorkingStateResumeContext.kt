package com.opencray.runtime.workingstate

import com.opencray.runtime.OpenCrayPromptCheckpointBoundary
import com.opencray.runtime.OpenCrayPromptResumeState
import com.opencray.runtime.OpenCraySerializableModelAction

data class WorkingStateResumeContext(
  val turnIndex: Int,
  val toolCallCount: Int,
  val pendingActionCount: Int = 0,
  val nextActionType: String? = null,
  val pendingToolName: String? = null,
  val requiresSingleActionReminder: Boolean = false,
  val checkpointBoundary: String? = null,
) {
  init {
    require(turnIndex >= 0) { "WorkingStateResumeContext turnIndex must be >= 0." }
    require(toolCallCount >= 0) { "WorkingStateResumeContext toolCallCount must be >= 0." }
    require(pendingActionCount >= 0) { "WorkingStateResumeContext pendingActionCount must be >= 0." }
    require(nextActionType == null || nextActionType.isNotBlank()) {
      "WorkingStateResumeContext nextActionType must not be blank."
    }
    require(pendingToolName == null || pendingToolName.isNotBlank()) {
      "WorkingStateResumeContext pendingToolName must not be blank."
    }
    require(checkpointBoundary == null || checkpointBoundary.isNotBlank()) {
      "WorkingStateResumeContext checkpointBoundary must not be blank."
    }
  }

  companion object {
    fun from(
      promptResumeState: OpenCrayPromptResumeState?,
      checkpointBoundary: OpenCrayPromptCheckpointBoundary? = null,
    ): WorkingStateResumeContext? {
      if (promptResumeState == null) {
        return null
      }
      val pendingActions = promptResumeState.resumableActions()
      val nextAction = pendingActions.getOrNull(promptResumeState.normalizedNextActionIndex())
      return WorkingStateResumeContext(
        turnIndex = promptResumeState.turnIndex,
        toolCallCount = promptResumeState.toolCallCount,
        pendingActionCount = pendingActions.size,
        nextActionType = nextAction?.toWorkingStateActionType(),
        pendingToolName = when (nextAction) {
          is OpenCraySerializableModelAction.ToolCall -> nextAction.call.toolName
            .trim()
            .takeIf(String::isNotBlank)

          else -> null
        },
        requiresSingleActionReminder = promptResumeState.requiresSingleActionReminder,
        checkpointBoundary = checkpointBoundary?.wireValue,
      )
    }
  }
}

private fun OpenCraySerializableModelAction.toWorkingStateActionType(): String = when (this) {
  is OpenCraySerializableModelAction.Commentary -> "commentary"
  is OpenCraySerializableModelAction.Final -> "final"
  is OpenCraySerializableModelAction.ToolCall -> "tool_call"
}
