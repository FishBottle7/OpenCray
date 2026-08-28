import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opencray/core/design/opencray_controls.dart';
import 'package:opencray/features/settings/settings.dart';

import 'settings_feature_test_support.dart';

void main() {
  testWidgets(
    'standalone notifications page saves the master switch and opens event alerts',
    (tester) async {
      final facade = FakeSettingsFacade(
        llmConfig: const LlmConfigSnapshot(
          localeTag: 'en',
          enabled: false,
          providerId: 'openai',
          selectedProviderOptionId: 'openai',
          protocol: 'openai',
          providerOptions: <LlmProviderOption>[
            LlmProviderOption(
              id: 'openai',
              providerId: 'openai',
              title: 'OpenAI',
              subtitle: 'Official OpenAI-compatible endpoint.',
              defaultBaseUrl: 'https://api.openai.com/v1',
              defaultModel: 'gpt-4o-mini',
              protocol: 'openai',
              apiKey: '',
              isCustom: false,
            ),
          ],
          providerName: 'OpenAI',
          providerNotes: '',
          baseUrl: 'https://api.openai.com/v1',
          apiKey: '',
          model: 'gpt-4o-mini',
          reasoningEffort: 'medium',
          systemPrompt: '',
          helperText: 'Helper text',
        ),
        validationResult: const LlmValidationResult(
          isSuccess: true,
          message: 'Validated.',
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: SettingsFeatureScreen(
            facade: facade,
            initialPage: SettingsPage.notificationsBackground,
            standalone: true,
          ),
        ),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      final masterSwitch = find.descendant(
        of: find.byKey(const ValueKey<String>('notification-master-enabled')),
        matching: find.byType(OpenCraySwitch),
      );
      await tester.ensureVisible(masterSwitch);
      await tester.tap(masterSwitch);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(facade.notificationSaveCallCount, 1);
      expect(facade.notificationSettings.masterEnabled, isFalse);

      await tester.ensureVisible(find.text('Alert types'));
      await tester.pump();
      await tester.tap(find.text('Alert types'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(find.text('Event Alerts'), findsOneWidget);
      expect(find.text('Approval requests'), findsOneWidget);
    },
  );

  testWidgets(
    'notifications page opens scheduled tasks and list switch saves',
    (tester) async {
      final facade = buildSettingsFacade()
        ..scheduledTasks = const ScheduledTasksSnapshot(
          tasks: <ScheduledTaskSummary>[
            ScheduledTaskSummary(
              scheduleId: 'schedule-1',
              sessionId: 'session-1',
              title: 'Morning review',
              enabled: true,
              triggerKind: 'recurrence',
              triggerSummary: 'Every day at 09:00',
              nextTriggerAtEpochMs: 1784336400000,
            ),
          ],
          totalCount: 1,
          enabledCount: 1,
        );
      final debugBridge = buildDebugBridge();

      await tester.pumpWidget(
        MaterialApp(
          home: SettingsFeatureScreen(
            facade: facade,
            initialPage: SettingsPage.notificationsBackground,
            standalone: true,
            debugBridge: debugBridge,
          ),
        ),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(find.text('1 tasks, 1 enabled.'), findsOneWidget);
      final scheduledTasksLink = find.text('Scheduled tasks');
      await tester.ensureVisible(scheduledTasksLink);
      await tester.tap(scheduledTasksLink);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(find.text('Morning review'), findsOneWidget);
      final taskSwitch = find.descendant(
        of: find.byKey(const ValueKey<String>('settings-scheduled-tasks')),
        matching: find.byType(OpenCraySwitch),
      );
      await tester.tap(taskSwitch);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(facade.scheduledTaskEnabledRequests, <bool>[false]);
      expect(facade.scheduledTasks.tasks.single.enabled, isFalse);

      await tester.tap(find.text('Morning review'));
      await tester.pump();
      expect(
        debugBridge.persistedSettingsRouteIds.last,
        SettingsPage.scheduledTasks.routeId,
      );
    },
  );

  testWidgets('scheduled task detail runs snoozes toggles and shows history', (
    tester,
  ) async {
    final facade = buildSettingsFacade()
      ..scheduledTaskDetail = const ScheduledTaskDetailSnapshot(
        task: ScheduledTaskDetails(
          scheduleId: 'schedule-1',
          sessionId: 'session-1',
          title: 'Morning review',
          enabled: true,
          triggerKind: 'recurrence',
          triggerSummary: 'Every day at 09:00',
          prompt: 'Review open work and summarize blockers.',
          conflictPolicy: 'queue',
          foregroundNotificationRequired: true,
          notifyOnQueued: true,
          notifyOnApproval: true,
          notifyOnCompletion: true,
          notifyOnInterruption: true,
          createdAtEpochMs: 1784200000000,
          updatedAtEpochMs: 1784200000000,
          nextTriggerAtEpochMs: 1784336400000,
        ),
        recentRuns: <ScheduledTaskRunRecord>[
          ScheduledTaskRunRecord(
            scheduleRunId: 'schedule-run-1',
            triggerReason: 'alarm',
            result: 'accepted',
            triggeredAtEpochMs: 1784250000000,
            updatedAtEpochMs: 1784250000000,
          ),
        ],
        totalRunCount: 1,
      );

    await tester.pumpWidget(
      MaterialApp(
        home: SettingsFeatureScreen(
          facade: facade,
          initialPage: SettingsPage.scheduledTaskDetail,
          initialScheduleId: 'schedule-1',
          standalone: true,
        ),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));

    expect(
      find.text('Review open work and summarize blockers.'),
      findsOneWidget,
    );
    expect(find.text('Accepted'), findsOneWidget);
    expect(find.text('Exact alarm trigger'), findsOneWidget);

    final runNow = find.byKey(const ValueKey<String>('scheduled-task-run-now'));
    await tester.ensureVisible(runNow);
    await tester.tap(runNow);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(facade.scheduledTaskRunNowCallCount, 1);

    final snooze = find.byKey(const ValueKey<String>('scheduled-task-snooze'));
    await tester.ensureVisible(snooze);
    await tester.tap(snooze);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(facade.scheduledTaskSnoozeCallCount, 1);

    final enabledSwitch = find.descendant(
      of: find.byKey(const ValueKey<String>('scheduled-task-enabled')),
      matching: find.byType(OpenCraySwitch),
    );
    await tester.ensureVisible(enabledSwitch);
    await tester.tap(enabledSwitch);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(facade.scheduledTaskEnabledRequests, <bool>[false]);
  });

  testWidgets('event alerts page saves per-event toggles', (tester) async {
    final facade = FakeSettingsFacade(
      llmConfig: const LlmConfigSnapshot(
        localeTag: 'en',
        enabled: false,
        providerId: 'openai',
        selectedProviderOptionId: 'openai',
        protocol: 'openai',
        providerOptions: <LlmProviderOption>[
          LlmProviderOption(
            id: 'openai',
            providerId: 'openai',
            title: 'OpenAI',
            subtitle: 'Official OpenAI-compatible endpoint.',
            defaultBaseUrl: 'https://api.openai.com/v1',
            defaultModel: 'gpt-4o-mini',
            protocol: 'openai',
            apiKey: '',
            isCustom: false,
          ),
        ],
        providerName: 'OpenAI',
        providerNotes: '',
        baseUrl: 'https://api.openai.com/v1',
        apiKey: '',
        model: 'gpt-4o-mini',
        reasoningEffort: 'medium',
        systemPrompt: '',
        helperText: 'Helper text',
      ),
      validationResult: const LlmValidationResult(
        isSuccess: true,
        message: 'Validated.',
      ),
    );

    await tester.pumpWidget(
      MaterialApp(
        home: SettingsFeatureScreen(
          facade: facade,
          initialPage: SettingsPage.eventAlerts,
          standalone: true,
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byType(OpenCraySwitch).first);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));

    expect(facade.notificationSaveCallCount, 1);
    expect(facade.notificationSettings.approvalRequestsEnabled, isFalse);
    expect(find.text('Approval requests'), findsOneWidget);
  });

  testWidgets(
    'notifications page keeps the newest state during overlapping saves',
    (tester) async {
      final firstSaveStarted = Completer<void>();
      final releaseFirstSave = Completer<void>();
      var saveInvocation = 0;
      final facade = notificationTestFacade(
        onSaveNotificationSettings: (snapshot) async {
          saveInvocation += 1;
          if (saveInvocation == 1) {
            firstSaveStarted.complete();
            await releaseFirstSave.future;
          }
          return snapshot;
        },
      );

      await tester.pumpWidget(
        MaterialApp(
          home: SettingsFeatureScreen(
            facade: facade,
            initialPage: SettingsPage.notificationsBackground,
            standalone: true,
          ),
        ),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      final masterSwitch = find.descendant(
        of: find.byKey(const ValueKey<String>('notification-master-enabled')),
        matching: find.byType(OpenCraySwitch),
      );
      final quietHoursSwitch = find.descendant(
        of: find.byKey(
          const ValueKey<String>('notification-quiet-hours-enabled'),
        ),
        matching: find.byType(OpenCraySwitch),
      );
      await tester.tap(masterSwitch);
      await tester.pump();
      await firstSaveStarted.future;
      await tester.ensureVisible(quietHoursSwitch);
      await tester.pump();
      await tester.tap(quietHoursSwitch);
      await tester.pump();

      expect(facade.notificationSaveCallCount, 1);
      expect(facade.notificationSaveRequests.single.masterEnabled, isFalse);
      expect(facade.notificationSaveRequests.single.quietHoursEnabled, isTrue);

      releaseFirstSave.complete();
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(facade.notificationSaveCallCount, 2);
      expect(facade.notificationSaveRequests.last.masterEnabled, isFalse);
      expect(facade.notificationSaveRequests.last.quietHoursEnabled, isFalse);
      expect(facade.notificationSettings.masterEnabled, isFalse);
      expect(facade.notificationSettings.quietHoursEnabled, isFalse);
    },
  );

  testWidgets('notifications page rolls back the latest failed save', (
    tester,
  ) async {
    final facade = notificationTestFacade(
      onSaveNotificationSettings: (snapshot) async {
        throw Exception('Notification save rejected');
      },
    );

    await tester.pumpWidget(
      MaterialApp(
        home: SettingsFeatureScreen(
          facade: facade,
          initialPage: SettingsPage.notificationsBackground,
          standalone: true,
        ),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));

    final masterSwitch = find.descendant(
      of: find.byKey(const ValueKey<String>('notification-master-enabled')),
      matching: find.byType(OpenCraySwitch),
    );
    expect(tester.widget<OpenCraySwitch>(masterSwitch).value, isTrue);

    await tester.tap(masterSwitch);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));

    expect(tester.widget<OpenCraySwitch>(masterSwitch).value, isTrue);
    expect(facade.notificationSettings.masterEnabled, isTrue);
    expect(find.text('Notification save rejected'), findsOneWidget);
  });

  testWidgets(
    'notifications page keeps a newer edit when an older save fails',
    (tester) async {
      final firstSaveStarted = Completer<void>();
      final releaseFirstSave = Completer<void>();
      var saveInvocation = 0;
      final facade = notificationTestFacade(
        onSaveNotificationSettings: (snapshot) async {
          saveInvocation += 1;
          if (saveInvocation == 1) {
            firstSaveStarted.complete();
            await releaseFirstSave.future;
            throw Exception('Older save failed');
          }
          return snapshot;
        },
      );

      await tester.pumpWidget(
        MaterialApp(
          home: SettingsFeatureScreen(
            facade: facade,
            initialPage: SettingsPage.notificationsBackground,
            standalone: true,
          ),
        ),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      final masterSwitch = find.descendant(
        of: find.byKey(const ValueKey<String>('notification-master-enabled')),
        matching: find.byType(OpenCraySwitch),
      );
      final quietHoursSwitch = find.descendant(
        of: find.byKey(
          const ValueKey<String>('notification-quiet-hours-enabled'),
        ),
        matching: find.byType(OpenCraySwitch),
      );
      await tester.tap(masterSwitch);
      await tester.pump();
      await firstSaveStarted.future;
      await tester.ensureVisible(quietHoursSwitch);
      await tester.tap(quietHoursSwitch);
      await tester.pump();

      releaseFirstSave.complete();
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(facade.notificationSaveCallCount, 2);
      expect(facade.notificationSettings.masterEnabled, isFalse);
      expect(facade.notificationSettings.quietHoursEnabled, isFalse);
      expect(tester.widget<OpenCraySwitch>(masterSwitch).value, isFalse);
    },
  );

  testWidgets(
    'scheduled task summary failure does not block notification controls',
    (tester) async {
      final facade = notificationTestFacade(
        onLoadScheduledTasks: () async {
          throw Exception('Scheduled summary unavailable');
        },
      );

      await tester.pumpWidget(
        MaterialApp(
          home: SettingsFeatureScreen(
            facade: facade,
            initialPage: SettingsPage.notificationsBackground,
            standalone: true,
          ),
        ),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(
        find.byKey(const ValueKey<String>('notification-master-enabled')),
        findsOneWidget,
      );
      expect(
        find.text(
          'The task summary is temporarily unavailable. Management remains available.',
        ),
        findsOneWidget,
      );
      expect(find.text('System controls'), findsOneWidget);
    },
  );

  testWidgets('event alerts page keeps rapid per-event changes', (
    tester,
  ) async {
    final firstSaveStarted = Completer<void>();
    final releaseFirstSave = Completer<void>();
    var saveInvocation = 0;
    final facade = notificationTestFacade(
      onSaveNotificationSettings: (snapshot) async {
        saveInvocation += 1;
        if (saveInvocation == 1) {
          firstSaveStarted.complete();
          await releaseFirstSave.future;
        }
        return snapshot;
      },
    );

    await tester.pumpWidget(
      MaterialApp(
        home: SettingsFeatureScreen(
          facade: facade,
          initialPage: SettingsPage.eventAlerts,
          standalone: true,
        ),
      ),
    );
    await tester.pumpAndSettle();

    final approvalSwitch = find.descendant(
      of: find.byKey(
        const ValueKey<String>('notification-event-approval-requests'),
      ),
      matching: find.byType(OpenCraySwitch),
    );
    final reminderSwitch = find.descendant(
      of: find.byKey(
        const ValueKey<String>('notification-event-approval-reminder'),
      ),
      matching: find.byType(OpenCraySwitch),
    );
    await tester.tap(approvalSwitch);
    await tester.pump();
    await firstSaveStarted.future;
    await tester.ensureVisible(reminderSwitch);
    await tester.pump();
    await tester.tap(reminderSwitch);
    await tester.pump();

    releaseFirstSave.complete();
    await tester.pumpAndSettle();

    expect(facade.notificationSaveCallCount, 2);
    expect(
      facade.notificationSaveRequests.last.approvalRequestsEnabled,
      isFalse,
    );
    expect(
      facade.notificationSaveRequests.last.approvalReminderEnabled,
      isFalse,
    );
    expect(facade.notificationSettings.approvalRequestsEnabled, isFalse);
    expect(facade.notificationSettings.approvalReminderEnabled, isFalse);
  });

  testWidgets(
    'notifications page launches strong background action from system row',
    (tester) async {
      final facade = FakeSettingsFacade(
        llmConfig: const LlmConfigSnapshot(
          localeTag: 'en',
          enabled: false,
          providerId: 'openai',
          selectedProviderOptionId: 'openai',
          protocol: 'openai',
          providerOptions: <LlmProviderOption>[
            LlmProviderOption(
              id: 'openai',
              providerId: 'openai',
              title: 'OpenAI',
              subtitle: 'Official OpenAI-compatible endpoint.',
              defaultBaseUrl: 'https://api.openai.com/v1',
              defaultModel: 'gpt-4o-mini',
              protocol: 'openai',
              apiKey: '',
              isCustom: false,
            ),
          ],
          providerName: 'OpenAI',
          providerNotes: '',
          baseUrl: 'https://api.openai.com/v1',
          apiKey: '',
          model: 'gpt-4o-mini',
          reasoningEffort: 'medium',
          systemPrompt: '',
          helperText: 'Helper text',
        ),
        validationResult: const LlmValidationResult(
          isSuccess: true,
          message: 'Validated.',
        ),
      );

      await tester.pumpWidget(
        MaterialApp(
          home: SettingsFeatureScreen(
            facade: facade,
            initialPage: SettingsPage.notificationsBackground,
            standalone: true,
          ),
        ),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      await tester.ensureVisible(find.text('Foreground service notice'));
      await tester.pump();
      await tester.tap(find.text('Foreground service notice'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(
        facade.strongBackgroundActionRequests,
        contains(StrongBackgroundActionId.openNotificationSettings),
      );
    },
  );

  testWidgets(
    'notifications page shows active profile when notifications and alarms are ready',
    (tester) async {
      final facade =
          FakeSettingsFacade(
              llmConfig: const LlmConfigSnapshot(
                localeTag: 'en',
                enabled: false,
                providerId: 'openai',
                selectedProviderOptionId: 'openai',
                protocol: 'openai',
                providerOptions: <LlmProviderOption>[
                  LlmProviderOption(
                    id: 'openai',
                    providerId: 'openai',
                    title: 'OpenAI',
                    subtitle: 'Official OpenAI-compatible endpoint.',
                    defaultBaseUrl: 'https://api.openai.com/v1',
                    defaultModel: 'gpt-4o-mini',
                    protocol: 'openai',
                    apiKey: '',
                    isCustom: false,
                  ),
                ],
                providerName: 'OpenAI',
                providerNotes: '',
                baseUrl: 'https://api.openai.com/v1',
                apiKey: '',
                model: 'gpt-4o-mini',
                reasoningEffort: 'medium',
                systemPrompt: '',
                helperText: 'Helper text',
              ),
              validationResult: const LlmValidationResult(
                isSuccess: true,
                message: 'Validated.',
              ),
            )
            ..strongBackgroundSnapshot = const StrongBackgroundSnapshot(
              source: 'strong-background',
              available: true,
              tier: StrongBackgroundTier.activeBackground,
              setupComplete: false,
              recommendedActionIds: <StrongBackgroundActionId>[
                StrongBackgroundActionId.requestIgnoreBatteryOptimizations,
              ],
              notifications: StrongBackgroundNotificationsSnapshot(
                permissionRequired: true,
                permissionGranted: true,
                enabled: true,
                configured: true,
              ),
              exactAlarms: StrongBackgroundExactAlarmSnapshot(
                accessRequired: true,
                accessGranted: true,
                configured: true,
              ),
              batteryOptimization: StrongBackgroundBatteryOptimizationSnapshot(
                supported: true,
                exempt: false,
                configured: false,
              ),
              actions: <StrongBackgroundActionSnapshot>[
                StrongBackgroundActionSnapshot(
                  id: StrongBackgroundActionId
                      .requestIgnoreBatteryOptimizations,
                  available: true,
                  recommended: true,
                ),
              ],
            );

      await tester.pumpWidget(
        MaterialApp(
          home: SettingsFeatureScreen(
            facade: facade,
            initialPage: SettingsPage.notificationsBackground,
            standalone: true,
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Background protection status'), findsOneWidget);
      expect(find.text('Current status'), findsOneWidget);
      expect(find.text('Active'), findsOneWidget);
      expect(find.text('AUTO'), findsNothing);
      expect(find.text('ACTIVE'), findsNothing);
      expect(find.text('STRONG'), findsNothing);
      expect(
        find.text(
          'Notifications and exact alarms are ready, but the device is not yet in the strongest local background tier.',
        ),
        findsOneWidget,
      );
      expect(
        find.text(
          'Event alerts and scheduled wakes are ready. Addressing battery restrictions enables stronger local background protection.',
        ),
        findsOneWidget,
      );
    },
  );
}

FakeSettingsFacade notificationTestFacade({
  Future<NotificationSettingsSnapshot> Function(
    NotificationSettingsSnapshot snapshot,
  )?
  onSaveNotificationSettings,
  Future<ScheduledTasksSnapshot> Function()? onLoadScheduledTasks,
}) => FakeSettingsFacade(
  llmConfig: const LlmConfigSnapshot(
    localeTag: 'en',
    enabled: false,
    providerId: 'openai',
    selectedProviderOptionId: 'openai',
    protocol: 'openai',
    providerOptions: <LlmProviderOption>[],
    providerName: 'OpenAI',
    providerNotes: '',
    baseUrl: 'https://api.openai.com/v1',
    apiKey: '',
    model: 'gpt-4o-mini',
    reasoningEffort: 'medium',
    systemPrompt: '',
    helperText: 'Helper text',
  ),
  validationResult: const LlmValidationResult(
    isSuccess: true,
    message: 'Validated.',
  ),
  onSaveNotificationSettings: onSaveNotificationSettings,
  onLoadScheduledTasks: onLoadScheduledTasks,
);
