package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.persistence.model.MemoryRecord
import com.opencray.runtime.context.AgentRuntimeSessionContext
import com.opencray.runtime.context.ContextManager
import com.opencray.runtime.context.ContextManagerConfig
import com.opencray.runtime.context.PromptAssemblyInput
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.context.RuntimeSoulProfile
import com.opencray.runtime.context.TranscriptWindowBuilder
import com.opencray.runtime.context.TranscriptWindowConfig
import com.opencray.runtime.memory.MemoryKind
import com.opencray.runtime.memory.MemoryPolicy
import com.opencray.runtime.memory.MemoryRecallBudget
import com.opencray.runtime.memory.MemoryRecallRequest
import com.opencray.runtime.memory.MemoryRecordExtensionKeys
import com.opencray.runtime.memory.MemoryRetriever
import com.opencray.runtime.memory.MemoryScope
import com.opencray.runtime.memory.MemoryStatus
import org.junit.Test
import java.util.Arrays
import java.util.Locale

class RuntimePerformanceSmokeTest {
  @Test
  fun emitRuntimePerformanceMetrics() {
    val retriever = MemoryRetriever(
      policy = MemoryPolicy(
        recallBudget = MemoryRecallBudget(
          maxRecords = 8,
          maxChars = 1_600,
          maxRecordsPerKind = 3,
        ),
      ),
      clock = { NOW_EPOCH_MS },
    )
    val memoryRecords = buildMemoryCorpus(recordCount = 240)
    val recallRequest = MemoryRecallRequest(
      sessionId = "session-main",
      workspaceId = "workspace-main",
      userInput = "请继续用中文，别覆盖用户改动，并确认项目里的 Gradle、Python、权限和工作区设置。",
    )
    val recallResult = retriever.retrieve(
      records = memoryRecords,
      request = recallRequest,
    )
    val memoryMetric = measureMetric(
      name = "memory_retrieve",
      warmupCount = 80,
      iterationCount = 240,
    ) {
      val result = retriever.retrieve(
        records = memoryRecords,
        request = recallRequest,
      )
      blackhole += result.memories.size + result.matchedRecordCount + result.omittedRecordCount
    }

    val contextManager = ContextManager(
      transcriptWindowBuilder = TranscriptWindowBuilder(
        TranscriptWindowConfig(
          maxMessages = 10,
          maxCharsPerMessage = 240,
        ),
      ),
      config = ContextManagerConfig(
        maxInjectedMemoryRecords = 4,
      ),
    )
    val promptInput = PromptAssemblyInput(
      task = promptTask(),
      baseSystemPrompt = "You are OpenCray. Help the user finish the task on mobile.",
      sessionContext = AgentRuntimeSessionContext(
        sessionPolicyText = "Prefer concise Chinese replies and do not overwrite user edits.",
        soulProfile = RuntimeSoulProfile(
          presetName = "STEADY",
          displayName = "OpenCray",
          customGuidance = "Stay grounded, concrete, and mobile-first.",
        ),
        recalledMemory = recallResult,
      ),
      toolDefinitions = listOf(
        AgentToolDefinition(name = "Read", description = "Read files from the workspace."),
        AgentToolDefinition(name = "Write", description = "Write files inside allowed paths."),
        AgentToolDefinition(name = "python_exec", description = "Run Python for data or document handling."),
      ),
      liveConversation = buildConversation(),
    )
    val contextMetric = measureMetric(
      name = "context_prepare",
      warmupCount = 80,
      iterationCount = 240,
    ) {
      val managed = contextManager.prepare(promptInput)
      blackhole += managed.report.injectedMemoryRecordCount + managed.transcriptWindow.messages.size
    }

    println(
      "PERF|memory_retrieve|records=${memoryRecords.size}|iterations=${memoryMetric.iterationCount}" +
        "|avg_ms=${memoryMetric.avgMs}" +
        "|p50_ms=${memoryMetric.p50Ms}" +
        "|p95_ms=${memoryMetric.p95Ms}" +
        "|max_ms=${memoryMetric.maxMs}",
    )
    println(
      "PERF|context_prepare|messages=${promptInput.liveConversation.size}|matched_memory=${recallResult.matchedRecordCount}" +
        "|iterations=${contextMetric.iterationCount}" +
        "|avg_ms=${contextMetric.avgMs}" +
        "|p50_ms=${contextMetric.p50Ms}" +
        "|p95_ms=${contextMetric.p95Ms}" +
        "|max_ms=${contextMetric.maxMs}",
    )
  }

  private fun buildMemoryCorpus(recordCount: Int): List<MemoryRecord> =
    (0 until recordCount).map { index ->
      when (index % 4) {
        0 -> memoryRecord(
          id = "pref-$index",
          content = "默认使用中文回答，并在必要时解释 Gradle、Python 和工作区设置。",
          kind = MemoryKind.USER_PREFERENCE,
          scope = MemoryScope.USER,
          updatedAtEpochMs = NOW_EPOCH_MS - (index * 10L),
        )
        1 -> memoryRecord(
          id = "rule-$index",
          content = "未经确认不要覆盖用户改动，尤其不要跨工作区改写文件。",
          kind = MemoryKind.DURABLE_INSTRUCTION,
          scope = MemoryScope.WORKSPACE,
          workspaceId = "workspace-main",
          updatedAtEpochMs = NOW_EPOCH_MS - (index * 10L),
        )
        2 -> memoryRecord(
          id = "fact-$index",
          content = "当前项目使用 Gradle、Android、本地 Python 运行时和可配置权限工作流。",
          kind = MemoryKind.PROJECT_FACT,
          scope = MemoryScope.WORKSPACE,
          workspaceId = "workspace-main",
          updatedAtEpochMs = NOW_EPOCH_MS - (index * 10L),
        )
        else -> memoryRecord(
          id = "task-$index",
          content = "补完手机端文档处理链路说明，并继续检查权限配置。",
          kind = MemoryKind.TASK_COMMITMENT,
          scope = MemoryScope.SESSION,
          sourceSessionId = "session-main",
          updatedAtEpochMs = NOW_EPOCH_MS - (index * 10L),
        )
      }
    }

  private fun buildConversation(): List<RuntimeConversationMessage> = listOf(
    RuntimeConversationMessage(
      role = RuntimeConversationRole.USER,
      content = "帮我搜集资料并整理成文档，注意手机端权限别配错。",
    ),
    RuntimeConversationMessage(
      role = RuntimeConversationRole.ASSISTANT,
      content = "我会先规划任务，再看权限、记忆和可用工具。",
    ),
    RuntimeConversationMessage(
      role = RuntimeConversationRole.USER,
      content = "文档里别写没做完的功能。",
    ),
    RuntimeConversationMessage(
      role = RuntimeConversationRole.ASSISTANT,
      content = "收到。我会只写已经落地并能在代码里对应上的部分，同时补上测试依据和关键限制说明。",
    ),
    RuntimeConversationMessage(
      role = RuntimeConversationRole.USER,
      content = "还要强调它能直接在手机上处理文件、运行 Python、并且权限可控。",
    ),
    RuntimeConversationMessage(
      role = RuntimeConversationRole.ASSISTANT,
      content = "我会把这些写进产品定位、权限审批链和关键功能设计里，并尽量收住措辞，避免写得太散。",
    ),
    RuntimeConversationMessage(
      role = RuntimeConversationRole.USER,
      content = "另外它越用越懂用户，这部分要落到记忆系统和协作风格系统。",
    ),
    RuntimeConversationMessage(
      role = RuntimeConversationRole.ASSISTANT,
      content = "明白。后面我会把记忆分层、协作风格叠加和本地存储安全性放到同一段里解释，不拆成概念口号。",
    ),
    RuntimeConversationMessage(
      role = RuntimeConversationRole.USER,
      content = "那你继续。",
    ),
    RuntimeConversationMessage(
      role = RuntimeConversationRole.ASSISTANT,
      content = "我先补性能烟测，然后用跑出来的数据改第四章运行速度。",
    ),
  )

  private fun memoryRecord(
    id: String,
    content: String,
    kind: MemoryKind,
    scope: MemoryScope,
    status: MemoryStatus = MemoryStatus.ACTIVE,
    sourceSessionId: String = "session-source",
    workspaceId: String? = null,
    updatedAtEpochMs: Long = NOW_EPOCH_MS - 2_000L,
  ): MemoryRecord = MemoryRecord(
    id = id,
    content = content,
    tags = listOf(
      "kind:${kind.name.lowercase()}",
      "scope:${scope.name.lowercase()}",
      "status:${status.name.lowercase()}",
    ),
    createdAtEpochMs = updatedAtEpochMs - 1_000L,
    updatedAtEpochMs = updatedAtEpochMs,
    extensions = buildMap {
      put(MemoryRecordExtensionKeys.KIND, kind.name.lowercase())
      put(MemoryRecordExtensionKeys.SCOPE, scope.name.lowercase())
      put(MemoryRecordExtensionKeys.STATUS, status.name.lowercase())
      put(MemoryRecordExtensionKeys.SOURCE_SESSION_ID, sourceSessionId)
      workspaceId?.let { put(MemoryRecordExtensionKeys.WORKSPACE_ID, it) }
    },
  )

  private fun promptTask(): AgentTask = AgentTask(
    id = "task-runtime-performance",
    type = AgentTaskType.PROMPT,
    input = "整理手机端私人助理的设计文档。",
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "TEST_ALLOW",
    ),
    createdAtEpochMs = 100L,
  )

  private fun measureMetric(
    name: String,
    warmupCount: Int,
    iterationCount: Int,
    block: () -> Unit,
  ): PerformanceMetric {
    repeat(warmupCount) { block() }
    val samplesNs = LongArray(iterationCount)
    repeat(iterationCount) { index ->
      val startedAt = System.nanoTime()
      block()
      samplesNs[index] = System.nanoTime() - startedAt
    }
    val sortedNs = samplesNs.copyOf()
    Arrays.sort(sortedNs)
    val avgNs = samplesNs.average()
    return PerformanceMetric(
      name = name,
      iterationCount = iterationCount,
      avgMs = formatMs(avgNs),
      p50Ms = formatMs(percentile(sortedNs, 0.50)),
      p95Ms = formatMs(percentile(sortedNs, 0.95)),
      maxMs = formatMs(sortedNs.last().toDouble()),
    )
  }

  private fun percentile(sortedNs: LongArray, ratio: Double): Double {
    val position = ((sortedNs.size - 1) * ratio).toInt().coerceIn(0, sortedNs.size - 1)
    return sortedNs[position].toDouble()
  }

  private fun formatMs(nanos: Double): String =
    String.format(Locale.US, "%.3f", nanos / 1_000_000.0)

  private data class PerformanceMetric(
    val name: String,
    val iterationCount: Int,
    val avgMs: String,
    val p50Ms: String,
    val p95Ms: String,
    val maxMs: String,
  )

  private companion object {
    const val NOW_EPOCH_MS: Long = 1_700_000_000_000L
    var blackhole: Int = 0
  }
}
