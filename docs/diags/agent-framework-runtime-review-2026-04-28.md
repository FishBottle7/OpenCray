# Agent Framework Runtime Review

**Date:** 2026-04-28
**Scope:** Full review of the agent runtime framework for runtime-blocking defects, streaming stutter, and UI race conditions.
**Known symptoms reported by user:**
1. Streaming sometimes stutters / not smooth
2. Runtime process chat bubble gets overwritten by the next streaming bubble when streaming is enabled
3. Inspector only refreshes item-by-item after the final answer is fully rendered — appears as if inspector refresh is blocked by runtime

---

## 2026-04-30 Follow-up Status

All defects enumerated in this review have now been fixed in code. The analysis below is still useful as historical root-cause context, but any section still labeled "Unresolved" or "New" in the original write-up should now be read together with this follow-up.

### Fix summary

- Bug 5 / 9 / 11: user-visible incomplete JSON drafts still stream, structured commentary drafts are no longer surfaced as competing live bubbles, and native structured completions now preserve multiple commentary segments via `commentaryTexts`.
- Bug 7 / 10 / 13 / 14: runtime deltas now merge by per-session `sequence`, open inspectors refresh directly from delta patches, live draft updates no longer rebuild full host state, and sandbox auto-refresh now drains a real queue instead of collapsing intermediate anchors.
- Bug 8 / 12: foreground `wait_agent` joins now emit progress heartbeats while polling child execution state, and the visible text coalescing window was reduced to `24ms` to smooth full-so-far streaming.

### Verification

- 2026-04-30: `./gradlew.bat :runtime:testDebugUnitTest :app:testDebugUnitTest`
- 2026-04-30: `flutter test test/chat_feature_screen_test.dart --name "runtime event deltas update the open inspector without a runtime snapshot refresh|runtime event deltas ignore totalLength mismatches when sequence is contiguous|runtime event deltas resync when sequence jumps|runtime event deltas create run traces without a runtime snapshot refresh|runtime event deltas update grouped inspector entries without a runtime snapshot refresh|live draft events keep projected process bubbles and terminal process status|streamed assistant snapshots keep process bubbles and update the open inspector|projected assistant phases suppress competing live drafts while runtime is ahead|sandbox session auto refresh drains queued anchors after an in-flight refresh"`

---

## Bug 1 (Critical): Streaming stutter — dual emission race + zero throttle

### Symptom
Streaming text occasionally pauses or stutters mid-response.

### Root cause
Every SSE chunk triggers `onAssistantDraftUpdated`, which synchronously fires two independent emissions on the executor thread:

```
// OpenCrayHostRuntime.kt:631-670
onAssistantDraftUpdated(...) {
    synchronized(lock) { /* update draft, record event */ }   // holds lock
    emitLiveAssistantDraftEvent(draftEventPayload)            // (1) EventChannel direct
    emitChatRuntimeSnapshot()                                 // (2) mainThreadPoster.post { ... }
}
```

`emitChatRuntimeSnapshot()` posts to main thread which calls `loadChatRuntimeSnapshot()` → `synchronized(lock) { runtimeActivitySnapshotLocked() }`. The next draft update on the executor thread also needs `lock`. This creates a lock contention chain:

- Executor thread holds `lock` during `onAssistantDraftUpdated`
- Main thread waits for `lock` to build snapshot
- Meanwhile executor thread's next chunk arrives and blocks on `lock` too

Compounding factors:
- `VisibleTextSnapshotCoalescer` default `minIntervalMs = 75L` (`OpenAiCompatibleLiteLlmProviderClient.kt:5511`) — some throttling, but data arrives in large bursts due to 8KB input buffering (see "Stream buffering" section below)
- 400ms polling timer (`OpenCrayHostRuntime.kt:7957`) also calls `emitChatRuntimeSnapshot()`, competing with event-driven emissions
- Live draft events and runtime snapshots travel on separate Flutter channels and can arrive out of order

### Suggested fix
1. `minIntervalMs = 75L` is reasonable, keep as-is
2. Remove `emitChatRuntimeSnapshot()` from `onAssistantDraftUpdated` — let the live draft event channel drive bubble updates alone; runtime snapshot should only be driven by run events or the 400ms poll
3. Or merge both emissions into a single atomic operation under one post

### Stream buffering: why streaming looks like batch playback

The underlying SSE reading is genuinely real-time — `readLine()` blocks until a complete line arrives from the LLM API. However, three layers of buffering cause tokens to arrive in large bursts rather than one-at-a-time:

```
Network: LLM API sends one token at a time
  ↓
Layer 1: HttpURLConnection internal buffer (~8KB)
  ↓  setChunkedStreamingMode() is NOT called, so HttpURLConnection
  ↓  buffers internally before releasing bytes to getInputStream()
Layer 2: BufferedInputStream (default 8192 bytes)     ← OpenAiCompatibleLiteLlmProviderClient.kt:3550
  ↓  accumulates bytes before delivering to InputStreamReader
Layer 3: BufferedReader (default 8192 characters)     ← used in all three SSE readers
  ↓  accumulates characters before delivering to readLine()
  ↓
Code: readLine() returns one SSE line
  ↓
VisibleTextSnapshotCoalescer (minIntervalMs = 75ms)  ← rate-limits UI updates
  ↓
UI: text appears in bursts every ~75ms
```

Each layer exists for a reason, but together they cause significant apparent batching:

- **Layer 1 (HttpURLConnection)**: Avoids making a system call per byte. Without it, reading would be extremely slow due to syscall overhead. But for streaming, it means tokens accumulate until the buffer fills.
- **Layer 2 (BufferedInputStream)**: Same purpose — reduces system calls. Redundant with Layer 1 when using HttpURLConnection, but needed for other InputStream types.
- **Layer 3 (BufferedReader)**: Converts byte stream to character stream with buffering. `readLine()` needs to scan for `\n`, so it reads ahead into a buffer. This is unavoidable for line-based protocols like SSE.

If streaming feels too fast (like batch playback rather than typing), the most impactful change would be calling `connection.setChunkedStreamingMode(0)` on the HttpURLConnection — this tells it to send data as soon as it's available rather than buffering. Layer 2 and 3 are unavoidable for correct SSE parsing.

### Files
- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:631-670`
- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:7926-7944` (emitChatRuntimeSnapshot)
- `app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt:5511` (DEFAULT_STREAM_UPDATE_MIN_INTERVAL_MS)
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt:1566-1594` (assistantDraftObserver)

---

## Bug 2 (Critical): Process bubble overwritten by next streaming bubble

### Symptom
When streaming is on, the process (run trace) bubble that shows tool calls gets visually replaced by the next streaming draft bubble. Only after the final answer is fully rendered does the inspector appear to refresh item-by-item.

### Root cause
`_handleLiveAssistantDraftEvent` for non-cleared events calls `_applyHostState()` (`chat_feature_screen.dart:3295-3302`):

```dart
if (!event.cleared) {
    final List<ChatMessageData> previousMessages = _state.messages;
    _applyHostState();  // rebuilds entire UI state from latest snapshots
    ...
    return;
}
```

`_applyHostState()` → `_mapSnapshot()` → `_mapRunTraces()` rebuilds all run traces from scratch by iterating `runtimeSnapshot.events`. If the runtime snapshot arriving at this moment was built while the executor thread was mid-streaming (between tool calls), the events list may not yet include the latest tool_call/tool_result events. The run trace history is rebuilt with stale data, causing the process bubble to appear overwritten or missing.

Additionally, `_resolvedChatMessageText()` (`chat_feature_screen.dart:4669`) replaces "Thinking" placeholder text with live draft text, which visually overwrites the pending thinking bubble. The run trace (process card) is a separate widget but shares the same message list — when the draft text replaces the thinking placeholder and the run trace history is stale, it looks like the process bubble is "covered."

### Suggested fix
1. In `_handleLiveAssistantDraftEvent` non-cleared path, do NOT call `_applyHostState()`. Instead, only update the draft text portion of the state (a lightweight patch)
2. Or decouple live draft text updates from runtime snapshot run trace updates using independent state fields

### Files
- `flutter_app/lib/features/chat/chat_feature_screen.dart:3295-3302` (non-cleared draft handler)
- `flutter_app/lib/features/chat/chat_feature_screen.dart:4473-4591` (_mapSnapshot)
- `flutter_app/lib/features/chat/chat_feature_screen.dart:4669-4684` (_resolvedChatMessageText)
- `flutter_app/lib/features/chat/chat_feature_screen.dart:5142-5441` (_mapRunTrace)

---

## Bug 3 (Critical): Inspector refresh blocked by runtime lock contention

### Symptom
Inspector history entries (tool_call, tool_result, etc.) only appear after the final answer is fully rendered. Looks like inspector refresh is blocked by runtime.

### Root cause
`loadChatRuntimeSnapshot()` (`OpenCrayHostRuntime.kt:1803`) acquires `synchronized(lock)` to call `runtimeActivitySnapshotLocked()`. The executor thread also holds the same `lock` during every draft update and run event:

```
Executor thread (near-continuous lock holder):
  onVisibleTextSnapshot → eventSink.onAssistantDraftUpdated
    → listenerProvider().forEach { ... }
      → OpenCrayHostRuntime.onAssistantDraftUpdated
        → synchronized(lock) { updateAssistantDraftLocked; recordRuntimeEventLocked }

Main thread (waiting for lock):
  mainThreadPoster.post {
    loadChatRuntimeSnapshot()
      → synchronized(lock) { runtimeActivitySnapshotLocked() }  // blocked
  }
```

During streaming, the executor thread holds `lock` on nearly every SSE chunk. The main thread's snapshot build is frequently blocked. The 400ms poll's emission is also delayed by lock contention.

Result: inspector history entries (tool_call, tool_result, subagent, etc.) can only be read when the executor thread releases `lock` — which happens between LLM calls or during tool execution gaps. This creates the appearance that inspector refreshes only after streaming completes.

### Suggested fix
1. Separate `liveAssistantDraftsBySession` read/write from the main lock — use a dedicated lock or `ConcurrentHashMap`
2. In `onAssistantDraftUpdated`, use copy-on-write: update draft data without holding the main lock for runtime event recording
3. Or build the snapshot from a lock-free copy of the data

### Files
- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:631-670` (onAssistantDraftUpdated under lock)
- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:1803` (loadChatRuntimeSnapshot under lock)
- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:2834` (runtimeActivitySnapshotLocked)
- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:7930-7944` (emitChatRuntimeSnapshot)

---

## Bug 4 (Medium): Three independent stream callbacks race on shared state

### Symptom
Occasional UI flicker or inconsistent state during rapid streaming.

### Root cause
Three separate `StreamSubscription` callbacks all modify `_state`:

| Callback | Trigger | Calls |
|---|---|---|
| `_handleChatSnapshot` | chat snapshot stream | `_applyHostState()` → `setState` |
| `_handleChatRuntimeSnapshot` | runtime snapshot stream | `_applyHostState()` → `setState` |
| `_handleLiveAssistantDraftEvent` | live draft EventChannel | `_applyHostState()` or direct `setState` |

Dart is single-threaded so no data race, but async interleaving causes issues:

1. Draft event → `_applyHostState()` → setState (based on snapshot pair A)
2. Runtime snapshot → `_applyHostState()` → setState (based on snapshot pair A + runtime B)
3. Chat snapshot → `_applyHostState()` → setState (based on chat C + runtime B)

Steps 2 and 3 may run in the same microtask, producing two `setState` calls based on inconsistent `_latestChatSnapshot` / `_latestChatRuntimeSnapshot` pairings. `chatFeatureStatesEquivalent` guards against equivalent states, but intermediate states can cause a frame of flicker.

### Suggested fix
1. Merge three callbacks into a unified state update entry using debounce or microtask coalescing
2. Or snapshot both `_latestChatSnapshot` and `_latestChatRuntimeSnapshot` atomically at the start of `_applyHostState`

### Files
- `flutter_app/lib/features/chat/chat_feature_screen.dart:3241-3247` (_handleChatSnapshot)
- `flutter_app/lib/features/chat/chat_feature_screen.dart:3250-3278` (_handleChatRuntimeSnapshot)
- `flutter_app/lib/features/chat/chat_feature_screen.dart:3281-3347` (_handleLiveAssistantDraftEvent)
- `flutter_app/lib/features/chat/chat_feature_screen.dart:3559-3606` (_applyHostState)

---

## Bug 5 (Medium): `visibleAssistantDraftText` filter too aggressive

### Symptom
When the LLM outputs valid JSON content (code blocks, config snippets, structured data), the chat bubble stays on "Thinking" until the full response is complete — no streaming preview is shown.

### Root cause
`visibleAssistantDraftText()` (`OpenCrayAgentRuntime.kt:1596-1628`) filters out any text that starts with `{` or `[` if it matches certain protocol-like patterns. Additionally, incomplete JSON (not ending with `}` or `]`) returns `null`:

```kotlin
if (!normalized.endsWith('}') && !normalized.endsWith(']')) {
    return null  // incomplete JSON → hidden from user
}
```

This means:
- Legitimate JSON content shown to users is suppressed during streaming
- Incomplete JSON (common during streaming) is always hidden
- The user sees "Thinking" until the LLM finishes outputting the entire JSON block

### Suggested fix
1. Add more precise protocol detection: check for `"type":` + `"actions":` combination rather than any `"type"` occurrence
2. For ambiguous cases, prefer showing over hiding
3. Consider extracting visible text from JSON structures (e.g., `"content"` fields) rather than blanket-filtering

### Files
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt:1596-1628`
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt:1630-1680` (extractStructuredAssistantDraftText)

---

## Bug 6 (Medium): Full snapshot rebuild on every emission — O(n) amplification

### Symptom
High GC pressure, potential OOM on real devices (referenced in `draft/2026-04-16-streaming-runtime-findings.md`), and main thread jank during streaming.

### Root cause
Every `emitChatRuntimeSnapshot()` calls `currentRunSnapshots()` (`AgentSessionRuntimeManager.kt:1346`) which rebuilds all `AgentRunSnapshot` from scratch:

- `loop.snapshot()` — fresh queue state
- `listManagedProcesses()` — fresh process list
- Associate processes to runs
- Rebuild `managedProcesses`, `history`, `lastEvent` per run

During high-frequency streaming (potentially dozens of emissions per second), this causes:
- Main thread holds `lock` for extended time building snapshots
- Massive temporary object allocation
- GC pressure leading to jank and potential OOM

### Suggested fix
1. Introduce incremental update — only rebuild when `updatedAtEpochMs` actually changes
2. Cache previous snapshot and diff before rebuilding
3. Or at minimum, debounce snapshot rebuilds to a minimum interval (e.g., 100ms)

### Files
- `app/src/main/kotlin/com/opencray/app/AgentSessionRuntimeManager.kt:1346` (currentRunSnapshots)
- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt:2834` (runtimeActivitySnapshotLocked)

---

## Bug 7 (Low): Sandbox refresh race condition

**2026-04-30 status:** ✅ Fixed by replacing the single queued anchor with an ordered queue that drains after each in-flight refresh completes.

### Symptom
Occasional stale sandbox session state in UI.

### Root cause
`_syncSandboxSessionAutoRefresh()` (`chat_feature_screen.dart:3627-3655`) uses a plain `bool _sandboxSessionRefreshInFlight` and single-value `_queuedSandboxSessionRefreshAnchor`. Multiple refresh requests during a flight are collapsed to only the last one, intermediate anchors are lost.

### Suggested fix
Replace single-value queue with a proper queue or at minimum track the latest anchor with a generation counter.

### Files
- `flutter_app/lib/features/chat/chat_feature_screen.dart:3627-3655`

---

## Bug 8 (Low): Sub-agent foreground execution blocks parent agent thread

**2026-04-30 status:** ✅ Fixed by polling foreground joins with a short timeout and emitting sub-agent progress heartbeats/checkpoint updates while waiting.

### Symptom
When a tool invokes a sub-agent in foreground mode, the parent agent's progress stalls — no draft updates are emitted, and the process bubble shows no activity until the sub-agent completes.

### Root cause
`waitForSubAgentExecution()` (`OpenCrayAgentRuntime.kt:6884`) calls `activeExecution.future.get()`, which blocks the parent agent's executor thread until the child sub-agent finishes. During this time:

- No `gateway.execute()` is running, so no draft updates
- The 400ms poll still fires, but the parent run shows "working" with no progress
- User sees a frozen process bubble

### Suggested fix
1. While waiting for sub-agent, periodically emit progress events from the sub-agent's state
2. Or use a non-blocking callback pattern instead of `future.get()`

### Files
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt:6879-6892` (waitForSubAgentExecution)
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt:6847` (subAgentExecutionLock)

---

## Architecture Diagram: Data Flow (2026-04-28 Snapshot Before Follow-up Fixes)

```
[SSE Stream from LLM API]
    │
    ▼
[Executor Thread] ── OpenCrayAgentRuntime.execute()
    │
    ├─ gateway.execute() ──── blocking HTTP call
    │   │
    │   └─ onVisibleTextSnapshot() ──── minIntervalMs=75
    │       │
    │       └─ assistantDraftObserver.onVisibleTextSnapshot()
    │           │
    │           └─ eventSink.onAssistantDraftUpdated()
    │               │
    │               ├─ synchronized(lock) { session check }   ← 轻量
    │               ├─ updateAssistantDraft()
    │               └─ emitLiveAssistantDraftEvent()           ← 只推文字 ✅
    │                  (不再调用 emitChatRuntimeSnapshot) ✅
    │
    ├─ onRunEvent (tool_call / tool_result / etc.)
    │   │
    │   └─ synchronized(lock) {
    │       recordRuntimeEventLocked()
    │       runtimeEventDeltaPayloadLocked()  ← 构建增量 payload
    │     }
    │     emitRuntimeEventDelta()             ← 增量 EventChannel ✅
    │     (不再调用 emitChatRuntimeSnapshot) ✅
    │
    ├─ onTaskFinished (run 结束时)
    │   ├─ emitChatSnapshot()
    │   └─ emitChatRuntimeSnapshot()          ← ⚠️ 仍然全量快照
    │
    └─ future.get() ──── blocking sub-agent wait

[Flutter EventChannel callbacks]
    ├─ _handleChatRuntimeSnapshot()      ← 全量快照（session 切换/恢复/初始化）
    │   └─ _applyHostState() → _mapSnapshot() → 全量重建
    │
    ├─ _handleRuntimeEventDelta()        ← 增量 event（运行时） ✅
    │   └─ _mergeRuntimeSnapshots() → _applyRuntimeActivityPatch()
    │       └─ 只更新 run traces + messages
    │
    └─ _handleLiveAssistantDraftEvent()  ← 流式文字
        └─ _patchMessagesForLiveDraftEvent() ← 直接 patch messages ✅
           (不再调用 _applyHostState) ✅

[Flutter 内存]
    └─ _latestChatRuntimeSnapshot ← 单数据源
         ├─ [message bubble]   取 liveDraft text → assistant 气泡
         ├─ [run trace 卡片]   取 events 最后几条 → 状态 + 摘要
         └─ [inspector]        取 events 全部 → history entries
```

**2026-04-30 follow-up:** the issues listed here have been addressed by later changes:
- 增量事件现在使用 per-session `sequence` 做连续性校验，不再依赖 `totalLength`
- native structured commentary 已支持 `commentaryTexts`，不会再把多段 process 合并成一个 commentary event
- live draft 更新与 runtime delta patch 现已解耦，不再通过 `_applyHostState()` 触发全量重建

---

## Priority-Ordered Remediation Plan (Updated)

| Priority | Bug | Effort | Impact | Status |
|---|---|---|---|---|
| P0 | Bug 1: Dual emission + zero throttle | Low | Streaming stutter | ✅ 已修复 |
| P0 | Bug 3: Lock contention blocks inspector | Medium | Inspector during streaming | ✅ 已修复 |
| P0 | Bug 13: 第一条 process 被复制占位 | Medium | 100% 复现，严重影响体验 | ✅ 已修复 |
| P1 | Bug 12: 流式文字跳跃式显示 | Low | 75ms coalescer + full-so-far | ✅ 已修复 |
| P1 | Bug 14: Inspector 比气泡慢 | Medium | 全量重建 vs 轻量 patch | ✅ 已修复 |
| P1 | Bug 9: Process 气泡被覆盖 | Medium | 只显示最后一条 process | ✅ 已修复 |
| P1 | Bug 11: 两条 process 被合并 | Medium | native structured output 单字段 | ✅ 已修复 |
| P1 | Bug 5: Aggressive text filter | Low | JSON content hidden | ✅ 已修复 |
| P2 | Bug 10: Inspector 不实时更新 | Medium | 全量重建 + equivalence 检查 | ✅ 已修复 |
| P3 | Bug 4: Three-callback race | Medium | Occasional flicker | ✅ 部分修复 |
| P3 | Bug 7: Sandbox refresh race | Low | Edge case | ✅ 已修复 |
| P3 | Bug 8: Sub-agent blocking | Medium | UX improvement | ✅ 已修复 |

---

## Post-Review Discussion: Clarifications and Consensus

### UI Structure Clarification

Review 中提到的"气泡"、"卡片"、"inspector"在实际 UI 中对应如下：

```
[user 气泡] "帮我查一下天气"
    ↓
[run trace 卡片]  ← 即 "Running" 卡片，有边框的圆角矩形
  ├─ 状态标签: "Running" / "Paused"
  ├─ 活动标签: "WebSearch" / "Read"
  ├─ 标题: "Searching..."       ← 即 "process 气泡"内容（"我现在搜索"）
  └─ 摘要详情: 一行简短描述
    ↓
[assistant 气泡]  ← LLM 的流式文字输出，或 "Thinking" 占位符
```

双击 run trace 卡片打开 **全屏 inspector**，里面有 `_RunTraceHistoryCard` 列表，每条是工具调用的完整记录（调用参数、结果、语义着色）。

| 概念 | 对应 Widget | 数据来源 |
|---|---|---|
| assistant 气泡文字 | `_ChatMessageBubble` + `_resolvedChatMessageText()` | `liveAssistantDrafts` (via EventChannel 增量推送) |
| process 气泡内容 | `_RunTraceBubble` 的 label/body | `runtimeSnapshot.events` 最后一条 event 的 label/body |
| run trace 卡片 | `_RunTraceBubble` | `runtimeSnapshot.activeRuns` + `events` |
| inspector 详情 | `_RunTraceFullscreenSheet` + `_RunTraceHistoryCard` | `runtimeSnapshot.events` 完整列表 |

### 核心问题定性

Bug 1/2/3 的根本原因不是锁竞争本身，而是**流式文字更新触发了不必要的全量快照重建**。

当前数据流：

```
SSE chunk 到达
  → onAssistantDraftUpdated
    → synchronized(lock) 更新草稿                           ← 合理
    → emitLiveAssistantDraftEvent()                         ← 合理：只推文字增量
    → emitChatRuntimeSnapshot()                             ← 不合理：全量重建整个快照
      → Kotlin: synchronized(lock) 从零构建完整 snapshot    ← 不必要的锁获取
      → EventChannel: 序列化整个 Map (50+ events × 45 字段) ← 不必要的序列化开销
      → Flutter: _applyHostState() 全量重建 UI              ← 不必要的 UI 重建
        → _mapRunTraces() → _buildRunTraceHistory()         ← events 没变，重建无意义
```

`emitLiveAssistantDraftEvent()` 已经通过独立 EventChannel 把文字推给了 Flutter，`emitChatRuntimeSnapshot()` 是多余的。

### 已确认的事实

1. **Flutter 和 Kotlin 运行在不同的 VM 里，不能直接共享内存**。所有数据传输都是"序列化 → 拷贝 → 反序列化"。
2. **Kotlin 侧的 events 是增量存储的**（不断 append），但传给 Flutter 的方式是全量的（每次把整个 snapshot 序列化一遍）。
3. **Flutter 侧收到后也是全量替换**（`_latestChatRuntimeSnapshot = snapshot`，旧的直接丢弃）。
4. **run trace 卡片和 inspector 都从同一份 events 列表构建**，但它们的更新被捆绑在不必要的全量快照重建里。
5. **现有通信方式只有两种**：EventChannel（StandardMethodCodec 二进制编码，push 模式）和 HTTP localhost（JSON 文本，2 秒轮询）。都没有增量/diff 能力。

### 达成共识的方案：单数据源 + 增量更新 + 多视图

#### 核心思路

Flutter 内存中维护一份 `_localEvents` 作为**唯一数据源**，三个视图（message bubble、run trace 卡片、inspector）都从它取数据，只是显示的详略不同。数据通过一条增量 EventChannel 从 Kotlin 侧推送，不走全量快照。

#### 数据模型：一份数据，三个视图

```
Flutter 内存
  └─ _localEvents: List<RuntimeEventSnapshot>  ← 唯一数据源，持续 append
       │
       ├─ [message bubble] 取最后一条 event 的 label → 显示 "正在搜索..." / "正在读取文件..."
       │
       ├─ [run trace 卡片] 取最后几条 event → 状态标签 + 活动标签 + 摘要
       │
       └─ [inspector] 取全部 event → 每条映射为 ChatRunTraceHistoryEntry（语义化字段，不暴露原始格式）
```

三个视图读同一份数据，不存在"气泡显示了但 inspector 还没更新"或"inspector 更新了但气泡还没跟上"的情况。

#### 数据管道

| 管道 | 数据内容 | 触发时机 | 用途 |
|---|---|---|---|
| **增量 event** | 新增的 runtime event | `onRunEvent` 时 | 更新 `_localEvents`，三个视图同步刷新 |
| **流式文字** | assistant 气泡文字 | 每个 SSE chunk（可节流） | 只更新 assistant 气泡的文字内容 |
| **全量快照** | 完整 snapshot | session 切换、恢复、初始化时 | 替换 `_localEvents`，用于全量同步 |

#### 400ms 轮询定时器的处理

`OpenCrayHostRuntime.kt:7957` 的 `liveChatRuntimeRefreshTimer` 在 agent 运行期间每 400ms 重建一次全量快照推给 Flutter。

**问题**：这个全量快照会覆盖 Flutter 侧通过增量通道已经收到的最新 events（因为 Kotlin 侧构建快照有延迟，events 列表可能比增量通道的旧）。

**处理方案**：去掉运行时的 400ms 轮询。增量通道已经保证了实时性，全量快照只在以下场景触发：
- session 初始化 / 恢复
- session 切换
- 用户从后台回到前台（需要重新同步）

#### Kotlin 侧改动

```
onAssistantDraftUpdated(...) {
    synchronized(lock) { updateAssistantDraftLocked(...) }
    emitLiveAssistantDraftEvent(...)     // 只推文字，不碰 events
    // 不再调用 emitChatRuntimeSnapshot()
}

onRunEvent(...) {
    synchronized(lock) {
        recordRuntimeEventLocked(...)
        val newEvents = eventsSinceLastPush()
    }
    emitRuntimeEventDelta(newEvents)     // 只推增量 event
    // 不再调用 emitChatRuntimeSnapshot()
}

// 全量快照只在 session 切换/恢复/初始化时触发
onSessionSwitched(...) {
    emitChatRuntimeSnapshot()            // 全量同步
}

// 400ms 轮询去掉
// syncLiveChatRuntimeRefreshLoop() 不再启动
```

**新增 EventChannel：**

```
Channel: com.opencray.host/runtime_event_delta
Payload: {
    'sessionId': String,
    'events': List<Map>  // 只包含新增的 event，不是全量
    'totalLength': int   // Kotlin 侧 events 列表的总长度，用于校验
}
```

#### 增量通道丢包检测

EventChannel 没有 ACK 确认机制，存在丢包可能。通过 `totalLength` 做校验：

```
Kotlin 侧：每次推送增量 event 时附带当前 events 列表总长度
    → payload: { events: [new1, new2], totalLength: 42 }

Flutter 侧：收到后对比 _localEvents.length + delta.events.length 是否等于 totalLength
    → 相等：正常，append
    → 不等：丢了包，触发一次全量同步
```

这样不需要轮询，也不需要 ACK，只在异常时才走全量同步。

#### Flutter 侧改动

```dart
// === 唯一数据源 ===
List<OpenCrayChatRuntimeEventSnapshot> _localEvents = [];

// === 唯一写入路径：增量 append ===
void _handleRuntimeEventDelta(RuntimeEventDelta delta) {
    if (delta.sessionId != _activeSessionId) return;
    _localEvents.addAll(delta.events);
    setState(() {});  // 三个视图自动刷新
}

// === 全量同步（session 切换/恢复时） ===
void _handleChatRuntimeSnapshot(OpenCrayChatRuntimeSnapshot snapshot) {
    _localEvents = snapshot.events;
    setState(() {});
}

// === 三个视图都是读 _localEvents，不做独立数据处理 ===

// Inspector: 渲染全部 event → 每条映射为 ChatRunTraceHistoryEntry（语义化，不暴露原始格式）
List<ChatRunTraceHistoryEntry> inspectorHistory =
    _localEvents.map(_mapRunTraceHistoryEntry).toList();

// Run trace 卡片: 从 inspector 最后几条取 label/body
ChatRunTraceData runTraceCompact = _buildFromRecentEntries(inspectorHistory);

// Message bubble: 从 inspector 最后一条取 label
String processBubbleLabel = inspectorHistory.lastOrNull?.label ?? 'Thinking';
```

气泡和卡片的信息**就是从 inspector 数据里取的**，不是单独维护的。只有一条写入路径，三个视图都是读，不存在同步问题。

#### 改动后的数据流

```
运行时（正常执行）:
  Kotlin onRunEvent
    → 增量 EventChannel(runtime_event_delta)
    → Flutter _localEvents.addAll(delta)
    → setState
    → message bubble: 读 _localEvents.last → 显示 "正在搜索..."
    → run trace 卡片: 读 _localEvents.last few → 更新状态和摘要
    → inspector: 读 _localEvents 全部 → 新增一条 history entry

流式文字（独立，不碰 events）:
  Kotlin onAssistantDraftUpdated
    → live_assistant_draft EventChannel
    → Flutter 只更新 assistant 气泡文字

Session 切换/恢复（全量同步）:
  Kotlin
    → 全量 EventChannel(chat_runtime_snapshot)
    → Flutter _localEvents = snapshot.events（全量替换）
    → 三个视图同步刷新
```

#### 预期效果

| 问题 | 改动前 | 改动后（设计目标） | 实际状态 |
|---|---|---|---|
| 流式卡顿 | 每个 token 触发全量快照序列化 + 锁竞争 | 文字走独立通道，不碰快照 | ✅ 已改善 |
| process 气泡覆盖 | 流式更新触发全量重建，events 可能不完整 | process 卡片只在 onRunEvent 时更新 | 🔴 仍然存在（见 Bug 9） |
| inspector 延迟 | 被全量快照的锁竞争阻塞 | 增量 append，无锁竞争 | 🔴 仍然存在（见 Bug 10） |
| 序列化开销 | 每次传 50+ events × 45 字段 | 增量只传 1-2 个新 event | ✅ 已改善 |
| 三条回调竞争 | 三个独立 stream 各自触发 _applyHostState | 文字更新和 event 更新走不同路径 | ✅ 已改善 |
| 400ms 轮询开销 | 每 400ms 全量序列化 + IPC | 去掉，只在 session 切换时全量同步 | ✅ 已落实 |

#### 已确认的设计决策

1. **`_localEvents` 生命周期**：持续 append，session 切换时由全量快照整体替换。不需要 ack 机制，不需要主动清空。
2. **全量快照保留**：保留给 session 恢复、初始化、session 切换等需要全量同步的场景。运行时只走增量通道。
3. **400ms 轮询定时器**：即 `OpenCrayHostRuntime.kt:7957` 的 `liveChatRuntimeRefreshTimer`，在 agent 运行期间每 400ms 重建一次全量快照推给 Flutter。有了增量通道后这个定时器多余，可以去掉或降频到几秒一次作为兜底校验。
4. **紧凑视图数据来源**：run trace 卡片的紧凑视图（标题、摘要、状态标签）从 Flutter 内存中的 `_localEvents` 取最后几条 event 的 label/body 构建。inspector 也从同一份 `_localEvents` 取完整 history。同一份数据，两个视图，只是详略不同。
5. **Inspector 流式更新**：inspector 内容随 event 到达实时刷新，不等 final answer 完成。每条新 event 经过 `_mapRunTraceHistoryEntry()` 映射成 `ChatRunTraceHistoryEntry`（含 `label`、`body`、`inspectorCallParts`、`inspectorResultBody` 等语义化字段），append 到 history 列表。原始 JSON/Map 格式不暴露给 inspector 视图。

---

## Implementation Review: 落地情况检查

### ✅ 已落实

| 改动 | 状态 | 位置 |
|---|---|---|
| `onAssistantDraftUpdated` 不再调用 `emitChatRuntimeSnapshot()` | ✅ | `OpenCrayHostRuntime.kt:638-662` |
| 400ms 轮询定时器已移除 | ✅ | `syncLiveChatRuntimeRefreshLoop` / `liveChatRuntimeRefreshTimer` 已不存在 |
| 增量 event channel 已实现（骨架） | ✅ | Kotlin: `runtimeEventDeltaListeners`(line 426), `emitRuntimeEventDelta`(line 8077), `runtimeEventDeltaPayloadLocked`(line 2636) |
| totalLength 丢包检测已实现 | ✅ | `chat_feature_screen.dart:3347-3350` |
| 全量同步兜底已实现 | ✅ | `_resyncRuntimeSnapshotAfterDeltaMiss()` (line 3362-3381) |
| `_handleLiveAssistantDraftEvent` 不再调用 `_applyHostState()` | ✅ | 直接 patch messages，不触发全量重建 |

### Historical Root Defect Analysis (Resolved in Follow-up Changes by 2026-04-30)

增量 channel 虽然骨架搭好了，但存在根本性缺陷，导致**三个核心问题（气泡覆盖、inspector 不刷新、流式卡住）依然存在甚至恶化**。

#### 缺陷 1：`runtimeEventDeltaPayloadLocked` 不是增量，每次都是全量重建——且在锁内

设计目标：只推新增的 event，不重建整个 snapshot。

实际代码（`OpenCrayHostRuntime.kt:2636-2657`）：

```kotlin
private fun runtimeEventDeltaPayloadLocked(...): Map<String, Any?>? {
    val runtimeDelta = runtimeActivityDeltaBuildLocked(sessionId)  // ← 从零重建全部！
    val eventKey = runtimeEventDedupKey(event)
    val deltaEvents = runtimeDelta.recentEvents.filter { ... }     // ← 从全量中过滤出匹配的
    return runtimeDelta.payload + mapOf(                            // ← payload 包含全量 runs/subAgents/etc
      "events" to deltaEvents.map(::runtimeEventToMap),
      "totalLength" to runtimeDelta.recentEvents.size,
    )
}
```

`runtimeActivityDeltaBuildLocked`（line 2662-2715）**每次调用都从零重建**：
- `listRuns()` — 查询所有 run
- `mergedRuntimeEventsLocked()` — 合并去重所有 events
- `displayedRunsForSnapshot()` — 重建所有 run snapshot
- `subAgentSnapshotsForActivity()` — 重建所有 sub-agent
- `liveAssistantDraftsForSnapshot()` — 重建所有 draft
- 然后序列化成完整 Map（`activeRuns`、`retainedRuns`、`subAgents`、`events`、`liveAssistantDrafts`）

只在最后一步过滤 `events` 为匹配当前 event 的那些，但 payload 里的**其他字段都是全量**。每个 onRunEvent 的开销与旧的 `emitChatRuntimeSnapshot()` 基本相同。

**关键恶化**：旧代码的 `emitChatRuntimeSnapshot()` 是 `mainThreadPoster.post {}` 后才重建的（锁已释放，在主线程上重建）。新代码把全量重建**搬进了 `synchronized(lock)` 块内**（line 607-631），在 executor 线程上执行：

```kotlin
// line 607-631
val emission = synchronized(lock) {          // ← 拿锁
    recordRuntimeEventLocked(...)             // ← 记录 event（轻量）
    maybePersistAssistantPhaseChatMessageLocked(...)  // ← 持久化
    maybePersistGeneralResumeCheckpointLocked(...)     // ← 持久化
    val runtimeEventDelta = runtimeEventDeltaPayloadLocked(...)  // ← 全量重建 + 序列化，在锁内！
    EventEmissionDecision(...)
}
emission.runtimeEventDelta?.let(::emitRuntimeEventDelta)  // 释放锁后才 emit
```

这比旧代码**锁持有时间更长**，主线程的 `emitChatRuntimeSnapshot` 更难拿到锁，inspector 更难刷新。

#### 缺陷 2：totalLength 校验必然失败 → 每次都触发全量 resync

Kotlin 侧 `totalLength` = `mergedRuntimeEventsLocked()` 的结果数量。去重用 `runtimeEventDedupKey`，key 包含 `reason` + `arguments`。

Flutter 侧 `_mergeRuntimeSnapshots`（line 535-554）用 `_runtimeEventMergeKey` 去重，key 包含 `emittedAtEpochMs` + `text`。

**两侧 key 计算方式不同**，合并后的 event 数量不一致。

结果：`patchedSnapshot.events.length != delta.totalLength`（line 3347-3350）→ 触发 `_resyncRuntimeSnapshotAfterDeltaMiss()` → 异步加载全量快照。

**增量 channel 形同虚设，每次都回退到全量模式**。而全量 resync 走的是 `bridge.loadChatRuntimeSnapshot()` → `_handleChatRuntimeSnapshot()` → `_applyHostState()` → 全量重建 UI，和旧代码一样。

#### 缺陷 3：流式卡住 — executor 线程被锁内的全量重建阻塞

SSE 流式读取和 `onRunEvent` 回调都在**同一个 executor 线程**上。

当 `onRunEvent` 在 `synchronized(lock)` 内做全量重建时（`runtimeActivityDeltaBuildLocked`），executor 线程被阻塞。期间：
- SSE `readLine()` 无法执行（同一个线程）
- 流式数据在网络缓冲区堆积
- 用户看到流式卡住

加上缺陷 2 的全量 resync，Flutter 主线程忙于处理 resync 返回的全量快照，EventChannel 的事件投递也被延迟。

### 三个问题的因果链

```
问题 1：气泡被覆盖
  → Bug 9 根因未修复：draft stream 用 pendingMessageId 做 key，latest wins
  → 设计文档提出了三个方案但均未实施

问题 2：Inspector 不刷新
  → 缺陷 1：增量 channel 做全量重建，锁持有时间比旧代码更长
  → 缺陷 2：totalLength 校验失败，每次都回退到全量 resync
  → 全量 resync 和旧代码一样，inspector 仍然被锁阻塞

问题 3：流式卡住
  → 缺陷 1：全量重建在锁内，阻塞 executor 线程
  → executor 线程同时负责 SSE 读取，被阻塞后流式数据堆积
  → 缺陷 2 的全量 resync 进一步阻塞 Flutter 主线程
```

### 正确的实现方向

增量 channel 要真正有效，需要做到：

1. **`runtimeEventDeltaPayloadLocked` 只序列化新增 event**：锁内只做 `recordRuntimeEventLocked` + `runtimeEventToMap(event)`（单个 event 序列化），不做 `runtimeActivityDeltaBuildLocked` 全量重建
2. **只传新增 event 的序列化数据**，不传 runs/subAgents/drafts（这些由全量快照负责）
3. **用 sequence number 替代 totalLength** 做丢包校验（sequence number 单调递增，不受 dedup key 影响）
4. **全量快照只在 session 切换/恢复时触发**，运行时完全不调用 `emitChatRuntimeSnapshot()`（包括 `onTaskFinished`）
5. **Bug 9 需要单独修复**：draft stream 的 `pendingMessageId` 替换问题是独立的，增量 channel 解决不了

### 🔴 仍未解决的问题

#### 问题 A：`onTaskFinished` 仍然调用 `emitChatRuntimeSnapshot()`

`OpenCrayHostRuntime.kt:600-603`：

```kotlin
override fun onTaskFinished(...) {
    ...
    emitChatSnapshot()
    emitChatRuntimeSnapshot()  // ← 仍然做全量快照
}
```

task 结束时的全量快照会与增量 channel 竞争。如果此时 Flutter 侧正在通过增量 channel 收集 events，全量快照到达后会替换掉 `_latestChatRuntimeSnapshot`，可能覆盖增量数据。

**建议**：task 结束时也走增量 channel，或者至少在全量快照中包含完整的 events 列表确保一致性。

#### 问题 B：totalLength 校验与 merge 逻辑不匹配

Kotlin 侧 `runtimeEventDeltaPayloadLocked` 中的 `totalLength` 是 `mergedRuntimeEventsLocked()` 的结果长度（journal + live 合并去重后的数量）。

Flutter 侧 `_handleRuntimeEventDelta` 中的校验：

```dart
// chat_feature_screen.dart:3347-3350
if (delta.totalLength > 0 &&
    patchedSnapshot.events.length != delta.totalLength) {
  _resyncRuntimeSnapshotAfterDeltaMiss();  // 触发全量同步
  return;
}
```

`patchedSnapshot` 是 `_mergeRuntimeSnapshots(currentSnapshot, deltaSnapshot)` 的结果。merge 用 `_runtimeEventMergeKey` 去重，key 包含 `emittedAtEpochMs` + `text`，与 Kotlin 侧的 `runtimeEventDedupKey`（包含 `reason` + `arguments`）**计算方式不同**。这会导致两边的 event 数量不一致，频繁触发误判 resync。

**建议**：统一 Kotlin 和 Dart 侧的 dedup key 计算，或者用 sequence number 替代 totalLength 做校验。

#### 问题 C：tool_call + tool_result 被合并成一条 inspector entry

`_mapRunTraceHistoryEntry`（`chat_feature_screen.dart:6068-6086`）会把 tool_call 和对应的 tool_result 合并成一条 `_buildGroupedToolHistoryEntry`。如果一个工具调用的 call 阶段有文字（如"我将要定位"），result 阶段也有文字（如"我已经定位到"），它们会被拼接成一条 entry 显示。

同时在气泡端，`processBubbleLabel` 只取最后一条 event 的 label，前面的会被覆盖。

**建议**：
- 如果希望 call 和 result 分开显示，修改 `_mapRunTraceHistoryEntry` 不做合并，每条 event 独立成 entry
- 或者保留合并但用分隔符清晰区分 call 和 result 两个阶段
- 气泡端取最后一条未被 consumed 的 event 的 label，而不是所有 event 的最后一条

#### 问题 D：文档仍有不一致

1. **Flutter 侧代码示例**（设计文档中的伪代码）写的是简单的 `_localEvents` append，实际是 snapshot merge 模式（`_mergeRuntimeSnapshots`），更健壮但更复杂
2. **Kotlin 侧改动示例**写的 `onRunEvent` 不再调用 `emitChatRuntimeSnapshot()`（已落实），但没有提到 `onTaskFinished`（line 603）仍然调用

---

## Bug 9 (Resolved on 2026-04-30): Process 气泡文字被覆盖 — draft stream 用 pendingMessageId 做 key，latest wins

### Symptom
agent 连续输出两条 process 文字（如"我将要定位"和"我已经定位到"），第一条在气泡里显示后被第二条覆盖，用户只能看到最后一条。

### Root cause

"process 气泡"是 assistant 的流式文字气泡（draft stream），不是 run trace 卡片。

draft stream 的存储和传输都用 `pendingMessageId` 做 key，一个 task 执行期间只有一个 draft per pendingMessageId，每次新文字到达都是**替换**而不是追加：

**Kotlin 侧**（`OpenCrayHostRuntime.updateAssistantDraft`，line 2860-2866）：

```kotlin
val sessionDrafts = liveAssistantDraftsBySession.getOrPut(sessionId) { linkedMapOf() }
sessionDrafts[pendingMessageId] = updatedDraft  // REPLACE，不是 APPEND
```

**Flutter 侧**（`_patchMessagesForLiveDraftEvent`，line 3542-3560）：

```dart
if (messageIndex >= 0) {
  final ChatMessageData updatedMessage = ChatMessageData(
    text: text,  // 新文字替换旧文字
    ...
  );
  // 替换 message list 中的旧消息
}
```

所以 "我将要定位" → 气泡显示 → "我已经定位到" 到达 → 替换同一条消息 → 用户只看到后者。

同时，这两段文字也可能通过 **commentary event 路径**（`assistant_phase`）独立显示为单独的气泡（每条有唯一 message ID），但 draft stream 和 commentary 路径会同时生效，造成混乱：draft 气泡一直在覆盖，commentary 气泡可能被 draft 气泡遮挡。

### Suggested fix
- 方案 A：让 draft stream 不显示 commentary 类型的文字，只显示最终回答。commentary 文字交给 `assistant_phase` 事件独立显示为单独气泡
- 方案 B：改 draft key 从 `pendingMessageId` 为包含 turn/text identity 的复合 key，让每段 process 独立存储
- 方案 C：在 draft stream 中保留文字历史，气泡显示多条而不是替换

---

## Bug 10 (Resolved on 2026-04-30): Inspector 不实时更新 — `_applyRuntimeActivityPatch` 全量重建 UI state

### Symptom
inspector 在 streaming 期间不逐条刷新，看起来只有在 run 结束后才更新。

### Root cause
虽然增量 channel 已经实现了（`_handleRuntimeEventDelta`），但 `_handleRuntimeEventDelta` 调用的是 `_applyRuntimeActivityPatch`（line 3359），这个方法（line 3701-3761）：

1. 从 `_latestChatRuntimeSnapshot` 重新 `_mapRuntimeProjection`
2. 重建所有 messages 和 runTraces
3. `chatFeatureStatesEquivalent` 检查是否有变化

问题是 `chatFeatureStatesEquivalent` → `_chatRunTracesEquivalent`（line 1303）比较 run trace 的 `history` 列表时用 `_chatRunTraceHistoryEntriesEquivalent`，逐字段比较 `label`、`body`、`inspectorCallParts` 等。如果新 event 被 tool_call+tool_result 合并逻辑处理后产生的 history entry 与已有条目在结构上等价（只是 text 字段不同），`chatFeatureStatesEquivalent` 可能返回 true，导致 `setState` 被跳过。

此外，`onTaskFinished`（line 603）仍然调用 `emitChatRuntimeSnapshot()` 做全量快照，这会到达 `_handleChatRuntimeSnapshot`（line 3273），走 `_applyHostState()` 全量重建。在全量快照到达之前，增量更新可能因为 `chatFeatureStatesEquivalent` 的比较逻辑而被丢弃。

### Suggested fix
- 检查 `chatFeatureStatesEquivalent` 的比较逻辑，确保新增的 history entry 不会被误判为等价
- 或者在增量更新路径中跳过 `chatFeatureStatesEquivalent` 检查，直接 `setState`

---

## Bug 11 (Resolved on 2026-04-30): 两条 process 被合并成一条 — native structured output 路径只有一个 commentaryText 字段

### Symptom
两条独立的 process（如"我将要定位"和"我已经定位到"）在 inspector 里出现在同一条 entry 里，被拼接到一起。在气泡里是后面那段覆盖前面那段。

### Root cause

agent 输出 process 文字有两条路径，根因在 **native structured output 路径**：

**Native structured output 路径**（当前主要使用的路径）：

`LiteLlmStructuredCompletion`（`LiteLlmGateway.kt:390-393`）只有一个 `commentaryText` 字段：

```kotlin
data class LiteLlmStructuredCompletion(
  val toolCalls: List<LiteLlmStructuredToolCall> = emptyList(),
  val finalText: String? = null,
  val commentaryText: String? = null,  // 只有一个字段
  val reasoningText: String? = null,
  ...
)
```

当 LLM 在一次响应中输出多段 commentary 时（如先输出"我将要定位"再输出"我已经定位到"），框架将它们合并成**一个字符串**存入 `commentaryText`。然后 `parseStructuredCompletion`（line 1254-1259）将这个字符串变成**一个** `AgentModelAction.Commentary`，最终产生**一个** `OpenCrayAssistantEvent`。

所以：
- **Inspector 里**：只有一条 event，body 包含合并后的文字 → 看起来像被拼接
- **气泡里**：draft stream 用 `pendingMessageId` 做 key，新文字替换旧文字（Bug 9）→ 后面覆盖前面

**Legacy JSON fallback 路径**（不走 native structured output 时）：

`extractJsonSequence`（line 3785-3833）能解析多个独立的 JSON 对象，每个 commentary JSON 产生独立的 `AgentModelAction.Commentary` → 独立的 `OpenCrayAssistantEvent`。这条路径下 inspector 里会是两条独立 entry。但气泡端仍然是 `pendingMessageId` 替换（Bug 9），所以气泡里还是后面覆盖前面。

### Suggested fix
- 方案 A（推荐）：native structured output 路径不要合并多段 commentary，让每段独立成 event。需要修改 `LiteLlmStructuredCompletion` 的结构，将 `commentaryText: String?` 改为 `commentaryTexts: List<String>` 或在解析时拆分
- 方案 B：在 event 端保持合并，但 inspector 端按换行符拆分显示为多条 entry
- 方案 C：让 draft stream 路径也保留历史（与 Bug 9 的修复方案合并）

---

## Bug 12 (Resolved on 2026-04-30): 流式传输不是逐字出现，而是"跳出来"

### Symptom
流式文字不是逐字逐句出现的，而是先显示几个字，然后一大段文字一口气跳出来。长文字尤其明显。

### Root cause

整条链路都是 **full-so-far 语义**（每次传递完整累积文字，不是增量 delta），加上多层 rate-limit 和 dedup，导致中间状态被吞掉。

**Choke Point 1：`VisibleTextSnapshotCoalescer`（75ms 节流 + full-so-far）**

`OpenAiCompatibleLiteLlmProviderClient.kt:5481-5522`：

```kotlin
private const val DEFAULT_STREAM_UPDATE_MIN_INTERVAL_MS: Long = 75L

fun update(text: String) {  // text = 完整累积文字，不是 delta
    pendingText = normalized
    emitIfEligible(force = lastEmittedText == null)
}

private fun emitIfEligible(force: Boolean) {
    if (now - lastEmittedAtEpochMs < minIntervalMs) return  // 75ms 内丢弃
    observer.onVisibleTextSnapshot(text)  // 传递完整累积文字
}
```

LLM 每 ~20-50ms 生成一个 token。在 75ms 窗口内，3-4 个 token 的完整文字被缓存但不发射。窗口到期时只发射**最后一个完整文字**，中间状态全部丢弃。

效果：用户看到 "H"（T=0），等 75ms，然后看到 "Hello world, this is"（T=75ms，跳了十几个字符）。

**Choke Point 2：`assistantDraftObserver`（又一层 dedup）**

`OpenCrayAgentRuntime.kt:1566-1594`：

```kotlin
override fun onVisibleTextSnapshot(text: String) {  // text = 完整累积文字
    if (normalized == lastVisibleDraftText) return  // 又一层 dedup
    eventSink.onAssistantDraftUpdated(text = normalized)  // 传递完整累积文字
}
```

**Choke Point 3：`updateAssistantDraft`（替换存储）**

`OpenCrayHostRuntime.kt:2860-2888`：

```kotlin
sessionDrafts[pendingMessageId] = updatedDraft  // 完整替换
```

**Choke Point 4：`_patchMessagesForLiveDraftEvent`（替换消息文本）**

`chat_feature_screen.dart:3542-3560`：

```dart
final ChatMessageData updatedMessage = ChatMessageData(
    text: text,  // 完整累积文字替换旧文字
);
```

**整条链路没有一个地方做增量 append**。每一层都是"用完整新文字替换完整旧文字"。75ms 节流 + 完整替换 = 跳跃式显示。

### Suggested fix
- 方案 A：降低 `minIntervalMs` 到 16-25ms（一帧 60fps），减少每个窗口内的 token 批量
- 方案 B：改为 delta 语义，coalescer 只发射新增字符，Flutter 端 append 而不是替换
- 方案 C：在 Flutter 端做字符级动画（typewriter effect），接收完整文字后逐字显示

---

## Bug 13 (Resolved on 2026-04-30): 第一条 process 气泡在第二条流式过程中被复制占位

### Symptom
当第二条 process 开始流式时，第一条 process 的文字被复制出来作为占位符显示。第二条 process 没有流式生成过程，等它完全生成后才替换掉占位符。同一个 process 重复出现，100% 复现。

### Root cause

`_patchMessagesForLiveDraftEvent`（`chat_feature_screen.dart:3516-3571`）的逻辑：

```dart
if (messageIndex >= 0) {
    // 找到了已有消息 → 替换其文字
    final ChatMessageData updatedMessage = ChatMessageData(text: text, ...);
    return [...messages.take(messageIndex), updatedMessage, ...messages.skip(messageIndex + 1)];
}
// 没找到 → 追加新的 ephemeral 消息
return [...messages, ChatMessageData(messageId: normalizedMessageId, text: text, isEphemeral: true)];
```

当第一条 process（"我将要定位"）的 draft 到达时，它被追加为一条 ephemeral 消息（`messageIndex < 0`）。

当第二条 process（"我已经定位到"）的 draft 到达时，**它有相同的 `pendingMessageId`**（因为是同一个 task），所以 `messageIndex >= 0`，走替换路径。替换时**旧消息的文字被新文字覆盖**。

但问题是：`_applyRuntimeActivityPatch`（line 3724）也会重建 messages 列表。它从 `_latestChatRuntimeSnapshot` 的 `liveAssistantDrafts` 构建 `projectedLiveDraftMessages`，这会创建**新的 ephemeral 消息**。同时 `_handleLiveAssistantDraftEvent` 也在修改 `_state.messages`。

两条路径同时修改 messages 列表：
1. `_handleLiveAssistantDraftEvent` → `_patchMessagesForLiveDraftEvent` → 替换/追加 ephemeral 消息
2. `_applyRuntimeActivityPatch` → `_mapRuntimeProjection` → `_mapMessages` + `_mapUnanchoredLiveDraftMessages` → 从快照重建所有消息

当第二条 process 的 draft 事件到达时，`_handleLiveAssistantDraftEvent` 替换了 pendingMessageId 对应的消息文字。但紧接着 `_applyRuntimeActivityPatch` 从快照重建消息列表，快照里的 `liveAssistantDrafts` 可能还包含第一条 process 的文字（因为快照构建有延迟），导致第一条的文字被重新创建为一条消息。

两条消息同时存在 → 用户看到重复。等快照更新后才统一。

### Suggested fix
- `_applyRuntimeActivityPatch` 不应重建 messages 列表，只重建 runTraces。messages 的更新由 `_handleLiveAssistantDraftEvent` 负责
- 或者 `_mapUnanchoredLiveDraftMessages` 在构建 projectedLiveDraftMessages 时，跳过已有 pendingMessageId 对应的消息

---

## Bug 14 (Resolved on 2026-04-30): Inspector 比 process 气泡慢很多

### Symptom
inspector 现在会更新了，但比 process 气泡慢很多，不是实时的。

### Root cause

Process 气泡和 inspector 走的是**两条完全不同的路径**，处理开销差异巨大：

**Process 气泡路径（轻量）：**

```
Kotlin onAssistantDraftUpdated
  → synchronized(lock) { 检查 session }     ← 轻量
  → updateAssistantDraft()                    ← O(1) map 替换
  → emitLiveAssistantDraftEvent()             ← 传文字
  → Flutter _handleLiveAssistantDraftEvent
    → _patchMessagesForLiveDraftEvent()       ← O(n) find + 替换单条消息
    → setState()                              ← 立即
```

**Inspector 路径（重量）：**

```
Kotlin onRunEvent
  → synchronized(lock) {
      recordRuntimeEventLocked()
      buildRuntimeTaskDeltaPayload()          ← 构建 run snapshot + 序列化
    }
  → emitRuntimeEventDelta()
  → Flutter _handleRuntimeEventDelta
    → _mergeRuntimeSnapshots()                ← O(runs × events) 合并去重
    → shouldReplaceObservedRuntimeSnapshot()  ← 比较 + 可能 jsonEncode
    → _applyRuntimeActivityPatch()
      → _resolveRuntimeSnapshot()
      → _mapRuntimeProjection()
        → _mapRunTraces()                     ← 遍历所有 run，每个重建 history
        → _mapMessages()                      ← 重建所有消息
      → setState()
```

关键瓶颈：
1. **Kotlin 侧**：`buildRuntimeTaskDeltaPayload`（line 2706-2734）在锁内构建 run snapshot + 序列化
2. **Flutter 侧**：`_mergeRuntimeSnapshots` 合并两个完整快照，`_mapRunTraces` 重建所有 run trace（遍历 events、排序、映射 history entries）
3. **`shouldReplaceObservedRuntimeSnapshot`**（line 920-971）：级联比较，最终 fallback 是对整个快照做 `jsonEncode` 比较（line 970-971）

Process 气泡只需要 O(n) 的消息列表查找 + 替换单条消息文本。Inspector 需要 O(runs × events) 的全量重建。当 events 列表增长时，差距越来越大。

### Suggested fix
- inspector 的 runTrace 重建应该是增量的：新 event 到达时只 append 一条 history entry，不从零重建
- `_mergeRuntimeSnapshots` 应该是 append-only 的，不做完整的去重合并
- `shouldReplaceObservedRuntimeSnapshot` 不应该用 `jsonEncode` 做 fallback 比较
