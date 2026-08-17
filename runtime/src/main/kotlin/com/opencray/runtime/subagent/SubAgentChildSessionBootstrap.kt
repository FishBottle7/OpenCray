package com.opencray.runtime.subagent

import com.opencray.runtime.bootstrap.BootstrapContext
import com.opencray.runtime.bootstrap.BootstrapMode
import com.opencray.runtime.bootstrap.BootstrapSnippet
import com.opencray.runtime.bootstrap.BootstrapTrace
import com.opencray.runtime.context.AgentRuntimeSessionContext
import com.opencray.runtime.context.ContextInjectionPolicy
import com.opencray.runtime.context.RuntimeSoulProfile
import com.opencray.runtime.workingstate.WorkingState
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SerializedRuntimeSoulProfile(
  val presetName: String? = null,
  val displayName: String? = null,
  val voice: String? = null,
  val customGuidance: String? = null,
  val extensions: Map<String, String> = emptyMap(),
) {
  fun toRuntime(): RuntimeSoulProfile = RuntimeSoulProfile(
    presetName = presetName,
    displayName = displayName,
    voice = voice,
    customGuidance = customGuidance,
    extensions = extensions,
  )

  companion object {
    fun from(profile: RuntimeSoulProfile): SerializedRuntimeSoulProfile = SerializedRuntimeSoulProfile(
      presetName = profile.presetName,
      displayName = profile.displayName,
      voice = profile.voice,
      customGuidance = profile.customGuidance,
      extensions = profile.extensions,
    )
  }
}

@Serializable
data class SerializedContextInjectionPolicy(
  val soulContractEnabled: Boolean = true,
  val soulTurnPolicyEnabled: Boolean = true,
  val automaticMemoryInjectionEnabled: Boolean = true,
  val memoryDerivedPolicyEnabled: Boolean = true,
) {
  fun toRuntime(): ContextInjectionPolicy = ContextInjectionPolicy(
    soulContractEnabled = soulContractEnabled,
    soulTurnPolicyEnabled = soulTurnPolicyEnabled,
    automaticMemoryInjectionEnabled = automaticMemoryInjectionEnabled,
    memoryDerivedPolicyEnabled = memoryDerivedPolicyEnabled,
  )

  companion object {
    fun from(policy: ContextInjectionPolicy): SerializedContextInjectionPolicy =
      SerializedContextInjectionPolicy(
        soulContractEnabled = policy.soulContractEnabled,
        soulTurnPolicyEnabled = policy.soulTurnPolicyEnabled,
        automaticMemoryInjectionEnabled = policy.automaticMemoryInjectionEnabled,
        memoryDerivedPolicyEnabled = policy.memoryDerivedPolicyEnabled,
      )
  }
}

@Serializable
data class SerializedBootstrapSnippet(
  val name: String,
  val relativePath: String,
  val content: String,
  val sourceCharCount: Int,
  val truncated: Boolean,
) {
  fun toRuntime(): BootstrapSnippet = BootstrapSnippet(
    name = name,
    relativePath = relativePath,
    content = content,
    sourceCharCount = sourceCharCount,
    truncated = truncated,
  )

  companion object {
    fun from(snippet: BootstrapSnippet): SerializedBootstrapSnippet = SerializedBootstrapSnippet(
      name = snippet.name,
      relativePath = snippet.relativePath,
      content = snippet.content,
      sourceCharCount = snippet.sourceCharCount,
      truncated = snippet.truncated,
    )
  }
}

@Serializable
data class SerializedBootstrapTrace(
  val mode: String = BootstrapMode.NONE.wireValue,
  val visibleFileCount: Int = 0,
  val injectedFileCount: Int = 0,
  val omittedFileCount: Int = 0,
  val truncatedFileCount: Int = 0,
) {
  fun toRuntime(): BootstrapTrace = BootstrapTrace(
    mode = mode,
    visibleFileCount = visibleFileCount,
    injectedFileCount = injectedFileCount,
    omittedFileCount = omittedFileCount,
    truncatedFileCount = truncatedFileCount,
  )

  companion object {
    fun from(trace: BootstrapTrace): SerializedBootstrapTrace = SerializedBootstrapTrace(
      mode = trace.mode,
      visibleFileCount = trace.visibleFileCount,
      injectedFileCount = trace.injectedFileCount,
      omittedFileCount = trace.omittedFileCount,
      truncatedFileCount = trace.truncatedFileCount,
    )
  }
}

@Serializable
data class SerializedBootstrapContext(
  val mode: String = BootstrapMode.NONE.wireValue,
  val files: List<SerializedBootstrapSnippet> = emptyList(),
  val trace: SerializedBootstrapTrace = SerializedBootstrapTrace(),
) {
  fun toRuntime(): BootstrapContext = BootstrapContext(
    mode = BootstrapMode.fromWireValue(mode) ?: BootstrapMode.NONE,
    files = files.map(SerializedBootstrapSnippet::toRuntime),
    trace = trace.toRuntime(),
  )

  companion object {
    fun from(context: BootstrapContext): SerializedBootstrapContext = SerializedBootstrapContext(
      mode = context.mode.wireValue,
      files = context.files.map(SerializedBootstrapSnippet::from),
      trace = SerializedBootstrapTrace.from(context.trace),
    )
  }
}

@Serializable
data class SubAgentParentSessionSnapshot(
  val sessionPolicyText: String? = null,
  val soulProfile: SerializedRuntimeSoulProfile? = null,
  val injectionPolicy: SerializedContextInjectionPolicy = SerializedContextInjectionPolicy(),
  val memoryToolsEnabled: Boolean = true,
  val bootstrapContext: SerializedBootstrapContext = SerializedBootstrapContext(),
  val workingState: WorkingState = WorkingState(),
) {
  fun toSessionContext(): AgentRuntimeSessionContext = AgentRuntimeSessionContext(
    sessionPolicyText = sessionPolicyText,
    soulProfile = soulProfile?.toRuntime(),
    injectionPolicy = injectionPolicy.toRuntime(),
    memoryToolsEnabled = memoryToolsEnabled,
    bootstrapContext = bootstrapContext.toRuntime(),
    workingState = workingState,
  )

  companion object {
    fun from(sessionContext: AgentRuntimeSessionContext): SubAgentParentSessionSnapshot =
      SubAgentParentSessionSnapshot(
        sessionPolicyText = sessionContext.sessionPolicyText,
        soulProfile = sessionContext.soulProfile?.let(SerializedRuntimeSoulProfile::from),
        injectionPolicy = SerializedContextInjectionPolicy.from(sessionContext.injectionPolicy),
        memoryToolsEnabled = sessionContext.memoryToolsEnabled,
        bootstrapContext = SerializedBootstrapContext.from(sessionContext.bootstrapContext),
        workingState = sessionContext.workingState,
      )
  }
}

@Serializable
data class SubAgentChildSessionBootstrap(
  val parentSessionId: String,
  val parentSession: SubAgentParentSessionSnapshot,
  val childTask: SubAgentTask,
  val parentGoalSummary: String? = null,
  val parentObservationLines: List<String> = emptyList(),
) {
  init {
    require(parentSessionId.isNotBlank()) {
      "SubAgentChildSessionBootstrap parentSessionId must not be blank."
    }
  }

  fun buildInitialContext(
    contextBuilder: SubAgentContextBuilder = SubAgentContextBuilder(),
  ): SubAgentContextBuildResult = contextBuilder.build(
    SubAgentContextBuildRequest(
      parentSessionContext = parentSession.toSessionContext(),
      childTask = childTask,
      parentGoalSummary = parentGoalSummary,
      parentObservationLines = parentObservationLines,
    ),
  )

  companion object {
    fun fromParentSession(
      parentSessionId: String,
      parentSessionContext: AgentRuntimeSessionContext,
      childTask: SubAgentTask,
      parentGoalSummary: String? = null,
      parentObservationLines: List<String> = emptyList(),
    ): SubAgentChildSessionBootstrap = SubAgentChildSessionBootstrap(
      parentSessionId = parentSessionId,
      parentSession = SubAgentParentSessionSnapshot.from(parentSessionContext),
      childTask = childTask,
      parentGoalSummary = parentGoalSummary,
      parentObservationLines = parentObservationLines,
    )
  }
}

object SubAgentChildSessionBootstrapMetadata {
  const val KEY_BOOTSTRAP_JSON: String = "_host.subagentChildSessionBootstrapJson"

  fun encodeToMetadata(
    bootstrap: SubAgentChildSessionBootstrap,
    json: Json,
  ): Map<String, String> = mapOf(
    KEY_BOOTSTRAP_JSON to json.encodeToString(bootstrap),
  )

  fun decodeFromMetadata(
    metadata: Map<String, String>,
    json: Json,
  ): SubAgentChildSessionBootstrap? = metadata[KEY_BOOTSTRAP_JSON]
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?.let { encoded ->
      runCatching {
        json.decodeFromString<SubAgentChildSessionBootstrap>(encoded)
      }.getOrNull()
    }
}
