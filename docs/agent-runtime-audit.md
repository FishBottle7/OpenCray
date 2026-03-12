# Agent Runtime Audit

Audit date: 2026-03-11

## Scope

This note audits the current repository state for three questions:

1. Has OpenCray already implemented an OpenClaw-like agent loop?
2. Can the agent freely access workspace content and installed software directories?
3. Which tools or tool-like capability modules currently exist in the codebase?

The conclusions below describe the code that exists in this repository on 2026-03-11. They do not assume future host wiring or deferred runtime work.

## Executive summary

| Topic | Current status | Short conclusion |
| --- | --- | --- |
| OpenClaw-like agent loop | Partial skeleton only | There is a serial session queue and task lifecycle model, but no production multi-turn thought-action-observation loop. |
| LLM integration into agent runtime | Not wired end-to-end | `DefaultLiteLlmGateway` exists as a standalone gateway, but it is not connected to `AgentLoop` as a real autonomous runtime. |
| Workspace access | Restricted | File and Python execution paths are explicitly constrained to approved roots or workspace-local paths. |
| Installed software directory access | Not generally allowed as a file capability | File tooling is bounded to approved roots. Command execution is looser, but still policy-gated and not wired into a completed agent loop. |
| Tooling model | Mixed maturity | Several capability modules exist, but there is no single production tool registry or unified tool dispatcher like OpenClaw. |
| Termux or external runtime | Stub only in V1 | Real Termux execution is explicitly out of scope for the current V1 contract. |

## Agent loop status

| Area | Evidence | Status | Notes |
| --- | --- | --- | --- |
| Agent loop facade | `core/src/main/kotlin/com/opencray/core/orchestrator/AgentLoop.kt` | Implemented as a thin wrapper | `AgentLoop` forwards to `SessionQueue` and exposes `submit`, `runUntilIdle`, `cancel`, `retry`, `stop`, `resume`, and `snapshot`. |
| Session execution queue | `core/src/main/kotlin/com/opencray/core/orchestrator/SessionQueue.kt` | Implemented | FIFO serial execution, retry, cancellation, stop/resume, restart-safe snapshot persistence. |
| Task contract | `core/src/main/kotlin/com/opencray/core/contracts/AgentContracts.kt` | Implemented | Task types include `PROMPT`, `TOOL_CALL`, `SKILL_CALL`, and `SYSTEM`, but this is a contract layer, not a fully wired dispatcher. |
| Runtime abstraction | `SessionTaskRuntime` in `SessionQueue.kt` | Implemented as interface only | The queue delegates to a runtime callback, but the repository does not wire a production agent runtime that loops across LLM and tools. |
| Multi-turn tool loop | No production implementation found | Missing | No repository evidence of a completed cycle that repeatedly does model inference, tool selection, tool execution, result injection, and continuation until completion. |
| Production chat host wiring | `ui/src/main/kotlin/com/opencray/ui/chat/ChatScreen.kt`, `app/src/main/kotlin/com/opencray/app/AppShellActivity.kt` | Placeholder or seeded | The chat surface uses seeded state and explicit placeholder copy rather than live agent execution state. |
| Restart-safe session recovery | `persistence/src/main/kotlin/com/opencray/persistence/store/SessionStoreQueueSnapshotStore.kt` and android tests | Implemented | Queue snapshots persist and restore correctly, but that is orchestration infrastructure rather than a complete autonomous agent loop. |

## OpenClaw-style loop gap assessment

| Capability expected from an OpenClaw-like runtime | Current state | Gap |
| --- | --- | --- |
| Model receives prompt plus tool schema | Not found in production loop | Missing host/runtime integration |
| Model emits tool call or next action | Contract types exist, but no completed routing layer found | Missing dispatcher |
| Tool executes and returns structured observation | Individual modules exist | Not unified under a production agent runtime |
| Observation is fed back into the next model turn | Not found | Missing |
| Loop continues until terminal answer or stop condition | Queue can drain tasks, but not model-driven turns | Missing |
| User-visible chat reflects live runtime state | Current UI is seeded and placeholder-heavy | Missing |

## Workspace and filesystem boundary

| Capability | Boundary model | Current behavior |
| --- | --- | --- |
| File mutations | Approved roots only | `FileOpsService` resolves paths against approved roots, rejects traversal, rejects path escape, and protects a minimum protected-file set. |
| Path validation | Canonical path checks | `..` traversal and out-of-root paths are denied by both filesystem and policy layers. |
| Protected files | Hard minimum registry | `agent.md`, `memory.md`, and `soul.md` are protected from destructive mutation. |
| SAF workspace access | Granted root only | `SafWorkspaceBridge` models `Granted`, `OutsideGrantedRoot`, `InvalidPath`, `NotGranted`, and `Revoked`. |
| Files tab workbench | Local seeded sandboxed workbench | The current workbench is created under app cache storage and seeded with demo content. It is not a general unrestricted device file browser. |

## Runtime and command boundary

| Capability | Current behavior | Boundary strength |
| --- | --- | --- |
| Local command execution | `CommandExecutor` launches local processes through `ProcessBuilder` | Medium |
| Command approval gating | `ModeGate` enforces `ALLOW`, `ASK`, and `DENY` based on policy decisions and approval tokens | Strong at policy layer |
| Command timeout and output limits | Implemented | Strong operational guardrail |
| Command allowlist | Not found | Weak |
| Working directory must stay in workspace | Not found in `CommandExecutor` or `ModeGate` | Weak |
| Real Termux backend | Not implemented in V1 | Not available |

The important practical distinction is:

| Path | Can it freely leave the workspace? |
| --- | --- |
| File mutation services | No |
| Python script execution through `python_runner` | No |
| SAF-granted workbench operations | No |
| Local command execution | Potentially yes at the process level, but still policy-gated and not wired into a completed autonomous agent runtime |

## Current tool and capability inventory

| Capability module | File or module | Current role | Maturity |
| --- | --- | --- | --- |
| Session queue runtime skeleton | `core/orchestrator` | Serial task orchestration, cancellation, retry, persistence | Implemented infrastructure |
| LLM gateway | `llm/src/main/kotlin/com/opencray/llm/LiteLlmGateway.kt` | Provider routing and fallback handling | Implemented module |
| Local command executor | `runtime/src/main/kotlin/com/opencray/runtime/CommandExecutor.kt` | Local process execution with gating and audit | Implemented module |
| Python runtime adapter | `runtime/src/main/kotlin/com/opencray/runtime/PythonRuntimeAdapter.kt` | Executes workspace-local Python scripts via `python_runner` | Implemented module |
| Python dependency installer | `runtime/src/main/kotlin/com/opencray/runtime/PipInstaller.kt` | Installs workspace-local Python dependencies into a workspace-managed venv | Implemented module |
| Python runner | `python_runner/runner.py` | Workspace-bound script execution and offline-style dependency installation | Implemented module |
| File mutation service | `filesystem/src/main/kotlin/com/opencray/filesystem/FileOpsService.kt` | Create, write, delete, and move inside approved roots | Implemented module |
| SAF workspace bridge | `filesystem/src/main/kotlin/com/opencray/filesystem/SafWorkspaceBridge.kt` | Workspace grant state and path authorization model | Implemented module |
| MCP registry | `mcp/src/main/kotlin/com/opencray/mcp/McpRegistry.kt` | Server registration, trust state, enablement state, auth readiness metadata | Implemented module |
| MCP client exposure factory | `mcp/src/main/kotlin/com/opencray/mcp/McpClientFactory.kt` | Converts registered servers into active or blocked descriptors | Implemented descriptor layer only |
| Skills loader and registry | `skills/src/main/kotlin/com/opencray/skills/SkillLoader.kt` | Discovers and validates `SKILL.md` packages | Implemented loader layer |
| Skills management UI | `ui/src/main/kotlin/com/opencray/ui/skills/SkillEditorViewModel.kt` | In-memory management UI with placeholder install and export flows | Partial or placeholder |
| Termux runtime adapter contract | `runtime/src/main/kotlin/com/opencray/runtime/TermuxRuntimeAdapterContract.kt` | Defines normalized adapter API and unavailable stub | Contract only |

## Tooling maturity table

| Tool or extension surface | Production-ready? | Why |
| --- | --- | --- |
| File mutations | Mostly yes as a local module | The service is concrete and tested, with rollback and path safety. |
| Local commands | Partially | The executor is real, but not visibly integrated into a completed agent runtime. |
| Python execution | Partially | The runtime path is real, but workspace-bound and not shown as part of a completed agent loop. |
| Python dependency install | Partially | Real module, but still a capability building block rather than a finished agent runtime behavior. |
| Skills | Not as a full execution runtime | Discovery and validation exist, but repository evidence points to metadata and UI scaffolding more than a complete execution engine. |
| MCP | Not as a full callable tool runtime | Registry and exposure states exist, but runtime connection wiring is explicitly deferred. |
| Termux | No | V1 intentionally ships an unavailable stub. |

## Why the current UI does not prove a completed agent runtime

| UI surface | Current state | Why it matters |
| --- | --- | --- |
| Chat screen | Placeholder and seeded | The screen explicitly says a future host can stream user and agent messages into it. |
| Chat timeline | Seeded scenario output | `AppShellActivity` builds deterministic approval and denial scenarios rather than reflecting a live runtime. |
| Skills UI | In-memory management and placeholder install or export actions | This demonstrates shell and metadata work, not a production skill execution pipeline. |
| Files tab | Bounded lightweight workbench | Useful for granted-root browsing and small edits, but not evidence of unrestricted agent filesystem access. |

## Stub and scaffold indicators

| File | Meaning |
| --- | --- |
| `core/src/main/kotlin/com/opencray/core/CoreStub.kt` | Core module still has stub residue. |
| `runtime/src/main/kotlin/com/opencray/runtime/RuntimeStub.kt` | Runtime module still has stub residue. |
| `mcp/src/main/kotlin/com/opencray/mcp/McpStub.kt` | MCP module still has stub residue. |
| `skills/src/main/kotlin/com/opencray/skills/SkillsStub.kt` | Skills module still has stub residue. |

These files do not prove a feature is absent by themselves, but together with the seeded UI and the missing runtime wiring they reinforce that the repository is still in a staged foundation state rather than a fully integrated autonomous agent runtime state.

## Bottom line

| Question | Answer |
| --- | --- |
| Has this repository completed an OpenClaw-like agent loop? | No |
| Does it have useful orchestration infrastructure already? | Yes |
| Can the agent freely access any workspace or installed software directory? | No |
| Are there real capability modules in the repository already? | Yes |
| Are those modules unified into one production autonomous tool runtime? | No |
| Is real Termux or external runtime execution part of the current V1 build? | No |

## Suggested next implementation milestones

| Priority | Missing piece | Why it matters |
| --- | --- | --- |
| P0 | Wire `AgentLoop` to a production runtime that can call LLM and tools | This is the missing core of a real agent loop. |
| P0 | Add a unified tool dispatcher with structured tool schemas and result envelopes | This is required for model-driven tool use. |
| P0 | Feed tool observations back into the next LLM turn | Without this there is no OpenClaw-style multi-turn loop. |
| P1 | Replace seeded chat state with live session state | This makes the runtime visible and debuggable in the product shell. |
| P1 | Decide and enforce command workspace boundaries | Current command execution is looser than file and Python paths. |
| P1 | Finish MCP runtime connection wiring | Current MCP support is mostly registry and exposure metadata. |
| P2 | Build a real skill execution engine on top of the current loader and validator | Current skills support is not yet a full runtime. |
| P2 | Implement a real Termux backend only if V2 scope includes it | V1 explicitly does not ship real Termux execution. |
