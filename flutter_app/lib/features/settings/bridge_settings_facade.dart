import '../../core/bridge/opencray_host_bridge.dart';
import '../../core/models/opencray_llm_config.dart';
import '../../core/models/opencray_llm_validation.dart';
import '../../core/models/opencray_media_speech_config.dart';
import '../../core/models/opencray_mcp_settings.dart';
import '../../core/models/opencray_network_search_config.dart';
import '../../core/models/opencray_notification_settings.dart';
import '../../core/models/opencray_personalization_config.dart';
import '../../core/models/opencray_sandbox_settings.dart';
import '../../core/models/opencray_safety_settings.dart';
import '../../core/models/opencray_scheduled_tasks.dart';
import '../../core/models/opencray_settings_snapshot.dart';
import '../../core/models/opencray_strong_background.dart';
import 'notification_settings_models.dart';
import 'safety_settings_models.dart';
import 'scheduled_task_settings_models.dart';
import 'settings_facade.dart';
import 'settings_models.dart';
import 'strong_background_settings_models.dart';

class BridgeSettingsFacade implements SettingsFacade {
  const BridgeSettingsFacade({required OpenCrayHostBridge bridge})
    : _bridge = bridge;

  final OpenCrayHostBridge _bridge;

  @override
  Future<SettingsOverviewSnapshot> loadOverview() async =>
      _mapOverview(await _bridge.loadSettingsOverview());

  @override
  Stream<SettingsOverviewSnapshot> watchOverview() =>
      _bridge.watchSettingsOverview().map(_mapOverview);

  @override
  Future<SettingsDetailSnapshot> loadDetail(SettingsPage page) async =>
      _mapDetail(await _bridge.loadSettingsDetail(page.routeId));

  @override
  Future<NotificationSettingsSnapshot> loadNotificationSettings() async =>
      _mapNotificationSettings(await _bridge.loadNotificationSettings());

  @override
  Future<NotificationSettingsSnapshot> saveNotificationSettings(
    NotificationSettingsSnapshot snapshot,
  ) async => _mapNotificationSettings(
    await _bridge.saveNotificationSettings(
      OpenCrayNotificationSettingsSnapshot(
        masterEnabled: snapshot.masterEnabled,
        defaultDeliveryModeId: snapshot.defaultDeliveryMode.id,
        quietHoursEnabled: snapshot.quietHoursEnabled,
        quietHoursStartMinutes: snapshot.quietHoursStartMinutes,
        quietHoursEndMinutes: snapshot.quietHoursEndMinutes,
        approvalRequestsEnabled: snapshot.approvalRequestsEnabled,
        approvalReminderEnabled: snapshot.approvalReminderEnabled,
        taskFinishedEnabled: snapshot.taskFinishedEnabled,
        taskFailedEnabled: snapshot.taskFailedEnabled,
        scheduledWakeEnabled: snapshot.scheduledWakeEnabled,
        backgroundTaskPausedEnabled: snapshot.backgroundTaskPausedEnabled,
        serviceRecoveredEnabled: snapshot.serviceRecoveredEnabled,
      ),
    ),
  );

  @override
  Future<ScheduledTasksSnapshot> loadScheduledTasks() async =>
      _mapScheduledTasks(await _bridge.loadScheduledTasks());

  @override
  Future<ScheduledTaskDetailSnapshot> loadScheduledTask(
    String scheduleId,
  ) async =>
      _mapScheduledTaskDetail(await _bridge.loadScheduledTask(scheduleId));

  @override
  Future<ScheduledTaskActionResult> updateScheduledTaskEnabled({
    required String scheduleId,
    required bool enabled,
  }) async => _mapScheduledTaskAction(
    await _bridge.updateScheduledTaskEnabled(
      scheduleId: scheduleId,
      enabled: enabled,
    ),
  );

  @override
  Future<ScheduledTaskActionResult> runScheduledTaskNow(
    String scheduleId,
  ) async =>
      _mapScheduledTaskAction(await _bridge.runScheduledTaskNow(scheduleId));

  @override
  Future<ScheduledTaskActionResult> snoozeScheduledTask({
    required String scheduleId,
    int durationMinutes = 15,
  }) async => _mapScheduledTaskAction(
    await _bridge.snoozeScheduledTask(
      scheduleId: scheduleId,
      durationMinutes: durationMinutes,
    ),
  );

  @override
  Future<StrongBackgroundSnapshot> loadStrongBackgroundSnapshot() async =>
      _mapStrongBackground(await _bridge.loadStrongBackgroundSnapshot());

  @override
  Future<StrongBackgroundActionResult> performStrongBackgroundAction(
    StrongBackgroundActionId actionId,
  ) async => _mapStrongBackgroundActionResult(
    await _bridge.performStrongBackgroundAction(actionId.id),
  );

  @override
  Future<NetworkSearchConfigSnapshot> loadNetworkSearchConfig() async =>
      _mapNetworkSearch(await _bridge.loadNetworkSearchConfig());

  @override
  Future<NetworkSearchConfigSnapshot> saveNetworkSearchConfig(
    List<NetworkSearchSlotSnapshot> slots,
  ) async => _mapNetworkSearch(
    await _bridge.saveNetworkSearchConfig(
      slots
          .map(
            (slot) => OpenCrayNetworkSearchSlotSnapshot(
              id: slot.id,
              providerId: slot.providerId,
              label: slot.label,
              baseUrl: slot.baseUrl,
              model: slot.model,
              apiKey: slot.apiKey,
              enabled: slot.enabled,
            ),
          )
          .toList(growable: false),
    ),
  );

  @override
  Future<MediaSpeechConfigSnapshot> loadMediaSpeechConfig() async =>
      _mapMediaSpeech(await _bridge.loadMediaSpeechConfig());

  @override
  Future<MediaSpeechConfigSnapshot> saveMediaSpeechConfig(
    MediaSpeechConfigSnapshot snapshot,
  ) async => _mapMediaSpeech(
    await _bridge.saveMediaSpeechConfig(
      OpenCrayMediaSpeechConfigSnapshot(
        localeTag: snapshot.localeTag,
        title: snapshot.title,
        subtitle: snapshot.subtitle,
        imageGeneration: OpenCrayMediaProviderConfigSnapshot(
          provider: snapshot.imageGeneration.provider,
          baseUrl: snapshot.imageGeneration.baseUrl,
          endpoint: snapshot.imageGeneration.endpoint,
          model: snapshot.imageGeneration.model,
          authProtocol: snapshot.imageGeneration.authProtocol,
          apiKey: snapshot.imageGeneration.apiKey,
        ),
        videoGeneration: OpenCrayMediaProviderConfigSnapshot(
          provider: snapshot.videoGeneration.provider,
          baseUrl: snapshot.videoGeneration.baseUrl,
          endpoint: snapshot.videoGeneration.endpoint,
          model: snapshot.videoGeneration.model,
          authProtocol: snapshot.videoGeneration.authProtocol,
          apiKey: snapshot.videoGeneration.apiKey,
        ),
        voiceGeneration: OpenCrayVoiceProviderConfigSnapshot(
          provider: snapshot.voiceGeneration.provider,
          baseUrl: snapshot.voiceGeneration.baseUrl,
          endpoint: snapshot.voiceGeneration.endpoint,
          model: snapshot.voiceGeneration.model,
          voicePreset: snapshot.voiceGeneration.voicePreset,
          authProtocol: snapshot.voiceGeneration.authProtocol,
          apiKey: snapshot.voiceGeneration.apiKey,
        ),
        sttRouteId: snapshot.sttRoute.id,
        externalStt: OpenCrayMediaProviderConfigSnapshot(
          provider: snapshot.externalStt.provider,
          baseUrl: snapshot.externalStt.baseUrl,
          endpoint: snapshot.externalStt.endpoint,
          model: snapshot.externalStt.model,
          authProtocol: snapshot.externalStt.authProtocol,
          apiKey: snapshot.externalStt.apiKey,
        ),
        onDeviceModel: OpenCrayOnDeviceSttConfigSnapshot(
          modelPackage: snapshot.onDeviceModel.modelPackage,
          downloadStatus: snapshot.onDeviceModel.downloadStatus,
        ),
      ),
    ),
  );

  @override
  Future<SandboxSettingsSnapshot> loadSandboxSettings() async =>
      _mapSandboxSettings(await _bridge.loadSandboxSettings());

  @override
  Future<SandboxSettingsSnapshot> saveSandboxSettings(
    SandboxSettingsSnapshot snapshot,
  ) async => _mapSandboxSettings(
    await _bridge.saveSandboxSettings(
      OpenCraySandboxSettingsSnapshot(
        localeTag: snapshot.localeTag,
        enabled: snapshot.enabled,
        providerId: snapshot.providerId,
        defaultBackend: snapshot.defaultBackend,
        sessionMode: snapshot.sessionMode,
        autoResume: snapshot.autoResume,
        idleTimeoutMinutes: snapshot.idleTimeoutMinutes,
        startupTimeoutMs: snapshot.startupTimeoutMs,
        requestTimeoutMs: snapshot.requestTimeoutMs,
        timeoutAction: snapshot.timeoutAction,
        templateId: snapshot.templateId,
        e2bApiKey: snapshot.e2bApiKey,
        apiKeyConfigured: snapshot.apiKeyConfigured,
      ),
    ),
  );

  @override
  Future<LlmConfigSnapshot> loadLlmConfig() async =>
      _mapLlmConfig(await _bridge.loadLlmConfig());

  @override
  Future<LlmConfigSnapshot> saveLlmConfig({
    required bool enabled,
    bool? streamingEnabled,
    String providerMode = 'cloud',
    required String providerId,
    required String selectedProviderOptionId,
    required String protocol,
    required String providerName,
    required String providerNotes,
    required String baseUrl,
    required String apiKey,
    required String model,
    required String reasoningEffort,
    required String systemPrompt,
    String? openAiPromptCacheKeyStrategy,
    String? openAiPromptCacheRetention,
    bool? anthropicPromptCachingEnabled,
    String? anthropicPromptCacheTtl,
    String selectedOnDeviceModelId = 'gemma-4-e2b-it',
    int onDeviceMaxContextWindow = 32768,
    int onDeviceMaxTokens = 4096,
    int onDeviceTopK = 40,
    double onDeviceTopP = 0.95,
    double onDeviceTemperature = 0.70,
    String onDeviceAccelerator = 'gpu',
    bool onDeviceThinkingEnabled = false,
    bool onDeviceLiteModeEnabled = false,
    String? contextBudgetPreset,
    int? contextBudgetReservedOutputTokens,
    int? contextBudgetSafetyMarginTokens,
    double? contextBudgetEffectiveInputPercent,
  }) async => _mapLlmConfig(
    await _bridge.saveLlmConfig(
      enabled: enabled,
      streamingEnabled: streamingEnabled,
      providerMode: providerMode,
      providerId: providerId,
      selectedProviderOptionId: selectedProviderOptionId,
      protocol: protocol,
      providerName: providerName,
      providerNotes: providerNotes,
      baseUrl: baseUrl,
      apiKey: apiKey,
      model: model,
      reasoningEffort: reasoningEffort,
      systemPrompt: systemPrompt,
      openAiPromptCacheKeyStrategy: openAiPromptCacheKeyStrategy,
      openAiPromptCacheRetention: openAiPromptCacheRetention,
      anthropicPromptCachingEnabled: anthropicPromptCachingEnabled,
      anthropicPromptCacheTtl: anthropicPromptCacheTtl,
      selectedOnDeviceModelId: selectedOnDeviceModelId,
      onDeviceMaxContextWindow: onDeviceMaxContextWindow,
      onDeviceMaxTokens: onDeviceMaxTokens,
      onDeviceTopK: onDeviceTopK,
      onDeviceTopP: onDeviceTopP,
      onDeviceTemperature: onDeviceTemperature,
      onDeviceAccelerator: onDeviceAccelerator,
      onDeviceThinkingEnabled: onDeviceThinkingEnabled,
      onDeviceLiteModeEnabled: onDeviceLiteModeEnabled,
      contextBudgetPreset: contextBudgetPreset,
      contextBudgetReservedOutputTokens: contextBudgetReservedOutputTokens,
      contextBudgetSafetyMarginTokens: contextBudgetSafetyMarginTokens,
      contextBudgetEffectiveInputPercent: contextBudgetEffectiveInputPercent,
    ),
  );

  @override
  Future<LlmConfigSnapshot> saveCustomLlmProvider({
    required String selectedProviderOptionId,
    bool? streamingEnabled,
    required String protocol,
    required String providerName,
    required String providerNotes,
    required String baseUrl,
    required String apiKey,
    required String model,
    required String reasoningEffort,
    required String systemPrompt,
    String? openAiPromptCacheKeyStrategy,
    String? openAiPromptCacheRetention,
    bool? anthropicPromptCachingEnabled,
    String? anthropicPromptCacheTtl,
  }) async => _mapLlmConfig(
    await _bridge.saveCustomLlmProvider(
      selectedProviderOptionId: selectedProviderOptionId,
      streamingEnabled: streamingEnabled,
      protocol: protocol,
      providerName: providerName,
      providerNotes: providerNotes,
      baseUrl: baseUrl,
      apiKey: apiKey,
      model: model,
      reasoningEffort: reasoningEffort,
      systemPrompt: systemPrompt,
      openAiPromptCacheKeyStrategy: openAiPromptCacheKeyStrategy,
      openAiPromptCacheRetention: openAiPromptCacheRetention,
      anthropicPromptCachingEnabled: anthropicPromptCachingEnabled,
      anthropicPromptCacheTtl: anthropicPromptCacheTtl,
    ),
  );

  @override
  Future<LlmValidationResult> validateLlmConfig({
    required String providerId,
    required String protocol,
    required String baseUrl,
    required String apiKey,
    required String model,
    required String reasoningEffort,
  }) async => _mapLlmValidation(
    await _bridge.validateLlmConfig(
      providerId: providerId,
      protocol: protocol,
      baseUrl: baseUrl,
      apiKey: apiKey,
      model: model,
      reasoningEffort: reasoningEffort,
    ),
  );

  @override
  Future<LlmConfigSnapshot> downloadOnDeviceLlmModel(String modelId) async =>
      _mapLlmConfig(await _bridge.downloadOnDeviceLlmModel(modelId));

  @override
  Future<LlmConfigSnapshot> cancelOnDeviceLlmModelDownload(
    String modelId,
  ) async =>
      _mapLlmConfig(await _bridge.cancelOnDeviceLlmModelDownload(modelId));

  @override
  Future<LlmConfigSnapshot> deleteOnDeviceLlmModel(String modelId) async =>
      _mapLlmConfig(await _bridge.deleteOnDeviceLlmModel(modelId));

  @override
  Future<PersonalizationConfigSnapshot> loadPersonalizationConfig() async =>
      _mapPersonalization(await _bridge.loadPersonalizationConfig());

  @override
  Future<PersonalizationConfigSnapshot> savePersonalizationConfig({
    required String presetId,
    required String customLabel,
    required String customGuidance,
  }) async => _mapPersonalization(
    await _bridge.savePersonalizationConfig(
      presetId: presetId,
      customLabel: customLabel,
      customGuidance: customGuidance,
    ),
  );

  @override
  Future<PersonalizationConfigSnapshot> setAppLanguage(
    String languageId,
  ) async => _mapPersonalization(await _bridge.setAppLanguage(languageId));

  @override
  Future<PersonalizationConfigSnapshot> runPersonalizationReset(
    String scopeId,
  ) async =>
      _mapPersonalization(await _bridge.runPersonalizationReset(scopeId));

  @override
  Future<McpSettingsSnapshot> loadMcpSettings() async =>
      _mapMcpSettings(await _bridge.loadMcpSettings());

  @override
  Future<McpSettingsSnapshot> setMcpMasterEnabled(bool enabled) async =>
      _mapMcpSettings(await _bridge.setMcpMasterEnabled(enabled));

  @override
  Future<McpSettingsSnapshot> setMcpServerEnabled({
    required String serverId,
    required bool enabled,
  }) async => _mapMcpSettings(
    await _bridge.setMcpServerEnabled(serverId: serverId, enabled: enabled),
  );

  @override
  Future<SafetySettingsSnapshot> loadSafetySettings() async =>
      _mapSafetySettings(await _bridge.loadSafetySettings());

  @override
  Future<bool> authorizeExternalAccessLocation(String locationId) =>
      _bridge.authorizeExternalAccessLocation(locationId);

  @override
  Future<SafetySettingsSnapshot> saveSafetySettings(
    SafetySettingsSnapshot snapshot,
  ) async => _mapSafetySettings(
    await _bridge.saveSafetySettings(
      automationModeId: snapshot.automationMode.id,
      rollbackJournalEnabled: snapshot.rollbackJournalEnabled,
      maxFilesPerBatch: snapshot.maxFilesPerBatch,
      maxAgentTurns: snapshot.maxAgentTurns,
      maxToolCalls: snapshot.maxToolCalls,
      undoWindowHours: snapshot.undoWindowHours,
      fileChangesPolicyId: snapshot.fileChangesPolicy.id,
      fileDeletesPolicyId: snapshot.fileDeletesPolicy.id,
      shellCommandsPolicyId: snapshot.shellCommandsPolicy.id,
      externalAccessModeId: snapshot.externalAccessMode.id,
      photoLibraryEnabled: snapshot.isLocationEnabled('photo_library'),
      downloadsEnabled: snapshot.isLocationEnabled('downloads'),
      documentsEnabled: snapshot.isLocationEnabled('documents'),
      recordingsEnabled: snapshot.isLocationEnabled('recordings'),
      workspaceAccessProfileId: snapshot.workspaceAccessProfile.id,
      readOnlyOutsideWorkspace: snapshot.readOnlyOutsideWorkspace,
      liveContextModeId: snapshot.liveContextMode.id,
      memoryToolsEnabled: snapshot.memoryToolsEnabled,
      subAgentContextDefaultModeId: snapshot.subAgentContextDefaultMode?.id,
      subAgentContextProfileOverrides:
          snapshot.subAgentContextProfileOverrides.map(
            (profileId, mode) => MapEntry(profileId, mode.id),
          ),
    ),
  );

  static SettingsOverviewSnapshot _mapOverview(
    OpenCraySettingsOverviewSnapshot snapshot,
  ) {
    return SettingsOverviewSnapshot(
      eyebrow: snapshot.eyebrow,
      title: snapshot.title,
      subtitle: snapshot.subtitle,
      deviceTitle: snapshot.deviceTitle,
      deviceSummary: snapshot.deviceSummary,
      entries: snapshot.entries
          .map(
            (entry) => SettingsHomeEntrySnapshot(
              page: settingsPageFromRouteId(entry.routeId),
              title: entry.title,
            ),
          )
          .toList(growable: false),
    );
  }

  static SettingsDetailSnapshot _mapDetail(
    OpenCraySettingsDetailSnapshot snapshot,
  ) {
    return SettingsDetailSnapshot(
      page: settingsPageFromRouteId(snapshot.routeId),
      title: snapshot.title,
      subtitle: snapshot.subtitle,
      sections: snapshot.sections
          .map(
            (section) => SettingsSectionSnapshot(
              title: section.title,
              helperText: section.helperText,
              rows: section.rows
                  .map(
                    (row) => switch (row.trailingKind) {
                      OpenCraySettingsRowTrailingKind.chevron =>
                        SettingsRowSnapshot.chevron(
                          title: row.title,
                          subtitle: row.subtitle,
                        ),
                      OpenCraySettingsRowTrailingKind.toggle =>
                        SettingsRowSnapshot.toggle(
                          title: row.title,
                          subtitle: row.subtitle,
                          toggleValue: row.toggleValue ?? false,
                        ),
                      OpenCraySettingsRowTrailingKind.value =>
                        SettingsRowSnapshot.value(
                          title: row.title,
                          valueLabel: row.valueLabel ?? '',
                        ),
                    },
                  )
                  .toList(growable: false),
              segmentedOptions: section.segmentedOptions,
              segmentedIndex: section.segmentedIndex,
              inlinePanelText: section.inlinePanelText,
              backgroundTone:
                  section.backgroundTone ==
                      OpenCraySettingsSectionBackgroundTone.danger
                  ? SettingsSectionBackgroundTone.danger
                  : SettingsSectionBackgroundTone.surface,
            ),
          )
          .toList(growable: false),
    );
  }

  static StrongBackgroundSnapshot _mapStrongBackground(
    OpenCrayStrongBackgroundSnapshot snapshot,
  ) {
    return StrongBackgroundSnapshot(
      source: snapshot.source,
      available: snapshot.available,
      tier: strongBackgroundTierFromId(snapshot.tierId),
      setupComplete: snapshot.setupComplete,
      recommendedActionIds: snapshot.recommendedActionIds
          .map(strongBackgroundActionIdFromId)
          .toList(growable: false),
      notifications: StrongBackgroundNotificationsSnapshot(
        permissionRequired: snapshot.notifications.permissionRequired,
        permissionGranted: snapshot.notifications.permissionGranted,
        enabled: snapshot.notifications.enabled,
        configured: snapshot.notifications.configured,
      ),
      exactAlarms: StrongBackgroundExactAlarmSnapshot(
        accessRequired: snapshot.exactAlarms.accessRequired,
        accessGranted: snapshot.exactAlarms.accessGranted,
        configured: snapshot.exactAlarms.configured,
      ),
      batteryOptimization: StrongBackgroundBatteryOptimizationSnapshot(
        supported: snapshot.batteryOptimization.supported,
        exempt: snapshot.batteryOptimization.exempt,
        configured: snapshot.batteryOptimization.configured,
      ),
      actions: snapshot.actions
          .map(
            (action) => StrongBackgroundActionSnapshot(
              id: strongBackgroundActionIdFromId(action.id),
              available: action.available,
              recommended: action.recommended,
            ),
          )
          .toList(growable: false),
      runtimeServiceConnectionState: snapshot.runtimeServiceConnectionState,
    );
  }

  static NotificationSettingsSnapshot _mapNotificationSettings(
    OpenCrayNotificationSettingsSnapshot snapshot,
  ) {
    return NotificationSettingsSnapshot(
      masterEnabled: snapshot.masterEnabled,
      defaultDeliveryMode: notificationDeliveryModeFromId(
        snapshot.defaultDeliveryModeId,
      ),
      quietHoursEnabled: snapshot.quietHoursEnabled,
      quietHoursStartMinutes: snapshot.quietHoursStartMinutes,
      quietHoursEndMinutes: snapshot.quietHoursEndMinutes,
      approvalRequestsEnabled: snapshot.approvalRequestsEnabled,
      approvalReminderEnabled: snapshot.approvalReminderEnabled,
      taskFinishedEnabled: snapshot.taskFinishedEnabled,
      taskFailedEnabled: snapshot.taskFailedEnabled,
      scheduledWakeEnabled: snapshot.scheduledWakeEnabled,
      backgroundTaskPausedEnabled: snapshot.backgroundTaskPausedEnabled,
      serviceRecoveredEnabled: snapshot.serviceRecoveredEnabled,
    );
  }

  static ScheduledTasksSnapshot _mapScheduledTasks(
    OpenCrayScheduledTasksSnapshot snapshot,
  ) => ScheduledTasksSnapshot(
    tasks: snapshot.tasks.map(_mapScheduledTaskSummary).toList(growable: false),
    totalCount: snapshot.totalCount,
    enabledCount: snapshot.enabledCount,
  );

  static ScheduledTaskSummary _mapScheduledTaskSummary(
    OpenCrayScheduledTaskSummary task,
  ) => ScheduledTaskSummary(
    scheduleId: task.scheduleId,
    sessionId: task.sessionId,
    title: task.title,
    enabled: task.enabled,
    triggerKind: task.triggerKind,
    triggerSummary: task.triggerSummary,
    nextTriggerAtEpochMs: task.nextTriggerAtEpochMs,
    snoozedUntilEpochMs: task.snoozedUntilEpochMs,
  );

  static ScheduledTaskDetailSnapshot _mapScheduledTaskDetail(
    OpenCrayScheduledTaskDetailSnapshot snapshot,
  ) => ScheduledTaskDetailSnapshot(
    task: ScheduledTaskDetails(
      scheduleId: snapshot.task.scheduleId,
      sessionId: snapshot.task.sessionId,
      title: snapshot.task.title,
      enabled: snapshot.task.enabled,
      triggerKind: snapshot.task.triggerKind,
      triggerSummary: snapshot.task.triggerSummary,
      prompt: snapshot.task.prompt,
      nextTriggerAtEpochMs: snapshot.task.nextTriggerAtEpochMs,
      snoozedUntilEpochMs: snapshot.task.snoozedUntilEpochMs,
      conflictPolicy: snapshot.task.conflictPolicy,
      foregroundNotificationRequired:
          snapshot.task.foregroundNotificationRequired,
      notifyOnQueued: snapshot.task.notifyOnQueued,
      notifyOnApproval: snapshot.task.notifyOnApproval,
      notifyOnCompletion: snapshot.task.notifyOnCompletion,
      notifyOnInterruption: snapshot.task.notifyOnInterruption,
      createdAtEpochMs: snapshot.task.createdAtEpochMs,
      updatedAtEpochMs: snapshot.task.updatedAtEpochMs,
    ),
    recentRuns: snapshot.recentRuns
        .map(
          (run) => ScheduledTaskRunRecord(
            scheduleRunId: run.scheduleRunId,
            triggerReason: run.triggerReason,
            result: run.result,
            triggeredAtEpochMs: run.triggeredAtEpochMs,
            acceptedAtEpochMs: run.acceptedAtEpochMs,
            createdRunId: run.createdRunId,
            createdTaskId: run.createdTaskId,
            failureReason: run.failureReason,
            recoverySource: run.recoverySource,
            updatedAtEpochMs: run.updatedAtEpochMs,
          ),
        )
        .toList(growable: false),
    totalRunCount: snapshot.totalRunCount,
  );

  static ScheduledTaskActionResult _mapScheduledTaskAction(
    OpenCrayScheduledTaskActionResult result,
  ) => ScheduledTaskActionResult(
    action: result.action,
    scheduleId: result.scheduleId,
    title: result.title,
    enabled: result.enabled,
    scheduleRunId: result.scheduleRunId,
    requestedAtEpochMs: result.requestedAtEpochMs,
    nextTriggerAtEpochMs: result.nextTriggerAtEpochMs,
    snoozedUntilEpochMs: result.snoozedUntilEpochMs,
  );

  static StrongBackgroundActionResult _mapStrongBackgroundActionResult(
    OpenCrayStrongBackgroundActionResult result,
  ) {
    return StrongBackgroundActionResult(
      source: result.source,
      actionId: strongBackgroundActionIdFromId(result.actionId),
      available: result.available,
      launched: result.launched,
      reason: result.reason,
      fallbackActionId: result.fallbackActionId == null
          ? null
          : strongBackgroundActionIdFromId(result.fallbackActionId!),
    );
  }

  static NetworkSearchConfigSnapshot _mapNetworkSearch(
    OpenCrayNetworkSearchConfigSnapshot snapshot,
  ) {
    return NetworkSearchConfigSnapshot(
      localeTag: snapshot.localeTag,
      title: snapshot.title,
      subtitle: snapshot.subtitle,
      slots: snapshot.slots
          .map(
            (slot) => NetworkSearchSlotSnapshot(
              id: slot.id,
              providerId: slot.providerId,
              label: slot.label,
              baseUrl: slot.baseUrl,
              model: slot.model,
              apiKey: slot.apiKey,
              enabled: slot.enabled,
            ),
          )
          .toList(growable: false),
    );
  }

  static LlmConfigSnapshot _mapLlmConfig(OpenCrayLlmConfigSnapshot snapshot) {
    return LlmConfigSnapshot(
      localeTag: snapshot.localeTag,
      enabled: snapshot.enabled,
      streamingEnabled: snapshot.streamingEnabled,
      providerMode: snapshot.providerMode,
      providerId: snapshot.providerId,
      selectedProviderOptionId: snapshot.selectedProviderOptionId,
      protocol: snapshot.protocol,
      providerOptions: snapshot.providerOptions
          .map(
            (option) => LlmProviderOption(
              id: option.id,
              providerId: option.providerId,
              title: option.title,
              subtitle: option.subtitle,
              defaultBaseUrl: option.defaultBaseUrl,
              defaultModel: option.defaultModel,
              protocol: option.protocol,
              apiKey: option.apiKey,
              isCustom: option.isCustom,
            ),
          )
          .toList(growable: false),
      providerName: snapshot.providerName,
      providerNotes: snapshot.providerNotes,
      baseUrl: snapshot.baseUrl,
      apiKey: snapshot.apiKey,
      model: snapshot.model,
      reasoningEffort: snapshot.reasoningEffort,
      systemPrompt: snapshot.systemPrompt,
      helperText: snapshot.helperText,
      openAiPromptCacheKeyStrategy: snapshot.openAiPromptCacheKeyStrategy,
      openAiPromptCacheRetention: snapshot.openAiPromptCacheRetention,
      anthropicPromptCachingEnabled: snapshot.anthropicPromptCachingEnabled,
      anthropicPromptCacheTtl: snapshot.anthropicPromptCacheTtl,
      onDeviceModels: snapshot.onDeviceModels
          .map(
            (option) => LlmOnDeviceModelOption(
              id: option.id,
              title: option.title,
              subtitle: option.subtitle,
              sizeLabel: option.sizeLabel,
              fileSizeBytes: option.fileSizeBytes,
              installState: option.installState,
              downloadedBytes: option.downloadedBytes,
              downloadBytesPerSecond: option.downloadBytesPerSecond,
              sha256Verified: option.sha256Verified,
              isSelected: option.isSelected,
              lastError: option.lastError,
            ),
          )
          .toList(growable: false),
      selectedOnDeviceModelId: snapshot.selectedOnDeviceModelId,
      onDeviceMaxContextWindow: snapshot.onDeviceMaxContextWindow,
      onDeviceMaxTokens: snapshot.onDeviceMaxTokens,
      onDeviceTopK: snapshot.onDeviceTopK,
      onDeviceTopP: snapshot.onDeviceTopP,
      onDeviceTemperature: snapshot.onDeviceTemperature,
      onDeviceAccelerator: snapshot.onDeviceAccelerator,
      onDeviceThinkingEnabled: snapshot.onDeviceThinkingEnabled,
      onDeviceLiteModeEnabled: snapshot.onDeviceLiteModeEnabled,
      contextBudgetPreset: snapshot.contextBudgetPreset,
      contextBudgetReservedOutputTokens:
          snapshot.contextBudgetReservedOutputTokens,
      contextBudgetSafetyMarginTokens: snapshot.contextBudgetSafetyMarginTokens,
      contextBudgetEffectiveInputPercent:
          snapshot.contextBudgetEffectiveInputPercent,
    );
  }

  static MediaSpeechConfigSnapshot _mapMediaSpeech(
    OpenCrayMediaSpeechConfigSnapshot snapshot,
  ) {
    return MediaSpeechConfigSnapshot(
      localeTag: snapshot.localeTag,
      title: snapshot.title,
      subtitle: snapshot.subtitle,
      imageGeneration: MediaProviderConfigSnapshot(
        provider: snapshot.imageGeneration.provider,
        baseUrl: snapshot.imageGeneration.baseUrl,
        endpoint: snapshot.imageGeneration.endpoint,
        model: snapshot.imageGeneration.model,
        authProtocol: snapshot.imageGeneration.authProtocol,
        apiKey: snapshot.imageGeneration.apiKey,
      ),
      videoGeneration: MediaProviderConfigSnapshot(
        provider: snapshot.videoGeneration.provider,
        baseUrl: snapshot.videoGeneration.baseUrl,
        endpoint: snapshot.videoGeneration.endpoint,
        model: snapshot.videoGeneration.model,
        authProtocol: snapshot.videoGeneration.authProtocol,
        apiKey: snapshot.videoGeneration.apiKey,
      ),
      voiceGeneration: VoiceProviderConfigSnapshot(
        provider: snapshot.voiceGeneration.provider,
        baseUrl: snapshot.voiceGeneration.baseUrl,
        endpoint: snapshot.voiceGeneration.endpoint,
        model: snapshot.voiceGeneration.model,
        voicePreset: snapshot.voiceGeneration.voicePreset,
        authProtocol: snapshot.voiceGeneration.authProtocol,
        apiKey: snapshot.voiceGeneration.apiKey,
      ),
      sttRoute: mediaSpeechSttRouteFromId(snapshot.sttRouteId),
      externalStt: MediaProviderConfigSnapshot(
        provider: snapshot.externalStt.provider,
        baseUrl: snapshot.externalStt.baseUrl,
        endpoint: snapshot.externalStt.endpoint,
        model: snapshot.externalStt.model,
        authProtocol: snapshot.externalStt.authProtocol,
        apiKey: snapshot.externalStt.apiKey,
      ),
      onDeviceModel: OnDeviceSttConfigSnapshot(
        modelPackage: snapshot.onDeviceModel.modelPackage,
        downloadStatus: snapshot.onDeviceModel.downloadStatus,
      ),
    );
  }

  static SandboxSettingsSnapshot _mapSandboxSettings(
    OpenCraySandboxSettingsSnapshot snapshot,
  ) {
    return SandboxSettingsSnapshot(
      localeTag: snapshot.localeTag,
      enabled: snapshot.enabled,
      providerId: snapshot.providerId,
      defaultBackend: snapshot.defaultBackend,
      sessionMode: snapshot.sessionMode,
      autoResume: snapshot.autoResume,
      idleTimeoutMinutes: snapshot.idleTimeoutMinutes,
      startupTimeoutMs: snapshot.startupTimeoutMs,
      requestTimeoutMs: snapshot.requestTimeoutMs,
      timeoutAction: snapshot.timeoutAction,
      templateId: snapshot.templateId,
      e2bApiKey: snapshot.e2bApiKey,
      apiKeyConfigured: snapshot.apiKeyConfigured,
    );
  }

  static LlmValidationResult _mapLlmValidation(
    OpenCrayLlmValidationResult result,
  ) {
    return LlmValidationResult(
      isSuccess: result.isSuccess,
      message: result.message,
    );
  }

  static PersonalizationConfigSnapshot _mapPersonalization(
    OpenCrayPersonalizationConfigSnapshot snapshot,
  ) {
    return PersonalizationConfigSnapshot(
      title: snapshot.title,
      subtitle: snapshot.subtitle,
      introTitle: snapshot.introTitle,
      introBody: snapshot.introBody,
      introHelper: snapshot.introHelper,
      presetsTitle: snapshot.presetsTitle,
      presetsHelper: snapshot.presetsHelper,
      presets: snapshot.presets
          .map(
            (preset) => PersonalizationPresetOption(
              id: preset.id,
              title: preset.title,
              summary: preset.summary,
              voice: preset.voice,
              status: preset.status,
              isSelected: preset.isSelected,
            ),
          )
          .toList(growable: false),
      selectedPresetId: snapshot.selectedPresetId,
      customOverlayTitle: snapshot.customOverlayTitle,
      customOverlayHelper: snapshot.customOverlayHelper,
      customLabelHint: snapshot.customLabelHint,
      customLabelHelper: snapshot.customLabelHelper,
      customGuidanceHint: snapshot.customGuidanceHint,
      customGuidanceHelper: snapshot.customGuidanceHelper,
      customLabel: snapshot.customLabel,
      customGuidance: snapshot.customGuidance,
      behaviorDefaultsTitle: snapshot.behaviorDefaultsTitle,
      appLanguageTitle: snapshot.appLanguageTitle,
      appLanguageOptions: snapshot.appLanguageOptions
          .map(
            (option) => PersonalizationLanguageOption(
              id: option.id,
              title: option.title,
              isSelected: option.isSelected,
            ),
          )
          .toList(growable: false),
      selectedAppLanguageId: snapshot.selectedAppLanguageId,
      livePreviewTitle: snapshot.livePreviewTitle,
      livePreviewName: snapshot.livePreviewName,
      livePreviewSummary: snapshot.livePreviewSummary,
      queueTitle: snapshot.queueTitle,
      queueBody: snapshot.queueBody,
      queueIsIdle: snapshot.queueIsIdle,
      lastResetTitle: snapshot.lastResetTitle,
      lastResetMessage: snapshot.lastResetMessage,
      resetActions: snapshot.resetActions
          .map(
            (action) => PersonalizationResetAction(
              scopeId: action.scopeId,
              title: action.title,
              scopeBody: action.scopeBody,
              retainBody: action.retainBody,
              confirmationToken: action.confirmationToken,
              inputHint: action.inputHint,
              disabledGuidance: action.disabledGuidance,
              typeExactGuidance: action.typeExactGuidance,
              armedGuidance: action.armedGuidance,
              isInputEnabled: action.isInputEnabled,
            ),
          )
          .toList(growable: false),
    );
  }

  static McpSettingsSnapshot _mapMcpSettings(
    OpenCrayMcpSettingsSnapshot snapshot,
  ) {
    return McpSettingsSnapshot(
      title: snapshot.title,
      subtitle: snapshot.subtitle,
      masterTitle: snapshot.masterTitle,
      masterSummary: snapshot.masterSummary,
      masterEnabled: snapshot.masterEnabled,
      summaryLine: snapshot.summaryLine,
      serversTitle: snapshot.serversTitle,
      serversHelper: snapshot.serversHelper,
      masterDisabledTitle: snapshot.masterDisabledTitle,
      masterDisabledBody: snapshot.masterDisabledBody,
      servers: snapshot.servers
          .map(
            (server) => McpServerSnapshot(
              id: server.id,
              title: server.title,
              statusLabel: server.statusLabel,
              statusTone: server.statusTone,
              trustLine: server.trustLine,
              authLine: server.authLine,
              readinessLine: server.readinessLine,
              transportLine: server.transportLine,
              exposureLine: server.exposureLine,
              guidance: server.guidance,
              actionLabel: server.actionLabel,
              actionTurnsOn: server.actionTurnsOn,
              isActionEnabled: server.isActionEnabled,
            ),
          )
          .toList(growable: false),
    );
  }

  static SafetySettingsSnapshot _mapSafetySettings(
    OpenCraySafetySettingsSnapshot snapshot,
  ) {
    return SafetySettingsSnapshot(
      automationMode: safetyAutomationModeFromId(snapshot.automationModeId),
      rollbackJournalEnabled: snapshot.rollbackJournalEnabled,
      maxFilesPerBatch: snapshot.maxFilesPerBatch,
      maxAgentTurns: snapshot.maxAgentTurns,
      maxToolCalls: snapshot.maxToolCalls,
      undoWindowHours: snapshot.undoWindowHours,
      fileChangesPolicy: toolPolicyOverrideFromId(snapshot.fileChangesPolicyId),
      fileDeletesPolicy: toolPolicyOverrideFromId(snapshot.fileDeletesPolicyId),
      shellCommandsPolicy: toolPolicyOverrideFromId(
        snapshot.shellCommandsPolicyId,
      ),
      externalAccessMode: externalAccessModeFromId(
        snapshot.externalAccessModeId,
      ),
      locations: snapshot.locations
          .map(
            (location) => SafetyLocationSetting(
              id: location.id,
              enabled: location.enabled,
            ),
          )
          .toList(growable: false),
      workspaceAccessProfile: workspaceAccessProfileFromId(
        snapshot.workspaceAccessProfileId,
      ),
      readOnlyOutsideWorkspace: snapshot.readOnlyOutsideWorkspace,
      liveContextMode: liveContextModeFromId(snapshot.liveContextModeId),
      memoryToolsEnabled: snapshot.memoryToolsEnabled,
      subAgentContextDefaultMode: subAgentContextModeFromId(
        snapshot.subAgentContextDefaultModeId,
      ),
      subAgentContextProfileOverrides:
          Map<String, SubAgentContextMode>.unmodifiable(
            Map<String, SubAgentContextMode>.fromEntries(
              snapshot.subAgentContextProfileOverrides.entries
                  .map(
                    (entry) => MapEntry(
                      entry.key,
                      subAgentContextModeFromId(entry.value),
                    ),
                  )
                  .where((entry) => entry.value != null)
                  .map(
                    (entry) => MapEntry(entry.key, entry.value!),
                  ),
            ),
          ),
    );
  }
}
