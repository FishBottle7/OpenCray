# Consumer shell migration guide

## Scope statement

Use this guide when reviewing release copy, QA steps, screenshots, support notes, and onboarding text for the consumer app. V1 now ships as a shell-based app, so release-facing docs must describe the shell contract that users actually see.

## Launcher contract

- `com.opencray.app.AppShellActivity` is the only `MAIN` + `LAUNCHER` activity.
- A launcher-style app start must resume `AppShellActivity`.
- The visible top-level tabs after launch are `Chat`, `Skills`, `Files`, and `Settings`.
- Evidence: `.sisyphus/evidence/task-13-shell-launcher.txt`

## Wrapper compatibility policy

These exported activity names still exist, but only as compatibility wrappers:

- `SkillsManagementActivity` routes into the shell Skills tab.
- `MainInteractionActivity` routes into the shell Chat tab and preserves `EXTRA_SCENARIO`.
- `WorkspaceSettingsActivity` routes into the shell Files tab and preserves `EXTRA_SCENARIO`.
- `SafetyAndLimitsActivity` routes into `Settings > Safety & Limits`.

Release and QA rules:

- Treat `AppShellActivity` as the real consumer host.
- Describe the shell destination, not the wrapper class name, in release notes and screenshots.
- If a wrapper name must appear for migration or support reasons, pair it with the shell destination it opens.
- Do not describe wrappers as separate app homes, separate tabs, or parallel standalone screens.

Evidence: `.sisyphus/evidence/task-13-wrapper-routing.txt`, `.sisyphus/evidence/task-13-shell-launcher.txt`

## Consumer tab map

The shell tab order is fixed:

1. `Chat`
2. `Skills`
3. `Files`
4. `Settings`

Operational meaning:

- `Chat` owns the main interaction surface, including approval and denial scenarios.
- `Skills` owns the shell-facing skills management surface.
- `Files` owns workspace access state, grant recovery, and outside-root guidance.
- `Settings` owns a home hub plus its subpages.

Evidence: `.sisyphus/evidence/task-13-shell-launcher.txt`, `.sisyphus/evidence/task-4-chat-tab-approval.txt`, `.sisyphus/evidence/task-5-skills-tab-happy.txt`, `.sisyphus/evidence/task-6-files-workbench-happy.txt`, `.sisyphus/evidence/task-7-settings-home.txt`

## Settings home and subpage structure

`Settings` is not a long merged page. It uses a home-plus-subpage structure inside the shell host.

Settings HOME card order:

1. `MCP`
2. `Privacy & Telemetry`
3. `Safety & Limits`
4. `About & Version`
5. `Personalization`

Navigation rules:

- Entering a card opens that subpage inside the Settings tab.
- The visible `← Settings` back affordance returns to Settings HOME before leaving the tab.
- Stored shell state keeps the current settings subpage tied to the shell destination model.

Subpage summary:

- `MCP`: HOME keeps the summary and master toggle, while per-server trust, auth, readiness, and manual enable actions stay inside the MCP subpage.
- `Privacy & Telemetry`: shows the real toggle surface, keeps defaults visible, persists changes, and keeps the local-retention disclosure visible even when telemetry is off.
- `Safety & Limits`: hosts the release-critical warnings inside Settings, not in a separate standalone activity flow.
- `About & Version`: shows the consumer-facing product summary, build details, and release guardrail summary.
- `Personalization`: shows preset profiles, custom editing, and guarded reset controls.

Evidence: `.sisyphus/evidence/task-7-settings-home.txt`, `.sisyphus/evidence/task-7-settings-back.txt`, `.sisyphus/evidence/task-8-mcp-settings-happy.txt`, `.sisyphus/evidence/task-8-mcp-settings-blocked.txt`, `.sisyphus/evidence/task-9-privacy-settings-happy.txt`, `.sisyphus/evidence/task-9-privacy-settings-disclosure.txt`, `.sisyphus/evidence/task-10-safety-subpage-happy.txt`, `.sisyphus/evidence/task-11-about-screen-happy.txt`, `.sisyphus/evidence/task-12-personalization-happy.txt`

## Bilingual behavior in V1

V1 bilingual behavior is driven by system locale resources.

- Audited resource pairs exist in `values` and `values-zh-rCN`.
- Release-facing text should describe the app as English and Simplified Chinese, selected by device locale.
- Do not promise an in-app language switch in V1.
- The current shell Settings contract defines exactly five cards, none for language or locale switching.

Evidence: `.sisyphus/evidence/task-2-bilingual-resources.txt`, `.sisyphus/evidence/task-2-reset-token-strings.txt`, `.sisyphus/evidence/task-7-settings-home.txt`

## Files tab scope limits

The `Files` tab is a workspace access surface, not a general-purpose file browser.

- The tab stays centered on SAF grant state and the stored workspace root.
- Audited states include `GRANT ACTIVE`, `RECOVERY NEEDED`, `OUTSIDE ROOT`, and the no-grant path.
- Recovery stays in the Files tab through actions such as `Pick workspace`, `Clear grant`, and `Re-authorize workspace`.
- Release docs must not imply silent out-of-root access, hidden recovery, or a broader file-management promise than the audited workspace flow.

Evidence: `.sisyphus/evidence/task-6-files-workbench-happy.txt`, `.sisyphus/evidence/task-6-files-workbench-deny.txt`, `.sisyphus/evidence/task-15-saf-happy.txt`, `.sisyphus/evidence/task-15-saf-revoked.txt`

## Typed reset phrases

The Personalization danger-zone resets use exact typed phrases.

- `Reset memory` requires `RESET MEMORY`.
- `Reset soul` requires `RESET SOUL`.
- The typed tokens stay the same in both English and Simplified Chinese resources.
- Wrong text keeps the action disabled.
- Release copy must not translate the tokens or imply fuzzy matching.

Scope notes:

- `Reset memory` clears app-level memory and history stores only.
- `Reset soul` clears personality and soul profile data only.
- Neither reset clears workspace grants, MCP state, telemetry or privacy preferences, or About and Version metadata.

Evidence: `.sisyphus/evidence/task-12-personalization-happy.txt`, `.sisyphus/evidence/task-12-personalization-reset-guard.txt`, `.sisyphus/evidence/task-2-reset-token-strings.txt`

## Learnings

- Shell migration docs are easier to audit when they map old entry-point names straight to the shell destination the user actually lands on.

## Issues

- The evidence for this shell contract is spread across several earlier tasks, so release reviewers should keep this guide next to the checklist during final signoff.
