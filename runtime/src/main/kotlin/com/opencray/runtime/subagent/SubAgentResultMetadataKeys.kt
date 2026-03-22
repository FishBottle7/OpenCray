package com.opencray.runtime.subagent

object SubAgentResultMetadataKeys {
  const val EXECUTION_STATE: String = "childExecutionState"
  const val CONTINUATION_KIND: String = "childContinuationKind"
  const val CONTINUATION_RESUMABLE: String = "childContinuationResumable"
  const val CONTINUATION_REQUIRES_USER_ACTION: String = "childContinuationRequiresUserAction"
  const val CONTINUATION_IS_HIGH_RISK: String = "childContinuationIsHighRisk"
  const val SUMMARY_HEADLINE: String = "childSummaryHeadline"
  const val SUMMARY_DETAIL_COUNT: String = "childSummaryDetailCount"
  const val SUMMARY_DETAILS: String = "childSummaryDetails"
  const val ERROR_CODE: String = "childErrorCode"
}
