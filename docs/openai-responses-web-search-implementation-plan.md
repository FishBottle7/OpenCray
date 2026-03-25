# OpenAI Responses 与宿主 WebSearch 并存实施设计

更新时间：2026-03-24

## 1. 文档目的

本文档定义一条可执行的实施方案，用来同时满足下面两个目标：

- 保留宿主 `WebSearch` 工具，保证任意主模型都能进行联网搜索
- 为 OpenAI 模型增加独立的 `openai_responses` 路线，使其能够使用 OpenAI 原生 `web_search`、`previous_response_id` 与更完整的 Responses-native agent 能力

这不是一份抽象 roadmap，而是一份面向当前代码库的实施设计。重点回答：

- 哪些能力已经在现有代码里具备
- 哪些地方仍然是结构性缺口
- 应该如何拆模块、拆 PR、拆测试矩阵
- 宿主搜索与 provider-native 搜索应该如何并存，而不是互相覆盖

## 2. 结论摘要

推荐采用“双层并存”方案，而不是二选一：

1. 保留宿主 `WebSearch`
2. 新增独立 provider 方言：`openai_responses`
3. 在 `openai_responses` route 上接入 Responses-native continuation 与 provider-native `web_search`
4. 再新增宿主搜索 backend：`OpenAiResponsesWebSearchProvider`
5. 在非 `openai_responses` route 上继续暴露宿主 `WebSearch`

这样可以同时获得：

- 多 provider 主模型都能搜索
- OpenAI 官方搜索可以替代第三方搜索 backend
- OpenAI 模型在支持的 route 上获得更自然的 Responses-native 搜索与 continuation 行为

## 3. 目标与非目标

### 3.1 目标

- 保持宿主工具层的统一搜索抽象不变
- 允许宿主 `WebSearch` 的 backend 使用 OpenAI 官方 `web_search`
- 在 OpenAI 支持的 route 上引入 `Responses` 原生 continuation
- 避免把 `Responses` 塞进现有 `chat/completions` 兼容分支里做条件补丁
- 明确 provider-native 搜索与宿主搜索的暴露策略，避免模型同时看到两个语义重复的搜索工具
- 保持 runtime transcript 仍然是 durable source of truth

### 3.2 非目标

- 本阶段不要求把所有 provider 都迁移到 Responses 风格
- 本阶段不要求把 Anthropic 或其他 OpenAI-compatible provider 统一成 Responses 方言
- 本阶段不要求一次性提升所有复杂工具的 schema fidelity 到完全 strict-ready
- 本阶段不移除现有 `openai` chat-completions 路线

## 4. 当前代码基线

### 4.1 已具备的能力

当前代码已经有几块关键基础，可以直接承接这次实施：

- gateway 请求模型已经支持 `previousResponseId` 与 `responseApiPreferred`
  - 代码位置：[llm/src/main/kotlin/com/opencray/llm/LiteLlmGateway.kt](../llm/src/main/kotlin/com/opencray/llm/LiteLlmGateway.kt)
- gateway 返回模型已经支持 `providerResponseId` 与 `providerLineageId`
  - 代码位置：[llm/src/main/kotlin/com/opencray/llm/LiteLlmGateway.kt](../llm/src/main/kotlin/com/opencray/llm/LiteLlmGateway.kt)
- runtime 已可从 `llmMetadata` 透传 `previousResponseId` 与 `responseApiPreferred`
  - 代码位置：[runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt](../runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt)
- provider 结构化 completion 已拆分为 `toolCalls / finalText / progressText / reasoningText / rawText`
  - 代码位置：[llm/src/main/kotlin/com/opencray/llm/LiteLlmGateway.kt](../llm/src/main/kotlin/com/opencray/llm/LiteLlmGateway.kt)
- runtime 已优先消费 provider 返回的结构化 completion
  - 代码位置：[runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt](../runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt)
- 宿主 `WebSearch` 工具已经是独立抽象，并不绑定某个 provider
  - 代码位置：[runtime/src/main/kotlin/com/opencray/runtime/web/WebSearchProvider.kt](../runtime/src/main/kotlin/com/opencray/runtime/web/WebSearchProvider.kt)
- 当前 app 注入的搜索 provider 也是独立工厂
  - 代码位置：[app/src/main/kotlin/com/opencray/app/AppConfiguredWebSearchProviderFactory.kt](../app/src/main/kotlin/com/opencray/app/AppConfiguredWebSearchProviderFactory.kt)

### 4.2 当前主要缺口

尽管底层模型已留出不少字段，真正的协议能力仍然缺下面这些环节：

- provider 协议枚举当前只有 `openai` 与 `anthropic`
  - 代码位置：[app/src/main/kotlin/com/opencray/app/LlmProviderRequestSupport.kt](../app/src/main/kotlin/com/opencray/app/LlmProviderRequestSupport.kt)
- provider client 当前只有两条 endpoint 语义：
  - OpenAI 兼容：`/v1/chat/completions`
  - Anthropic：`/v1/messages`
  - 代码位置：[app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt](../app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt)
- 宿主搜索 backend 当前只支持 `exa / tavily / brave`
  - 代码位置：[runtime/src/main/kotlin/com/opencray/runtime/web/SequentialWebSearchProvider.kt](../runtime/src/main/kotlin/com/opencray/runtime/web/SequentialWebSearchProvider.kt)
- 当前没有 runtime-managed continuation state 与 provider lineage state 的桥接逻辑
- 当前没有 OpenAI built-in tool 与 host function tool 的分层建模
- 当前 tool schema fidelity 还不足以无风险直接切所有复杂工具到 strict Responses function schema
  - 代码位置：[runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt](../runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt)

### 4.3 当前最重要的判断

从现状看，本次工作不是“从零搭 agent loop”，而是：

- 在现有 native tool loop 之上
- 再接入一条独立的 OpenAI Responses 方言
- 同时给宿主搜索层增加一个可复用的 OpenAI 官方搜索 backend

因此它属于“中高复杂度的协议扩展”，不是“重写 runtime”。

## 5. 目标架构

目标架构分成三层。

### 5.1 第一层：主模型 route / dialect 层

显式区分下面三类 route：

- `openai`
  - 语义：OpenAI-compatible chat completions
- `anthropic`
  - 语义：Anthropic Messages API
- `openai_responses`
  - 语义：OpenAI Responses API

关键原则：

- `openai_responses` 必须是独立方言
- 不能把它当成 `openai` 的布尔开关
- 不能继续在一个 giant client 里用 `when(protocol)` 无限叠加

### 5.2 第二层：宿主工具层

宿主工具层继续保持统一：

- `Read`
- `Write`
- `Bash`
- `WebSearch`
- 其他 host tools

这里的原则是：

- 宿主工具是跨模型的一致能力面
- provider-native 能力不应破坏宿主工具抽象
- 但允许在特定 route 上隐藏某些宿主工具，改为使用 provider-native 同类能力

### 5.3 第三层：搜索 backend 层

宿主 `WebSearch` 的 backend 允许多实现并存：

- `exa`
- `tavily`
- `brave`
- `openai_web_search`

这个层次服务的是“所有主模型都能搜索”的目标。

注意：它和 `openai_responses` 主 route 是两个不同层次的问题。

- backend 层解决“谁来搜”
- route 层解决“主模型如何以原生方式思考、续接、调工具”

## 6. 关键设计决策

### 6.1 保留宿主 WebSearch，不做 OpenAI-only 搜索架构

这是整个设计最重要的前提。

如果把搜索能力直接下沉成 OpenAI route 的 provider-native 专属能力，那么：

- Anthropic / GLM / Kimi / 其他兼容模型会失去统一搜索能力
- runtime 工具面会出现 provider-specific 分叉
- policy、approval、observability 和 transcript 也会变复杂

因此宿主 `WebSearch` 必须保留。

### 6.2 OpenAI 模型的“完全实力”来自独立 Responses route，而不是只换搜索 backend

仅仅把宿主 `WebSearch` 的 backend 换成 OpenAI 官方搜索，并不能让 OpenAI 模型拿到完整的 Responses-native agent 能力。

只换 backend，OpenAI 模型仍然受限于：

- `chat/completions` 的消息语义
- runtime 手工 transcript replay
- 没有 provider-native `previous_response_id`
- 没有 Responses output items
- 没有 built-in `web_search` 的原生工具面

因此：

- 搜索 backend 替换是必要但不充分条件
- 真正让 OpenAI agent 行为接近 Codex 的，是独立的 `openai_responses` route

### 6.3 实施顺序先做 Responses protocol，再做宿主搜索 backend

推荐交付顺序不是“先做搜索 backend”，而是：

1. 先做 `openai_responses` route 本身
2. 再补齐该 route 上的 provider-native `web_search`
3. 最后再把 OpenAI 官方搜索下沉成宿主 `WebSearch` backend

这样做的原因是：

- 先稳定 dialect、continuation、tool 建模与 output item 解析边界
- 再接 provider-native 搜索时，不会把“协议问题”和“backend 归一问题”混在一起排查
- 宿主搜索 backend 可以在 Responses route 稳定后复用相同的 OpenAI 协议理解与解析逻辑

### 6.4 OpenAI Responses route 默认不暴露宿主 WebSearch

为了避免模型同时看到两个语义重复的搜索工具，推荐如下默认策略：

- `openai_responses + native_web_search_enabled=true`
  - 不把宿主 `WebSearch` 暴露给模型
  - 只暴露 provider-native `web_search`
- 其他 route
  - 继续暴露宿主 `WebSearch`

这样可以避免：

- 模型在两种搜索工具之间来回摇摆
- 不同搜索工具的结果格式混杂
- prompt 里不得不解释“什么时候用哪个搜索工具”

### 6.5 transcript 继续是真相源，provider lineage 只是 continuation 加速层

不能把 `previous_response_id` 当成唯一状态源。

推荐语义：

- provider lineage 用来让 OpenAI route 获得更自然的 continuation
- runtime transcript 继续承担 durable replay / resume / audit / compaction 的真相源职责

这意味着：

- 一旦 provider continuity 不可信，runtime 仍可回退到 transcript replay
- 不能把恢复语义完全绑死在某个 provider 的 lineage 上

### 6.6 不同方言不能演化成不同 agent 架构

`openai_responses` 可以拥有更强的 provider-native continuation 与 built-in tools，但这不应把系统演化成“两套 agent 架构”。

需要保持一致的层次是：

- 同一个宿主 runtime loop
- 同一套 transcript / replay / restore 真相源
- 同一套 supplement inbox 与 safe checkpoint 规则
- 同一套 host tool policy、approval 与事件投影

允许因方言而不同的，只应是 provider adapter 与 capability bridge：

- provider request/response 形状
- 是否支持 `previous_response_id`
- 是否支持 provider-native `web_search`
- 是否支持 richer output items

换句话说：

- 架构层保持统一
- continuation 机制可以按方言分能力
- tool surface 可以按 route 做暴露裁剪
- 但 supplement、checkpoint、replay、approval、恢复语义不能变成某个方言的私有机制

## 7. 详细实施方案

本章按能力层次展开，不代表实施顺序。

实际交付顺序见第 10 章：

1. `openai_responses` protocol
2. `openai_responses` route 上的 provider-native `web_search`
3. 宿主 `WebSearch` 的 OpenAI 搜索 backend

### 7.1 宿主搜索 backend：`OpenAiResponsesWebSearchProvider`

这是宿主搜索层的配套设计。

按本文建议的实施顺序，它应在 `openai_responses` 主路线稳定后落地。

#### 7.1.1 新增 provider id

在搜索设置层新增一个 provider id：

- `openai_web_search`

需要变更：

- [app/src/main/kotlin/com/opencray/app/WebSearchSettingsStore.kt](../app/src/main/kotlin/com/opencray/app/WebSearchSettingsStore.kt)
- 相关设置 facade / UI 枚举

#### 7.1.2 新增 backend 实现

新增类：

- `runtime/src/main/kotlin/com/opencray/runtime/web/OpenAiResponsesWebSearchProvider.kt`

职责：

- 实现 `WebSearchProvider`
- 内部调用 OpenAI Responses 的 `web_search`
- 把返回内容归一成 `WebSearchResult`

建议输出映射：

- `providerName = "openai-web-search"`
- `results[].title`
- `results[].url`
- `results[].snippet`
- 错误时返回统一 `errorCode / errorMessage`

#### 7.1.3 新增 transport 或 client

这里有两种实现方式。

方案 A，直接 HTTP：

- 在 runtime/web 层增加一个 OpenAI 专用 HTTP client
- 自己构造 Responses request
- 自己解析 search output

方案 B，复用 app provider transport：

- 在 app 层写 OpenAI search backend 适配器
- runtime 只看到 `WebSearchProvider`

推荐方案 B，原因：

- OpenAI API key、User-Agent、endpoint 这些配置已经主要在 app 层聚合
- 可以避免 runtime 模块过多感知具体厂商协议

#### 7.1.4 工厂重构

当前工厂只会组 `SequentialWebSearchProvider`。

建议改成：

- `AppConfiguredWebSearchProviderFactory.create(...)`
  - 根据 slot 组装多个 backend
  - 返回 `FallbackWebSearchProvider` 或扩展后的 `SequentialWebSearchProvider`

使其支持：

- OpenAI backend
- 第三方 backend
- 统一 fallback 顺序

#### 7.1.5 配置策略

v1 推荐默认策略：

- 复用主 OpenAI API key
- 可单独指定 search model
- 不要求单独配置第二份 key
- 仅 `openai_web_search` provider 暴露 `baseUrl` 配置
- 仅 `openai_web_search` provider 暴露 `model` 配置
- `exa / tavily / brave` 继续只保留各自现有的 `apiKey` 配置，不引入通用 `baseUrl` 字段
- `openai_web_search` 的 `model` 若留空，当前实现默认回落到 `gpt-5`

后续可扩展：

- 单独的 search key
- 针对搜索的 provider 优先级
- 根据当前主模型 provider 自动选 backend

#### 7.1.6 这一阶段的收益

完成后可立即获得：

- 任意主模型仍可调用宿主 `WebSearch`
- 宿主搜索 backend 可以改用 OpenAI 官方搜索
- 不需要立刻引入完整 `openai_responses` 主 route

### 7.2 新增独立方言：`openai_responses`

这是推荐先落地的第一阶段，也是 OpenAI 原生 agent 能力的核心。

#### 7.2.1 方言建模

当前 [app/src/main/kotlin/com/opencray/app/LlmProviderRequestSupport.kt](../app/src/main/kotlin/com/opencray/app/LlmProviderRequestSupport.kt) 只有：

- `openai`
- `anthropic`

需要显式扩成：

- `openai`
- `anthropic`
- `openai_responses`

推荐短期方案：

- 直接把 `openai_responses` 作为新的 `protocol` 值

长期更稳的方案：

- 将配置层拆成 `auth family + api dialect`
- 但这一步可以后做

#### 7.2.2 provider adapter 拆分

当前的 [app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt](../app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt) 已经同时承载：

- OpenAI chat completions
- Anthropic messages

如果继续把 Responses 塞进去，这个类会继续膨胀。

推荐重构为：

- `OpenAiChatCompletionsAdapter`
- `AnthropicMessagesAdapter`
- `OpenAiResponsesAdapter`
- 外层 dispatcher 型 provider client

外层 client 负责：

- 读取 route dialect
- 分发到具体 adapter

具体 adapter 负责：

- endpoint 构造
- request payload 映射
- response payload 解析

#### 7.2.3 request 映射

`OpenAiResponsesAdapter` 需要处理：

- `systemPrompt -> instructions`
- `messages -> input`
- `tools -> function tools`
- `previousResponseId -> previous_response_id`
- route 级开关控制是否加入 built-in `web_search`

这里不建议把 Responses route 简化成“再发一段文本 prompt”，而应该按 Responses 的结构去建模。

### 7.3 工具建模：区分 host function tools 与 provider built-in tools

当前 gateway 的 `tools` 更接近函数工具定义，不足以表达 OpenAI built-in tool。

推荐新增一层区分：

- host function tools
- provider built-in tools

可以采用其中一种建模。

方案 A：

- `LiteLlmToolDefinition` 保留为 function tool
- 额外新增 `LiteLlmBuiltinToolDefinition`

方案 B：

- 引入 `LiteLlmToolSpec` sealed interface

推荐方案 A，改动面更可控。

建议结构：

- `functionTools: List<LiteLlmToolDefinition>`
- `builtinTools: List<LiteLlmBuiltinToolDefinition>`

这样 `openai_responses` route 可以同时具备：

- host 文件/命令/编辑类工具
- provider-native `web_search`

### 7.4 Responses output item 映射

`OpenAiResponsesAdapter` 需要把 Responses output items 映射回当前 runtime 可消费的统一结构。

至少需要处理：

- reasoning item
- function call item
- message text item
- citation / annotation item
- built-in web search 相关 item

建议扩充 `LiteLlmStructuredCompletion` 或增加 companion metadata，用来承接：

- `citations`
- `providerEvents`
- `usedBuiltinWebSearch`

如果第一版暂时不想扩结构，也至少要保证：

- function call 能正确转成 `toolCalls`
- 最终文本能落到 `finalText`
- reasoning 不污染公开 progress

### 7.5 Continuation：provider lineage 与 runtime transcript 的桥接

这是第二阶段最关键的实现点。

#### 7.5.1 要新增的运行态状态

建议为当前 run / prompt checkpoint 保存：

- `lastProviderResponseId`
- `lastProviderLineageId`
- `continuationDialect`

#### 7.5.2 正常续接路径

当 route 为 `openai_responses` 且上一跳成功返回 `providerResponseId` 时：

- runtime 保存该值
- 下一跳自动把它写入 `LiteLlmGatewayRequest.previousResponseId`

这样就不需要每一跳都完整重放全部 provider 历史上下文。

#### 7.5.3 必须清空 lineage 的场景

出现下面情况时，必须放弃 `previous_response_id` 续接：

- route fallback
- 从 `openai_responses` 降级到 `openai`
- provider 返回无法可信解析的 payload
- runtime 切回 legacy JSON fallback
- transcript compaction 改写了 provider 可见边界
- 从旧 checkpoint 恢复，但当前 provider lineage 已不可确认

#### 7.5.4 回退策略

当 lineage 不可用时：

- runtime 退回 transcript replay 路径
- 不中断整个 agent loop
- 只丢失 provider-native continuation 增益，不丢宿主任务能力

### 7.6 `openai_responses` 下的 mid-loop supplement 桥接

`mid-loop supplement` 的 durability / replay 语义仍然属于宿主层，但 `openai_responses` 的续接方式应直接走 provider-native 路径：

- supplement inbox 是 session-scoped、run-targeted 的 durable 输入盒
- supplement 只在 safe checkpoint 被消费
- 当前 Phase 1 checkpoint 仍然是“下一次 LLM 请求前的 turn start”

配套背景设计见：

- [docs/chat-mid-loop-supplement-plan.md](./chat-mid-loop-supplement-plan.md)

#### 7.6.1 宿主侧规则保持不变

无论 route 是 `openai`、`anthropic` 还是 `openai_responses`，下面这些规则都不应该改变：

- 不中断已经发出的 in-flight provider request
- 不在工具执行中注入 supplement
- 不在 approval wait 中注入 supplement
- supplement 先写入 durable inbox
- 到 safe checkpoint 时，按到达顺序消费并写入 transcript
- 同时发出 supplement runtime event，保证 replay 和 UI 可恢复

这保证了：

- supplement 是 agent 架构层能力，而不是某个 provider 特例
- 用户感知与 replay 语义不会因方言切换而改变
- route 差异只体现在“下一跳怎么续接”，而不是“supplement 是否存在”

#### 7.6.2 `openai_responses` 路线下的续接方式

当当前 route 为 `openai_responses` 时，supplement 的消费分为两层：

第一层，宿主层：

- 在 turn start 消费 supplement
- 将 supplement 作为新的 `USER` transcript message 追加到本地 transcript
- 记录 `OpenCraySupplementEvent`

第二层，provider continuation 层：

- 如果 `lastProviderResponseId` 仍然可信，则下一跳请求使用 `previous_response_id`
- 并把本轮新增 supplement 作为新的 delta input 追加给 Responses

这意味着 `openai_responses` 下的 supplement 不是“改写已完成的历史请求”，而是：

- 沿用同一条 provider lineage
- 在下一跳请求中附加新的 user guidance

这里不需要再额外设计一层本地 continuation envelope 去包住 `openai_responses`。

对于这条 route，原生 continuation 本身就是首选实现。

#### 7.6.3 supplement 何时使 lineage 失效

只要 supplement 仍然是在 safe checkpoint 注入，它本身不应自动导致 lineage 失效。

真正需要清空 `previous_response_id` 的，是下面这些情况：

- route fallback
- provider payload 无法可信解析
- 从 `openai_responses` 降级到其他方言
- compaction 改写了 provider 可见历史边界
- 从 durable checkpoint 恢复时无法确认 provider lineage 一致性

因此规则应是：

- “有 supplement” 不等于 “必须重放全 transcript”
- 是否沿用 lineage 取决于 continuation state 是否可信

#### 7.6.4 fallback 语义

如果 `openai_responses` 下的 continuation state 不可信，则：

- supplement 仍然先写入 transcript
- runtime 放弃 `previous_response_id`
- 下一跳退回 transcript-first 的 provider request 重建

这样做的结果是：

- supplement 语义不丢
- Claude Code 式“中途补一句话”能力仍然存在
- 只是暂时失去 Responses-native continuation 的效率收益

#### 7.6.5 建议的实现边界

推荐把 supplement 设计拆成两层，避免把 durability 语义和 continuation 机制混在一起：

- 通用层：
  - supplement inbox
  - checkpoint gating
  - transcript append
  - replay event
- route-specific continuation 层：
  - `openai_responses` 读取 `lastProviderResponseId`
  - 直接决定下一跳是 provider-native lineage continuation 还是 transcript replay
  - 非 Responses route 再决定是否使用本地 continuation envelope

这能保证：

- supplement durability 机制只有一套
- `openai_responses` 可以直接使用原生 continuation
- 其他方言继续使用 transcript-first 或本地 continuation 路径

#### 7.6.6 Anthropic native 路线的边界

Anthropic native route 不应被视为具备和 `openai_responses` 同等级的 provider lineage continuation。

当前建议是：

- Anthropic 没有通用的 `previous_response_id` 等价物
- 因此不能把“mid-loop supplement”设计成 Anthropic 私有的 response lineage 续接
- Anthropic 只能在严格的 tool boundary 利用其原生消息结构做局部优化

可用的 Anthropic-native 优化边界是：

- 当前 assistant 刚返回 `tool_use`
- runtime 正在构造紧随其后的 `tool_result`
- 如果此时有新的用户补充说明，允许把该说明追加在同一个 `tool_result` user turn 内

不应做的事：

- 不要在普通 running 状态下伪造 Anthropic lineage continuation
- 不要在 approval wait 中把 supplement 注入同一 suspended request
- 不要把 Anthropic-native tool boundary 扩张成通用的“任意时刻追加 delta”

因此 Anthropic 路线应是：

- tool-result 边界：优先走 Anthropic-native message composition
- 其他边界：继续走共享 supplement inbox + transcript-first continuation

#### 7.6.7 非 Responses 路线的本地 continuation 设计

对于 `openai`、`anthropic` 以及其他非 Responses 路线，推荐新增一层 runtime-owned 的本地 continuation 优化，而不是试图发明一个 provider 假 lineage token。

建议模型：

- `supplement inbox` 仍然是唯一的 durable 输入盒
- transcript / replay 仍然是真相源
- 在 safe checkpoint 上，runtime 额外维护一个 durable `checkpointed continuation envelope`

这一层只保存 prompt-visible continuation state，例如：

- 当前 checkpoint class
- 当前 task prompt / direction anchor
- 当前 transcript frontier
- 最近工具 frontier
- active skill / approval resume / subagent resume
- 已消费 supplement cursor

它不应保存：

- hidden reasoning
- provider 私有 opaque state
- UI-only projection

这样非 Responses 路线在消费 supplement 时可以：

1. 先按通用规则把 supplement 写入 transcript 与 replay
2. 优先从本地 continuation envelope 继续下一跳
3. 只有 envelope 不可信时，才退回完整 transcript-first rebuild

这条路线的意义是：

- 不改变 supplement 语义
- 不制造第二套 agent 架构
- 让非 Responses route 也更接近 Claude Code 式的 mid-loop continuation 体验

## 8. OpenAI Responses route 的工具暴露策略

### 8.1 默认策略

推荐默认行为如下：

- `openai_responses` 且启用原生搜索：
  - 不暴露宿主 `WebSearch`
  - 暴露 provider-native `web_search`
- `openai_responses` 但关闭原生搜索：
  - 暴露宿主 `WebSearch`
- `openai` / `anthropic` / 其他兼容 route：
  - 暴露宿主 `WebSearch`

### 8.2 原因

这样做可以避免：

- 模型同时看到两个“搜索互联网”的工具
- prompt 需要加入复杂的优先级说明
- 结果格式混用导致运行态判断困难

### 8.3 调试开关

调试期可以临时提供：

- `dualExposeWebSearch=true`

仅用于：

- 对比 provider-native 搜索与宿主搜索的行为差异
- 验证 prompt 引导是否稳定

不建议作为默认生产行为。

## 9. schema fidelity 与 strict 策略

当前工具 schema 还存在一个现实约束：

- `AgentToolDefinition.toJsonSchema()` 目前只支持较薄的一层 schema
- `object[]` 不会继续展开内部字段约束

代码位置：

- [runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt](../runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt)

因此第一版不建议：

- 一上来把所有复杂工具都以 strict function schema 暴露给 Responses

推荐策略：

- 第一阶段优先验证参数简单的工具
- 暂不默认开启复杂工具 strict
- 在验证稳定后，再逐步提升 schema fidelity

## 10. 分阶段交付计划

### 10.1 PR1：接入 `openai_responses` 独立方言

范围：

- 新增 `openai_responses` route / protocol
- 拆 provider adapter
- 打通 `previous_response_id`
- 解析基础 Responses output items
- 将现有 supplement checkpoint 语义桥接到 Responses continuation

第一版目标：

- 支持 function call
- 支持 final text
- 支持 reasoning 提取
- 支持 continuation state 保存
- 支持 turn-start supplement 在 lineage 可信时走 Responses-native continuation

结果：

- OpenAI route 获得独立的 Responses 协议面
- runtime 能沿用 provider lineage 做基础 continuation
- 宿主搜索层暂时保持不变，便于单独验证协议主线

暂不要求：

- citation 全量映射
- 宿主搜索 backend 改造

### 10.2 PR2：在 `openai_responses` route 上启用 provider-native `web_search`

范围：

- 新增 built-in tool 建模
- route 级搜索工具暴露策略
- citation / annotation 归一
- diagnostics 与回退策略补齐

结果：

- OpenAI 模型在该 route 上优先使用原生搜索
- 其他模型不受影响

当前实现检查点（2026-03-24，本地已落地并完成定向单测）：

- `LiteLlmGatewayRequest` 已补 `builtinTools: List<LiteLlmBuiltinToolDefinition>`
- runtime 已区分 host function tool 与 provider built-in tool
- 当前只落地了一种 provider built-in tool：
  - `LiteLlmBuiltinToolType.WEB_SEARCH`
- `openai_responses` route 下，runtime 的默认判定逻辑为：
  - 若 `llmMetadata["nativeWebSearchEnabled"]` 显式为 `true/false`，则优先使用该值
  - 否则只有 `protocol=openai_responses` 且 `_host.providerId=openai` 时，默认启用 provider-native `web_search`
  - 自定义 `openai_responses` provider 默认不会自动获得 built-in search
- 当 provider-native `web_search` 启用时：
  - runtime 注入 Responses built-in `web_search`
  - 默认隐藏宿主 `WebSearch`
  - 若 `llmMetadata["dualExposeWebSearch"]=true`，则仅在调试场景双暴露
- 当前 Responses request 已按 OpenAI 原生格式发出：
  - `tools: [{ "type": "web_search" }, ...function tools]`
  - 当请求源引用时附带 `include: ["web_search_call.action.sources"]`
- 当前 diagnostics / observability 已补齐最小闭环：
  - `builtinWebSearchRequested`
  - `builtinWebSearchUsed`
  - `providerCitationCount`
- 当前 PR2 仍刻意保持一个统一 agent loop：
  - 只是在同一 runtime 架构里切换 tool exposure 与 Responses 请求体
  - 没有把 `openai_responses` 单独做成第二套 agent/supplement/replay 架构

当前 PR2 明确未包含：

- 宿主 `openai_web_search` backend
- `WebSearchSettingsStore` / `AppConfiguredWebSearchProviderFactory` 的 OpenAI 搜索后端接入
- 任意主模型共享 OpenAI 官方搜索 backend 的能力

对应项留给 PR3，避免把 “Responses route 的原生 built-in 搜索” 和 “宿主搜索后端替换” 混在一个阶段里排查。

### 10.3 PR3：宿主搜索 backend 接入 OpenAI 官方搜索

范围：

- 新增 `openai_web_search` provider id
- 新增 `OpenAiResponsesWebSearchProvider`
- 重构 `AppConfiguredWebSearchProviderFactory`
- 新增对应设置项与单测

结果：

- 任意主模型仍能通过宿主 `WebSearch` 搜索
- 宿主搜索 backend 可由 OpenAI 官方搜索承担
- 搜索 backend 与 `openai_responses` 主路线解耦，可独立 fallback 与验证

## 11. 受影响模块与建议改动点

### 11.1 `app/`

主要改动点：

- [app/src/main/kotlin/com/opencray/app/LlmProviderRequestSupport.kt](../app/src/main/kotlin/com/opencray/app/LlmProviderRequestSupport.kt)
- [app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt](../app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt)
- [app/src/main/kotlin/com/opencray/app/WebSearchSettingsStore.kt](../app/src/main/kotlin/com/opencray/app/WebSearchSettingsStore.kt)
- [app/src/main/kotlin/com/opencray/app/AppConfiguredWebSearchProviderFactory.kt](../app/src/main/kotlin/com/opencray/app/AppConfiguredWebSearchProviderFactory.kt)
- `facade/llm` 与 `facade/search` 相关配置入口

建议：

- 将 provider adapter 拆分为单职责实现
- 保持 app 层承接厂商配置与 transport 聚合

### 11.2 `llm/`

主要改动点：

- [llm/src/main/kotlin/com/opencray/llm/LiteLlmGateway.kt](../llm/src/main/kotlin/com/opencray/llm/LiteLlmGateway.kt)

建议：

- 补 provider built-in tool 建模
- 视需要扩展结构化 completion 的 citation / provider event 字段

### 11.3 `runtime/`

主要改动点：

- [runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt](../runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt)
- [runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt](../runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt)
- [runtime/src/main/kotlin/com/opencray/runtime/web/WebSearchProvider.kt](../runtime/src/main/kotlin/com/opencray/runtime/web/WebSearchProvider.kt)

建议：

- 增加 continuation state 管理
- 增加 route 级工具暴露策略
- 保持 transcript replay 为 durable source of truth

## 12. 测试矩阵

### 12.1 provider adapter 单测

- `openai_responses` 请求正确写入 `previous_response_id`
- Responses output 中 reasoning 能被提取
- Responses output 中 function call 能被映射成 `toolCalls`
- Responses output 中 final text 能被映射成 `finalText`
- citation / annotation 至少不导致解析失败

### 12.2 runtime 单测

- `openai_responses`: function call -> tool result -> continuation -> final
- `openai_responses`: turn-start supplement -> continuation with `previous_response_id`
- `openai_responses`: supplement consumed but lineage invalid -> transcript replay fallback
- `openai_responses`: lineage 失效 -> transcript replay fallback
- `openai_responses`: 开启 provider-native 搜索时不暴露宿主 `WebSearch`
- `openai_responses`: 关闭 provider-native 搜索时继续暴露宿主 `WebSearch`
- cross-dialect invariant: supplement 仍然只在 safe checkpoint 注入，不因方言不同改变 host 规则

### 12.3 宿主搜索 backend 单测

- `OpenAiResponsesWebSearchProvider` 成功归一结果
- domain filter 正确映射
- provider 错误正确归一
- backend fallback 顺序正确

### 12.4 配置与验证测试

- `openai_web_search` 可正确保存和读取
- `openai_responses` route 与普通 `openai` route 可区分
- response-api-capable route 与 chat-only route 可区分

## 13. 风险与控制

### 13.1 最大风险

最大风险不是“Responses 本身太复杂”，而是：

- 把 Responses 当成现有 `openai` 分支里的补丁条件堆进去
- 导致 chat/messages/responses 三种协议长期共享一个越来越难维护的 giant client

### 13.2 主要控制手段

- 把 `openai_responses` 作为显式方言
- 拆 provider adapter
- 先做主 route，再做宿主搜索 backend
- 第一版不要把所有复杂工具都切 strict
- transcript 与 lineage 双轨并存，而不是只信任 provider continuation

## 14. 推荐默认配置

v1 推荐默认值：

- 宿主 `WebSearch` 保持启用
- 宿主搜索 backend 允许配置为 `openai_web_search`
- `openai_responses` route 默认开启 `responseApiPreferred`
- `openai_responses` route 若启用原生搜索，则默认隐藏宿主 `WebSearch`
- OpenAI 搜索 backend 默认复用主 OpenAI API key

## 15. 验收标准

满足下面几项，可以认为方案首版落地成功：

- 非 OpenAI 主模型仍可通过宿主 `WebSearch` 搜索
- 宿主 `WebSearch` 可切到 OpenAI 官方搜索 backend
- `openai_responses` route 能完成多跳 function tool 调用
- `openai_responses` route 能自动续接 `previous_response_id`
- `openai_responses` route 能在 safe checkpoint 消费 mid-loop supplement，并在 lineage 可信时继续沿用同一条 Responses thread
- `openai_responses` route 在启用原生搜索时默认优先走 provider-native `web_search`
- lineage 丢失时系统仍可通过 transcript replay 保持任务继续执行
- 不同方言不会形成两套 supplement / replay / approval 架构

## 16. 与现有文档的关系

本文档是下面两份文档的实施落地补充：

- [docs/agent-native-tool-calling-protocol-plan.md](./agent-native-tool-calling-protocol-plan.md)
- [docs/codex-gap-analysis.md](./codex-gap-analysis.md)

它解决的是“怎么做”，而不是只回答“差距在哪里”或“长期路线是什么”。

## 17. 外部参考

- OpenAI Responses migration guide:
  - <https://platform.openai.com/docs/guides/migrate-to-responses>
- OpenAI conversation state / `previous_response_id`:
  - <https://platform.openai.com/docs/guides/conversation-state?api-mode=responses>
- OpenAI web search guide:
  - <https://platform.openai.com/docs/guides/tools-web-search?api-mode=responses>
