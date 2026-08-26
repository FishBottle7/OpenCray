# Agent Core 全盘 Bug 审计

日期：2026-08-25
状态：待排期
范围：`runtime/`、`core/`、`llm/`、`persistence/`、app 模块 LLM provider client（OpenAiCompatibleLiteLlmProviderClient + 三个 dialect）
方法：5 路并行深扫（主运行时 / core 编排 / 工具执行链 / LLM 网关链路 / 持久化与压缩），逐文件通读。

## 结论摘要

共确认 45 条问题：P0 × 3、P1 × 17、P2 × 21、P3 × 2（另附各路"排查过但无问题"清单）。
四组共享根因解释了"运行起来磕磕绊绊"的整体感受：

1. **错误吞噬文化**：`catch(Throwable)` 兜底、`decodeRecordOrNull` 吞一切异常、`runCatching{...}.getOrElse{return}`——大量静默失败点让故障不可诊断。
2. **取消信号未贯通**：`hooks.isCancellationRequested` 只在零散检查点生效；LLM 在途读、并行工具分派、托管进程三条路径完全不响应取消。
3. **容错策略分裂**：同一 store 读路径抛异常、写路径静默清零；叠加 `ignoreUnknownKeys=false` + `schemaVersion` 从不校验，降级安装必炸且无自愈。
4. **文件持久化非原子**：tmp 写入无 fsync 即 ATOMIC_MOVE，掉电产生空/损坏文件，再连锁触发清零类 bug 造成数据丢失。

建议修复顺序：P0-1（一行改动收益最大）→ P0-2 → 取消贯通三连（T-03/T-04/G-08）→ WebSearch policy 绕过 → SessionQueue 竞态。

---

## P0 —— 崩溃 / 数据损坏

### P0-1 Edit/MultiEdit 单次替换走了 Java 正则语义，可静默改错位置或抛异常导致数据损坏

- 位置：`runtime/src/main/kotlin/com/opencray/runtime/ToolResultRenderers.kt:144`（`applyTextEdits`，被 `AgentTooling.kt:1969/2000` 的 Edit/MultiEdit 调用）
- 问题：Kotlin 标准库没有字面量版 `replaceFirst(String, String)`，该调用解析到 `java.lang.String.replaceFirst(regex, replacement)`：`old_string` 被当作**正则**、`new_string` 被当作**替换模板**。而前面的 `countOccurrences`（151 行）按字面量计数，两者不一致。后果三种：
  - `old_string` 含 `(`、`[`、`*`、`+` 等元字符 → 抛 `PatternSyntaxException`；
  - 合法但语义不同的模式 → 匹配到错误位置，**静默写坏文件**；
  - `new_string` 含 `$` 或 `\`（如 `${var}`、`$HOME`）→ 抛 `IllegalArgumentException("Illegal group reference")`。
- 触发条件：任何一次 Edit 的 old/new 含正则元字符或 `$` —— 代码编辑场景极常见（函数调用括号、数组下标、shell 变量）。注意 `replaceAll` 分支用的 `replace(String,String)` 是 Kotlin 字面量重载，是正确的，只有单次替换路径出错，更隐蔽。

### P0-2 聊天工作区文件解码失败被静默清空并覆盖写回 —— 全量聊天记录永久丢失

- 位置：`persistence/.../store/file/JsonFileStores.kt:243-260`（`decodeRecordOrNull` 吞掉一切异常返回 null）+ `persistence/.../store/file/JsonFileChatWorkspaceStore.kt:33-46` + `app/.../ChatWorkspaceRecordSupport.kt:23-42`
- 问题：`updateRecord` 内部对损坏/不可解码 JSON 静默返回 `null`，而 `loadWorkspaceOrCreate()` 收到 `transform(null)` 后会生成全新空工作区并**写回磁盘覆盖原文件**，所有会话、消息、模板无提示销毁。恶化因素：`PersistenceJson.kt:11` 设了 `ignoreUnknownKeys = false`，且所有记录 `schemaVersion` 恒为 `CURRENT=1`（`JsonFileStores.kt:185/254` 判等后跳过迁移），新增字段版本写入后回退旧版本必然解码失败。
- 触发条件：① 新旧版本混用/降级（未知字段导致 `SerializationException`）；② 文件为空白或半损坏（旧版本写入器、磁盘满残留）。App 下一次打开聊天页即触发清空。

### P0-3 仅含 reasoning 的成功响应直接 error() 炸掉整轮 run

- 位置：`runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt:964`（`error("Parsed gateway result is null despite visible output.")`），根因在 `ModelActionParser.kt:51-85` 与 `AssistantDraftExtractor.kt:134-141` 判定不一致
- 问题：`parseGatewayResultActionBatch` 在 `completion.rawText` 为空且 `outputText` 为空时返回 `null`——即使 `reasoningText` 非空（`parseStructuredCompletion` 因无 toolCalls/finalText 返回 null）。而前置守卫 `isSuccessfulEmptyResponse` 用的是 `hasVisibleOutput`，它把 `reasoningText` 视为"可见输出"。结果：reasoning-only 响应既不算空响应（走不了恢复路径）、又解析不出动作，直接落入 `error(...)` 抛 `IllegalStateException`。
- 触发条件：推理型模型经某路由只返回 reasoning 通道内容（截断或路由实现差异），status=SUCCESS。本可走 empty-response 恢复的场景变成 `RUNTIME_EXCEPTION` 硬失败。

---

## P1 —— 功能明显受损 / 安全缺口

### 运行时主循环

#### T-01 异常退出路径不清理后台 sub-agent，孤儿执行继续烧 token

- 位置：`OpenCrayAgentRuntime.kt:1151-1162`（finally 只在 `cancelOpenSubAgentsOnExit` 为 true 时取消；该标志仅在用户取消路径 552、826 被置位）
- 问题：`executePromptTask` 中任何未预期异常（包括 P0-3 的 `error()`、并行分派抛出的 `InterruptedException`）穿透重抛，但 finally 不取消已在后台运行的 sub-agent。`InMemorySubAgentExecutionCoordinator.activeExecutionsByKey` 中的执行不会被移除，子线程继续循环调用 gateway.execute。
- 触发条件：父 run 启动后台/分离 sub-agent 后任意运行时异常导致 run 失败。后果：进程存活期间子任务持续消耗 LLM 配额、executor 线程与 handle 状态泄漏。

#### T-02 wait_agent 被中断后返回伪 SUCCESS

- 位置：`subagent/SubAgentHandleLifecycleTooling.kt:613-615`（吞掉 InterruptedException 直接 return）、`OpenCrayAgentRuntime.kt:4986-4999`
- 问题：等待子任务时线程被中断，`waitForStableSubAgentExecution` 捕获中断后静默返回，handle 仍是 BACKGROUND_RUNNING，再次等待又立即被中断，最终 `storedSubAgentHandleResult` 走 `else -> AgentToolResultStatus.SUCCESS` 分支——把仍在运行的子任务当成功提交进转录。when 兜底应为 FAILED 或显式处理 RUNNING 态。
- 触发条件：父 run 线程在 wait_agent 阻塞期间被 `Thread.interrupt()`。

#### T-03 并行工具分派不可取消、无超时

- 位置：`OpenCrayAgentRuntime.kt:3318-3358`
- 问题：`futures.map { it.get() }` 无超时、两次 get() 之间不检查 `hooks.isCancellationRequested()`。任一并行只读工具（WebFetch/WebSearch 等）阻塞则整轮 run 无限挂起，仅线程中断能打破。对比串行路径和 LLM 重试都有 250ms 分片的取消轮询。
- 触发条件：`parallelToolCalls=true` 且任一并行工具阻塞不返回。

### 编排层

#### Q-01 重试分支覆盖未决的取消请求（取消丢失）

- 位置：`core/src/main/kotlin/com/opencray/core/orchestrator/SessionQueue.kt:587-602`
- 问题：`executeTaskAt` 执行完成后的处理顺序是先判断 shouldRetry（587 行）再处理取消（604-613 行）。任务处于 CANCEL_REQUESTED 且 runtime 同时通过 hook 调用 requestRetry 时，任务走 RUNNING → RETRY_PENDING → QUEUED，取消请求被静默丢弃并再次执行。对比同函数挂起分支（573-577 行）显式检查了 `lifecycleState != CANCEL_REQUESTED`，重试分支缺少同样守卫，明显遗漏。
- 触发条件：任务执行期间用户 requestCancel(taskId)，随后 runtime 因可重试错误调用 hooks.requestRetry 且返回非 SUCCESS/CANCELLED 结果。

#### Q-02 执行期间外部状态变更导致 transitionTaskLocked 的 require 抛异常，中断整个 drain

- 位置：`SessionQueue.kt:604-627`（配合 337-351 与 675-678）
- 问题：runtime 执行在锁外进行。此间另一线程可通过 reconcileFailure（允许 RUNNING → FAILED）改任务状态，还可继续 requestRetry 推到 QUEUED/RETRY_PENDING。执行线程返回后按"当前仍是 RUNNING"假设做收尾转换（如 → COMPLETED），ALLOWED_TASK_TRANSITIONS 不允许该迁移，require（675-678 行）抛 IllegalArgumentException。该异常未被捕获，冲出 drain()：本次结果丢弃、剩余队列任务不执行。
- 触发条件：双线程场景——drain 执行任务 T 时宿主/watchdog 对同一任务调用 reconcileFailure（可选再接 requestRetry）。

#### Q-03 RuntimeExecutionHooks 回调写普通局部 var，无内存可见性保证

- 位置：`SessionQueue.kt:552-562`（读取于 567-586）
- 问题：retryRequest/suspensionRequest 是 drain 线程栈上的普通局部变量。接口未约定 hook 必须在调用线程内同步回调；若 runtime 从工作线程调用 requestRetry/requestSuspend，与 drain 线程随后的读取之间没有 happens-before，写入可能不可见——应重试/应挂起的任务被标成 FAILED。
- 触发条件：runtime 从非 drain 线程触发 hook（接口契约未禁止）。

### 工具执行链

#### W-01 WebSearch 的 policy plan 无条件覆盖为 ALLOW，显式 DENY 被丢弃

- 位置：`AgentTooling.kt:599-618`（webSearchPolicyPlan），生效于 webSearch()(1767) 与 preflightWebSearch()(574)
- 问题：`basePlan.copy(policyDecision = PolicyDecision(ALLOW, "WEB_SEARCH_DEFAULT_ALLOW"))` 把 pipeline 刚算出的决策整体替换。`ToolPolicyEvaluator.mergePolicyDecisions`(ToolPolicyEvaluator.kt:196) 特意让 DENY 最高优先级，但任务级 DENY、modePolicy 对 NETWORK_ACCESS 的 DENY/ASK 全部被抹掉，gate 直接放行并发起真实网络请求；审计 metadata 记录的是伪造的 ALLOW 语义。违反 AGENTS.md「不得在工具处理器内手写 allow/deny」约束。
- 触发条件：任务带 DENY 决策或运行模式要求网络审批时模型调用 WebSearch 即绕过。

#### W-02 托管进程路径完全无视取消钩子；ProcessWait/Bash 等待可无限期阻塞

- 位置：`process/LocalManagedProcessController.kt:32-78,177-235`（构造参数无 hooks，monitor 循环只查超时）；`AgentTooling.kt:2098-2104`（wait_timeout_ms/process_timeout_ms 只要求 >0 无上限）；`AgentTooling.kt:2510`
- 问题：对比 `LocalCommandProcessRunner`(CommandExecutor.kt:161) 每 25ms 检查取消，托管进程控制器从不检查。用户停止任务后正在跑的 Bash 只能靠自身 timeout（默认 300s）结束；阻塞在 processRegistry.wait 的调用线程不受取消影响。
- 触发条件：模型传 `process_timeout_ms=999999999`（校验允许）+ 挂死命令 + 用户点停止 → agent 线程占死无法恢复。

#### W-03 进程树杀不干净：孤儿进程 + 收集线程永久泄漏

- 位置：`CommandExecutor.kt:133-176`；`LocalManagedProcessController.kt:192-196,237-245`
- 问题：Bash 实际启动 `sh -lc`/`powershell -Command`（ToolResultRenderers.kt:266），超时/取消/输出超限时 `destroyForcibly()` 只杀 shell 本体，孙进程持有 stdout/stderr 管道 → 两个收集线程永远阻塞在 `input.read()`（daemon 线程每次泄漏 2 个）；kill 后仅 waitFor(250ms) 一次无二次终止升级，直接子进程若未立即死亡也会以"已 TIMEOUT"返回而实际仍在运行。
- 触发条件：任何 spawn 后台子进程的命令（dev server、watcher、daemon）触发超时或输出上限即复现。

#### W-04 dispatch() 用 catch(Throwable) 兜底，吞 CancellationException 与所有 Error

- 位置：`AgentTooling.kt:366-377`
- 问题：所有工具执行的异常出口把 `CancellationException`、`InterruptedException`、`OutOfMemoryError` 统一转成 `status=FAILED, errorCode=TOOL_EXECUTION_FAILED` 的普通结果。协程取消信号被当成普通工具失败上报给模型，模型会继续下一轮动作而不是结束；OOM/StackOverflow 也被降级为一句话 error message。
- 触发条件：上游以协程取消/线程中断方式终止运行中的工具调用。

#### W-05 审批 token 是整个任务期的"万能令牌"

- 位置：`ModeGate.kt:131-152`；`AgentTooling.kt:2955`（commandApprovalToken 为静态配置不消费不过期）
- 问题：ASK 决策下只要 token 的 taskId 匹配就放行，不校验命令内容是否与用户当时看到的一致；配合 `ToolPolicyEvaluator.applyApprovedTaskOverride`(ToolPolicyEvaluator.kt:229-248) 把同任务后续所有 ASK 直接翻成 ALLOW。用户批准一条 `rm tmp/x` 后同任务内任意后续命令静默放行。
- 性质：安全设计缺口而非崩溃 bug。

### LLM / 网关链路

#### G-01 LOCAL_DELTA 续传合成 tool-call id 重编号冲突 → 整轮终态失败

- 位置：`GatewayMessagePlanner.kt:664、463-471`
- 问题：buildGatewayMessages 用 nextSyntheticToolCallSequence(transcript) 为无 id 的工具调用生成 oc-call-N，全量构建扫描整个 transcript 编号一致；但 buildLocalContinuationDeltaMessages 只传 delta 切片，编号从 1 重来。plan.messages = envelope.gatewayMessages + delta 两段拼进同一请求后出现重复 oc-call-N。后果链：invalidToolMessageContract（OpenAiCompatibleLiteLlmProviderClient.kt:1147-1174）判重复 → PROVIDER_REQUEST_INVALID_TOOL_CALL_ID Failure → LiteLlmGateway.kt:813-838 对 Failure 终态不 fallback → 错误码不在瞬态白名单（GatewayRecoveryPolicy.kt:245-259）→ 无重试本轮终止。
- 触发条件：非 Responses 协议 + 本地续传 envelope 已含 oc-call-* + delta 中出现无 id 的工具调用记录（legacy JSON 回退模式典型形态，OpenCrayAgentRuntime.kt:4697-4712 允许 id 为空）。第二次带工具调用的续传轮即可命中。

#### G-02 Anthropic 流式 message_delta 的 usage 整体覆盖 message_start，input/cache token 全丢

- 位置：app/.../facade/llm/AnthropicDialect.kt:1030-1035（覆盖）、979（message_start 写入）
- 问题：message_start 把含 input_tokens、cache_read_input_tokens、cache_creation_input_tokens 的完整 usage 放入 payload；随后 message_delta 用 `payload.put("usage", ...)` 整体替换（Anthropic 的 message_delta.usage 通常只有 output_tokens）。流式结束后 anthropicPromptCacheUsage(:214-245) 读不到 cache 字段返回 null → PROVIDER_PROMPT_CACHE_USED/READ_TOKENS/WRITE_TOKENS metadata（OpenAiCompatibleLiteLlmProviderClient.kt:794-823）全部缺失，缓存命中监控/成本核算对流式 Anthropic 全部失真。应为字段级 merge。
- 触发条件：protocol=anthropic 且走流式（route stream=true 或 Kimi 模型自动流式 :1116-1123）。

#### G-08 用户取消不会中断在途 LLM 请求，连接持续读到 readTimeout

- 位置：`OpenAiCompatibleLiteLlmProviderClient.kt:148-271`（execute 无任何取消钩子）；`OpenCrayAgentRuntime.kt:1380-1387`（仅在 gateway.execute 前后检查取消）
- 问题：取消信号只在两次网关调用之间生效。进入阻塞读后 BufferedReader.readLine() 一直读到服务器结束或 readTimeout（交互路由 120s，LlmProviderRequestSupport.kt:278）才返回，之后才检查取消。移动场景下用户取消后流量/电量继续消耗最长约 2 分钟。
- 触发条件：生成进行中用户取消运行。

### 持久化

#### D-01 MemoryWriter supersede 序列非事务，中断即丢用户偏好

- 位置：`memory/MemoryWriter.kt:26-51`
- 问题：第 32 行先把所有同 key 旧 ACTIVE 记录 upsert 成 status=resolved + superseded_by=<新id>，第 50 行才写替代记录。两步之间进程被杀（Android 后台随时可能）或中途抛异常，旧值已永久失效、superseded_by 指向不存在的记录，偏好从召回中彻底消失（MemoryRetriever.kt:172 过滤 RESOLVED）无法回滚。另外整个方法基于 store.list() 快照做多条 upsert，并发写方（memory.json 为全局共享存储）会产生基于过期数据的解决/版本回退。
- 触发条件：候选写入循环中途进程死亡；或两个 session 并发 flush 同一偏好 key。

---

## P2 —— 边缘场景 / 可观测性 / 性能

### 运行时主循环

| # | 问题 | 位置 | 说明 |
|---|------|------|------|
| T-04 | 审批挂起/恢复 toolCallCount 双重计数 | `OpenCrayAgentRuntime.kt:4178` vs `3560` | resume state 存 toolCallCount+1 预补偿，恢复后重新执行再 +1，同一逻辑调用净计 2 次，污染 maxToolCalls 预算 |
| T-05 | applyMidTurnMaintenance 吞一切异常 | `OpenCrayAgentRuntime.kt:3115-3128` | `runCatching{...}.getOrElse{return}` 无事件无日志，维护回调部分副作用后静默放弃 |
| T-06 | provider metadata 可覆盖运行时计算的结果键 | `OpenCrayAgentRuntime.kt:2036-2040` | gatewayResult.metadata 后置 put 同名即覆盖 turnCount/toolCallCount 等，emitLifecycleEvent:4360 用 turnCount 反推 turn，provider 侧可控 metadata 可扭曲生命周期事件与遥测 |
| T-07 | checkpoint 归一化对非原始类型 metadata 直接崩溃 | `OpenCrayPromptResumeState.kt:670-672` | toStringMap() 用 jsonPrimitive.content，遇嵌套对象抛 IllegalArgumentException；每次 checkpoint 发射都执行；恢复外来/旧版本含非原始值 metadata 的状态即硬崩（对比 OpenCrayAgentRuntime.kt:2750-2752 有 as? JsonPrimitive 过滤） |
| T-08 | 空 content 工具结果使 Responses 待发消息序列化崩溃 | `OpenCrayPromptResumeState.kt:94` + `OpenCrayAgentRuntime.kt:3538-3559` | require(content.isNotBlank())；Responses 协议 + 带 lineage + 任一工具返回空白 content，下一次 checkpoint 编码即 IllegalStateException |
| T-09 | synchronizedSubAgentHandles 写回覆盖竞态（丢失更新） | `SubAgentHandleLifecycleTooling.kt:1001-1019` | 读→取最新→无条件写回，窗口期内后台子任务完成线程写入的 finalized handle 被过期 RUNNING 句柄覆盖；协调器侧下次同步可自愈，但 checkpoint 可能持久化过期状态 |
| T-10 | 父子 runtime 共享 workingStateStore 互相污染 | `OpenCrayAgentRuntime.kt:4948-4974`（copy 未替换 store）、641 | 后台子任务 persistWorkingStateSnapshot 会用子任务视角整表覆盖，父任务下回合可能把子任务 observation 注入父提示词上下文（若为刻意设计需注释明确） |
| T-11 | 流式草稿转义解析破坏 \uXXXX | `AssistantDraftExtractor.kt:343-356` | partialJsonStringFieldValue 把 `\u00e9` 变成字面 u00e9，仅影响 UI 草稿预览不影响最终解析 |
| T-12 | 已关闭 sub-agent handle 及子转录永久驻留内存 | `SubAgentExecutionCoordinator.kt:156/183-193` | closedHandlesByKey 仅同 key 再 upsert 时清除，close_agent 后永不驱逐；handle 可能携带完整子转录，移动端长会话多次 spawn/close 线性累积内存 |

### 编排层

| # | 问题 | 位置 | 说明 |
|---|------|------|------|
| Q-04 | 构造函数拒绝跨 session 快照后仍覆写存储 | `SessionQueue.kt:182-189、412-413、920-922` | restoreLocked 发现 id 不匹配跳过恢复，构造器随后无条件 persistSnapshotLocked 用空快照抹掉外来快照，防御检查与破坏性写入自相矛盾 |
| Q-05 | catch(Throwable) 吞 OutOfMemoryError/StackOverflowError | `SessionQueue.kt:653-664` | 致命 VM 错误转成普通 FAILED，drain 继续跑并在可能损坏的状态下持续写盘 |
| Q-06 | ContractJson 严格模式 + schemaVersion 从不校验 | `ContractJson.kt:8-13` | ignoreUnknownKeys=false 使旧版解析新版快照直接抛异常；schemaVersion 字段任何解析路径都不校验，形同虚设 |
| Q-07 | 每次微转换都在全局锁内全量同步持久化快照 | `SessionQueue.kt:746、762、780、893、920-922` | 文件型 store 在高频转换下（重试循环单任务一次 drain 可 6+ 次写盘）有 ANR 隐患；store 无事务契约，中途被杀留撕裂快照，下次 init 反序列化失败丢整个队列 |
| Q-08 | InMemorySessionQueueSnapshotStore 自身无同步 | `SessionQueue.kt:85-99` | public API，外部绕过队列锁直接轮询 store 存在数据竞争 |

### 工具执行链

| # | 问题 | 位置 | 说明 |
|---|------|------|------|
| W-06 | 文件读取全量载入内存后才截断；truncated 标志字节/字符单位不一致 | `AgentTooling.kt:800-804、920-934` | 先 readAllBytes（无上限）再 take(maxReadBytes)；truncated 按**字节**判断截断按**字符**执行，UTF-8 多字节偏差可达数倍；大文件撑爆堆。Edit/MultiEdit(:1968/:1999) 同样全量读入 |
| W-07 | Grep/Glob 先物化整棵目录树再匹配 | `ToolResultRenderers.kt:52-84`，调用点 `AgentTooling.kt:1648/1722` | Files.walk 全量收集到 List 才开始匹配，maxResults 早退只省匹配阶段不省遍历/内存 |
| W-08 | 进程注册表淘汰策略可把 RUNNING 控制器挤出追踪表 | `AgentProcessRegistry.kt:424-434` | 超 maxTrackedProcesses=64 且无终态可删时 firstOrNull() 直接删最旧（可能是 RUNNING），OS 进程失控成不可管理孤儿 |
| W-09 | 注册表 start 先创建真实进程再做重复 ID 校验 | `AgentProcessRegistry.kt:377-384（InMemory）、474-484（FileBacked） | 碰撞时已启动进程既未注册也未 terminate；当前随机 UUID 概率低，自定义 id 必现 |

### LLM / 网关链路

| # | 问题 | 位置 | 说明 |
|---|------|------|------|
| G-03 | SSE 流内 error 事件被归类 PROVIDER_TRANSPORT_ERROR（可瞬态重试） | `OpenAiChatDialect.kt:955-961`、`OpenAiResponsesDialect.kt:860-866`、`AnthropicDialect.kt:1037-1043` → `OpenAiCompatibleLiteLlmProviderClient.kt:256-268` → `GatewayRecoveryPolicy.kt:247` | 流中 error 抛 IllegalStateException 被统一捕获成 transport error 判瞬态；context length exceeded、鉴权失效等永久性错误按固定延迟重试满次数，已推 UI 的部分文本清掉重来；同样的错误走 HTTP 400 则不重试，分类自相矛盾 |
| G-04 | OpenAI Chat Completions 流式从不发送 stream_options.include_usage | `OpenAiChatDialect.kt:41-81` | 官方流式响应默认不含 usage；读取端 copyJsonFieldIfPresent(:969) 恒读不到 → token/缓存统计静默为空 |
| G-05 | Failure 一律终态不走 fallback；主路由 5xx 永远打满重试预算 | `LiteLlmGateway.kt:813-838`、`ProviderRouting.kt:13-35` | 只有 Timeout 和 429 触发 FallbackTrigger；HTTP_5xx/TRANSPORT_ERROR 立即终态 FAILED。运行时虽判可重试但每次从 routeIndex=0 重来，多路由 profile 下健康 fallback 永不被尝试直到预算耗尽暂停会话 |
| G-06 | Anthropic 流式 tool_use 参数解析失败静默降级 {}，工具带空参执行 | `AnthropicDialect.kt:1011-1015（buffer）、1018-1027（getOrDefault(JSONObject())） | input_json_delta 拼出的 JSON 在 content_block_stop 解析失败直接放空对象不记 toolCallErrors；对比 OpenAI 路径会记录 parse 错误并触发结构化恢复流程，Anthropic 路径把参数损坏的工具调用当合法执行 |
| G-07 | deriveContextCacheBreakReason 遗漏多个可达 FULL_REBUILD reason | `ContextCacheBreakReason.kt:8-25（else -> null） | buildResponsesGatewayMessagePlan 可产出 responses_no_pending_messages（GatewayMessagePlanner.kt:136-138）、responses_pending_tool_result_duplicate_call_id(:139-143)、responses_pending_*(:297-315)、responses_continuation_disabled(:119-121)，均走 fullGatewayMessageRebuild（丢 previous_response_id 链、全量重放计费）但映射落 else -> null，诊断 contextCacheBreakReason=null，cache miss/token 浪费被系统性低估 |

### 持久化 / 压缩 / 记忆

| # | 问题 | 位置 | 说明 |
|---|------|------|------|
| D-02 | 压缩双写不原子：先落摘要后截断转录，中断产生重复压缩 | `compaction/DurableCompaction.kt:379 / 408-417 / 435-442` | 第 435 行先把摘要追加 compactionStore，第 442 行才 replaceReplayWorkingCopy 截断；两步间进程死亡重启后同段消息再压一遍，摘要重复、compactedMessageCount 双倍计入。且全流程用快照做全量替换（379→442），压缩期间（远端调用耗时长）并发 append 的新消息被静默丢弃 |
| D-03 | session/memory 记录损坏时 load() 抛 IllegalStateException 无自愈 | `JsonFileStores.kt:191-195（readRecord 抛出） vs 252-260（decodeRecordOrNull 吞掉） | 同一 store 读路径行为分裂：直接 list()/load()（JsonFileMemoryStore.list():96-99、JsonFileSessionStore.load()）遇坏 JSON 直接抛异常，坏一个字节即可让依赖 list() 的功能永久崩溃直到手动删文件 |
| D-04 | FileBackedSessionCompactionStore 解码失败静默归零，摘要档案无声消失 | `FileBackedSessionCompactionStore.kt:53-64 + 26-41` | decodeRecord 失败 getOrDefault(空记录)，下一次 update/save 把空记录写回；原消息早已在转录中截断，唯一剩余信息就是该摘要文件 → 历史彻底消失且无告警 |
| D-05 | 原子重命名前缺 fsync，掉电可致目标文件空/损坏 | `DirectoryDurableTextStorage.kt:106-119` | tmp.writeText() 未 fd.sync() 即 Files.move(ATOMIC_MOVE)；类注释自称 "durable, restart-safe"，实际达不到。掉电后命中 P0-2/D-03/D-04 形成连锁数据丢失 |
| D-06 | 已过期删除的任务承诺被 MemoryFlushCoordinator 复活 | `MemoryFlushCoordinator.kt:158-161 / 214-216 / 256-259` 与 `TaskCommitmentResolver.kt:210-216` | syncFlushedCandidateRecordIds 用 retainAll(现存id) 把 TTL 删除的承诺 id 移出已刷新集合；集合变空绕过 ALREADY_FLUSHED 短路（162 行），同批旧证据重新写出拿到新时间戳和 TTL，僵尸承诺反复重生 |

---

## P3 —— 低危 / 改进项

| # | 问题 | 位置 | 说明 |
|---|------|------|------|
| G-09 | Retry-After 解析只支持 delta-seconds，退避无指数增长/抖动 | `OpenAiCompatibleLiteLlmProviderClient.kt:954-957`、`GatewayRecoveryPolicy.kt:19-23` | toLongOrNull() 无法解析 HTTP-date 形式 Retry-After → 退化为固定延迟；重试延迟恒定不随连续 429 增长 |
| G-10 | chat 流式 tool_call 的 function.name 采用追加拼接 | `OpenAiChatDialect.kt:1045-1047` | 规范下 name 只在首个 delta 出现一次等价于赋值；对每 chunk 重发完整 name 的非规范兼容实现会拼成 get_weatherget_weather 导致工具不存在 |

---

## 各路排查过但确认无问题的部分

- **锁顺序**：runtime 模块 cursor 锁与 sub-agent 协调器锁无嵌套交叉，无死锁反转。
- **SessionQueue 串行路径**：无重复执行/丢任务（drainInProgress 守卫 + 索引锁内二次校验正确）；重启恢复归一化（RUNNING/CANCEL_REQUESTED/RETRY_PENDING → FAILED + RESTART_REQUIRES_EXPLICIT_RETRY）防止静默重跑，nextEnqueueOrder 无回退风险。
- **kotlinx.serialization init 校验**在反序列化路径同样生效（fail-closed）；Transcript store 各方法加锁返回副本无泄漏；McpTransportDescriptor 多态判别符配置正确。
- **WorkspaceBoundary**：`..` 段拒绝 + 最近存在祖先 toRealPath() 规范化对符号链接逃逸防护正确。
- **ToolArgumentParsers**：非预期 JSON 类型均抛 IllegalArgumentException 并被 dispatch 统一转 FAILED，无强转崩溃路径。
- **AgentTodoStore**：AtomicReference 整体校验后原子换入，snapshot 返回拷贝，无丢失更新/撕裂状态。
- **JSON 序列化往返本身**：emoji/中文/长文本无损，模型字段均有默认值；问题仅在容错策略而非编解码正确性。
- 审查范围内无 SQLite 实现（纯 JSON 文件存储），"缺事务"类问题以文件原子性形式呈现。

## 附：范围说明

llm/src/main/kotlin 仅 4 个文件（LiteLlmGateway、ProviderRouting、LiteLlmMetadataKeys、LiteLlmBuiltinWebSearchObservation）；真正的 HTTP/SSE provider client 在 app 模块（OpenAiCompatibleLiteLlmProviderClient.kt + OpenAiChatDialect / OpenAiResponsesDialect / AnthropicDialect），已一并纳入 G 系列。

---

## 修复对照表（2026-08-26 更新）

全部 45 项已修复，另加 1 项存量失败（ServiceOwnedChatRuntimeGatewayTest）。commit 映射：

| 区域 | 条目 → commit |
|------|--------------|
| P0 | P0-1→449561c · P0-2→4f7cc4e · P0-3→2c7a9a9 |
| T 系列 | T-01/02/03→e4ace23 · T-04/05/06/10→51fa62d · T-07/08/11→402218d · T-09/12→8b853ed |
| Q 系列 | Q-01/05→e6e4c21 · Q-04/06/07/08→1c9cbf8 · Q-02/03→af694a0 |
| W 系列 | W-01/04→1064a25 · W-02/03→e852027 · W-06/07→e3d6e56 · W-08/09→011029b+9d05fff · W-05→910083f |
| G 系列 | G-01/07→65aac0e · G-02/04→c8c7d2e · G-03/05/06→b27b151 · G-09/10→f8b9d3c · G-08→ffc43b1 |
| D 系列 | D-01/06→e9c203f · D-02/04/05→bc55f2e · D-03→e5e5a71 |
| 存量 | ServiceOwnedChatRuntimeGatewayTest→8aaf0aa |

### 遗留跟进项（非审计编号）

1. 单 handle 版 synchronizedSubAgentHandle 与复数版同构竞态未修（超当时范围）。
2. 	runcateToReadBudget 死代码待清理；Read 工具大文件 	otalLineCount 语义变为窗口内行数，待产品确认。
3. SessionQueue 每实例一个常驻持久化线程；自定义 store 回调队列公共方法会自锁死（现无此模式）。
4. 进程注册表满载 fail-closed：极端长任务下 Bash 会收到明确失败（原为静默挤掉 RUNNING）。
5. W-05 内容绑定休眠中：生产端尚无 commandApprovalToken 签发方，app 层接入审批签发时激活。
6. PROVIDER_REQUEST_CANCELLED 未注册 UserFacingErrorCodes（当前路径不产生用户可见文案，如未来直连网关渲染失败需补注册）。

### 验证状态

runtime / core / persistence / llm 全量绿；app 全量在 HEAD 验证绿。工作区中用户进行中的 feature（workspace 导出分享、通知、凭证脱敏、媒体任务令牌等）存在 15 个相关测试失败（ScheduledTaskWorkManagerTest、OpenCrayRuntimeServiceInteractiveRepairTest、AgentBootstrapExecutionControllerTest、ChatSessionDeleteReliabilityTest、ServiceOwnedChatRuntimeGatewayTest 各部分），经 HEAD worktree 对照确认为该 WIP 引入，随其开发收敛。
