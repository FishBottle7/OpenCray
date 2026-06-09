# Agent Runtime Review 2026-05-03

范围：
- `core/src/main/kotlin/com/opencray/core/orchestrator`
- `runtime/src/main/kotlin/com/opencray/runtime`
- `app/src/main/kotlin/com/opencray/app`

方法：
- 使用 3 个 `gpt-5.4` subagent 并行审查队列层、runtime 层、app/service 恢复层。
- 下面只记录会导致运行逻辑错误的问题；均已做本地代码交叉复核。
- 未做代码修复；本文档仅记录 findings。

## High

### 1. Prompt 恢复时丢失 `responsesLineageTrusted`，会破坏 Responses 原生续跑

- 影响：checkpoint 已经明确保存了“当前 lineage 可以信任并继续”，但恢复执行时又被硬编码改回 `false`。结果是恢复后的 prompt run 不能继续走原生 Responses continuation，而会退回 full replay / 重建请求路径。
- 证据：
  - `OpenCrayPromptResumeState` 持久化了该字段：`runtime/src/main/kotlin/com/opencray/runtime/OpenCrayPromptResumeState.kt:364-379`
  - checkpoint 写回时也保存了该字段：`runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt:8566-8568`
  - 但恢复初始化 cursor 时没有读取保存值，而是直接用 `config.promptResumeState == null`：`runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt:305-322`
  - 后续 lineage 判定依赖这个布尔值：`runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt:2348-2351`
- 旁证：
  - 相关测试明确期望恢复态保留该标记：`runtime/src/test/kotlin/com/opencray/runtime/OpenCrayAgentRuntimeTest.kt:1875-1884`

### 2. 处于审批暂停状态的 detached subagent recovery run 在进程重启后无法恢复

- 影响：如果 detached subagent recovery task 已经跑到“等待审批/手动恢复”，应用进程重启后，run 记录还能看到，但无法再 `resume`，也无法从内存中重新挂回 recovery driver，等于卡死。
- 证据：
  - `ManagedAgentSessionHandle` 启动时只恢复 `runRecordsById`，不会重建 `SessionSubAgentRecoveryDriver` 的 `tasksByTaskId`：`app/src/main/kotlin/com/opencray/app/AgentSessionRuntimeManager.kt:517-555`, `app/src/main/kotlin/com/opencray/app/AgentSessionRuntimeManager.kt:587-591`
  - `requestResumeTask()` 走到 subagent recovery 分支时，必须先在 driver 的内存 map 中找到任务：`app/src/main/kotlin/com/opencray/app/AgentSessionRuntimeManager.kt:1044-1072`, `app/src/main/kotlin/com/opencray/app/SessionSubAgentRecoveryDriver.kt:153-175`
  - `listDetachedControlTasks()` 也只读 driver 的内存态：`app/src/main/kotlin/com/opencray/app/AgentSessionRuntimeManager.kt:1198-1200`
  - bootstrap 只会为 `BACKGROUND_QUEUED` 且 `pendingApprovalResume == null` 的 handle 重新建 recovery task；已经进入审批暂停态的 recovery task 不会被重建：`app/src/main/kotlin/com/opencray/app/AgentSessionRuntimeManager.kt:1202-1248`
- 旁证：
  - 同进程内按 `taskId` 恢复的 happy path 已有测试，但重启后没有等价恢复链路：`app/src/test/kotlin/com/opencray/app/AgentSessionRuntimeManagerTest.kt:273-345`

## Medium

### 3. 通用 detached control task 也有同样的重启恢复断层

- 影响：generic detached control task 一旦在“等待审批/手动恢复”期间发生进程重启，run snapshot 仍然可见，但后续既不能 resume，也不能 cancel。
- 证据：
  - 仅恢复 run record，不恢复 `detachedControlTasksByTaskId`：`app/src/main/kotlin/com/opencray/app/AgentSessionRuntimeManager.kt:440-441`, `app/src/main/kotlin/com/opencray/app/AgentSessionRuntimeManager.kt:587-591`, `app/src/main/kotlin/com/opencray/app/AgentSessionRuntimeManager.kt:1496-1510`
  - cancel / resume 都要求先命中这张内存表，否则直接失败：`app/src/main/kotlin/com/opencray/app/AgentSessionRuntimeManager.kt:767-804`, `app/src/main/kotlin/com/opencray/app/AgentSessionRuntimeManager.kt:809-842`
  - 但 `currentRunSnapshots()` 仍会把这些持久化 run 暴露出来：`app/src/main/kotlin/com/opencray/app/AgentSessionRuntimeManager.kt:1346-1456`
- 旁证：
  - 同进程内 detached control 按 `taskId` 恢复的路径有测试：`app/src/test/kotlin/com/opencray/app/AgentSessionRuntimeManagerTest.kt:192-269`

### 4. 审批挂起时保存的 `toolCallCount` 少算一次，恢复后可能突破 `maxToolCalls`

- 影响：一个已经实际发生过的 tool call，在 approval suspend checkpoint 里没有计入 `OpenCrayPromptResumeState.toolCallCount`。恢复后 runtime 会把这次调用“白送”掉，导致工具预算校验失真。
- 证据：
  - 恢复 cursor 时直接使用 resume state 里的 `toolCallCount`：`runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt:305-308`
  - tool budget 校验使用这个计数：`runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt:4028-4035`
  - approval suspend 返回结果的 metadata 已经把计数加一：`runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt:4956-4963`
  - 但同时序列化进 `OpenCrayPromptResumeState` 的仍然是旧值，没有 `+ 1`：`runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt:4968-4973`

### 5. `requestResumeTask()` 重新排队时没有清掉旧的审批错误码，重启后可能把已恢复任务再次识别成待审批

- 影响：审批通过后，task 虽然已经从 `SUSPENDED` 变回 `QUEUED`，但 `SessionQueueTaskSnapshot.lastErrorCode` 仍保留 `APPROVAL_REQUIRED`。如果这时应用重启，内存里的 approval registry 会丢失，而 approval lookup 仍会仅凭这个旧错误码把任务重新当成待审批对象。
- 证据：
  - `requestResumeTask()` 只改 lifecycle，不会覆盖旧错误：`core/src/main/kotlin/com/opencray/core/orchestrator/SessionQueue.kt:299-324`
  - `transitionTaskLocked()` 在未传入新错误时保留 `lastErrorCode/lastErrorMessage`：`core/src/main/kotlin/com/opencray/core/orchestrator/SessionQueue.kt:649-657`
  - approval lookup 不看 lifecycle，只要 `taskSnapshot.lastErrorCode` 命中审批错误码就认为还需要决策：`app/src/main/kotlin/com/opencray/app/ApprovalLookupSupport.kt:145-161`
  - runtime service 解析待审批对象时也依赖这套投影：`app/src/main/kotlin/com/opencray/app/RuntimeServiceApprovalDecisionAccess.kt:177-223`
- 旁证：
  - app 层测试已经明确要求 queued retry snapshot 不应继续暴露旧错误：`app/src/test/kotlin/com/opencray/app/AgentSessionRuntimeManagerTest.kt:1691-1744`

### 6. 显式 delegated-child 审批恢复只按 `agentId/childTaskId/childRunId` 匹配 handle，没有校验 `parentRunId`

- 影响：如果一个 session 内存在复用的 child 标识，批准某个 delegated child 的审批时，service 侧可能命中错误的 handle，并为错误的 `(parentRunId, agentId)` 创建 detached recovery task。
- 证据：
  - service 侧显式恢复先找“第一个匹配的 handle”：`app/src/main/kotlin/com/opencray/app/RuntimeServiceApprovalDecisionAccess.kt:237-280`
  - 但匹配函数完全不看 `parentRunId`：`app/src/main/kotlin/com/opencray/app/RuntimeServiceApprovalDecisionAccess.kt:433-442`
  - 而后续 detached recovery 的身份键实际是 `(parentRunId, agentId)`：`app/src/main/kotlin/com/opencray/app/SessionSubAgentRecoveryDriver.kt:54-66`, `runtime/src/main/kotlin/com/opencray/runtime/subagent/SubAgentExecutionCoordinator.kt:7-29`
- 旁证：
  - 相关测试也把 detached recovery identity 视为 `parentRunId + agentId`：`app/src/test/kotlin/com/opencray/app/OpenCrayRuntimeServiceHostTest.kt:369-389`

### 7. `SessionQueue` 自身不保证 `drain()` 互斥，多个调用者可以并行执行同一 session 的不同任务

- 影响：`SessionQueue` 的注释承诺“always serial and FIFO”，但实现没有阻止第二个 caller 在第一个 caller 已经进入 `RUNNING` 后再次进入 drain 循环。两个 caller 可以分别拿到不同 runnable task 并并行执行，破坏单 session 串行语义。
- 证据：
  - `drain()` 进入条件只排除了 `STOPPED` 和 `maxTasks == 0`，对“已经在 RUNNING”没有互斥：`core/src/main/kotlin/com/opencray/core/orchestrator/SessionQueue.kt:201-235`
  - task 选择和真实 runtime 执行分布在锁外：`core/src/main/kotlin/com/opencray/core/orchestrator/SessionQueue.kt:212-223`, `core/src/main/kotlin/com/opencray/core/orchestrator/SessionQueue.kt:465-563`
  - `transitionSessionStateLocked(RUNNING)` 在第二个 caller 进来时只是 no-op：`core/src/main/kotlin/com/opencray/core/orchestrator/SessionQueue.kt:787-795`
- 备注：
  - 当前 app 层的 `ManagedAgentSessionHandle.ensureProcessing()` 用 `processingLock` 做了一层串行保护：`app/src/main/kotlin/com/opencray/app/AgentSessionRuntimeManager.kt:721-757`
  - 但 `SessionQueue` 本身仍然不自洽；只要被第二个调用方直接使用，就会出现并发执行。

### 8. 默认的 `InMemorySubAgentExecutionCoordinator` 清理 parent run 时只删 handle，不删 active execution

- 影响：一旦调用 `retainKnownParentRuns()` 清掉了旧 parent run，活跃的 child execution 仍然留在 `activeExecutionsByKey`。后续这个 `(parentRunId, agentId)` 仍会被视为“已经在运行”，形成孤儿执行和错误阻塞。
- 证据：
  - `retainKnownParentRuns()` 只清 `handlesByKey`：`runtime/src/main/kotlin/com/opencray/runtime/subagent/SubAgentExecutionCoordinator.kt:155-166`
  - `activeExecution()` / `beginExecution()` 仍然读 `activeExecutionsByKey`：`runtime/src/main/kotlin/com/opencray/runtime/subagent/SubAgentExecutionCoordinator.kt:169-205`
- 备注：
  - app 主路径默认使用的是持久化 coordinator，而不是这个 in-memory 实现：`app/src/main/kotlin/com/opencray/app/AppAgentSessionTaskRuntimeFactory.kt:881-890`
  - 但 runtime 默认配置仍把它作为默认实现暴露出去：`runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt:149-151`

## 建议优先级

1. 先修复恢复链路会直接卡死用户操作的项：第 2、3、6 条。
2. 再修复会破坏 prompt continuation / tool budget 的项：第 1、4 条。
3. 然后处理会在重启或跨层投影中制造错误状态的项：第 5 条。
4. 最后补齐框架层一致性：第 7、8 条。
