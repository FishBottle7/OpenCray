package com.opencray.runtime.policy

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.policy.ModePolicy
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.WorkspaceBoundary
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ToolPolicyPipelineTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun planCarriesResolvedMixedScopeMetadataForImportStyleActions() {
    val workspaceRoot = temporaryFolder.newFolder("pipeline-workspace").toPath()
    val externalRoot = temporaryFolder.newFolder("pipeline-external").toPath()
    val source = externalRoot.resolve("photo.txt")
    val destination = workspaceRoot.resolve("imports").resolve("photo.txt")
    Files.createDirectories(destination.parent)
    Files.write(source, "camera".toByteArray(StandardCharsets.UTF_8))
    val pipeline = pipeline(workspaceRoot = workspaceRoot, readRoots = setOf(workspaceRoot, externalRoot))
    val resolver = resolver(workspaceRoot = workspaceRoot, readRoots = setOf(workspaceRoot, externalRoot))
    val resolvedSource = resolver.resolveReadablePath(
      candidate = source.toString(),
      label = "import source",
      defaultToRoot = false,
    )
    val resolvedDestination = resolver.resolveWritablePath(
      candidate = "imports/photo.txt",
      label = "import destination",
      defaultToRoot = false,
    )

    val plan = pipeline.plan(
      task = task(metadata = mapOf("chatMode" to "AUTO")),
      toolName = "ImportFile",
      targetPath = resolvedDestination,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.FILE,
        primaryPath = resolvedSource,
        secondaryPath = resolvedDestination,
        primaryTargetPath = resolver.displayModelPath(resolvedSource),
        secondaryTargetPath = resolver.displayWritablePath(resolvedDestination),
      ),
    )

    assertEquals("ALLOW_AUTO_STANDARD", plan.policyDecision.reasonCode)
    assertEquals(ToolTargetKind.FILE, plan.metadataContext.targetKind)
    assertEquals(ToolWorkspaceRelation.MIXED, plan.metadataContext.workspaceRelation)
    assertTrue(requireNotNull(plan.metadataContext.primaryTargetPath).endsWith("/photo.txt"))
    assertEquals("imports/photo.txt", plan.metadataContext.secondaryTargetPath)
    assertTrue(requireNotNull(plan.metadataContext.targetSummary).contains("imports/photo.txt"))
  }

  @Test
  fun gateFileMutationBuildsHighRiskApprovalResultFromSharedPipeline() {
    val workspaceRoot = temporaryFolder.newFolder("pipeline-delete").toPath()
    val rawTarget = workspaceRoot.resolve("notes.txt")
    Files.write(rawTarget, "keep".toByteArray(StandardCharsets.UTF_8))
    val pipeline = pipeline(workspaceRoot = workspaceRoot)
    val resolver = resolver(workspaceRoot = workspaceRoot, readRoots = setOf(workspaceRoot))
    val target = resolver.resolveWritablePath(
      candidate = "notes.txt",
      label = "delete target",
      defaultToRoot = false,
    )

    val plan = pipeline.plan(
      task = task(metadata = mapOf("chatMode" to "SAFE")),
      toolName = "workspace_delete_file",
      targetPath = target,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.FILE,
        primaryPath = target,
      ),
    )
    val result = requireNotNull(
      pipeline.gateFileMutation(
        plan = plan,
        affectedPaths = mapOf("path" to "notes.txt"),
      ),
    )

    assertEquals(AgentToolResultStatus.DENIED, result.status)
    assertEquals("HIGH_RISK_APPROVAL_REQUIRED", result.errorCode)
    assertEquals("ASK_SAFE_DESTRUCTIVE_HIGH_RISK", result.metadata["policyReasonCode"])
    assertEquals("HIGH_RISK", result.metadata["approvalRisk"])
    assertEquals("delete_file", result.metadata["capabilityKind"])
    assertEquals("file", result.metadata["targetKind"])
    assertEquals("inside_workspace", result.metadata["workspaceRelation"])
    assertEquals("notes.txt", result.metadata["primaryTargetPath"])
    assertTrue(result.content.contains("High-risk approval required"))
  }

  @Test
  fun gateFileMutationAllowsApprovedManagedRootsOutsideWorkspace() {
    val workspaceRoot = temporaryFolder.newFolder("pipeline-managed-workspace").toPath()
    val managedRoot = temporaryFolder.newFolder("pipeline-managed-root").toPath()
    val target = managedRoot.resolve("find-skills")
    val pipeline = pipeline(
      workspaceRoot = workspaceRoot,
      readRoots = setOf(workspaceRoot),
      writeRoots = setOf(workspaceRoot, managedRoot),
    )

    val plan = pipeline.plan(
      task = task(metadata = mapOf("chatMode" to "SAFE")),
      toolName = "SkillsAdd",
      targetPath = target,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.DIRECTORY,
        primaryPath = target,
        primaryTargetPath = target.toString().replace('\\', '/'),
      ),
    )
    val result = requireNotNull(
      pipeline.gateFileMutation(
        plan = plan,
        affectedPaths = mapOf("path" to target.toString().replace('\\', '/')),
      ),
    )

    assertEquals(AgentToolResultStatus.DENIED, result.status)
    assertEquals("APPROVAL_REQUIRED", result.errorCode)
    assertEquals("ASK_SAFE_WRITE", result.metadata["policyReasonCode"])
    assertEquals("outside_workspace", result.metadata["workspaceRelation"])
    assertEquals("directory", result.metadata["targetKind"])
    assertEquals("install_skill", result.metadata["capabilityKind"])
  }

  @Test
  fun policyMetadataCarriesExplicitExecutionIntentMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("pipeline-intent-execution").toPath()
    val pipeline = pipeline(workspaceRoot = workspaceRoot)
    val resolver = resolver(workspaceRoot = workspaceRoot, readRoots = setOf(workspaceRoot))
    val workingDirectory = resolver.resolveWritablePath(
      candidate = ".",
      label = "working directory",
      defaultToRoot = true,
    )

    val plan = pipeline.plan(
      task = task(metadata = mapOf("chatMode" to "AUTO")),
      toolName = "command_exec",
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.WORKING_DIRECTORY,
        primaryPath = workingDirectory,
        primaryTargetPath = resolver.displayModelPath(workingDirectory),
        targetSummary = "git status",
      ),
      intent = ExecutionIntent(
        kind = ExecutionIntentKind.HOST_COMMAND,
        transport = ExecutionTransport.FOREGROUND,
        commandPreview = "git status",
        workingDirectory = resolver.displayModelPath(workingDirectory),
      ),
    )

    val metadata = pipeline.policyMetadata(plan)

    assertEquals("execution", metadata["intentCategory"])
    assertEquals("host_command", metadata["executionIntentKind"])
    assertEquals("foreground", metadata["executionTransport"])
    assertEquals("git status", metadata["executionCommandPreview"])
    assertEquals(".", metadata["executionWorkingDirectory"])
  }

  @Test
  fun gateCarriesExplicitProcessLifecycleIntentMetadataIntoApprovalResult() {
    val workspaceRoot = temporaryFolder.newFolder("pipeline-intent-process").toPath()
    val pipeline = pipeline(workspaceRoot = workspaceRoot)
    val resolver = resolver(workspaceRoot = workspaceRoot, readRoots = setOf(workspaceRoot))
    val workingDirectory = resolver.resolveWritablePath(
      candidate = ".",
      label = "working directory",
      defaultToRoot = true,
    )

    val plan = pipeline.plan(
      task = task(metadata = mapOf("chatMode" to "SAFE")),
      toolName = "ProcessTerminate",
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.PROCESS,
        primaryPath = workingDirectory,
        primaryTargetPath = resolver.displayModelPath(workingDirectory),
        targetSummary = "proc-1234",
      ),
      intent = ProcessLifecycleIntent(
        kind = ProcessLifecycleIntentKind.TERMINATE,
        processId = "proc-1234",
        workingDirectory = resolver.displayModelPath(workingDirectory),
      ),
    )

    val result = requireNotNull(
      pipeline.gate(
        plan = plan,
        affectedPaths = mapOf("processId" to "proc-1234"),
        askDetail = "Approval is required before ProcessTerminate can run.",
        denyDetail = "Policy denied ProcessTerminate.",
      ),
    )

    assertEquals(AgentToolResultStatus.DENIED, result.status)
    assertEquals("process_lifecycle", result.metadata["intentCategory"])
    assertEquals("terminate", result.metadata["processLifecycleIntentKind"])
    assertEquals("proc-1234", result.metadata["intentProcessId"])
    assertEquals(".", result.metadata["intentWorkingDirectory"])
  }

  @Test
  fun resultMetadataAddsStableResultLimitContractForPlannedTools() {
    val workspaceRoot = temporaryFolder.newFolder("pipeline-result-contract").toPath()
    val pipeline = pipeline(workspaceRoot = workspaceRoot)
    val resolver = resolver(workspaceRoot = workspaceRoot, readRoots = setOf(workspaceRoot))
    val target = resolver.resolveReadablePath(
      candidate = ".",
      label = "search root",
      defaultToRoot = true,
    )

    val plan = pipeline.plan(
      task = task(metadata = mapOf("chatMode" to "AUTO")),
      toolName = "LS",
      targetPath = target,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.DIRECTORY,
        primaryPath = target,
      ),
    )

    val metadata = pipeline.resultMetadata(
      plan = plan,
      metadata = mapOf("entryCount" to "1"),
      resultEnvelope = ToolResultEnvelope(
        limitApplied = true,
        truncated = true,
        limitKind = ToolResultLimitKind.DIRECTORY_ENTRY_LIMIT,
      ),
    )

    assertEquals("ALLOW_AUTO_STANDARD", metadata["policyReasonCode"])
    assertEquals("directory", metadata["targetKind"])
    assertEquals("true", metadata["resultLimitApplied"])
    assertEquals("true", metadata["resultTruncated"])
    assertEquals("directory_entry_limit", metadata["resultLimitKind"])
    assertEquals("1", metadata["entryCount"])
  }

  @Test
  fun resultMetadataAddsStableResultLimitContractWithoutPlan() {
    val workspaceRoot = temporaryFolder.newFolder("pipeline-result-contract-common").toPath()
    val pipeline = pipeline(workspaceRoot = workspaceRoot)

    val metadata = pipeline.resultMetadata(
      toolName = "ProcessRead",
      request = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.PROCESS,
        primaryTargetPath = ".",
        targetSummary = "proc-123",
      ),
      metadata = mapOf("processId" to "proc-123"),
      resultEnvelope = ToolResultEnvelope(
        limitApplied = true,
        truncated = false,
        limitKind = ToolResultLimitKind.PROCESS_OUTPUT_BYTE_LIMIT,
      ),
    )

    assertEquals("process", metadata["targetKind"])
    assertEquals(".", metadata["primaryTargetPath"])
    assertEquals("proc-123", metadata["targetSummary"])
    assertEquals("true", metadata["resultLimitApplied"])
    assertEquals("false", metadata["resultTruncated"])
    assertEquals("process_output_byte_limit", metadata["resultLimitKind"])
    assertEquals("proc-123", metadata["processId"])
  }

  private fun pipeline(
    workspaceRoot: java.nio.file.Path,
    readRoots: Set<java.nio.file.Path> = setOf(workspaceRoot),
    writeRoots: Set<java.nio.file.Path> = setOf(workspaceRoot),
  ): ToolPolicyPipeline {
    val resolver = resolver(workspaceRoot = workspaceRoot, readRoots = readRoots)
    return ToolPolicyPipeline(
      toolPolicyEvaluator = ToolPolicyEvaluator(modePolicy = ModePolicy()),
      toolPolicySupport = ToolPolicySupport(),
      toolCapabilityClassifier = ToolCapabilityClassifier(),
      toolTargetResolver = resolver,
      workspaceRoot = workspaceRoot,
      readRoots = readRoots,
      writeRoots = writeRoots,
    )
  }

  private fun resolver(
    workspaceRoot: java.nio.file.Path,
    readRoots: Set<java.nio.file.Path>,
  ): ToolTargetResolver = ToolTargetResolver(
    readBoundary = WorkspaceBoundary(readRoots),
    writeBoundary = WorkspaceBoundary(setOf(workspaceRoot)),
  )

  private fun task(
    metadata: Map<String, String> = emptyMap(),
    policyDecision: PolicyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "HOST_ALLOW",
    ),
  ): AgentTask = AgentTask(
    id = "task-1",
    type = AgentTaskType.TOOL_CALL,
    input = """{"type":"tool_call"}""",
    policyDecision = policyDecision,
    metadata = metadata,
    createdAtEpochMs = 1_000L,
  )
}
