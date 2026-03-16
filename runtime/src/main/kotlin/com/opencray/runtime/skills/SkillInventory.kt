package com.opencray.runtime.skills

import com.opencray.skills.LoadedSkill
import com.opencray.skills.SkillPermissionRule
import com.opencray.skills.SkillExecutionContext
import com.opencray.skills.SkillInvocationControl
import com.opencray.skills.SkillLoadReport
import com.opencray.skills.SkillLoader
import java.io.File

data class VisibleSkill(
  val name: String,
  val description: String,
  val relativePath: String,
  val invocationControl: SkillInvocationControl,
  val userInvocable: Boolean,
  val executionContext: SkillExecutionContext,
)

data class VisibleSkillTrace(
  val name: String,
  val relativePath: String,
  val invocationControl: String,
  val userInvocable: Boolean,
  val executionContext: String,
  val descriptionPreview: String,
)

data class SkillInventoryTrace(
  val visible: List<VisibleSkillTrace> = emptyList(),
  val totalVisibleSkillCount: Int = 0,
  val implicitSkillCount: Int = 0,
  val omittedTraceSkillCount: Int = 0,
  val invalidSkillCount: Int = 0,
)

data class SkillCatalogEntry(
  val name: String,
  val description: String,
  val relativePath: String,
  val invocationControl: SkillInvocationControl,
  val userInvocable: Boolean,
  val executionContext: SkillExecutionContext,
  val markdownBody: String,
  val toolPermissions: List<SkillPermissionRule> = emptyList(),
)

data class SkillCatalog(
  val inventory: SkillInventory = SkillInventory(),
  val skillsByName: Map<String, SkillCatalogEntry> = emptyMap(),
)

data class ActiveSkillCapsule(
  val name: String,
  val description: String,
  val relativePath: String,
  val invocationControl: String,
  val executionContext: String,
  val activationSource: String,
  val markdownBody: String,
  val toolPermissionSummary: List<String> = emptyList(),
  val allowedToolKeys: Set<String> = emptySet(),
) {
  val toolRestrictionEnabled: Boolean
    get() = allowedToolKeys.isNotEmpty()
}

data class ActiveSkillTrace(
  val name: String? = null,
  val relativePath: String? = null,
  val invocationControl: String? = null,
  val executionContext: String? = null,
  val activationSource: String? = null,
  val toolRestrictionEnabled: Boolean = false,
  val allowedToolKeys: List<String> = emptyList(),
  val truncated: Boolean = false,
)

data class SkillInventory(
  val skills: List<VisibleSkill> = emptyList(),
  val invalidSkillCount: Int = 0,
  val trace: SkillInventoryTrace = SkillInventoryTrace(),
) {
  val visibleSkillCount: Int
    get() = skills.size

  val implicitSkillCount: Int
    get() = skills.count { skill ->
      skill.invocationControl == SkillInvocationControl.EXPLICIT_AND_IMPLICIT
    }
}

data class RenderedSkillInventory(
  val text: String = "",
  val injectedSkillCount: Int = 0,
  val omittedSkillCount: Int = 0,
)

data class RenderedActiveSkillCapsule(
  val text: String = "",
  val trace: ActiveSkillTrace = ActiveSkillTrace(),
)

data class SkillInventoryPromptLayerConfig(
  val maxSkills: Int = 8,
  val maxDescriptionChars: Int = 120,
) {
  init {
    require(maxSkills >= 1) { "SkillInventoryPromptLayerConfig maxSkills must be >= 1." }
    require(maxDescriptionChars >= 24) {
      "SkillInventoryPromptLayerConfig maxDescriptionChars must be >= 24."
    }
  }
}

data class ActiveSkillPromptLayerConfig(
  val maxBodyChars: Int = 3_200,
  val maxPermissionEntries: Int = 8,
) {
  init {
    require(maxBodyChars >= 240) { "ActiveSkillPromptLayerConfig maxBodyChars must be >= 240." }
    require(maxPermissionEntries >= 1) {
      "ActiveSkillPromptLayerConfig maxPermissionEntries must be >= 1."
    }
  }
}

class SkillInventoryPromptLayer(
  private val config: SkillInventoryPromptLayerConfig = SkillInventoryPromptLayerConfig(),
) {
  fun render(inventory: SkillInventory): RenderedSkillInventory {
    if (inventory.skills.isEmpty()) {
      return RenderedSkillInventory()
    }
    val injectedSkills = inventory.skills.take(config.maxSkills)
    val omittedSkillCount = (inventory.skills.size - injectedSkills.size).coerceAtLeast(0)
    return RenderedSkillInventory(
      text = buildString {
        appendLine("Visible skills are available from configured skills roots.")
        appendLine("Use skill_read to load the full SKILL.md before relying on a skill's workflow.")
        appendLine()
        injectedSkills.forEach { skill ->
          append("- name=")
          append(skill.name)
          append(" invocation=")
          append(skill.invocationControl.serializedValue())
          append(" user_invocable=")
          append(skill.userInvocable)
          append(" execution_context=")
          append(skill.executionContext.serializedValue())
          append(" path=")
          append(skill.relativePath)
          append(" description=")
          append(skill.description.trim().take(config.maxDescriptionChars))
          if (skill.description.trim().length > config.maxDescriptionChars) {
            append("…")
          }
          appendLine()
        }
        if (omittedSkillCount > 0 || inventory.invalidSkillCount > 0) {
          appendLine()
        }
        if (omittedSkillCount > 0) {
          appendLine(
            "Omitted $omittedSkillCount additional visible skill(s) from this prompt layer due to skill inventory budget.",
          )
        }
        if (inventory.invalidSkillCount > 0) {
          append("Ignored ${inventory.invalidSkillCount} invalid skill file(s) during inventory assembly.")
        }
      }.trim(),
      injectedSkillCount = injectedSkills.size,
      omittedSkillCount = omittedSkillCount,
    )
  }
}

class ActiveSkillPromptLayer(
  private val config: ActiveSkillPromptLayerConfig = ActiveSkillPromptLayerConfig(),
) {
  fun render(capsule: ActiveSkillCapsule?): RenderedActiveSkillCapsule {
    capsule ?: return RenderedActiveSkillCapsule()
    val trimmedBody = capsule.markdownBody.trim()
    val truncated = trimmedBody.length > config.maxBodyChars
    val permissionSummary = capsule.toolPermissionSummary.take(config.maxPermissionEntries)
    return RenderedActiveSkillCapsule(
      text = buildString {
        appendLine("A skill is now active for this run.")
        append("- name=")
        append(capsule.name)
        append(" invocation=")
        append(capsule.invocationControl)
        append(" execution_context=")
        append(capsule.executionContext)
        append(" activation_source=")
        appendLine(capsule.activationSource)
        append("- path=")
        appendLine(capsule.relativePath)
        append("- description=")
        appendLine(capsule.description.trim())
        if (permissionSummary.isNotEmpty()) {
          append("- tool_permissions=")
          appendLine(permissionSummary.joinToString(separator = ","))
        }
        if (capsule.toolRestrictionEnabled) {
          append("- allowed_tools=")
          appendLine(capsule.allowedToolKeys.sorted().joinToString(separator = ","))
        }
        appendLine()
        appendLine("[Instructions]")
        if (truncated) {
          append(trimmedBody.take(config.maxBodyChars).trimEnd())
          appendLine()
          append("... [truncated]")
        } else {
          append(trimmedBody.ifBlank { "<empty body>" })
        }
      }.trim(),
      trace = ActiveSkillTrace(
        name = capsule.name,
        relativePath = capsule.relativePath,
        invocationControl = capsule.invocationControl,
        executionContext = capsule.executionContext,
        activationSource = capsule.activationSource,
        toolRestrictionEnabled = capsule.toolRestrictionEnabled,
        allowedToolKeys = capsule.allowedToolKeys.sorted(),
        truncated = truncated,
      ),
    )
  }
}

class SkillCatalogResolver(
  private val loader: (Iterable<File>) -> SkillLoadReport = SkillLoader::load,
) {
  fun resolve(roots: Iterable<File>): SkillCatalog {
    val report = loader(roots)
    val catalogEntries = report.registry.allSkills().associate { skill ->
      skill.name to toCatalogEntry(skill)
    }
    val visibleSkills = catalogEntries.values.map(::toVisibleSkill)
    return SkillCatalog(
      inventory = SkillInventory(
        skills = visibleSkills,
        invalidSkillCount = report.invalidSkills.size,
        trace = SkillInventoryTrace(
          visible = visibleSkills.take(MAX_TRACE_SKILLS).map(::toTrace),
          totalVisibleSkillCount = visibleSkills.size,
          implicitSkillCount = visibleSkills.count { skill ->
            skill.invocationControl == SkillInvocationControl.EXPLICIT_AND_IMPLICIT
          },
          omittedTraceSkillCount = (visibleSkills.size - MAX_TRACE_SKILLS).coerceAtLeast(0),
          invalidSkillCount = report.invalidSkills.size,
        ),
      ),
      skillsByName = catalogEntries,
    )
  }

  private fun toCatalogEntry(skill: LoadedSkill): SkillCatalogEntry = SkillCatalogEntry(
    name = skill.name,
    description = skill.metadata.skillSpec.description,
    relativePath = skill.source.relativePath,
    invocationControl = skill.metadata.invocationControl,
    userInvocable = skill.metadata.userInvocable,
    executionContext = skill.metadata.executionContext,
    markdownBody = skill.document.markdownBody,
    toolPermissions = skill.metadata.toolPermissions,
  )

  private fun toVisibleSkill(skill: SkillCatalogEntry): VisibleSkill = VisibleSkill(
    name = skill.name,
    description = skill.description,
    relativePath = skill.relativePath,
    invocationControl = skill.invocationControl,
    userInvocable = skill.userInvocable,
    executionContext = skill.executionContext,
  )

  private fun toTrace(skill: VisibleSkill): VisibleSkillTrace = VisibleSkillTrace(
    name = skill.name,
    relativePath = skill.relativePath,
    invocationControl = skill.invocationControl.serializedValue(),
    userInvocable = skill.userInvocable,
    executionContext = skill.executionContext.serializedValue(),
    descriptionPreview = skill.description.trim().take(MAX_TRACE_DESCRIPTION_CHARS),
  )

  private companion object {
    const val MAX_TRACE_SKILLS: Int = 24
    const val MAX_TRACE_DESCRIPTION_CHARS: Int = 160
  }
}

class SkillInventoryResolver(
  private val catalogResolver: SkillCatalogResolver = SkillCatalogResolver(),
) {
  fun resolve(roots: Iterable<File>): SkillInventory = catalogResolver.resolve(roots).inventory
}

class ActiveSkillCapsuleResolver {
  fun resolve(
    catalog: SkillCatalog,
    activeSkillName: String?,
    activationSource: String?,
  ): ActiveSkillCapsule? {
    val normalizedName = activeSkillName?.trim()?.takeIf(String::isNotBlank) ?: return null
    val source = activationSource?.trim()?.takeIf(String::isNotBlank) ?: return null
    val skill = catalog.skillsByName[normalizedName] ?: return null
    return ActiveSkillCapsule(
      name = skill.name,
      description = skill.description,
      relativePath = skill.relativePath,
      invocationControl = skill.invocationControl.serializedValue(),
      executionContext = skill.executionContext.serializedValue(),
      activationSource = source,
      markdownBody = skill.markdownBody,
      toolPermissionSummary = skill.toolPermissions.map { permission ->
        "${permission.pattern}:${permission.decision.name.lowercase()}"
      },
      allowedToolKeys = skill.toolPermissions
        .filter { permission -> permission.decision == com.opencray.skills.SkillPermissionDecision.ALLOW }
        .map { permission -> permission.pattern.trim().lowercase() }
        .filter(String::isNotBlank)
        .toSet(),
    )
  }
}

private fun SkillInvocationControl.serializedValue(): String = when (this) {
  SkillInvocationControl.EXPLICIT_ONLY -> "explicit-only"
  SkillInvocationControl.EXPLICIT_AND_IMPLICIT -> "explicit-and-implicit"
}

private fun SkillExecutionContext.serializedValue(): String = when (this) {
  SkillExecutionContext.INLINE -> "inline"
  SkillExecutionContext.FORK -> "fork"
}
