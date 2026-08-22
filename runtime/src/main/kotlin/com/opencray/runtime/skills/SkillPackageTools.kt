package com.opencray.runtime.skills

import com.opencray.core.contracts.AgentTask
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.OpenCrayExecutionMetadataKeys
import com.opencray.runtime.OpenCrayToolDispatcher
import com.opencray.runtime.WINDOWS_ABSOLUTE_PATH_REGEX
import com.opencray.runtime.inlinePreview
import com.opencray.runtime.optionalBooleanFrom
import com.opencray.runtime.optionalInt
import com.opencray.runtime.optionalString
import com.opencray.runtime.optionalStringArrayFrom
import com.opencray.runtime.optionalStringFrom
import com.opencray.runtime.requiredStringFrom
import com.opencray.runtime.policy.ToolMetadataContextRequest
import com.opencray.runtime.policy.ToolTargetKind
import com.opencray.runtime.policy.ToolWorkspaceRelation
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.serialization.json.JsonObject

internal fun OpenCrayToolDispatcher.findSkillPackages(
    task: AgentTask,
    arguments: JsonObject,
  ): AgentToolResult {
    val packageManager = config.skillPackageManager ?: return unavailableSkillPackageManager(toolName = "SkillsFind")
    val query = arguments.optionalString("query")?.trim().orEmpty()
    val maxResults = (arguments.optionalInt("max_results") ?: config.maxDirectoryEntries)
      .coerceIn(1, config.maxDirectoryEntries)
    if (query.isNotBlank()) {
      gateRemoteSkillNetworkAccess(
        task = task,
        resultToolName = "SkillsFind",
        url = "https://skills.sh/api/search",
        targetSummary = inlinePreview(query, maxChars = 256),
        affectedPaths = mapOf("query" to query),
      )?.let { return it }
    }
    val catalogRoot = packageManager.catalogRootPath().toPath().toAbsolutePath().normalize()
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "SkillsFind",
      targetPath = catalogRoot,
      approvedHostManagedReadRoots = skillPackageHostManagedReadRoots(packageManager),
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.DIRECTORY,
        primaryPath = catalogRoot,
        primaryTargetPath = displaySkillPackagePath(catalogRoot),
        targetSummary = if (query.isBlank()) {
          displaySkillPackagePath(catalogRoot)
        } else {
          inlinePreview(query, maxChars = 256)
        },
      ),
    )
    gateReadOnlyTool(
      plan = plan,
      affectedPaths = mapOf("path" to displaySkillPackagePath(catalogRoot)),
    )?.let { return it }

    val installedSkillIds = packageManager.listManagedSkills().mapTo(linkedSetOf()) { skill -> skill.name }
    val localMatches = packageManager.listCatalogSkills()
      .asSequence()
      .filter { skill ->
        query.isBlank() ||
          skill.name.contains(query, ignoreCase = true) ||
          skill.metadata.skillSpec.description.contains(query, ignoreCase = true)
      }
      .toList()
    val remoteSearch = if (query.isBlank()) {
      null
    } else {
      packageManager.searchRemoteSkills(
        query = query,
        limit = maxResults,
      )
    }
    if (remoteSearch?.errorCode != null && localMatches.isEmpty()) {
      return AgentToolResult(
        toolName = "SkillsFind",
        status = AgentToolResultStatus.FAILED,
        content = remoteSearch.errorMessage ?: "Remote skill search failed.",
        errorCode = remoteSearch.errorCode,
        errorMessage = remoteSearch.errorMessage,
        metadata = toolPolicyPipeline.resultMetadata(
          toolName = "SkillsFind",
          request = ToolMetadataContextRequest(
            targetKind = ToolTargetKind.NETWORK,
            primaryTargetPath = "https://skills.sh/api/search",
            workspaceRelation = ToolWorkspaceRelation.NONE,
            targetSummary = inlinePreview(query, maxChars = 256),
          ),
          metadata = mapOf(
            "query" to query,
            "providerName" to remoteSearch.providerName,
            "remoteResultCount" to "0",
            "localResultCount" to "0",
            "resultCount" to "0",
          ),
        ),
      )
    }

    val remoteLines = remoteSearch?.hits
      .orEmpty()
      .take(maxResults)
      .map { hit ->
        val installState = if (hit.name in installedSkillIds) "installed_remote" else "remote"
        "${hit.name}\t$installState\tinstall_ref=${hit.installRef}\tsource=${hit.source}\tinstalls=${hit.installs}\tdetail_url=${hit.detailUrl}"
      }
    val remainingLocalBudget = (maxResults - remoteLines.size).coerceAtLeast(0)
    val localLines = localMatches
      .take(remainingLocalBudget.takeIf { it > 0 } ?: 0)
      .map { skill ->
        val installState = if (skill.name in installedSkillIds) "installed_local" else "catalog"
        "${skill.name}\t$installState\tsource=local_catalog\tdescription=${skill.metadata.skillSpec.description}"
      }
    val lines = buildList {
      if (remoteSearch?.errorCode != null && localLines.isNotEmpty()) {
        add("Remote search unavailable: ${remoteSearch.errorMessage ?: remoteSearch.errorCode}")
      }
      addAll(remoteLines)
      addAll(localLines)
    }
    val content = if (lines.isEmpty()) {
      if (query.isBlank()) {
        "No skills were found in the host-managed catalog."
      } else {
        "No local or remote skills matched '$query'."
      }
    } else {
      lines.joinToString(separator = "\n")
    }
    return AgentToolResult(
      toolName = "SkillsFind",
      status = AgentToolResultStatus.SUCCESS,
      content = content,
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = mapOf(
          "path" to displaySkillPackagePath(catalogRoot),
          "query" to query,
          "providerName" to (remoteSearch?.providerName ?: "local-catalog"),
          "remoteResultCount" to remoteLines.size.toString(),
          "localResultCount" to localLines.size.toString(),
          "resultCount" to (remoteLines.size + localLines.size).toString(),
        ),
      ),
    )
  }

internal fun OpenCrayToolDispatcher.listInstalledSkillPackages(task: AgentTask): AgentToolResult {
    val packageManager = config.skillPackageManager ?: return unavailableSkillPackageManager(toolName = "SkillsList")
    packageManager.refreshManifest()
    val managedRoot = packageManager.managedRootPath().toPath().toAbsolutePath().normalize()
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "SkillsList",
      targetPath = managedRoot,
      approvedHostManagedReadRoots = skillPackageHostManagedReadRoots(packageManager),
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.DIRECTORY,
        primaryPath = managedRoot,
        primaryTargetPath = displaySkillPackagePath(managedRoot),
        targetSummary = displaySkillPackagePath(managedRoot),
      ),
    )
    gateReadOnlyTool(
      plan = plan,
      affectedPaths = mapOf("path" to displaySkillPackagePath(managedRoot)),
    )?.let { return it }

    val installationsById = packageManager.listInstallations().associateBy(SkillInstallManifestEntry::skillId)
    val managedSkills = packageManager.listManagedSkills()
    val content = if (managedSkills.isEmpty()) {
      "No skills are installed in the host-managed skills directory."
    } else {
      managedSkills.joinToString(separator = "\n") { skill ->
        val installation = installationsById[skill.name]
        val sourceType = installation?.sourceType ?: "unknown"
        val updatedAt = installation?.updatedAtEpochMs?.toString() ?: "unknown"
        "${skill.name}\t$sourceType\tupdated_at=$updatedAt\tdescription=${skill.metadata.skillSpec.description}"
      }
    }
    return AgentToolResult(
      toolName = "SkillsList",
      status = AgentToolResultStatus.SUCCESS,
      content = content,
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = mapOf(
          "path" to displaySkillPackagePath(managedRoot),
          "skillCount" to managedSkills.size.toString(),
        ),
      ),
    )
  }

internal fun OpenCrayToolDispatcher.inspectSkillPackageSource(
    task: AgentTask,
    arguments: JsonObject,
  ): AgentToolResult {
    val packageManager = config.skillPackageManager ?: return unavailableSkillPackageManager(toolName = "SkillsInspect")
    val sourceRef = arguments.requiredStringFrom("source_ref", "sourceRef", "source", "path", "url")
    val localSourcePath = resolveExplicitLocalSkillSourcePath(sourceRef)
    if (localSourcePath != null) {
      gateLocalSkillSourceReadAccess(
        task = task,
        resultToolName = "SkillsInspect",
        sourcePath = localSourcePath,
        sourceRef = sourceRef,
      )?.let { return it }

      val normalizedSourcePath = localSourcePath.toAbsolutePath().normalize()
      val displayPath = toolTargetResolver.displayModelPath(normalizedSourcePath)
      val plan = toolPolicyPipeline.plan(
        task = task,
        toolName = "SkillsInspect",
        targetPath = normalizedSourcePath,
        metadataRequest = ToolMetadataContextRequest(
          targetKind = if (Files.isDirectory(normalizedSourcePath)) ToolTargetKind.DIRECTORY else ToolTargetKind.FILE,
          primaryPath = normalizedSourcePath,
          primaryTargetPath = displayPath,
          targetSummary = sourceRef,
        ),
      )
      gateReadOnlyTool(
        plan = plan,
        affectedPaths = mapOf("sourcePath" to displayPath),
      )?.let { return it }

      val attempt = packageManager.inspectLocalSource(
        sourcePath = localSourcePath.toFile(),
        sourceRef = sourceRef,
      )
      val result = attempt.result ?: return AgentToolResult(
        toolName = "SkillsInspect",
        status = AgentToolResultStatus.FAILED,
        content = attempt.errorMessage ?: "Failed to inspect '$sourceRef'.",
        errorCode = attempt.errorCode ?: "SKILL_SOURCE_INSPECTION_FAILED",
        errorMessage = attempt.errorMessage,
        metadata = toolPolicyPipeline.resultMetadata(
          plan = plan,
          metadata = mapOf(
            "sourceRef" to sourceRef,
            "candidateCount" to "0",
          ),
        ),
      )
      return AgentToolResult(
        toolName = "SkillsInspect",
        status = AgentToolResultStatus.SUCCESS,
        content = renderSkillSourceInspection(result),
        metadata = toolPolicyPipeline.resultMetadata(
          plan = plan,
          metadata = buildMap {
            put("sourceRef", result.sourceRef)
            put("sourceType", result.sourceType)
            put("candidateCount", result.candidates.size.toString())
            result.sourcePath?.let { put("sourcePath", it) }
            result.resolvedRevision?.let { put("resolvedRevision", it) }
            result.resolvedCommitSha?.let { put("resolvedCommitSha", it) }
          },
        ),
      )
    }

    val remoteSource = packageManager.resolveRemoteSource(sourceRef = sourceRef)
    if (remoteSource != null) {
      gateRemoteSkillNetworkAccess(
        task = task,
        resultToolName = "SkillsInspect",
        url = remoteSource.policyTargetUrl,
        targetSummary = remoteSource.requestedSourceRef,
        affectedPaths = mapOf("sourceRef" to remoteSource.requestedSourceRef),
      )?.let { return it }

      val metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.NETWORK,
        primaryTargetPath = remoteSource.policyTargetUrl,
        workspaceRelation = ToolWorkspaceRelation.NONE,
        targetSummary = remoteSource.requestedSourceRef,
      )
      val attempt = packageManager.inspectRemoteSource(sourceRef = sourceRef)
      val result = attempt.result ?: return AgentToolResult(
        toolName = "SkillsInspect",
        status = AgentToolResultStatus.FAILED,
        content = attempt.errorMessage ?: "Failed to inspect '$sourceRef'.",
        errorCode = attempt.errorCode ?: "SKILL_SOURCE_INSPECTION_FAILED",
        errorMessage = attempt.errorMessage,
        metadata = toolPolicyPipeline.resultMetadata(
          toolName = "SkillsInspect",
          request = metadataRequest,
          metadata = mapOf(
            "sourceRef" to remoteSource.requestedSourceRef,
            "candidateCount" to "0",
          ),
        ),
      )
      return AgentToolResult(
        toolName = "SkillsInspect",
        status = AgentToolResultStatus.SUCCESS,
        content = renderSkillSourceInspection(result),
        metadata = toolPolicyPipeline.resultMetadata(
          toolName = "SkillsInspect",
          request = metadataRequest,
          metadata = buildMap {
            put("sourceRef", result.sourceRef)
            put("sourceType", result.sourceType)
            put("candidateCount", result.candidates.size.toString())
            result.sourcePath?.let { put("sourcePath", it) }
            result.resolvedRevision?.let { put("resolvedRevision", it) }
            result.resolvedCommitSha?.let { put("resolvedCommitSha", it) }
          },
        ),
      )
    }

    return AgentToolResult(
      toolName = "SkillsInspect",
      status = AgentToolResultStatus.FAILED,
      content = "Source '$sourceRef' is not a supported local path, GitHub source, or GitLab source.",
      errorCode = "SKILL_SOURCE_UNSUPPORTED",
      metadata = toolPolicyPipeline.resultMetadata(
        toolName = "SkillsInspect",
        request = ToolMetadataContextRequest(
          targetKind = ToolTargetKind.NONE,
          workspaceRelation = ToolWorkspaceRelation.NONE,
          targetSummary = sourceRef,
        ),
        metadata = mapOf(
          "sourceRef" to sourceRef,
          "candidateCount" to "0",
        ),
      ),
    )
  }

internal fun OpenCrayToolDispatcher.checkInstalledSkillPackages(
    task: AgentTask,
    arguments: JsonObject,
  ): AgentToolResult {
    val packageManager = config.skillPackageManager ?: return unavailableSkillPackageManager(toolName = "SkillsCheck")
    val requestedSkillId = arguments.optionalStringFrom("skill_id", "skillId", "name")
      ?.trim()
      ?.takeIf(String::isNotBlank)
    packageManager.refreshManifest()
    val managedRoot = packageManager.managedRootPath().toPath().toAbsolutePath().normalize()
    val displayManagedRoot = displaySkillPackagePath(managedRoot)
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "SkillsCheck",
      targetPath = managedRoot,
      approvedHostManagedReadRoots = skillPackageHostManagedReadRoots(packageManager),
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.DIRECTORY,
        primaryPath = managedRoot,
        primaryTargetPath = displayManagedRoot,
        targetSummary = requestedSkillId ?: displayManagedRoot,
      ),
    )
    gateReadOnlyTool(
      plan = plan,
      affectedPaths = mapOf("path" to displayManagedRoot),
    )?.let { return it }

    val managedSkills = packageManager.listManagedSkills()
    val managedSkillIds = managedSkills.mapTo(linkedSetOf()) { skill -> skill.name }
    val installations = packageManager.listInstallations()
    val relevantInstallations = installations.filter { entry ->
      requestedSkillId == null || entry.skillId == requestedSkillId
    }
    if (requestedSkillId != null &&
      requestedSkillId !in managedSkillIds &&
      relevantInstallations.isEmpty()
    ) {
      return AgentToolResult(
        toolName = "SkillsCheck",
        status = AgentToolResultStatus.FAILED,
        content = "Skill '$requestedSkillId' is not installed in the host-managed skills directory.",
        errorCode = "SKILL_NOT_INSTALLED",
        metadata = toolPolicyPipeline.resultMetadata(
          plan = plan,
          metadata = mapOf(
            "path" to displayManagedRoot,
            "skillId" to requestedSkillId,
            "checkedCount" to "0",
          ),
        ),
      )
    }
    relevantInstallations.forEach { entry ->
      if (!isRemoteSkillSourceType(entry.sourceType)) {
        return@forEach
      }
      val resolvedSource = packageManager.resolveRemoteSource(
        sourceRef = entry.sourceRef,
        selectedSkillName = entry.selectedSkillName,
      )
      gateRemoteSkillNetworkAccess(
        task = task,
        resultToolName = "SkillsCheck",
        url = resolvedSource?.policyTargetUrl ?: entry.sourceRef,
        targetSummary = entry.sourceRef,
        affectedPaths = mapOf("sourceRef" to entry.sourceRef),
      )?.let { return it }
    }

    val report = packageManager.checkInstalledSkills(requestedSkillId)
    val content = if (report.results.isEmpty()) {
      "No installed skills are available for update checks."
    } else {
      report.results.joinToString(separator = "\n", transform = { result -> renderSkillPackageCheckLine(result) })
    }
    return AgentToolResult(
      toolName = "SkillsCheck",
      status = AgentToolResultStatus.SUCCESS,
      content = content,
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = buildMap {
          put("path", displayManagedRoot)
          put("checkedCount", report.results.size.toString())
          put("upToDateCount", report.upToDateCount.toString())
          put("updateAvailableCount", report.updateAvailableCount.toString())
          put("sourceUnavailableCount", report.sourceUnavailableCount.toString())
          put("unsupportedCount", report.unsupportedCount.toString())
          requestedSkillId?.let { put("skillId", it) }
        },
      ),
    )
  }

internal fun OpenCrayToolDispatcher.installSkillPackage(
    task: AgentTask,
    arguments: JsonObject,
  ): AgentToolResult {
    val packageManager = config.skillPackageManager ?: return unavailableSkillPackageManager(toolName = "SkillsAdd")
    val sourceRef = arguments.requiredStringFrom("source_ref", "sourceRef", "skill_id", "skillId", "name")
    val selectedSkillName = arguments.optionalStringFrom("skill", "selected_skill", "selectedSkill")
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val localSourcePath = resolveExplicitLocalSkillSourcePath(sourceRef)
    if (localSourcePath != null) {
      gateLocalSkillSourceReadAccess(
        task = task,
        resultToolName = "SkillsAdd",
        sourcePath = localSourcePath,
        sourceRef = sourceRef,
      )?.let { return it }

      val managedRoot = packageManager.managedRootPath().toPath().toAbsolutePath().normalize()
      val displayManagedRoot = displaySkillPackagePath(managedRoot)
      val localPlan = toolPolicyPipeline.plan(
        task = task,
        toolName = "SkillsAdd",
        targetPath = managedRoot,
        metadataRequest = ToolMetadataContextRequest(
          targetKind = ToolTargetKind.DIRECTORY,
          primaryPath = managedRoot,
          primaryTargetPath = displayManagedRoot,
          targetSummary = "$sourceRef -> $displayManagedRoot",
        ),
      )
      toolPolicyPipeline.gateFileMutation(
        plan = localPlan,
        affectedPaths = mapOf("path" to displayManagedRoot),
      )?.let { return it }

      val attempt = packageManager.installFromLocalSource(
        sourcePath = localSourcePath.toFile(),
        sourceRef = sourceRef,
        selectedSkillName = selectedSkillName,
      )
      val result = attempt.result ?: return AgentToolResult(
        toolName = "SkillsAdd",
        status = AgentToolResultStatus.FAILED,
        content = attempt.errorMessage ?: "Failed to install '$sourceRef' from the local source.",
        errorCode = attempt.errorCode ?: "SKILL_INSTALL_FAILED",
        errorMessage = attempt.errorMessage,
      )
      return AgentToolResult(
        toolName = "SkillsAdd",
        status = AgentToolResultStatus.SUCCESS,
        content = "Installed skill '${result.skillId}' from local source '$sourceRef'.",
        metadata = toolPolicyPipeline.resultMetadata(
          plan = localPlan,
          metadata = mapOf(
            "path" to displaySkillPackagePath(result.targetDirectory.toPath().toAbsolutePath().normalize()),
            "skillId" to result.skillId,
            "sourceType" to result.manifestEntry.sourceType,
            "sourceRef" to result.manifestEntry.sourceRef,
          ),
        ),
      )
    }

    val remoteSource = packageManager.resolveRemoteSource(
      sourceRef = sourceRef,
      selectedSkillName = selectedSkillName,
    )
    if (remoteSource != null) {
      gateRemoteSkillNetworkAccess(
        task = task,
        resultToolName = "SkillsAdd",
        url = remoteSource.policyTargetUrl,
        targetSummary = remoteSource.requestedSourceRef,
        affectedPaths = mapOf("sourceRef" to remoteSource.requestedSourceRef),
      )?.let { return it }

      val managedRoot = packageManager.managedRootPath().toPath().toAbsolutePath().normalize()
      val displayManagedRoot = displaySkillPackagePath(managedRoot)
      val remotePlan = toolPolicyPipeline.plan(
        task = task,
        toolName = "SkillsAdd",
        targetPath = managedRoot,
        metadataRequest = ToolMetadataContextRequest(
          targetKind = ToolTargetKind.DIRECTORY,
          primaryPath = managedRoot,
          primaryTargetPath = displayManagedRoot,
          targetSummary = "${remoteSource.requestedSourceRef} -> $displayManagedRoot",
        ),
      )
      toolPolicyPipeline.gateFileMutation(
        plan = remotePlan,
        affectedPaths = mapOf("path" to displayManagedRoot),
      )?.let { return it }

      val attempt = packageManager.installFromRemoteSource(
        sourceRef = sourceRef,
        selectedSkillName = selectedSkillName,
      )
      val result = attempt.result ?: return AgentToolResult(
        toolName = "SkillsAdd",
        status = AgentToolResultStatus.FAILED,
        content = attempt.errorMessage ?: "Failed to install '$sourceRef' from the remote source.",
        errorCode = attempt.errorCode ?: "SKILL_INSTALL_FAILED",
        errorMessage = attempt.errorMessage,
      )
      return AgentToolResult(
        toolName = "SkillsAdd",
        status = AgentToolResultStatus.SUCCESS,
        content = "Installed skill '${result.skillId}' from remote source '${result.manifestEntry.sourceRef}'.",
        metadata = toolPolicyPipeline.resultMetadata(
          plan = remotePlan,
          metadata = mapOf(
            "path" to displaySkillPackagePath(result.targetDirectory.toPath().toAbsolutePath().normalize()),
            "skillId" to result.skillId,
            "sourceType" to result.manifestEntry.sourceType,
            "sourceRef" to result.manifestEntry.sourceRef,
          ) + listOfNotNull(
            result.manifestEntry.resolvedRevision?.let { "resolvedRevision" to it },
            result.manifestEntry.resolvedCommitSha?.let { "resolvedCommitSha" to it },
          ).toMap(),
        ),
      )
    }

    val skillId = sourceRef
    val targetDirectory = packageManager.resolveCatalogInstallTarget(skillId)
      ?: return AgentToolResult(
        toolName = "SkillsAdd",
        status = AgentToolResultStatus.FAILED,
        content = "Skill '$skillId' was not found in the host-managed catalog.",
        errorCode = "SKILL_NOT_FOUND",
      )
    val targetPath = targetDirectory.toPath().toAbsolutePath().normalize()
    val displayPath = displaySkillPackagePath(targetPath)
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "SkillsAdd",
      targetPath = targetPath,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.DIRECTORY,
        primaryPath = targetPath,
        primaryTargetPath = displayPath,
        targetSummary = "$skillId -> $displayPath",
      ),
    )
    toolPolicyPipeline.gateFileMutation(
      plan = plan,
      affectedPaths = mapOf("path" to displayPath),
    )?.let { return it }

    val result = packageManager.installFromCatalog(skillId)
      ?: return AgentToolResult(
        toolName = "SkillsAdd",
        status = AgentToolResultStatus.FAILED,
        content = "Failed to install '$skillId' from the host-managed catalog.",
        errorCode = "SKILL_INSTALL_FAILED",
      )
    return AgentToolResult(
      toolName = "SkillsAdd",
      status = AgentToolResultStatus.SUCCESS,
      content = "Installed skill '${result.skillId}' from the host-managed catalog.",
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = mapOf(
          "path" to displayPath,
          "skillId" to result.skillId,
          "sourceType" to result.manifestEntry.sourceType,
        ),
      ),
    )
  }

internal fun OpenCrayToolDispatcher.installSkillPackagesBatch(
    task: AgentTask,
    arguments: JsonObject,
  ): AgentToolResult {
    val packageManager = config.skillPackageManager ?: return unavailableSkillPackageManager(toolName = "SkillsAddBatch")
    val sourceRef = arguments.requiredStringFrom("source_ref", "sourceRef", "source", "path", "url")
    val selectedSkillNames = arguments.optionalStringArrayFrom(
      "skills",
      "selected_skills",
      "selectedSkills",
      "skill_ids",
      "skillIds",
    )
      .asSequence()
      .map(String::trim)
      .filter(String::isNotBlank)
      .distinct()
      .toList()
    val installAll = arguments.optionalBooleanFrom("install_all", "installAll", "all") == true

    val localSourcePath = resolveExplicitLocalSkillSourcePath(sourceRef)
    if (localSourcePath != null) {
      gateLocalSkillSourceReadAccess(
        task = task,
        resultToolName = "SkillsAddBatch",
        sourcePath = localSourcePath,
        sourceRef = sourceRef,
      )?.let { return it }

      val managedRoot = packageManager.managedRootPath().toPath().toAbsolutePath().normalize()
      val displayManagedRoot = displaySkillPackagePath(managedRoot)
      val plan = toolPolicyPipeline.plan(
        task = task,
        toolName = "SkillsAddBatch",
        targetPath = managedRoot,
        metadataRequest = ToolMetadataContextRequest(
          targetKind = ToolTargetKind.DIRECTORY,
          primaryPath = managedRoot,
          primaryTargetPath = displayManagedRoot,
          targetSummary = "$sourceRef -> $displayManagedRoot",
        ),
      )
      toolPolicyPipeline.gateFileMutation(
        plan = plan,
        affectedPaths = mapOf("path" to displayManagedRoot),
      )?.let { return it }

      val attempt = packageManager.installFromLocalSourceBatch(
        sourcePath = localSourcePath.toFile(),
        sourceRef = sourceRef,
        selectedSkillNames = selectedSkillNames,
        installAll = installAll,
      )
      val result = attempt.result ?: return AgentToolResult(
        toolName = "SkillsAddBatch",
        status = AgentToolResultStatus.FAILED,
        content = attempt.errorMessage ?: "Failed to batch install from local source '$sourceRef'.",
        errorCode = attempt.errorCode ?: "SKILL_BATCH_INSTALL_FAILED",
        errorMessage = attempt.errorMessage,
      )
      val status = if (result.failedCount > 0) {
        AgentToolResultStatus.FAILED
      } else {
        AgentToolResultStatus.SUCCESS
      }
      return AgentToolResult(
        toolName = "SkillsAddBatch",
        status = status,
        content = renderSkillBatchInstallResult(result),
        errorCode = if (status == AgentToolResultStatus.FAILED) {
          result.entries.firstNotNullOfOrNull(SkillPackageBatchInstallEntry::errorCode)
            ?: "SKILL_BATCH_INSTALL_FAILED"
        } else {
          null
        },
        errorMessage = if (status == AgentToolResultStatus.FAILED) {
          result.entries.firstNotNullOfOrNull(SkillPackageBatchInstallEntry::errorMessage)
        } else {
          null
        },
        metadata = toolPolicyPipeline.resultMetadata(
          plan = plan,
          metadata = buildMap {
            put("path", displayManagedRoot)
            put("sourceType", result.sourceType)
            put("sourceRef", result.sourceRef)
            put("requestedCount", result.requestedCount.toString())
            put("installedCount", result.installedCount.toString())
            put("failedCount", result.failedCount.toString())
            result.entries
              .mapNotNull(SkillPackageBatchInstallEntry::installedSkillId)
              .singleOrNull()
              ?.let { put("skillId", it) }
          },
        ),
      )
    }

    val remoteSource = packageManager.resolveRemoteSource(sourceRef = sourceRef)
    if (remoteSource != null) {
      gateRemoteSkillNetworkAccess(
        task = task,
        resultToolName = "SkillsAddBatch",
        url = remoteSource.policyTargetUrl,
        targetSummary = remoteSource.requestedSourceRef,
        affectedPaths = mapOf("sourceRef" to remoteSource.requestedSourceRef),
      )?.let { return it }

      val managedRoot = packageManager.managedRootPath().toPath().toAbsolutePath().normalize()
      val displayManagedRoot = displaySkillPackagePath(managedRoot)
      val plan = toolPolicyPipeline.plan(
        task = task,
        toolName = "SkillsAddBatch",
        targetPath = managedRoot,
        metadataRequest = ToolMetadataContextRequest(
          targetKind = ToolTargetKind.DIRECTORY,
          primaryPath = managedRoot,
          primaryTargetPath = displayManagedRoot,
          targetSummary = "${remoteSource.requestedSourceRef} -> $displayManagedRoot",
        ),
      )
      toolPolicyPipeline.gateFileMutation(
        plan = plan,
        affectedPaths = mapOf("path" to displayManagedRoot),
      )?.let { return it }

      val attempt = packageManager.installFromRemoteSourceBatch(
        sourceRef = sourceRef,
        selectedSkillNames = selectedSkillNames,
        installAll = installAll,
      )
      val result = attempt.result ?: return AgentToolResult(
        toolName = "SkillsAddBatch",
        status = AgentToolResultStatus.FAILED,
        content = attempt.errorMessage ?: "Failed to batch install from remote source '$sourceRef'.",
        errorCode = attempt.errorCode ?: "SKILL_BATCH_INSTALL_FAILED",
        errorMessage = attempt.errorMessage,
      )
      val status = if (result.failedCount > 0) {
        AgentToolResultStatus.FAILED
      } else {
        AgentToolResultStatus.SUCCESS
      }
      return AgentToolResult(
        toolName = "SkillsAddBatch",
        status = status,
        content = renderSkillBatchInstallResult(result),
        errorCode = if (status == AgentToolResultStatus.FAILED) {
          result.entries.firstNotNullOfOrNull(SkillPackageBatchInstallEntry::errorCode)
            ?: "SKILL_BATCH_INSTALL_FAILED"
        } else {
          null
        },
        errorMessage = if (status == AgentToolResultStatus.FAILED) {
          result.entries.firstNotNullOfOrNull(SkillPackageBatchInstallEntry::errorMessage)
        } else {
          null
        },
        metadata = toolPolicyPipeline.resultMetadata(
          plan = plan,
          metadata = buildMap {
            put("path", displayManagedRoot)
            put("sourceType", result.sourceType)
            put("sourceRef", result.sourceRef)
            put("requestedCount", result.requestedCount.toString())
            put("installedCount", result.installedCount.toString())
            put("failedCount", result.failedCount.toString())
            result.sourcePath?.let { put("sourcePath", it) }
            result.resolvedRevision?.let { put("resolvedRevision", it) }
            result.resolvedCommitSha?.let { put("resolvedCommitSha", it) }
            result.entries
              .mapNotNull(SkillPackageBatchInstallEntry::installedSkillId)
              .singleOrNull()
              ?.let { put("skillId", it) }
          },
        ),
      )
    }

    return AgentToolResult(
      toolName = "SkillsAddBatch",
      status = AgentToolResultStatus.FAILED,
      content = "Batch installation requires an explicit local path, GitHub source, or GitLab source. Use SkillsAdd for the host-managed catalog.",
      errorCode = "SKILL_SOURCE_UNSUPPORTED",
      metadata = toolPolicyPipeline.resultMetadata(
        toolName = "SkillsAddBatch",
        request = ToolMetadataContextRequest(
          targetKind = ToolTargetKind.NONE,
          workspaceRelation = ToolWorkspaceRelation.NONE,
          targetSummary = sourceRef,
        ),
        metadata = mapOf(
          "sourceRef" to sourceRef,
          "requestedCount" to selectedSkillNames.size.toString(),
          "installedCount" to "0",
          "failedCount" to "0",
        ),
      ),
    )
  }

internal fun OpenCrayToolDispatcher.updateInstalledSkillPackages(
    task: AgentTask,
    arguments: JsonObject,
  ): AgentToolResult {
    val packageManager = config.skillPackageManager ?: return unavailableSkillPackageManager(toolName = "SkillsUpdate")
    val requestedSkillId = arguments.optionalStringFrom("skill_id", "skillId", "name")
      ?.trim()
      ?.takeIf(String::isNotBlank)
    packageManager.refreshManifest()
    val managedSkills = packageManager.listManagedSkills()
    val managedSkillIds = managedSkills.mapTo(linkedSetOf()) { skill -> skill.name }
    val installations = packageManager.listInstallations()
    val relevantInstallations = installations.filter { entry ->
      requestedSkillId == null || entry.skillId == requestedSkillId
    }
    if (requestedSkillId != null &&
      requestedSkillId !in managedSkillIds &&
      relevantInstallations.isEmpty()
    ) {
      return AgentToolResult(
        toolName = "SkillsUpdate",
        status = AgentToolResultStatus.FAILED,
        content = "Skill '$requestedSkillId' is not installed in the host-managed skills directory.",
        errorCode = "SKILL_NOT_INSTALLED",
      )
    }
    relevantInstallations.forEach { entry ->
      if (entry.sourceType == "local_path") {
        val sourcePath = entry.sourcePath?.trim()?.takeIf(String::isNotBlank)
        if (sourcePath != null) {
          gateLocalSkillSourceReadAccess(
            task = task,
            resultToolName = "SkillsUpdate",
            sourcePath = Paths.get(sourcePath),
            sourceRef = entry.sourceRef,
          )?.let { return it }
        }
      }
      if (isRemoteSkillSourceType(entry.sourceType)) {
        val resolvedSource = packageManager.resolveRemoteSource(
          sourceRef = entry.sourceRef,
          selectedSkillName = entry.selectedSkillName,
        )
        gateRemoteSkillNetworkAccess(
          task = task,
          resultToolName = "SkillsUpdate",
          url = resolvedSource?.policyTargetUrl ?: entry.sourceRef,
          targetSummary = entry.sourceRef,
          affectedPaths = mapOf("sourceRef" to entry.sourceRef),
        )?.let { return it }
      }
    }

    val checkReport = packageManager.checkInstalledSkills(requestedSkillId)
    val managedRoot = packageManager.managedRootPath().toPath().toAbsolutePath().normalize()
    val displayManagedRoot = displaySkillPackagePath(managedRoot)
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "SkillsUpdate",
      targetPath = managedRoot,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.DIRECTORY,
        primaryPath = managedRoot,
        primaryTargetPath = displayManagedRoot,
        targetSummary = requestedSkillId ?: displayManagedRoot,
      ),
    )
    if (checkReport.updateAvailableCount > 0) {
      toolPolicyPipeline.gateFileMutation(
        plan = plan,
        affectedPaths = mapOf("path" to displayManagedRoot),
      )?.let { return it }
    }
    val updateReport = packageManager.updateInstalledSkills(checkReport)
    val status = if (updateReport.updatedCount == 0 &&
      updateReport.failedCount > 0 &&
      updateReport.skippedCount == 0
    ) {
      AgentToolResultStatus.FAILED
    } else {
      AgentToolResultStatus.SUCCESS
    }
    val content = if (updateReport.results.isEmpty()) {
      "No installed skills are available for update."
    } else {
      updateReport.results.joinToString(separator = "\n", transform = { result -> renderSkillPackageUpdateLine(result) })
    }
    return AgentToolResult(
      toolName = "SkillsUpdate",
      status = status,
      content = content,
      errorCode = if (status == AgentToolResultStatus.FAILED) {
        updateReport.results.firstNotNullOfOrNull(SkillPackageUpdateResult::errorCode)
          ?: "SKILL_UPDATE_FAILED"
      } else {
        null
      },
      errorMessage = if (status == AgentToolResultStatus.FAILED) {
        updateReport.results.firstNotNullOfOrNull(SkillPackageUpdateResult::errorMessage)
      } else {
        null
      },
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = buildMap {
          put("path", displayManagedRoot)
          put("resultCount", updateReport.results.size.toString())
          put("updatedCount", updateReport.updatedCount.toString())
          put("skippedCount", updateReport.skippedCount.toString())
          put("failedCount", updateReport.failedCount.toString())
          requestedSkillId?.let { put("skillId", it) }
        },
      ),
    )
  }

internal fun OpenCrayToolDispatcher.removeSkillPackage(
    task: AgentTask,
    arguments: JsonObject,
  ): AgentToolResult {
    val packageManager = config.skillPackageManager ?: return unavailableSkillPackageManager(toolName = "SkillsRemove")
    val skillId = arguments.requiredStringFrom("skill_id", "skillId", "name")
    val targetDirectory = packageManager.resolveInstalledSkillDirectory(skillId)
      ?: return AgentToolResult(
        toolName = "SkillsRemove",
        status = AgentToolResultStatus.FAILED,
        content = "Skill '$skillId' is not installed in the host-managed skills directory.",
        errorCode = "SKILL_NOT_INSTALLED",
      )
    val targetPath = targetDirectory.toPath().toAbsolutePath().normalize()
    val displayPath = displaySkillPackagePath(targetPath)
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "SkillsRemove",
      targetPath = targetPath,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.DIRECTORY,
        primaryPath = targetPath,
        primaryTargetPath = displayPath,
        targetSummary = displayPath,
      ),
    )
    toolPolicyPipeline.gateFileMutation(
      plan = plan,
      affectedPaths = mapOf("path" to displayPath),
    )?.let { return it }

    val result = packageManager.removeInstalledSkill(skillId)
      ?: return AgentToolResult(
        toolName = "SkillsRemove",
        status = AgentToolResultStatus.FAILED,
        content = "Failed to remove '$skillId' from the host-managed skills directory.",
        errorCode = "SKILL_REMOVE_FAILED",
      )
    return AgentToolResult(
      toolName = "SkillsRemove",
      status = AgentToolResultStatus.SUCCESS,
      content = "Removed skill '${result.skillId}' from the host-managed skills directory.",
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = mapOf(
          "path" to displayPath,
          "skillId" to result.skillId,
        ),
      ),
    )
  }

internal fun OpenCrayToolDispatcher.renderSkillPackageCheckLine(
    result: SkillPackageCheckResult,
  ): String = buildList {
    add(result.skillId)
    add(result.status.wireValue)
    add("source=${result.sourceType}")
    add("source_ref=${result.sourceRef}")
    result.installedRevision?.takeIf(String::isNotBlank)?.let { add("installed_revision=$it") }
    result.installedCommitSha?.takeIf(String::isNotBlank)?.let { add("installed_commit=$it") }
    result.latestRevision?.takeIf(String::isNotBlank)?.let { add("latest_revision=$it") }
    result.latestCommitSha?.takeIf(String::isNotBlank)?.let { add("latest_commit=$it") }
    result.latestContentHash?.takeIf(String::isNotBlank)?.let { add("latest_hash=$it") }
    result.errorCode?.let { add("error_code=$it") }
    result.errorMessage?.takeIf(String::isNotBlank)?.let { add("message=${inlinePreview(it, maxChars = 160)}") }
  }.joinToString(separator = "\t")

internal fun OpenCrayToolDispatcher.renderSkillPackageUpdateLine(
    result: SkillPackageUpdateResult,
  ): String = buildList {
    add(result.skillId)
    add(result.status.wireValue)
    add("source=${result.sourceType}")
    add("source_ref=${result.sourceRef}")
    result.checkStatus?.let { add("reason=${it.wireValue}") }
    result.manifestEntry?.resolvedRevision?.takeIf(String::isNotBlank)?.let { add("resolved_revision=$it") }
    result.manifestEntry?.resolvedCommitSha?.takeIf(String::isNotBlank)?.let { add("resolved_commit=$it") }
    result.errorCode?.let { add("error_code=$it") }
    result.errorMessage?.takeIf(String::isNotBlank)?.let { add("message=${inlinePreview(it, maxChars = 160)}") }
  }.joinToString(separator = "\t")

internal fun OpenCrayToolDispatcher.renderSkillSourceInspection(
    result: com.opencray.runtime.skills.SkillSourceInspectionResult,
  ): String = buildList {
    add(
      buildList {
        add("inspection")
        add(result.sourceType)
        add("source_ref=${result.sourceRef}")
        result.sourcePath?.takeIf(String::isNotBlank)?.let { add("source_path=$it") }
        result.resolvedRevision?.takeIf(String::isNotBlank)?.let { add("resolved_revision=$it") }
        result.resolvedCommitSha?.takeIf(String::isNotBlank)?.let { add("resolved_commit=$it") }
        add("candidate_count=${result.candidates.size}")
      }.joinToString(separator = "\t"),
    )
    addAll(
      result.candidates.map { candidate ->
        buildList {
          add("candidate")
          add(candidate.name)
          add("description=${candidate.description}")
          add("relative_path=${candidate.relativePath}")
        }.joinToString(separator = "\t")
      },
    )
  }.joinToString(separator = "\n")

internal fun OpenCrayToolDispatcher.renderSkillBatchInstallResult(
    result: com.opencray.runtime.skills.SkillPackageBatchInstallResult,
  ): String = buildList {
    add(
      buildList {
        add("batch_install")
        add(result.sourceType)
        add("source_ref=${result.sourceRef}")
        result.sourcePath?.takeIf(String::isNotBlank)?.let { add("source_path=$it") }
        result.resolvedRevision?.takeIf(String::isNotBlank)?.let { add("resolved_revision=$it") }
        result.resolvedCommitSha?.takeIf(String::isNotBlank)?.let { add("resolved_commit=$it") }
        add("requested_count=${result.requestedCount}")
        add("installed_count=${result.installedCount}")
        add("failed_count=${result.failedCount}")
      }.joinToString(separator = "\t"),
    )
    addAll(
      result.entries.map { entry ->
        buildList {
          add(if (entry.succeeded) "installed" else "failed")
          add(entry.installedSkillId ?: entry.requestedSkillName)
          add("requested=${entry.requestedSkillName}")
          entry.manifestEntry?.sourceRelativePath
            ?.takeIf(String::isNotBlank)
            ?.let { add("relative_path=$it") }
          entry.errorCode?.let { add("error_code=$it") }
          entry.errorMessage
            ?.takeIf(String::isNotBlank)
            ?.let { add("message=${inlinePreview(it, maxChars = 160)}") }
        }.joinToString(separator = "\t")
      },
    )
  }.joinToString(separator = "\n")

internal fun OpenCrayToolDispatcher.isRemoteSkillSourceType(sourceType: String): Boolean = when (sourceType) {
    "remote_github",
    "remote_gitlab",
    -> true

    else -> false
  }

internal fun OpenCrayToolDispatcher.unavailableSkillPackageManager(toolName: String): AgentToolResult = AgentToolResult(
    toolName = toolName,
    status = AgentToolResultStatus.FAILED,
    content = "The host-managed skills package manager is not configured for this runtime.",
    errorCode = "SKILL_PACKAGE_MANAGER_UNAVAILABLE",
  )

internal fun OpenCrayToolDispatcher.displaySkillPackagePath(path: Path): String {
    val normalized = path.toAbsolutePath().normalize()
    val packageManager = config.skillPackageManager
    if (packageManager != null) {
      val managedRoot = packageManager.managedRootPath().toPath().toAbsolutePath().normalize()
      if (normalized.startsWith(managedRoot)) {
        return labeledHostPath(label = "skills-managed", root = managedRoot, path = normalized)
      }
      val catalogRoot = packageManager.catalogRootPath().toPath().toAbsolutePath().normalize()
      if (normalized.startsWith(catalogRoot)) {
        return labeledHostPath(label = "skills-catalog", root = catalogRoot, path = normalized)
      }
    }
    return normalized.toString().replace('\\', '/')
  }

internal fun OpenCrayToolDispatcher.skillPackageHostManagedReadRoots(packageManager: SkillPackageManager): Set<Path> =
    packageManager.policyReadRoots()
      .map { root -> root.toPath().toAbsolutePath().normalize() }
      .toSet()

internal fun OpenCrayToolDispatcher.labeledHostPath(
    label: String,
    root: Path,
    path: Path,
  ): String {
    val relative = runCatching {
      root.relativize(path).toString().replace('\\', '/')
    }.getOrDefault(path.toString().replace('\\', '/'))
    return if (relative.isBlank()) {
      label
    } else {
      "$label/$relative"
    }
  }

internal fun OpenCrayToolDispatcher.gateLocalSkillSourceReadAccess(
    task: AgentTask,
    resultToolName: String,
    sourcePath: Path,
    sourceRef: String,
  ): AgentToolResult? {
    val normalizedSourcePath = sourcePath.toAbsolutePath().normalize()
    val displayPath = toolTargetResolver.displayModelPath(normalizedSourcePath)
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "Read",
      targetPath = normalizedSourcePath,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = if (Files.isDirectory(normalizedSourcePath)) ToolTargetKind.DIRECTORY else ToolTargetKind.FILE,
        primaryPath = normalizedSourcePath,
        primaryTargetPath = displayPath,
        targetSummary = sourceRef,
      ),
    )
    return toolPolicyPipeline.gate(
      plan = plan,
      affectedPaths = mapOf("sourcePath" to displayPath),
      askDetail = "Approval is required before $resultToolName can read the local skill source.",
      denyDetail = "Policy denied $resultToolName local source access.",
    )?.let { result ->
      result.copy(
        toolName = resultToolName,
        metadata = result.metadata + mapOf(
          OpenCrayExecutionMetadataKeys.APPROVAL_RESUME_TOOL_NAME to plan.toolName,
          "requestedToolName" to resultToolName,
          "normalizedToolName" to resultToolName,
        ),
      )
    }
  }

internal fun OpenCrayToolDispatcher.gateRemoteSkillNetworkAccess(
    task: AgentTask,
    resultToolName: String,
    url: String,
    targetSummary: String,
    affectedPaths: Map<String, String> = emptyMap(),
  ): AgentToolResult? {
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "WebFetch",
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.NETWORK,
        primaryTargetPath = url,
        workspaceRelation = ToolWorkspaceRelation.NONE,
        targetSummary = targetSummary,
      ),
    )
    return toolPolicyPipeline.gate(
      plan = plan,
      affectedPaths = affectedPaths,
      askDetail = "Approval is required before $resultToolName can access the remote skills service.",
      denyDetail = "Policy denied $resultToolName remote network access.",
    )?.let { result ->
      result.copy(
        toolName = resultToolName,
        metadata = result.metadata + mapOf(
          OpenCrayExecutionMetadataKeys.APPROVAL_RESUME_TOOL_NAME to plan.toolName,
          "requestedToolName" to resultToolName,
          "normalizedToolName" to resultToolName,
        ),
      )
    }
  }

internal fun OpenCrayToolDispatcher.resolveExplicitLocalSkillSourcePath(sourceRef: String): Path? {
    if (!looksLikeExplicitLocalSkillSource(sourceRef)) {
      return null
    }
    return runCatching {
      toolTargetResolver.resolveReadablePath(
        candidate = sourceRef,
        label = "skill source",
        defaultToRoot = false,
      )
    }.getOrNull()
  }

internal fun OpenCrayToolDispatcher.looksLikeExplicitLocalSkillSource(sourceRef: String): Boolean {
    val normalized = sourceRef.trim()
    return normalized.startsWith(".") ||
      normalized.startsWith("/") ||
      normalized.startsWith("\\") ||
      normalized.contains("\\") ||
      WINDOWS_ABSOLUTE_PATH_REGEX.matches(normalized)
  }
