# Design: P1 Memory Tools and Skill Inventory

Last updated: 2026-03-16

## Status

Completed

This package has been implemented in the runtime, projected through host and local snapshot surfaces, and verified by targeted unit tests.

## Scope

This completed package covers two P1 context-management items:

1. OpenClaw-aligned on-demand memory tools
2. Runtime-visible skill inventory

It does not include:

- active skill capsule injection
- skill-specific tool narrowing
- pre-compaction memory flush
- dedicated operator UI for memory or skill traces

## Implemented outcomes

### 1. On-demand memory tools

OpenCray now exposes:

- `memory_search`
- `memory_get`

These tools operate on a projected memory corpus rather than raw persistence files.

The runtime also emits explicit `memory_retrieval` events when these tools are used, so post-run inspection can distinguish:

- automatic bounded memory recall before prompt assembly
- explicit in-run memory lookup through tools
- deterministic post-turn memory writes

### 2. Runtime-visible skill inventory

Managed skills roots are now resolved before prompt assembly and injected as a bounded `Skill Inventory` context layer.

The runtime records:

- visible skill count
- injected skill count
- omitted prompt-layer skill count
- implicit skill count
- invalid skill count
- a bounded visible-skill trace summary

Host and local runtime snapshot surfaces now project this metadata as structured `skillInventory` payloads for debugging.

## Boundary decisions preserved

- `ContextManager` remains a budget owner and allocator only.
- Skill visibility is resolved before entering `ContextManager`.
- `ContextManager` only decides how much inventory text fits into the prompt layer.
- Memory ranking, search, get, and retrieval policy stay in `runtime/memory/*`.
- Skill discovery stays outside `ContextManager`; it is injected as a prepared runtime input.

## Main code paths

- `runtime/src/main/kotlin/com/opencray/runtime/memory/*`
- `runtime/src/main/kotlin/com/opencray/runtime/skills/SkillInventory.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/context/ContextManager.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/context/PromptAssembler.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`
- `app/src/main/kotlin/com/opencray/app/AppAgentSessionTaskRuntimeFactory.kt`
- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt`

## Verification

Targeted verification completed with:

```powershell
$env:GRADLE_USER_HOME='D:\codes\MobileProjects\OpenCray\.gradle-user'; ./gradlew.bat :runtime:testDebugUnitTest --tests "com.opencray.runtime.context.PromptAssemblerTest" --tests "com.opencray.runtime.OpenCrayAgentRuntimeTest"
```

```powershell
New-Item -ItemType Directory -Force '.android-user' | Out-Null
Remove-Item Env:ANDROID_SDK_HOME -ErrorAction SilentlyContinue
$env:GRADLE_USER_HOME='D:\codes\MobileProjects\OpenCray\.gradle-user'
$env:ANDROID_USER_HOME='D:\codes\MobileProjects\OpenCray\.android-user'
./gradlew.bat :app:testDebugUnitTest --tests "com.opencray.app.AppAgentSessionTaskRuntimeFactoryTodoStoreTest" --tests "com.opencray.app.OpenCrayHostRuntimeTest" --tests "com.opencray.app.OpenCrayLocalRuntimeServerTest"
```

## Remaining follow-up

The next unfinished context-management items after this package are:

1. active skill capsule injection
2. pre-compaction memory flush
3. durable compaction summaries
4. bootstrap context files
5. richer full-context trace
