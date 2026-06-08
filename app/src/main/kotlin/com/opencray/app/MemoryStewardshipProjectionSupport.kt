package com.opencray.app

import com.opencray.runtime.memory.MemoryStewardshipPlanGraph
import com.opencray.runtime.memory.MemoryStewardshipPlanGraphEdge
import com.opencray.runtime.memory.MemoryStewardshipPlanGraphNode
import com.opencray.runtime.memory.MemoryStewardshipPlanStep

internal fun stewardshipPlanStepToMap(step: MemoryStewardshipPlanStep): Map<String, Any?> = buildMap {
  put("action", step.action.wireValue)
  put("outcome", step.outcome.wireValue)
  step.recordId?.let { recordId -> put("recordId", recordId) }
  step.candidateIndex?.let { candidateIndex -> put("candidateIndex", candidateIndex) }
  step.producedRecordId?.let { producedRecordId -> put("producedRecordId", producedRecordId) }
  step.reason?.let { reason -> put("reason", reason) }
}

internal fun stewardshipPlanGraphToMap(graph: MemoryStewardshipPlanGraph): Map<String, Any?> = mapOf(
  "nodes" to graph.nodes.map(::stewardshipPlanGraphNodeToMap),
  "edges" to graph.edges.map(::stewardshipPlanGraphEdgeToMap),
)

private fun stewardshipPlanGraphNodeToMap(node: MemoryStewardshipPlanGraphNode): Map<String, Any?> = buildMap {
  put("id", node.id)
  put("kind", node.kind)
  put("label", node.label)
  node.action?.let { action -> put("action", action) }
  node.outcome?.let { outcome -> put("outcome", outcome) }
  node.recordId?.let { recordId -> put("recordId", recordId) }
  node.candidateIndex?.let { candidateIndex -> put("candidateIndex", candidateIndex) }
  node.producedRecordId?.let { producedRecordId -> put("producedRecordId", producedRecordId) }
  node.reason?.let { reason -> put("reason", reason) }
}

private fun stewardshipPlanGraphEdgeToMap(edge: MemoryStewardshipPlanGraphEdge): Map<String, Any?> = mapOf(
  "from" to edge.from,
  "to" to edge.to,
  "kind" to edge.kind,
)
