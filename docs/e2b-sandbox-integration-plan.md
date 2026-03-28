# OpenCray 沙盒能力接入计划

Last updated: 2026-03-27

## 当前实现状态

截至 2026-03-27，`python_exec` 这条链路已经从“仅有设置和路由骨架”推进到了“本地 / E2B 双后端可切换的可运行状态”，且当前实现边界已经固定：

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
- `python_exec` 的 tool name 和参数目前保持不变
- 已把 E2B runtime 注入现有 owner/runtime 装配路径
  - 本地 `P4aPythonRuntime` 保持不变
  - 云端 backend 已接入到同一条 `python_exec` 路由
- 已把 `command_exec` 接入本地 / E2B 双后端路由
  - 显式 `local` 只走本地 `CommandExecutor`
  - 显式 `sandbox` 只走 E2B
  - 当前 E2B 命令执行实现为 `python-backed wrapper`
  - wrapper 会把本地 `workingDirectory` 映射到远端 workspace 路径，避免把宿主机绝对路径错误带进云端 subprocess
- 已把命令型 `ProcessStart` 接入本地 / E2B 双后端路由
  - `ProcessStart(script_path=...)` 继续复用已经接好的 `python_exec` runtime 路由
  - `ProcessStart(command=...)` 在 E2B 下走 `python-backed managed command controller`
  - `ProcessRead/Wait/Terminate` 继续走现有 registry/tool surface，不改工具名与参数
- 已补齐未来 sandbox-native tools 的模型可见性规则
  - 约定所有沙盒原生能力工具统一使用 `sandbox_` 前缀
  - 本地模式下不向模型暴露 `sandbox_*` tool definitions
  - 显式云端模式且 E2B 可用时才向模型暴露这些 definitions
  - 过滤发生在 dispatcher/tool definition 层，不靠 prompt 文案硬编码隐藏
- 已补齐最小可用的 preview tool
  - 新增 `sandbox_preview_open`
  - 当前会基于活动中的 E2B sandbox session 和指定端口拼出 preview URL，并对该 URL 做一次短超时 reachability probe
  - probe 结果会区分 `ready`、`reachable`、`unreachable`
  - 仅在显式云端模式下向模型暴露
- 已补齐最小可用的 preview UI 宿主
  - Chat 主流里的 run trace 气泡现在可以把最新一次 `sandbox_preview_open` 结果映射成 preview 卡片
  - `Run inspector` 顶部滚动内容现在也会显示同一条 preview 的详细区块
  - 卡片当前展示标题、URL、provider / port / path / HTTP 状态、probe 状态
  - 卡片提供 `Open` 与 `Copy URL` 两个动作
  - 当前只在显式 `Run in cloud` 时显示；切回 `Run locally` 后不会渲染
  - preview service 现在会优先读取运行时内存里的活动 sticky sandbox session，再回落到持久化 session store
  - 这修复了 `sessionMode=sticky` 且 `autoResume=false` 时 preview 能力拿不到当前活动会话的问题

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
- 远端删除当前不会直接应用到本地文件系统
  - 只会通过 metadata 暴露 `remoteDeletedFiles` / `skippedRemoteDeletes`
- `.opencray`、`.git`、`node_modules`、`venv`、`__pycache__` 等内部或缓存目录不会参与回传下载
- `command_exec` 当前不直接调用独立的 E2B command API，而是复用已接好的 E2B code-interpreter，在远端通过 Python wrapper 运行 shell command
- E2B 下的命令型 `ProcessStart` 当前也是 wrapper-based
  - 好处是复用了现有 `python_exec` 的取消与路由能力
  - 当前限制是运行中的增量 stdout/stderr 不是流式可读，通常会在 wrapper 完成后一次性落到快照里

当前仍未完成的部分：

- 还没有做完整 preview 生命周期管理 / artifact download / snapshot / MCP gateway
- 还没有做执行前增量上传、远端删除回放、artifact 归档、preview 端口发现与完整 preview 暴露
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
- `./gradlew.bat :runtime:testDebugUnitTest --tests=com.opencray.runtime.SandboxPreviewToolTest --tests=com.opencray.runtime.ExecutionAttachmentArtifactSummaryTest --tests=com.opencray.runtime.policy.ToolCapabilityClassifierTest`
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

截至 2026-03-27，本阶段也已经进入“可运行但仍偏保守”的状态：

- 已完成：
  - `command_exec` 已支持 `local / auto / sandbox` 路由
  - E2B 下的 `command_exec` 当前通过 `python-backed wrapper` 执行
  - wrapper 会把本地 `workingDirectory` 映射到远端 workspace
  - 命令型 `ProcessStart` 已支持本地 / 云端双后端分流
  - `ProcessTerminate` 在云端模式下会通过 `CancellablePythonScriptRuntime` 触发终止
- 仍未完成：
  - 直接接入 provider 原生命令 API / background session API
  - 运行中 stdout/stderr 的增量流式读取
  - 远端后台进程更细粒度的状态恢复与重连

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

### 验收

- `command_exec` 可以明确选择 local 或 sandbox
- `command_exec` 可在 sandbox 前台执行命令
- `ProcessStart` 可启动 sandbox 后台命令
- `ProcessRead/Wait/Terminate` 可操作该命令
- UI/transcript 不需要理解 provider 细节，也能展示状态

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

截至 2026-03-27，本阶段已经开始落地，但仍只完成了最小可用子集：

- 已完成：
  - `python_exec` 在 E2B 执行后把远端 changed files 下载回本地 workspace
  - 当前下载范围被限制在 workspace 内，且会跳过 `.opencray`、`.git`、缓存目录和虚拟环境目录
  - 成功回传的文件已接到现有 attachment artifact 元数据链路
  - 已新增 `sandbox_preview_open`
    - 当前会为现有 E2B sandbox session 的指定端口生成 preview URL
    - 当前会对该 URL 执行短超时 HEAD 探测，并把状态写入 tool result metadata
    - 当前可区分 `ready`、`reachable`、`unreachable`
    - 不负责启动服务，也不负责自动端口发现
  - 已补齐最小可用的 preview 卡片 UI
    - 当前会把最新一次 `sandbox_preview_open` 的 tool result 映射到 Chat 主流的 run trace 卡片
    - 当前也会把同一份 preview 数据映射到 `Run inspector` 顶部的详细区块
    - 卡片只在显式云端模式显示，本地模式隐藏
    - 已支持 `Open` / `Copy URL`
  - 远端删除不会直接删除本地文件，而是只写入 metadata，避免误删本地事实来源
- 仍未完成：
  - 执行前增量上传
  - 通用 artifact 归档与受控下载目录
  - preview 自动端口发现、preview 生命周期管理、preview 宿主内嵌渲染与完整 session 可视化
  - 远端删除回放策略

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
