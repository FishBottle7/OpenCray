# MCP 工具桥实施计划（2026-09-08）

## 一、定位与背景

本计划是 `docs/agent-system-ability-expansion-plan-2026-09-07.md` 中"MCP 工具桥（远期
独立立项）"的立项与实施文档，技术路线继承 `docs/e2b-sandbox-integration-plan.md`
"5. MCP runtime"一节（V1.5 `McpConnectionManager` + V2 动态 MCP 工具代理）的设计。

OpenCray 当前 MCP 状态为 **exposure-only**：

- 注册表/信任态/凭证引用完整（`mcp/McpRegistry.kt`、`core/.../McpSpec.kt`），
  信任收敛、manual-enable 同意、credentialRef-only 持久化均已落地并有测试。
- 设置链路完整（Flutter MCP 页 → 桥 → `LocalMcpSettingsFacade` → 持久注册表）。
- 唯一 agent 工具 `mcp_list_servers` 只读暴露报告，`McpRuntimeSupport
  .REMOTE_TOOL_BRIDGE_AVAILABLE = false` 写死；无 JSON-RPC client、无连接管理、
  无 tools/call 桥、无动态工具发现。
- E6xxx 错误段已改定义为系统能力，MCP 暂无保留错误段（本计划阶段二处理）。

缺口与 e2b 计划结论一致：缺的是 MCP client 连接管理器、动态 tool discovery、
tool call 代理、远端工具的策略分类与审批。

## 二、非目标

- 不做本地 stdio server 进程拉起（`LocalStdio` 合同保留，实现列为远期；Android
  端 stdio 双管道进程管理依赖强后台运行时方案，独立立项）。
- 不做 MCP server 角色（OpenCray 只做 client）。
- 不做 SSE 长连接传输（`RemoteSse` 合同保留；阶段一先做 Streamable HTTP 单工，
  2025-11-05 协议允许 client 只用 request/response 模式）。
- 不批量代理 E2B 200+ server 目录，不做无策略清单的透传（e2b 计划明确警示）。
- 不在本计划内做"添加 MCP 服务器"UI（chat-led 原型需求第 11 节之外的 add-server
  表单与桥方法另行排期，见 `docs/chat-led-ui-prototype-requirements.md` L425/L462）。

## 三、技术选型

- **传输栈**：`java.net.HttpURLConnection`（与 `OpenAiCompatibleLiteLlmProviderClient`
  等现有 HTTP 消费者一致；不引入 OkHttp/Ktor 新依赖）。
- **JSON**：`kotlinx.serialization`（mcp 模块已有依赖）。
- **协议版本**：MCP `2025-11-05`（Streamable HTTP），jsonrpc 2.0 信封。
- **连接模型**：同步 request/response + 每次请求带 `Mcp-Session-Id` 会话头；先
  `initialize` → `notifications/initialized` → `tools/list`，必要时按
  `Mcp-Protocol-Version` 头协商版本。请求在调用线程执行（dispatcher 工具调用已
  在工作线程池中），管理器自身无协程、无线程安全假设之外的并发要求。

## 四、分阶段实施

### 阶段一：协议客户端骨架（本次落地）

目标：mcp 模块内形成可测的 MCP client 协议层，仍不接入 agent 工具面——
`REMOTE_TOOL_BRIDGE_AVAILABLE` 保持 `false`，`mcp_list_servers` 行为不变。

新增文件（`mcp/src/main/kotlin/com/opencray/mcp/`）：

| 文件 | 职责 |
|---|---|
| `McpJsonRpc.kt` | JSON-RPC 2.0 信封模型（request/response/notification）与错误码常量（`-32700`…`-32603`） |
| `McpProtocol.kt` | MCP 语义层编解码：`initialize` / `tools/list` / `tools/call` 的参数与结果模型、`McpToolDescriptor`（name/description/inputSchema）、content 块（text/image/resource） |
| `McpHttpException.kt` | HTTP 层失败统一异常：`McpHttpException(statusCode, message)`，从 4xx/5xx/超时/IO 归一 |
| `McpHttpTransport.kt` | Streamable HTTP 传输：单次 POST → JSON 响应或 SSE `content-type: text/event-stream` 内首条 `data:` 帧解出（兼容 server 单工模式）；`Mcp-Session-Id` 与 `Mcp-Protocol-Version` 头处理；认证头从 `CredentialRef` 经解析器注入 |
| `McpConnectionManager.kt` | 连接生命周期：`connect()`（initialize + initialized 通知）→ `listTools()` → `callTool()` → `close()`；凭据缺失（auth MISSING/ERROR）在 connect 前置失败；信任态非 ENABLED 拒绝连接 |
| `McpHttpEndpoint.kt` | 可注入端点接口（`postJson(url, headers, body, timeoutMs): McpHttpResult`）+ JVM 默认 `HttpURLConnection` 实现，测试用 lambda 注入假端点 |

对齐项目规约的要点：

- 传输描述符的安全形：`McpClientTransportDescriptor.RemoteHttp` 只带 `headerNames`
  不带值——连接管理器从注册表记录 + 凭据解析器取真实值，**不**在描述符上直接执行。
- 凭据只经 `CredentialRef` 引用流转；明文值仅出现在请求头注入点，不落盘、不进日志、
  不进暴露报告。
- `McpConnectionManager` 为纯 Kotlin（无 Android Context），与 `McpRegistry` 同型，
  经依赖注入接入 app 层。
- 阶段一不新增任何 `errorCode`（无用户可见失败面）；现有 E6 段不动。

阶段一验收：

- `:mcp:test` 新增编解码、连接管理器、传输测试全绿（假端点注入，不发真实网络）。
- 现有 `McpManagerTest` / `OpenCrayToolDispatcherMcpTest` 不变。
- `.\gradlew.bat test` 全量通过。

### 阶段二：工具面接入（下一步，待用户确认后开工）

- `McpRuntimeSupport.REMOTE_TOOL_BRIDGE_AVAILABLE = true`，
  `BRIDGE_STATUS_EXPOSURE_ONLY` 改为 `bridge_ready`；`mcp_list_servers` 输出追加
  每服务器已发现工具数与连接摘要。
- 新增只读工具 `mcp_list_tools`（列出已发现远端工具，DISCOVERY 类）。
- 动态代理工具 `mcp__<server_id>__<tool_name>`：
  - `ToolCapabilityClassifier` 新增 `MCP_TOOL` 策略类（默认按远端工具不可信，
    SAFE → ASK 审批档，AUTO/DEVELOPER → ALLOW——**不**默认当作只读）。
  - `policy/ModePolicy.kt` 矩阵补 `MCP_TOOL` 档；`ToolIntentModels` 新增
    `McpToolCallIntent`（serverId/toolName/参数摘要）。
  - 工具目录动态合入：已启用服务器在会话启动/工具刷新时 `tools/list`，schema 归一
    后进 `AgentToolCatalog`。
- 错误段：E6xxx 已被系统能力占用，MCP 错误登记到 E5 段之后的空段或新增段——
  在阶段二开工时与用户确认选段（候选：复用 E0 策略段 + 新增 E2x 段不合适；倾向
  新增 `MCP_TOOL_FAILED` 等 3-5 个码登记进未占用的 E6 空位之外的新段，需定稿）。
- 连接生命周期接入 `InProcessOpenCrayRuntimeOwner`（`mcpReportProvider` 旁新增
  `mcpToolBridgeProvider`），与强后台/重启恢复语义对齐。

### 阶段三：设置与发现闭环（远期）

- add-server 桥方法 + 设置表单（chat-led 原型 L425/L462 后端待办）。
- 凭据录入 UI（vault 保存，凭据引用回写注册表）。
- SSE 传输、LocalStdio 进程传输、E2B MCP gateway provider。

## 五、测试策略

- 编解码测试：信封 round-trip、错误响应解析、MCP content 块解析、畸形 JSON 拒绝。
- 连接管理器测试：假端点注入——initialize 握手、工具发现、调用、关闭、认证缺失
  前置失败、信任态拒绝、超时归一 `McpHttpException`。
- 传输测试：SSE 单帧解出、session 头回传、4xx/5xx 归一、凭据头注入。
- 现有测试不变式：`RuntimeSupport_isExplicitlyExposureOnly` 在阶段一保持通过
  （阶段二翻转时同步改断言）。

## 六、风险与对策

- **协议版本漂移**：MCP 规范迭代快；阶段一锁 2025-11-05，协商头带
  `Mcp-Protocol-Version`，编解码层集中在 `McpProtocol.kt` 便于升级。
- **远端工具不可信**：阶段二策略档默认 ASK，schema 与实际行为不符的风险靠审批
  与 intent 审计兜底；绝不默认只读。
- **凭据泄漏**：明文只在传输注入点出现；报告/日志/持久层都只存引用。
- **Android 后台网络**：强后台运行时已保证网络执行环境，阶段二接入时验证。

## 七、状态

- 阶段一（协议客户端骨架）完成（2026-09-08）：`McpJsonRpc`（信封 + 错误码常量）、
  `McpProtocol`（initialize/tools 列表与调用模型 + content 块）、`McpHttpException`、
  `McpHttpTransport`（Streamable HTTP，单 POST 单响应，SSE 首帧解出，会话/协议头，
  凭据头注入）、`McpHttpEndpoint`（可注入端点 + HttpURLConnection 默认实现）、
  `McpConnectionManager`（连接生命周期：信任态/认证前置校验、initialize 握手、
  工具发现、调用、关闭）。工具面未动：`REMOTE_TOOL_BRIDGE_AVAILABLE` 仍为
  `false`，`mcp_list_servers` 行为与元数据不变。新增 38 个 `:mcp:test` 用例
  （编解码 8、协议 13、管理器 8、传输 9），模块 42 用例全绿；全量
  `.\gradlew.bat test` 912 例仅 1 例既有偶发失败（`OpenCrayAgentRuntimeSubAgentTest`
  子代理审批续跑，单独与整模块重跑均通过，与 mcp 改动无关）。
- 待做：阶段二工具面接入（动态 `mcp__<server>__<tool>` 代理、`MCP_TOOL` 策略类与
  intent 模型、E5/E6 之外的错误段定稿——选段需用户确认）；阶段三 add-server UI 与
  凭据录入、SSE/stdio 传输、E2B gateway。
