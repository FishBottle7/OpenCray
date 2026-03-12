# Release checklist

Use this checklist before approving any V1 release candidate. Every required item must be checked. Any fail condition blocks release.

## Shell-era structure baseline

Use these checks before reviewing the deeper release gates. Consumer release notes, QA scripts, screenshots, and support docs must describe the shell-era app that ships now, not the older standalone-activity layout.

- [ ] SB-01 Launcher contract points to the shell
  - Pass if: `.sisyphus/evidence/task-13-shell-launcher.txt` shows `com.opencray.app.AppShellActivity` is the only `MAIN` + `LAUNCHER` activity, and a launcher-style start renders `Chat`, `Skills`, `Files`, and `Settings`.
  - Fail if: any release-facing document treats a legacy exported activity as the launcher or primary home surface.

- [ ] SB-02 Legacy activity names are compatibility wrappers only
  - Pass if: `.sisyphus/evidence/task-13-wrapper-routing.txt` shows direct launches of `SkillsManagementActivity`, `MainInteractionActivity`, `WorkspaceSettingsActivity`, and `SafetyAndLimitsActivity` all resume `AppShellActivity` and land on the intended shell destination.
  - Fail if: a wrapper is documented as a standalone consumer destination, loses scenario data, or opens a parallel UX outside the shell.

- [ ] SB-03 Consumer tab map stays `Chat / Skills / Files / Settings`
  - Pass if: the launcher and shell tab evidence keep that top-level order visible and functional.
  - Evidence: `.sisyphus/evidence/task-13-shell-launcher.txt`, `.sisyphus/evidence/task-4-chat-tab-approval.txt`, `.sisyphus/evidence/task-5-skills-tab-happy.txt`, `.sisyphus/evidence/task-6-files-workbench-happy.txt`, `.sisyphus/evidence/task-7-settings-home.txt`
  - Fail if: release copy or QA steps still describe old activity names as peer top-level app sections.

- [ ] SB-04 Settings uses a home-plus-subpage shell structure
  - Pass if: `.sisyphus/evidence/task-7-settings-home.txt` shows exactly five Settings HOME cards, `MCP`, `Privacy & Telemetry`, `Safety & Limits`, `About & Version`, `Personalization`, and `.sisyphus/evidence/task-7-settings-back.txt` shows subpages return to HOME before leaving the tab.
  - Pass if: `.sisyphus/evidence/task-8-mcp-settings-happy.txt`, `.sisyphus/evidence/task-9-privacy-settings-happy.txt`, `.sisyphus/evidence/task-10-safety-subpage-happy.txt`, `.sisyphus/evidence/task-11-about-screen-happy.txt`, and `.sisyphus/evidence/task-12-personalization-happy.txt` show real content inside the shell-owned Settings host.
  - Fail if: release docs describe one merged long Settings page, or treat the old standalone screens as separate app areas.

- [ ] SB-05 Bilingual behavior is system-locale driven in V1
  - Pass if: `.sisyphus/evidence/task-2-bilingual-resources.txt` and `.sisyphus/evidence/task-2-reset-token-strings.txt` show paired `values` and `values-zh-rCN` resources, and the shell Settings evidence continues to define only the five cards above.
  - Pass if: release copy states bilingual behavior follows the device locale and does not promise an in-app language switch in V1.
  - Fail if: any release-facing copy promises a language picker or describes locale behavior that is not backed by the audited resource evidence.

- [ ] SB-06 Files tab scope stays bounded to workspace grant state and root-safe actions
  - Pass if: `.sisyphus/evidence/task-6-files-workbench-happy.txt`, `.sisyphus/evidence/task-6-files-workbench-deny.txt`, `.sisyphus/evidence/task-15-saf-happy.txt`, and `.sisyphus/evidence/task-15-saf-revoked.txt` keep the Files tab focused on workspace access, grant status, recovery, and outside-root denial handling.
  - Fail if: release docs imply a general file browser, silent out-of-root access, or recovery outside the shell-hosted Files tab.

- [ ] SB-07 Typed reset phrases stay exact
  - Pass if: `.sisyphus/evidence/task-12-personalization-happy.txt`, `.sisyphus/evidence/task-12-personalization-reset-guard.txt`, and `.sisyphus/evidence/task-2-reset-token-strings.txt` show `RESET MEMORY` and `RESET SOUL` as the exact required phrases in both locales.
  - Fail if: release copy localizes those tokens, softens exact matching, or misstates what each reset clears.

## Release gate checklist

- [ ] RG-01 Policy matrix and protected-file invariants, Tasks 3 and 16
  - Pass if: `.sisyphus/evidence/task-3-policy-happy.txt` and `.sisyphus/evidence/task-3-policy-protected-deny.txt` exist, and `.sisyphus/evidence/task-16-e2e-traversal-deny.txt` shows `DENY_PATH_ESCAPE` with no out-of-root write.
  - Fail if: Safe, Auto, or Developer mode behavior is undocumented, nondeterministic, or protected and path-escape denials can be bypassed.

- [ ] RG-02 Local file rollback stays bounded, Tasks 8 and 16
  - Pass if: `.sisyphus/evidence/task-8-fileops-happy.txt` shows checkpoint metadata plus matching rollback hashes before and after restore, and release copy states rollback is local-only.
  - Fail if: partial local writes survive a failed batch, or any user-facing copy promises rollback for command side effects, MCP side effects, network or remote system changes, or other external effects.

- [ ] RG-03 Command execution gating and audit trail, Tasks 3, 9, and 16
  - Pass if: `.sisyphus/evidence/task-9-command-happy.txt` shows `spawn_count=1` with audit metadata, `.sisyphus/evidence/task-9-command-deny.txt` shows `spawn_count=0`, and release disclosures keep approval requirements visible for gated commands.
  - Fail if: a denied command can spawn, audit records disappear, or Developer mode is described as bypassing hard policy denials.

- [ ] RG-04 Skills validation and invocation controls, Tasks 10 and 16
  - Pass if: `.sisyphus/evidence/task-10-skills-happy.txt` shows normalized load data with explicit invocation control, and `.sisyphus/evidence/task-10-skills-invalid.txt` shows `INVALID_SKILL_METADATA` with `registryEntryCreated=false`.
  - Fail if: malformed or disabled skills can enter the active registry.

- [ ] RG-05 MCP trust and auth handling, Tasks 11 and 16
  - Pass if: `.sisyphus/evidence/task-11-mcp-happy.txt` shows manual enable persists across reload, and `.sisyphus/evidence/task-11-mcp-blocked.txt` shows blocked exposure plus `credentialRef` instead of plaintext secret fields.
  - Fail if: an unknown server auto-enables, tools appear before consent, or raw auth secrets are exposed.

- [ ] RG-06 SAF workspace boundary and revocation recovery, Tasks 15 and 16
  - Pass if: `.sisyphus/evidence/task-15-saf-happy.txt` and `.sisyphus/evidence/task-15-saf-revoked.txt` show passing adb instrumentation runs, and the Task 15 UI artifacts remain available for review.
  - Fail if: stale SAF grants survive revocation, out-of-root operations succeed, or recovery guidance is missing.

- [ ] RG-07 V1 runtime scope and Termux limits, Task 17
  - Pass if: `docs/termux-phase.md`, `.sisyphus/evidence/task-17-termux-contract-happy.txt`, and `.sisyphus/evidence/task-17-termux-contract-failure-guard.txt` show `termux_stub`, `unavailable_in_v1`, and `TERMUX_UNAVAILABLE`.
  - Fail if: any release-facing copy implies real Termux execution ships in V1, or any production path requires Termux.

- [ ] RG-08 Release disclosures complete, Task 18
  - Pass if: every item in the user-facing disclosure checklist is checked and backed by release UI or validation evidence.
  - Fail if: any required warning is missing. Release must fail if Developer mode disclosure is missing.

## User-facing disclosure checklist

- [ ] UD-01 Developer mode disclosure
  - Required content: state that Developer mode can expose high-risk operations and reduce prompts, but it does not override protected-file, path, or other hard policy denials.
  - Pass if: the warning is visible in settings or help copy and test-verified.
  - Fail if: the warning is hidden, softened, or omitted. Release must fail if Developer mode disclosure is missing.

- [ ] UD-02 Rollback limits disclosure
  - Required content: state that rollback is guaranteed only for local filesystem checkpoints.
  - Required content: state that command side effects, MCP side effects, network or remote system changes, and other external effects are not guaranteed reversible.
  - Pass if: the copy matches Task 8 and Task 16 behavior.
  - Fail if: any screen, help text, or release note promises general rollback.

- [ ] UD-03 Telemetry and privacy disclosure
  - Required content: name each telemetry or privacy toggle, its default state, what behavior it changes, whether the setting persists, and where the user can change it.
  - Required content: explain which local identity, history, or audit records remain required for core app function even when telemetry is off.
  - Pass if: the disclosure is visible next to the toggles and test-verified.
  - Fail if: defaults are undocumented, labels are vague, or the privacy copy promises behavior that is not backed by evidence.

- [ ] UD-04 V1 out-of-scope warning
  - Required content: state that V1 does not ship real Termux execution.
  - Required content: state that multi-agent parallel execution, iOS client support, cloud collaboration sync, and a public marketplace review system are out of scope for V1.
  - Pass if: the warning is visible in settings, help, or release notes.
  - Fail if: release-facing copy implies these items are shipped or nearly shipped in V1.

## Telemetry/privacy defaults and verification

- [ ] TP-01 Defaults are fixed before ship
  - Pass if: each telemetry and privacy toggle has a named default state in settings or help copy, release notes, and QA expectations.
  - Fail if: reviewers must infer defaults from code or screenshots.

- [ ] TP-02 Toggle rendering and default state are test-backed
  - Pass if: `.sisyphus/evidence/task-18-release-ui-happy.txt` confirms the toggle labels and default states rendered in the release candidate.
  - Fail if: a toggle is hidden, mislabeled, missing a default-state assertion, or missing evidence.

- [ ] TP-03 Disclosure matches storage behavior
  - Pass if: privacy wording explains what remains stored for core app operation, what can be disabled, and what cannot.
  - Fail if: privacy wording over-promises anonymity, deletion, or non-retention beyond verified behavior.

## Rollback / SAF / policy / runtime / skills / MCP checks

### Policy

- [ ] PC-01 Protected files stay blocked by policy, Task 3
  - Pass if: protected delete, rename, and move attempts resolve to deterministic denial and the release copy does not claim a mode can bypass that rule.
  - Evidence: `.sisyphus/evidence/task-3-policy-protected-deny.txt`

- [ ] PC-02 Path escape stays blocked end to end, Tasks 3 and 16
  - Pass if: `DENY_PATH_ESCAPE` remains visible in both decision output and user-facing denial UI.
  - Evidence: `.sisyphus/evidence/task-16-e2e-traversal-deny.txt`

### Rollback

- [ ] RC-01 Local rollback emits checkpoint evidence, Task 8
  - Pass if: checkpoint id, entry count, committed path count, and matching before or after hashes are present.
  - Evidence: `.sisyphus/evidence/task-8-fileops-happy.txt`

- [ ] RC-02 Remote rollback is never promised, Tasks 8 and 16
  - Pass if: no release-facing copy claims rollback for remote or external effects.
  - Evidence: Task 8 plan limit, Task 16 E2E scope, and release disclosure review

### SAF

- [ ] SC-01 Granted SAF roots only allow in-root operations, Task 15
  - Pass if: the grant path succeeds only inside the granted root.
  - Evidence: `.sisyphus/evidence/task-15-saf-happy.txt`

- [ ] SC-02 Revoked SAF permission fails with recovery path, Task 15
  - Pass if: revocation produces a recoverable state and re-authorize guidance.
  - Evidence: `.sisyphus/evidence/task-15-saf-revoked.txt`

### Runtime

- [ ] RTC-01 V1 stays independent from real Termux execution, Task 17
  - Pass if: the V1 adapter path resolves to the deterministic unavailable stub and preserves normalized runtime metadata.
  - Evidence: `docs/termux-phase.md`, `.sisyphus/evidence/task-17-termux-contract-happy.txt`, `.sisyphus/evidence/task-17-termux-contract-failure-guard.txt`

### Skills

- [ ] SKC-01 Only valid skills become active, Task 10
  - Pass if: valid `SKILL.md` loads with explicit invocation metadata and malformed skills stay non-invokable.
  - Evidence: `.sisyphus/evidence/task-10-skills-happy.txt`, `.sisyphus/evidence/task-10-skills-invalid.txt`

### MCP

- [ ] MC-01 Unknown MCP servers stay blocked until consent, Task 11
  - Pass if: exposure stays `BLOCKED` until manual enable and auth metadata uses a secret reference instead of plaintext.
  - Evidence: `.sisyphus/evidence/task-11-mcp-blocked.txt`

- [ ] MC-02 Enabled MCP state survives reload, Task 11
  - Pass if: manually enabled state persists and tools become active only after persisted consent.
  - Evidence: `.sisyphus/evidence/task-11-mcp-happy.txt`

## Evidence artifact checklist

- [ ] `.sisyphus/evidence/task-2-bilingual-resources.txt`
- [ ] `.sisyphus/evidence/task-2-reset-token-strings.txt`
- [ ] `.sisyphus/evidence/task-3-policy-happy.txt`
- [ ] `.sisyphus/evidence/task-3-policy-protected-deny.txt`
- [ ] `.sisyphus/evidence/task-4-chat-tab-approval.txt`
- [ ] `.sisyphus/evidence/task-4-chat-tab-deny.txt`
- [ ] `.sisyphus/evidence/task-5-skills-tab-happy.txt`
- [ ] `.sisyphus/evidence/task-6-files-workbench-happy.txt`
- [ ] `.sisyphus/evidence/task-6-files-workbench-deny.txt`
- [ ] `.sisyphus/evidence/task-7-settings-home.txt`
- [ ] `.sisyphus/evidence/task-7-settings-back.txt`
- [ ] `.sisyphus/evidence/task-8-fileops-happy.txt`
- [ ] `.sisyphus/evidence/task-8-fileops-protected-deny.txt`
- [ ] `.sisyphus/evidence/task-8-mcp-settings-happy.txt`
- [ ] `.sisyphus/evidence/task-8-mcp-settings-blocked.txt`
- [ ] `.sisyphus/evidence/task-9-command-happy.txt`
- [ ] `.sisyphus/evidence/task-9-command-deny.txt`
- [ ] `.sisyphus/evidence/task-9-privacy-settings-happy.txt`
- [ ] `.sisyphus/evidence/task-9-privacy-settings-disclosure.txt`
- [ ] `.sisyphus/evidence/task-10-skills-happy.txt`
- [ ] `.sisyphus/evidence/task-10-skills-invalid.txt`
- [ ] `.sisyphus/evidence/task-10-safety-subpage-happy.txt`
- [ ] `.sisyphus/evidence/task-11-mcp-happy.txt`
- [ ] `.sisyphus/evidence/task-11-mcp-blocked.txt`
- [ ] `.sisyphus/evidence/task-11-about-screen-happy.txt`
- [ ] `.sisyphus/evidence/task-12-personalization-happy.txt`
- [ ] `.sisyphus/evidence/task-12-personalization-reset-guard.txt`
- [ ] `.sisyphus/evidence/task-13-shell-launcher.txt`
- [ ] `.sisyphus/evidence/task-13-wrapper-routing.txt`
- [ ] `.sisyphus/evidence/task-15-saf-happy.txt`
- [ ] `.sisyphus/evidence/task-15-saf-revoked.txt`
- [ ] `.sisyphus/evidence/task-15-workspace-ui.png`
- [ ] `.sisyphus/evidence/task-15-workspace-ui.xml`
- [ ] `.sisyphus/evidence/task-15-release-checklist-refresh.txt`
- [ ] `.sisyphus/evidence/task-15-shell-migration-doc.txt`
- [ ] `.sisyphus/evidence/task-16-e2e-happy.txt`
- [ ] `.sisyphus/evidence/task-16-e2e-traversal-deny.txt`
- [ ] `.sisyphus/evidence/task-17-termux-contract-happy.txt`
- [ ] `.sisyphus/evidence/task-17-termux-contract-failure-guard.txt`
- [ ] `docs/consumer-shell-migration.md`
- [ ] `docs/termux-phase.md`
- [ ] `.sisyphus/evidence/task-18-release-ui-happy.txt` required before ship
- [ ] `.sisyphus/evidence/task-18-release-disclosure-guard.txt` required before ship

## Learnings

- The safest release gate is plain: if a warning changes user expectations, tie it to a named evidence artifact.
- Shell-era release docs stay clearer when each consumer claim names the shell destination first, then cites any legacy wrapper only as a compatibility route.

## Issues

- This file defines the release gate now, but Task 18 UI and disclosure-test artifacts still need to exist before any V1 release can pass.
- Shell evidence now spans Tasks 2 and 4 through 15, so release reviewers need `docs/consumer-shell-migration.md` nearby to keep old standalone activity names from slipping back into release copy.
