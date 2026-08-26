package com.opencray.runtime.policy

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.PolicyDecision
import com.opencray.runtime.AgentToolResult
import java.nio.file.Path

internal data class ToolMetadataContextRequest(
  val targetKind: ToolTargetKind = ToolTargetKind.NONE,
  val primaryPath: Path? = null,
  val secondaryPath: Path? = null,
  val primaryTargetPath: String? = null,
  val secondaryTargetPath: String? = null,
  val workspaceRelation: ToolWorkspaceRelation? = null,
  val targetSummary: String? = null,
)

internal data class ToolPolicyPlan(
  val task: AgentTask,
  val toolName: String,
  val policyDecision: PolicyDecision,
  val metadataContext: ToolMetadataContext,
  val intentMetadata: Map<String, String> = emptyMap(),
)

internal class ToolPolicyPipeline(
  private val toolPolicyEvaluator: ToolPolicyEvaluator,
  private val toolPolicySupport: ToolPolicySupport,
  private val toolCapabilityClassifier: ToolCapabilityClassifier,
  private val toolTargetResolver: ToolTargetResolver,
  private val workspaceRoot: Path,
  private val readRoots: Set<Path> = setOf(workspaceRoot),
  private val writeRoots: Set<Path> = setOf(workspaceRoot),
) {
  fun plan(
    task: AgentTask,
    toolName: String,
    targetPath: Path? = null,
    destinationPath: Path? = null,
    metadataRequest: ToolMetadataContextRequest = ToolMetadataContextRequest(),
    intent: ToolRuntimeIntent? = null,
    approvedHostManagedReadRoots: Set<Path> = emptySet(),
  ): ToolPolicyPlan = ToolPolicyPlan(
    task = task,
    toolName = toolName,
    policyDecision = toolPolicyEvaluator.evaluate(
      ToolPolicyEvaluationRequest(
        task = task,
        toolName = toolName,
        toolClass = toolCapabilityClassifier.classifyPolicyToolClass(toolName),
        workspaceRoot = workspaceRoot,
        targetPath = targetPath,
        destinationPath = destinationPath,
        approvedReadRoots = readRoots,
        approvedWriteRoots = writeRoots,
        approvedHostManagedReadRoots = approvedHostManagedReadRoots,
        invocationFingerprint = (intent as? ExecutionIntent)?.commandPreview
          ?.takeIf(String::isNotBlank),
      ),
    ),
    metadataContext = metadataContext(
      toolName = toolName,
      request = metadataRequest,
    ),
    intentMetadata = intent?.metadata().orEmpty(),
  )

  fun metadataContext(
    toolName: String,
    request: ToolMetadataContextRequest = ToolMetadataContextRequest(),
  ): ToolMetadataContext {
    val resolvedPrimaryTargetPath = request.primaryTargetPath ?: request.primaryPath?.let { path ->
      toolTargetResolver.displayPathForTool(toolName = toolName, path = path)
    }
    val resolvedSecondaryTargetPath = request.secondaryTargetPath ?: request.secondaryPath?.let { path ->
      toolTargetResolver.displayPathForTool(toolName = toolName, path = path)
    }
    val resolvedTargetSummary = request.targetSummary ?: when {
      !resolvedPrimaryTargetPath.isNullOrBlank() && !resolvedSecondaryTargetPath.isNullOrBlank() ->
        "$resolvedPrimaryTargetPath -> $resolvedSecondaryTargetPath"
      !resolvedPrimaryTargetPath.isNullOrBlank() -> resolvedPrimaryTargetPath
      !resolvedSecondaryTargetPath.isNullOrBlank() -> resolvedSecondaryTargetPath
      else -> null
    }
    return ToolMetadataContext(
      targetKind = request.targetKind,
      workspaceRelation = request.workspaceRelation ?: toolTargetResolver.workspaceRelation(
        primary = request.primaryPath,
        secondary = request.secondaryPath,
      ),
      primaryTargetPath = resolvedPrimaryTargetPath,
      secondaryTargetPath = resolvedSecondaryTargetPath,
      targetSummary = resolvedTargetSummary,
    )
  }

  fun gate(
    plan: ToolPolicyPlan,
    affectedPaths: Map<String, String> = emptyMap(),
    askDetail: String,
    denyDetail: String,
  ): AgentToolResult? = toolPolicySupport.gateResult(
    ToolGateRequest(
      task = plan.task,
      toolName = plan.toolName,
      policyDecision = plan.policyDecision,
      affectedPaths = affectedPaths + plan.intentMetadata,
      metadataContext = plan.metadataContext,
      askDetail = askDetail,
      denyDetail = denyDetail,
    ),
  )

  fun gateFileMutation(
    plan: ToolPolicyPlan,
    affectedPaths: Map<String, String> = emptyMap(),
  ): AgentToolResult? = gate(
    plan = plan,
    affectedPaths = affectedPaths,
    askDetail = "Approval is required before ${plan.toolName} can run.",
    denyDetail = "Policy denied ${plan.toolName}.",
  )

  fun policyMetadata(
    plan: ToolPolicyPlan,
    includeOutcome: Boolean = false,
  ): Map<String, String> = toolPolicySupport.policyMetadata(
    task = plan.task,
    toolName = plan.toolName,
    policyDecision = plan.policyDecision,
    metadataContext = plan.metadataContext,
    includeOutcome = includeOutcome,
  ) + plan.intentMetadata

  fun commonMetadata(
    toolName: String,
    request: ToolMetadataContextRequest = ToolMetadataContextRequest(),
  ): Map<String, String> = toolPolicySupport.commonMetadata(
    toolName = toolName,
    metadataContext = metadataContext(
      toolName = toolName,
      request = request,
    ),
  )

  fun resultMetadata(
    plan: ToolPolicyPlan,
    metadata: Map<String, String> = emptyMap(),
    resultEnvelope: ToolResultEnvelope? = null,
    includeOutcome: Boolean = false,
  ): Map<String, String> = policyMetadata(
    plan = plan,
    includeOutcome = includeOutcome,
  ) + resultEnvelope?.metadata().orEmpty() + metadata

  fun resultMetadata(
    toolName: String,
    request: ToolMetadataContextRequest = ToolMetadataContextRequest(),
    metadata: Map<String, String> = emptyMap(),
    resultEnvelope: ToolResultEnvelope? = null,
  ): Map<String, String> = commonMetadata(
    toolName = toolName,
    request = request,
  ) + resultEnvelope?.metadata().orEmpty() + metadata
}
