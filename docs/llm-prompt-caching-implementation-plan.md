# LLM Prompt Caching Implementation Plan

Last updated: 2026-04-04

## 0. 实施状态快照

截至 2026-04-04，本计划对应的代码推进状态如下：

- PR1 已落地：
  - 已新增统一缓存观测 metadata key
  - 已解析 OpenAI / Responses `cached_tokens`
  - 已解析 Anthropic cache read / write usage
  - 已补对应单测
- PR1 已做过一次目标单测验证，命令为：
  - `$env:GRADLE_USER_HOME='D:\codes\MobileProjects\OpenCray\.gradle-user-codex-local'; $env:ANDROID_USER_HOME='D:\codes\MobileProjects\OpenCray\.android'; .\gradle-8.13\bin\gradle.bat --no-daemon '-Dorg.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=1g' '-Dorg.gradle.vfs.watch=false' :app:testDebugUnitTest --tests com.opencray.app.OpenAiCompatibleLiteLlmProviderClientTest`
- PR2 已落地并完成目标单测验证：
  - 已在 `openai` / `openai_responses` 请求体中按条件注入 `prompt_cache_key`
  - 已在 `openai` / `openai_responses` 请求体中按条件注入 `prompt_cache_retention`
  - 已把请求侧缓存痕迹写入 diagnostics / success metadata
  - 已补 `chat/completions`、`responses`、自定义 OpenAI-compatible 显式开启 / 默认关闭 的测试
- PR3 已落地 MVP 并完成目标单测验证：
  - 已为 Anthropic 路线接入顶层 `cache_control`
  - 已通过 route metadata 控制 Anthropic prompt caching 开关与 `5m` / `1h` TTL
  - 已把 Anthropic 请求侧缓存控制痕迹写入 diagnostics / success metadata
  - 已补 Anthropic `cache_control` 请求测试
- PR4 已完成后端配置链路接线：
  - 已在 `LlmSettingsState` / `LlmSettingsStore` 中持久化 OpenAI / Anthropic prompt caching 设置
  - 已在 `LlmConfigFacade` 的 load/save 路径中接入这些设置
  - 已让 `effectiveLlmRouteMetadata(...)` 和 runtime 路由生成携带这些缓存 metadata
  - 已把 settings gateway / host runtime / local runtime server 的 LLM 配置读写协议补上对应字段
- PR5 已完成 Flutter 设置页暴露与桥接测试：
  - 已在 Flutter LLM 配置快照、settings facade、platform bridge、local runtime bridge 中接入缓存字段
  - 已在 Flutter LLM 设置页新增 provider-specific 的 prompt cache 卡片
  - OpenAI / OpenAI Responses 路线已暴露 cache key scope 与 retention 选择
  - Anthropic 路线已暴露 prompt caching 开关与 `5m` / `1h` TTL 选择
  - 已补 Flutter settings page、platform bridge、local runtime bridge 的定向测试
- 本轮继续实施时，重新核对了 Anthropic 官方 prompt caching 文档：
  - 当前官方已经支持顶层 `cache_control` 的 automatic prompt caching
  - 第一版 PR3 可以先走顶层 `cache_control`，不必一上来就为了缓存重构成显式 block 断点
  - 当前文档没有把 1h TTL 描述成必须依赖额外 `anthropic-beta` header
- 2026-04-04 已完成一次针对 provider client 的目标验证：
  - `:app:compileDebugKotlin`
  - `:app:testDebugUnitTest --tests com.opencray.app.OpenAiCompatibleLiteLlmProviderClientTest`
- 2026-04-04 已完成一次针对 PR4 配置链路的目标验证：
  - `:app:compileDebugKotlin`
  - `:app:testDebugUnitTest --tests com.opencray.app.LlmSettingsStoreTest --tests com.opencray.app.LlmProviderRequestSupportTest --tests com.opencray.app.LlmRouteCapabilityMetadataTest --tests com.opencray.app.facade.llm.LlmConfigFacadeTest --tests com.opencray.app.OpenAiCompatibleLiteLlmProviderClientTest`
  - 结果通过，说明设置持久化、route metadata 生成、Facade 配置流转，以及 provider client 缓存请求/观测链路当前都已回归通过
- 2026-04-04 已完成一次针对 Flutter 暴露层的目标验证：
  - `dart analyze flutter_app`
  - `flutter test test/settings_feature_test.dart test/opencray_platform_bridge_test.dart test/opencray_local_runtime_bridge_test.dart`
  - 由于当前 Codex sandbox 内 `flutter test` 可能卡住，本轮实际是在沙箱外从 `flutter_app/` 目录重跑
  - 结果通过，说明 Flutter 设置页、MethodChannel 桥接，以及本地 runtime HTTP bridge 的 prompt caching 字段链路当前已回归通过
- 本地 Windows / Gradle 环境仍有增量构建锁文件问题：
  - 需要在重跑前偶发性清理 `app/build/tmp/kotlin-classes/*`、`runtime/build/tmp/kotlin-classes/*`
  - 有时还需要结束残留 `java.exe` 进程后再重跑
  - 这属于当前工作区的构建环境噪音，不再阻塞 PR2 / PR3 的目标验证结论
- 2026-04-04 本轮验证过程中，还顺手修掉了一处当前工作区里会阻塞编译的无关 Kotlin 类型推断问题：
  - `app/src/main/kotlin/com/opencray/app/E2BEnvdNativeCommandExecution.kt`
  - 修复方式是把一段 `mapOf(...)` 的内联值表达式拆成局部变量，消除 `Serializable` / `Pair` 推断歧义
  - 该修复不属于 prompt caching 方案本身，但它确实是本轮完成编译验证所必需的前置清障

## 1. 结论先行

这份计划基于一轮比较深入的静态调查，但还不是最终的 live 接口验收结论。

截至 2026-04-03，已经确认的事实有：

- 当前仓库没有显式的 prompt caching 配置、请求参数注入、缓存命中指标解析，也没有用户可见的缓存观测面。
- 当前仓库已经有一套按 route 维度持久化能力快照的机制，可以复用，但不应该为了“缓存”再引入一个新协议值。
- OpenAI 官方 prompt caching 目前是自动生效的，所以对 OpenAI / `openai_responses` 而言，“支持缓存”的第一步不是发明一个新协议，而是把缓存观测和可选优化参数接进现有链路。
- Anthropic 的 prompt caching 需要显式请求形状支持，当前代码还不具备这一层，所以对 Anthropic 而言，需要真实改请求结构，而不是只改 metadata。

这份计划的调查深度包括：

- 已审计当前代码路径：
  - `app/src/main/kotlin/com/opencray/app/LlmSettingsStore.kt`
  - `app/src/main/kotlin/com/opencray/app/LlmProviderRequestSupport.kt`
  - `app/src/main/kotlin/com/opencray/app/LlmAgentCapabilitySupport.kt`
  - `app/src/main/kotlin/com/opencray/app/LlmModelCapabilityRegistry.kt`
  - `app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt`
  - `app/src/main/kotlin/com/opencray/app/facade/llm/LlmConfigFacade.kt`
  - `app/src/main/kotlin/com/opencray/app/AppAgentSessionTaskRuntimeFactory.kt`
  - `llm/src/main/kotlin/com/opencray/llm/LiteLlmMetadataKeys.kt`
- 已核对仓库现有设计文档：
  - `docs/openai-responses-web-search-implementation-plan.md`
  - `docs/agent-native-tool-calling-protocol-plan.md`
  - `docs/codex-gap-analysis.md`
  - `docs/agent-media-message-plan.md`
- 已核对官方文档：
  - OpenAI Prompt Caching guide
  - OpenAI Responses API reference
  - Anthropic Prompt Caching guide
  - Anthropic Messages API reference

还没有做的事情：

- 没有在本次会话里对真实 OpenAI / Anthropic 账户做 live 端到端请求验证。
- 没有对第三方 OpenAI-compatible 代理逐个验证它们是否接受 `prompt_cache_key` 或 `prompt_cache_retention`。

所以，这是一份可以进入实现阶段的设计文档，但第一批代码仍然要配合 provider smoke test 落地。

## 2. 当前现状审计

### 2.1 设置层没有缓存配置

`LlmSettingsState` 当前只覆盖：

- `protocol`
- `model`
- `reasoningEffort`
- `systemPrompt`
- `agentCapability`

没有任何 prompt cache 相关字段，也没有“高级 LLM 设置”里的缓存开关或策略。

代码位置：

- `app/src/main/kotlin/com/opencray/app/LlmSettingsStore.kt`

### 2.2 route metadata 里没有缓存语义

当前 `LlmProviderProtocols.routeMetadata(...)` 只负责：

- `protocol`
- `responseApiPreferred`
- `reasoning_effort`
- `thinking_budget_tokens`

没有：

- `promptCachingEnabled`
- `promptCacheRetention`
- `promptCacheKeyStrategy`
- `anthropicPromptCachingEnabled`

代码位置：

- `app/src/main/kotlin/com/opencray/app/LlmProviderRequestSupport.kt`
- `app/src/main/kotlin/com/opencray/app/LlmModelCapabilityRegistry.kt`

### 2.3 provider client 没有接缓存请求参数，也没有解析缓存 usage

当前 `OpenAiCompatibleLiteLlmProviderClient` 已按协议分支处理三条路径：

- OpenAI Chat Completions
- OpenAI Responses
- Anthropic Messages

但没有以下能力：

- OpenAI / Responses 请求里注入 `prompt_cache_key`
- OpenAI / Responses 请求里注入 `prompt_cache_retention`
- Anthropic 请求里构造 `cache_control`
- Anthropic 1h TTL 时注入 `anthropic-beta`
- 从 OpenAI `usage.prompt_tokens_details.cached_tokens` 里提取缓存命中
- 从 Anthropic `usage.cache_creation_input_tokens` / `usage.cache_read_input_tokens` 或更细分 usage 字段里提取缓存读写

代码位置：

- `app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt`

### 2.4 metadata key 还没有缓存指标

当前 `LiteLlmMetadataKeys` 里没有缓存相关 key，所以即便 provider 返回 usage，也无法稳定透传到 runtime、日志或 UI。

代码位置：

- `llm/src/main/kotlin/com/opencray/llm/LiteLlmMetadataKeys.kt`

### 2.5 已有的能力缓存机制可以复用，但不应该过度设计

当前已经有按 `(protocol, baseUrl, model)` 路由指纹存储的 `LlmAgentCapabilitySnapshot`，并会把能力位转换成 route metadata / runtime metadata。

这意味着：

- 如果后续真的需要探测“某个自定义 OpenAI-compatible 服务是否接受缓存 hint”，可以复用现有能力缓存。
- 但第一版不需要为了“缓存”新增一个 `openai_cache` 或 `anthropic_cache` 协议。

代码位置：

- `app/src/main/kotlin/com/opencray/app/LlmAgentCapabilitySupport.kt`
- `app/src/main/kotlin/com/opencray/app/LlmSettingsStore.kt`
- `app/src/main/kotlin/com/opencray/app/LlmModelCapabilityRegistry.kt`

## 3. 官方行为结论

### 3.1 OpenAI

根据 2026-04-03 查阅的 OpenAI 官方文档：

- Prompt Caching 对近期模型默认自动开启，不要求代码改动才能发生缓存命中。
- 缓存命中依赖 prompt 的精确前缀匹配。
- `Responses.create` 和 `chat.completions.create` 都支持 `prompt_cache_retention`。
- `Responses.create` 文档当前明确列出了 `prompt_cache_key`。
- `usage.prompt_tokens_details.cached_tokens` 是最直接的缓存命中观测字段。

这意味着对 OpenAI / `openai_responses` 路线而言：

- 第一阶段重点是“观测 + 结构优化”。
- 第二阶段才是“可选 hint 注入”。
- 不需要新增协议。

### 3.2 Anthropic

根据 2026-04-03 在本次实施中重新核对的 Anthropic 官方文档：

- Prompt caching 现在同时支持：
  - 顶层 `cache_control` 的 automatic prompt caching
  - 基于结构化 block 的 explicit cache breakpoints
- 默认 TTL 为 5 分钟。
- 1 小时 TTL 可以通过 `cache_control.ttl=1h` 请求。
- 当前官方文档没有把 1 小时 TTL 描述成必须依赖额外 `anthropic-beta` header。

这意味着对 Anthropic 路线而言：

- 不能只在 metadata 里补一个布尔值，仍然需要真实改请求体。
- 第一版可以先实现顶层 `cache_control`，用 automatic caching 打通最小可用链路。
- 如果后续需要精细控制缓存断点，再继续重构 `system` 和 message block 的显式 breakpoint 方案。
- 由于 Anthropic cache write 会改变计费结构，是否默认开启仍需谨慎。

## 4. 目标与非目标

### 4.1 目标

- 在不新增协议值的前提下，为现有 `openai`、`openai_responses`、`anthropic` 路线补足 prompt caching 支持。
- 让 runtime、日志和后续 UI 能看见缓存是否发生、命中了多少 token。
- 为 OpenAI 官方路线补上可选的 cache key / retention 优化接口。
- 为 Anthropic 路线补上真正可工作的 prompt caching 请求构造。

### 4.2 非目标

- 不做“本地回答结果缓存”来替代 provider prompt caching。
- 不把 prompt caching 抽象成一个新的 `protocol`。
- 不在第一版就为所有 OpenAI-compatible 第三方代理做完整能力探测。
- 不承诺第一版就把缓存指标完整展示到所有 UI 页面；第一版只要求 metadata 链路打通。

## 5. 设计原则

### 5.1 不新增协议

缓存是现有协议下的能力，不是新的协议面。

保留现有：

- `openai`
- `openai_responses`
- `anthropic`

不要新增：

- `openai_cache`
- `anthropic_cache`
- `responses_cache`

### 5.2 OpenAI 和 Anthropic 分开实现，不做伪统一

两家 provider 的缓存语义不同：

- OpenAI：自动缓存 + 可选 routing / retention hint
- Anthropic：显式 `cache_control` 断点 + provider 定义的 TTL 语义

所以第一版应共享“观测指标名”和“配置入口位置”，但不要强行共享请求语义。

### 5.3 先观测，再优化，再默认开启

推荐实施顺序：

1. 打通缓存指标观测
2. 给 OpenAI 路线补可选优化
3. 给 Anthropic 路线补显式缓存请求
4. 观察真实命中效果后，再决定是否默认启用某些策略

### 5.4 Anthropic 默认不直接全局开启

原因：

- 它不是“自动命中免费优化”，而是会引入 cache write 计费。
- 当前仓库还没有用户可见的成本说明和控制面。
- 第一版应该先以 route metadata 或实验开关启用，避免 silently 改变成本行为。

## 6. 具体改造方案

### 6.1 Phase 0: 先补缓存观测，不改默认行为

### 6.1.1 新增 metadata key

在 `llm/src/main/kotlin/com/opencray/llm/LiteLlmMetadataKeys.kt` 新增：

- `PROVIDER_PROMPT_CACHE_USED`
- `PROVIDER_PROMPT_CACHE_READ_TOKENS`
- `PROVIDER_PROMPT_CACHE_WRITE_TOKENS`
- `PROVIDER_PROMPT_CACHE_WRITE_5M_TOKENS`
- `PROVIDER_PROMPT_CACHE_WRITE_1H_TOKENS`
- `PROVIDER_PROMPT_CACHE_RETENTION`
- `PROVIDER_PROMPT_CACHE_KEY_PRESENT`

其中：

- OpenAI 的 `cached_tokens` 映射到 `READ_TOKENS`
- Anthropic 的 `cache_read_input_tokens` 映射到 `READ_TOKENS`
- Anthropic 的 cache creation 字段映射到 `WRITE_*`

### 6.1.2 扩展 response metadata 解析

在 `OpenAiCompatibleLiteLlmProviderClient.responseMetadata(...)` 中：

- OpenAI / Responses：
  - 尝试读取 `usage.prompt_tokens_details.cached_tokens`
  - 命中时写入 `PROVIDER_PROMPT_CACHE_USED=true`
  - 即使为 `0` 也可以保留 key，方便上层明确看到“无命中”
- Anthropic：
  - 尝试读取 `usage.cache_read_input_tokens`
  - 尝试读取 `usage.cache_creation_input_tokens`
  - 如果响应结构已经升级成细分 `cache_creation` 对象，则同时兼容 5m / 1h 两种字段

### 6.1.3 请求侧 diagnostics 先补基础痕迹

在 `requestDiagnosticsMetadata(...)` 中追加：

- 当前请求是否显式发送了 cache key
- 当前请求是否显式发送了 retention
- 当前请求是否启用了 Anthropic `cache_control`

这一步的目标不是改变行为，而是为后续调试提供事实。

### 6.1.4 测试

更新：

- `app/src/test/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClientTest.kt`

新增覆盖：

- OpenAI 返回 `cached_tokens` 时 metadata 正确写出
- OpenAI 返回 `cached_tokens=0` 时 metadata 仍稳定
- Anthropic 返回 `cache_read_input_tokens` / `cache_creation_input_tokens` 时 metadata 正确写出
- Anthropic 返回细分 creation usage 时 metadata 正确聚合

### 6.2 Phase 1: OpenAI / `openai_responses` 最小可用支持

### 6.2.1 先承认一个事实

对于官方 OpenAI，基础 prompt caching 已自动存在。

所以“支持缓存”的 MVP 定义应是：

- 观测命中
- 稳定前缀结构
- 可选注入 `prompt_cache_key`
- 可选注入 `prompt_cache_retention`

而不是假装“以前完全没有缓存，现在从零发明缓存”。

### 6.2.2 不新增用户开关，先走 route metadata

第一版最初可以先不修改设置页 UI，而是先通过 route metadata 接入：

- `promptCacheKeyStrategy`
- `promptCacheRetention`

推荐值：

- `promptCacheKeyStrategy=none|route|session`
- `promptCacheRetention=in_memory|24h`

初始默认：

- `promptCacheKeyStrategy=none`
- `promptCacheRetention` 不显式发送

原因：

- OpenAI 自动缓存本来就存在，不加参数也能命中。
- 先把能力做出来，再决定是否需要 UI 暴露。

补充：截至 2026-04-04，这部分已经在 Flutter LLM 设置页里补了一个独立的 prompt cache 卡片，但字段语义仍保持 provider-specific，没有引入伪统一开关。

### 6.2.3 `prompt_cache_key` 的生成策略

建议不要直接把 `sessionId` 当唯一方案，也不要把整段 prompt hash 后每次变成不同值。

推荐支持两种策略：

- `route`
  - 基于 `(providerId, protocol, baseUrl, model)` 稳定生成
  - 适合共享静态 system/tool 前缀的场景
- `session`
  - 基于 `(routeFingerprint, sessionId)` 生成
  - 适合同一会话内反复重放大前缀的场景

不推荐第一版做：

- `request`
  - 粒度太细，几乎等于放弃缓存聚合

### 6.2.4 `prompt_cache_retention` 的使用策略

建议第一版只在明确条件下发送：

- 协议为 `openai_responses` 或 `openai`
- provider 明确是官方 OpenAI，或者后续 capability 明确支持
- route metadata 指定了 retention

默认不向所有 OpenAI-compatible 自定义服务发送 `prompt_cache_retention`，因为很多代理未必兼容。

### 6.2.5 结构优化

即使不发送额外字段，也应检查 OpenAI 请求前缀是否稳定：

- system prompt 固定放在最前
- 工具定义顺序稳定
- 同一组 builtin tools 输出顺序稳定
- 避免在静态前缀里混入 run-specific metadata 或时间戳

如果未来发现 tool definition 或 schema 顺序不稳定，应优先修这个问题，因为它会直接破坏缓存命中。

### 6.2.6 代码改动点

主要文件：

- `app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt`
- `app/src/main/kotlin/com/opencray/app/LlmProviderRequestSupport.kt`
- `app/src/main/kotlin/com/opencray/app/LlmModelCapabilityRegistry.kt`
- `app/src/main/kotlin/com/opencray/app/AppAgentSessionTaskRuntimeFactory.kt`

具体动作：

- 在 route metadata 里增加可选缓存策略字段
- 在 OpenAI request body builder 里按条件注入 `prompt_cache_key`
- 在 OpenAI request body builder 里按条件注入 `prompt_cache_retention`
- 在 request diagnostics / response metadata 中记录这些字段是否存在

### 6.2.7 测试

新增覆盖：

- `chat/completions` 请求在官方 OpenAI 路线下正确注入 `prompt_cache_key`
- `responses` 请求正确注入 `prompt_cache_key`
- 指定 `24h` retention 时请求体正确
- 非官方 OpenAI-compatible 路线默认不发 retention
- diagnostics metadata 能反映 key / retention 是否发送

### 6.3 Phase 2: Anthropic prompt caching 显式接入

### 6.3.1 这是第一版里最实质的改造

当前 Anthropic builder 的关键问题仍然是：

- 没有任何 prompt caching 请求字段
- `system` / `messages` 虽然能正常工作，但还没有缓存控制入口

不过按照当前官方文档，第一版不必直接从“显式缓存断点建模”起步。

更现实的 MVP 是：

- 先接入顶层 `cache_control`
- 先让 Anthropic automatic prompt caching 能被显式开启
- 后续再决定是否继续细化到 block 级 explicit cache breakpoints

### 6.3.2 第一版不要做“全会话缓存智能断点”

那会牵涉：

- transcript 分段
- 旧消息与新消息的边界选择
- 工具结果回放与缓存层次关系
- 中途补流、replay、continuation 的一致性

这一步太大，不适合第一批实现。

在 automatic prompt caching 路线下，第一版不再要求手工挑断点。

因此 PR3 的范围可以收敛为：

- 通过 route metadata 开启顶层 `cache_control`
- 让 Anthropic 自己在请求前缀上做 automatic caching
- 先把请求侧控制和响应侧观测打通

显式断点建模保留给后续增强版。

### 6.3.3 需要引入的内部建模

建议在 provider client 内部引入一个轻量建模，而不是直接散落字符串：

- `AnthropicCachePolicy`
- `AnthropicCacheBreakpoint`
- `AnthropicCacheableBlock`

只在 `OpenAiCompatibleLiteLlmProviderClient` 内部使用即可，不需要上升到 repo-wide 抽象。

目标是让下面三件事可控：

- 哪些 block 可缓存
- 哪个 block 带 `cache_control`
- 当前使用 5m 还是 1h TTL

### 6.3.4 第一版先走顶层 `cache_control`

当前 `buildAnthropicRequestBody(...)` 直接把 `systemPrompt` 放成字符串，这在顶层 automatic caching 路线下仍然可以保持兼容。

第一版建议：

- 当未启用 Anthropic prompt caching 时，保持当前行为
- 当启用时，在请求顶层增加 `cache_control`
- 不在 PR3 里同时引入 `system` block 数组化改造

### 6.3.5 1h TTL 必须显式受控

Anthropic 1h TTL 会影响：

- 计费
- 兼容性

所以第一版建议：

- 默认只支持 5m
- 1h 仅通过实验 route metadata 启用
- 1h 通过 `cache_control.ttl=1h` 控制

不要在第一版默认打开 1h。

### 6.3.6 启用入口

Anthropic 第一版建议通过 route metadata 控制：

- `anthropicPromptCachingEnabled=true|false`
- `anthropicPromptCacheTtl=5m|1h`

默认：

- `false`

原因不是技术限制，而是成本控制。

### 6.3.7 代码改动点

主要文件：

- `app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt`
- `app/src/main/kotlin/com/opencray/app/LlmProviderRequestSupport.kt`
- `app/src/main/kotlin/com/opencray/app/AppAgentSessionTaskRuntimeFactory.kt`

具体动作：

- 在 Anthropic request builder 中接入顶层 `cache_control`
- 当 `anthropicPromptCacheTtl=1h` 时发送 `cache_control.ttl=1h`
- 在 response metadata 中记录 retention / cache read / cache write

### 6.3.8 测试

新增覆盖：

- 未启用 Anthropic prompt caching 时，请求体与当前行为兼容
- 启用 5m 时，顶层 `cache_control` 正确存在
- 启用 1h 时，`cache_control.ttl=1h` 正确存在
- Anthropic response usage 被正确解析到统一 metadata

### 6.4 Phase 3: 将缓存策略纳入 settings / capability cache

这一阶段不是第一优先级，但应提前留接口。

### 6.4.1 Settings 是否需要新增字段

不建议在 Phase 0 就把 UI 改得很重。

但如果要让用户真正可控，推荐后续在 `LlmSettingsState` 中增加“高级缓存设置”，而不是混入基础字段。

候选字段：

- `openAiPromptCacheKeyStrategy`
- `openAiPromptCacheRetention`
- `anthropicPromptCachingEnabled`
- `anthropicPromptCacheTtl`

不推荐做一个伪统一字段：

- `promptCachingEnabled`

因为它会让不同 provider 的真实行为被错误地抹平。

### 6.4.2 Capability cache 的角色

后续如果要对自定义 provider 做能力探测，可在 `LlmAgentCapabilitySnapshot` 增加：

- `openAiPromptCacheKeySupported`
- `openAiPromptCacheRetentionSupported`
- `anthropicPromptCachingSupported`
- `anthropicExtendedPromptCachingSupported`

但这一步不应该阻塞第一版。

原因：

- OpenAI 官方路线已足够明确
- Anthropic 官方路线也足够明确
- 真正不明确的是各种兼容代理，而这类能力探测本身更适合放在第二阶段

### 6.5 Phase 4: runtime / UI 观测面

在 metadata 打通后，再考虑是否展示到 UI。

推荐顺序：

1. runtime event / logs 可看
2. 调试页或 inspector 可看
3. 设置页或会话页是否展示，再单独决定

第一版建议至少保证：

- 每次 LLM 成功返回时，run metadata 能看见 cache hit tokens
- 对 Anthropic，能看见 cache write / read 规模

## 7. 实施顺序建议

建议拆成 4 个 PR：

### PR1: 统一缓存观测

- 新增 metadata keys
- 解析 OpenAI / Responses `cached_tokens`
- 解析 Anthropic cache usage
- 补测试

### PR2: OpenAI / Responses 请求优化

- route metadata 增加 cache key / retention 选项
- 请求体按条件注入 `prompt_cache_key`
- 请求体按条件注入 `prompt_cache_retention`
- 补测试

### PR3: Anthropic prompt caching MVP

- 接入顶层 `cache_control`
- 通过 route metadata 控制 `5m` / `1h`
- 补测试

### PR4: 配置链路与 Flutter 暴露

- 持久化 OpenAI / Anthropic prompt caching 设置
- 补齐 gateway / host runtime / local runtime server 字段链路
- 在 Flutter settings UI 暴露 provider-specific 缓存控制
- 补 Flutter bridge / settings 定向测试

## 8. 风险与注意事项

### 8.1 不要把 provider-native prompt caching 和本地答案缓存混为一谈

本地答案缓存会带来过期回答、工具结果失真、时效信息错误等风险。

这次计划只讨论 provider-native prompt caching。

### 8.2 Anthropic 启用缓存会改变成本结构

这和 OpenAI 自动缓存不同。

因此：

- Anthropic 不应在没有控制面的情况下默认全局开启
- 文档和设置文案必须明确说明这一点

### 8.3 对 OpenAI-compatible 第三方代理不能盲发私有字段

尤其是：

- `prompt_cache_key`
- `prompt_cache_retention`

第一版只建议对官方 OpenAI 默认支持；对兼容代理先观测、后能力探测。

### 8.4 前缀稳定性比“有没有开关”更重要

如果静态前缀自身不稳定：

- tool 顺序漂移
- system prompt 拼接顺序漂移
- builtin tool 列表顺序漂移

那么即使 provider 支持缓存，也很难命中。

## 9. 验证计划

### 9.1 单元测试

重点更新：

- `app/src/test/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClientTest.kt`
- `app/src/test/kotlin/com/opencray/app/LlmSettingsStoreTest.kt`
- `app/src/test/kotlin/com/opencray/app/facade/llm/LlmConfigFacadeTest.kt`
- `app/src/test/kotlin/com/opencray/app/LlmModelCapabilityRegistryTest.kt`

### 9.2 smoke test

在实现完成后，建议至少做两类 live 验证：

- OpenAI 官方：
  - 连续两次发送同前缀长 prompt
  - 确认第二次 `cached_tokens > 0`
- Anthropic 官方：
  - 启用 5m cache breakpoint
  - 连续两次发送同前缀 prompt
  - 确认第二次 `cache_read_input_tokens > 0`

### 9.3 回归风险验证

必须回归：

- `openai` 普通 completions
- `openai_responses` continuation
- Anthropic tool calling
- multimodal input
- builtin web search

因为这些路径都在同一个 provider client 中。

## 10. 这份计划是否已经“调查得足够深”

回答是：

- 对“能否进入实现”这个层级，已经足够深。
- 对“能否直接保证所有 provider live 兼容”这个层级，还不够。

更准确地说：

- 架构层面和仓库现状层面，调查已经足够。
- provider 官方能力层面，已做官方文档核对。
- 兼容代理与真实生产账号层面，还需要 live smoke test 才能封板。

因此推荐做法不是继续空谈设计，而是按本计划直接进入 PR1。

## 11. 参考资料

官方文档，2026-04-03 查阅：

- OpenAI Prompt Caching: https://platform.openai.com/docs/guides/prompt-caching
- OpenAI Responses API reference: https://platform.openai.com/docs/api-reference/responses/create
- Anthropic Prompt Caching: https://docs.anthropic.com/en/docs/build-with-claude/prompt-caching
- Anthropic Messages API: https://docs.anthropic.com/en/api/messages

仓库内相关文件：

- `app/src/main/kotlin/com/opencray/app/LlmSettingsStore.kt`
- `app/src/main/kotlin/com/opencray/app/LlmProviderRequestSupport.kt`
- `app/src/main/kotlin/com/opencray/app/LlmAgentCapabilitySupport.kt`
- `app/src/main/kotlin/com/opencray/app/LlmModelCapabilityRegistry.kt`
- `app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt`
- `app/src/main/kotlin/com/opencray/app/facade/llm/LlmConfigFacade.kt`
- `app/src/main/kotlin/com/opencray/app/AppAgentSessionTaskRuntimeFactory.kt`
- `llm/src/main/kotlin/com/opencray/llm/LiteLlmMetadataKeys.kt`
