# Agent Native Tool Calling Protocol Plan

更新时间：2026-03-22

## 当前进度

截至 2026-03-22，本计划已落地到下面这一步：

- gateway 请求模型已支持 `messages[]` 与 `tools[]`
- OpenAI 兼容与 Anthropic 分支都已接入原生 tool calling request/response 解析
- runtime 已优先消费 provider 返回的结构化 completion，只把文本 JSON 协议当 fallback
- runtime transcript / prompt resume 已开始保存结构化 `tool_call` / `tool_result` 元数据
- tool call id 已能在 `provider -> runtime -> approval/resume -> 下一跳 gateway request` 之间贯通

当前仍未完成的关键尾项：

- 宿主层与持久化回放层还存在一部分基于文本前缀的历史兼容逻辑
- transcript 压缩、回放、观测链路虽然已开始识别结构化 `kind`，但还没有彻底移除旧文本协议分支
- 还需要继续把“事件回放 / 持久化 transcript / 运行态 transcript”统一到同一套 canonical message 模型

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

### 2.1 当前 gateway 请求模型只支持“文本 prompt + 文本 completion”

当前 `LiteLlmGatewayRequest` 只有：

- `prompt`
- `systemPrompt`
- `metadata`
- `authHeaders`

没有：

- provider 级 message 序列
- tool definitions
- tool choice
- tool result messages
- native completion envelope

代码位置：

- [llm/src/main/kotlin/com/opencray/llm/LiteLlmGateway.kt](../llm/src/main/kotlin/com/opencray/llm/LiteLlmGateway.kt)
- [runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt](../runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt)

其中 runtime 每一轮仍然把内容组装成：

- `systemPrompt = assembledPrompt.systemPrompt`
- `prompt = assembledPrompt.taskPrompt`

再交给 gateway：

- [runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt](../runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt)

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

### 2.3 OpenAI 兼容 provider client 仍以文本完成为主

`OpenAiCompatibleLiteLlmProviderClient` 当前：

- OpenAI 兼容走 `POST /v1/chat/completions`
- Anthropic 走 `POST /v1/messages`
- OpenAI 分支只从 `message.content`、`message.tool_calls`、`reasoning_content` 等少数位置取值
- Anthropic 分支只拼接 `content[]` 中 `type=text` 的 block

代码位置：

- [app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt](../app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt)

当前已确认的限制：

1. OpenAI 分支没有向 provider 发送 `tools`
2. Anthropic 分支没有向 provider 发送 `tools`
3. Anthropic 分支没有解析 `tool_use`
4. provider 返回空 completion 会直接落成 `PROVIDER_EMPTY_RESPONSE`

### 2.4 runtime 仍在要求模型“输出 JSON 文本”

当前 prompt protocol 明确要求模型：

- “On each turn, return exactly one JSON object and nothing else.”
- `type=tool_call` 由 runtime 执行后再追问下一步
- `tool_call` 不能同时带 final answer

代码位置：

- [runtime/src/main/kotlin/com/opencray/runtime/context/PromptAssembler.kt](../runtime/src/main/kotlin/com/opencray/runtime/context/PromptAssembler.kt)
- [runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt](../runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt)

这导致当前系统有两个独立故障面：

1. 第一跳：模型没有按文本 JSON 协议输出工具调用，工具根本不会执行
2. 第二跳：工具执行后，最终答复为空、变形、或被 provider/client 丢失

### 2.5 设置页校验没有验证 agent path

当前 `validate()` 只发一条简单 prompt：

- `Reply with OK.`

不会验证：

- 工具定义是否能成功发送
- provider 是否支持 native tool calling
- provider 是否会返回可解析的 native tool call
- schema 约束是否生效

代码位置：

- [app/src/main/kotlin/com/opencray/app/facade/llm/LlmConfigFacade.kt](../app/src/main/kotlin/com/opencray/app/facade/llm/LlmConfigFacade.kt)

因此，当前“设置页验证通过”只代表文本问答可用，不代表 agent 工具调用链路可用。

### 2.6 当前测试覆盖仍明显偏向 OpenAI 文本协议桥接

当前测试主要证明了两件事：

- OpenAI 风格 `tool_calls` 能被 provider client 合成为当前文本协议 payload
- reasoning 字段里如果恰好塞了一段 JSON，也可能被文本协议解析器接住

代码位置：

- [app/src/test/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClientTest.kt](../app/src/test/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClientTest.kt)

但当前仍缺少关键覆盖：

- Anthropic 原生 `tool_use`
- 原生 request 里真正发送 `tools`
- capability probe 成功/失败路径
- `native unsupported -> text fallback`
- “第一跳工具已执行，第二跳最终答复为空” 的端到端收敛

这意味着现有测试还不足以证明当前系统具备跨方言、跨兼容层的稳定工具调用能力。

## 3. 外部生态调查结论

本节只采信官方文档或官方公告，结论以 2026-03-22 调查时官方公开信息为准。

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

## 4. 设计结论：不要按厂商判断，要按“方言 + 能力状态”判断

本项目后续不应再用以下任一单一信号决定协议：

- providerId
- host 名称
- model 名称
- 是否是 Claude / GLM / Kimi / DeepSeek

这些信号最多只能用于：

- UI 默认值
- 迁移提示
- 辅助展示

真正决定调用路径的应是两个维度：

1. `apiDialect`
2. `toolCallingCapabilityState`

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
- `toolCallingPreference`
- `toolCallingCapabilityState`
- `toolCallingValidatedAtEpochMs`
- `toolCallingLastFailureCode`
- `toolSchemaGuarantee`
- `toolChoiceCapability`
- `parallelToolCallsCapability`

目标：

- 将“协议形态”和“能力状态”从 `protocol` 中解耦

### 6.2 Phase 2: gateway/provider response 改成结构化结果

当前 `LiteLlmProviderResult.Success` 只有 `outputText`。

建议升级为 envelope 形态，例如：

```kotlin
data class LiteLlmTurnEnvelope(
  val assistantText: String? = null,
  val toolCalls: List<LiteLlmToolCall> = emptyList(),
  val progressText: String? = null,
  val rawText: String? = null,
  val finishReason: String? = null,
  val dialect: String,
  val parseMode: String,
  val metadata: Map<String, String> = emptyMap(),
)
```

重点：

- runtime 不再只依赖 `outputText`
- provider client 负责把 native tool calling 结构提升成统一 envelope
- 文本 JSON 解析变成 envelope 构建时的 fallback，而不是唯一主路径

### 6.3 Phase 3: OpenAI-compatible native tool calling

对 `openai_chat_completions` 方言：

- 请求中发送 `tools`
- 需要时发送 `tool_choice`
- 解析 `message.tool_calls`
- 接收普通文本 final answer
- 兼容 `content` 数组文本段

兼容目标：

- OpenAI 官方
- OpenRouter
- DeepSeek OpenAI-compatible
- GLM OpenAI-compatible
- Kimi OpenAI-compatible
- Claude OpenAI SDK compatibility

注意：

- 不应默认假设所有 OpenAI-compatible endpoint 都支持 `strict`
- `strict` 需要单独 capability 标记
- `tool_choice` 也需要 capability 标记，不能因为是 OpenAI-compatible 就默认开启最强控制
- 并行工具调用应默认关闭，直到 capability probe 或 route preset 明确证明可用

### 6.4 Phase 4: Anthropic native tool use

对 `anthropic_messages` 方言：

- 请求中发送 `tools`
- 解析 `content[]` 中 `tool_use`
- 工具执行结果用 `tool_result` 语义回传

第一阶段即便仍保留 flattened prompt，也至少要做到：

- 原生 `tool_use` 能被识别
- 不再因为 `content` 里没有纯文本就被误判为空响应

第二阶段再升级到真正的 provider-native message history。

### 6.5 Phase 5: canonical message model

要真正把 multi-turn native tool calling 做稳，最终还需要把 gateway request 从：

- `systemPrompt + taskPrompt`

升级为：

- canonical `messages[]`
- canonical `tools[]`
- canonical `tool_result` / `assistant tool call` messages

建议新增内部模型：

- `GatewayConversationMessage`
- `GatewayToolDefinition`
- `GatewayToolResultMessage`

这样 provider adapter 才能：

- OpenAI 路径输出 `messages`
- Anthropic 路径输出 `messages + content blocks`

而不是继续把整段 transcript 压成一个长字符串再祈祷模型自己遵循协议。

### 6.6 Phase 6: 文本 JSON fallback 退居二线

保留当前文本协议，但只在以下场景启用：

- route capability 明确标记为 `unsupported`
- native request 遇到确定性的“unsupported tools”错误
- 某些兼容 provider 只支持普通 completions，不支持 native tool calling

文本 fallback 仍然重要，因为：

- 有些自建代理层会吃掉 `tools`
- 有些廉价兼容服务只支持基础 chat completion

但它不应再是默认主路径。

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

### Phase A: 观测与测试地基

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

验收标准：

- 能明确区分“工具未执行”和“工具执行后 final 丢失”
- 能明确区分“方言不匹配”和“方言正确但能力子集不足”

### Phase B: OpenAI-compatible native path

- 在 gateway/provider request 中增加 canonical tools
- OpenAI-compatible request 发送 `tools`
- provider client 返回 structured envelope
- runtime 优先消费 native tool calls

验收标准：

- OpenAI / OpenRouter / DeepSeek / GLM / Kimi OpenAI-compatible 路径可走 native-first
- 对 `strict`、`tool_choice`、并行工具调用不做超能力假设

### Phase C: Anthropic native path

- Anthropic request 发送 `tools`
- 解析 `tool_use`
- 支持 `tool_result` 语义轮转

验收标准：

- Claude native messages API 可稳定完成 tool loop
- Kimi Anthropic-compatible endpoint 可被方言层接入

### Phase D: 校验与能力持久化

- 设置页新增 capability probe
- route metadata 持久化 capability state
- 运行时动态降级

验收标准：

- “设置页验证通过但 agent 不可用”明显减少
- route 失败后能稳定收敛到正确的降级形态，而不是每轮重复踩坑

### Phase E: canonical message model

- gateway request 不再只接受单个 prompt
- provider adapter 消费 canonical messages
- 真正支持 provider-native multi-turn tool loop

验收标准：

- multi-turn tool calling 不再主要依赖 prompt 内文本模拟

### Phase F: 宿主通用兜底

- 基于最后一个成功工具生成摘要型 fallback
- 覆盖空第二跳、内部 payload、provider empty completion

验收标准：

- 用户不再频繁遇到“明明执行了动作却看起来像空返回”

## 10. 最终建议

最终建议不是“按 provider 名单写死特判”，也不是“每轮都乱试所有协议”。

建议落地方向是：

1. 显式建模 `apiDialect`
2. 在该方言内默认 native tool calling first
3. 用 capability probe + 运行时观测决定该 route 具体能用到哪一层 native 能力
4. 只在安全边界内退回文本 JSON fallback
5. 即使回退，也以通用宿主摘要兜底，而不是给某个工具写专门补丁

换句话说：

- 协议选择按 endpoint/dialect
- 原生能力选择按 capability subset
- 文本协议只做兼容兜底

这才是能同时覆盖 OpenAI、Anthropic、GLM、Kimi、以及 custom provider 代理层的长期方案。

## 11. 测试矩阵

### 11.1 Provider adapter 单测

- OpenAI:
  - `message.tool_calls`
  - `content` 文本 final
  - `content` 数组文本段
  - `strict` capability state
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

### 11.2 Runtime 单测

- native tool call -> execute -> native final
- native tool call -> execute -> empty final -> host fallback
- native unsupported -> text fallback
- first hop parse failure diagnostics

### 11.3 Settings / validation 单测

- 文本校验成功但 tool probe 失败
- tool probe 成功后 capability 持久化
- degraded route 自动降级

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
- [runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt](../runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt)
- [runtime/src/main/kotlin/com/opencray/runtime/context/PromptAssembler.kt](../runtime/src/main/kotlin/com/opencray/runtime/context/PromptAssembler.kt)

### 官方外部资料

- OpenAI Function Calling Guide: <https://developers.openai.com/api/docs/guides/function-calling>
- Claude Tool Use Overview: <https://platform.claude.com/docs/en/agents-and-tools/tool-use/overview>
- Claude OpenAI SDK Compatibility: <https://platform.claude.com/docs/en/api/openai-sdk>
- 智谱工具调用文档: <https://docs.bigmodel.cn/cn/guide/capabilities/function-calling>
- 智谱模型能力页（示例）: <https://docs.bigmodel.cn/cn/guide/models/free/glm-4-flash-250414>
- Kimi API 快速入门: <https://platform.moonshot.cn/blog/posts/kimi-api-quick-start-guide>
- Kimi API Tool Use 更新公告: <https://platform.moonshot.cn/blog/posts/kimi-api-update-amazon-cloud-china-summit>
- Kimi Playground 工具调用公告: <https://platform.moonshot.cn/blog/posts/kimi-playground>
- Kimi K2 0905 公告: <https://platform.moonshot.cn/blog/posts/kimi-k2-0905>
