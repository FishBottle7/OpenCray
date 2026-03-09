# Android-First Mobile Coding Agent Plan (Claude Code / Codex / OpenCode-like)

## TL;DR

> **Quick Summary**: Build an Android-first coding-agent app with persistent identity, in-app Python execution, Skills+MCP, and strong policy controls; then prepare a Termux sandbox phase through a compatible runtime adapter contract.
>
> **Deliverables**:
> - Android app foundation with agent loop, queue, policy engine, file/command/Python execution, Skills, MCP, and UI management panels
> - TDD-based verification suite (Android + Python) with agent-executed QA evidence
> - Termux phase contract scaffolding (without fully shipping Termux runtime in V1)
>
> **Estimated Effort**: XL
> **Parallel Execution**: YES - 4 implementation waves + final verification wave
> **Critical Path**: 2 → 6 → 8 → 16 → FINAL

---

## Context

### Original Request
User wants a mobile coding agent similar to Claude Code / OpenCode / OpenClaw style experience:
- Basic: run Python in app workspace, install dependencies, write markdown/files, run scripts/commands.
- Advanced: later integrate Termux Linux sandbox.
- File ops allow write/delete in app scope, but protected files (agent.md, memory.md, soul.md + expanded protected set) must follow policy.
- Skills and MCP must be supported, including self-install/self-create skills and a UI for create/edit/disable/delete + install/uninstall + import/export.

### Interview Summary
**Key confirmed decisions**:
- Android native first; cross-platform considered later.
- Single persistent agent identity across sessions.
- Execution model: single-session serial + queue.
- Runtime: in-app Python first; Termux later.
- Skills strategy: Agent Skills core standard + selected extensions.
- Skills installation: directory package + Git installation.
- MCP: open onboarding; non-whitelisted servers require manual enable and can persist.
- Security posture: three modes (Safe / Auto / Developer).
- Recovery: rollback + retry; rollback guaranteed only for local operations.
- V1 out-of-scope: multi-agent parallel, iOS client, cloud collaboration sync, public marketplace review system.

### Research Findings
**Official docs used**:
- Claude Code: permissions, sandboxing, skills, plugins, MCP, subagents.
- Codex: CLI approvals/sandbox, skills, MCP.
- OpenCode: agents, skills, MCP servers.

**Cross-tool common standard**:
- Skill directory + `SKILL.md`
- Metadata-driven invocation (`name`/`description`)
- Explicit + optional implicit invocation
- Permission/visibility controls

**Licensing/usage caution**:
- `system-prompts-and-models-of-ai-tools` may be used as inspiration only.
- Do not copy prompt text verbatim into product assets; respect GPL and provenance limitations.

### Metis Review (Applied)
Guardrails explicitly incorporated:
- Mode matrix must be explicit and testable.
- Protected-file invariants must block delete/rename/move bypass.
- Path boundary + traversal escape prevention is mandatory.
- Local vs remote rollback semantics must be explicit.
- Scope creep lockdown included in Must NOT Have.

---

## Work Objectives

### Core Objective
Deliver a production-structured Android coding-agent MVP that safely executes local development tasks with Python + file/command tooling, extensible via Skills and MCP, while maintaining persistent identity and verifiable policy behavior.

### Concrete Deliverables
- Android app modules: agent-core, execution-runtime, policy/security, skills, mcp, ui, persistence, tests.
- Skills lifecycle support: create/edit/disable/delete/install/uninstall/import/export.
- MCP lifecycle support: add/remove/enable/auth/configure + persisted trust state.
- Evidence-driven QA artifacts in `.sisyphus/evidence/`.
- Termux-phase compatibility interfaces and parity test contract (V1 scaffolding only).

### Definition of Done
- [x] All Must Have items implemented and all Must NOT Have items absent.
- [x] All automated tests pass (Android unit/instrumentation + Python tests).
- [x] Every task has agent-executed QA evidence files.
- [x] Mode matrix, protected-file policy, and rollback semantics validated by tests.

### Must Have
- In-app Python execution with dependency install in controlled environment.
- File and command operations scoped to approved workspace.
- Protected-file and path-boundary enforcement.
- Persistent identity/memory continuity.
- Skills + MCP support with management UI.
- Three execution modes: Safe / Auto / Developer.
- TDD workflow + agent-executed QA.

### Must NOT Have (Guardrails)
- No multi-agent parallel orchestration in V1.
- No iOS client in V1.
- No cloud multi-device collaboration sync in V1.
- No public skill marketplace moderation system in V1.
- No verbatim reuse of GPL prompt dumps into product bundles.
- No claims of guaranteed rollback for remote side effects.

---

## Verification Strategy (MANDATORY)

> **ZERO HUMAN INTERVENTION** — all validation is agent-executed.

### Test Decision
- **Infrastructure exists**: NO (greenfield)
- **Automated tests**: YES (TDD)
- **Framework**:
  - Android: JUnit + MockK + Espresso
  - Python: pytest
- **TDD policy**: RED → GREEN → REFACTOR on each implementation task.

### QA Policy
- Frontend/UI: Playwright where browser-equivalent checks apply or Android instrumentation equivalents.
- CLI/runtime/API checks: bash/command assertions and deterministic outputs.
- Evidence path convention: `.sisyphus/evidence/task-{N}-{scenario-slug}.{ext}`
- Each task includes at least:
  - 1 happy-path scenario
  - 1 failure/edge scenario

---

## Execution Strategy

### Parallel Execution Waves

```
Wave 1 (Foundation - can start together)
├── Task 1: Android project scaffold + module layout [quick]
├── Task 2: Core contracts + canonical schemas [deep]
├── Task 3: Policy matrix + protected registry spec [deep]
├── Task 4: Persistence + secure secret interfaces [unspecified-high]
└── Task 5: Test harness + evidence pipeline [quick]

Wave 2 (Core runtime implementation)
├── Task 6: Agent orchestration queue + session state [deep] (depends: 2,4)
├── Task 7: In-app Python runtime + package installer [unspecified-high] (depends: 1,2,5)
├── Task 8: File operations + local rollback engine [deep] (depends: 2,3,4)
├── Task 9: Command executor + mode gating [deep] (depends: 2,3)
└── Task 10: Skills engine core (SKILL.md standard + validation) [unspecified-high] (depends: 2,3,5)

Wave 3 (Integrations + UI)
├── Task 11: MCP manager core + persisted trust/auth state [unspecified-high] (depends: 2,3,4)
├── Task 12: LiteLLM provider gateway + routing [quick] (depends: 2,6)
├── Task 13: Skills management UI (CRUD+install/import-export) [visual-engineering] (depends: 6,10)
└── Task 14: Main interaction UI (chat/timeline/approvals/settings) [visual-engineering] (depends: 6,8,9,12)

Wave 4 (Hardening + phase bridge)
├── Task 15: SAF integration + boundary enforcement bridge [unspecified-high] (depends: 8,14)
├── Task 16: Security/policy E2E tests + restart persistence tests [deep] (depends: 8,9,10,11,14,15)
├── Task 17: Termux adapter contract + parity test scaffolding [deep] (depends: 2,6,7,9,11)
└── Task 18: Release hardening + operational guardrails docs-in-app [writing] (depends: 16,17)

Wave FINAL (Independent verification, all parallel)
├── F1: Plan compliance audit (oracle)
├── F2: Code quality review (unspecified-high)
├── F3: Real QA replay across all task scenarios (unspecified-high)
└── F4: Scope fidelity check (deep)

Critical Path: 2 → 6 → 8 → 16 → F1-F4
Parallel Speedup: ~60% vs fully sequential
Max Concurrent: 5
```

### Dependency Matrix

- **1**: — → 7
- **2**: — → 6,7,8,9,10,11,12,17
- **3**: — → 8,9,10,11
- **4**: — → 6,8,11
- **5**: — → 7,10
- **6**: 2,4 → 12,13,14,17
- **7**: 1,2,5 → 17
- **8**: 2,3,4 → 14,15,16
- **9**: 2,3 → 14,16,17
- **10**: 2,3,5 → 13,16
- **11**: 2,3,4 → 16,17
- **12**: 2,6 → 14
- **13**: 6,10 → 16
- **14**: 6,8,9,12 → 15,16
- **15**: 8,14 → 16
- **16**: 8,9,10,11,14,15 → 18, FINAL
- **17**: 2,6,7,9,11 → 18, FINAL
- **18**: 16,17 → FINAL

### Agent Dispatch Summary

- **Wave 1 (5 tasks)**: T1 quick, T2 deep, T3 deep, T4 unspecified-high, T5 quick
- **Wave 2 (5 tasks)**: T6 deep, T7 unspecified-high, T8 deep, T9 deep, T10 unspecified-high
- **Wave 3 (4 tasks)**: T11 unspecified-high, T12 quick, T13 visual-engineering, T14 visual-engineering
- **Wave 4 (4 tasks)**: T15 unspecified-high, T16 deep, T17 deep, T18 writing
- **FINAL (4 tasks)**: F1 oracle, F2 unspecified-high, F3 unspecified-high, F4 deep

---

## TODOs

> Implementation + testing are bundled in each task.

---

- [x] 1. Bootstrap Android project skeleton and module boundaries

  **What to do**:
  - Create baseline Gradle structure for `app`, `core`, `runtime`, `skills`, `mcp`, `ui`, `persistence` modules.
  - Define deterministic build variants for debug/release and baseline dependency catalogs.
  - Ensure project boots with empty feature stubs and no unresolved module references.

  **Must NOT do**:
  - Do not add feature logic in this task.
  - Do not introduce iOS, cloud sync, or multi-agent modules.

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: scaffolding and deterministic project wiring.
  - **Skills**: `find-skills`
    - `find-skills`: useful to discover reusable setup conventions if needed.
  - **Skills Evaluated but Omitted**:
    - `playwright`: no browser flow here.

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 2-5)
  - **Blocks**: Task 7
  - **Blocked By**: None

  **References**:
  - **Pattern References**:
    - Greenfield: no local pattern baseline exists; this task creates the baseline structure.
  - **API/Type References**:
    - `https://developer.android.com/studio/build` - canonical Android Gradle layout guidance.
  - **Test References**:
    - `https://developer.android.com/training/testing/fundamentals` - test module placement conventions.
  - **External References**:
    - OpenCode multi-surface structure inspiration: `https://github.com/anomalyco/opencode`
  - **WHY Each Reference Matters**:
    - Ensure layout decisions are aligned with mature coding-agent projects and Android standards.

  **Acceptance Criteria**:
  - [ ] `./gradlew tasks` runs successfully from project root.
  - [ ] `./gradlew :app:assembleDebug` completes with `BUILD SUCCESSFUL`.
  - [ ] Module graph includes all planned modules without cyclic dependency errors.

  **QA Scenarios (MANDATORY)**:

  ```
  Scenario: Happy path — clean bootstrap builds
    Tool: Bash
    Preconditions: Fresh clone; no cached local modifications
    Steps:
      1. Run ./gradlew clean
      2. Run ./gradlew :app:assembleDebug
      3. Assert output contains "BUILD SUCCESSFUL"
    Expected Result: Debug APK assembled; all modules resolved
    Failure Indicators: "Project not found", dependency cycle, unresolved plugin
    Evidence: .sisyphus/evidence/task-1-bootstrap-build.txt

  Scenario: Failure path — broken module include is detected
    Tool: Bash
    Preconditions: Temporarily remove one module include entry during test run
    Steps:
      1. Run ./gradlew :app:assembleDebug
      2. Assert build fails with explicit missing module message
    Expected Result: Deterministic failure and actionable error output
    Evidence: .sisyphus/evidence/task-1-bootstrap-failure.txt
  ```

  **Evidence to Capture**:
  - [ ] Build output logs for success/failure scenarios.

  **Commit**: YES
  - Message: `chore(android): establish modular app scaffold for agent runtime`
  - Files: `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`
  - Pre-commit: `./gradlew :app:assembleDebug`

- [x] 2. Define core contracts for agent loop, skill spec, and MCP spec

  **What to do**:
  - Create canonical contracts for `AgentTask`, `PolicyDecision`, `SkillSpec`, `McpServerSpec`, and `ExecutionResult`.
  - Define serialization-safe schemas for persistence and restart recovery.
  - Add compatibility notes for Agent Skills core fields and extension hooks.

  **Must NOT do**:
  - Do not implement runtime logic in this task.
  - Do not hardcode vendor-specific prompt text.

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: foundational interfaces block most downstream tasks.
  - **Skills**: `find-skills`
    - `find-skills`: useful when mapping skill schema conventions.
  - **Skills Evaluated but Omitted**:
    - `frontend-ui-ux`: no UI composition needed.

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 1,3,4,5)
  - **Blocks**: Tasks 6,7,8,9,10,11,12,17
  - **Blocked By**: None

  **References**:
  - **Pattern References**:
    - `https://code.claude.com/docs/en/skills` - skill metadata and invocation controls.
    - `https://developers.openai.com/codex/skills` - Agent Skills standard usage and folder model.
    - `https://opencode.ai/docs/skills` - SKILL.md discovery and permission patterns.
  - **API/Type References**:
    - `https://modelcontextprotocol.io/introduction` - MCP object model baseline.
  - **Test References**:
    - `https://developer.android.com/topic/libraries/architecture` - contract-first architecture guidance.
  - **External References**:
    - `https://agentskills.io/specification` - canonical open Agent Skills specification.
  - **WHY Each Reference Matters**:
    - Align schemas with real-world interoperability and avoid lock-in.

  **Acceptance Criteria**:
  - [ ] Contract files compile without implementation code.
  - [ ] Schema validators reject invalid `name/description` skill metadata.
  - [ ] MCP spec supports local/remote transport descriptors.

  **QA Scenarios (MANDATORY)**:

  ```
  Scenario: Happy path — valid schema serialization round-trip
    Tool: Bash
    Preconditions: Contract classes and basic schema tests exist
    Steps:
      1. Run ./gradlew :core:testDebugUnitTest
      2. Assert test output includes serialization round-trip pass
    Expected Result: Valid contract payloads serialize/deserialize losslessly
    Failure Indicators: Missing field mapping, enum mismatch, nullability break
    Evidence: .sisyphus/evidence/task-2-contract-roundtrip.txt

  Scenario: Failure path — invalid skill metadata rejected
    Tool: Bash
    Preconditions: Validator tests include invalid cases (empty description, invalid name)
    Steps:
      1. Run ./gradlew :core:testDebugUnitTest --tests "*SkillSpecValidation*"
      2. Assert tests report deterministic rejection reason
    Expected Result: Invalid metadata is blocked before runtime usage
    Evidence: .sisyphus/evidence/task-2-contract-invalid-skill.txt
  ```

  **Evidence to Capture**:
  - [ ] Unit test logs for valid and invalid schema behavior.

  **Commit**: YES
  - Message: `feat(core): introduce canonical agent, skill, and mcp contracts`
  - Files: `core/src/main/kotlin/contracts/AgentContracts.kt`, `core/src/main/kotlin/contracts/SkillSpec.kt`, `core/src/main/kotlin/contracts/McpSpec.kt`
  - Pre-commit: `./gradlew :core:testDebugUnitTest`

- [x] 3. Implement explicit mode policy matrix and protected-file invariants

  **What to do**:
  - Encode Safe/Auto/Developer mode matrix with deterministic allow/ask/deny outcomes.
  - Add protected-file registry and invariant checks for delete/rename/move.
  - Define explicit non-override minimum rules in Safe mode and override semantics in Developer mode.

  **Must NOT do**:
  - Do not leave policy rules as prose-only; they must be executable.
  - Do not allow path traversal or protected-file bypass in default mode.

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: high-risk security policy foundation.
  - **Skills**: `find-skills`
    - `find-skills`: useful for discovering policy pattern references.
  - **Skills Evaluated but Omitted**:
    - `git-master`: not a git-history task.

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 1,2,4,5)
  - **Blocks**: Tasks 8,9,10,11
  - **Blocked By**: None

  **References**:
  - **Pattern References**:
    - `https://code.claude.com/docs/en/permissions` - tiered permission model.
    - `https://code.claude.com/docs/en/sandboxing` - boundary + escape-hatch considerations.
  - **API/Type References**:
    - Task 2 contract `PolicyDecision` and `ProtectedPathRule` definitions.
  - **Test References**:
    - `https://owasp.org/www-community/attacks/Path_Traversal` - traversal threat patterns.
  - **External References**:
    - `https://developers.openai.com/codex/cli/reference` (approval/sandbox mode parallels).
  - **WHY Each Reference Matters**:
    - Converts abstract “safety-first” into measurable policy behavior.

  **Acceptance Criteria**:
  - [ ] Policy matrix returns deterministic decision for each tool class and mode.
  - [ ] Protected file delete/rename/move attempts are denied in Safe mode.
  - [ ] Path traversal tests are blocked (`../`, symbolic indirection equivalent).

  **QA Scenarios (MANDATORY)**:

  ```
  Scenario: Happy path — policy grants expected operation in Auto mode
    Tool: Bash
    Preconditions: Policy unit tests and fixtures exist
    Steps:
      1. Run ./gradlew :policy:testDebugUnitTest --tests "*ModePolicyMatrix*"
      2. Assert approved operation returns ALLOW
    Expected Result: Matrix behavior matches documented rules
    Failure Indicators: nondeterministic or mismatched decision output
    Evidence: .sisyphus/evidence/task-3-policy-happy.txt

  Scenario: Failure path — protected file bypass attempt denied
    Tool: Bash
    Preconditions: Protected files include agent.md/memory.md/soul.md
    Steps:
      1. Run ./gradlew :policy:testDebugUnitTest --tests "*ProtectedFileInvariant*"
      2. Assert delete/rename/move test cases return DENY_PROTECTED_FILE
    Expected Result: Protected invariants always enforced in default mode
    Evidence: .sisyphus/evidence/task-3-policy-protected-deny.txt
  ```

  **Evidence to Capture**:
  - [ ] Matrix test output and protected-invariant output.

  **Commit**: YES
  - Message: `feat(policy): add executable mode matrix and protected file invariants`
  - Files: `policy/src/main/kotlin/ModePolicy.kt`, `policy/src/main/kotlin/ProtectedRegistry.kt`, `policy/src/test/kotlin/PolicyMatrixTest.kt`
  - Pre-commit: `./gradlew :policy:testDebugUnitTest`

---

- [x] 4. Build persistence primitives and secure credential vault interfaces

  **What to do**:
  - Implement session/memory/soul storage interfaces with deterministic versioned records.
  - Add secure credential abstraction backed by Android Keystore contract.
  - Define persistence migration hooks for future Termux phase metadata.

  **Must NOT do**:
  - Do not store tokens/secrets in plaintext files.
  - Do not tightly couple persistence logic to UI classes.

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
    - Reason: storage and key management are security-sensitive and non-trivial.
  - **Skills**: `find-skills`
    - `find-skills`: useful for discovering security storage templates.
  - **Skills Evaluated but Omitted**:
    - `playwright`: no browser execution needed.

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 1,2,3,5)
  - **Blocks**: Tasks 6,8,11
  - **Blocked By**: None

  **References**:
  - **Pattern References**:
    - `https://developer.android.com/topic/security/data` - secure data storage guidance.
  - **API/Type References**:
    - Task 2 contracts: `SessionState`, `MemoryRecord`, `CredentialRef`.
  - **Test References**:
    - `https://developer.android.com/training/articles/keystore` - keystore behavior and lifecycle.
  - **External References**:
    - `https://code.claude.com/docs/en/memory` - persistent memory considerations.
  - **WHY Each Reference Matters**:
    - Persistent identity is core product behavior and must be secure by default.

  **Acceptance Criteria**:
  - [ ] Session/memory/soul records persist across app restart in tests.
  - [ ] Credentials are retrieved through secure provider abstraction only.
  - [ ] Migration version metadata is present in persisted state.

  **QA Scenarios (MANDATORY)**:

  ```
  Scenario: Happy path — persistent memory survives restart
    Tool: Bash
    Preconditions: Persistence unit tests and instrumentation restart test exist
    Steps:
      1. Run ./gradlew :persistence:testDebugUnitTest
      2. Run ./gradlew :app:connectedDebugAndroidTest --tests "*RestartPersistenceTest*"
      3. Assert previous session/memory records are reloaded
    Expected Result: Agent identity data remains intact after restart
    Failure Indicators: missing records, schema mismatch, migration failure
    Evidence: .sisyphus/evidence/task-4-persistence-restart.txt

  Scenario: Failure path — insecure secret path rejected
    Tool: Bash
    Preconditions: Security tests include plaintext storage attempt
    Steps:
      1. Run ./gradlew :persistence:testDebugUnitTest --tests "*CredentialStoragePolicy*"
      2. Assert insecure storage test returns DENY_INSECURE_SECRET_STORE
    Expected Result: plaintext secret storage attempts are blocked
    Evidence: .sisyphus/evidence/task-4-persistence-secret-deny.txt
  ```

  **Evidence to Capture**:
  - [ ] Persistence restart logs.
  - [ ] Credential policy unit test logs.

  **Commit**: YES
  - Message: `feat(persistence): add durable identity stores and secure secret vault interfaces`
  - Files: `persistence/src/main/kotlin/SessionStore.kt`, `persistence/src/main/kotlin/MemoryStore.kt`, `security/src/main/kotlin/SecretVault.kt`
  - Pre-commit: `./gradlew :persistence:testDebugUnitTest`

- [x] 5. Establish TDD harness and QA evidence pipeline

  **What to do**:
  - Configure Android unit/instrumentation test runners and Python pytest harness.
  - Add shared test fixtures for policy/runtime/skills/mcp scenarios.
  - Add evidence output conventions for `.sisyphus/evidence/task-*` artifacts.

  **Must NOT do**:
  - Do not postpone test harness setup after runtime implementation.
  - Do not rely on manual-only checks.

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: mostly setup and wiring for deterministic test flow.
  - **Skills**: `find-skills`
    - `find-skills`: can help standardize reusable testing templates.
  - **Skills Evaluated but Omitted**:
    - `frontend-ui-ux`: not design-centric.

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 1-4)
  - **Blocks**: Tasks 7,10
  - **Blocked By**: None

  **References**:
  - **Pattern References**:
    - `https://developer.android.com/training/testing/local-tests`
    - `https://developer.android.com/training/testing/espresso`
  - **API/Type References**:
    - pytest docs: `https://docs.pytest.org/`
  - **Test References**:
    - TDD red-green-refactor process from Verification Strategy section.
  - **External References**:
    - `https://code.claude.com/docs/en/common-workflows` (plan + verify cadence inspiration)
  - **WHY Each Reference Matters**:
    - Ensures all later tasks are born testable and evidence-ready.

  **Acceptance Criteria**:
  - [ ] `./gradlew testDebugUnitTest` passes on baseline scaffold.
  - [ ] `./gradlew connectedDebugAndroidTest` executes baseline smoke test.
  - [ ] `pytest -q` executes baseline runtime fixture tests.

  **QA Scenarios (MANDATORY)**:

  ```
  Scenario: Happy path — unified test pipelines execute
    Tool: Bash
    Preconditions: Baseline tests and pytest config committed
    Steps:
      1. Run ./gradlew testDebugUnitTest
      2. Run ./gradlew connectedDebugAndroidTest
      3. Run pytest -q
    Expected Result: all three commands pass with deterministic summary
    Failure Indicators: missing runner, instrumentation not discovered, pytest import error
    Evidence: .sisyphus/evidence/task-5-test-harness-happy.txt

  Scenario: Failure path — malformed fixture fails clearly
    Tool: Bash
    Preconditions: Inject one invalid fixture in controlled test branch
    Steps:
      1. Run pytest -q
      2. Assert failure output pinpoints fixture and line number
    Expected Result: actionable failure diagnostics for rapid fix
    Evidence: .sisyphus/evidence/task-5-test-harness-failure.txt
  ```

  **Evidence to Capture**:
  - [ ] Combined logs from gradle+pytest commands.

  **Commit**: YES
  - Message: `test(infra): set up android and python tdd harness with evidence conventions`
  - Files: `app/build.gradle.kts`, `app/src/androidTest/.../SmokeTest.kt`, `pytest.ini`
  - Pre-commit: `./gradlew testDebugUnitTest && pytest -q`

- [x] 6. Implement agent orchestration queue and session state machine

  **What to do**:
  - Implement single-session serial queue with deterministic task ordering.
  - Persist queue snapshots and resumable state transitions.
  - Add cancellation and retry hooks for downstream runtime calls.

  **Must NOT do**:
  - Do not introduce multi-agent parallel orchestration in V1.
  - Do not bypass persisted state transitions.

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: central orchestration logic affects all execution behavior.
  - **Skills**: `find-skills`
    - `find-skills`: helps identify orchestration loop patterns.
  - **Skills Evaluated but Omitted**:
    - `dev-browser`: not browser automation.

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Wave 2 (core path)
  - **Blocks**: Tasks 12,13,14,17
  - **Blocked By**: Tasks 2,4

  **References**:
  - **Pattern References**:
    - OpenCode queue/lane concept (for inspiration): `https://opencode.ai/docs/agents`
  - **API/Type References**:
    - Task 2 `AgentTask`, `SessionState`, `ExecutionResult` contracts.
  - **Test References**:
    - Restart persistence pattern from Task 4 tests.
  - **External References**:
    - `https://code.claude.com/docs/en/checkpointing`
  - **WHY Each Reference Matters**:
    - Reliable task sequencing is mandatory for deterministic mobile execution.

  **Acceptance Criteria**:
  - [ ] Enqueued tasks execute strictly serial in insertion order.
  - [ ] Queue state survives app restart and resumes correctly.
  - [ ] Cancelled task does not execute side effects after cancel point.

  **QA Scenarios (MANDATORY)**:

  ```
  Scenario: Happy path — serial queue ordering preserved
    Tool: Bash
    Preconditions: Queue integration tests exist with 3 synthetic tasks
    Steps:
      1. Run ./gradlew :core:testDebugUnitTest --tests "*SessionQueueOrdering*"
      2. Assert execution log order equals enqueue order
    Expected Result: strict FIFO behavior with deterministic timestamps
    Failure Indicators: parallel overlap, reordered tasks, missing task completion
    Evidence: .sisyphus/evidence/task-6-queue-order.txt

  Scenario: Failure path — restart mid-queue resumes safely
    Tool: Bash
    Preconditions: Integration test simulates process death after task 1/3
    Steps:
      1. Run ./gradlew :app:connectedDebugAndroidTest --tests "*QueueRestartRecoveryTest*"
      2. Assert pending tasks resume exactly once after restart
    Expected Result: no duplicate side effects and no dropped tasks
    Evidence: .sisyphus/evidence/task-6-queue-restart.txt
  ```

  **Evidence to Capture**:
  - [ ] Queue ordering and restart recovery logs.

  **Commit**: YES
  - Message: `feat(core): add persistent single-session queue orchestration`
  - Files: `core/src/main/kotlin/orchestrator/SessionQueue.kt`, `core/src/main/kotlin/orchestrator/AgentLoop.kt`, `core/src/test/kotlin/SessionQueueTest.kt`
  - Pre-commit: `./gradlew :core:testDebugUnitTest`

---

- [x] 7. Implement in-app Python runtime adapter and dependency installer

  **What to do**:
  - Implement runtime adapter to execute Python scripts inside app-managed environment.
  - Add package installation flow with logging, timeout, and deterministic result model.
  - Persist environment manifest for reproducibility across sessions.

  **Must NOT do**:
  - Do not install packages into uncontrolled/global OS locations.
  - Do not skip package operation audit logs.

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
    - Reason: runtime embedding + package management on Android is implementation-heavy.
  - **Skills**: `find-skills`
    - `find-skills`: useful to locate runtime integration references.
  - **Skills Evaluated but Omitted**:
    - `playwright`: no browser interactions.

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2 (with Tasks 8-10)
  - **Blocks**: Task 17
  - **Blocked By**: Tasks 1,2,5

  **References**:
  - **Pattern References**:
    - `https://developer.android.com/topic/performance/vitals` (resource/time budget awareness).
  - **API/Type References**:
    - Task 2 `ExecutionResult`, `RuntimeAdapter` contract.
  - **Test References**:
    - pytest harness from Task 5.
  - **External References**:
    - Codex shell/runtime model inspiration: `https://developers.openai.com/codex/cli/reference`
  - **WHY Each Reference Matters**:
    - Runtime behavior must be deterministic and auditable under mobile constraints.

  **Acceptance Criteria**:
  - [ ] Python script execution returns structured stdout/stderr/exit metadata.
  - [ ] Package install operation persists dependency manifest with version pins.
  - [ ] Timeout/cancel paths return deterministic error codes.

  **QA Scenarios (MANDATORY)**:

  ```
  Scenario: Happy path — run python script with dependency install
    Tool: Bash
    Preconditions: Runtime adapter and installer tests ready
    Steps:
      1. Run pytest -q python_tests/test_runtime_install_and_exec.py
      2. Assert package install step succeeds and script prints expected output
    Expected Result: dependency installs and script execution complete with exit_code=0
    Failure Indicators: install failure, missing manifest, inconsistent stdout capture
    Evidence: .sisyphus/evidence/task-7-python-happy.txt

  Scenario: Failure path — invalid package fails safely
    Tool: Bash
    Preconditions: Test case requests non-existent package name
    Steps:
      1. Run pytest -q python_tests/test_runtime_invalid_package.py
      2. Assert deterministic INSTALL_ERROR and no environment corruption
    Expected Result: operation fails cleanly and queue continues safely
    Evidence: .sisyphus/evidence/task-7-python-invalid-package.txt
  ```

  **Evidence to Capture**:
  - [ ] Python runtime and installer test outputs.

  **Commit**: YES
  - Message: `feat(runtime): add in-app python execution and package install flow`
  - Files: `runtime/src/main/kotlin/PythonRuntimeAdapter.kt`, `runtime/src/main/kotlin/PipInstaller.kt`, `python_runner/runner.py`
  - Pre-commit: `pytest -q`

- [x] 8. Implement file operations service with rollback journal and boundary guard

  **What to do**:
  - Implement create/write/delete/move operations constrained to approved workspace roots.
  - Add rollback journal for local operations with atomic checkpoints.
  - Enforce protected-file invariants (delete/rename/move lock).

  **Must NOT do**:
  - Do not allow path escape (`../`, equivalent canonical escapes).
  - Do not claim rollback support for remote effects.

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: high-impact safety-critical file mutation logic.
  - **Skills**: `find-skills`
    - `find-skills`: helps compare filesystem guard patterns.
  - **Skills Evaluated but Omitted**:
    - `frontend-ui-ux`: no UI layout work.

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2 (with Tasks 7,9,10)
  - **Blocks**: Tasks 14,15,16
  - **Blocked By**: Tasks 2,3,4

  **References**:
  - **Pattern References**:
    - Task 3 policy and protected registry rules.
  - **API/Type References**:
    - Task 2 `FileOperation`, `RollbackRecord`, `PolicyDecision`.
  - **Test References**:
    - OWASP traversal patterns: `https://owasp.org/www-community/attacks/Path_Traversal`
  - **External References**:
    - Claude sandbox boundary principles: `https://code.claude.com/docs/en/sandboxing`
  - **WHY Each Reference Matters**:
    - File safety is the primary blast-radius control for local autonomous execution.

  **Acceptance Criteria**:
  - [ ] Canonicalized path must always resolve inside approved roots.
  - [ ] Protected files return deterministic `DENY_PROTECTED_FILE` for delete/rename/move.
  - [ ] Local rollback restores pre-operation state after injected failure.

  **QA Scenarios (MANDATORY)**:

  ```
  Scenario: Happy path — local batch write with rollback checkpoint
    Tool: Bash
    Preconditions: File ops integration tests and fixture workspace
    Steps:
      1. Run ./gradlew :filesystem:testDebugUnitTest --tests "*BatchWriteRollback*"
      2. Assert successful write set and checkpoint metadata emitted
    Expected Result: operations commit atomically and journal state is clean
    Failure Indicators: partial writes, missing checkpoint, inconsistent file hashes
    Evidence: .sisyphus/evidence/task-8-fileops-happy.txt

  Scenario: Failure path — protected file rename attempt denied
    Tool: Bash
    Preconditions: protected list includes agent.md/memory.md/soul.md
    Steps:
      1. Run ./gradlew :filesystem:testDebugUnitTest --tests "*ProtectedRenameDenied*"
      2. Assert DENY_PROTECTED_FILE and unchanged file metadata
    Expected Result: invariant preserved
    Evidence: .sisyphus/evidence/task-8-fileops-protected-deny.txt
  ```

  **Evidence to Capture**:
  - [ ] File hash diffs before/after test runs.

  **Commit**: YES
  - Message: `feat(filesystem): add guarded file ops and local rollback journal`
  - Files: `filesystem/src/main/kotlin/FileOpsService.kt`, `filesystem/src/main/kotlin/RollbackJournal.kt`, `filesystem/src/test/kotlin/FileOpsTest.kt`
  - Pre-commit: `./gradlew :filesystem:testDebugUnitTest`

- [x] 9. Implement command executor with mode-based permission gating

  **What to do**:
  - Implement command execution wrapper with allow/ask/deny decision integration.
  - Add command timeout, cancellation, output size caps, and audit trail records.
  - Bind command executor to queue orchestration semantics.

  **Must NOT do**:
  - Do not execute denied commands in background fallback paths.
  - Do not bypass audit logging in Auto/Developer modes.

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: command execution is highest-risk runtime capability.
  - **Skills**: `find-skills`
    - `find-skills`: useful for command policy examples.
  - **Skills Evaluated but Omitted**:
    - `dev-browser`: unrelated domain.

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2 (with Tasks 7,8,10)
  - **Blocks**: Tasks 14,16,17
  - **Blocked By**: Tasks 2,3

  **References**:
  - **Pattern References**:
    - Task 3 mode policy matrix.
  - **API/Type References**:
    - Task 2 `CommandRequest`, `PolicyDecision`, `ExecutionResult`.
  - **Test References**:
    - Codex approval/sandbox flags: `https://developers.openai.com/codex/cli/reference`
  - **External References**:
    - Claude permission rule precedence: `https://code.claude.com/docs/en/permissions`
  - **WHY Each Reference Matters**:
    - Prevents unsafe command drift while preserving user-chosen automation level.

  **Acceptance Criteria**:
  - [ ] Denied command class never executes process spawn.
  - [ ] Ask-mode commands require explicit approval token/event.
  - [ ] Timeout and output-limit violations produce deterministic status.

  **QA Scenarios (MANDATORY)**:

  ```
  Scenario: Happy path — allowed command executes and audits
    Tool: Bash
    Preconditions: command policy fixtures and approval token injector available
    Steps:
      1. Run ./gradlew :runtime:testDebugUnitTest --tests "*AllowedCommandExecution*"
      2. Assert exit_code=0 and audit event persisted
    Expected Result: command executes once with full audit metadata
    Failure Indicators: missing audit record, duplicate execution, wrong status mapping
    Evidence: .sisyphus/evidence/task-9-command-happy.txt

  Scenario: Failure path — denied command is blocked pre-exec
    Tool: Bash
    Preconditions: denied command fixture configured
    Steps:
      1. Run ./gradlew :runtime:testDebugUnitTest --tests "*DeniedCommandNoSpawn*"
      2. Assert status DENY_POLICY and spawn count = 0
    Expected Result: command never reaches process layer
    Evidence: .sisyphus/evidence/task-9-command-deny.txt
  ```

  **Evidence to Capture**:
  - [ ] Command audit logs and denial logs.

  **Commit**: YES
  - Message: `feat(runtime): add mode-aware command executor with audit and limits`
  - Files: `runtime/src/main/kotlin/CommandExecutor.kt`, `runtime/src/main/kotlin/ModeGate.kt`, `runtime/src/test/kotlin/CommandExecutorTest.kt`
  - Pre-commit: `./gradlew :runtime:testDebugUnitTest`

---

- [x] 10. Implement Skills engine core (Agent Skills standard + extensions)

  **What to do**:
  - Implement skill discovery from configured roots and parse `SKILL.md` metadata.
  - Support Agent Skills core fields plus selected V1 extensions: tool permissions, invocation controls, subagent execution fields.
  - Add creation-time validation (name regex, description length, schema checks) before activation.

  **Must NOT do**:
  - Do not accept malformed skills into active registry.
  - Do not embed GPL prompt dumps verbatim into bundled defaults.

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
    - Reason: compatibility logic and parser validation are moderately complex.
  - **Skills**: `find-skills`
    - `find-skills`: aligns with skill-ecosystem mapping work.
  - **Skills Evaluated but Omitted**:
    - `git-master`: no repository history task.

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2 (with Tasks 7-9)
  - **Blocks**: Tasks 13,16
  - **Blocked By**: Tasks 2,3,5

  **References**:
  - **Pattern References**:
    - Claude skills spec page: `https://code.claude.com/docs/en/skills`
    - Codex skills page: `https://developers.openai.com/codex/skills`
    - OpenCode skills page: `https://opencode.ai/docs/skills`
  - **API/Type References**:
    - Task 2 `SkillSpec` schema contracts.
  - **Test References**:
    - Agent Skills spec baseline: `https://agentskills.io/specification`
  - **External References**:
    - Plugin distribution model inspiration: `https://code.claude.com/docs/en/plugins`
  - **WHY Each Reference Matters**:
    - Guarantees portable skill packaging and predictable invocation semantics.

  **Acceptance Criteria**:
  - [ ] Valid `SKILL.md` loads into registry with normalized metadata.
  - [ ] Invalid metadata is rejected with explicit reason.
  - [ ] Invocation-control flags are respected (implicit/explicit behavior).

  **QA Scenarios (MANDATORY)**:

  ```
  Scenario: Happy path — valid skill discovered and invoked explicitly
    Tool: Bash
    Preconditions: sample valid skill fixture in test skill directory
    Steps:
      1. Run ./gradlew :skills:testDebugUnitTest --tests "*SkillDiscoveryAndInvoke*"
      2. Assert skill appears in registry and executes expected instruction path
    Expected Result: registry includes normalized skill metadata and invocation succeeds
    Failure Indicators: missing skill, wrong parser output, invocation mismatch
    Evidence: .sisyphus/evidence/task-10-skills-happy.txt

  Scenario: Failure path — malformed skill blocked at validation stage
    Tool: Bash
    Preconditions: malformed skill fixture (bad name/empty description)
    Steps:
      1. Run ./gradlew :skills:testDebugUnitTest --tests "*SkillValidationRejectsMalformed*"
      2. Assert error code INVALID_SKILL_METADATA and no registry entry
    Expected Result: malformed skill never becomes invokable
    Evidence: .sisyphus/evidence/task-10-skills-invalid.txt
  ```

  **Evidence to Capture**:
  - [ ] Skills parser and validator test logs.

  **Commit**: YES
  - Message: `feat(skills): implement agent-skills compatible loader and validator`
  - Files: `skills/src/main/kotlin/SkillLoader.kt`, `skills/src/main/kotlin/SkillValidator.kt`, `skills/src/test/kotlin/SkillEngineTest.kt`
  - Pre-commit: `./gradlew :skills:testDebugUnitTest`

- [x] 11. Implement MCP manager core with persisted trust and auth state

  **What to do**:
  - Implement local stdio and remote HTTP/SSE MCP server registration.
  - Add user manual enable flow and persistent trust state for non-whitelisted servers.
  - Persist auth metadata and status transitions with secure secret references.

  **Must NOT do**:
  - Do not auto-enable unknown MCP servers silently.
  - Do not store raw auth secrets in plaintext.

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
    - Reason: protocol integration + auth state lifecycle complexity.
  - **Skills**: `find-skills`
    - `find-skills`: helps choose proven MCP integration patterns.
  - **Skills Evaluated but Omitted**:
    - `playwright`: not a browser automation task.

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3 (with Tasks 12-14)
  - **Blocks**: Tasks 16,17
  - **Blocked By**: Tasks 2,3,4

  **References**:
  - **Pattern References**:
    - Claude MCP docs: `https://code.claude.com/docs/en/mcp`
    - Codex MCP docs: `https://developers.openai.com/codex/mcp`
    - OpenCode MCP docs: `https://opencode.ai/docs/mcp-servers`
  - **API/Type References**:
    - Task 2 `McpServerSpec`, `McpAuthState`.
  - **Test References**:
    - MCP protocol conceptual baseline: `https://modelcontextprotocol.io/introduction`
  - **External References**:
    - Official MCP servers list (ecosystem context): `https://github.com/modelcontextprotocol/servers`
  - **WHY Each Reference Matters**:
    - Ensures compatibility while preserving user-controlled trust and safety.

  **Acceptance Criteria**:
  - [ ] MCP server add/remove/enable/disable state persists across restart.
  - [ ] Non-whitelisted server requires explicit user enable before tool exposure.
  - [ ] Auth state transitions are represented without plaintext credential leakage.

  **QA Scenarios (MANDATORY)**:

  ```
  Scenario: Happy path — user enables MCP server and state persists
    Tool: Bash
    Preconditions: MCP manager integration tests with local fixture server
    Steps:
      1. Run ./gradlew :mcp:testDebugUnitTest --tests "*ManualEnablePersistence*"
      2. Assert server state remains enabled after simulated restart
    Expected Result: enabled server persists and tools become available
    Failure Indicators: lost state, unexpected auto-disable, missing registry entry
    Evidence: .sisyphus/evidence/task-11-mcp-happy.txt

  Scenario: Failure path — non-whitelisted server remains blocked without consent
    Tool: Bash
    Preconditions: test includes unknown remote server fixture
    Steps:
      1. Run ./gradlew :mcp:testDebugUnitTest --tests "*UnknownServerNeedsManualEnable*"
      2. Assert tools are hidden until explicit consent event occurs
    Expected Result: deny-by-default behavior for unknown server trust
    Evidence: .sisyphus/evidence/task-11-mcp-blocked.txt
  ```

  **Evidence to Capture**:
  - [ ] MCP registry and persistence logs.

  **Commit**: YES
  - Message: `feat(mcp): add managed mcp registry with persisted trust state`
  - Files: `mcp/src/main/kotlin/McpRegistry.kt`, `mcp/src/main/kotlin/McpClientFactory.kt`, `mcp/src/test/kotlin/McpManagerTest.kt`
  - Pre-commit: `./gradlew :mcp:testDebugUnitTest`

- [x] 12. Integrate LiteLLM provider gateway and routing profiles

  **What to do**:
  - Implement LiteLLM gateway adapter with provider/profile routing.
  - Add fallback policy for provider timeout/rate-limit failures.
  - Persist model profile selection in session settings.

  **Must NOT do**:
  - Do not hardcode one provider endpoint.
  - Do not leak API keys into logs.

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: adapter mapping and routing profile wiring.
  - **Skills**: `find-skills`
    - `find-skills`: useful for discovering provider integration guidance.
  - **Skills Evaluated but Omitted**:
    - `frontend-ui-ux`: no UI painting in this task.

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3 (with Tasks 11,13,14)
  - **Blocks**: Task 14
  - **Blocked By**: Tasks 2,6

  **References**:
  - **Pattern References**:
    - LiteLLM provider model docs (external official reference to be pinned by implementer).
  - **API/Type References**:
    - Task 2 `ModelProfile`, `ProviderRoute`, `ExecutionResult`.
  - **Test References**:
    - Rate-limit handling patterns from Metis-identified edge cases.
  - **External References**:
    - Codex/OpenCode provider-agnostic strategy inspiration.
  - **WHY Each Reference Matters**:
    - Multi-provider resilience is a core accepted requirement.

  **Acceptance Criteria**:
  - [ ] Provider profile switching works without app restart.
  - [ ] 429/timeout triggers deterministic fallback or clear terminal failure.
  - [ ] Secrets are redacted in all request/response logs.

  **QA Scenarios (MANDATORY)**:

  ```
  Scenario: Happy path — provider routing profile executes request
    Tool: Bash
    Preconditions: gateway tests with two mock providers
    Steps:
      1. Run ./gradlew :llm:testDebugUnitTest --tests "*ProviderRouteSelection*"
      2. Assert selected provider receives request and returns structured response
    Expected Result: correct route chosen and response mapped consistently
    Failure Indicators: wrong provider route, schema mismatch, missing metadata
    Evidence: .sisyphus/evidence/task-12-llm-happy.txt

  Scenario: Failure path — primary provider rate-limited triggers fallback
    Tool: Bash
    Preconditions: mock primary provider returns 429
    Steps:
      1. Run ./gradlew :llm:testDebugUnitTest --tests "*RateLimitFallback*"
      2. Assert fallback provider path engaged (or deterministic fail policy)
    Expected Result: no silent drop; outcome is explicit and auditable
    Evidence: .sisyphus/evidence/task-12-llm-fallback.txt
  ```

  **Evidence to Capture**:
  - [ ] LLM route and fallback test logs.

  **Commit**: YES
  - Message: `feat(llm): add litellm gateway with profile routing and fallback`
  - Files: `llm/src/main/kotlin/LiteLlmGateway.kt`, `llm/src/main/kotlin/ProviderRouting.kt`, `llm/src/test/kotlin/LiteLlmGatewayTest.kt`
  - Pre-commit: `./gradlew :llm:testDebugUnitTest`

---

- [x] 13. Build Skills Management UI (CRUD + lifecycle actions)

  **What to do**:
  - Implement screens and view models for create/edit/disable/delete skill entries.
  - Add install/uninstall and import/export flows for directory package + Git source.
  - Reflect invocation control and permission metadata in edit forms.

  **Must NOT do**:
  - Do not auto-activate unvalidated skills.
  - Do not hide validation errors from the user.

  **Recommended Agent Profile**:
  - **Category**: `visual-engineering`
    - Reason: UI state-heavy feature with interaction flows.
  - **Skills**: `frontend-ui-ux`
    - `frontend-ui-ux`: helps produce clear management flows and error states.
  - **Skills Evaluated but Omitted**:
    - `playwright`: browser skill not primary for native Android UI implementation.

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3 (with Tasks 11,12,14)
  - **Blocks**: Task 16
  - **Blocked By**: Tasks 6,10

  **References**:
  - **Pattern References**:
    - Claude skill invocation controls: `https://code.claude.com/docs/en/skills`
    - OpenCode skill metadata constraints: `https://opencode.ai/docs/skills`
  - **API/Type References**:
    - Task 2 `SkillSpec` + Task 10 validator outputs.
  - **Test References**:
    - Android UI testing docs: `https://developer.android.com/training/testing/espresso`
  - **External References**:
    - Codex skills UX expectations: `https://developers.openai.com/codex/skills`
  - **WHY Each Reference Matters**:
    - UI must expose interoperable skill semantics instead of ad-hoc local-only behavior.

  **Acceptance Criteria**:
  - [ ] User can create, edit, disable, delete skills through UI.
  - [ ] User can install/uninstall and import/export skill packages.
  - [ ] Invalid skill metadata produces actionable UI validation feedback.

  **QA Scenarios (MANDATORY)**:

  ```
  Scenario: Happy path — full skill lifecycle via UI
    Tool: Bash
    Preconditions: Android instrumentation tests for SkillsScreen in place
    Steps:
      1. Run ./gradlew :app:connectedDebugAndroidTest --tests "*SkillsManagementLifecycleTest*"
      2. Assert create→disable→edit→export path succeeds
    Expected Result: lifecycle actions persist and UI state reflects changes immediately
    Failure Indicators: stale list state, missing persisted updates, failed export artifact
    Evidence: .sisyphus/evidence/task-13-skills-ui-happy.txt

  Scenario: Failure path — invalid metadata blocked in editor
    Tool: Bash
    Preconditions: test enters malformed skill name and empty description
    Steps:
      1. Run ./gradlew :app:connectedDebugAndroidTest --tests "*SkillsEditorValidationTest*"
      2. Assert save action blocked and inline error shown
    Expected Result: invalid skill cannot be activated
    Evidence: .sisyphus/evidence/task-13-skills-ui-invalid.txt
  ```

  **Evidence to Capture**:
  - [ ] Instrumentation test logs + screenshots of lifecycle states.

  **Commit**: YES
  - Message: `feat(ui-skills): deliver complete skills lifecycle management screens`
  - Files: `ui/src/main/kotlin/skills/SkillsScreen.kt`, `ui/src/main/kotlin/skills/SkillEditorViewModel.kt`, `ui/src/androidTest/kotlin/SkillsManagementTest.kt`
  - Pre-commit: `./gradlew :app:connectedDebugAndroidTest --tests "*Skills*"`

- [x] 14. Build main interaction UI (chat, timeline, approvals, mode settings)

  **What to do**:
  - Implement conversation screen with action timeline and queue visibility.
  - Add approval prompts for ask-mode operations and status chips for allow/deny results.
  - Add settings panel for Safe/Auto/Developer mode switching.
  - Add explicit "Reset Agent Identity" action (session memory/soul reset with confirmation flow).

  **Must NOT do**:
  - Do not hide policy decision context from users.
  - Do not introduce multi-session orchestration controls.

  **Recommended Agent Profile**:
  - **Category**: `visual-engineering`
    - Reason: complex interaction UX and status-driven components.
  - **Skills**: `frontend-ui-ux`
    - `frontend-ui-ux`: required for clear approval and audit UX.
  - **Skills Evaluated but Omitted**:
    - `dev-browser`: browser automation irrelevant for Android-native UI.

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3 (with Tasks 11-13)
  - **Blocks**: Tasks 15,16
  - **Blocked By**: Tasks 6,8,9,12

  **References**:
  - **Pattern References**:
    - Claude permission modes and rationale: `https://code.claude.com/docs/en/permissions`
    - OpenCode plan/build mode affordances: `https://opencode.ai/docs/agents`
  - **API/Type References**:
    - Task 6 queue state and Task 9 policy decisions.
  - **Test References**:
    - Espresso UI interaction testing patterns.
  - **External References**:
    - Codex command mode semantics for user mental model consistency.
  - **WHY Each Reference Matters**:
    - UX clarity is critical when giving users explicit control over risk.

  **Acceptance Criteria**:
  - [ ] Timeline shows operation attempts with policy decisions and result statuses.
  - [ ] Ask-mode operation requires user approval interaction.
  - [ ] Mode switch takes effect in subsequent operation requests.

  **QA Scenarios (MANDATORY)**:

  ```
  Scenario: Happy path — approval flow executes queued operation
    Tool: Bash
    Preconditions: instrumented UI test with synthetic ask-mode command
    Steps:
      1. Run ./gradlew :app:connectedDebugAndroidTest --tests "*ApprovalFlowExecutesAfterConsent*"
      2. Assert pending action changes to running then succeeded
    Expected Result: operation only runs after explicit consent
    Failure Indicators: auto-execution without consent, stale pending status
    Evidence: .sisyphus/evidence/task-14-main-ui-approval.txt

  Scenario: Failure path — denied operation surfaced transparently
    Tool: Bash
    Preconditions: denied policy fixture configured
    Steps:
      1. Run ./gradlew :app:connectedDebugAndroidTest --tests "*DeniedOperationVisibleInTimeline*"
      2. Assert timeline row shows DENY_POLICY and error reason text
    Expected Result: user sees clear denial reason and no hidden failure
    Evidence: .sisyphus/evidence/task-14-main-ui-deny.txt
  ```

  **Evidence to Capture**:
  - [ ] Timeline screenshots and instrumentation logs.

  **Commit**: YES
  - Message: `feat(ui-core): add conversation timeline approvals and mode settings`
  - Files: `ui/src/main/kotlin/chat/ChatScreen.kt`, `ui/src/main/kotlin/timeline/ActionTimeline.kt`, `ui/src/androidTest/kotlin/MainFlowUiTest.kt`
  - Pre-commit: `./gradlew :app:connectedDebugAndroidTest --tests "*MainFlow*"`

- [x] 15. Integrate SAF workspace bridge with boundary enforcement

  **What to do**:
  - Add SAF-based workspace picker and permission grant persistence.
  - Enforce workspace operations only within granted document roots.
  - Handle revoked SAF permissions gracefully with recoverable error state.

  **Must NOT do**:
  - Do not silently keep stale SAF grants after revocation.
  - Do not bypass path boundary checks for SAF URIs.

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
    - Reason: Android storage APIs + policy enforcement edge cases.
  - **Skills**: `find-skills`
    - `find-skills`: helps source proven SAF handling patterns.
  - **Skills Evaluated but Omitted**:
    - `playwright`: not browser context.

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Wave 4
  - **Blocks**: Task 16
  - **Blocked By**: Tasks 8,14

  **References**:
  - **Pattern References**:
    - Android SAF docs: `https://developer.android.com/training/data-storage/shared/documents-files`
  - **API/Type References**:
    - Task 8 path/boundary guard contracts.
  - **Test References**:
    - Revoked permission edge-case expectations from Metis gap review.
  - **External References**:
    - Claude sandbox boundary principle mapping (conceptual parity).
  - **WHY Each Reference Matters**:
    - SAF is required by your chosen filesystem scope and is a major boundary surface.

  **Acceptance Criteria**:
  - [ ] User can grant SAF directory; grant persists and is visible in settings.
  - [ ] Operations outside granted roots are denied.
  - [ ] Revoked permission state is detected and surfaced with recovery path.

  **QA Scenarios (MANDATORY)**:

  ```
  Scenario: Happy path — SAF grant allows in-root file operation
    Tool: Bash
    Preconditions: instrumentation test can simulate SAF grant
    Steps:
      1. Run ./gradlew :app:connectedDebugAndroidTest --tests "*SafGrantAllowsInRootOps*"
      2. Assert in-root write succeeds and timeline logs operation
    Expected Result: granted SAF root becomes usable workspace extension
    Failure Indicators: false deny, missing grant persistence, operation crash
    Evidence: .sisyphus/evidence/task-15-saf-happy.txt

  Scenario: Failure path — revoked grant blocks operation and prompts re-authorize
    Tool: Bash
    Preconditions: test simulates permission revocation after prior success
    Steps:
      1. Run ./gradlew :app:connectedDebugAndroidTest --tests "*SafRevocationRecoveryTest*"
      2. Assert operation returns PERMISSION_REVOKED and re-grant prompt appears
    Expected Result: graceful fail and guided recovery
    Evidence: .sisyphus/evidence/task-15-saf-revoked.txt
  ```

  **Evidence to Capture**:
  - [ ] SAF grant/revoke test logs and screenshots.

  **Commit**: YES
  - Message: `feat(files): add saf workspace bridge with revocation-safe enforcement`
  - Files: `filesystem/src/main/kotlin/SafWorkspaceBridge.kt`, `ui/src/main/kotlin/files/WorkspacePickerScreen.kt`, `app/src/androidTest/kotlin/SafIntegrationTest.kt`
  - Pre-commit: `./gradlew :app:connectedDebugAndroidTest --tests "*Saf*"`

---

- [x] 16. Deliver end-to-end security and persistence test suite

  **What to do**:
  - Build E2E scenarios for policy matrix, protected-file invariants, path traversal defense, queue restart persistence, and skills/MCP state persistence.
  - Add explicit tests for local rollback success and remote rollback non-guarantee markers.
  - Integrate suite into CI gates.

  **Must NOT do**:
  - Do not leave critical security paths covered only by manual tests.
  - Do not use vague pass criteria.

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: broad integration coverage and cross-module assertions.
  - **Skills**: `find-skills`
    - `find-skills`: can assist with reusable E2E test pattern discovery.
  - **Skills Evaluated but Omitted**:
    - `frontend-ui-ux`: not focused on visual design.

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Wave 4
  - **Blocks**: Task 18, FINAL
  - **Blocked By**: Tasks 8,9,10,11,13,14,15

  **References**:
  - **Pattern References**:
    - Metis gap checklist on missing acceptance criteria.
  - **API/Type References**:
    - Contracts and policy decisions from Tasks 2 and 3.
  - **Test References**:
    - Android instrumentation + pytest harness from Task 5.
  - **External References**:
    - Security baseline from Claude sandbox/permission docs.
  - **WHY Each Reference Matters**:
    - This is the objective proof that risk controls and persistence actually work.

  **Acceptance Criteria**:
  - [ ] E2E suite verifies protected-file and traversal defenses.
  - [ ] E2E suite verifies queue recovery after restart and no duplicate side effects.
  - [ ] E2E suite verifies skills/MCP enable-state persistence and enforcement.

  **QA Scenarios (MANDATORY)**:

  ```
  Scenario: Happy path — complete policy and persistence e2e pack passes
    Tool: Bash
    Preconditions: all dependent modules integrated
    Steps:
      1. Run ./gradlew :app:connectedDebugAndroidTest --tests "*SecurityPolicyE2E*"
      2. Run pytest -q python_tests/test_runtime_policy_e2e.py
      3. Assert all listed checks pass
    Expected Result: critical controls validated end-to-end
    Failure Indicators: flaky policy outcomes, restart state divergence, missing artifacts
    Evidence: .sisyphus/evidence/task-16-e2e-happy.txt

  Scenario: Failure path — injected traversal payload blocked in e2e
    Tool: Bash
    Preconditions: traversal attack fixture included
    Steps:
      1. Run ./gradlew :app:connectedDebugAndroidTest --tests "*TraversalAttackBlockedE2E*"
      2. Assert decision DENY_PATH_ESCAPE and no side-effect file writes
    Expected Result: boundary bypass attempt is rejected deterministically
    Evidence: .sisyphus/evidence/task-16-e2e-traversal-deny.txt
  ```

  **Evidence to Capture**:
  - [ ] Full e2e run logs and failure fixture logs.

  **Commit**: YES
  - Message: `test(e2e): add security, rollback, and persistence integration suites`
  - Files: `app/src/androidTest/kotlin/SecurityPolicyE2ETest.kt`, `app/src/androidTest/kotlin/RestartPersistenceTest.kt`, `python_tests/test_runtime_policy_e2e.py`
  - Pre-commit: `./gradlew connectedDebugAndroidTest && pytest -q`

- [x] 17. Add Termux-phase runtime adapter contract and parity scaffolding

  **What to do**:
  - Define `TermuxRuntimeAdapter` interface and parity contract with in-app runtime.
  - Add contract tests that assert identical request/response semantics across adapters.
  - Produce phase document listing what stays V1-scaffold-only vs Phase 2 implementation.

  **Must NOT do**:
  - Do not implement full Termux runtime execution in V1.
  - Do not alter V1 app behavior to depend on unavailable Termux layer.

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: forward-compatibility design with strict non-creep constraint.
  - **Skills**: `find-skills`
    - `find-skills`: useful for adapter contract design references.
  - **Skills Evaluated but Omitted**:
    - `playwright`: unrelated domain.

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 4 (with Task 16)
  - **Blocks**: Task 18, FINAL
  - **Blocked By**: Tasks 2,6,7,9,11

  **References**:
  - **Pattern References**:
    - Runtime adapter contracts from Task 2 and Task 7.
  - **API/Type References**:
    - MCP and command execution result schemas.
  - **Test References**:
    - Parity-style contract testing patterns in adapter architectures.
  - **External References**:
    - OpenCode client/server and multi-runtime inspiration (`https://github.com/anomalyco/opencode`).
  - **WHY Each Reference Matters**:
    - Prevents future rewrite by freezing interoperable runtime contract now.

  **Acceptance Criteria**:
  - [ ] Termux adapter interface compiles and satisfies parity contract tests with in-app adapter mock.
  - [ ] Phase document clearly lists deferred Termux implementation scope.
  - [ ] No production execution path requires Termux in V1.

  **QA Scenarios (MANDATORY)**:

  ```
  Scenario: Happy path — runtime parity contract passes against adapter stubs
    Tool: Bash
    Preconditions: parity tests target in-app + termux stub adapters
    Steps:
      1. Run ./gradlew :runtime:testDebugUnitTest --tests "*RuntimeAdapterParity*"
      2. Assert same input produces equivalent normalized output envelope
    Expected Result: adapter contract stable for phase-2 runtime swap
    Failure Indicators: schema divergence, status mismatch, missing fields
    Evidence: .sisyphus/evidence/task-17-termux-contract-happy.txt

  Scenario: Failure path — accidental termux hard dependency detected
    Tool: Bash
    Preconditions: static checks include forbidden direct termux invocation in V1 paths
    Steps:
      1. Run ./gradlew :runtime:testDebugUnitTest --tests "*NoTermuxHardDependencyInV1*"
      2. Assert test fails if V1 path requires Termux availability
    Expected Result: V1 remains independent from Termux runtime presence
    Evidence: .sisyphus/evidence/task-17-termux-contract-failure-guard.txt
  ```

  **Evidence to Capture**:
  - [ ] Parity test logs and phase-scope checklist.

  **Commit**: YES
  - Message: `chore(runtime): add termux-phase adapter contract and parity test scaffolding`
  - Files: `runtime/src/main/kotlin/TermuxRuntimeAdapterContract.kt`, `runtime/src/test/kotlin/RuntimeAdapterParityTest.kt`, `docs/termux-phase.md`
  - Pre-commit: `./gradlew :runtime:testDebugUnitTest`

- [x] 18. Release hardening and in-app operational guardrails documentation

  **What to do**:
  - Add release checklist and in-app safety/limits screen.
  - Add telemetry/privacy toggles aligned with persistent identity model.
  - Confirm V1 out-of-scope banner and operational warnings are visible in settings/help.

  **Must NOT do**:
  - Do not expose hidden unsafe defaults without warning.
  - Do not ship undocumented developer-mode risk behavior.

  **Recommended Agent Profile**:
  - **Category**: `writing`
    - Reason: this is release governance + user-facing operational clarity.
  - **Skills**: `frontend-ui-ux`
    - `frontend-ui-ux`: useful for legible warning and settings surfaces.
  - **Skills Evaluated but Omitted**:
    - `git-master`: no history surgery task.

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Wave 4 terminal task
  - **Blocks**: FINAL
  - **Blocked By**: Tasks 16,17

  **References**:
  - **Pattern References**:
    - Task 3 policy matrix and Task 16 verified behaviors.
  - **API/Type References**:
    - Settings model for mode, telemetry, and privacy toggles.
  - **Test References**:
    - UI instrumentation for settings/help screens.
  - **External References**:
    - Claude security best-practice transparency patterns.
  - **WHY Each Reference Matters**:
    - Makes risk posture explicit and reduces unsafe user expectations.

  **Acceptance Criteria**:
  - [ ] Release checklist exists and maps to all critical risk controls.
  - [ ] In-app settings/help surfaces clearly explain mode risks and rollback limits.
  - [ ] Telemetry and privacy toggles are present and test-verified.

  **QA Scenarios (MANDATORY)**:

  ```
  Scenario: Happy path — settings/help shows operational guardrails clearly
    Tool: Bash
    Preconditions: guardrail screens implemented in UI
    Steps:
      1. Run ./gradlew :app:connectedDebugAndroidTest --tests "*SafetyAndLimitsScreenTest*"
      2. Assert required warning strings and toggle states are rendered
    Expected Result: users can see and change operational/privacy controls
    Failure Indicators: missing warning text, hidden toggle, inconsistent defaults
    Evidence: .sisyphus/evidence/task-18-release-ui-happy.txt

  Scenario: Failure path — undocumented developer-mode behavior blocked by test
    Tool: Bash
    Preconditions: release checklist validation test includes required disclosure fields
    Steps:
      1. Run ./gradlew :app:testDebugUnitTest --tests "*DeveloperModeDisclosureRequired*"
      2. Assert failure when disclosure entry missing
    Expected Result: release fails until risk disclosure is complete
    Evidence: .sisyphus/evidence/task-18-release-disclosure-guard.txt
  ```

  **Evidence to Capture**:
  - [ ] Settings/help screenshots + checklist validation logs.

  **Commit**: YES
  - Message: `docs(release): add operational guardrails, privacy toggles, and safety disclosures`
  - Files: `docs/release-checklist.md`, `ui/src/main/kotlin/help/SafetyAndLimitsScreen.kt`, `ui/src/main/kotlin/settings/TelemetryToggles.kt`
  - Pre-commit: `./gradlew :app:testDebugUnitTest`

---

## Final Verification Wave (MANDATORY)

- [x] F1. **Plan Compliance Audit** — `oracle`
  - Verify all Must Have/Must NOT Have and task evidence artifacts.
  - Output: `Must Have [N/N] | Must NOT Have [N/N] | VERDICT`

- [x] F2. **Code Quality Review** — `unspecified-high`
  - Run type/lint/test gates; detect risky anti-patterns and slop.
  - Output: `Build [PASS/FAIL] | Lint [PASS/FAIL] | Tests [PASS/FAIL] | VERDICT`

- [x] F3. **Real QA Replay** — `unspecified-high`
  - Re-run every task QA scenario; store artifacts under `.sisyphus/evidence/final-qa/`.
  - Output: `Scenarios [N/N] | Integration [PASS/FAIL] | VERDICT`

- [x] F4. **Scope Fidelity Check** — `deep`
  - Ensure no out-of-scope creep and no missing in-scope deliverables.
  - Output: `Compliant [N/N] | Out-of-scope [0] | VERDICT`

---

## Commit Strategy

- Wave 1 foundation commit group
- Wave 2 runtime core commit group
- Wave 3 integration/UI commit group
- Wave 4 hardening/bridge commit group
- Final verification fixes commit group (only if needed)

Message style: `type(scope): why-focused summary`

---

## Success Criteria

### Verification Commands

```bash
./gradlew testDebugUnitTest            # Expected: BUILD SUCCESSFUL
./gradlew connectedDebugAndroidTest    # Expected: all instrumentation tests pass
pytest -q                              # Expected: all Python runtime tests pass
```

### Final Checklist
- [x] All Must Have present
- [x] All Must NOT Have absent
- [x] Security and policy tests pass
- [x] Skills/MCP lifecycle and persistence pass
- [x] QA evidence present for every task
