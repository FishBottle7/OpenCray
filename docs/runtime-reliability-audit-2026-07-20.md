# OpenCray 主线运行可靠性审查

日期：2026-07-20  
状态：实施中  
决策：采用协议优先的根治方案，不再继续叠加基于时间戳、内容权重或监听器数量的合并启发式。

## 审查范围

本轮审查覆盖用户明确反馈的 restart、interrupt、流式消息和 Run Inspector，并向相邻边界扩展：

- SessionQueue、run record、managed process 的恢复一致性。
- Android runtime service、Binder、loopback HTTP fallback 的跨进程所有权和重绑。
- chat snapshot、runtime full snapshot、runtime delta、live draft 四条实时数据路径。
- Flutter bridge 生命周期、session 切换、乱序、断档和重同步。
- LLM provider 的流式配置、SSE 解析、draft 合并和非流式降级。
- 运行事件 journal、回放去重、截断、排序和 Inspector 投影。
- 现有回归测试是否使用真实生产协议和竞态时序。
- loopback HTTP 控制面的身份认证、资源上限和跨应用隔离。
- runtime listener、投影持久化和诊断链路的故障隔离。
- transport 启停竞态、executor 生命周期和 dispose 后回调。
- 生产构建中的日志内容、用户数据暴露和诊断最小化。
- Android release 合并清单、签名、Auto Backup 与依赖 AAR 供应链。
- scheduled automation 的并发触发、崩溃恢复与 exactly-once 边界。
- 会话删除、文件分享、通知身份和导出组件的跨边界安全性。

## 结论摘要

这些问题不是独立 UI 缺陷，而是两组共享根因：

1. 运行控制存在多个状态事实源。队列真实状态、run record、managed process 恢复结果和 UI 投影可能互相矛盾。
2. 实时界面存在多个无统一 revision 的数据源。客户端只能根据墙钟时间、列表长度和“信息量”猜测新旧，无法可靠处理跨通道乱序、断线重绑和重同步。
3. 观测、投影和诊断副作用与核心执行处于同一同步调用栈。任一 listener 或持久化异常都可能反向终止 task、draft 或 tool event 分发。
4. 本地 fallback 被当作可信进程内通道设计，但实际是固定端口的设备级 loopback HTTP 服务，缺少跨应用身份边界和资源边界。

## 已确认问题

### P0：恢复状态分裂导致 restart 必然失败

managed process 恢复修复会把 run record 投影为 `FAILED / PROCESS_INTERRUPTED_ON_RESTORE`，但 SessionQueue 中的 task 仍可能是 `QUEUED`、`SUSPENDED` 或其他非终态。UI 因此显示“运行已中断 / 重新启动”，而 `SessionQueue.requestRetry()` 只接受真实 `FAILED`，请求必然返回 false。

修复原则：恢复修复必须通过队列所有者执行真实状态迁移，再生成 run projection；不得只改 run record 的展示结果。

### P0：interrupt 存在 TOCTOU、假成功和未 settled 语义

interrupt 先查 run，再请求取消。任务若在两步之间自然完成，取消返回 false，当前实现直接抛出 `Unable to cancel`。对运行中任务，队列只进入 `CANCEL_REQUESTED`，调用方却立即记录 `user_interrupted` 并清理 checkpoint，实际执行和子资源可能仍在运行。

修复原则：取消命令必须幂等；区分 requested、settling、settled；只有已取消或已终止资源才能发布终态中断语义。底层 `InterruptedException` 必须映射为取消并保留线程中断语义。

### P0：重复进度气泡来自消息身份协议不一致

Kotlin 持久化的 assistant phase message ID 包含文本 hash；Flutter 为同一 runtime event 生成的 alias 不包含该 hash。transcript 和 runtime event 同时可见时，客户端无法判定它们是同一气泡，稳定生成两条相同内容。

修复原则：消息和事件身份由服务端生成并持久化；客户端不得重新推导。一个逻辑消息只能有一个权威 `messageId`。

### P0：同一 turn 的多个 commentary action 仍会共享身份并互相覆盖

即使客户端 alias 完全一致，服务端当前 assistant phase 的 fallback identity 仍只包含 run/task/execution/turn/phase/stage 等字段，不包含 action index 或 assistant item。一个模型 batch 可以连续产生多个相同 stage 的 Commentary，第二条会复用第一条的 `eventId`/projected message key，表现为“下一个气泡覆盖上一个”。这不是 UI 去重策略可以修复的丢失，而是源头把两个事实压成了一个键。

修复原则：在 action 解析/执行边界分配持久 `assistantItemId`；同一 item 的 chunk、journal、full、delta、draft 和 projected message 全部复用该 ID，下一 item 必须分配新 ID。缺少 provider item ID 时也必须使用 action 序号或随机 durable ID，禁止回退到 text、时间或 stage 猜测。

### P0：draft 与 runtime delta 以同一 sequence 双发，先到者会吞掉另一条事实

service-owned gateway 在 draft 更新时先广播 draft，再用同一个 realtime envelope 的 sequence/eventId 生成 delta。Flutter 两个 reducer 只允许一个 sequence/eventId，因此到达顺序决定丢 draft 还是丢 Inspector delta；local long-poll server 的 `offer` 也只保留先到的一条。

修复原则：实时协议只能有一个 canonical envelope。推荐 delta envelope 同时承载 `liveAssistantDrafts` patch；只有不存在 delta listener 的 legacy 消费者才得到不占新序号的镜像 draft。客户端不得根据相同 eventId 猜两条互补 payload。

### P0：完整快照、delta 和 draft 没有统一 revision

四条独立流没有跨通道顺序保证。完整快照不携带 delta watermark，Flutter 收到完整快照后还会清除本地 sequence；旧 delta 随后可被当成首包重新接受。重同步期间客户端只缓存最后一条 delta，中间 thinking、tool call、tool result 会永久丢失。

修复原则：所有 runtime payload 使用同一 `streamInstanceId + sequence`；完整快照携带 `lastSequence`；delta 携带连续序号；旧 epoch、重复包和逆序包必须丢弃，断档时冻结增量、排队并重同步。

### P0：local runtime 模式没有真正的实时流

local HTTP bridge 的 draft 和 runtime delta watcher 返回空流，chat/runtime snapshot 默认每两秒轮询一次。因此该模式最多是低频快照刷新，不可能逐 chunk 更新，也无法为 Inspector 提供实时工具事件。

修复原则：loopback transport 提供可重连的事件流；短轮询只能作为兼容降级，不能冒充 streaming。

### P1：draft 只按 pendingMessageId 合并

retry/resume 会复用 pending assistant message。旧 execution 的晚到 update/clear 可能覆盖或清除新 execution 的 draft；相同毫秒的乱序事件也没有可靠 tie-breaker。

修复原则：draft 必须携带并校验 `streamInstanceId`、`sequence`、`executionId`、`runId`、`taskId` 和 `pendingMessageId`。

### P1：事件身份和 Inspector 排序依赖墙钟时间

同一事件经 live、journal、projection 多路到达时可能拥有不同时间戳；同毫秒内的多个事件又可能碰撞。当前 `totalLength` 没有参与断档判断，现有测试甚至明确允许不一致。

修复原则：事件首次写 journal 时生成稳定 `eventId` 和单调序号；时间戳仅用于展示，不再承担身份或顺序职责。

### P1：bridge 更换和 stream 错误不会自动恢复

Flutter widget 更新时没有处理 bridge 实例变化，旧订阅不会取消，新订阅不会建立。EventChannel、local polling stream 和页面订阅缺少完整的 onError/onDone 重连策略；一次异常可导致后续永久停更。

修复原则：bridge epoch 变化必须取消四条旧订阅，加载一次权威 full snapshot，再开始消费新 epoch 的增量；错误采用有界退避重连。

### P1：resync 期间 draft/delta 分队列会人为制造 gap

当前 Flutter resync 缓存把 delta 和 draft 放在两个队列，恢复时先排空全部 delta，再按到达顺序排空 draft。真实序列 `[draft@6, delta@7]` 会被重排成 `[delta@7, draft@6]`，从而触发二次 gap 或覆盖；队列没有统一上限，持续断线时还会无界增长。

修复原则：所有实时 envelope 进入同一个按 `(sessionId, streamInstanceId, bridgeEpoch, sequence)` 排序的有界队列，应用后再在内存 fan-out。overflow 直接丢弃缓存并触发一次 full resync，不允许静默跳水位。

### P1：runtime 重启后 draft 时钟沿用旧 stream，新的 draft 永远被丢弃

stream/bridge identity 切换时客户端清了 runtime delta watermark 和 seen event IDs，却没有清 draft sequence、epoch、cleared clocks 与 overrides。旧 stream 的 draft seq=100 会让新 stream seq=1 看起来过期，导致重启后消息不再逐 chunk 更新。

修复原则：draft clock key 必须包含 stream/bridge identity，或在 identity 切换的同一 reducer transaction 中清理全部 draft clocks、cleared 状态和旧 override。

### P1：hydrate 的旧 full snapshot 可以回滚已收到的实时状态

`_hydrateFromHost` 并发加载 chat/runtime full snapshot；commit 没有比较 generation/version。若 watcher 在加载期间收到新 delta，旧 full 仍会无条件覆盖消息、run 和 Inspector，之后相同签名事件不会再次发送，界面就永久停在旧状态。

修复原则：hydrate commit 必须携带 bridge generation，并只接受不落后的 stream/sequence；更简单的实现是先完成 full hydrate，再建立 live subscriptions。旧 bridge 的异步结果不得写入新 generation。

### P1：bridge 重绑的异步 cancel 可拆掉新订阅

旧 bridge 的四个 subscription 通过 `unawaited(cancel())` 启动后立即建立新监听。native `onCancel` 若晚于新 `listen` 到达，可能清掉新 observer，正是“切页面/重连后完全不流式”的间歇性症状。

修复原则：绑定使用 generation 状态机，先 await 全部旧 cancel，再创建新 listener；或者给 native channel 分配不可混淆的订阅 token，旧 cancel 只能作用于旧 token。

### P1：provider 非 SSE 响应时静默退化

当前 route 组装已在 `AppAgentSessionTaskRuntimeFactory.effectiveLlmRouteMetadata()` 的各 provider 分支显式写入 `metadata["stream"] = settings.streamingEnabled`，因此“所有 route 漏传 stream”不是现状根因。真正的缺口在 `OpenAiCompatibleLiteLlmProviderClient.readSuccessResponse()`：请求要求 streaming 但 provider 返回普通 body 时会成功走 `plain_body`，只写 debug log，结果 metadata 没有记录请求是否流式、实际 transport mode 或降级原因。Inspector 因而无法区分 provider 不支持、代理剥离 SSE、响应头错误和客户端解析失败。

修复原则：保留显式 route streaming 偏好；每次响应写入 `streamRequested`、`streamTransportMode` 和结构化 `streamDowngradeReason=provider_returned_non_event_stream`（或具体原因），并让 Inspector/诊断流可见，而不是静默切换。

### P1：retry 可能保留旧恢复错误

队列 retry 状态迁移没有显式清除旧 `lastErrorCode/lastErrorMessage`。恢复错误可能污染后续执行，并错误绕过普通最大重试次数判断。

修复原则：开始新 execution 时清除上一次终态错误，恢复相关 metadata 只能在需要时重新生成。

### Critical：runtime owner 心跳异常后可能永久停摆

`RuntimeServiceProjectionCoordinator` 在持久化投影和 owner lease 失败时，可能跳过下一次心跳排程。租约在一个周期后失效，另一进程可以取得 owner，原进程仍保留执行句柄，最终表现为控制命令间歇性失效或双主。

修复原则：心跳排程必须放在 `finally`；持久化失败要单独暴露诊断，不得改变下一次尝试的计划。

### Critical：所有权持久化失败时静默 fail-open

投影库或 owner lease 库构造失败会降级到进程内存实现。跨进程随后各自认为自己是 owner，且恢复数据随进程消失。这是跨进程 interrupt/restart 失灵的直接放大器。

修复原则：owner lease 存储必须 fail-closed；只有非关键展示投影可以降级，且降级状态必须显式可见。

### Critical：损坏的 owner lease 会被当成“无人持有”

`FileBackedRuntimeServiceOwnerLeaseStore.decodeLeaseOrNull()` 和 session lease 的对应实现会把反序列化失败转换为 null。下一次 `save/acquire` 随即把损坏文件视为空租约并覆盖，绕过原 owner 排他约束。只要发生半写入、迁移不兼容或字段损坏，就可能产生第二 owner。

证据路径：`RuntimeServiceOwnerLease.kt`、`RuntimeSessionOwnerLease.kt`。

修复原则：租约解码失败必须 fail-closed 并隔离坏文件；在人工修复或有证据的过期恢复完成前，不得自动取得 owner。

### Critical：runtime listener 异常可反向终止执行和流式事件

`AgentSessionRuntimeManager` 对 `onTaskStarted`、`onRunEvent`、draft update/clear 和 `onTaskFinished` 逐个同步调用 listener，没有逐 listener 故障隔离。`DefaultRuntimeServiceProjectionCoordinator` 正是其中一个 listener，其回调会同步续租、汇总 active work 并写磁盘。任一 I/O 或投影异常都可能从观测链反向传播到 runtime，导致任务未执行、流式回调停止、工具事件中断或终态未发布。

证据路径：`AgentSessionRuntimeManager.kt` 的 `runtimeEventSink` 与 task wrapper；`RuntimeServiceProjectionCoordinator.kt` 的 `runtimeOwnerProjectionListener`。

修复原则：执行事实先写入权威 journal/queue，再通过隔离 dispatcher 通知观测者；单个 listener 失败只进入 diagnostics，不得阻断其他 listener 或核心执行。关键 journal 失败应进入明确的 durability-degraded 状态，而不是伪装成模型/工具失败。

### Critical Security：固定端口 loopback 控制面完全无认证

`OpenCrayLocalRuntimeServer` 固定监听 `127.0.0.1:42617/42618`，解析请求时丢弃所有 header，也没有 capability token、调用方身份或重放保护。Android loopback 不是应用私有命名空间，同设备其他应用可直接访问这些端口。当前路由不仅可读取 chat、memory、settings 和 workspace 内容，还可删除文件、修改安全/模型设置、安装技能、提交 agent 任务以及 interrupt/retry。

证据路径：`OpenCrayLocalRuntimeServer.kt`、`RuntimeServiceCommandFallbackTransport.kt`。

修复原则：loopback transport 必须使用应用私有持久化交换的高熵 capability，按 runtime instance 轮换并对每个请求做常量时间校验；写命令还需 nonce/sequence 防重放。认证完成前不能上线长期 SSE 连接，否则会扩大未授权读取面。

固定端口还带来端点劫持：其他进程可以先绑定 `42617/42618`，让 fallback 客户端把 API key、审批和写命令发给伪造 server；真实 runtime 随后因 `BindException` attach 失败，用户看到的只是“服务不可用”。因此随机端口本身也不是认证，端点和 capability 必须通过受保护的 Binder/应用私有存储交换，并绑定 runtime instance/epoch；客户端不得在端口连接成功后就把它当作可信 runtime。

### Critical Security：未认证配置接口直接返回全部 provider 明文密钥

loopback 的配置读取路由会把内部 settings snapshot 原样序列化。`LlmConfigSnapshot.toMap()` 返回主 LLM `apiKey` 和每个 provider option 的 `apiKey`；network search、image、video、voice 与 external STT 的 snapshot mapper 也返回各自明文 `apiKey`。结合固定端口无认证问题，同设备任意应用只需访问配置 GET 路由，就能提取用户配置的全部上游凭据，而不必先获得文件系统权限。

证据路径：`OpenCrayHostRuntime.kt` 的 `NetworkSearchSlotSnapshot.toMap()`、`MediaProviderSnapshot.toMap()`、`VoiceProviderSnapshot.toMap()`、`LlmConfigSnapshot.toMap()` 与 `LlmProviderOptionSnapshot.toMap()`；`OpenCrayLocalRuntimeServer.kt` 的 settings/config GET 路由。

修复原则：读模型必须与写模型分离；所有返回 UI/bridge 的 snapshot 只暴露 `hasCredential`、掩码尾部或 credential handle，绝不回传 secret。保存接口以“缺省表示保留、显式 clear 表示删除”的 patch 语义更新安全存储。loopback capability 是必要的纵深防御，但不能替代输出脱敏；即使认证通过，也不应把可复用密钥发回 transport。

### Critical Security：媒体任务轮询/取消绕过 policy，且可伪造 jobId 注入请求 URL

`AgentToolDispatcher.dispatch()` 对 `PollMediaJob` 和 `CancelMediaJob` 调用不传 `AgentTask`，两个 handler 也没有构造 `ToolPolicyPlan` 或调用 shared pipeline gate；它们只在结果阶段补 common/result metadata。provider job id 则是无签名的 URL-safe Base64 JSON，解码后信任其中的 `toolName`、`providerJobId`、`providerPollUrl` 和 `providerCancelUrl`。攻击者或受提示注入影响的模型可以自行构造 job id，令 media provider client 携带已配置 provider 凭据向注入的 poll/cancel URL 发起 GET/POST；这同时绕过工具审批边界，并形成 SSRF 与 credential forwarding 风险。

证据路径：`AgentTooling.kt` 的 dispatch 分支、`pollMediaJob()`、`cancelMediaJob()`、`encodeProviderMediaJobId()` 与 `decodeProviderMediaJobId()`；`OpenCrayConfigurableMediaProviderClient.kt` 的 `poll()`、`cancel()`、poll/cancel endpoint 解析及 `executeRequest()`。

修复原则：provider job 状态必须由 runtime 持久化并只向模型暴露不可伪造的随机 opaque handle；poll/cancel 先按 task/session/execution 解析 handle，再通过 `ToolPolicyPipeline` 使用明确的 network/process-lifecycle intent 做 gate。所有 provider 返回或恢复的 URL 均要解析、规范化，并约束到初始获批 provider origin（或显式 allowlist），重定向后再次校验 origin，认证 header 只允许发送给原获批 origin。若必须支持跨域 job URL，需单独审批且不得自动转发原 provider credential。

### High：owner 心跳在主线程同步做磁盘与工作汇总

主 Looper 同步执行文件锁、投影写入和 `activeWorkSummary()`，平台线程卡顿即可令租约误过期，并阻塞 Binder/notification 生命周期。

修复原则：心跳使用独立单线程调度器、有限超时和失败观测。

### High：loopback 读与 bridge 请求可能阻塞界面或回调旧 engine

fallback HTTP 读的超时可达 60 秒，host bridge 又为每次调用裸建线程；detach 只清订阅，不取消在途请求。服务断链、engine 重建或快速切换期间，旧结果可能回写新界面，interrupt/restart 也会被表象上的阻塞掩盖。

修复原则：读写请求使用有界 executor 和 request generation；detach/dispose 取消在途工作并丢弃旧 generation 的回调。

### High：loopback HTTP 解析和并发完全无界

服务端使用 `Executors.newCachedThreadPool()` 为每个连接分配工作，`Content-Length` 可直接控制 `ByteArray(length)`，请求行和 header 行也没有长度上限。恶意或异常客户端可通过并发慢连接、超大长度或无限长 header 造成线程、堆内存和文件描述符耗尽；这会表现为 UI 卡住、interrupt 超时和 runtime service 被系统杀死。

修复原则：使用有界线程池/连接数、最大 request line/header/body、读取总时限和明确的 413/429；SSE 连接单独限额并支持背压、心跳和主动回收。

### High：transport 的 start/dispose 竞态可在销毁后复活 server

`DefaultOpenCrayRuntimeServiceTransportBootstrapFactory.ensureStarted()` 在外层标记 `starting` 后才进入 loopback 创建；若 dispose 在创建前完成，它会关闭“当前为空”的 server，但在途 ensure 随后仍可创建并监听端口。外层检测到 disposed 后只返回 false，没有关闭刚创建的 server，留下引用不可达但仍运行的控制面和 executor。

修复原则：server 创建与 disposed generation 必须在同一生命周期状态机内提交；创建完成发现 generation 失效时立即 close，且测试 start/dispose 交错时端口不再监听。

### High：ProjectionCoordinator 的 replace/dispose 不是异常安全的

`replaceRuntimeOwner()` 先安装新 observer，再同步 release 旧 lease；release 抛错会跳过旧 observer disposer、notification host 替换和新投影持久化，造成双 observer。`dispose()` 中 lease release 或 released projection 写入抛错会跳过 notification/observer 清理，销毁后的回调仍可能继续触发。

修复原则：资源解绑放入 finally，并将 lease release、projection save、notification dispose 分成互不阻断的清理阶段；每阶段失败进入结构化 diagnostics。

### High Privacy：生产日志无条件记录模型输出片段

Kotlin 的 `runtimeFlowDebug`、`serviceChatDebug` 和 provider stream debug 未受 `BuildConfig.DEBUG` 或用户诊断开关保护，会把 assistant draft 前 80 个字符以及 session/task 标识写入 logcat。Flutter 侧已有 `kDebugMode` 保护，但 Kotlin 侧没有同等边界。

修复原则：生产默认关闭内容日志；诊断仅记录长度、eventId、sequence 和脱敏错误码。临时内容采样必须由显式用户授权、限时开启并在导出前再次脱敏。

### High：默认心跳 scheduler 初始化失败会静默永久禁用

默认 scheduler 构造失败时返回一个永不执行 action 的 no-op task。协调器仍认为 heartbeat 已排程，系统不会重试，也没有 diagnostics；owner 会在 30 秒后过期。

修复原则：关键 scheduler 初始化必须 fail-fast 或切换到真实的后台 scheduler；不得用“成功返回但永不执行”的对象模拟可用性。

### High：删除/撤回只 request cancel，旧执行可在删除后继续回写

`ChatSessionMutationCoordinator.discardSession()` 对非终态 run 只调用 `requestCancel()`，随后立即终止已登记进程、清 journal/checkpoint/supplement、释放 runtime session 并删除 transcript。`deleteChatMessage()` 和 `recallChatMessage()` 同样只按 pendingMessageId 发取消请求便马上修改消息。执行线程可能尚未 settled，`AppAgentSessionTaskRuntimeFactory.releaseSession()` 也只是移除缓存，并不 join 或停止正在运行的 task。晚到的 event、draft clear、tool result 或 final assistant message 因而可以在删除之后重新创建数据或污染新状态。

证据路径：`ServiceOwnedChatSessionMutationAccess.kt`、`AgentSessionRuntimeManager.kt`、`AppAgentSessionTaskRuntimeFactory.kt`。

修复原则：删除/撤回采用 tombstone + execution generation：先阻止该 generation 的任何新写入，再请求取消并等待 settled，最后清理 transcript/journal/checkpoint/media；UI 可以立即隐藏，但 durable 删除完成前必须保留可恢复清理记录。

### High：visibility heartbeat 与多处 listener 仍有同类“异常即永久停止”模式

`AppVisibilityMonitor` 在 `publisher.publish(true)` 后才安排下一次 heartbeat，发布异常会永久停止。`RuntimeServiceWorkStateTracker`、`RuntimeServiceKeepAliveController`、host/service gateway 的 listener 广播也普遍直接 `forEach`，单个 listener 可阻断后续 listener。说明该问题不是 owner heartbeat 单点缺陷，而是事件分发基础设施缺少统一故障隔离。

修复原则：定时任务统一在 finally 重排；广播统一使用逐 listener 隔离、结构化错误汇总和限流 diagnostics，禁止在持锁区调用外部 listener。

### High：polling 失败被永久吞掉，界面无限保留陈旧成功态

Kotlin projection polling 使用 `runCatching(...).getOrNull() ?: return`，Dart local bridge 的 async generator 则在一次 loader 异常后直接终止 stream。前者永不告诉 UI 数据已经失联，后者没有重连；两者都无法区分“没有变化”和“读取失败”。

证据路径：`ProjectionOnlyOpenCrayGateways.kt`、`ProjectionOnlyOpenCrayChatRuntimeGateway.kt`、`opencray_local_runtime_bridge.dart`。

修复原则：transport 状态必须显式包含 connected/degraded/disconnected、lastSuccessAt 和 lastErrorCode；轮询或事件流使用有界退避恢复，陈旧数据必须带 stale 标记，不能继续伪装成实时成功态。

### High：draft 与 runtime delta 双消费者竞争同一 long-poll 游标

local bridge 曾为 `watchLiveAssistantDraftEvents()` 和 `watchRuntimeEventDeltas()` 分别启动 `_watchRuntimeRealtimePayloads()`。两条 watcher 消费同一个 `/v1/chat_runtime_events` 序列，却各自维护 `afterSequence`；任一 watcher 收到另一 kind 后先推进水位再过滤，另一 watcher 稍晚请求时便无法补回该事件。结果取决于请求交错：可能只流 draft、只更新 Inspector，或两边随机缺包。

修复原则：一个 bridge instance 只能有一个底层 realtime cursor；统一 feed 完整接收并按 sequence 校验，再在进程内 broadcast/fan-out 给 draft、runtime delta 和后续消费者。消费者过滤不得推进独立 transport 水位，订阅增减也不得重置共享 cursor。

### High：local bridge 会话切换沿用旧会话高水位

事件序号按 `sessionId` 分区，但 local bridge 的 long-poll cursor 曾只在 `streamInstanceId` 变化时归零。会话 A 已消费到 8 后切到同一 runtime 中只到 2 的会话 B，bridge 仍请求 `afterSequence=8`；B 的序号在达到 9 前全部被服务端过滤，表现为切会话后流式和 Inspector 永久停更。

修复原则：收到不同 `sessionId` 的 full/heartbeat 响应时，先清除该 transport cursor，再以新会话 snapshot 的 `lastSequence` 建立水位；session、stream instance、bridge epoch 三者任一变化都必须触发对应分区的 reducer 重置。已加入会话 A@8 → B@2 → B delta@3 的回归测试。

### High Security：审批通知没有绑定 execution，旧操作可作用于新重试

审批 PendingIntent 携带 sessionId、taskId、runId，但 requestCode 只使用 `action + sessionId + taskId` 的 30,000 桶稳定哈希，未包含 runId/executionId。通知 dispatcher 随后忽略 sessionId，只把 `runId ?: taskId` 交给全局审批查找。retry 复用 run/task 标识时，旧通知点击可能批准或拒绝新的 execution；哈希碰撞配合 `FLAG_UPDATE_CURRENT` 还可能覆盖另一 PendingIntent 的 extras。审批不存在时 `approve/reject` 返回 Unit，dispatcher 仍无条件视为成功。

证据路径：`RuntimeNotificationCoordinator.kt`、`OpenCrayRuntimeServiceAccess.kt`、`RuntimeServiceWakeCommandDispatcherFactory.kt`、`RuntimeServiceApprovalDecisionAccess.kt`。

修复原则：通知动作绑定 `sessionId + runId + taskId + executionId + approvalEventId`，服务端原子 consume 一次；任何字段不匹配或审批已终结都返回 stale/no-op 结果。PendingIntent identity 使用包含完整不可猜 token 的 data URI 或持久化唯一 ID，不再依赖小模哈希与 extras 更新。

### High：损坏持久化被当成空文件覆盖

通用 JSON store 吞掉解析/迁移异常，下一次 update 可能从空记录重建并覆盖原工作区、session 或 memory。该行为会破坏恢复链，不能作为正常降级。

修复原则：坏文件隔离并保留原件/备份，读取失败进入显式错误状态；只有用户确认或迁移工具才能重建。

### Critical Release Security：调试版 Python AAR 污染 release 最终清单

release 合并产物曾包含 `android:debuggable="true"`，来源不是 app build type，而是 `opencray-python-runtime-debug-0.1.0.aar`。同一个 AAR 还注入了无 permission 的 `org.opencray.app.ServiceOpencraypython exported=true`；该 service 接收 Python runtime 启动参数，在 OpenCray UID 下执行，因此外部显式启动不是普通组件暴露，而是进程执行边界暴露。

证据路径：`app/build/outputs/logs/manifest-merger-release-report.txt`、`tools/android_python_runtime_p4a/dist/*.aar`、`P4aPythonRuntimeLauncher.kt`。

本轮处置：主清单显式覆盖 `debuggable=false` 和 Python service `exported=false`，release Gradle 门禁解析最终 merged manifest 并在任一属性回退时失败。剩余供应链工作是让 p4a 产物不再以 `*-debug.aar` 作为 release 唯一输入，并在 AAR 生成端删除这两个危险默认值。

### Critical Privacy：明文凭据和运行数据默认进入 Auto Backup

LLM、Web Search、Media/Speech、per-agent LLM 与 E2B token 分散明文写入 `filesDir`；chat、workspace、queue、journal、checkpoint、lease 和 scheduled specs 也位于默认备份域。原清单只有 `allowBackup=true`，没有 Android 12 前后的排除规则。恢复到新设备后不仅会泄露凭据，`OpenCrayApplication` 的 APP_START repair 还可能重新排程旧自动化。已有 Keystore vault 的密文同样不能备份，因为 Android Keystore key 不迁移，恢复后会形成“引用存在但永远解不开”的悬空配置。

证据路径：`LlmSettingsStore.kt`、`WebSearchSettingsStore.kt`、`MediaSpeechSettingsStore.kt`、`AgentConfigStore.kt`、`SandboxSettingsStore.kt`、`AndroidKeystoreSharedPreferencesSecretVault.kt`、`OpenCrayApplication.kt`。

本轮处置：`allowBackup=false`，并同时配置 `fullBackupContent` 与 `dataExtractionRules`，对 cloud backup 和 device transfer 的全部私有域做显式排除；release merged-manifest 门禁强制保留这些属性。后续仍需把全部 provider key 迁入 Keystore vault，以原子迁移清理 legacy SharedPreferences，并在检测到无法解密的 credential ref 时呈现明确的重新录入状态。

### High：scheduled automation 缺少原子 claim 和 durable outbox

同一 schedule run 可被 Alarm 与 WorkManager 双路并发触发。当前“读取 run”与写入 `TRIGGERED` 不是 CAS，且已是 `TRIGGERED` 的记录仍允许继续，两个 worker 都可能调用 `session.submitTask()`。即使没有并发，`TRIGGERED` 落盘后会先随机生成 task/run 并提交，最后才写 `ACCEPTED`；提交后、确认前崩溃，repair 会再次提交同一外部副作用。

证据路径：`ScheduledTaskRuntime.kt`、`ScheduledTaskWorkManager.kt`、`ScheduledTaskAlarmScheduler.kt`。

修复原则：以 `scheduleRunId` 做 durable CAS claim 和 outbox 主键，task/run/message ID 必须确定性派生；恢复时先按该 ID 对账 queue、run record 与 transcript，只有确认从未提交才允许重放。测试必须并发释放 Alarm/Work 两个执行者，并在 submit 后、ACCEPTED 前注入崩溃，断言只产生一个 task 和一次副作用。

### High Data Integrity：删除会话后旧写入可污染当前会话并被恢复复活

目标 session 不存在时，`workspaceAndSessionForAppend()` 会回退到 active session。删除 A 与迟到 submit/final 回写竞态时，A 的消息可能直接写入当前 B，而不只是“重新创建 A”。同时 `deleteChatSession()` 没有统一清除 queue、run、process、checkpoint 等持久目录；恢复候选又无条件合并这些目录，因此已删除会话可在重启后复活。关联 scheduled specs 也未级联处理，会继续唤醒并制造 `FAILED_MISSING_SESSION`。

证据路径：`ChatSessionLocalStore.kt`、`ChatSessionMutationCoordinator.kt`、`AgentSessionRuntimeManager.kt`、各 runtime store factory 的 `knownSessionIds()`。

修复原则：显式 session 写入在目标缺失时 fail closed，禁止回退 active session；删除使用持久 tombstone 与 generation，先封锁该 generation 的新写入，再等待执行 settled，最后用统一 teardown 清理所有 store 与关联 schedule。恢复只接受 chat 仍存在且 generation 匹配的候选。

### High Security：workspace 符号链接可借分享/打开流程导出 app 私有文件

workspace opener/sharer 只做词法 `startsWith` 检查，随后 `Files.copy` 或 `inputStream` 默认跟随符号链接。workspace 内若存在指向 `agent-runtime/llm-settings.json` 等私有文件的链接，打开或分享会把目标复制到 cache，再由 FileProvider 向外部应用授予读取 URI。FileProvider 本身虽为 non-exported，但当前 paths 又覆盖整个 cache root，放大了 staging 失误的影响。

证据路径：`AppAgentWorkspaceOpener.kt`、`AppAgentWorkspaceSharer.kt`、`opencray_file_provider_paths.xml`。

修复原则：real root 与每个选择/遍历节点都以 `toRealPath()` 校验 containment，直接拒绝 symlink，并在 copy/open 时防 TOCTOU；FileProvider paths 收窄到专用 `workspace-open/` 和 `workspace-share/`。回归覆盖文件链接、目录内链接和校验后换链。

### High Reliability：实际 Flutter APK 曾缺少 loopback cleartext 例外

交付脚本通过 `flutter build apk` 构建，Flutter Android app 只把 root `app/src/main` 接入 source set，没有接入 `app/src/debug`。因此 root debug 清单中的 network security config 并未进入实际 Flutter APK；targetSdk 33 默认拒绝 `http://127.0.0.1`，Binder 一旦降级，snapshot、long-poll、interrupt/retry fallback 都会停止。这是“有时完全不流式”和 Inspector 停更的独立交付层原因。

本轮处置：loopback-only network security policy 已移入 main 资源，基础 cleartext 仍为 false，只允许 `127.0.0.1` 与 `localhost`；它必须与本轮 epoch/HMAC/nonce/响应验签协议一起上线，不能恢复成无认证固定端口。

### High Release Security：release 默认使用 debug signing key

`flutter_app/android/app/build.gradle.kts` 的 release build type 显式引用 debug signing config，而 `build-apk.ps1` 默认构建 release。产物名称看似 release，实际身份仍是公开调试密钥，无法建立可信升级链，也可能被其他持有同一 debug key 的 APK 替换。

修复原则：正式 release signing 从 CI/本机受保护配置注入，缺失时 fail closed；开发安装使用明确的 debug variant。交付门禁对最终 APK 运行 signer 校验并拒绝 Android debug certificate，不能只检查 Gradle DSL。

### High Reliability：通知 ID 的小哈希桶会覆盖无关运行

approval、terminal、schedule 和 service recovery 通知分别被压进 1,000 到 5,000 个 ID 桶。两个活动任务碰撞时，后一次 `notify(id, ...)` 会覆盖前一条，任一任务终结后的 `cancel(id)` 又会删除仍有效的另一条。这与 PendingIntent 的 execution 绑定缺陷独立存在。

证据路径：`RuntimeNotificationCoordinator.kt` 的 notification ID 生成函数。

修复原则：使用 `NotificationManager.notify(stableTag, typeId, ...)` 与同 tag cancel，stableTag 包含完整 schedule/run/task/execution 身份；回归测试构造确定的 Java hash/modulo 碰撞，验证两条通知并存且取消互不影响。

### Medium Hardening：repair receiver 和内部 Activity 的导出面过宽

`ScheduledTaskRepairReceiver` 曾为 exported，但只接受 `BOOT_COMPLETED` 与 `MY_PACKAGE_REPLACED`，两者是系统 protected broadcast，因此不能把它表述为普通第三方应用可直接伪造的 Critical。将其改为 non-exported 仍是正确的最小权限硬化，且不影响系统广播。另有五个无 intent-filter 的 legacy wrapper Activity 为兼容性保持 exported；主 launcher 还会信任 route/session/task/run extras，需要单独迁移到丢弃外部 extras 的 launcher trampoline，内部 Flutter Activity 与 wrappers 再改为 non-exported。

证据路径：`AndroidManifest.xml`、`ScheduledTaskWorkManager.kt`、`ShellWrapperRoutingTest.kt`、`OpenCrayFlutterActivity.kt`。

## 未由用户点名但已主动审查的领域

| 领域 | 当前结论 | 状态 |
| --- | --- | --- |
| 跨进程 owner/session lease | 已确认 fail-open、心跳与墙钟风险 | 深入审查中 |
| 持久化损坏与 crash durability | 已确认坏 JSON 覆盖、缺少明确 fsync | 深入审查中 |
| loopback 安全边界 | 已确认无认证固定端口、端点劫持和无界资源 | 本轮按已批准方案 A 实施 epoch/HMAC 协议 |
| credential 输出与存储边界 | 已确认多个配置读取模型返回明文 API key | Critical，待脱敏 DTO 与安全存储专项 |
| media job / tool policy | 已确认无签名 jobId、policy bypass、SSRF 与凭据转发风险 | Critical，待 opaque handle 与 origin policy 专项 |
| 资源与背压 | 已确认无界线程、请求体、header 和连接 | 已形成修复原则 |
| observer 故障隔离 | 已确认投影异常可打断 runtime | 纳入本轮可靠性修复 |
| transport 启停生命周期 | 已确认 dispose 后复活竞态 | 待失败测试 |
| 诊断与隐私 | 已确认 release 日志包含 draft 内容 | 待收口 |
| Android 发布与组件暴露 | 已修 release debuggable、Python service 与 repair receiver；wrapper/launcher 待迁移 | 持续门禁 |
| executor/订阅回收 | 已确认部分旧 generation 和清理异常路径 | 深入审查中 |
| 会话/消息删除一致性 | 已确认未 settled 即删除导致晚到回写 | 纳入后续修复 |
| 审批/通知幂等与跨代隔离 | 已确认旧 PendingIntent 可命中新 execution | 安全敏感，待专项 |
| tool policy/approval 边界 | 已发现 media poll/cancel 绕过；其余 handler 继续全量对照 | 专项进行中 |
| Auto Backup / D2D restore | 已确认凭据、运行数据与旧 schedule 恢复风险 | 本轮已 fail-closed，vault 迁移待专项 |
| scheduled exactly-once | 已确认并发双提交与 crash 重放窗口 | 待 durable outbox/CAS 专项 |
| 文件打开与分享 | 已确认 symlink staging 可导出 app 私有文件 | High，待 real-path/TOCTOU 专项 |
| release 签名 | 已确认实际 release 使用 debug certificate | High，待签名门禁 |

## 统一协议方向

运行时实时 envelope：

- `streamInstanceId`：服务进程或 bridge epoch，重启后变化。
- `sessionId`：会话路由边界。
- `sequence`：该 session 在当前 epoch 内的单调序号。
- `lastSequence`：full snapshot 已覆盖的最大序号。
- `eventId`：事件首次产生时生成并写入 journal，full/delta/replay 复用。
- `executionId`：隔离 initial、retry、approval resume、checkpoint resume。

客户端 reducer 规则：

1. 只接受当前 `streamInstanceId`。
2. full snapshot 替换权威集合并设置 `lastSequence`。
3. delta 只按连续 sequence 应用；重复和旧包直接丢弃。
4. gap 期间缓存全部包并触发单次 resync；full 落地后按 sequence 回放。
5. event 按 `eventId` upsert，message 按服务端 `messageId` upsert。
6. draft 只能修改同 execution 的 pending message。
7. chat content 和 runtime activity 分开归约，UI 只消费归约后的单一状态。

## 回归测试矩阵

### 运行控制

- managed process 恢复中断后，队列真实状态为 FAILED，restart 可成功入队。
- retry 开始后旧恢复错误被清除，普通失败重新遵守 maxAttempts。
- lookup 与 cancel 之间任务完成时，interrupt 幂等返回，不抛泛化错误。
- cancellation requested 期间不发布 settled 中断；资源终止后只发布一次。
- runtime 抛 `InterruptedException` 时结果为 CANCELLED，线程中断标记和 cleanup 可观察。

### 实时协议

- full 与 delta 中同一 `eventId` 只渲染一次。
- 真实 Kotlin assistant message ID 与 runtime event 同时到达只显示一个气泡。
- snapshot 插入 delta 1/2 之间后，重复 delta 1 被拒绝。
- resync 期间连续到达多条 delta，全部保留或由 full 覆盖。
- 旧 `streamInstanceId` 的 delta/draft 在服务重启后被丢弃。
- `totalLength`、sequence gap、journal truncation 任一不一致触发 resync。
- retry 复用 pendingMessageId 时，旧 execution draft 不改写新 execution。
- update/clear 同毫秒乱序不复活旧 draft。

### transport 与 UI

- EventChannel 重新 listen 后先加载 full，再消费新 delta。
- widget bridge 更换时旧订阅全部取消，新订阅建立。
- local transport 能逐 chunk 更新 draft 和 Inspector，断线后自动重连。
- local transport 的 draft 与 runtime delta 共用唯一底层 cursor；交错到达时两类事件都只消费一次且均不丢失。
- provider 非流式降级在 diagnostics 中可见。
- Inspector 打开期间 thinking、tool call、tool result 按 event sequence 实时追加。
- 任一 runtime listener 抛错时，其他 listener 和 task 执行不受影响，并生成 diagnostics。
- projection save 单次失败后，下一次 owner heartbeat 仍会排程并成功执行。
- transport start 与 dispose 交错后没有残留监听端口或 executor。
- loopback 拒绝无 capability、错误 capability、重放和超限请求。
- loopback descriptor 使用临时端口并按 runtime epoch 轮换；伪造响应、旧 epoch 响应和篡改 body 均被客户端拒绝。
- 所有配置读取响应不含主/provider/media/search/voice/STT 明文 credential，保存掩码值不会覆盖原 secret。
- 伪造或篡改 provider media job handle 被拒绝且不产生网络请求。
- media poll/cancel 必须经过 policy gate；poll/cancel URL 跨获批 origin、重定向跨 origin或尝试向新 origin 转发认证 header 时被拒绝。
- 生产构建日志不包含 prompt、draft、tool payload 或 credential 内容。
- 活跃 run 所在会话被删除后，旧 generation 不得重新创建 transcript/journal/draft。
- 消息删除或撤回后，晚到 final/draft clear/tool event 不得复活被删消息。
- polling 失败会呈现 stale/disconnected，并在恢复后自动重新同步。
- 旧 execution 的审批 PendingIntent 对新 retry 返回 stale，且不会改变审批状态。
- 不同 session/run/task 的 PendingIntent 即使哈希碰撞也不会覆盖或串用 extras。
- Alarm 与 WorkManager 同时触发同一 `scheduleRunId` 只提交一次；submit 后崩溃恢复也不重复。
- 删除 A 后任何 A generation 的晚到写入均 fail closed，且不得写入当前 B 或被恢复扫描复活。
- workspace 文件/目录 symlink 与换链 TOCTOU 均不得生成分享 cache 或授权 URI。
- Auto Backup 与 device transfer 均不包含 credential、chat、workspace、journal、lease 或 scheduled spec。
- 最终 release APK 不可调试、Python service 不导出，并且 signer 不是 Android debug certificate。

## 已审查但暂未判定为本轮修改的问题

- runtime service owner lease、detached runtime 与 WorkManager 的恢复排期。
- managed process reconnect 的 durable controller 身份和跨 owner 状态。
- session/message 删除时的取消级联和媒体 GC。
- approval、checkpoint、subagent continuation 的跨进程恢复。
- runtime projection store 损坏、journal 损坏和回放降级。
- 通知动作触发 retry/interrupt 时与前台命令的幂等关系。
- JSON 文件与父目录缺少明确 fsync durability 保证。
- owner lease 使用可跳变 wall clock，系统改时可能误判存活。
- 动态文件锁 registry 按 session/run 增长且没有回收策略。
- polling gateway 将所有异常吞掉并永久保留陈旧快照。
- credential vault 的全量迁移、legacy key 清除与 restore 后悬空引用处理。
- loopback epoch descriptor 的崩溃残留、nonce cache 上限与长轮询重连。
- listener diagnostics 的限流，避免故障风暴反过来压垮持久化。

这些领域会继续按证据分级；本轮只修改能够直接追溯到可靠性目标且有回归测试支撑的代码，避免无边界重构。
