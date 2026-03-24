# Agent Native Tool Calling Protocol Plan

更新时间：2026-03-24

配套实施设计：

- [OpenAI Responses 与宿主 WebSearch 并存实施设计](./openai-responses-web-search-implementation-plan.md)

## 当前进度

截至 2026-03-23，本计划已落地到下面这一步：

- gateway 请求模型已支持 `messages[]` 与 `tools[]`
- OpenAI 兼容与 Anthropic 分支都已接入原生 tool calling request/response 解析
- runtime 已优先消费 provider 返回的结构化 completion，只把文本 JSON 协议当 fallback
- prompt assembler 已改成按运行态条件输出协议说明：
  - `native tool calling 可用且未降级` -> 不再注入 legacy JSON fallback 指令
  - `native tool calling 不可用` 或 `本 run 已降级` -> 才注入 JSON fallback 形状
- runtime 已引入按 run 维度的 fallback 降级状态：
  - 首轮走 native-only
  - 只有当 provider 实际回到文本 JSON 路径，或发生协议恢复时，后续 turn 才重新打开 legacy JSON fallback
- runtime transcript / prompt resume 已开始保存结构化 `tool_call` / `tool_result` 元数据
- tool call id 已能在 `provider -> runtime -> approval/resume -> 下一跳 gateway request` 之间贯通
- managed process / Bash 工具族现在会把 `exitCode/stdout/stderr/errorCode/errorMessage` 直接写进 `AgentToolResult`，rich tool result 不再只藏在 observation 文本与 metadata 里
- provider 结构化 completion 已明确拆分为：
  - `toolCalls`
  - `finalText`
  - `progressText`
  - `reasoningText`
  - `rawText`
- OpenAI / Anthropic provider 侧已把“公开 commentary/progress”与“内部 reasoning/thinking”分离解析
- reasoning-only 响应即使最后判定成 `PROVIDER_EMPTY_RESPONSE`，也会保留结构化 completion，并带出 reasoning 观测元数据
- host durable replay 已优先按 `RuntimeConversationMessage.kind` 与结构化字段回放，不再只依赖 `tool_call ` / `tool_result ` / `progress ` 前缀
- 新生成的 `tool_call / tool_result / progress / supplement / subagent` transcript 已统一落成 canonical JSON payload
- transcript window / pruning / compaction / recent observation / memory flush / session transcript normalization 这些 common 层已统一走 shared replay helper，优先消费 canonical JSON payload
- runtime 在重建 provider 下一跳 `tool_result` message 时，已改为回传 canonical 工具输出内容，不再把整个 replay envelope 原样塞回 provider
- `MultiEdit.edits` 与 `TodoWrite.todos` 已从薄 `object[]` 升级为展开字段的 JSON schema，strict readiness 的 schema fidelity 基线已补齐
- host replay 侧已补上独立 replay helper，除了 `kind + canonical JSON` 之外，也能从 plain JSON durable transcript 中推断 `tool_call / tool_result / progress / supplement / subagent` 事件类型
- runtime / app 两侧旧的 replay 前缀读取分支与 `content_preview` 兜底已从主实现中移除，开发期按 canonical message-first 收口
- app runtime factory 已把 provider `protocol` / native tool capability 元数据下发给 runtime，用来判断首轮是否 native-only，而不是按 provider 品牌字符串硬编码
- settings store 已引入按 `(protocol, baseUrl, model)` 路由指纹持久化的 agent capability cache
- 设置页 `validate()` 已从单次文本 ping 升级为多阶段 agent capability probe：
  - 文本连通性
  - native tool calling round-trip
  - `tool_choice + parallel_tool_calls=false` 控制面探测
  - `strict=true` 传输探测
- 设置页 probe 结果现在会回写 route capability cache：
  - native tool calling 不可验证时，显式标记该 route 为 `native unsupported`
  - native tool calling 可用时，再细分记录 `toolChoiceSupported / parallelToolCallsSupported / strictToolSchemaSupported`
- runtime 已真正按 capability metadata 透传 `LiteLlmToolDefinition.strict`
- host / Flutter bridge 返回的 `llmConfig` 与 `validateLlmConfig` payload 已带出 `agentCapability`

当前仍未完成的关键尾项：

- 与正在进行中的 runtime/UI 拆分合并时，需要重新过一遍 `OpenCrayHostRuntime`、`OpenCrayRuntimeServiceBridge`、`OpenCrayRuntimeServiceHost` 一带的集成回归
- 当前仍未做 OpenAI Responses-native continuation；这部分保持独立方言方案，由后续 `openai_responses` 线路承接
- 还没有跑完整仓库回归；目前完成的是 runtime/app 的定向 JVM 测试，且 Kotlin daemon 在当前环境下会反复退回 in-process fallback
- strict 现在已支持按 capability 受控下发，但仍未默认全量启用；后续应继续扩大 schema fidelity 覆盖面，再逐 route 打开
- LiteLLM backend 若继续保留，仍要补同一套 capability probe / regression gate；当前落地的是 OpenCray 自己的 provider-neutral 状态机

## 1. 背景

当前 Agent 聊天链路的工具调用主要依赖“文本协议”：

- runtime 在 prompt 中要求模型输出一段 JSON 文本
- provider client 将 completion 当成普通文本读取
- runtime 再把文本解析成 `type=tool_call` / `type=final`

这条链路在 provider、模型、兼容层、自定义网关之间都很脆弱。只要任一环节改成：

- 普通 prose
- reasoning 文本
- 空 `content`
- 非当前适配层支持的 block 结构
- 原生 tool calling 结果但未被正确解析

就会出现：

- token 已消耗，但工具根本没有执行
- 工具执行了，但第二跳最终回复丢失
- 宿主只能显示空答复、内部 payload 隐藏文案、或 provider failure

这不是 `TodoWrite` 单点问题，而是整个 LLM 协议边界的问题。

## 2. 本地实现调查结论

### 2.1 当前 gateway 契约已经支持 `messages[]` 与 `tools[]`，但仍缺 provider 级硬控制面

当前 `LiteLlmGatewayRequest` 已经具备：

- `prompt`
- `systemPrompt`
- `messages`
- `tools`
- `metadata`
- `authHeaders`

这意味着 OpenCray 已经不再是“只能发单段文本 prompt”的阶段。

但目前 gateway 契约仍然缺少关键 provider 控制面字段：

- `toolChoice`
- `parallelToolCalls`
- `previousResponseId` / `continuationToken`
- `responseApiPreferred` 或同类请求模式字段

代码位置：

- [llm/src/main/kotlin/com/opencray/llm/LiteLlmGateway.kt](../llm/src/main/kotlin/com/opencray/llm/LiteLlmGateway.kt)
- [runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt](../runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt)

这说明当前主缺口已经不是“没有 message/tool request 结构”，而是“没有把 provider 硬控制能力提升成统一契约”。

### 2.2 provider client 目前只有两种传输协议枚举

`LlmProviderProtocols` 当前只支持：

- `openai`
- `anthropic`

并且 `custom provider` 也是保存这两个值之一。

代码位置：

- [app/src/main/kotlin/com/opencray/app/LlmProviderRequestSupport.kt](../app/src/main/kotlin/com/opencray/app/LlmProviderRequestSupport.kt)
- [app/src/main/kotlin/com/opencray/app/LlmSettingsStore.kt](../app/src/main/kotlin/com/opencray/app/LlmSettingsStore.kt)
- [app/src/main/kotlin/com/opencray/app/facade/llm/LlmConfigFacade.kt](../app/src/main/kotlin/com/opencray/app/facade/llm/LlmConfigFacade.kt)

这意味着当前系统把“厂商/模型/能力”都粗暴折叠成了一个 `protocol` 字段。

### 2.3 provider client 已接入原生 tool calling，但 provider 边界仍然偏扁平

`OpenAiCompatibleLiteLlmProviderClient` 当前：

- OpenAI 兼容走 `POST /v1/chat/completions`
- Anthropic 走 `POST /v1/messages`
- OpenAI 分支只从 `message.content`、`message.tool_calls`、`reasoning_content` 等少数位置取值
- Anthropic 分支只拼接 `content[]` 中 `type=text` 的 block

代码位置：

- [app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt](../app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt)

当前已确认的限制：

1. OpenAI / Anthropic 请求虽已能发送 `tools`，但还没有统一的 provider 级 `tool_choice`、`parallel_tool_calls`、`previous_response_id` 控制面
2. provider 返回给下一跳的 `tool_result` 仍被压扁为 `toolCallId + toolName + content + isError`
3. runtime transcript 中已有 richer tool result payload，但 gateway/provider 边界没有 typed tool output 模型承接
4. provider 返回空 completion 仍可能落成 `PROVIDER_EMPTY_RESPONSE`，只是现在会额外保留 structured completion 与 reasoning 观测信息

另一个关键现实是：当前 OpenCray 并没有直接集成官方 LiteLLM SDK / LiteLLM Proxy，而是自己维护了一层名为 `LiteLlm*` 的 provider-neutral 契约，再由 provider client 直接打 OpenAI / Anthropic 方言 HTTP。

这意味着：

- 继续保留 `LiteLlm*` 这层抽象是合理的
- 但其语义真相层必须由 OpenCray 自己定义，不能寄托在上游某个网关的默认行为上

### 2.4 runtime 已是 native-first，但仍保留 legacy JSON fallback 与 protocol recovery

当前 prompt / runtime 的真实状态是：

- `native tool calling 可用且本 run 未降级` 时，prompt 不再注入 legacy JSON fallback 指令
- `native tool calling 不可用` 或 `本 run 已降级` 时，才重新打开 JSON fallback 协议
- runtime 会优先消费 provider 返回的 `toolCalls / finalText / progressText / reasoningText / rawText`
- 只有当 provider structured path 不可用，或文本里仍出现 legacy JSON 时，才走 fallback parser / protocol recovery

代码位置：

- [runtime/src/main/kotlin/com/opencray/runtime/context/PromptAssembler.kt](../runtime/src/main/kotlin/com/opencray/runtime/context/PromptAssembler.kt)
- [runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt](../runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt)

因此当前系统的真实故障面已经收缩成两类：

1. provider-native path 本身缺 typed tool result / 硬控制面，导致工具 loop 语义不够强
2. 某些 route 在运行中退回 legacy JSON fallback 后，后续轮转仍可能受文本协议脆弱性影响

### 2.5 设置页校验已升级为 agent capability probe，但 UI 侧仍未消费完整能力画像

当前 `validate()` 已按同一路由执行四段探测：

- `Reply with OK.`
- native tool calling probe
- `tool_choice + parallel_tool_calls=false` probe
- `strict=true` probe

它现在会验证：

- 文本 completion 是否可用
- provider 是否支持 native tool calling
- provider 是否会返回可解析的 native tool call
- `tool_choice` / `parallel_tool_calls=false` 是否至少在传输层可接受
- `strict=true` 是否至少在传输层可接受

代码位置：

- [app/src/main/kotlin/com/opencray/app/facade/llm/LlmConfigFacade.kt](../app/src/main/kotlin/com/opencray/app/facade/llm/LlmConfigFacade.kt)

验证结果还会持久化到 route capability cache，并在 runtime metadata 中生效。

当前剩余缺口是：

- 设置页 UI 还没有把 `agentCapability` 细项显式展示给用户
- probe 目前验证的是 OpenCray 自己的 runtime-managed native loop，不是 Responses-native continuation
- 某些 provider 可能只支持 native tools 主链路，不支持完整控制面；这类 route 仍需要 capability 粒度的渐进启用

### 2.6 当前测试已覆盖 native-first 主链路，但还没覆盖 typed tool result 与硬控制面

当前测试已经不只是旧的 OpenAI 文本桥接了，已经覆盖了：

- OpenAI 风格 `tool_calls`
- Anthropic `tool_use`
- structured `progressText`
- `reasoningText`
- native-first / degraded-fallback 的 runtime 行为

代码位置：

- [runtime/src/test/kotlin/com/opencray/runtime/OpenCrayAgentRuntimeTest.kt](../runtime/src/test/kotlin/com/opencray/runtime/OpenCrayAgentRuntimeTest.kt)
- [app/src/test/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClientTest.kt](../app/src/test/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClientTest.kt)

但当前仍缺少关键覆盖：

- richer `tool_result` 从 runtime transcript 到 provider request 的无损 round-trip
- `tool_choice`
- `parallel_tool_calls`
- `previous_response_id` / continuation 类控制面
- capability probe 成功/失败路径
- `native unsupported -> text fallback`
- “第一跳工具已执行，第二跳最终答复为空” 的端到端收敛

这意味着现有测试已经能证明 native-first 主路径落地，但还不足以证明“跨方言 + 跨兼容层 + 带控制面”的稳定工具调用能力。

### 2.7 与 Codex 对照时，当前最大缺口仍是 Responses-native continuation

如果拿 OpenCray 和 Codex / Responses API 的工具循环做对照，当前最大的协议差距仍然是：

- provider client 只打 `/v1/chat/completions` 与 `/v1/messages`
- `LiteLlmGatewayRequest` 里没有 `response_id` / `previous_response_id`
- runtime 仍要自己重放 transcript，而不是沿用 provider 端 response lineage

代码位置：

- [app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt](../app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt)
- [llm/src/main/kotlin/com/opencray/llm/LiteLlmGateway.kt](../llm/src/main/kotlin/com/opencray/llm/LiteLlmGateway.kt)

这不是说当前 native tool loop 不可用，而是说：

- 当前更接近“OpenCray 自己维护会话状态的 native loop”
- 还不是“Responses-native 的 provider lineage loop”

如果未来要向 Codex 这一类工作流继续靠拢，`openai_responses` 必须作为独立方言显式建模，而不能只当成 `openai_chat_completions` 的小补丁。

### 2.8 `strict` 已接入 capability gating，但 schema fidelity 仍需继续扩展

当前本地代码已补上这两步里的前一半：

1. runtime 已能按 route capability 把 `toolSchemaStrict=true` 透传到 `LiteLlmToolDefinition.strict`
2. `MultiEdit.edits` 与 `TodoWrite.todos` 已补成展开字段的 nested schema

代码位置：

- [llm/src/main/kotlin/com/opencray/llm/LiteLlmGateway.kt](../llm/src/main/kotlin/com/opencray/llm/LiteLlmGateway.kt)
- [app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt](../app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt)
- [runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt](../runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt)

当前仍受 schema 精度限制的典型方向：

- 其他尚未展开内部字段的 `object[]` / nested object 参数
- 未来新接入工具的 enum / required / nested object fidelity

这意味着：

- `strict` 已不再只是“传输层支持但 runtime 不会发”
- 但 `strict=true` 的收益仍会被 schema 精度上限限制住
- `schema fidelity -> capability probe -> strict enablement` 现在已经串成同一条实施链路，后续只需要继续扩大 schema 覆盖面

## 3. 外部生态调查结论

本节只采信官方文档、官方公告或官方仓库公开 issue，结论以 2026-03-24 调查时公开信息为准。

### 3.1 OpenAI

OpenAI 官方文档明确支持 function calling / tool calling，并建议使用 schema 驱动的工具定义。

关键点：

- Function calling 是原生能力，不是 prompt trick
- 支持基于 JSON schema 的函数工具
- `strict=true` 可显著提高 schema 遵循度
- 原生工具调用还包含 tool choice 控制能力，但不同兼容实现未必完整支持这一整套控制面

官方资料：

- OpenAI Function Calling Guide: <https://developers.openai.com/api/docs/guides/function-calling>

### 3.2 Anthropic

Anthropic 官方文档明确支持原生工具使用：

- 请求里提供 `tools`
- 响应里返回 `tool_use`
- 客户端执行后，以 `tool_result` 回传
- 工具选择与轮转语义是 Anthropic native 自己的一套，不应按 OpenAI 字段级等价处理

官方资料：

- Claude Tool Use Overview: <https://platform.claude.com/docs/en/agents-and-tools/tool-use/overview>

### 3.3 Anthropic 也提供 OpenAI SDK compatibility

Anthropic 官方同时提供 OpenAI SDK compatibility：

- 可以通过 OpenAI SDK 调用 Claude
- 但这条兼容层不是完整等价于 native Claude API
- 官方明确写明 `strict` for function calling 会被忽略

这说明：

- “Claude 模型”不必然等于“Anthropic native messages dialect”
- 同一个模型能力可以通过 OpenAI 兼容层暴露
- 因此系统不能把“模型品牌”直接映射成“请求协议”

官方资料：

- Claude OpenAI SDK Compatibility: <https://platform.claude.com/docs/en/api/openai-sdk>

### 3.4 GLM

智谱官方文档明确支持工具调用，并采用 OpenAI 风格字段：

- `tools`
- `tool_choice`
- `tool_calls`

同时，官方文档和模型能力页显示部分模型原生支持 Function Call。

官方资料：

- 智谱工具调用文档: <https://docs.bigmodel.cn/cn/guide/capabilities/function-calling>
- GLM-4-Flash-250414 能力页: <https://docs.bigmodel.cn/cn/guide/models/free/glm-4-flash-250414>

这意味着 GLM 至少应视为：

- OpenAI-compatible tool calling candidate

而不是“需要单独写一套 GLM 特判协议”。

但也要注意：

- 官方公开示例能证明它支持 OpenAI 风格工具调用
- 不能进一步直接假设它完整等价支持 OpenAI 全量 `tool_choice`、`strict`、并行工具调用控制

### 3.5 Kimi / Moonshot

Moonshot 官方公开资料表明：

- Kimi API 提供 `https://api.moonshot.cn/v1/chat/completions`
- 可直接通过 OpenAI SDK 调用
- 官方曾公告 Tool Use 使用体验升级
- Kimi K2 新版本官方公告明确写到：
  - toolcall 100% 格式正确
  - 完全兼容 Anthropic API

官方资料：

- Kimi API 快速入门: <https://platform.moonshot.cn/blog/posts/kimi-api-quick-start-guide>
- Kimi API 更新公告: <https://platform.moonshot.cn/blog/posts/kimi-api-update-amazon-cloud-china-summit>
- Kimi Playground 工具调用公告: <https://platform.moonshot.cn/blog/posts/kimi-playground>
- Kimi K2 0905 公告: <https://platform.moonshot.cn/blog/posts/kimi-k2-0905>

这说明 Kimi 的情况尤其能证明一个核心结论：

- 厂商名不等于协议形态
- 同一个厂商可能同时暴露 OpenAI 兼容入口和 Anthropic 兼容入口
- 即使是同一厂商，不同入口上的 tool calling 能力子集也可能不同

### 3.6 LiteLLM 深入调查结论

截至 2026-03-24，结合 LiteLLM 官方文档与官方仓库公开 issue，可以得出下面更细的结论。

LiteLLM 官方文档已明确公开这些能力：

- Function calling / tool calling
- `supports_function_calling()`
- `supports_parallel_function_calling()`
- `responses()` 调用路径
- `modify_params=True` 下对 Anthropic 工具消息的 sanitization

这说明 LiteLLM 至少可以承担：

- 统一 transport / proxy 入口
- 某些 provider-specific 参数与消息修正
- 一部分 capability helper
- OpenAI Responses API 风格调用的封装入口

但公开 issue 同时说明，LiteLLM 不能直接当成 OpenCray 的协议真相层：

- `tool_choice` 有过被 LiteLLM 本地参数门禁拦住、而下游其实支持的情况
- `parallel_tool_calls` 有过 provider 本身支持、但 LiteLLM 先判不支持的情况
- `previous_response_id` 在非 OpenAI provider 上需要 LiteLLM 自己做 provider-specific continuity 重建

这三点组合起来的工程含义是：

- LiteLLM 有“魔改空间”，不是不能继续用
- 但这种魔改本身就证明了 continuation / control plane / capability 不是稳定统一原语
- 因此 OpenCray 不应把 capability 真相、fallback 策略、用户可见事件语义外包给 LiteLLM

更稳的结论是：

- LiteLLM 可以继续作为可选 backend
- 但 OpenCray 必须自己拥有 provider-neutral 协议模型、状态机与验收矩阵

## 4. 设计结论：不要按厂商判断，要按“方言 + 能力状态 + 传输层角色”判断

本项目后续不应再用以下任一单一信号决定协议：

- providerId
- host 名称
- model 名称
- 是否是 Claude / GLM / Kimi / DeepSeek

这些信号最多只能用于：

- UI 默认值
- 迁移提示
- 辅助展示

真正决定调用路径的应是三个维度：

1. `apiDialect`
2. `toolCallingCapabilityState`
3. `providerBackend`

### 4.1 推荐的新概念模型

#### A. API 方言

建议引入显式方言字段：

- `openai_chat_completions`
- `anthropic_messages`
- `text_json_fallback`

后续可扩展：

- `openai_responses`
- 其他真正不兼容 OpenAI / Anthropic 的方言

#### B. 工具调用偏好

- `native_preferred`
- `fallback_only`
- `disabled`

#### C. 工具调用能力状态

- `unknown`
- `supported`
- `unsupported`
- `degraded`

#### D. schema 保证级别

- `strict_supported`
- `best_effort_only`
- `unknown`

#### E. tool choice 能力

- `full_control`
- `auto_only`
- `none`
- `unknown`

#### F. 并行工具调用能力

- `supported`
- `unsupported`
- `unknown`

#### G. continuation 能力

- `responses_native`
- `runtime_managed`
- `unsupported`
- `unknown`

#### H. provider backend

- `direct_http`
- `litellm_backend`
- `unknown`

### 4.2 LiteLLM 在架构中的推荐定位

LiteLLM 后续应被视为“可选 backend”，而不是“协议真相层”。

推荐保留给 LiteLLM 的职责：

- transport / proxy / auth / 路由
- provider-specific message sanitization
- 某些 Responses API 或 provider SDK 的兼容层
- 企业侧统一网关接入

不应外包给 LiteLLM 的职责：

- canonical conversation / tool result contract
- capability state machine
- native-first / degraded-fallback 策略
- user-visible progress / reasoning / final phase 语义
- replay / retry 的安全边界

推荐目标架构：

- `OpenCray runtime`
- `OpenCray provider-neutral adapter contract`
- `direct_http adapters` 或 `litellm-backed adapters`

也就是说：

- 保留 LiteLLM 的价值
- 但让 OpenCray 协议核心保持可替换、可验证、可直接落地

## 5. 推荐判定策略

### 5.1 不要“先猜厂商，再选协议”

应改成：

1. 先确定 endpoint 使用哪种 API 方言
2. 再确定该 endpoint 当前的 native tool calling 能力状态
3. 再决定是否启用 native-first

### 5.2 方言来源

方言应优先来自：

1. 用户显式配置
2. preset 默认值
3. base URL 启发式默认值

其中 base URL 启发式只能作为初始默认，不是最终真相。

推荐启发式：

- 看到 `/v1/messages` 或 Anthropic preset，默认 `anthropic_messages`
- 看到 `/chat/completions` 或普通 `/v1`，默认 `openai_chat_completions`
- 自定义 provider 未识别时，默认 `openai_chat_completions`

原因：

- OpenAI-compatible 生态面最广
- GLM、DeepSeek、OpenRouter、Kimi classic、Anthropic OpenAI SDK compatibility 都能落到这类方言

### 5.3 native-first, text-fallback-second

推荐最终策略是：

- 在已知方言内，默认 native tool calling first
- 文本 JSON 协议只作为 fallback

但这里的 “native-first” 不是一个布尔开关，而是要基于该 endpoint 的能力交集构造请求：

- 能发 `tools` 不代表也能安全发 `strict`
- 属于 OpenAI-compatible 不代表也支持完整 `tool_choice`
- 支持单工具串行调用，不代表也支持并行工具调用
- Anthropic native 与 OpenAI-compatible 的 tool result 轮转结构也不同

但这里有两个前提：

1. 必须先知道方言，不能把 OpenAI native request 发到 Anthropic native endpoint
2. fallback 必须有明确降级状态机，不能无限重试

### 5.4 为什么不是“每次都先试所有协议”

不建议在同一 live turn 中盲试多个协议：

- 会增加延迟和 token 成本
- 某些 provider 可能对未知字段静默忽略，无法可靠判定
- 一旦第一跳已经执行出副作用，再自动重试可能导致重复执行
- 审计和 approval trace 会变得难以解释

因此推荐：

- 校验期探测
- 首次运行探测
- 失败后持久化降级

而不是每轮都并发乱试。

### 5.5 为什么不能把“OpenAI-compatible”当成完全等价

后续实现里要避免另一个常见误区：

- 不是只要 endpoint 长得像 OpenAI，就能把 OpenAI 全套请求参数都发上去

真正稳妥的做法是：

1. 先选择 `openai_chat_completions` 这个大方言
2. 再按该 route 的 capability 子集决定：
   - 是否发送 `tools`
   - 是否发送 `tool_choice`
   - 是否允许并行工具调用
   - 是否启用严格 schema 约束

也就是说，协议选择和特性选择是两层判断，不应混成一个 `protocol=openai`。

## 6. 协议层重做方案

### 6.1 Phase 1: 引入 canonical capability model

需要扩展：

- `SavedCustomLlmProvider`
- `LlmSettingsState`
- `ProviderRoute.metadata`

新增建议字段：

- `apiDialect`
- `providerBackend`
- `toolCallingPreference`
- `toolCallingCapabilityState`
- `toolCallingValidatedAtEpochMs`
- `toolCallingLastFailureCode`
- `toolSchemaGuarantee`
- `toolChoiceCapability`
- `parallelToolCallsCapability`
- `continuationCapability`
- `reasoningItemsCapability`

目标：

- 将“协议形态”“能力状态”“continuation 方式”“backend 选择”从单一 `protocol` 中解耦

### 6.2 Phase 2: gateway request/result 契约补齐 provider 控制面

当前 gateway 已有 `messages[]` 与 `tools[]`，下一步应把 provider 级硬控制字段补齐到统一请求模型。

建议新增：

- `toolChoice`
- `parallelToolCalls`
- `previousResponseId` / `continuationToken`
- `responseApiPreferred`
- `providerResponseId`
- `providerLineageId`

目标：

- runtime 不再只靠 prompt 和“一次只做下一步”的软约束
- OpenAI Chat / Responses / Anthropic 三类 adapter 都能共享同一套控制面入口

### 6.3 Phase 3: typed tool result round-trip

当前 transcript 已经保留了 richer payload，但 provider 边界仍然只有扁平 `content`。

建议扩展 `LiteLlmGatewayToolResult` 至少携带：

- `content`
- `structuredContent` 或 `resultJson`
- `exitCode`
- `stdout`
- `stderr`
- `errorCode`
- `errorMessage`
- `metadata`

目标：

- runtime transcript -> gateway message -> provider request 全链路不再丢失工具输出结构
- 后续若接 OpenAI Responses / Anthropic richer content blocks，也有稳定承接点

### 6.4 Phase 4: schema fidelity 先行，再启用 strict

`strict=true` 不应先于 schema fidelity 升级落地。

需要先补：

- `object`
- `object[]`
- object 内部字段 `properties`
- `required`
- 受限枚举值
- 可能的数值范围或字符串格式约束

优先要补的工具：

- `MultiEdit.edits`
- `TodoWrite.todos`
- 其他当前以“数组里是 object”存在的工具参数

目标：

- 先把工具 schema 从“薄类型提示”升级成“可约束结构”
- 再分 route / capability 打开 `strict`

### 6.5 Phase 5: `openai_chat_completions` adapter 硬化

对 `openai_chat_completions` 方言：

- 请求中发送 `tools`
- 按 capability 选择是否发送 `tool_choice`
- 按 capability 选择是否发送 `parallel_tool_calls`
- 解析 `message.tool_calls`
- 兼容 `content` 字符串与数组文本段
- 继续提取公开 progress 与内部 reasoning

兼容目标：

- OpenAI 官方
- OpenRouter
- DeepSeek OpenAI-compatible
- GLM OpenAI-compatible
- Kimi OpenAI-compatible
- Claude OpenAI SDK compatibility

### 6.6 Phase 6: `anthropic_messages` adapter 硬化

对 `anthropic_messages` 方言：

- 请求中发送 `tools`
- 解析 `tool_use`
- 工具执行结果用 `tool_result` 回传
- 保留 `thinking` / `text` / `tool_use` 的 block 级语义

目标：

- 让 Anthropic native path 不再只是“能跑工具”
- 而是真正成为 provider-native 的多轮 tool loop

### 6.7 Phase 7: `openai_responses` 作为独立方言接入

`openai_responses` 不能隐藏在 `openai_chat_completions` 后面做兼容补丁，而应作为显式方言接入。

需要补齐：

- `response_id`
- `previous_response_id`
- output item 解析
- reasoning item / tool item / final text item 区分
- runtime-managed transcript 与 provider lineage 的桥接策略

目标：

- 在支持的 route 上获得 Responses-native continuation
- 让 OpenCray 从“自己重放 transcript 的 native loop”升级到“可沿用 provider lineage 的 native loop”

### 6.8 Phase 8: backend strategy 重构

推荐把 provider adapter 再分成两层：

- 协议层 adapter
- 传输层 backend

后端可选项：

- `direct_http`
- `litellm_backend`

原则：

- 协议语义以 OpenCray adapter 为准
- LiteLLM 只作为 transport / compatibility backend
- direct 和 LiteLLM 两种 backend 都必须跑同一套验收矩阵

### 6.9 Phase 9: 文本 JSON fallback 退居二线

保留当前文本协议，但只在以下场景启用：

- route capability 明确标记为 `unsupported`
- native request 遇到确定性的“unsupported tools”错误
- 某些兼容 provider 只支持普通 completions，不支持 native tool calling

文本 fallback 仍然重要，因为：

- 有些自建代理层会吃掉 `tools`
- 有些廉价兼容服务只支持基础 chat completion

但它不应再是默认主路径，更不应继续承载主链路的协议真相。

## 7. 能力探测与校验设计

### 7.1 设置页校验应分成两级

#### Level 1: 基础连通性校验

继续保留：

- `Reply with OK.`

目的：

- 验证 API key / base URL / model / 基本文本 completion 是否可用

#### Level 2: Agent capability probe

新增：

- native tool calling round-trip probe

探测方式：

- 提供一个只读、零副作用的测试工具定义，例如 `EchoProbe`
- 强提示模型调用它
- provider client 判断是否收到 native tool call 结构

判定结果：

- 成功：`toolCallingCapabilityState = supported`
- HTTP 明确报不支持字段：`unsupported`
- 200 但完全忽略工具：`degraded` 或 `unsupported`
- 网络/超时：保留 `unknown`

### 7.2 运行时动态降级

即使校验通过，运行中也可能遇到：

- 模型切换
- provider 兼容层变更
- 上游路由临时降级

因此运行时仍需动态降级：

- native path 发生确定性 unsupported error -> 标记 degraded/unsupported
- 同 route 后续改走文本 fallback
- 在下次手动校验或 TTL 过期后才恢复 probe

### 7.3 不透明 200 响应的处理

最麻烦的是：

- HTTP 200
- provider 不报错
- 但既没 native tool call，也没可用文本

这类情况不能只记成 `PROVIDER_EMPTY_RESPONSE`。

应该补充更多诊断维度：

- `responseShape`
- `nativeToolCallObserved`
- `nativeToolCallRequested`
- `fallbackParserAttempted`
- `fallbackParserSucceeded`
- `toolCallEventEmitted`
- `toolResultEventEmitted`
- `lastSuccessfulToolName`

其中事件层建议直接基于宿主真实事件判断，而不是只看中间解析状态：

- 是否实际发出了 `OpenCrayToolCallEvent`
- 是否实际发出了 `OpenCrayToolResultEvent`

这样才能区分：

- provider/client 解析出了工具调用，但 runtime 没真正下发工具
- runtime 确实下发了工具，但工具没有成功回到结果事件
- 工具结果事件已经产生，但后续 final 丢失

### 7.4 运行时状态机建议

建议把 route 的工具调用状态机明确化：

1. `unknown`
2. `probing_native`
3. `native_supported`
4. `native_degraded`
5. `fallback_text_only`

建议行为：

- 新 route 初始为 `unknown`
- 设置页 capability probe 成功后进入 `native_supported`
- 明确 unsupported 错误进入 `fallback_text_only`
- 200 但 response shape 异常、多次收不到 native tool call，则进入 `native_degraded`
- `native_degraded` 经过 TTL 到期或用户手动重验，才有资格回到 `probing_native`

### 7.5 同 turn 自动回退的安全边界

自动回退不是任何时候都安全。

推荐规则：

- 若本轮 `nativeToolCallObserved = false` 且工具尚未执行，可以在同 turn 内切到文本 fallback
- 若已经观察到 native tool call，或宿主已经执行了任何副作用工具，就不要对同一用户意图再自动重放另一种协议
- 这种场景应优先：
  - 继续当前方言完成后续轮转
  - 或中止并向用户展示“工具已执行/部分成功”的宿主摘要

原因：

- 否则可能重复创建 todo、重复发起 shell/tool 执行、重复触发 approval 流程
- 这类风险本质上是协议层幂等性问题，不能靠某个单工具特判来兜

## 8. 通用兜底，而不是工具特判

即使 native tool calling 全部打通，也仍然建议补一层通用宿主兜底：

- 如果最后一个工具已经成功执行
- 但后续最终答复为空、内部 payload、或 provider 空 completion

则宿主应优先用“最后一个成功工具的结果摘要”生成用户可见回复。

这层兜底应当：

- 面向所有成功工具
- 使用已有 tool result summary 机制
- 不写 `TodoWrite` 专用分支

原因：

- 真正可靠的系统需要同时解决“主路径稳定性”和“异常收敛体验”
- 即使协议层修完，上游 provider 仍可能偶发空第二跳

## 9. 分阶段实施计划

### Phase A: 观测与基线校正

- 为 provider client 增加 response shape trace
- 为 runtime 增加 native/fallback parse path trace
- 为 run 级诊断补齐最小观测集：
  - provider 返回形状
  - 是否解析出 native tool call
  - 是否实际发出 `OpenCrayToolCallEvent`
  - 是否实际发出 `OpenCrayToolResultEvent`
  - `lastSuccessfulToolName`
- 补测试：
  - OpenAI native `tool_calls`
  - Anthropic `tool_use`
  - OpenAI-compatible 但空 `content`
  - native unsupported -> fallback
  - first tool success + second final empty
- 在配置层和文档里补齐 `apiDialect` / `providerBackend` / `continuationCapability` 的统一术语

验收标准：

- 能明确区分“工具未执行”和“工具执行后 final 丢失”
- 能明确区分“方言不匹配”和“方言正确但能力子集不足”

### Phase B: gateway 控制面与 typed tool result 合同

- 为 `LiteLlmGatewayRequest` 增加：
  - `toolChoice`
  - `parallelToolCalls`
  - `previousResponseId` / `continuationToken`
  - `responseApiPreferred`
- 为 `LiteLlmGatewayToolResult` 增加 richer payload 字段
- 为 provider result / metadata 增加 `providerResponseId` / lineage 观测位
- 补 transcript -> gateway -> provider 的 round-trip 测试

验收标准：

- runtime 不再只能把控制面埋在 prompt 里
- richer tool result 不再在 provider 边界丢失

### Phase C: schema fidelity 与 strict readiness

- 升级 `AgentToolParameter -> JSON Schema` 生成器
- 为 `object` / `object[]` 补内部字段 schema
- 先补 `MultiEdit.edits`、`TodoWrite.todos`
- 只有在 schema 达标后，才开始对支持的 route 打开 `strict`

验收标准：

- nested object 参数不再只有“元素是 object”的空壳 schema
- `strict` 的启用建立在真实 schema fidelity 上，而不是传输层假开启

### Phase D: direct adapters 硬化

- 硬化 `openai_chat_completions` adapter
- 硬化 `anthropic_messages` adapter
- 让 direct HTTP 路径先成为功能完备、可独立运行的基线实现

验收标准：

- OpenAI Chat 与 Anthropic Messages 都能在 direct backend 下稳定跑通 native loop
- `tool_choice` / `parallel_tool_calls` 按 capability 受控下发

### Phase E: Responses-native continuation

- 新增 `openai_responses` 方言
- 接入 `response_id` / `previous_response_id`
- 解析 Responses output items
- 定义 provider lineage 与 runtime transcript 的桥接策略

验收标准：

- 支持的 route 可走 Responses-native continuation
- OpenCray 不再只能依赖“手工重放 transcript”维持所有 provider 状态

### Phase F: capability probe、持久化与 LiteLLM backend 决策

- 设置页新增 capability probe
- route metadata 持久化 capability state
- 运行时动态降级
- 为 LiteLLM backend 加同一套 probe / capability / regression gate
- 明确哪些 route 默认走 direct backend，哪些 route 可以安全走 LiteLLM backend

验收标准：

- “设置页验证通过但 agent 不可用”明显减少
- route 失败后能稳定收敛到正确的降级形态，而不是每轮重复踩坑
- LiteLLM 不再是“隐式主路径”，而是“通过矩阵验证后可启用的 backend”

### Phase G: canonical message model 与宿主通用兜底

- gateway request 不再只接受单个 prompt
- provider adapter 消费 canonical messages
- 真正支持 provider-native multi-turn tool loop
- 基于最后一个成功工具生成摘要型 fallback
- 覆盖空第二跳、内部 payload、provider empty completion

验收标准：

- multi-turn tool calling 不再主要依赖 prompt 内文本模拟
- 用户不再频繁遇到“明明执行了动作却看起来像空返回”

## 10. 最终建议

最终建议不是“按 provider 名单写死特判”，也不是“每轮都乱试所有协议”。

建议落地方向是：

1. 显式建模 `apiDialect`
2. 显式建模 `providerBackend`
3. 在该方言内默认 native tool calling first
4. 用 capability probe + 运行时观测决定该 route 具体能用到哪一层 native 能力
5. 先补 schema fidelity，再按 capability 启用 `strict`
6. 将 `openai_responses` 作为独立 continuation 方言建设，而不是隐藏在 chat completions 兼容层里
7. 只在安全边界内退回文本 JSON fallback
8. 即使回退，也以通用宿主摘要兜底，而不是给某个工具写专门补丁

换句话说：

- 协议选择按 endpoint/dialect
- continuation 选择按 route capability
- transport 选择按 backend 验证结果
- 原生能力选择按 capability subset
- 文本协议只做兼容兜底

这才是能同时覆盖 OpenAI、Anthropic、GLM、Kimi、custom provider 代理层，以及未来 LiteLLM backend / direct backend 并存场景的长期方案。

## 11. 测试矩阵

### 11.1 Provider adapter 单测

- OpenAI:
  - `message.tool_calls`
  - `content` 文本 final
  - `content` 数组文本段
  - `strict` capability state
- OpenAI Responses:
  - `response_id`
  - `previous_response_id`
  - output items 中的 reasoning / tool / final text
- Anthropic:
  - `tool_use`
  - `tool_result`
  - 纯文本 final
  - 非文本 block 混合
- 兼容层：
  - Claude OpenAI SDK compatibility
  - GLM OpenAI-compatible
  - Kimi OpenAI-compatible
  - Kimi Anthropic-compatible
- backend：
  - `direct_http`
  - `litellm_backend`

### 11.2 Runtime 单测

- native tool call -> execute -> native final
- native tool call -> execute -> empty final -> host fallback
- native unsupported -> text fallback
- first hop parse failure diagnostics
- richer tool result -> gateway -> provider request round-trip
- schema fidelity 提升后 strict route 的参数遵循

### 11.3 Settings / validation 单测

- 文本校验成功但 tool probe 失败
- tool probe 成功后 capability 持久化
- degraded route 自动降级
- Responses-capable route 与 chat-only route 的区分
- direct backend 与 LiteLLM backend 的能力矩阵一致性

## 12. 关键决策

### 决策 1

`custom provider` 不按厂商名判定协议，按 endpoint 方言判定。

### 决策 2

方言一旦确定，默认 native tool calling first，文本 JSON second。

### 决策 3

文本 JSON fallback 保留，但降级为兼容路径，不再作为主路径。

### 决策 4

能力状态要持久化，不能每轮都盲猜。

### 决策 5

设置页必须提供 agent capability probe，而不是只测纯文本 completion。

### 决策 6

OpenCray 自己拥有协议核心，LiteLLM 只作为可选 backend，不作为 capability 真相层。

### 决策 7

`strict` 不是先打开再修 schema，而是先补 schema fidelity，再按 capability 启用 strict。

### 决策 8

`openai_responses` 必须作为独立 continuation 方言接入，不隐藏在 `openai_chat_completions` 之下。

## 13. 非目标

本计划当前不覆盖：

- 并行多工具调用调度优化
- server-side tools 的供应商专有高级能力整合
- image/audio generation 专属非文本 API
- 一次性统一接入所有第三方 provider 的全部专有字段

优先目标是：

- 先把通用 client-side tool loop 做稳
- 再逐步扩展高阶方言能力

## 14. 参考资料

### 本地代码

- [app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt](../app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt)
- [app/src/main/kotlin/com/opencray/app/LlmProviderRequestSupport.kt](../app/src/main/kotlin/com/opencray/app/LlmProviderRequestSupport.kt)
- [app/src/main/kotlin/com/opencray/app/LlmSettingsStore.kt](../app/src/main/kotlin/com/opencray/app/LlmSettingsStore.kt)
- [app/src/main/kotlin/com/opencray/app/facade/llm/LlmConfigFacade.kt](../app/src/main/kotlin/com/opencray/app/facade/llm/LlmConfigFacade.kt)
- [llm/src/main/kotlin/com/opencray/llm/LiteLlmGateway.kt](../llm/src/main/kotlin/com/opencray/llm/LiteLlmGateway.kt)
- [llm/src/main/kotlin/com/opencray/llm/ProviderRouting.kt](../llm/src/main/kotlin/com/opencray/llm/ProviderRouting.kt)
- [runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt](../runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt)
- [runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt](../runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt)
- [runtime/src/main/kotlin/com/opencray/runtime/context/PromptAssembler.kt](../runtime/src/main/kotlin/com/opencray/runtime/context/PromptAssembler.kt)

### 官方外部资料

- OpenAI Function Calling Guide: <https://developers.openai.com/api/docs/guides/function-calling>
- OpenAI Local Shell / Responses continuation guide: <https://developers.openai.com/api/docs/guides/tools-local-shell>
- Claude Tool Use Overview: <https://platform.claude.com/docs/en/agents-and-tools/tool-use/overview>
- Claude OpenAI SDK Compatibility: <https://platform.claude.com/docs/en/api/openai-sdk>
- LiteLLM Function Calling: <https://docs.litellm.ai/docs/completion/function_call>
- LiteLLM Message Sanitization: <https://docs.litellm.ai/docs/completion/message_sanitization>
- LiteLLM Getting Started / Responses: <https://docs.litellm.ai/>
- LiteLLM `tool_choice` issue: <https://github.com/BerriAI/litellm/issues/14704>
- LiteLLM `parallel_tool_calls` issue: <https://github.com/BerriAI/litellm/issues/9686>
- LiteLLM `previous_response_id` continuity issue: <https://github.com/BerriAI/litellm/issues/12677>
- 智谱工具调用文档: <https://docs.bigmodel.cn/cn/guide/capabilities/function-calling>
- 智谱模型能力页（示例）: <https://docs.bigmodel.cn/cn/guide/models/free/glm-4-flash-250414>
- Kimi API 快速入门: <https://platform.moonshot.cn/blog/posts/kimi-api-quick-start-guide>
- Kimi API Tool Use 更新公告: <https://platform.moonshot.cn/blog/posts/kimi-api-update-amazon-cloud-china-summit>
- Kimi Playground 工具调用公告: <https://platform.moonshot.cn/blog/posts/kimi-playground>
- Kimi K2 0905 公告: <https://platform.moonshot.cn/blog/posts/kimi-k2-0905>
