package com.opencray.app

import com.opencray.runtime.memory.MemoryStewardshipPlanStep

internal fun stewardshipPlanStepToMap(step: MemoryStewardshipPlanStep): Map<String, Any?> = buildMap {
  put("action", step.action.wireValue)
  put("outcome", step.outcome.wireValue)
  step.recordId?.let { recordId -> put("recordId", recordId) }
  step.candidateIndex?.let { candidateIndex -> put("candidateIndex", candidateIndex) }
  step.producedRecordId?.let { producedRecordId -> put("producedRecordId", producedRecordId) }
  step.reason?.let { reason -> put("reason", reason) }
}
