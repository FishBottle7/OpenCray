# OpenCray 沙盒能力接入计划

Last updated: 2026-04-04

## 当前实现状态

截至 2026-03-30，`python_exec` 这条链路已经从“仅有设置和路由骨架”推进到了“本地 / E2B 双后端可切换的可运行状态”，且当前实现边界已经固定：

- 已补齐沙盒配置模型与安全凭据链路
  - 新增独立 `SandboxSettingsStore`
  - 保存 `enabled`、`default backend`、`session mode`、超时策略、`templateId`、`E2B API key CredentialRef`
  - E2B API key 通过 Android Keystore 支撑的安全存储读取，不走普通设置项明文持久化
- 已补齐 UI 与入口
  - `Settings > API Integrations > Sandbox Providers > E2B`
  - Chat 界面右上角新增运行环境切换入口
  - 下拉菜单为英文，并带本地 / 云端图标示意
- 已补齐 `python_exec` 的 runtime 路由层
  - 新增 `RoutingPythonScriptRuntime`
  - 当前可以根据 `local / auto / sandbox` 做选择
  - `auto` 在远端 backend 不可用时会安全回落到本地
  - `sandbox` 显式选择时，如果远端 backend 不可用，会返回明确错误，不会偷偷走本地
- 已落地真实的 `E2BCodeInterpreterPythonRuntime`
  - 通过 E2B 官方 `code-interpreter` HTTP 端点实现
  - 调用链路包括：
    - 创建 / 连接 sandbox
    - 上传本地 workspace 文件到远端执行镜像
    - 创建临时代码 context
    - 执行脚本
    - 收集 stdout / stderr / execution error
    - 根据远端执行后生成的 workspace diff manifest，把变更文件下载回本地 workspace
    - 处理超时 / 取消 / 会话清理
- 已补齐 Phase 3 的第一批深化能力
  - sticky session 现在会使用稳定的远端 workspace 根目录，而不是每次执行都换一个目录
  - 已新增 workspace sync 状态落盘
    - 本地状态文件位置：`.opencray/sandbox-sync/e2b-workspace-sync-state.json`
    - 当前会记录 `sandboxId`、`remoteWorkspaceRoot` 和上次成功同步的文件元数据
  - 已接入 sticky session 下的执行前增量上传
    - 第二次及后续执行会基于 `relativePath + size + modifiedAt` 只上传变化文件
    - 当前 metadata 会回传 `workspaceUploadMode`、`workspaceUnchangedFiles`、`workspacePendingRemoteDeleteFiles`
    - 当前远端删除仍不会自动回放，只会把“待删除数量”暴露给 metadata
  - 已补齐下载结果的受控归档目录
    - 当前所有成功回传的远端 changed files，都会额外归档到 `.opencray/sandbox-downloads/<requestId>/...`
    - 现有 attachment artifact metadata 现在会优先指向归档后的相对路径，而不是原始工作区覆盖路径
    - 当前 metadata 会回传 `archivedArtifactFiles`、`archivedArtifactBytes`、`sandboxDownloadArchiveRoot`
  - 已补齐归档保留 / 清理策略第一版
    - 当前默认只保留最近 `12` 个 request 归档目录
    - 当前默认归档总大小上限为 `64 MiB`
    - 超限时会优先清理更旧的 request 目录，不会优先删掉本次请求刚产生的归档
    - 当前 metadata 会额外回传：
      - `sandboxDownloadArchivePrunedDirectories`
      - `sandboxDownloadArchivePrunedBytes`
      - `sandboxDownloadArchiveRetainedDirectories`
      - `sandboxDownloadArchiveRetainedBytes`
  - 已补齐 preview / session lifecycle 的第一批持久化能力
    - `sandbox_preview_open` 现在会把最近一次 preview 打开记录和 probe 结果写回 session snapshot
    - `sandbox_session_info` 现在会额外回传：
      - `remoteWorkspaceRoot`
      - `lastPreviewUrl`
      - `lastPreviewPort`
      - `lastPreviewPath`
      - `lastPreviewProbeStatus`
      - `lastPreviewProbeHttpStatusCode`
      - `lastPreviewProbeMessage`
      - `lastPreviewOpenedAtEpochMs`
  - 已补齐 preview / session lifecycle 的第二批自动管理能力
    - `sandbox_preview_open` 现在会额外持久化：
      - `lastPreviewProbeObservedAtEpochMs`
      - `lastPreviewProbeSource`
    - `sandbox_session_info` 现在会计算并回传：
      - `sessionLifecycleStatus`
      - `sessionLastActivityAtEpochMs`
      - `sessionStaleAfterEpochMs`
      - `sessionIsStale`
      - `recommendedRefreshAfterMs`
      - `previewAutoProbeAttempted`
    - 当最近一次 preview 仍处于活跃窗口内、且上次 probe 已过期时，`sandbox_session_info` 会自动重探 preview URL，并把新 probe 结果写回活动 session / 持久化 snapshot
    - 当 sticky session 使用 `timeoutAction=kill` 且生命周期已过期、同时没有运行中的 request 时，`sandbox_session_info` 会自动回收 stale session
    - Chat 主流和 `Run inspector` 里的 session 卡片现在会显示 lifecycle pill、last active / stale after / preview checked 等二级信息
    - Chat 云端模式现在除了原有“云端执行后补打一枪 session_info”以外，还会根据 backend 返回的 `recommendedRefreshAfterMs` 自动续刷
    - 本地模式仍不会显示 session / preview 卡片，也不会触发这条云端 lifecycle refresh 链
- `python_exec` 的 tool name 和参数目前保持不变
- 已把 E2B runtime 注入现有 owner/runtime 装配路径
  - 本地 `P4aPythonRuntime` 保持不变
  - 云端 backend 已接入到同一条 `python_exec` 路由
- 已把 `command_exec` 接入本地 / E2B 双后端路由
  - 显式 `local` 只走本地 `CommandExecutor`
  - 显式 `sandbox` 只走 E2B
  - 当前 E2B 前台命令优先走 provider-native envd 最小协议；前提不满足或 native 尝试失败时，才回退到 E2B `python_wrapper`
  - 当前 native foreground command 已补齐 provider handle telemetry，对齐后台命令的最小 provider-native 句柄语义
    - `Start` 请求现在会带稳定 `tag`
    - metadata 会写出：
      - `sandboxCommandProviderHandleKind=envd_process`
      - `sandboxCommandProviderStableSelectorKind=tag`
      - `sandboxCommandProviderStableSelectorValue`
      - `sandboxCommandProviderLiveSelectorKind`
      - `sandboxCommandProviderLiveSelectorValue`
    - 当前 foreground 仍只返回一次性执行结果，不具备 managed process 的 reconnect / host observation cursor 语义
  - wrapper fallback 仍会把本地 `workingDirectory` 映射到远端 workspace 路径，避免把宿主机绝对路径错误带进云端 subprocess
- 已把命令型 `ProcessStart` 接入本地 / E2B 双后端路由
  - `ProcessStart(script_path=...)` 继续复用已经接好的 `python_exec` runtime 路由
  - `ProcessStart(command=...)` 在 E2B 下优先走 provider-native `process.Process/Start`，并在不满足条件时回退到 E2B wrapper
  - `ProcessTerminate` 在云端 native 路径下已接到 provider-native `process.Process/SendSignal`
  - durable restore 场景下，`FileBackedAgentProcessRegistry` 现在会先尝试通过 provider-native `process.Process/Connect` 重挂运行中的后台命令，再决定是否标记为 interrupted
  - `ProcessRead/Wait/Terminate` 继续复用现有 registry/tool surface，不改工具名与参数；当前云端 native 路径仍是 `host_managed_snapshot` 观察模式，还没有 cursor/backfill 语义
  - 当前 `ProcessRead/Wait` 已补齐一层 host-managed incremental delivery
    - 同一个 chat session 内，重复调用 `ProcessRead/Wait` 时会消费 `sandboxCommandObservationCursor`
    - `AppAgentSessionTaskRuntimeFactory` 现在会按 session 共享 observation tracker，所以跨新的 task / turn / runtime facade 仍能延续上一次已交付边界
    - session 被 runtime manager release / idle release 时，这层 session-scoped tracker 也会一起回收，不会无限滞留在宿主内存里
    - 如果 cursor 正常前进，tool 结果只返回自上次已交付边界之后新增的 stdout/stderr
    - 如果当前 session 里还没有上一次已交付边界，但 durable reconnect snapshot 带回了：
      - `sandboxCommandReconnectSeedObservationCursor`
      - `sandboxCommandReconnectSeededStdoutBytes`
      - `sandboxCommandReconnectSeededStderrBytes`
      - 那么首个 `ProcessRead/Wait` 现在会直接把这组 reconnect seed 当成宿主侧 observation baseline
      - 这能让 durable restore 后的第一次续读尽量返回 `delta` / `no_change`，而不是默认把 seed 之前已经见过的输出整段重放一遍
    - 当前 metadata / 正文会额外写出：
      - `sandboxCommandObservationDeliveryMode`
      - `sandboxCommandObservationCursorBefore`
      - `sandboxCommandObservationCursorAfter`
      - `sandboxCommandObservationStdoutDeltaBytes`
      - `sandboxCommandObservationStderrDeltaBytes`
    - 如果宿主 observation cursor 回退或无法和当前输出字节边界对齐，当前会回退成 full snapshot delivery，并显式写 warning
    - 这层仍然不是 provider-native log cursor
    - 当前已经不只依赖 reconnect seed：
      - reconnect seed 仍会以 `reconnectState.seed` 的 typed durable state 持久化下来
      - `ProcessRead/Wait` 成功交付后，还会把最近一次已交付的 host observation boundary 持久化到 `deliveredObservationState`
      - 当 session observation tracker 缺失时，会先用 `deliveredObservationState`，再回退到 reconnect seed
    - 但这仍然不是 provider-native cursor resume / backfill；当前 durable state 虽然已经顺带保留 provider delivered boundary，但实际恢复逻辑仍以宿主侧已交付边界为主，没有把 session observation tracker 完整升级成 provider-native cursor
  - 当前 native managed controller 已补齐一层 provider handle scaffold，用来稳定表达 OpenCray `processId` 与 envd selector 的映射
    - `sandboxCommandProviderHandleKind=envd_process`
    - `sandboxCommandProviderStableSelectorKind=tag`
    - `sandboxCommandProviderStableSelectorValue`
    - `sandboxCommandProviderLiveSelectorKind`
    - `sandboxCommandProviderLiveSelectorValue`
    - `sandboxCommandIdKind=tag`
    - `sandboxCommandId`
      - 当前在 envd native backend 下，等同于 stable tag selector
    - reconnect 时还会额外写出：
      - `sandboxCommandReconnectSelectorKind`
      - `sandboxCommandReconnectSelectorValue`
      - `sandboxCommandReconnectSelectorSource`
        - 当前最小值为 `snapshot_pid` 或 `stable_tag`
    - terminate 时还会额外写出：
      - `sandboxCommandTerminateSelectorKind`
      - `sandboxCommandTerminateSelectorValue`
  - 当前 native managed controller 已补齐一层 host observation cursor scaffold，用来稳定标识宿主侧观察边界
    - `sandboxCommandHandleIdKind=tag`
    - `sandboxCommandHandleId`
    - `sandboxCommandHandleTag`
    - `sandboxCommandObservationEventCount`
    - `sandboxCommandObservationCursor`
      - 当前值形如 `host_seq_<n>`
    - `sandboxCommandObservationStdoutBytes`
    - `sandboxCommandObservationStderrBytes`
    - 这层游标只表达“宿主累计观察到了哪里”，不是 provider-native log cursor
  - 当前 native managed controller 也已补齐一层 provider observation scaffold，用来显式表达“provider event stream 看到哪里了”
    - `sandboxCommandProviderObservationMode=provider_event_stream_host_buffered`
    - `sandboxCommandProviderObservationEventCount`
    - `sandboxCommandProviderObservationCursor`
      - 当前值形如 `envd_seq_<n>`
    - `sandboxCommandProviderObservationBackfillSupported=false`
    - 当前这层仍然只是 provider event boundary 的宿主镜像，不是 provider 官方日志 cursor，也不具备 backfill
  - reconnect 路径现在会额外把恢复边界写进 metadata：
    - `sandboxCommandReconnectResumeMode=seed_snapshot_then_live_attach`
    - `sandboxCommandReconnectBackfillSupported=false`
    - `sandboxCommandReconnectOutputGapRisk=true`
    - `sandboxCommandReconnectRecoveryState`
      - 当前聚合值固定为：
        - `connecting`
        - `attached_live`
        - `retry_scheduled`
        - `completed`
        - `failed_terminal`
    - `sandboxCommandReconnectRetryable`
    - `sandboxCommandReconnectRetryAfterEpochMs`
    - `sandboxCommandReconnectAttemptCount`
    - `sandboxCommandReconnectLastAttachedAtEpochMs`
    - `sandboxCommandReconnectLastEventAtEpochMs`
    - `sandboxCommandReconnectLastEventKind`
    - `sandboxCommandReconnectLastFailureAtEpochMs`
    - `sandboxCommandReconnectSeedObservationCursor`
    - `sandboxCommandReconnectSeedProviderObservationCursor`
    - `sandboxCommandReconnectSeedEventCount`
    - `sandboxCommandReconnectSeedProviderObservationEventCount`
    - `sandboxCommandReconnectSeededStdoutBytes`
    - `sandboxCommandReconnectSeededStderrBytes`
  - 已把 native managed process 的 durable snapshot 模型补到 typed remote state 第一版
    - `ManagedProcessSnapshot` 仍是统一持久化载体，不额外拆出 provider 专用 record
    - 当前已新增结构化字段：
      - `remoteHandle`
      - `observationState`
      - `reconnectState`
      - `deliveredObservationState`
    - `FileBackedAgentProcessRegistry` 在加载旧的 metadata-only snapshot 时，会先做 normalize，把上述 typed fields 从历史 metadata 自动补齐
    - E2B native managed controller 在运行中写回 snapshot 时，已经会同时持久化 typed fields 与兼容 metadata 投影
    - durable reconnect、`ProcessRead`、`ProcessWait` 当前会优先读取 typed state；metadata 继续保留，作为兼容老快照和现有 tool result surface 的投影层
    - `ProcessRead/Wait` 现在会把“本次已经成功交付到哪里的 host observation 边界”写回 `deliveredObservationState`
    - 当 session-scoped observation tracker 丢失时，首个 `ProcessRead/Wait` 现在会先尝试使用这组 durable delivered observation state，再回退到 reconnect seed
    - 这组 `deliveredObservationState` 现在还会顺带持久化最近一次已交付时对应的 provider observation boundary
      - `providerMode`
      - `providerCursor`
      - `providerEventCount`
    - 当前这组 provider delivered boundary 还没有直接驱动 provider-native cursor resume；它的作用是先把 durable model 建稳，给下一步 provider-native log cursor / backfill 留出兼容位
    - `remoteHandle.sessionId` 已预留，但 E2B provider 当前仍没有稳定返回独立 `providerSessionId`，因此现阶段保持 nullable，不伪造值
- 已补齐未来 sandbox-native tools 的模型可见性规则
  - 约定所有沙盒原生能力工具统一使用 `sandbox_` 前缀
  - 本地模式下不向模型暴露 `sandbox_*` tool definitions
  - 显式云端模式且 E2B 可用时才向模型暴露这些 definitions
  - 过滤发生在 dispatcher/tool definition 层，不靠 prompt 文案硬编码隐藏
- 已补齐最小可用的 preview tool
  - 新增 `sandbox_preview_open`
  - 当前会基于活动中的 E2B sandbox session 生成 preview URL，并对该 URL 做一次短超时 reachability probe
  - probe 结果会区分 `ready`、`reachable`、`unreachable`
  - 如果当前 sticky session 只发现了一个候选端口，`sandbox_preview_open` 现在允许省略 `port`
  - 如果当前 sticky session 有多个候选端口，tool 会明确报错并返回候选值，要求模型显式指定端口
  - 仅在显式云端模式下向模型暴露
- 已补齐最小可用的 preview UI 宿主
  - Chat 主流里的 run trace 气泡现在可以把最新一次 `sandbox_preview_open` 结果映射成 preview 卡片
  - `Run inspector` 顶部滚动内容现在也会显示同一条 preview 的详细区块
  - 卡片当前展示标题、URL、provider / port / path / HTTP 状态、probe 状态
  - 卡片提供 `Open` 与 `Copy URL` 两个动作
  - 当前只在显式 `Run in cloud` 时显示；切回 `Run locally` 后不会渲染
  - preview service 现在会优先读取运行时内存里的活动 sticky sandbox session，再回落到持久化 session store
  - 这修复了 `sessionMode=sticky` 且 `autoResume=false` 时 preview 能力拿不到当前活动会话的问题
- 已补齐最小可用的 preview 自动端口发现
  - E2B runtime 现在会从 stdout/stderr 中提取候选端口，并写回 sticky session snapshot
  - 当前只识别保守模式：`localhost:3000`、`127.0.0.1:3000`、`0.0.0.0:3000`、`listening on port 3000`、`started server on 3000`
  - 候选端口会同时写入执行 metadata 的 `sandboxPreviewCandidatePorts`
- 已补齐最小可用的 session close 生命周期控制
  - 新增 `sandbox_session_close`
  - 当前可显式终止当前 workspace 对应的可复用 E2B sandbox session，并清掉本地 resume snapshot
  - 如果当前没有可复用 session，tool 会返回 no-op 风格的成功结果
  - 如果当前还有请求在同一 sandbox 内运行，tool 会明确失败并返回阻塞中的 request id，避免误杀正在执行的任务
- 已补齐最小可用的 session info 生命周期可视化
  - 新增 `sandbox_session_info`
  - 当前会返回当前 workspace 是否存在可复用 E2B sandbox session，以及它来自活动内存、持久化快照，还是两者同时存在
  - 当前会回传 sandbox id / domain / template / updatedAt / preview candidate ports / running request ids
  - 当前会进一步回传 lifecycle status、stale deadline、last active、preview last probe 等辅助状态
  - 读取时会优先以运行时内存里的活动 sticky session 作为主视图，再结合同一 sandbox 的持久化 snapshot 合并 preview 端口
  - 如果内存或持久化里存在其他 workspace 的 session，当前会按 not found 处理，而不是报 workspace mismatch
  - Chat 主流里的 run trace 气泡和 `Run inspector` 现在也可以把最新一次 `sandbox_session_info` 结果映射成 session 状态卡片
  - 该卡片当前只在显式 `Run in cloud` 时显示；切回 `Run locally` 后同样隐藏
  - 卡片状态主 badge 现在不再只显示 source，还会优先外显 `Healthy / Stale / Reclaimed / No session`

当前这一步的真实语义是：

- 如果用户选择 `Run locally`，`python_exec` 仍走本地 Python runtime
- 如果用户选择 `Run in cloud`，`python_exec` 会路由到 E2B
- 如果用户选择 `Run locally`，`command_exec` 与命令型 `ProcessStart` 仍走本地 backend
- 如果用户选择 `Run in cloud`，`command_exec` 与命令型 `ProcessStart` 会路由到 E2B

当前已额外验证的路由边界是：

- 当 backend 明确选择 `local` 时，不只是不执行 E2B，连 `sandboxRuntimeProvider` 都不会被 resolve
- 当 backend 明确选择 `sandbox` 时，不会触碰本地 Python runtime
- 当 backend 选择 `auto` 且 E2B 可用时，会优先走 E2B，不会先执行一次本地再切换
- 当 backend 明确选择 `sandbox` 但 E2B 不可用时，会返回明确错误，不会静默回落到本地

当前实现里几个关键技术决定也已经固定：

- Sticky 模式只复用 sandbox，不复用 Python code context
- 每次 `python_exec` 都会新建一个 code context，执行完成后删除
- 每次执行都会使用新的远端 workspace 子目录，避免 sticky 模式下远端残留文件污染下一次运行
- 远端文件系统当前仍是“执行镜像”，本地 workspace 仍是事实来源
- `python_exec` 在 E2B 执行后，会基于远端打印的 workspace diff manifest 回传 changed files 到本地 workspace
- 回传到本地的 changed files 现在会进一步暴露为现有 attachment artifact 元数据
  - agent 可以直接在后续 final response 中使用 `artifact_id`
- 本地删除当前不会直接删除本地事实来源之外的任何内容
  - 但 sticky session 现在会在用户脚本执行前，把“本地上一次存在、本次已经删掉”的远端 workspace 文件回放删除到 sandbox 内
  - 本地文件系统仍不会因为远端状态而被反向删除
- `.opencray`、`.git`、`node_modules`、`venv`、`__pycache__` 等内部或缓存目录不会参与回传下载
- `command_exec` 在 E2B 下当前已优先走 envd provider-native command API
  - 当前最小协议已覆盖 `process.Process/Start`
  - 前台 `Start` 当前也会显式带 `tag`，并在 metadata 里稳定表达 `tag -> pid` 的 live selector 演进
  - native 前台路径失败时，仍会安全回退到 E2B `python_wrapper`
- E2B 下的命令型 `ProcessStart` 当前也已优先走 provider-native managed command controller
  - 当前最小协议已覆盖 `process.Process/Start`、`process.Process/SendSignal`、durable restore 后的 `process.Process/Connect`
  - 宿主机仍持有本地 managed controller 线程，负责把 provider stream 聚合成现有 `ManagedProcessSnapshot`
  - `ManagedProcessSnapshot` 当前仍是统一 durable surface，但已经开始结构化承载远端状态第一版：
    - `remoteHandle`
    - `observationState`
    - `reconnectState`
  - 旧的 metadata-only durable snapshot 会在 registry normalize 时自动补齐 typed fields
  - 当前限制是 `ProcessRead/Wait` 看到的仍是宿主快照，而不是 provider cursor；虽然已经补齐 host-managed 增量交付，且 reconnect seed 已能通过 typed durable state 持久化，但还没有 provider-native 日志 backfill / cursor resume 语义

当前仍未完成的部分：

- 执行前增量上传目前只覆盖 sticky session + 本地元数据比对
  - 还没有做内容 hash 回退
  - 还没有做 provider 级通用 sync planner
- artifact 归档目前只完成“受控目录 + metadata 接线”
  - 当前已补齐默认保留 / 清理策略
  - 还没有做 artifact 类型分类、下载选择策略和可配置保留策略
- provider-native command backend 目前只做到 E2B envd 最小协议
  - 已支持 foreground/background start、background terminate、durable reconnect live reattach
  - 还没有做通用 Connect RPC 客户端栈
  - 还没有做 provider-native 日志 cursor、backfill、增量续读
  - 当前仅有基于 `host_managed_snapshot` 的增量交付层，不是 provider-native log cursor
  - 当前虽然已经补了 `sandboxCommandId`，`ManagedProcessSnapshot.remoteHandle` 里也已经预留 `sessionId`
    - 但 E2B provider 协议当前仍没有稳定给出独立 `providerSessionId`
    - 现阶段主要还是靠 `sandboxId + sandboxCommandId(tag)` 锚定远端命令
- preview/session lifecycle 的后续深化项仍未完成，但“最近一次 preview 生命周期写回 + tool/UI 暴露 + 宿主内嵌渲染”第一版已经落地
  - Chat 主流和 `Run inspector` 现在都会在显式云端模式下渲染内嵌 preview surface
  - embed config 由 host bridge 按当前 workspace 的活动/持久化 E2B session 即时解析
  - traffic token 不经 run trace metadata 暴露，只在宿主向 Flutter 返回 embed headers 时按需下发
  - 当前实现基于 Flutter WebView request headers；如果后续确认认证子资源请求不继承 header，需要升级为宿主 proxy
- 还没有做 snapshot / MCP gateway
- 还没有把 provider 级能力从 E2B 泛化到 Daytona / Modal

这意味着第一步当前达到的是：

- 本地执行能力保持不变
- 本地 / 云端切换对 `python_exec`、`command_exec`、命令型 `ProcessStart` 已经真实生效
- 现有 UI、设置、runtime wiring、取消语义和基础测试已经打通
- 下一步应继续把 preview / artifact / snapshot / MCP gateway 这些 sandbox-native 能力补齐，而不是再回到“是否保留本地执行”这类已经定板的问题

## 目标

本计划解决的是一件具体的事：

- 让 OpenCray 具备访问 E2B 等远端沙盒的能力。
- 让本地执行与云端沙盒执行长期并存，并可按任务、会话或策略自由选择。
- 在不破坏现有本地优先、策略优先、工作区优先设计的前提下，把远端沙盒接入到现有 agent runtime。
- 明确 `python_exec`、`command_exec`、`ProcessStart` 和 MCP runtime 是否需要修改，以及应该怎么改。
- 把“能获得什么新能力”写成可执行的交付路线，而不是停留在产品宣传层。

本文优先调查了三类方案：

- `E2B`
- `Daytona`
- `Modal Sandboxes`

推荐结论先写在最前面：

1. OpenCray 应该把 “远端沙盒” 设计成一层独立的执行后端抽象，与本地执行长期并存，先接 `E2B`，不要把 provider 细节直接写死进工具 handler。
2. V1 应优先做 “远端执行后端” 接入，而不是先做 “远端 MCP 工具全量代理”。
3. `python_exec` 不应该删除；它应被扩展成一个可在 `local` 与 `sandbox` 之间切换的统一 Python 执行入口，V1 可以不改用户可见参数，但后台实现和元数据模型必须扩展。
4. `command_exec`、`ProcessStart`、`ProcessRead`、`ProcessWait`、`ProcessTerminate` 必须一起纳入沙盒方案，否则能力会残缺。
5. MCP 接入要分开做。当前 OpenCray 已有 MCP 注册/信任/UI 基础，但还没有真正的远端 MCP 工具桥接能力。

## 当前基线

### 1. `python_exec` 已经是可插拔后端，但语义仍然是本地工作区脚本执行

当前实现有两个关键事实：

- `runtime/src/main/kotlin/com/opencray/runtime/PythonScriptRuntime.kt`
  - 已经把 Python 执行抽象成了可替换 backend。
- `runtime/src/main/kotlin/com/opencray/runtime/HostProcessPythonRuntime.kt`
  - 默认 backend 仍然是本地 `python -m python_runner.runner exec ...`。
- `runtime/src/main/kotlin/com/opencray/runtime/PythonExecRequest.kt`
  - 当前请求模型只表达了：
    - `workspaceRoot`
    - `scriptPath`
    - `args`
    - `timeoutMs`
    - `pythonExecutable`
    - `requestId`
    - `startupTimeoutMs`
- `python_runner/runner.py`
  - 明确假设脚本在本地 workspace 内部，且使用 workspace 自己的 `.opencray/python/venv`。

这意味着：

- OpenCray 已经具备一个非常好的 Python 接入口。
- 但这个接入口当前表达的是 “本地脚本运行时”，不是 “远端沙盒执行上下文”。

### 2. `command_exec` 和进程工具当前是本地进程语义

当前实现关键点：

- `runtime/src/main/kotlin/com/opencray/runtime/CommandExecutor.kt`
  - 直接通过 `ProcessBuilder` 启动本地命令。
- `runtime/src/main/kotlin/com/opencray/runtime/ModeGate.kt`
  - `CommandExecutionRequest` 也默认是本地命令 + 本地 working directory。
- `runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt`
  - `command_exec`、`ProcessStart`、`ProcessTerminate` 都是围绕本地/宿主进程模型设计的。

这意味着：

- 如果只给 `python_exec` 接 E2B，而 `command_exec`/`ProcessStart` 仍留在本地，OpenCray 会出现两套互相割裂的执行模型。
- 对 coding agent 来说，这种割裂会很难用，因为真实任务经常混合需要：
  - `git`
  - `npm`
  - `pytest`
  - `bash`
  - `python`
  - 长任务进程管理

### 3. MCP 当前只有“暴露状态”，没有“运行时桥接”

当前实现关键点：

- `mcp/src/main/kotlin/com/opencray/mcp/McpRuntimeSupport.kt`
  - 明确写死 `REMOTE_TOOL_BRIDGE_AVAILABLE = false`
  - 当前只支持 `mcp_list_servers`
- `runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt`
  - `mcp_list_servers` 的说明也写明 “remote MCP tools are not callable yet”
- `core/src/main/kotlin/com/opencray/core/contracts/McpSpec.kt`
  - OpenCray 已经具备三类 MCP transport contract：
    - `LocalStdio`
    - `RemoteHttp`
    - `RemoteSse`
- `app/src/main/kotlin/com/opencray/app/facade/mcp/McpSettingsFacade.kt`
  - UI 和信任状态、认证状态、逐服务器控制都已经有了。

这意味着：

- OpenCray 并不缺 MCP 服务器注册模型。
- 缺的是：
  - 真正的 MCP client connection manager
  - 动态 tool discovery
  - tool call proxy
  - 远端工具的策略分类与审批

### 4. 当前策略管线还没有“远端沙盒”语义

当前实现关键点：

- `runtime/src/main/kotlin/com/opencray/runtime/policy/ToolPolicyPipeline.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/policy/ToolIntentModels.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/policy/ToolCapabilityClassifier.kt`

已有能力：

- 本地文件
- 本地 working directory
- 本地脚本
- 本地命令
- 进程生命周期
- 网络访问

但还缺少：

- 远端沙盒 provider
- 远端 sandbox session id
- 远端 sandbox template/snapshot
- 远端网络策略
- 远端 secure access
- 远端 preview/port exposure
- 远端 MCP gateway

结论：

- 如果要认真接入 E2B，这部分一定要补元数据和 intent 模型。
- 不能只在 handler 里偷偷发 HTTP 请求。

## 官方调研结论

## E2B

### 官方能力确认

基于官方文档，E2B 当前明确提供：

- 按需创建隔离 Linux sandbox
- 在 sandbox 内执行命令
- 文件系统读写、上传、下载
- 后台命令执行与 kill
- 连接到已运行 sandbox
- 生命周期超时、暂停/恢复、快照
- 默认安全访问控制
- 出网控制
- 桌面/Computer Use
- MCP gateway
- 200+ MCP servers
- 自定义 MCP server
- `runCode`/`run_code` 这类代码解释执行能力

### 与 OpenCray 最相关的官方事实

1. E2B 把 sandbox 定义为可编程控制的隔离 Linux 环境，而不是单一的 Python executor。
2. `commands.run()` 支持前台命令，后台命令需要 `background: true`，并可后续 kill。
3. 文件系统具备读写、上传、下载能力，并支持 pre-signed URL。
4. sandbox 支持 `Sandbox.connect()`，可复用同一个 sandbox。
5. sandbox 支持 snapshots，用于 checkpoint、rollback、fork。
6. sandbox 默认支持互联网访问，但可通过 `allowInternetAccess` 和更细粒度 network 规则收紧。
7. secure access 在新 SDK 中默认开启。
8. E2B 提供内建 MCP gateway，可在 sandbox 内通过 `http://localhost:50005/mcp` 访问，并需要 MCP token。
9. E2B 官方文档写明可接入 200+ MCP servers，并支持从 GitHub repo 启动自定义 stdio MCP server。
10. E2B 还提供 Desktop SDK，支持截图、点击、键盘输入、滚动、VNC stream。

### 对 OpenCray 的意义

E2B 不是只对应 `python_exec`。

它更适合被看作三层能力：

- 第一层：远端执行后端
  - 命令、Python、文件、后台进程
- 第二层：远端隔离环境
  - 生命周期、快照、网络策略、端口、桌面
- 第三层：远端 MCP 宿主
  - gateway、server catalog、自定义 server

因此：

- 如果 OpenCray 只把 E2B 接到 `python_exec`，能用，但会浪费大部分能力。
- 如果 OpenCray 先把 E2B 当成统一的 sandbox backend，再逐步解锁 MCP 和 desktop，路线会更稳定。

## Daytona

### 官方能力确认

Daytona 官方文档确认提供：

- sandbox 中的 shell command execution
- working directory / timeout / env 选项
- 代码执行
  - stateless 多语言
  - stateful Python interpreter
- background process sessions
- 文件上传下载
- preview URLs
- computer use
- 官方 MCP server

### 与 OpenCray 最相关的官方事实

1. `process.exec` 支持：
  - cwd
  - timeout
  - env
2. Daytona 有 session operations，可管理长任务和交互式命令。
3. 文件系统 API 比较完整，含 upload/download。
4. preview 支持生成带认证的 URL。
5. computer use 包含鼠标、键盘、截图、录屏等。
6. 官方 MCP server 直接暴露：
  - sandbox management
  - file system
  - git
  - process and code execution
  - computer use
  - preview
7. Daytona 文档明确提到：
  - stateful interpreter 当前只支持 Python。

### 对 OpenCray 的意义

Daytona 是最像 “OpenCray 未来完整沙盒接口” 的 provider。

它的优点：

- 对 command/process/session 语义支持清晰
- 对 preview/computer use/MCP 的一体化程度高
- 适合做多能力 provider 抽象的第二实现

它的缺点：

- 对 OpenCray 当前落地而言，接入复杂度不会比 E2B 更低
- 生态心智上不像 E2B 那样已经强绑定 “AI code sandbox + MCP gateway”

结论：

- `Daytona` 很适合作为 OpenCray provider abstraction 的第二个目标实现。
- 但如果只能先接一个，仍建议先做 `E2B`。

## Modal Sandboxes

### 官方能力确认

Modal 官方文档确认提供：

- 安全容器化 sandbox
- 运行不可信代码
- 命令执行
- timeout / idle timeout
- networking control
- connect token / tunnels
- 文件访问
- Volume / persistent file sharing
- filesystem / directory / memory snapshots

### 与 OpenCray 最相关的官方事实

1. Sandboxes 默认是 secure-by-default。
2. 默认 maximum lifetime 为 5 分钟，最多可配置到 24 小时。
3. 可以通过 `block_network=True` 彻底禁网，并用 `cidr_allowlist` 控制出网范围。
4. 可通过 connect tokens / tunnels 暴露服务。
5. 有 filesystem snapshots、directory snapshots、memory snapshots。
6. 文档很适合 “安全容器执行 + 持久化 state + storage/volume” 场景。

### 对 OpenCray 的意义

Modal 更像：

- 通用安全容器执行平台
- 而不是“自带 MCP gateway 的 agent sandbox 平台”

它适合的场景：

- 安全执行
- 资源隔离
- 长任务
- volume / snapshot
- 需要强基础设施能力的后端集成

它不适合做 OpenCray 第一阶段的主要原因：

- 官方文档里没有像 E2B 那样的一体化 MCP gateway 入口
- 更偏云后端/容器工作流，不如 E2B/Daytona 贴近 agent 运行时语义

结论：

- Modal 可以作为后续“后端型 sandbox provider”备选。
- 不建议把它作为 OpenCray 第一阶段接入目标。

## 方案比较

| 维度 | E2B | Daytona | Modal |
| --- | --- | --- | --- |
| 命令执行 | 强 | 强 | 强 |
| Python/代码执行 | 强 | 强 | 中 |
| 后台进程/会话 | 强 | 强 | 中 |
| 文件上传下载 | 强 | 强 | 强 |
| 快照/恢复 | 强 | 中强 | 强 |
| 网络控制 | 强 | 中 | 强 |
| Preview/端口暴露 | 中强 | 强 | 强 |
| Computer Use | 强 | 强 | 弱 |
| MCP 原生能力 | 很强 | 强 | 弱 |
| 与 agent 场景贴合度 | 很高 | 很高 | 中 |
| 作为 OpenCray 第一接入目标 | 推荐 | 次选 | 不推荐 |

## 推荐路线

### 总体推荐

采用两层接入策略：

1. `远端执行后端层`
   - 目标：让现有 `python_exec`、`command_exec`、进程工具在“本地执行”和“远端沙盒执行”之间可切换。
   - 首个 provider：`E2B`
2. `远端 MCP 桥接层`
   - 目标：让 OpenCray 真正调用远端 MCP tool，而不只是列出 server。
   - 首个 provider：`E2B MCP gateway`

### 执行模式目标

本计划的目标不是把本地执行替换成云端执行，而是建立长期并存的双后端模型：

- `local`
  - 继续使用现有 host/embedded runtime
- `sandbox`
  - 使用 E2B、后续可扩展到 Daytona/Modal

理想状态下，OpenCray 需要支持四层选择机制：

1. 全局默认
   - 例如用户偏好默认本地或默认沙盒
2. 会话级选择
   - 当前 chat/session 绑定为 local 或 sticky sandbox
3. 工具级自动选择
   - 由 runtime 根据任务类型、依赖、风险、平台能力自动决定
4. 单次调用覆盖
   - 某一次 `python_exec` 或 `command_exec` 显式指定 backend

推荐原则：

- 本地能安全完成、且不需要额外隔离时，优先允许本地执行。
- 需要隔离、不可信依赖、长任务、预览服务或远端能力时，切换到 sandbox。
- 不让模型直接控制 provider 的所有细节，但应允许 runtime 接受“local / sandbox”这一层级的显式选择。

## 设置入口建议

### MVP 决策

当前产品决策建议明确为：

- MVP 直接支持填写 `E2B API key`
- 不把 E2B 塞进 LLM provider 设置
- 入口放在：
  - `Settings > API Integrations > Sandbox Providers > E2B`

原因：

- E2B 是执行环境 provider，不是模型 provider。
- 它和 LLM、Web Search、Media API 处于不同层级。
- 从当前仓库结构看，挂在 `API Integrations` 下最自然，也最便于未来加入 `Daytona` 等其他 sandbox provider。

### MVP 页面最小字段

建议第一版只做这些字段：

- `Enabled`
- `API key`
- `Default backend`
  - `auto`
  - `local`
  - `sandbox`
- `Session mode`
  - `ephemeral`
  - `sticky`
- `Timeout`
- `On timeout`
  - `kill`
  - `pause`
- `Auto resume`
- `Template ID`（可选）

### 凭据存储

虽然 MVP 允许直接填 API key，但存储方式仍应保持现有安全约束：

- UI 层表现为直接输入 API key
- 持久化层不存明文
- 进入 secure vault 后只保存 `CredentialRef`
- 读取 runtime config 时再解析真实 secret

换句话说：

- “直接填 API key” 是产品交互选择
- 不是“把 key 明文存进普通设置项”

### 为什么不是 “直接把 E2B MCP 当全部方案”

因为这样会有三个问题：

1. OpenCray 当前还没有 MCP runtime bridge。
2. 现有 `command_exec` / `python_exec` / `ProcessStart` 的用户心智和测试基线都会失效。
3. 远端 MCP tool 的 side effect 分类比本地工具更难，需要额外的 policy contract。

因此：

- 先做 sandbox execution backend。
- 再做 MCP bridge。

## `python_exec`、`command_exec`、MCP 到底要不要改

## 1. `python_exec`

### 结论

- 要改后台，不建议先改用户可见参数。
- 不应该删除，也不应该被 `E2B runCode` 直接替代。
- 应扩展成一个统一的 Python 执行入口，支持 `local` 与 `sandbox` 双后端共存。

### 为什么

OpenCray 现在的 `python_exec` 已经有良好的 backend injection 结构，这是优势，不是负担。

但是它现在的问题是：

- 语义写死成 “workspace-local Python script”
- prompt 也明确告诉模型它是本地工具
- `python_runner` 假设有本地 `.venv`

### 推荐改法

V1：

- 保持 tool name 不变：仍叫 `python_exec`
- 保持用户参数不变：
  - `script_path`
  - `args`
  - `timeout_ms`
  - `startup_timeout_ms`
- 在 runtime config 中注入新的 backend：
  - `LocalPythonScriptRuntime`
  - `E2BPythonScriptRuntime`
- 增加一个非必填、可控暴露的 backend 选择层：
  - 可由 session config、policy、用户显式选项或 tool metadata 决定
  - 默认不要求模型每次都传 provider 细节

V1.5：

- 给 `PythonExecRequest` 增加或旁挂一层 execution context，但不要直接暴露给模型：
  - `backend = local | sandbox`
  - `provider = e2b | daytona | modal`
  - `sandboxSessionId`
  - `templateId`
  - `snapshotId`
  - `internetPolicy`
  - `syncMode`

V2：

- 如果需要 notebook/cell 语义，再新增单独工具，例如：
  - `python_code_exec`
  - 或 `sandbox_run_code`

### 不建议的做法

- 不建议在 V1 把大量 provider 参数直接暴露进 `python_exec` 的 model-facing 参数里。
- 不建议把 `python_exec` 直接变成 “执行任意字符串代码”，这会破坏当前脚本路径 + 工作区边界模型。

## 2. `command_exec`

### 结论

- 必须改。

### 为什么

如果 OpenCray 具备 E2B，但 `command_exec` 仍只能跑本地，那么：

- `python_exec` 在远端
- `command_exec` 在本地
- `ProcessStart` 在本地

模型面对同一个 workspace 会得到完全不一致的执行结果。

### 推荐改法

新增抽象：

- `CommandExecutionBackend`
  - `LocalCommandExecutionBackend`
  - `E2BCommandExecutionBackend`

并要求它与 `python_exec` 一样支持双后端选择：

- 默认本地
- 可显式切到 sandbox
- 可由策略强制切换到 sandbox

或复用现有结构：

- 把 `CommandProcessRunner` 从 “本地子进程 runner” 升级为 “本地/远端 command runner” 抽象。

同时补充元数据：

- `executionBackend`
- `sandboxProvider`
- `sandboxSessionId`
- `sandboxCommandId`
- `sandboxSecureAccess`
- `sandboxNetworkPolicy`

### “provider 原生命令 API” 到底指什么

这里说的不是 tool call 协议。

当前模型侧看到的仍然是：

- `command_exec`
- `ProcessStart`
- `ProcessRead`
- `ProcessWait`
- `ProcessTerminate`

这些 tool 的名字、参数和 transcript 形态可以继续保持稳定。真正要替换的是云端 backend 的最后一跳执行方式。

当前 E2B 命令执行链路的真实形态是：

- OpenCray tool dispatch 命中 `command_exec` 或 `ProcessStart`
- runtime 路由把请求分流到云端 backend
- 云端 backend 当前会优先尝试 provider-native envd command API
- 如果 reusable session、`envdAccessToken`、`remoteWorkspaceRoot` 等前提不满足，或 native 调用失败，则回退到 Python wrapper
- wrapper fallback 时，仍然会把最终 shell command 交给远端 Python 里的 `subprocess.run(...)` 或同类控制器代为执行

这条链路当前同时保留了两组优点：

- native path 已能拿到 provider 原生命令 pid、termination、durable reconnect 语义
- wrapper fallback 继续复用了已经接好的：
  - `python_exec` 路由
  - E2B session lifecycle
  - 取消/超时基础设施
  - workspace 同步与结果回传

但它也带来几个明确限制：

- 并不是所有云端命令都必然进入 native path；native 仍受 session / token / remote workspace 前提约束
- `ProcessRead/Wait` 当前读到的仍是宿主聚合后的 `ManagedProcessSnapshot`，不是 provider cursor
- stdout/stderr 还没有 provider 级 cursor / backfill / resume 能力
- 当前 native backend 的 `sandboxCommandSupportsStreamingLogs` 仍是 `false`
- 为了避免把这项 `false` 误读成“完全没有 live 观察能力”，当前还额外暴露：
  - `sandboxCommandSupportsManagedProcessLiveObservation=true`
  - `sandboxCommandSupportsManagedProcessObservationCursorResume=false`
  - `sandboxCommandSupportsManagedProcessObservationBackfill=false`
- 因此后续做更细粒度日志续读时，语义仍然不如完整 provider-native process handle 稳定

因此，文档里说的 “provider 原生命令 API” 指的是：

- 保持 OpenCray 现有 tool surface 不变
- 但把云端 backend 从 “Python wrapper 间接执行” 升级成 “直接调用 provider 的 foreground command / background command API”

### provider 原生命令后端拆解

在 2026-03-31 这轮依赖审计里，OpenCray 当前仓库仍然没有现成可复用的：

- `connectrpc`
- `grpc`
- `protobuf`

而 E2B 官方 JS SDK 的 commands/background 能力底层走的是 sandbox 内 `envd` 的 Connect RPC 协议，而不是一组可以直接拿 `HttpURLConnection` 平替的普通 REST 端点。

这意味着：

- 这一步现在不能假装“只要补几个 HTTP endpoint 就能 native 化”
- 当前已经落地的最小方案是：
  - 在 backend 层把 provider-native 作为首选目标显式建模
  - 对 E2B envd 手写最小 Connect envelope + protobuf 编解码，而不是先引入完整 `connectrpc/grpc/protobuf` 依赖栈
  - 先把 `provider_native` 接到现有选择器里，保留 `python_wrapper` fallback
  - 把 fallback 原因、native attempt、reconnect telemetry 都写进 metadata，避免后面排查时误以为已经走了完整原生命令通道

截至当前，这个最小协议已经覆盖：

- foreground `command_exec` 的 envd `process.Process/Start`
- background `ProcessStart` 的 envd `process.Process/Start`
- `ProcessTerminate` 的 envd `process.Process/SendSignal`
- durable restore 后运行中 native background command 的 envd `process.Process/Connect` live reattach

但它仍然没有覆盖：

- provider-native 日志 cursor
- 断线后的 backfill / 增量续读
- 通用化、可复用的 Connect RPC 客户端栈

#### 1. 保持 tool surface 不变，只替换 backend

第一原则是不要重做模型协议。

保留：

- `command_exec`
- `ProcessStart`
- `ProcessRead`
- `ProcessWait`
- `ProcessTerminate`

只替换：

- 云端 `CommandProcessRunner`
- 云端 `ManagedProcessControllerFactory`
- registry 中远端 process handle 的落盘与恢复方式

这样做的好处是：

- prompt、tool policy、UI、run trace 不需要整体返工
- 本地 / 云端双后端仍共用同一套 tool 心智
- 升级可以渐进发生在 runtime backend 层

#### 2. 先补 foreground command backend

新增 provider-native 前台命令抽象，例如：

- `SandboxCommandExecutionBackend`
  - `executeForeground(...)`
  - `startBackground(...)`
  - `readBackground(...)`
  - `waitBackground(...)`
  - `terminateBackground(...)`
  - `supportsStreamingLogs`
  - `supportsReconnect`
  - `supportsManagedProcessLiveObservation`
  - `supportsManagedProcessObservationCursorResume`
  - `supportsManagedProcessObservationBackfill`

E2B 的第一步已落地：

- `E2BNativeCommandExecutionBackend.executeForeground(...)`

它负责直接把这些语义映射到 provider：

- `command`
- `args`
- `workingDirectory`
- `timeout`
- `stdout`
- `stderr`
- `exitCode`
- provider 侧 command/session id

这一层已经让 `command_exec` 在满足 native 前提时不必再借道 `python_exec`；当前只在 session / envd token / remote workspace 缺失或 native 调用失败时回退 wrapper。

#### 3. 再补 background command / process backend

`ProcessStart` 不能只换前台命令；它需要真正的远端 process handle。

建议新增：

- `E2BNativeManagedProcessControllerFactory`
- `ProviderManagedProcessHandle`
  - `providerId`
  - `sandboxId`
  - `sessionId`
  - `commandId`
  - `stdoutCursor`
  - `stderrCursor`
  - `startedAtEpochMs`
  - `lastObservedAtEpochMs`

当前第一步已经做到：

- `ProcessStart` 走 provider-native `Start`
- `ProcessTerminate` 走 provider-native `SendSignal`
- `AgentProcessRegistry` 在恢复 `RUNNING` snapshot 时，会优先通过 reconnectable backend 尝试 live reconnect
- 路由层和 selection decorator 会在 reconnect 时继续保持 backend 选择与 metadata

但 `AgentProcessRegistry` 当前仍以现有 `ManagedProcessSnapshot` 作为统一 durable surface，而不是额外拆出 provider handle record。

这层 snapshot 已经开始结构化承载远端状态第一版：

- `remoteHandle`
- `observationState`
- `reconnectState`

旧的 metadata-only durable snapshot 会在 registry normalize 时自动补齐这些 typed fields。

当前剩余缺口已经不再是“有没有 provider handle 落盘”，而是：

- provider-native log cursor / backfill / resume
- provider 实际返回的独立 `sessionId`
- 更细粒度的 durable observation tracker

这样 `ProcessRead/Wait/Terminate` 才能真正变成：

- `ProcessRead` 读取 provider 原生日志
- `ProcessWait` 等待 provider 原生命令结束
- `ProcessTerminate` 调 provider 原生命令终止/关闭接口

#### 4. 明确 wrapper 与 native 的边界

不建议“一刀切删除 wrapper”。

更稳妥的做法是：

- `python_exec` 继续走当前已落地的 code-interpreter 路线
- `command_exec` 与命令型 `ProcessStart` 优先走 provider-native command backend
- 如果 provider 某些能力暂时缺失，再由 capability 决定是否允许 fallback 到 wrapper

建议 fallback 规则写死在 backend capability，而不是散落在 tool handler：

- foreground command 可选 fallback
- background process 只有在 native 启动前前提不满足时才允许显式 fallback 到 E2B wrapper；一旦已经进入 native reconnect / live session 语义，就不再偷偷切回本地
- 如果 native background command 不可用，应返回明确能力错误，而不是悄悄退回到“伪后台 wrapper”

原因是后台进程语义一旦 silently fallback，很容易让 `ProcessRead/Terminate` 的行为和用户预期不一致。

#### 5. 分阶段验收

建议把这项工作拆成 3 个可独立验收的交付：

1. `command_exec` 云端前台命令改成 provider-native
   - 验收点：
     - 不再生成 Python wrapper
     - metadata 带上 `sandboxCommandId`
     - timeout / exitCode / stdout / stderr 结果和现有 envelope 对齐
2. `ProcessStart/Read/Wait/Terminate` 改成 provider-native background command
   - 验收点：
     - `ProcessStart` 返回可恢复的 provider handle 映射
     - `ProcessRead` 能续读增量日志
     - `ProcessTerminate` 走 provider 原生终止语义
3. 恢复与流式能力
   - 验收点：
     - App 重启后仍能恢复云端 process handle
     - 支持日志 cursor / 续读
     - UI 能展示更接近真实远端状态的 running / success / failed / cancelled

#### 6. 这一步不需要改的部分

这次升级不应该顺手改掉下面这些层：

- 模型看到的 tool names
- tool call 参数协议
- transcript 事件模型
- 本地 backend 行为
- `python_exec` 现有语义

真正要动的是：

- 云端 command backend
- 云端 managed process backend
- provider handle 持久化模型
- 与之对应的 metadata / lifecycle 恢复逻辑

## 3. `ProcessStart` / `ProcessRead` / `ProcessWait` / `ProcessTerminate`

### 结论

- 必须改，而且要成套改。

### 为什么

E2B 和 Daytona 都支持后台命令/会话。如果只接前台执行，不接长任务，会丢失非常多价值：

- dev server
- test watcher
- build server
- browser automation
- long-running agent job
- preview server

### 推荐改法

新增统一的 remote process/session model：

- OpenCray `processId`
- provider `sandboxId`
- provider `sessionId`
- provider `commandId`
- 状态映射：
  - running
  - success
  - failed
  - timeout
  - cancelled

同时扩展 `AgentProcessRegistry`，让它能持久化：

- 远端 session
- 远端 command
- preview URL
- 可恢复连接信息

## 4. 本地文件工具 `Read` / `Write` / `Edit` / `ImportFile`

### 结论

- V1 不建议改成“直接读写远端沙盒文件系统”。

### 为什么

OpenCray 现在的核心设计是：

- 本地 workspace 是事实来源
- 策略管线围绕本地路径解析
- UI / transcript / rollback / file ops 都围绕本地文件

如果直接把 `Read/Write/Edit` 切到远端，会出现：

- 本地与远端状态漂移
- 回滚语义不清
- 文件审批与工作区边界语义失真

### 推荐改法

V1 保持：

- 本地 workspace 仍是 source of truth

新增：

- `SandboxWorkspaceSyncService`
  - 上传本地选中文件/目录到远端 `/workspace`
  - 执行命令后把输出文件、日志、产物下载回本地

## 5. MCP runtime

### 结论

- 必须改，而且不是小改。

### 为什么

OpenCray 当前只有：

- registry
- trust/auth state
- UI
- `mcp_list_servers`

缺少真正可调用远端工具的 runtime bridge。

而 E2B 的价值之一恰恰在于：

- MCP gateway
- 200+ server catalog
- 自定义 server

### 推荐改法

分两步：

1. 先让 OpenCray 能以 MCP client 身份连接 server
2. 再让远端 tool 真正暴露给 agent

V1.5：

- 新增 `McpConnectionManager`
- 支持：
  - `RemoteHttp`
  - `RemoteSse`
  - `LocalStdio`
- 接入认证与凭据引用

V2：

- 新增动态 MCP tool proxy
- 生成规范化 tool 名称，例如：
  - `mcp__<server_id>__<tool_name>`
- 每个 remote MCP tool 必须有 capability/policy classification

### 不建议的做法

- 不要把 E2B 的 200+ MCP server 一次性全部暴露给 agent。
- 不要把 MCP tool 当成天然 read-only。
- 不要在没有 policy manifest 的情况下直接透传所有远端 tool。

## OpenCray 应获得的新能力

接入完成后，OpenCray 可以新增至少以下能力。

## 1. 安全运行不可信代码

- 用户项目的测试、构建、脚本执行不再必须触碰本机或移动端本地环境。
- 对 Android 端尤其有价值，因为本地 runtime 能力和依赖安装能力天然受限。

## 2. 多语言执行能力提升

不仅是 Python，还可以稳定执行：

- shell
- node
- npm
- pnpm
- ruby
- java
- 自定义模板里的任意工具链

## 3. 长任务与可恢复会话

- 同一个 chat/session 绑定同一个 sandbox
- 多轮复用环境
- 超时后恢复
- 后台服务管理

## 4. 更强的依赖隔离

- 不污染本地 Python / Node / shell 环境
- 可按 sandbox template 固定依赖版本

## 5. Snapshot / Rollback / Fork

这类能力对 agent 非常关键：

- 风险操作前打快照
- 失败后回滚
- 从同一状态 fork 多个分支并行探索

## 6. Preview / 端口暴露

可以为：

- dev server
- web app preview
- notebook
- local service

生成 preview URL，并把它回传给 UI 或 agent。

## 7. Desktop / Computer Use

接入 E2B Desktop 或 Daytona Computer Use 后，可以支持：

- 浏览器自动化
- GUI 测试
- screenshot 驱动 agent
- later: mobile app 外部桌面代理工作流

## 8. 远端 MCP 工具生态

这是最有价值但也最危险的一层：

- Browserbase
- Exa
- GitHub
- Notion
- Stripe
- Filesystem
- Git
- Firecrawl
- 自定义 GitHub repo 启动的 MCP server

## 推荐目标架构

## 一、执行后端抽象

新增包建议：

- `runtime/src/main/kotlin/com/opencray/runtime/sandbox/`

建议核心接口：

- `SandboxProvider`
- `SandboxSessionManager`
- `SandboxSessionHandle`
- `SandboxWorkspaceSyncService`
- `SandboxArtifactBridge`
- `SandboxExecutionMetadata`

建议 provider 实现：

- `E2BSandboxProvider`
- `DaytonaSandboxProvider`
- `ModalSandboxProvider`（后续）

## 二、工具层映射

建议保持 agent-facing tool surface 尽量稳定：

| 现有工具 | V1 映射 |
| --- | --- |
| `python_exec` | 统一入口，按 backend 选择本地执行或上传脚本到 sandbox 后执行 |
| `command_exec` | 统一入口，按 backend 选择本地前台命令或 sandbox 前台命令 |
| `ProcessStart` | 统一入口，按 backend 选择本地后台进程或 sandbox 后台命令/会话 |
| `ProcessRead` | 读取对应 backend 的进程/session 输出 |
| `ProcessWait` | 等待对应 backend 的进程/session |
| `ProcessTerminate` | 终止对应 backend 的进程/session |

V1 不建议新增大量 provider-specific tool。

V2 可新增 sandbox-native tool：

- `sandbox_snapshot_create`
- `sandbox_snapshot_restore`
- `sandbox_preview_open`
- `sandbox_session_close`
- `sandbox_session_info`
- `sandbox_run_code`
- `sandbox_desktop_screenshot`

这些工具应遵守一个额外约束：

- 工具名统一使用 `sandbox_` 前缀
- 仅当运行环境显式选择 `sandbox` 且远端 provider 已可用时，才把这些 tool definitions 暴露给模型
- `local` 与 `auto` 模式下默认不揭露这些 definitions，避免模型在非云端回合里规划依赖沙盒原生能力

## 三、策略模型扩展

当前 `ExecutionIntent` 不足以表达远端沙盒。

建议新增元数据字段：

- `executionBackend`
  - `local_host`
  - `termux`
  - `sandbox_remote`
- `sandboxProvider`
  - `e2b`
  - `daytona`
  - `modal`
- `sandboxSessionMode`
  - `ephemeral`
  - `sticky`
  - `snapshot_restored`
- `sandboxSessionId`
- `sandboxTemplateId`
- `sandboxSnapshotId`
- `sandboxNetworkPolicy`
- `sandboxSecureAccess`
- `sandboxPreviewEnabled`
- `sandboxPreviewCandidatePorts`
- `mcpGatewayBound`

建议增加一个用户/运行时可理解的选择字段：

- `executionBackendRequested`
  - `local`
  - `sandbox`
  - `auto`

并区分：

- `executionBackendRequested`
  - 请求方想要什么
- `executionBackend`
  - 实际最后落到哪个 backend

建议扩展枚举：

- `ExecutionTransport`
  - 新增 `SANDBOX_REMOTE`
- `ToolTargetKind`
  - 新增 `REMOTE_SANDBOX`
- 必要时新增 execution intent kind：
  - `SANDBOX_COMMAND`
  - `SANDBOX_PYTHON_SCRIPT`

## 四、MCP 运行时桥接

建议新增包：

- `runtime/src/main/kotlin/com/opencray/runtime/mcp/bridge/`

建议组件：

- `McpConnectionManager`
- `McpToolCatalogLoader`
- `McpToolProxyDispatcher`
- `McpToolPolicyManifest`
- `SessionScopedMcpServerResolver`

E2B 场景下建议支持两种模式：

1. `session-scoped gateway`
   - 每个 sandbox 自带 gateway
   - tool 绑定当前 sandbox 生命周期
2. `registry-scoped remote server`
   - 与当前 `McpServerSpec` 兼容
   - 适合固定的远端 HTTP/SSE server

## 落地计划

## Phase 0: 方案定板与安全前置

### 目标

确定接入策略，不写业务代码前先解决安全边界。

### 交付

- 确认首个 provider：`E2B`
- 确认凭据模式：
  - MVP：用户直接填写 E2B API key
  - Production：服务端换短期 token，避免长期 key 直存移动端
- 确认 sandbox template 策略：
  - base
  - coding-agent
  - desktop
- 确认 session 模式：
  - ephemeral
  - sticky
- 确认 backend 选择模型：
  - global default
  - session override
  - tool-call override
  - policy-forced sandbox
- 确认 workspace sync 范围

### 必做事项

- 在 `API Integrations` 下增加 `Sandbox Providers > E2B`
- 第一版允许用户直接填写 E2B API key
- 即便如此，仍必须走现有 secure credential reference，不允许明文持久化
- 规划 session cleanup / timeout / quota 机制

### 验收

- 有一页 capability matrix
- 有一页 security decision record
- provider、凭据、会话策略被明确拍板

## Phase 1: `python_exec` 的 E2B backend Spike

### 当前状态

截至 2026-03-27，本阶段已经基本完成，当前已落地内容如下：

- `python_exec` 继续保持原有 tool surface
- `RoutingPythonScriptRuntime` 已支持本地 / E2B 路由
- `E2BCodeInterpreterPythonRuntime` 已接入真实 E2B code-interpreter HTTP 链路
- 取消时会终止当前 sandbox
- sticky 模式会复用 sandbox，但每次调用都会重新创建 code context
- E2B 执行完成后，会把远端 changed files 下载回本地 workspace
- 回传结果会落到统一 metadata 中，包括：
  - `workspaceSyncManifestObserved`
  - `workspaceSyncManifestParseFailed`
  - `remoteChangedFiles`
  - `remoteDeletedFiles`
  - `downloadedFiles`
  - `downloadedBytes`
  - `skippedDownloadFiles`
  - `skippedRemoteDeletes`
  - `downloadFailures`
- 对成功回传的文件，当前还会额外写入：
  - `attachmentArtifactsJson`
  - `attachmentArtifactId`
  - `attachmentArtifactRelativePath`
- 关键单测已覆盖：
  - create + upload + execute happy path
  - sticky reconnect
  - changed-files download back to local workspace
  - cancellation
  - path escape deny

本阶段仍留给后续阶段处理的内容：

- 增量同步
- 远端删除回放
- 通用 artifact 归档 / 选择性下载
- preview / snapshot / MCP gateway

### 当前验证结果

截至 2026-03-27，以下测试已通过，用来锁定 `python_exec` 的本地 / 云端路由边界：

- `RoutingPythonScriptRuntimeTest.localPreferenceUsesLocalRuntimeWithoutResolvingSandboxRuntime`
  - 验证显式 `local` 时，本地 runtime 被调用，sandbox provider 调用次数为 `0`
- `RoutingPythonScriptRuntimeTest.autoPrefersSandboxRuntimeWhenAvailableWithoutTouchingLocalRuntime`
  - 验证 `auto` 且 E2B 可用时，直接走 sandbox runtime，本地 runtime 不被触碰
- `RoutingPythonScriptRuntimeTest.sandboxPreferenceDispatchesToSandboxRuntimeWhenAvailable`
  - 验证显式 `sandbox` 时，执行落到 E2B runtime
- `PythonExecToolRoutingIntegrationTest.pythonExecUsesLocalRuntimeOnlyWhenBackendPreferenceIsLocal`
  - 从 `python_exec` tool dispatch 入口验证显式 `local` 只走本地 runtime
- `PythonExecToolRoutingIntegrationTest.pythonExecUsesSandboxRuntimeOnlyWhenBackendPreferenceIsSandbox`
  - 从 `python_exec` tool dispatch 入口验证显式 `sandbox` 只走 E2B runtime
- `E2BCodeInterpreterPythonRuntimeTest`
  - 已覆盖真实 E2B runtime 的 happy path、sticky reconnect、changed-files download、cancellation、path escape deny
- `PythonBackedCommandExecutionTest`
  - 验证云端 `command_exec` wrapper 会把本地 `workingDirectory` 映射为远端 workspace 路径
- `ExecutionAttachmentArtifactSummaryTest`
  - 验证 `python_exec` / `command_exec` 在 execution metadata 带有 attachment artifacts 时，会把 `artifact_id` 摘要显式追加到 tool result content
- `SandboxPreviewToolTest`
  - 验证 `sandbox_preview_open` 的 definition 可见性、隐藏规则和 preview URL 返回结果
- `SandboxSessionControlToolTest`
  - 验证 `sandbox_session_close` 的 definition 可见性、隐藏规则，以及 terminated / busy 的 tool result 外显
- `E2BSandboxSessionControlServiceTest`
  - 验证 close service 对活动内存 session、持久化 session、无 session 与 workspace mismatch 的解析行为
- `SandboxSessionInfoToolTest`
  - 验证 `sandbox_session_info` 的 definition 可见性、隐藏规则，以及 session present / not found 的 tool result 外显
- `E2BSandboxSessionInfoServiceTest`
  - 验证 info service 对活动内存 session、持久化 session、active+persisted 合并视图与 workspace 不匹配时的 not-found 解析行为
- `RoutingCommandExecutorTest`
  - 验证显式 `local` 时不 resolve sandbox executor
  - 验证 `auto` 且 sandbox executor 可用时直接走 sandbox
  - 验证显式 `sandbox` 但 sandbox executor 不可用时返回明确错误
- `CommandExecToolRoutingIntegrationTest`
  - 从 `command_exec` tool dispatch 入口验证显式 `local` / `sandbox` 分别只走对应 executor
- `RoutingManagedProcessControllerFactoryTest`
  - 验证命令型 `ProcessStart` 在 `local` / `sandbox` 下只走对应 controller factory
  - 验证 `ProcessStart(script_path=...)` 仍优先复用 python runtime factory
- `ProcessStartToolRoutingIntegrationTest`
  - 从 `ProcessStart` tool dispatch 入口验证命令型 managed process 的本地 / 云端路由边界

本轮验证命令：

- `./gradlew.bat "-Dkotlin.compiler.execution.strategy=in-process" :app:compileDebugKotlin`
- `./gradlew.bat "-Dkotlin.compiler.execution.strategy=in-process" :app:testDebugUnitTest --tests "com.opencray.app.RoutingPythonScriptRuntimeTest" --tests "com.opencray.app.E2BCodeInterpreterPythonRuntimeTest" --tests "com.opencray.app.PythonExecToolRoutingIntegrationTest"`
- `./gradlew.bat :app:testDebugUnitTest --tests=com.opencray.app.PythonBackedCommandExecutionTest --tests=com.opencray.app.E2BCodeInterpreterPythonRuntimeTest`
- `./gradlew.bat :app:testDebugUnitTest --tests=com.opencray.app.E2BSandboxPreviewServiceTest --tests=com.opencray.app.E2BSandboxSessionControlServiceTest --tests=com.opencray.app.E2BCodeInterpreterPythonRuntimeTest`
- `./gradlew.bat :app:testDebugUnitTest --tests=com.opencray.app.E2BSandboxPreviewServiceTest --tests=com.opencray.app.E2BSandboxSessionControlServiceTest --tests=com.opencray.app.E2BSandboxSessionInfoServiceTest --tests=com.opencray.app.E2BCodeInterpreterPythonRuntimeTest`
- `./gradlew.bat :runtime:testDebugUnitTest --tests=com.opencray.runtime.SandboxPreviewToolTest --tests=com.opencray.runtime.SandboxSessionControlToolTest --tests=com.opencray.runtime.SandboxSessionInfoToolTest --tests=com.opencray.runtime.ExecutionAttachmentArtifactSummaryTest --tests=com.opencray.runtime.policy.ToolCapabilityClassifierTest`
- `./gradlew.bat :app:testDebugUnitTest --tests=com.opencray.app.E2BSandboxPreviewServiceTest --tests=com.opencray.app.E2BCodeInterpreterPythonRuntimeTest --tests=com.opencray.app.PythonBackedCommandExecutionTest`

本轮新增实现的验证说明：

- `PythonBackedCommandExecutionTest` 与 `E2BCodeInterpreterPythonRuntimeTest` 已在当前 dirty worktree 上再次完整通过
- 为了让目标测试重新编译，补了两处无关沙盒实现的旧测试签名兼容：
  - `OpenCrayRuntimeServiceHostTest`
  - `OpenCrayHostRuntimeTest`
- 这些兼容改动只是在 replay recorder lambda 上补齐新增的 `RuntimeReplayExecutionContext` 参数，没有改变测试断言语义

### 目标

在尽量不改 tool surface 的前提下，让 `python_exec` 在保留本地执行的同时，新增可选的 E2B backend。

### 代码改动范围

- `runtime/.../PythonScriptRuntime.kt`
- `runtime/.../PythonExecRequest.kt`
- `runtime/.../AgentTooling.kt`
- `app/src/main/kotlin/com/opencray/app/RoutingPythonScriptRuntime.kt`
- `app/src/main/kotlin/com/opencray/app/E2BCodeInterpreterPythonRuntime.kt`
- `app/src/main/kotlin/com/opencray/app/InProcessOpenCrayRuntimeOwner.kt`

### 设计

- 本地 workspace 仍为事实来源
- `python_exec` 先支持：
  - `local`
  - `sandbox:e2b`
- 执行前：
  - 同步脚本与必要输入文件到 sandbox
- 执行时：
  - 在 sandbox `/workspace` 内运行
- 执行后：
  - 回收 stdout/stderr
  - 当前已支持按 workspace diff manifest 把 changed files 回传到本地 workspace
  - 通用 artifact 受控目录仍留在后续阶段

### 原始阶段范围内不做

- 不做 `command_exec`
- 不做 MCP tool proxy
- 不做 desktop
- 不先做完整视觉打磨 UI，只做能力所需的最小配置入口

### 验收

- `python_exec` 在 local backend 和 E2B backend 下共用同一个 tool name
- 同一个 session 或同一个任务可以明确切换 local / sandbox
- transcript 元数据能区分 backend
- 失败/超时/取消路径可测试
- prompt 不再误导模型把 sandbox backend 视为“总是本地”

## Phase 2: `command_exec` 与进程工具的 sandbox 化

### 当前状态

截至 2026-03-31，本阶段也已经进入“可运行但仍偏保守”的状态：

- 已完成：
  - `command_exec` 已支持 `local / auto / sandbox` 路由
  - E2B 下的 `command_exec` 当前已支持 provider-native / wrapper fallback 混合模式
  - wrapper 会把本地 `workingDirectory` 映射到远端 workspace
  - 命令型 `ProcessStart` 已支持本地 / 云端双后端分流
  - `ProcessTerminate` 在云端模式下当前已支持 provider-native `SendSignal` 最小路径
  - 已落地 provider-native foreground command 的最小协议试点
    - 当前新增 `E2BMinimalProtocolSandboxCommandExecutionBackend`
    - 当前只把云端 `command_exec` 的前台命令升级到 envd Connect 最小客户端
    - 当前直接命中的是 E2B envd `process.Process/Start`
    - 当前协议实现不依赖完整 `connectrpc/grpc/protobuf` 依赖栈，而是手写了最小：
      - Connect envelope framing
      - `StartRequest` protobuf 编码
      - `StartResponse` / `ProcessEvent` protobuf 解码
    - 当前 native path 的结果 metadata 会额外回传：
      - `runtimeBackend=e2b_envd_native_command`
      - `runtimeTransport=connect_proto_minimal`
      - `sandboxCommandApi=envd_process_start`
      - `sandboxCommandNativeProtocol=envd_connect_process_v1`
      - `sandboxCommandNativeHttpStatusCode`
      - `sandboxCommandPid`
      - `sandboxCommandSessionSource`
      - `sandboxCommandNativeProcessStatus`
      - `sandboxCommandNativeProcessExited`
      - `sandboxCommandNativeEndStreamErrorCode`
      - `sandboxCommandNativeEndStreamErrorMessage`
    - 当前 native path 只在“已有可复用 E2B session，且 session 内存在 `envdAccessToken + remoteWorkspaceRoot`”时启用
    - 当这些前提不满足时，当前不会回落到本地，而是继续回退到 E2B `python_wrapper`
    - 如果已经实际发起过 native 尝试但随后在 provider 侧失败，wrapper fallback 结果现在也会保留最小诊断信息：
      - `sandboxCommandNativeAttempted`
      - `sandboxCommandNativeAttemptApi`
      - `sandboxCommandNativeAttemptTransport`
      - `sandboxCommandNativeAttemptProtocol`
      - `sandboxCommandNativeAttemptSessionSource`
      - `sandboxCommandNativeAttemptRemoteWorkingDirectory`
      - `sandboxCommandNativeAttemptFailureStage`
      - `sandboxCommandNativeAttemptHttpStatusCode`
      - `sandboxCommandNativeAttemptTransportFailureClass`
      - `sandboxCommandNativeAttemptTransportFailureMessage`
    - 这里的语义要特别明确：
      - foreground `command_exec` 已经有 provider-native 最小实现
      - background `ProcessStart` 已经接到 provider-native `process.Process/Start`
      - `ProcessTerminate` 已经接到 provider-native `process.Process/SendSignal`
      - durable restore 后，native background command 现在会优先尝试通过 envd `process.Process/Connect` 重新挂回 live stream
      - `ProcessRead/Wait` 当前仍读取宿主本地 managed controller 的累积快照，不具备 provider-native cursor / backfill 语义
      - 但在同一个 chat session 内，`ProcessRead/Wait` 现在会基于 `sandbox_command_observation_cursor` 做 host-managed 增量交付，避免同一段 stdout/stderr 在跨 task / turn 的连续读取里反复整段回放
      - 为了避免这层边界在 agent 侧变成“黑箱”，`ProcessRead/Wait` 当前正文与 working-state 摘要也会显式外显：
        - `runtime_backend`
        - `runtime_transport`
        - `sandbox_backend_resolved_kind`
        - `sandbox_observation_mode=host_managed_snapshot`
        - `sandbox_command_provider_observation_mode`
        - `sandbox_command_provider_observation_event_count`
        - `sandbox_command_provider_observation_cursor`
        - `sandbox_command_provider_observation_backfill_supported`
        - `sandbox_command_observation_delivery_mode`
        - `sandbox_command_observation_cursor_before`
        - `sandbox_command_observation_cursor_after`
        - `sandbox_command_observation_stdout_delta_bytes`
        - `sandbox_command_observation_stderr_delta_bytes`
        - `sandbox_supports_reconnect`
        - `sandbox_command_reconnect_api`
        - `sandbox_command_reconnect_status`
        - `sandbox_command_reconnect_recovery_state`
        - `sandbox_command_reconnect_source`
        - `sandbox_command_reconnect_http_status_code`
        - `sandbox_command_reconnect_resume_mode`
        - `sandbox_command_reconnect_backfill_supported`
        - `sandbox_command_reconnect_output_gap_risk`
        - `sandbox_command_reconnect_retryable`
        - `sandbox_command_reconnect_retry_after_epoch_ms`
        - `sandbox_command_reconnect_attempt_count`
        - `sandbox_command_reconnect_last_attached_at_epoch_ms`
        - `sandbox_command_reconnect_last_event_at_epoch_ms`
        - `sandbox_command_reconnect_last_event_kind`
        - `sandbox_command_reconnect_last_failure_at_epoch_ms`
        - `sandbox_command_reconnect_failure_stage`
        - `sandbox_command_reconnect_seed_source`
        - `sandbox_command_reconnect_provider_observation_seed_consumed`
        - `sandbox_command_reconnect_provider_observation_seed_state`
        - `sandbox_command_reconnect_provider_observation_seed_consumed_at_epoch_ms`
      - 当 `sandbox_command_reconnect_output_gap_risk=true` 时，正文还会追加一条 observation warning，明确说明当前是“从持久化快照补种输出后再挂 live stream”，attach 前可能存在日志缺口
        - 如果 `sandbox_command_reconnect_provider_observation_seed_state=pending_live_attach`，warning 会改成“已经恢复持久化 seed，但还在等待 live attach”，避免误读成已经重新挂上 live stream
        - 如果 `sandbox_command_reconnect_provider_observation_seed_state=consumed_live_attach`，warning 才表示“已经从 seed 进入 live attach，但 attach 前日志可能缺失”
      - 当 reconnect 因 transport 类问题失败且 provider 仍未给出终态时，当前不会立刻把进程判死；而是保留 `status=running` 并写出 `sandbox_command_reconnect_retryable=true`
        - 如果这次失败发生在 live attach 之前，warning 会明确说明“当前输出仍只反映持久化 host snapshot seed，后续 read/wait 还会重试 attach”
      - 当 reconnect 成功重新挂回 live stream 时，当前会额外记录 `sandbox_command_reconnect_last_attached_at_epoch_ms` 与最近一次 provider 事件的时间/类型，便于后续 UI 和恢复判断
      - reconnect 现在会把 durable snapshot 上的 host/provider observation 边界当成正式 restore seed，并显式写出：
        - `sandbox_command_reconnect_seed_source=durable_snapshot_metadata`
        - `sandbox_command_reconnect_provider_observation_seed_consumed`
        - `sandbox_command_reconnect_provider_observation_seed_state`
        - `sandbox_command_reconnect_provider_observation_seed_consumed_at_epoch_ms`
      - 如果新的 session-scoped observation tracker 还没有 previous boundary，而 snapshot 已经带了上述 durable reconnect seed：
        - 首个 `ProcessRead/Wait` 现在会直接拿 `sandbox_command_reconnect_seed_observation_cursor`、`sandbox_command_reconnect_seeded_stdout_bytes`、`sandbox_command_reconnect_seeded_stderr_bytes` 当 baseline
        - 因此恢复后的第一次 read/wait 会优先返回 `delta` / `no_change`
        - 只有当当前 host snapshot 相对 seed 发生回退，或 seed byte offset 无法和 UTF-8 输出边界对齐时，才会回退成 `reset_full`
      - 其中 `sandbox_command_reconnect_provider_observation_seed_state` 当前约定为：
        - `pending_live_attach`
          - 已经从 durable snapshot 补种了 seed，但本次 envd `Connect` 还没收到能证明 live attach 的 provider 事件
        - `consumed_live_attach`
          - 本次 envd `Connect` 已收到第一个非 end-stream 的 provider 事件，seed 已被 live attach 正式消费
        - `retry_scheduled_before_live_attach`
          - 本次 reconnect 还没 attach 成功就发生可重试失败；达到 backoff 后下一次 `ProcessRead/Wait` 会再试
        - `failed_terminal_before_live_attach`
          - 本次 reconnect 在 attach 前就失败为终态，不再继续重试
      - 当 `sandbox_command_reconnect_recovery_state=failed_terminal` 时，正文还会追加一条 warning，明确当前 live attach 没有成功建立，输出可能只反映持久化 host snapshot
      - 为了避免上层每次都自己拼 `status/retryable/lastAttached`，当前还会额外聚合 `sandbox_command_reconnect_recovery_state`
        - `connecting`
          - durable restore 刚发起 envd `Connect`，还没拿到可证明 live attach 的 provider 事件
        - `attached_live`
          - 已经收到第一个可证明 live attach 的 provider 事件并重新挂回 live stream，当前仍处于运行态
        - `retry_scheduled`
          - 这次 reconnect 没拿到终态，但被判定为可重试；达到 backoff 后下一次 `ProcessRead/Wait` 会再试一次
        - `completed`
          - reconnect 已经成功接到 live stream，并拿到了终态；这描述的是“恢复流程已完成”，不代表进程业务一定成功
        - `failed_terminal`
          - reconnect 在拿到 live attach 前就终止失败，不会再继续重试
      - 这类 snapshot 在达到 `sandbox_command_reconnect_retry_after_epoch_ms` 后，下一次 `ProcessRead/Wait` 会再次尝试 envd `Connect`
      - 因此当前“云端原生命令 API”已经覆盖前台命令和后台命令的 start/kill，并补上了宿主侧增量读取；但还没有覆盖完整的 provider-native 后台日志重连协议
    - 当前后台进程这一步的真实边界是：
      - `ProcessStart` 会给 envd `StartRequest` 写入稳定 tag，当前直接复用 OpenCray 的 `processId`
      - 宿主本地仍持有一个 managed controller 线程，负责消费 envd stream 并把 stdout/stderr 聚合成现有 `ManagedProcessSnapshot`
      - `ProcessTerminate` 通过 envd `SendSignal(SIGKILL)` 按 tag 发送 kill signal
      - durable restore 时，`FileBackedAgentProcessRegistry` 会先按持久化 snapshot 上的 `executionBackend` 把 reconnect 路由回 sandbox/local/python 对应工厂；在 E2B native 路径下再继续使用 envd `Connect`
      - `ManagedProcessSnapshot` 的 durable 载体仍沿用统一 snapshot，但现在已经会写入 typed remote state：
        - `remoteHandle`
        - `observationState`
        - `reconnectState`
      - 旧的 metadata-only snapshot 在 restore 时会被 normalize 到同一结构，减少 reconnect 对 metadata key 的硬依赖
      - 如果 native 背景路径在启动前发现 session / `remoteWorkspaceRoot` / `envdAccessToken` 不满足条件，当前仍只回退到 E2B wrapper，不会回到本地
  - 已补齐云端命令 backend 抽象第一版
    - 当前新增 `SandboxCommandExecutionBackend`
    - 当前 E2B 已同时接入：
      - `PythonBackedSandboxCommandExecutionBackend`
      - `E2BMinimalProtocolSandboxCommandExecutionBackend`
    - 现有 `python-backed wrapper` 已不再直接散落在 owner 装配里，而是挂在统一 backend 抽象之后
    - 当前 `command_exec` / `Process*` 的结果 metadata 会额外暴露：
      - `sandboxCommandBackendKind`
      - `sandboxCommandProviderNative`
      - `sandboxCommandSupportsStreamingLogs`
      - `sandboxCommandSupportsReconnect`
      - `sandboxCommandSupportsManagedProcessLiveObservation`
      - `sandboxCommandSupportsManagedProcessObservationCursorResume`
      - `sandboxCommandSupportsManagedProcessObservationBackfill`
    - 已补齐 provider-native 优先 / wrapper fallback 的选择层
      - 当前会额外暴露：
        - `sandboxCommandBackendRequestedKind`
        - `sandboxCommandBackendResolvedKind`
        - `sandboxCommandProviderNativeRequested`
        - `sandboxCommandProviderNativeAvailable`
        - `sandboxCommandBackendFallbackReasonCode`
      - 当前 E2B 的默认选择是：
        - requested=`provider_native_preferred`
        - 当前满足 envd session 前提时 resolved=`provider_native`
        - 当前不满足前提或 native 调用失败时 resolved=`python_wrapper`
        - fallback reason 会按真实失败场景写回，例如 session 不可用、remote workspace 缺失、envd token 缺失、transport/http 失败
    - 这一步的目的不是改 tool surface，而是把后续切换到 provider-native commands 的替换面收缩到 backend 层
- 仍未完成：
  - 这已经不是 tool call 协议缺口；当前剩余缺口主要在 provider-native backend 的 cursor/resume 深化
  - 当前仓库里仍没有通用、可复用的 `connectrpc` / `grpc` / `protobuf` 客户端栈；现阶段用的是 E2B envd 的手写最小协议
  - 当前虽然已经能在 durable restore 后 live reattach，但还没有 provider-native 日志 cursor / backfill / resume 语义
  - `ProcessRead/Wait` 仍是 host-managed snapshot，而不是直接读取 provider 原生 cursor
  - durable restore 后的首个 `ProcessRead/Wait` 现在虽然能借助持久化 reconnect seed 做宿主侧 baseline，但这仍然不是 provider cursor resume，只是减少整段重放
  - `ManagedProcessSnapshot.remoteHandle.sessionId` 虽已建模，但 provider 协议目前仍未稳定返回独立 `providerSessionId`
  - `sandboxCommandSupportsStreamingLogs` 对当前 native backend 仍是 `false`
  - 当前这几个 capability 的语义已拆开：
    - `sandboxCommandSupportsStreamingLogs=false`
      - 表示还没有稳定的 provider-native 日志 cursor/backfill/resume 能力
    - `sandboxCommandSupportsManagedProcessLiveObservation=true`
      - 表示 native backend 已支持 provider event stream 到 host-managed snapshot 的 live attach / live consume
    - `sandboxCommandSupportsManagedProcessObservationCursorResume=false`
      - 表示还没有 provider-native cursor resume
    - `sandboxCommandSupportsManagedProcessObservationBackfill=false`
      - 表示还没有 provider-native backfill
  - 远端后台进程更细粒度的状态恢复与断点续读仍未完成

### 目标

让 `command_exec` 和 process family 与 `python_exec` 一样，支持 local / sandbox 双后端并存。

### 代码改动范围

- `runtime/.../CommandExecutor.kt`
- `runtime/.../ModeGate.kt`
- `runtime/.../AgentTooling.kt`
- `runtime/.../process/*`
- 新增 `runtime/.../sandbox/E2BCommandExecutionBackend.kt`

### 设计

- 引入 `CommandExecutionBackend`
- 引入统一 backend 选择逻辑，避免 Python 和 command 的 backend 判定分叉
- 远端后台任务与本地 `ManagedProcessSnapshot` 建立映射
- provider 输出统一成现有 `ExecutionResult` / process snapshot 模型
- 保持 `command_exec` / `Process*` 的 tool surface 不变，只替换云端 backend 的最后一跳
- foreground command 已从 `python-backed wrapper` 升级为 provider-native command execution 优先模式
- background command 已接入 provider-native start / terminate / durable reconnect 最小路径，并已补齐 provider handle / selector telemetry、host-managed observation cursor scaffold、provider observation scaffold，以及 durable seed consumed / before-live-attach recovery contract；下一步再升级为 provider-native cursor-based log reading / backfill

### 推荐实施拆解

1. 落地 `E2BNativeCommandExecutionBackend.executeForeground(...)`
   - 当前状态：已完成
   - 目标：让云端 `command_exec` 在满足前提时不再借道 `python_exec`
2. 落地 `E2BNativeManagedProcessControllerFactory`
   - 当前状态：已完成最小版
   - 目标：让 `ProcessStart/Terminate` 和 durable restore reconnect 基于 provider 原生命令句柄工作
3. 扩展 `AgentProcessRegistry`
   - 当前状态：已完成 typed durable model 第一版，剩余 provider-native cursor / backfill 深化
   - 已完成：
     - durable restore 后按 provider-native `Connect` 重挂运行中命令
     - `ProcessRead/Wait` 外显 reconnect recovery state
     - provider handle / reconnect selector scaffold
     - host-managed observation cursor scaffold
     - provider observation scaffold
     - `ManagedProcessSnapshot` 已新增：
        - `remoteHandle`
        - `observationState`
        - `reconnectState`
        - `deliveredObservationState`
     - 旧 metadata-only snapshot load 时会自动 normalize 成 typed state
     - E2B native managed controller 已把 typed state 真正写回 durable snapshot
     - `ProcessRead/Wait` 与 reconnect 路径当前优先读 typed state，metadata 继续保留兼容投影
     - `ProcessRead/Wait` 成功交付后会把最近一次已交付的 host observation boundary durable 写回，session tracker 丢失时会先用这组状态恢复增量边界
     - `deliveredObservationState` 现在也会顺带保留 provider observation boundary，为后续 provider-native cursor resume / backfill 建模预留 durable 字段
   - 目标：从“typed durable state 已接上”继续推进到 provider-native `sessionId`、provider log cursor / backfill / resume，以及更细粒度的 durable observation tracker
4. 加入 capability gating
   - 当前状态：已完成第一版
   - 目标：继续细化 foreground/background 是否允许 wrapper fallback
5. 最后补流式日志与重连恢复
   - 目标：把当前“一次性收口”升级为可续读、可恢复的远端进程视图

### 验收

- `command_exec` 可以明确选择 local 或 sandbox
- `command_exec` 可在 sandbox 前台执行命令
- `ProcessStart` 可启动 sandbox 后台命令
- `ProcessRead/Wait/Terminate` 可操作该命令
- UI/transcript 不需要理解 provider 细节，也能展示状态
- provider-native 路径落地后，云端 `command_exec` 不再生成 Python wrapper
- provider-native 路径落地后，云端 `ProcessStart` 返回的状态基于真实 provider handle，而不是 Python 包装任务

### 当前验证

- `:app:testDebugUnitTest --tests "com.opencray.app.PythonBackedCommandExecutionTest"` 通过
- `:app:testDebugUnitTest --tests "com.opencray.app.E2BSandboxCommandExecutionBackendFactoryTest"` 已补充为本轮新增覆盖目标
- `:app:testDebugUnitTest --tests "com.opencray.app.RoutingCommandExecutorTest" --tests "com.opencray.app.RoutingManagedProcessControllerFactoryTest"` 通过
- `:app:testDebugUnitTest --tests "com.opencray.app.E2BEnvdNativeCommandExecutionTest"` 通过
- `:runtime:testDebugUnitTest --tests "com.opencray.runtime.process.FileBackedAgentProcessRegistryTest" --tests "com.opencray.runtime.AgentManagedProcessToolTest" --tests "com.opencray.runtime.context.RecentToolObservationSupportTest"` 通过
- 当前新增覆盖：
  - Python-backed sandbox command backend capability metadata
  - provider-native 优先 / wrapper fallback 的选择 metadata
  - envd Connect 最小协议的 protobuf 编解码与前台命令 happy path
  - envd Connect 后台命令 `Start` + `SendSignal` happy path
  - envd Connect 后台命令 durable restore 后的 `Connect` live reattach happy path
  - reconnect transport failure 后保留 `running` + retryable metadata
  - registry 在 retry backoff 到期后会于下一次 `read/wait` 再次尝试 reconnect
  - reconnect 恢复模式 / recovery state / output gap risk / seeded bytes metadata
  - 缺少 `remoteWorkspaceRoot` 时只回退到 E2B wrapper，不会触碰本地
  - native foreground 成功路径的 provider-native completion metadata
  - native foreground 尝试后因 transport / HTTP 失败而回退 wrapper 时的 attempt telemetry
  - native background 成功路径的 provider-native pid / termination metadata
  - native background 缺少 `remoteWorkspaceRoot` 时回退到 E2B wrapper
  - `FileBackedAgentProcessRegistry` 恢复运行中 snapshot 时优先走 reconnect，而不是直接标记 interrupted
  - `ProcessRead/Wait` 对 reconnect metadata、retryable reconnect 状态和 observation warning 的正文外显
  - `command_exec` 结果里的 backend capability 外显
  - 命令型 `ProcessStart` 结果里的 backend capability 外显
  - 路由层在抽象接入后仍保持本地 / 云端分流边界

## 建议实施顺序

关于“先画 UI 还是先做能力”，本计划建议：

1. 先做能力
   - 先把配置模型、凭据存储、runtime plumbing、backend selection 打通
2. 再做最小 UI
   - 把已经存在的真实字段暴露出来
3. 最后再做 UI 打磨
   - 状态解释、错误提示、成本提示、生命周期可视化

原因很直接：

- 如果先画完整 UI，字段和交互很容易在能力落地时返工。
- 当前更需要先确定：
  - backend selection contract
  - session lifecycle
  - API key 如何安全存储
  - runtime 如何拿到 sandbox config
- 但也不应该等所有 backend 都完成后才补 UI，所以推荐“能力先行，最小 UI 同步跟进”。

## Phase 3: workspace sync、artifact、preview

### 当前状态

截至 2026-03-31，本阶段已经从“最小可用”推进到“第一批深化能力已落地”，但仍未完成全量目标：

- 已完成：
  - `python_exec` 在 E2B 执行后把远端 changed files 下载回本地 workspace
  - 当前下载范围被限制在 workspace 内，且会跳过 `.opencray`、`.git`、缓存目录和虚拟环境目录
  - 成功回传的文件已接到现有 attachment artifact 元数据链路
  - 已新增 `sandbox_preview_open`
    - 当前会为现有 E2B sandbox session 生成 preview URL
    - 当前会对该 URL 执行短超时 HEAD 探测，并把状态写入 tool result metadata
    - 当前可区分 `ready`、`reachable`、`unreachable`
    - 如果当前 sticky session 只有一个已发现的候选端口，当前允许省略 `port`
    - 如果当前 sticky session 有多个候选端口，当前会直接失败并返回候选值，避免误开错服务
    - 当前不负责启动服务
  - 已补齐最小可用的 preview 自动端口发现
    - 当前会在 E2B `python_exec` 的 stdout/stderr 收口阶段抽取候选端口
    - 当前识别模式只覆盖保守子集：
      - `localhost:3000`
      - `127.0.0.1:3000`
      - `0.0.0.0:3000`
      - `listening on port 3000`
      - `started server on 3000`
    - 候选端口会写入 sticky session snapshot，并在执行 metadata 里回传 `sandboxPreviewCandidatePorts`
  - 已新增 `sandbox_session_close`
    - 当前会显式终止当前 workspace 对应的可复用 E2B sandbox session
    - 当前会在成功关闭后同步清掉本地 resume snapshot，保证下一次云端执行可以从 fresh sandbox 启动
    - 当前如果没有可复用 session，会返回 no-op 风格的成功结果
    - 当前如果同一 sandbox 里还有 request 在运行，会直接失败并返回 `blockingRequestId`
  - 已新增 `sandbox_session_info`
    - 当前会回传当前 workspace 的 reusable sandbox session 是否存在
    - 当前会区分 `active_memory`、`persisted`、`active_memory_and_persisted`
    - 当前会回传 preview candidate ports 和 running request ids，便于后续 UI 做 session 生命周期可视化
  - 已补齐最小可用的 session 状态卡片 UI
    - 当前会把最新一次 `sandbox_session_info` 的 tool result 映射到 Chat 主流的 run trace 卡片
    - 当前也会把同一份 session 状态映射到 `Run inspector` 顶部的详细区块
    - 卡片只在显式云端模式显示，本地模式隐藏
    - 当前新增了自动刷新链路
      - 触发方式不是伪造一条用户消息，而是由 host bridge 直接提交一个预批准的 `TOOL_CALL`
      - tool payload 固定为 `sandbox_session_info`
      - run metadata 会带上 `submissionSource=host_ui_tool_action` 和 `preapprovedToolName=sandbox_session_info`
      - 这样可以复用现有 run trace / runtime event 管线，同时不污染正式聊天 transcript
    - 当前自动刷新策略是事件驱动，不是轮询
      - 只在显式云端模式下启用
      - 只会在检测到最近一次云端执行工具结果之后，自动补一次 `sandbox_session_info`
      - 如果最近已经存在更新后的 `sandbox_session_info` 结果，则不会重复补发
      - 已补齐 Flutter 回归测试，覆盖“已有更新后的 `sandbox_session_info` 时不重复刷新”和“刷新失败后不对同一 anchor 死循环重试”
      - 本地模式不会触发这条 host action，也不会显示 session 卡片
  - 已补齐最小可用的 preview 卡片 UI
    - 当前会把最新一次 `sandbox_preview_open` 的 tool result 映射到 Chat 主流的 run trace 卡片
    - 当前也会把同一份 preview 数据映射到 `Run inspector` 顶部的详细区块
    - 卡片只在显式云端模式显示，本地模式隐藏
    - 已支持 `Open` / `Copy URL`
  - 已补齐 preview 宿主内嵌渲染第一版
    - Chat 主流里的 preview 卡片现在会在动作按钮上方渲染内嵌 preview surface
    - `Run inspector` 顶部的 preview 详细区块同样会渲染内嵌 preview surface
    - 当前只在显式云端模式显示；本地模式不会显示，也不会解析 embed config
    - Flutter 侧当前使用 `webview_flutter`
    - embed config 不从 tool result metadata 直接取 token，而是通过 host bridge / loopback runtime 按需解析
    - 宿主会按当前 workspace 匹配活动或持久化 E2B session，并返回：
      - `previewUrl`
      - `providerId`
      - `headers`
      - `sessionMatched`
      - `accessTokenConfigured`
      - `unavailableReason`
    - 当前 E2B traffic token 只通过 embed config headers 下发，不会进 run trace metadata
    - 如果当前设备或测试环境不支持 WebView，UI 会回退到 unavailable placeholder，不会阻塞 `Open` / `Copy URL`
  - 已补齐 sticky session 下的执行前增量上传第一版
    - 当前 sticky session 会绑定稳定的远端 workspace 根目录
    - 当前会把上一次成功同步后的文件元数据落到 `.opencray/sandbox-sync/e2b-workspace-sync-state.json`
    - 当 sandbox 可复用且远端 workspace 根目录不变时，后续执行只会上传变更文件
    - 当前也会在用户脚本执行前，把“本地已删除、但上次同步里存在”的远端文件回放删除到 sandbox
    - 当前 metadata 会回传：
      - `workspaceUploadMode=full|incremental`
      - `workspaceUnchangedFiles`
      - `workspacePendingRemoteDeleteFiles`
  - 已补齐通用 artifact 归档与受控下载目录的第一版
    - 当前成功下载回本地的 changed files，会额外归档到 `.opencray/sandbox-downloads/<requestId>/...`
    - 当前 attachment artifact metadata 会优先指向归档路径
    - 当前 metadata 会回传：
      - `archivedArtifactFiles`
      - `archivedArtifactBytes`
      - `sandboxDownloadArchiveRoot`
  - 已补齐通用 artifact 归档保留 / 清理策略第一版
    - 当前默认只保留最近 `12` 个 request 归档目录
    - 当前默认归档总大小上限为 `64 MiB`
    - 超限时会优先删除更旧的 request 归档目录
    - 当前不会优先删除本次请求刚生成的归档目录
    - 当前 metadata 会回传：
      - `sandboxDownloadArchivePrunedDirectories`
      - `sandboxDownloadArchivePrunedBytes`
      - `sandboxDownloadArchiveRetainedDirectories`
      - `sandboxDownloadArchiveRetainedBytes`
  - 已补齐 preview / session lifecycle 管理的第一版
    - `sandbox_preview_open` 现在会把最近一次 preview URL、端口、path、probe 状态、HTTP 状态和打开时间写回 session snapshot
    - `sandbox_session_info` 现在会把上述字段连同 `remoteWorkspaceRoot` 一起回传给 tool result metadata
    - 这让 UI 后续可以基于同一条 session 状态源继续扩展“最近一次 preview 是否可用、何时打开、当前远端 workspace 在哪”这些视图
  - 已补齐远端删除回放第一版
    - 当前只作用于 sticky session 的 sandbox 侧 workspace
    - 执行方式不是额外调用不稳定的 provider 文件删除接口，而是在用户脚本执行前由 Python 前导脚本做受控删除
    - 当前不会反向删除本地文件，避免破坏本地事实来源
- 仍未完成：
  - 执行前增量上传的后续项
    - 内容 hash 回退
    - provider 级通用 sync planner
  - 通用 artifact 归档与受控下载目录的后续项
    - artifact 类型识别
    - 可配置归档保留/清理策略
    - 选择性下载与更细粒度落盘策略
  - 更完整的 preview / session 生命周期自动管理
    - 更完整 session 可视化
    - preview / session auto refresh 的可配置节流策略
    - provider 级 lifecycle policy 抽象
    - 如果后续发现 E2B 认证子资源在 WebView 中不继承 header，需要把当前 embed 实现升级为宿主 proxy
  - 远端删除回放的后续项
    - 更细粒度的目录删除策略
    - provider 级抽象
    - 更可解释的 telemetry / metadata

### 当前验证状态与阻塞

截至 2026-04-04，本阶段代码验证的真实状态是：

- 已完成：
  - `:app:compileDebugKotlin` 通过
  - `:app:testDebugUnitTest --tests "com.opencray.app.E2BSandboxSessionInfoServiceTest"` 通过
  - `:app:testDebugUnitTest --tests "com.opencray.app.E2BSandboxPreviewServiceTest"` 通过
  - `:app:testDebugUnitTest --tests "com.opencray.app.E2BSandboxPreviewEmbedConfigServiceTest" --tests "com.opencray.app.OpenCrayFlutterHostBridgeTest" --tests "com.opencray.app.OpenCrayLocalRuntimeServerTest"` 通过
  - `:runtime:testDebugUnitTest --tests "com.opencray.runtime.SandboxSessionInfoToolTest"` 通过
  - `:runtime:testDebugUnitTest --tests "com.opencray.runtime.process.FileBackedAgentProcessRegistryTest" --tests "com.opencray.runtime.AgentManagedProcessToolTest"` 通过
  - `:app:testDebugUnitTest --tests "com.opencray.app.E2BEnvdNativeCommandExecutionTest"` 通过
  - `dart analyze flutter_app` 通过
  - Flutter 定向 widget tests 通过：
    - `cloud mode shows sandbox preview card on the run trace`
    - `local mode hides sandbox preview card on the run trace`
    - `cloud mode shows sandbox preview inside the run inspector`
    - `local mode hides sandbox preview inside the run inspector`
    - `cloud mode shows sandbox session card on the run trace`
    - `cloud mode shows sandbox session inside the run inspector`
    - `cloud mode auto refreshes sandbox session info from lifecycle metadata`
    - `local mode does not auto refresh sandbox session info from lifecycle metadata`
  - Flutter bridge tests 通过：
    - `test/opencray_platform_bridge_test.dart`
    - `test/opencray_local_runtime_bridge_test.dart`
- `app` 侧本次新增/更新的单测已经补齐：
  - `E2BSandboxPreviewServiceTest`
  - `E2BSandboxSessionInfoServiceTest`
  - `E2BSandboxPreviewEmbedConfigServiceTest`
  - `OpenCrayFlutterHostBridgeTest`
  - `OpenCrayLocalRuntimeServerTest`
  - `E2BEnvdNativeCommandExecutionTest`
  - 其中已覆盖：
    - preview probe 观测时间 / source 持久化
    - session info 自动探活
    - stale / reclaimed lifecycle 判定
    - preview embed config 的 workspace/session 匹配与 token header 解析
    - host bridge / loopback runtime 的 preview embed config 暴露链路
- `runtime` / process 侧本次新增或更新的单测已经补齐：
  - `FileBackedAgentProcessRegistryTest`
  - `AgentManagedProcessToolTest`
  - 其中已覆盖：
    - metadata-only remote snapshot 在 load 时自动 normalize 成 typed remote state
    - metadata 稀疏时，durable reconnect 仍可通过 typed reconnect seed 建立首个 `ProcessRead` baseline
    - metadata 稀疏且 session tracker 为空时，durable delivered observation state 可以直接恢复首个 `ProcessRead` 的增量边界
    - `ProcessRead` 成功交付后，会把最新 host observation boundary 写回 registry，供后续 durable restore 使用
    - file-backed registry 在 live snapshot refresh 之后仍会保留并回放这组 delivered observation state
    - 这组 delivered observation state 现在还会一起持久化 provider observation boundary，并覆盖对应的 file-backed restore / projection 断言
    - retryable reconnect 在 durable normalize / persistence 收敛路径下，验证重点改为最终状态和 attempt metadata 自洽，而不是固定内部重连次数
- `app` 侧 native managed command 本轮还额外覆盖了：
  - start happy path 会把 typed remote state 写回 `ManagedProcessSnapshot`
  - reconnect 在 metadata 稀疏时，会优先读取 typed state，而不是强依赖旧 metadata key
- 仍需注意：
  - 当前 Windows 环境下，直接使用默认 `app/build` 路径跑 Android/Gradle 任务时，仍可能撞上中间产物文件锁
  - 本轮验收通过的方式，是显式设置 `GRADLE_USER_HOME=.gradle-user` 与 `ANDROID_USER_HOME=.android-user`，并把 Gradle build 输出切到独立目录后串行执行
  - Android/Gradle 在当前环境里仍会打印 `C:\\Users\\CodexSandboxOffline\\.android\\analytics.settings` 的 metrics 初始化警告，但本轮测试表明这条警告不阻塞用例通过
  - `flutter test test/chat_feature_screen_test.dart` 整包在当前环境下仍然偏慢，因此本轮只对直接受影响的 widget cases 做了定向回归

### 目标

把执行后端变成可实际使用的 coding environment，而不是只能跑一条命令。

### 必做

- 增量同步本地文件到 sandbox
- 结果文件回传本地
  - 当前状态：`python_exec` changed files 已回传，但还不是完整 artifact 管线
- 对 build/test/log/report 产物做 artifact 归档
- provider 支持 preview URL 时，把 URL 暴露给 runtime

### 不建议

- 不要把本地 `Read/Write/Edit` 直接切到远端
- 不要让 agent 同时随意读写两套 workspace

### 验收

- 一个典型 coding task 能在 sandbox 内完成：
  - 上传项目
  - 安装依赖
  - 运行测试
  - 下载报告

## Phase 4: 远端 MCP 桥接

### 目标

让 OpenCray 真正调用远端 MCP tool。

### 必做

- 实现 remote MCP client
- 支持：
  - `RemoteHttp`
  - `RemoteSse`
  - `LocalStdio`
- 设计 remote tool exposure model
- 为每个 remote tool 加 policy manifest
- 从 `mcp_list_servers` 升级到 “list + connect + discover + call”

### E2B 特别说明

E2B gateway 很适合作为 V2 的首个 MCP runtime 目标，因为它天然提供：

- gateway URL
- token
- server catalog
- custom servers

但必须控制暴露范围：

- 初期只白名单少量 server
- 例如：
  - `fetch`
  - `filesystem`
  - `git`
  - `github`
  - `exa`

### 验收

- OpenCray 能发现并调用至少一个远端 MCP tool
- tool call 有明确的 capability/policy metadata
- server trust、auth、manual enable 与现有 UI 对齐

## Phase 5: sandbox-native 能力

### 可选新增能力

- `runCode`/stateful Python execution
- snapshots / rollback / fork
- desktop / computer use
- preview URL attach
- 多 sandbox 并行分支执行

### 优先级建议

1. snapshots
2. preview
3. desktop
4. stateful code interpreter

## Phase 6: provider 泛化

### 目标

让 OpenCray 支持：

- `E2B`
- `Daytona`
- `Modal`

### 原则

- tool surface 不变
- provider 能力差异体现在 capability flags，不体现在模型参数爆炸

### 预期结果

- E2B：默认首选
- Daytona：多能力第二实现
- Modal：偏后端安全容器场景

## 需要修改的代码点清单

## 高优先级

- `runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/PythonExecRequest.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/PythonScriptRuntime.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/CommandExecutor.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/ModeGate.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/policy/ToolIntentModels.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/policy/ToolPolicyPipeline.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/policy/ToolPolicySupport.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/policy/ToolCapabilityClassifier.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/context/PromptAssembler.kt`

## 中优先级

- `mcp/src/main/kotlin/com/opencray/mcp/McpRuntimeSupport.kt`
- `mcp/src/main/kotlin/com/opencray/mcp/*`
- `core/src/main/kotlin/com/opencray/core/contracts/McpSpec.kt`
- `app/src/main/kotlin/com/opencray/app/facade/mcp/McpSettingsFacade.kt`
- MCP settings UI 对应的 strings / tests

## 新增建议目录

- `runtime/src/main/kotlin/com/opencray/runtime/sandbox/`
- `runtime/src/main/kotlin/com/opencray/runtime/mcp/bridge/`
- `runtime/src/test/kotlin/com/opencray/runtime/sandbox/`
- `runtime/src/test/kotlin/com/opencray/runtime/mcp/bridge/`

## 测试计划

## 单元测试

- `python_exec` local backend 与 E2B backend 注入一致性
- `command_exec` local/remote backend 结果 envelope 一致性
- backend 选择优先级：
  - global default
  - session override
  - single-call override
  - policy forced override
- sandbox session id / command id / process id 映射
- policy metadata 是否正确发出
- sync planner 是否只同步允许的路径
- artifact download 是否安全落盘

## 集成测试

- `python_exec` 远端执行 happy path
- `command_exec` 远端执行 happy path
- `ProcessStart` + `ProcessWait` + `ProcessTerminate`
- sandbox timeout / cancellation
- snapshot restore
- preview URL
- MCP gateway 至少一个 tool 的 discover + call

## 回归测试

- 现有 local runtime 不退化
- local 与 sandbox 并存时，不会互相污染 tool semantics
- `SAFE/AUTO/DEVELOPER` 模式语义不变化
- transcript / UI / approval 流程不被破坏

## 风险与防护

## 风险 1：本地与远端 workspace 漂移

防护：

- 明确本地 workspace 仍是 source of truth
- 远端仅作 execution mirror
- 采用显式 sync/upload/download，而不是双主写

## 风险 2：远端 MCP 工具权限爆炸

防护：

- 白名单暴露
- manifest 级 capability classification
- 每个 server 保留 trust/auth/manual enable

## 风险 3：移动端保存长期 API key

防护：

- MVP 才允许用户自带 key
- 生产形态优先改为服务端签发短期 token
- 所有 provider credential 必须走 secure credential ref

## 风险 4：成本失控

防护：

- sticky session 绑定上限
- 空闲自动清理
- 任务级 timeout
- provider quota 监控

## 风险 5：策略语义失真

防护：

- 所有远端执行必须仍走 `ToolPolicyPipeline`
- 不允许 handler 私下绕过 policy
- 把 remote sandbox 信息编码进统一 metadata

## 不该做的事

- 不要只改 `python_exec`，放着 `command_exec` 和进程工具不管。
- 不要先做 “全量远端 MCP tool 透传”。
- 不要把 provider 参数直接一股脑塞进模型可见工具参数。
- 不要把远端沙盒文件系统当本地 workspace 替身。
- 不要让远端 MCP tool 绕开现有 trust/auth/policy 体系。

## 最终建议

如果只允许做一轮高价值、低风险落地，推荐顺序是：

1. 抽象 sandbox execution provider，先接 `E2B`
2. 先打通 `python_exec`
3. 紧接着补齐 `command_exec` 和 process family
4. 做 workspace sync / artifact / preview
5. 最后再做 E2B MCP gateway

一句话概括：

- `python_exec` 不是不该改，而是要扩展成“本地 + 沙盒双后端统一入口”。
- 真正应该优先建设的是 “统一的、可切换的远端沙盒执行层”。
- E2B 最适合做 OpenCray 的第一阶段 provider。
- Daytona 适合做第二 provider 和能力对照。
- Modal 更适合后续偏基础设施型场景，而不是第一阶段主路径。

## 参考来源

以下结论基于 2026-03-25 当天核对的官方资料：

- E2B Documentation: https://e2b.dev/docs
- E2B Sandbox lifecycle: https://e2b.dev/docs/sandbox
- E2B Commands: https://e2b.dev/docs/commands
- E2B Commands background: https://e2b.dev/docs/commands/background
- E2B Filesystem: https://e2b.dev/docs/filesystem
- E2B Filesystem read/write: https://e2b.dev/docs/filesystem/read-write
- E2B Filesystem upload: https://e2b.dev/docs/filesystem/upload
- E2B Filesystem download: https://e2b.dev/docs/filesystem/download
- E2B Internet access: https://e2b.dev/docs/sandbox/internet-access
- E2B Connect to running sandbox: https://e2b.dev/docs/sandbox/connect
- E2B Snapshots: https://e2b.dev/docs/sandbox/snapshots
- E2B Secured access: https://e2b.dev/docs/sandbox/secured-access
- E2B API key: https://e2b.dev/docs/api-key
- E2B MCP overview: https://e2b.dev/docs/mcp
- E2B MCP quickstart: https://e2b.dev/docs/mcp/quickstart
- E2B MCP available servers: https://e2b.dev/docs/mcp/available-servers
- E2B MCP custom servers: https://e2b.dev/docs/mcp/custom-servers
- E2B Computer use: https://e2b.dev/docs/use-cases/computer-use
- Daytona Docs home: https://www.daytona.io/docs/
- Daytona Process and Code Execution: https://www.daytona.io/docs/en/process-code-execution/
- Daytona File System Operations: https://www.daytona.io/docs/en/file-system-operations/
- Daytona Preview: https://www.daytona.io/docs/en/preview/
- Daytona Computer Use: https://www.daytona.io/docs/en/computer-use/
- Daytona MCP Server: https://www.daytona.io/docs/en/mcp/
- Modal Sandboxes: https://modal.com/docs/guide/sandbox
- Modal Running commands in Sandboxes: https://modal.com/docs/guide/sandbox-spawn
- Modal Networking and security: https://modal.com/docs/guide/sandbox-networking
- Modal Filesystem access: https://modal.com/docs/guide/sandbox-files
- Modal Snapshots: https://modal.com/docs/guide/sandbox-memory-snapshots
