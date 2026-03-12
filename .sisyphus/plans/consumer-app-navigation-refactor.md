# OpenCray Consumer App Shell and Navigation Refactor

## TL;DR
> **Summary**: Refactor the current multi-activity prototype into a consumer-style Android app shell with a bottom navigation bar, Chat as the default home, and four primary surfaces: Chat, Skills, Files, and Settings. Preserve the current minimalist visual language while consolidating navigation, clarifying screen ownership, and shipping bilingual English/Simplified Chinese user-facing copy.
> **Deliverables**:
> - Single `AppShellActivity` launcher with bottom navigation
> - Four-tab information architecture: Chat / Skills / Files / Settings
> - Settings home plus subpages for MCP, Privacy & Telemetry, Safety & Limits, About & Version, and Personalization
> - Bilingual shell and settings copy (`values/` + `values-zh-rCN/`)
> - Updated instrumentation/unit/manual QA evidence for shell navigation and disclosures
> **Effort**: XL
> **Parallel**: YES - 4 waves
> **Critical Path**: 1 → 2 → 3 → 7 → 12 → 13 → 14 → 15

## Context
### Original Request
- Make the product feel like a complete consumer app rather than a prototype.
- Add a global navigation bar with four main pages.
- Main pages: Chat, Skills management, Files agent can access, Settings.
- Keep the current minimalist design language.
- Use Socratic questioning to refine the plan first.

### Interview Summary
- Global navigation is a bottom navigation bar.
- Default home page is Chat.
- Main tabs are fixed to `Chat / Skills / Files / Settings`.
- MCP management moves under Settings instead of using main-tab real estate.
- Files is a lightweight granted-workspace workbench, not a general file manager.
- Settings is a settings home with subpages.
- Settings home must expose cards for MCP, Privacy & Telemetry, Safety & Limits, About & Version, and Personalization.
- MCP appears on settings home as summary + quick toggles.
- Safety & Limits is a settings subpage.
- Personalization is a settings subpage using presets + custom editing.
- Memory and soul reset live in a danger zone at the bottom of Personalization.
- Reset requires a typed confirmation phrase.
- UI ships bilingual English + Simplified Chinese.
- Minimalist visual style is preserved; the refactor focuses on architecture, navigation, and affordances rather than visual redesign.

### Metis Review (gaps addressed)
- Lock shell architecture to a single `AppShellActivity` instead of continuing the current multi-activity consumer navigation pattern.
- Explicitly bound Files v1 to a lightweight workbench scope to avoid file-manager creep.
- Explicitly bounded Settings to a home + subpage stack to avoid “all settings on one long page” sprawl.
- Added bilingual-string extraction as first-class work, not incidental cleanup.
- Added compatibility-wrapper work so current standalone activities can remain valid entry points during migration.
- Added explicit acceptance for back behavior, tab state retention, and destructive reset confirmation.

## Work Objectives
### Core Objective
Deliver a decision-complete plan to convert the existing prototype into a coherent consumer-style Android application shell without changing the minimalist visual language, while preserving the existing functional surfaces and making them reachable, stateful, and release-ready.

### Deliverables
- New launcher shell with bottom navigation and tab-state retention
- Chat tab host using the existing `ChatScreen`
- Skills tab host using the existing `SkillsScreen`
- Files tab host using the existing SAF/filesystem surfaces
- Settings home with five entry cards and summary states
- Settings subpages for MCP, Privacy & Telemetry, Safety & Limits, About & Version, and Personalization
- Typed-confirmation danger zone for memory/soul reset
- Bilingual resource bundles for all user-facing shell/settings strings
- Updated compatibility routing for legacy activities
- Updated QA and release-checklist coverage for the new shell

### Definition of Done (verifiable conditions with commands)
- `./gradlew :app:compileDebugKotlin :ui:compileDebugKotlin` exits 0.
- `./gradlew :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest --tests "*DeveloperModeDisclosureRequired*"` exits 0.
- `./gradlew :app:assembleDebug` exits 0.
- `adb shell am start -W -n org.opencray.app/com.opencray.app.AppShellActivity` launches the new shell and bottom navigation is visible.
- `adb shell am instrument -w -e class com.opencray.app.BottomNavShellTest org.opencray.app.test/androidx.test.runner.AndroidJUnitRunner` passes.
- `adb shell am instrument -w -e class com.opencray.app.SettingsNavigationTest org.opencray.app.test/androidx.test.runner.AndroidJUnitRunner` passes.
- `python -m pytest -q python_tests/test_runtime_policy_e2e.py` still passes after shell migration.

### Must Have
- Single launcher shell with bottom navigation
- Chat default home tab
- Main tabs exactly `Chat / Skills / Files / Settings`
- Files limited to granted workspace roots and lightweight workbench behavior
- MCP located under Settings, not a primary tab
- Settings home + subpage architecture
- Personalization page with presets + custom personality editing
- Typed confirmation before memory/soul reset
- Bilingual English + Simplified Chinese resources for shell/settings copy
- Existing functionality preserved and reachable via the new shell or compatibility routes

### Must NOT Have (guardrails, AI slop patterns, scope boundaries)
- No Compose migration in this refactor
- No full device file manager behavior
- No iOS shell work
- No cloud sync/collaboration scope
- No multi-agent parallel orchestration scope expansion
- No public marketplace/review system work
- No in-app language switch in v1 (system locale only)
- No redesign away from current minimalist style

## Verification Strategy
> ZERO HUMAN INTERVENTION — all verification is agent-executed.
- Test decision: tests-after + existing Android unit/instrumentation plus Python regression suite
- QA policy: Every task has agent-executed scenarios
- Evidence: `.sisyphus/evidence/task-{N}-{slug}.{ext}`

## Execution Strategy
### Parallel Execution Waves
> Target: 5-8 tasks per wave. <3 per wave (except final) = under-splitting.
> Extract shared dependencies as Wave-1 tasks for max parallelism.

Wave 1: shell foundation and language scaffolding
- Task 1 — Shell navigation contract and state store
- Task 2 — Bilingual string/resource extraction

Wave 2: shell host and settings hub
- Task 3 — `AppShellActivity` bottom-nav host
- Task 7 — Settings home and subpage router

Wave 3: tab and subpage integration
- Task 4 — Chat tab integration
- Task 5 — Skills tab integration
- Task 6 — Files tab workbench integration
- Task 8 — MCP settings subpage
- Task 9 — Privacy & Telemetry subpage
- Task 10 — Safety & Limits subpage
- Task 11 — About & Version subpage
- Task 12 — Personalization subpage

Wave 4: compatibility, QA, release updates
- Task 13 — Legacy activity wrappers and manifest routing
- Task 14 — Navigation/UI instrumentation and replay evidence
- Task 15 — Release checklist and migration-note refresh

### Dependency Matrix (full, all tasks)
| Task | Depends On |
|---|---|
| 1 | Existing app/ui activities only |
| 2 | Existing shell/settings/help text only |
| 3 | 1, 2 |
| 4 | 3 |
| 5 | 3 |
| 6 | 3 |
| 7 | 1, 2, 3 |
| 8 | 7 |
| 9 | 7 |
| 10 | 7 |
| 11 | 7 |
| 12 | 7 |
| 13 | 3, 4, 5, 6, 7, 8, 9, 10, 11, 12 |
| 14 | 4, 5, 6, 7, 8, 9, 10, 11, 12, 13 |
| 15 | 8, 9, 10, 11, 12, 14 |

### Agent Dispatch Summary (wave → task count → categories)
- Wave 1 → 2 tasks → `deep`, `unspecified-high`
- Wave 2 → 2 tasks → `deep`, `visual-engineering`
- Wave 3 → 8 tasks → `visual-engineering`, `unspecified-high`, `writing`
- Wave 4 → 3 tasks → `unspecified-high`, `deep`, `writing`

## TODOs
> Implementation + Test = ONE task. Never separate.
> EVERY task MUST have: Agent Profile + Parallelization + QA Scenarios.

- [x] 1. Define app shell navigation contract and persistent shell state

  **What to do**: Create the shell-domain layer in `app/src/main/kotlin/com/opencray/app/shell/` with exactly these models: `AppShellTab` (`CHAT`, `SKILLS`, `FILES`, `SETTINGS`), `SettingsSubpage` (`HOME`, `MCP`, `PRIVACY`, `SAFETY`, `ABOUT`, `PERSONALIZATION`), `AppShellDestination`, and `AppShellStateStore`. Persist last selected top-level tab plus last selected settings subpage with a lightweight app-local store (`SharedPreferences` wrapper) so tab routing survives process recreation. Define wrapper launch extras `EXTRA_START_TAB`, `EXTRA_START_SETTINGS_PAGE`, and optional scenario pass-through extras for Chat/Files/Safety compatibility wrappers.
  **Must NOT do**: Do not build UI in this task. Do not introduce Fragments, Compose, Navigation Component, or any new top-level tabs. Do not add an in-app language switch.

  **Recommended Agent Profile**:
  - Category: `deep` — Reason: this is the architectural contract that every later task depends on.
  - Skills: `[]` — no extra skill is required beyond disciplined Android app-state design.
  - Omitted: `frontend-ui-ux` — no layout work yet; `git-master` — no git work.

  **Parallelization**: Can Parallel: YES | Wave 1 | Blocks: 3, 7, 13 | Blocked By: none

  **References**:
  - Pattern: `app/src/main/kotlin/com/opencray/app/SkillsManagementActivity.kt:17-139` — current host-activity pattern with programmatic root container and embedded custom view.
  - Pattern: `app/src/main/kotlin/com/opencray/app/MainInteractionActivity.kt:19-107` — current seeded-host state pattern and scenario-extra handling.
  - Pattern: `app/src/main/kotlin/com/opencray/app/WorkspaceSettingsActivity.kt:33-106` — current scenario-driven host state selection for Files/SAF-related surfaces.
  - Pattern: `app/src/main/AndroidManifest.xml:5-30` — current multi-activity exported entry-point layout that the shell must absorb.
  - API/Type: `app/build.gradle.kts:6-17` — app namespace, application id, and instrumentation runner constraints.

  **Acceptance Criteria**:
  - [ ] `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests "*AppShellStateStoreTest*"` exits 0.
  - [ ] The new destination model exposes exactly four top-level tabs and exactly five settings subpages plus `HOME`.
  - [ ] `AppShellStateStore` persists and restores the last tab + settings subpage without storing any UI text.

  **QA Scenarios** (MANDATORY — task incomplete without these):
  ```
  Scenario: Shell state persists last destination
    Tool: Bash
    Steps: Run `./gradlew :app:testDebugUnitTest --tests "*AppShellStateStoreTest*"` where the test saves `SETTINGS -> PERSONALIZATION` and reloads the store.
    Expected: Test passes and restored state equals `SETTINGS/PERSONALIZATION`.
    Evidence: .sisyphus/evidence/task-1-shell-state.txt

  Scenario: Invalid wrapper destination falls back safely
    Tool: Bash
    Steps: Run `./gradlew :app:testDebugUnitTest --tests "*AppShellInvalidDestinationTest*"` with an unsupported tab/subpage value.
    Expected: Test passes and store/controller resolves to `CHAT` or `SETTINGS/HOME` fallback without exception.
    Evidence: .sisyphus/evidence/task-1-shell-invalid-destination.txt
  ```

  **Commit**: NO | Message: `feat(app): define shell navigation contract` | Files: `app/src/main/kotlin/com/opencray/app/shell/**`, `app/src/test/**`

- [x] 2. Externalize shell and settings/help copy into bilingual Android resources

  **What to do**: Introduce resource-backed copy for all new shell/navigation/settings/help labels in both `values/strings.xml` and `values-zh-rCN/strings.xml`. Put shell-wide strings in `app/src/main/res/` and reusable component strings in `ui/src/main/res/`. Replace hardcoded text in the shell/settings/help/files chrome that will participate in the refactor. Use system locale only for v1; do not add an in-app language switch. Standardize destructive confirmation phrases as fixed tokens: `RESET MEMORY` and `RESET SOUL`, with bilingual explanatory copy around them.
  **Must NOT do**: Do not try to localize every historical seeded demo string in this task. Do not add language-picker UI. Do not rename the product away from `OpenCray`.

  **Recommended Agent Profile**:
  - Category: `unspecified-high` — Reason: this is cross-module resource extraction with downstream test impact.
  - Skills: `[]` — Android resource work is sufficient without extra skills.
  - Omitted: `frontend-ui-ux` — copy extraction is structural, not layout design.

  **Parallelization**: Can Parallel: YES | Wave 1 | Blocks: 3, 7, 8, 9, 10, 11, 12, 15 | Blocked By: none

  **References**:
  - Pattern: `ui/src/main/kotlin/com/opencray/ui/skills/SkillsScreen.kt:134-225` — current hardcoded shell-ish user copy that must move into resources.
  - Pattern: `ui/src/main/kotlin/com/opencray/ui/files/WorkspacePickerScreen.kt:19-22, 161-170, 339-369` — files/status strings that should become localized resource-backed labels.
  - Pattern: `ui/src/main/kotlin/com/opencray/ui/help/SafetyAndLimitsScreen.kt:14-30, 346-399` — release-critical disclosure copy that must stay visible in both languages.
  - Pattern: `ui/src/main/kotlin/com/opencray/ui/settings/TelemetryToggles.kt:14-22, 42-67` — toggle labels, defaults, and local-retention disclosures.
  - Pattern: `docs/release-checklist.md:39-62` — disclosure language that must remain semantically consistent after extraction.

  **Acceptance Criteria**:
  - [ ] `./gradlew :app:mergeDebugResources :ui:mergeDebugResources` exits 0.
  - [ ] `values/strings.xml` and `values-zh-rCN/strings.xml` exist for every new shell/settings/help resource set.
  - [ ] No new shell/settings/help files introduced by this plan contain hardcoded English/Chinese display labels that should be resource-backed.

  **QA Scenarios**:
  ```
  Scenario: English and Simplified Chinese resources both compile
    Tool: Bash
    Steps: Run `./gradlew :app:mergeDebugResources :ui:mergeDebugResources` and inspect that both default and `values-zh-rCN` resource folders are packaged.
    Expected: Resource merge succeeds with no duplicate-key or missing-reference errors.
    Evidence: .sisyphus/evidence/task-2-bilingual-resources.txt

  Scenario: Required reset tokens remain deterministic
    Tool: Bash
    Steps: Search the resource files for `RESET MEMORY` and `RESET SOUL`, then verify the surrounding explanatory strings exist in both locales.
    Expected: Tokens remain exact and locale-stable; surrounding labels exist in both English and Simplified Chinese.
    Evidence: .sisyphus/evidence/task-2-reset-token-strings.txt
  ```

  **Commit**: NO | Message: `chore(ui): add bilingual shell resources` | Files: `app/src/main/res/**`, `ui/src/main/res/**`

- [x] 3. Build `AppShellActivity` as the new launcher with bottom navigation and tab state retention

  **What to do**: Create `AppShellActivity` in the app module as the sole launcher. It must own a root layout with a content host (`FrameLayout`) plus a bottom navigation bar showing exactly `Chat / Skills / Files / Settings`. Default start tab is Chat. Keep one instantiated host view per top-level tab and preserve each tab’s scroll/state while switching. The Settings tab must open Settings home by default. Move the launcher intent-filter from `SkillsManagementActivity` to `AppShellActivity`, but do not delete legacy activities yet.
  **Must NOT do**: Do not use Fragments or Navigation Component. Do not embed nested `ScrollView`s inside the shell itself. Do not add extra tabs or FAB-driven secondary navigation.

  **Recommended Agent Profile**:
  - Category: `visual-engineering` — Reason: this is host-shell composition plus high-importance navigation UI.
  - Skills: [`frontend-ui-ux`] — needed to keep the shell visually minimal while making tabs consumer-grade.
  - Omitted: `ui-ux-pro-max` — the design language is already fixed; `find-skills` — no skills-domain discovery here.

  **Parallelization**: Can Parallel: NO | Wave 2 | Blocks: 4, 5, 6, 13 | Blocked By: 1, 2

  **References**:
  - Pattern: `app/src/main/kotlin/com/opencray/app/SkillsManagementActivity.kt:29-44` — current simple root container embedding a hosted custom view.
  - Pattern: `app/src/main/kotlin/com/opencray/app/SafetyAndLimitsActivity.kt:33-52` — programmatic vertical shell composition with scroll-safe hosted views.
  - Pattern: `app/src/main/AndroidManifest.xml:5-15` — current launcher wiring to replace with `AppShellActivity`.
  - API/Type: task 1 shell contract outputs — `AppShellTab`, `AppShellDestination`, `AppShellStateStore`.

  **Acceptance Criteria**:
  - [ ] `./gradlew :app:compileDebugKotlin :app:assembleDebug` exits 0.
  - [ ] `adb shell am start -W -n org.opencray.app/com.opencray.app.AppShellActivity` launches a screen whose visible bottom-nav labels are exactly `Chat`, `Skills`, `Files`, `Settings`.
  - [ ] Switching tabs does not recreate the shell activity or lose the selected tab state across a simple relaunch.

  **QA Scenarios**:
  ```
  Scenario: Launcher opens into Chat with four-tab bottom nav
    Tool: Bash
    Steps: Install debug APK, launch `AppShellActivity`, dump UI hierarchy, and verify visible labels `Chat`, `Skills`, `Files`, `Settings` with Chat selected by default.
    Expected: Chat is the resumed top-level surface and all four tabs are visible.
    Evidence: .sisyphus/evidence/task-3-shell-launcher.txt

  Scenario: Unknown start-extra falls back to Chat safely
    Tool: Bash
    Steps: Launch `AppShellActivity` with an invalid `EXTRA_START_TAB` extra via `adb shell am start` and dump the hierarchy.
    Expected: The shell opens without crash and falls back to the Chat tab.
    Evidence: .sisyphus/evidence/task-3-shell-invalid-extra.txt
  ```

  **Commit**: NO | Message: `feat(app): add bottom-nav shell launcher` | Files: `app/src/main/**`, `app/src/androidTest/**`

- [x] 4. Integrate the existing chat experience into the Chat tab

  **What to do**: Extract the seeded host behavior from `MainInteractionActivity` into a reusable shell-owned chat tab host/controller so the existing `ChatScreen` runs inside `AppShellActivity`. Preserve queue visibility, mode switching, approval/deny flows, timeline visibility, and reset confirmation behavior. The tab must restore its last visible state when users leave and return. `MainInteractionActivity` remains as a later compatibility wrapper only; it must no longer be the primary consumer entry point.
  **Must NOT do**: Do not redesign `ChatScreen`. Do not remove existing seeded approval/policy-denial scenarios. Do not add new agent behavior beyond existing flows.

  **Recommended Agent Profile**:
  - Category: `visual-engineering` — Reason: integration work touches existing hosted UI plus state ownership.
  - Skills: [`frontend-ui-ux`] — needed to preserve the current minimal interaction surface while moving it under the shell.
  - Omitted: `find-skills` — no skills-domain change; `ui-ux-pro-max` — no visual redesign.

  **Parallelization**: Can Parallel: YES | Wave 3 | Blocks: 14 | Blocked By: 3

  **References**:
  - Pattern: `app/src/main/kotlin/com/opencray/app/MainInteractionActivity.kt:19-138` — current seeded scenario host logic and user-facing copy.
  - Pattern: `ui/src/main/kotlin/com/opencray/ui/chat/ChatScreen.kt:138-220` — existing reusable View-based Chat surface and listener contract.
  - Pattern: `ui/src/main/kotlin/com/opencray/ui/timeline/ActionTimeline.kt` — timeline component already used by `ChatScreen`.
  - Test: `app/src/androidTest/kotlin/com/opencray/app/MainFlowUiTest.kt:16-183` — current deterministic approval/denial assertions to preserve after shell migration.

  **Acceptance Criteria**:
  - [ ] `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin` exits 0.
  - [ ] `adb shell am instrument -w -e class com.opencray.app.ChatTabShellTest org.opencray.app.test/androidx.test.runner.AndroidJUnitRunner` passes.
  - [ ] Chat tab preserves the existing approval-required, approved, denied, and policy-denied seeded behaviors inside the shell.

  **QA Scenarios**:
  ```
  Scenario: Approval flow still succeeds inside Chat tab
    Tool: Bash
    Steps: Launch shell on Chat, trigger the seeded approval scenario, tap `Approve write`, and verify the visible transition to `Write approved`.
    Expected: The same approval-state progression previously covered by `MainFlowUiTest` is visible inside the shell tab.
    Evidence: .sisyphus/evidence/task-4-chat-tab-approval.txt

  Scenario: Denied-policy scenario remains visible after wrapper routing
    Tool: Bash
    Steps: Launch Chat via the compatibility wrapper scenario extra or shell destination extra for the denied-policy state and dump the timeline.
    Expected: `Blocked by policy`, `POLICY DENY`, and the denial reason remain visible in the Chat tab.
    Evidence: .sisyphus/evidence/task-4-chat-tab-deny.txt
  ```

  **Commit**: NO | Message: `feat(app): move chat flow into shell tab` | Files: `app/src/main/**`, `app/src/androidTest/**`

- [x] 5. Integrate the existing skills experience into the Skills tab and remove temporary launcher-era chrome

  **What to do**: Embed `SkillsScreen` into the shell’s Skills tab and remove the temporary top navigation workaround that was added to `SkillsManagementActivity`. The tab must keep the improved affordances already added: quick actions above the fold, visible import/export feedback, and card-wide selection. The redundant `Editing` affordance must remain absent, and the tab must preserve selected draft/skill state when users leave and return.
  **Must NOT do**: Do not regress Skills CRUD/install/import/export behavior. Do not bury key actions below the fold again. Do not keep shell-level navigation duplicated inside the Skills tab.

  **Recommended Agent Profile**:
  - Category: `visual-engineering` — Reason: this is user-facing interaction polish plus shell integration.
  - Skills: [`frontend-ui-ux`] — needed for interaction affordances and information hierarchy.
  - Omitted: `find-skills` — no backend skills-engine change; `ui-ux-pro-max` — style remains intentionally minimal.

  **Parallelization**: Can Parallel: YES | Wave 3 | Blocks: 14 | Blocked By: 3

  **References**:
  - Pattern: `ui/src/main/kotlin/com/opencray/ui/skills/SkillsScreen.kt:134-225` — current quick-actions, list, form, and lifecycle sections.
  - Pattern: `app/src/main/kotlin/com/opencray/app/SkillsManagementActivity.kt:17-89` — temporary launcher-era top navigation that should not survive inside the shell tab.
  - Pattern: `app/src/androidTest/kotlin/com/opencray/app/SkillsManagementUiTest.kt:30-90` — create/edit/export and inline-validation flows that must still pass.

  **Acceptance Criteria**:
  - [ ] `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin` exits 0.
  - [ ] `adb shell am instrument -w -e class com.opencray.app.SkillsTabShellTest org.opencray.app.test/androidx.test.runner.AndroidJUnitRunner` passes.
  - [ ] The Skills tab shows no duplicate shell navigation chrome and still exposes create/save/import/export above the fold.

  **QA Scenarios**:
  ```
  Scenario: Skills tab keeps quick actions and card-wide selection
    Tool: Bash
    Steps: Open the shell, switch to Skills, verify `Create new draft`, `Save draft`, `Import package`, and `Export package` are visible above the fold, then tap a non-selected skill card.
    Expected: The card becomes selected, `Selected` is visible, and no redundant `Editing` button is shown.
    Evidence: .sisyphus/evidence/task-5-skills-tab-happy.txt

  Scenario: Invalid skill name still shows inline validation inside shell tab
    Tool: Bash
    Steps: In Skills, create a draft, enter `Invalid Name`, tap `Save draft`, and dump the visible text.
    Expected: The inline validation error and status message remain visible in the tab-hosted screen.
    Evidence: .sisyphus/evidence/task-5-skills-tab-invalid.txt
  ```

  **Commit**: NO | Message: `feat(ui): move skills experience into shell tab` | Files: `app/src/main/**`, `ui/src/main/**`, `app/src/androidTest/**`

- [x] 6. Build the Files tab as a lightweight granted-workspace workbench

  **What to do**: Turn the current SAF-only `WorkspacePickerScreen` foundation into a Files tab workbench scoped to granted roots. The tab must show granted-root status, root selector/breadcrumb, in-root directory list, search, text preview for UTF-8 files up to 128 KB, metadata-only fallback for larger/binary files, and exactly these user operations: create file, create folder, rename, delete, refresh, and copy relative path. Reuse `SafWorkspaceBridge` and `FileOpsService` so all operations stay rooted under granted paths.
  **Must NOT do**: Do not build a general device file manager. Do not support arbitrary cross-root move/copy, binary editing, or unmanaged external roots. Do not promise access outside granted workspace roots.

  **Recommended Agent Profile**:
  - Category: `unspecified-high` — Reason: this is cross-module integration of filesystem policy, SAF state, and new UI behavior.
  - Skills: [`frontend-ui-ux`] — needed for workbench ergonomics without overbuilding a file manager.
  - Omitted: `find-skills` — no skills-engine work; `playwright` — not a browser surface.

  **Parallelization**: Can Parallel: YES | Wave 3 | Blocks: 14 | Blocked By: 3

  **References**:
  - Pattern: `ui/src/main/kotlin/com/opencray/ui/files/WorkspacePickerScreen.kt:59-205` — current granted/revoked/outside-root state rendering.
  - Pattern: `ui/src/main/kotlin/com/opencray/ui/files/WorkspacePickerScreen.kt:223-329` — status, grant summary, and action-state rendering patterns.
  - API/Type: `filesystem/src/main/kotlin/com/opencray/filesystem/FileOpsService.kt` — existing bounded file operation service.
  - API/Type: `filesystem/src/main/kotlin/com/opencray/filesystem/SafWorkspaceBridge.kt` — granted-root containment and revocation state.
  - Test: `app/src/androidTest/kotlin/com/opencray/app/SafIntegrationTest.kt:29-58` — existing SAF happy/recovery state expectations to preserve.

  **Acceptance Criteria**:
  - [ ] `./gradlew :filesystem:testDebugUnitTest :app:compileDebugAndroidTestKotlin` exits 0.
  - [ ] `adb shell am instrument -w -e class com.opencray.app.FilesWorkbenchTest org.opencray.app.test/androidx.test.runner.AndroidJUnitRunner` passes.
  - [ ] Files tab never shows or mutates anything outside granted roots, and revoked grants show recovery guidance instead of silent failure.

  **QA Scenarios**:
  ```
  Scenario: Active grant allows lightweight workbench operations inside the granted root
    Tool: Bash
    Steps: Launch Files with the `active_grant` scenario, browse into `projects/demo`, create a draft file/folder, rename one entry, and verify the list/preview update.
    Expected: All operations succeed inside the granted root and the visible status remains `GRANT ACTIVE`.
    Evidence: .sisyphus/evidence/task-6-files-workbench-happy.txt

  Scenario: Outside-root or revoked access stays blocked and recoverable
    Tool: Bash
    Steps: Launch Files with `outside_root_denial` and `revoked_grant` scenarios, then attempt an in-tab operation.
    Expected: Outside-root shows denial, revoked shows `Re-authorize workspace`, and no file mutation happens.
    Evidence: .sisyphus/evidence/task-6-files-workbench-deny.txt
  ```

  **Commit**: NO | Message: `feat(files): add granted-workspace workbench tab` | Files: `app/src/main/**`, `ui/src/main/**`, `filesystem/src/main/**`, `app/src/androidTest/**`

- [x] 7. Build Settings home and its internal subpage router

  **What to do**: Create a Settings home surface inside the shell rather than another standalone activity. The home must expose exactly five cards in this order: `MCP`, `Privacy & Telemetry`, `Safety & Limits`, `About & Version`, `Personalization`. Each card needs a summary line. MCP must expose a summary plus a single home-level master toggle (`Enable MCP integrations`). Settings navigation must use an internal stack (`HOME` → subpage) with a top app bar/back action that returns to Settings home, not out of the app.
  **Must NOT do**: Do not put Safety directly on Settings home as full content. Do not collapse Settings back into one giant long page. Do not introduce additional top-level tabs.

  **Recommended Agent Profile**:
  - Category: `visual-engineering` — Reason: settings IA and card navigation are strongly UX-shaped.
  - Skills: [`frontend-ui-ux`] — needed to make Settings feel consumer-grade while staying minimal.
  - Omitted: `ui-ux-pro-max` — visual style is already chosen.

  **Parallelization**: Can Parallel: NO | Wave 2 | Blocks: 8, 9, 10, 11, 12, 14, 15 | Blocked By: 1, 2, 3

  **References**:
  - Pattern: `app/src/main/kotlin/com/opencray/app/SafetyAndLimitsActivity.kt:23-52` — current example of composing multiple settings/help surfaces in a simple scroll container.
  - Pattern: `ui/src/main/kotlin/com/opencray/ui/help/SafetyAndLimitsScreen.kt:118-179` — section-card composition pattern to reuse for settings cards and subpage hosts.
  - Pattern: `ui/src/main/kotlin/com/opencray/ui/settings/TelemetryToggles.kt:106-167` — reusable settings-card and disclosure structure.

  **Acceptance Criteria**:
  - [ ] `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin` exits 0.
  - [ ] `adb shell am instrument -w -e class com.opencray.app.SettingsNavigationTest org.opencray.app.test/androidx.test.runner.AndroidJUnitRunner` passes.
  - [ ] Settings home shows exactly the five required cards in the required order and back from a subpage returns to Settings home.

  **QA Scenarios**:
  ```
  Scenario: Settings home shows the required five cards and MCP master toggle
    Tool: Bash
    Steps: Open shell → Settings and dump the UI hierarchy.
    Expected: Cards appear in order `MCP`, `Privacy & Telemetry`, `Safety & Limits`, `About & Version`, `Personalization`, and the MCP card exposes a visible master toggle.
    Evidence: .sisyphus/evidence/task-7-settings-home.txt

  Scenario: Subpage back behavior stays inside Settings
    Tool: Bash
    Steps: Open Settings → Safety & Limits → press back.
    Expected: User returns to Settings home, not to the previously selected top-level tab or app exit.
    Evidence: .sisyphus/evidence/task-7-settings-back.txt
  ```

  **Commit**: NO | Message: `feat(ui): add settings home and subpage router` | Files: `app/src/main/**`, `ui/src/main/**`, `app/src/androidTest/**`

- [x] 8. Implement the MCP settings subpage and settings-home summary/toggle contract

  **What to do**: Build the MCP subpage under Settings using the existing MCP core (`McpRegistry`, `McpClientFactory`). Settings home shows summary counts (`enabled`, `blocked`, `attention needed`) plus one master toggle to enable/disable all MCP integrations. The MCP subpage itself must show each registered server, trust state, enabled/blocked state, auth readiness, and per-server enable/disable actions. Unknown servers remain blocked until manually enabled; no plaintext secret values may appear anywhere.
  **Must NOT do**: Do not create a marketplace/discovery UI. Do not expose raw auth secrets. Do not add per-server quick toggles to Settings home beyond the single master switch.

  **Recommended Agent Profile**:
  - Category: `unspecified-high` — Reason: cross-module UI + MCP-core integration.
  - Skills: [`frontend-ui-ux`] — needed for summary-card clarity and settings hierarchy.
  - Omitted: `find-skills` — no skills-domain work; `playwright` — not a browser surface.

  **Parallelization**: Can Parallel: YES | Wave 3 | Blocks: 14, 15 | Blocked By: 7

  **References**:
  - API/Type: `mcp/src/main/kotlin/com/opencray/mcp/McpRegistry.kt` — registry/trust/auth state source of truth.
  - API/Type: `mcp/src/main/kotlin/com/opencray/mcp/McpClientFactory.kt` — active vs blocked exposure model.
  - Pattern: `docs/release-checklist.md:23-29,124-130` — required trust/auth release conditions.

  **Acceptance Criteria**:
  - [ ] `./gradlew :mcp:testDebugUnitTest :app:compileDebugAndroidTestKotlin` exits 0.
  - [ ] `adb shell am instrument -w -e class com.opencray.app.McpSettingsFlowTest org.opencray.app.test/androidx.test.runner.AndroidJUnitRunner` passes.
  - [ ] Settings home shows summary + master toggle; MCP subpage shows blocked-by-default unknown servers and no plaintext auth fields.

  **QA Scenarios**:
  ```
  Scenario: MCP summary and manual-enable state are visible in Settings
    Tool: Bash
    Steps: Open Settings home, verify MCP summary counts, enter MCP subpage, manually enable a seeded server, return to home, and verify updated counts.
    Expected: Home summary updates and subpage shows enabled status without exposing plaintext credentials.
    Evidence: .sisyphus/evidence/task-8-mcp-settings-happy.txt

  Scenario: Unknown server stays blocked until consent
    Tool: Bash
    Steps: Open MCP subpage with a seeded unknown server.
    Expected: Server is visible as blocked, tools are not exposed, and block reason is visible until manual enable occurs.
    Evidence: .sisyphus/evidence/task-8-mcp-settings-blocked.txt
  ```

  **Commit**: NO | Message: `feat(settings): add MCP management subpage` | Files: `app/src/main/**`, `ui/src/main/**`, `mcp/src/main/**`, `app/src/androidTest/**`

- [x] 9. Implement the Privacy & Telemetry settings subpage with persistent defaults

  **What to do**: Mount the existing `TelemetryToggles` as a dedicated Settings subpage and back it with a lightweight persisted settings store (app-local `SharedPreferences` is sufficient). The page must preserve the current defaults (`Enable telemetry = Off`, `Enable privacy guard = On`), keep the local-retention disclosure visible, and ensure the settings home summary reflects the current toggle state after change.
  **Must NOT do**: Do not hide the defaults. Do not imply that turning telemetry off clears mandatory local state. Do not add remote sync for settings.

  **Recommended Agent Profile**:
  - Category: `unspecified-high` — Reason: settings state/persistence plus disclosure correctness.
  - Skills: [`frontend-ui-ux`] — needed for clear consumer-facing toggle summary and disclosure placement.
  - Omitted: `find-skills`, `playwright`, `git-master`.

  **Parallelization**: Can Parallel: YES | Wave 3 | Blocks: 14, 15 | Blocked By: 7

  **References**:
  - Pattern: `ui/src/main/kotlin/com/opencray/ui/settings/TelemetryToggles.kt:42-76, 146-252` — current toggle state model, defaults, and local-retention disclosure.
  - Pattern: `app/src/main/kotlin/com/opencray/app/SafetyAndLimitsActivity.kt:23-31, 55-75` — current seeded toggle/safety composition pattern.
  - Release contract: `docs/release-checklist.md:52-76` — telemetry/privacy disclosure and default-state requirements.

  **Acceptance Criteria**:
  - [ ] `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests "*TelemetrySettingsPersistenceTest*"` exits 0.
  - [ ] `adb shell am instrument -w -e class com.opencray.app.PrivacyTelemetrySettingsTest org.opencray.app.test/androidx.test.runner.AndroidJUnitRunner` passes.
  - [ ] Toggle states persist across process recreation and Settings home reflects the updated summary.

  **QA Scenarios**:
  ```
  Scenario: Telemetry/privacy subpage shows defaults and persists changes
    Tool: Bash
    Steps: Open Settings → Privacy & Telemetry, verify defaults, toggle telemetry on, relaunch app, and reopen the page.
    Expected: The changed state persists, while the local-retention disclosure remains visible.
    Evidence: .sisyphus/evidence/task-9-privacy-settings-happy.txt

  Scenario: Turning telemetry off does not hide required local-retention disclosure
    Tool: Bash
    Steps: Set telemetry Off and inspect the page and Settings summary.
    Expected: The page still explains what OpenCray retains locally even when telemetry is off.
    Evidence: .sisyphus/evidence/task-9-privacy-settings-disclosure.txt
  ```

  **Commit**: NO | Message: `feat(settings): persist privacy and telemetry preferences` | Files: `app/src/main/**`, `ui/src/main/**`, `app/src/test/**`, `app/src/androidTest/**`

- [x] 10. Integrate Safety & Limits as a Settings subpage

  **What to do**: Move the existing `SafetyAndLimitsScreen` into the Settings subpage flow so it is reachable from Settings home and no longer presented as a separate consumer destination. Keep all existing release-critical warnings visible, resource-backed, and testable. Ensure the Settings home summary for Safety surfaces the highest-risk current warning headline.
  **Must NOT do**: Do not drop or soften any current disclosures. Do not duplicate the whole screen on Settings home.

  **Recommended Agent Profile**:
  - Category: `visual-engineering` — Reason: settings IA plus high-stakes disclosure UI.
  - Skills: [`frontend-ui-ux`] — needed for readable but minimal disclosure layout.
  - Omitted: `playwright`, `git-master`.

  **Parallelization**: Can Parallel: YES | Wave 3 | Blocks: 14, 15 | Blocked By: 7

  **References**:
  - Pattern: `ui/src/main/kotlin/com/opencray/ui/help/SafetyAndLimitsScreen.kt:14-30, 53-87, 346-399` — current warning sections and default disclosure content.
  - Test: `app/src/androidTest/kotlin/com/opencray/app/SafetyAndLimitsScreenTest.kt:31-56` — visible-string and toggle-state assertions to preserve.
  - Test: `app/src/test/kotlin/com/opencray/app/DeveloperModeDisclosureRequired.kt:10-89` — release guard for mandatory Developer mode disclosure.

  **Acceptance Criteria**:
  - [ ] `./gradlew :app:testDebugUnitTest --tests "*DeveloperModeDisclosureRequired*" :app:compileDebugAndroidTestKotlin` exits 0.
  - [ ] `adb shell am instrument -w -e class com.opencray.app.SafetyLimitsSubpageTest org.opencray.app.test/androidx.test.runner.AndroidJUnitRunner` passes.
  - [ ] Safety & Limits is reachable only through Settings home/subpage routing and retains all required warnings.

  **QA Scenarios**:
  ```
  Scenario: Safety subpage exposes all required warnings inside Settings
    Tool: Bash
    Steps: Open Settings → Safety & Limits and dump the hierarchy.
    Expected: Developer hard-denial warning, rollback local-only warning, V1 Termux warning, and V1 out-of-scope warning are all visible.
    Evidence: .sisyphus/evidence/task-10-safety-subpage-happy.txt

  Scenario: Developer disclosure guard fails if wording is removed
    Tool: Bash
    Steps: Run `./gradlew :app:testDebugUnitTest --tests "*DeveloperModeDisclosureRequired*"`.
    Expected: Test passes with current wording and is the explicit release blocker if wording is removed later.
    Evidence: .sisyphus/evidence/task-10-safety-subpage-guard.txt
  ```

  **Commit**: NO | Message: `feat(settings): route safety and limits as a subpage` | Files: `app/src/main/**`, `ui/src/main/**`, `app/src/test/**`, `app/src/androidTest/**`

- [x] 11. Build the About & Version settings subpage

  **What to do**: Create an `About & Version` subpage under Settings that shows app name (`OpenCray`), version name/code, minimum supported Android version (26), shell/navigation model summary, and links/entries for release checklist visibility (for example a static “Release guardrails verified” section with current local build metadata). Keep it simple and consumer-facing, not developer-console style.
  **Must NOT do**: Do not expose internal implementation details that users do not need. Do not add update-checking, remote release notes, or store integration.

  **Recommended Agent Profile**:
  - Category: `writing` — Reason: this is mostly structured consumer-facing release/about copy with light UI wiring.
  - Skills: [`frontend-ui-ux`] — needed to keep information digestible inside Settings.
  - Omitted: `find-skills`, `playwright`, `git-master`.

  **Parallelization**: Can Parallel: YES | Wave 3 | Blocks: 15 | Blocked By: 7

  **References**:
  - Pattern: `app/build.gradle.kts:10-16` — authoritative version name/code and minSdk values.
  - Pattern: `docs/release-checklist.md:1-38` — release-gate content that should inform user-facing about/guardrails summary.

  **Acceptance Criteria**:
  - [ ] `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin` exits 0.
  - [ ] `adb shell am instrument -w -e class com.opencray.app.AboutVersionScreenTest org.opencray.app.test/androidx.test.runner.AndroidJUnitRunner` passes.
  - [ ] About screen shows `OpenCray`, version, Android floor, and release/guardrail summary without exposing implementation clutter.

  **QA Scenarios**:
  ```
  Scenario: About screen exposes app/version metadata and guardrail summary
    Tool: Bash
    Steps: Open Settings → About & Version and dump visible text.
    Expected: App name, version name/code, Android floor, and guardrail/release summary text are visible.
    Evidence: .sisyphus/evidence/task-11-about-screen-happy.txt

  Scenario: About screen stays consumer-facing rather than diagnostic-heavy
    Tool: Bash
    Steps: Inspect visible text and hierarchy for the About subpage.
    Expected: No internal exception dumps, debug-only sections, or raw implementation jargon appear.
    Evidence: .sisyphus/evidence/task-11-about-screen-clean.txt
  ```

  **Commit**: NO | Message: `feat(settings): add about and version subpage` | Files: `app/src/main/**`, `ui/src/main/**`, `app/src/androidTest/**`

- [x] 12. Build the Personalization subpage with presets, custom personality editing, and typed-confirmation reset zone

  **What to do**: Add a `Personalization` subpage under Settings with two sections: (1) personality presets + custom personality editor; (2) a danger zone at the bottom for `Reset memory` and `Reset soul`. Use presets + custom editing, not just one or the other. Reset confirmations must require exact typed phrases: `RESET MEMORY` and `RESET SOUL`. Define the reset contract explicitly: memory reset clears app-level memory/history stores only; soul reset clears personality/soul profile only; neither reset clears workspace grants, MCP state, telemetry/privacy preferences, or app version/about metadata. If an active queue/session is not idle, both reset actions must be disabled with visible explanation.
  **Must NOT do**: Do not hide reset in another page. Do not use a one-tap destructive action. Do not silently clear unrelated stores.

  **Recommended Agent Profile**:
  - Category: `visual-engineering` — Reason: high-stakes settings UX and destructive-action safety.
  - Skills: [`frontend-ui-ux`] — needed to make presets/custom editing and the danger zone consumer-friendly.
  - Omitted: `playwright`, `git-master`.

  **Parallelization**: Can Parallel: YES | Wave 3 | Blocks: 14, 15 | Blocked By: 7

  **References**:
  - Pattern: `ui/src/main/kotlin/com/opencray/ui/help/SafetyAndLimitsScreen.kt:346-389` — current tone and disclosure style for risky behavior.
  - Pattern: `ui/src/main/kotlin/com/opencray/ui/settings/TelemetryToggles.kt:42-76` — current settings-state model shape and persistent-default approach.
  - Pattern: `app/src/main/kotlin/com/opencray/app/MainInteractionActivity.kt:64-81` — existing reset-related state language and deterministic host behavior.
  - Release contract: `docs/release-checklist.md:41-62` — disclosure requirements to align destructive-action copy with.

  **Acceptance Criteria**:
  - [ ] `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin` exits 0.
  - [ ] `adb shell am instrument -w -e class com.opencray.app.PersonalizationSettingsTest org.opencray.app.test/androidx.test.runner.AndroidJUnitRunner` passes.
  - [ ] Personalization shows presets + custom editing, and reset actions require exact typed confirmation while respecting queue-idle gating.

  **QA Scenarios**:
  ```
  Scenario: Personality presets and custom editor coexist
    Tool: Bash
    Steps: Open Settings → Personalization, select a preset, then switch to custom personality editing.
    Expected: Preset selection and custom editing are both visible and the resulting summary state updates without leaving the page.
    Evidence: .sisyphus/evidence/task-12-personalization-happy.txt

  Scenario: Reset actions require exact typed confirmation and stay blocked when queue is not idle
    Tool: Bash
    Steps: Open Personalization danger zone, attempt reset with wrong phrase, then with correct phrase; separately seed a non-idle queue state and verify reset controls disable.
    Expected: Wrong phrase blocks reset, exact phrase enables reset, and non-idle queue state disables reset with visible explanation.
    Evidence: .sisyphus/evidence/task-12-personalization-reset-guard.txt
  ```

  **Commit**: NO | Message: `feat(settings): add personalization and reset danger zone` | Files: `app/src/main/**`, `ui/src/main/**`, `app/src/androidTest/**`

- [x] 13. Convert standalone activities into compatibility wrappers and move the launcher to `AppShellActivity`

  **What to do**: Make `AppShellActivity` the only launcher in the manifest. Keep `SkillsManagementActivity`, `MainInteractionActivity`, `WorkspaceSettingsActivity`, and `SafetyAndLimitsActivity` as temporary compatibility wrappers that immediately route into the correct shell destination and subpage using the task 1 extras contract. Preserve existing scenario extras for Chat and Files so current tests/manual commands still work. After wrapper routing, these activities must no longer host primary consumer UI directly.
  **Must NOT do**: Do not delete wrapper activities in this refactor. Do not keep the old Skills launcher. Do not strand users in a parallel non-shell experience.

  **Recommended Agent Profile**:
  - Category: `deep` — Reason: manifest, wrapper routing, and compatibility behavior are cross-cutting and easy to get wrong.
  - Skills: `[]` — no specialist skill required.
  - Omitted: `frontend-ui-ux` — this is routing/compatibility logic, not layout design.

  **Parallelization**: Can Parallel: NO | Wave 4 | Blocks: 14, 15 | Blocked By: 3, 4, 5, 6, 7, 8, 9, 10, 11, 12

  **References**:
  - Pattern: `app/src/main/AndroidManifest.xml:5-30` — current launcher and exported activities to replace with shell-first routing.
  - Pattern: `app/src/main/kotlin/com/opencray/app/SkillsManagementActivity.kt:17-139` — current launcher-era activity that becomes a wrapper.
  - Pattern: `app/src/main/kotlin/com/opencray/app/MainInteractionActivity.kt:20-24, 84-92` — scenario extras that must survive wrapper routing.
  - Pattern: `app/src/main/kotlin/com/opencray/app/WorkspaceSettingsActivity.kt:34-40, 77-87` — Files/SAF scenario extras that must survive wrapper routing.
  - Pattern: `app/src/main/kotlin/com/opencray/app/SafetyAndLimitsActivity.kt:14-85` — current standalone settings/help host to demote into a wrapper.

  **Acceptance Criteria**:
  - [ ] `./gradlew :app:assembleDebug :app:compileDebugAndroidTestKotlin` exits 0.
  - [ ] Normal app launch opens `AppShellActivity`.
  - [ ] Direct launches of legacy activities still land on the right shell tab/subpage with preserved scenario state.

  **QA Scenarios**:
  ```
  Scenario: Shell becomes the only launcher
    Tool: Bash
    Steps: Launch the app from the launcher and inspect the resumed activity plus visible UI.
    Expected: `AppShellActivity` is resumed and bottom navigation is visible.
    Evidence: .sisyphus/evidence/task-13-shell-launcher.txt

  Scenario: Legacy activities route into the shell correctly
    Tool: Bash
    Steps: Launch each old activity via `adb shell am start -n ...`.
    Expected: Each one lands on the correct shell tab or settings subpage rather than a separate old host screen.
    Evidence: .sisyphus/evidence/task-13-wrapper-routing.txt
  ```

  **Commit**: NO | Message: `refactor(app): route legacy activities through shell` | Files: `app/src/main/**`, `app/src/androidTest/**`

- [x] 14. Update shell-level navigation, bilingual, and replay QA suites

  **What to do**: Create or update Android instrumentation coverage for the shell itself: bottom-nav visibility, tab switching, tab-state retention, Settings home/subpage routing, wrapper routing, Files shell state, and bilingual English/Simplified Chinese copy checks. Preserve direct `adb shell am instrument` replay support because UTP has been flaky. Refresh evidence for shell-era user flows and locale coverage.
  **Must NOT do**: Do not leave shell navigation validated only by compile tests. Do not rely on brittle Espresso input injection where direct view-tree helpers are already the more stable pattern.

  **Recommended Agent Profile**:
  - Category: `unspecified-high` — Reason: cross-surface QA orchestration and evidence capture.
  - Skills: `[]` — the project already has stable direct-instrumentation patterns.
  - Omitted: `playwright` — Android native only.

  **Parallelization**: Can Parallel: NO | Wave 4 | Blocks: 15 | Blocked By: 4, 5, 6, 7, 8, 9, 10, 11, 12, 13

  **References**:
  - Test: `app/src/androidTest/kotlin/com/opencray/app/MainFlowUiTest.kt:16-183` — current direct-view Android instrumentation style.
  - Test: `app/src/androidTest/kotlin/com/opencray/app/SkillsManagementUiTest.kt:30-90` — existing emulator-safe skills flow pattern.
  - Test: `app/src/androidTest/kotlin/com/opencray/app/SafIntegrationTest.kt:29-58` — Files/SAF state validation pattern.
  - Test: `app/src/androidTest/kotlin/com/opencray/app/SafetyAndLimitsScreenTest.kt:31-56` — disclosure visibility pattern.

  **Acceptance Criteria**:
  - [ ] `./gradlew :app:compileDebugAndroidTestKotlin :app:assembleDebugAndroidTest` exits 0.
  - [ ] Direct `adb shell am instrument` replay passes for shell nav, settings nav, Files state, and bilingual copy coverage.
  - [ ] Evidence exists for both English and Simplified Chinese shell captures.

  **QA Scenarios**:
  ```
  Scenario: Shell navigation and settings routing replay passes in English
    Tool: Bash
    Steps: Run the shell/navigation/settings instrumentation suite via `adb shell am instrument`, then dump UI XML/screenshots.
    Expected: Chat/Skills/Files/Settings all remain reachable, and settings subpage back behavior is correct.
    Evidence: .sisyphus/evidence/task-14-shell-english.txt

  Scenario: Simplified Chinese shell copy renders correctly
    Tool: Bash
    Steps: Set emulator locale to `zh-CN`, relaunch the shell, and dump visible text for the shell and settings.
    Expected: Navigation, settings cards, and disclosures render in Simplified Chinese with no mixed-language regression except fixed confirmation tokens.
    Evidence: .sisyphus/evidence/task-14-shell-zh-cn.txt
  ```

  **Commit**: NO | Message: `test(app): cover shell navigation and bilingual replay` | Files: `app/src/androidTest/**`, `.sisyphus/evidence/**`

- [x] 15. Refresh release checklist, migration notes, and evidence mapping for the shell launch

  **What to do**: Update `docs/release-checklist.md` and add `docs/consumer-shell-migration.md` to reflect the new shell: launcher = `AppShellActivity`, wrappers retained for compatibility, top-level tabs fixed to Chat/Skills/Files/Settings, Settings home + subpages, system-locale-only bilingual behavior, Files scope limits, and typed reset rules. Point the release checklist to shell-era evidence rather than only pre-shell standalone-activity evidence.
  **Must NOT do**: Do not document out-of-scope features as near-term release promises. Do not leave release docs pointing at obsolete launcher assumptions.

  **Recommended Agent Profile**:
  - Category: `writing` — Reason: release/migration docs and evidence mapping.
  - Skills: `[]` — concise technical writing only.
  - Omitted: `frontend-ui-ux`, `playwright`, `git-master`.

  **Parallelization**: Can Parallel: YES | Wave 4 | Blocks: none | Blocked By: 8, 9, 10, 11, 12, 14

  **References**:
  - Pattern: `docs/release-checklist.md:1-162` — existing gate structure to extend.
  - Pattern: `docs/termux-phase.md` — concise scope-boundary documentation style.
  - Evidence: future shell-era artifacts from tasks 13 and 14.

  **Acceptance Criteria**:
  - [ ] `docs/release-checklist.md` references shell-era evidence and shell-era disclosures.
  - [ ] `docs/consumer-shell-migration.md` exists and explains launcher, wrappers, tab map, settings structure, bilingual strategy, Files scope, and reset phrases.
  - [ ] `./gradlew :app:testDebugUnitTest --tests "*DeveloperModeDisclosureRequired*"` still exits 0.

  **QA Scenarios**:
  ```
  Scenario: Release checklist now points at shell-era evidence
    Tool: Bash
    Steps: Search the refreshed docs for shell-era evidence artifact names and shell-routing terminology.
    Expected: Release docs reference the new shell evidence set and no longer imply the old launcher/activity layout is the released UX.
    Evidence: .sisyphus/evidence/task-15-release-checklist-refresh.txt

  Scenario: Migration notes explain exactly what changed
    Tool: Bash
    Steps: Read `docs/consumer-shell-migration.md` and verify launcher, wrappers, tab map, settings structure, bilingual rules, Files scope, and reset phrases are all explicit.
    Expected: All six migration facts are present and unambiguous.
    Evidence: .sisyphus/evidence/task-15-shell-migration-doc.txt
  ```

  **Commit**: NO | Message: `docs(app): document shell migration and release gates` | Files: `docs/**`, `.sisyphus/evidence/**`



## Final Verification Wave (4 parallel agents, ALL must APPROVE)
- [ ] F1. Plan Compliance Audit — oracle
- [ ] F2. Code Quality Review — unspecified-high
- [ ] F3. Real Manual QA — unspecified-high
- [ ] F4. Scope Fidelity Check — deep

## Commit Strategy
- Commit 1: `feat(app): add OpenCray shell navigation foundation`
- Commit 2: `feat(ui): integrate consumer tabs and settings subpages`
- Commit 3: `test(app): cover shell navigation and release disclosures`

## Success Criteria
- Bottom navigation replaces the current “one launcher activity per feature” feel.
- Users can reach Chat, Skills, Files, and Settings without hidden routes.
- Settings feels like a consumer settings hub rather than a pile of standalone technical screens.
- Existing safety and policy guardrails remain visible and test-backed after shell migration.
- Bilingual copy is consistent and system-locale aware.
