# OpenAI-Compatible 原生工具调用与搜索支持

完成时间: 2026-03-27

## 范围

这一轮落地的是 `custom provider + protocol=openai` 路线上的原生能力补齐，目标是让 GLM 和 Kimi 继续走多 provider 架构，同时尽量按它们各自官方兼容接口的原生方式使用：

- 新式 `tools` / `tool_calls`
- provider-native web search

没有新增单独的 `/chat/completions` 协议方言，也没有回退去补旧式 `function_call` 解析。

## 已完成

- runtime 不再把 provider-native web search 限死在 `openai_responses` 路线上；只要 route 启用了 provider-native web search，就会优先把宿主 `WebSearch` 映射成 builtin tool。
- `OpenAiCompatibleLiteLlmProviderClient` 为 `protocol=openai` 增加了 builtin web search dialect：
  - GLM / BigModel: `tools[{type:\"web_search\", web_search:{...}}]`
  - Kimi / Moonshot: `tools[{type:\"builtin_function\", function:{name:\"$web_search\"}}]`
- Kimi builtin search 的 provider-native 回环在 provider adapter 内部自动完成：
  - 如果模型先只返回 `$web_search` tool call
  - adapter 会自动补 assistant/tool 消息并继续请求
  - runtime 不需要知道 Kimi 的特殊握手细节
- OpenAI 协议路线的 capability validate 现在也会主动 probe builtin web search，而不是只在 Responses 路线上探测。
- 新增了 builtin search dialect 元数据，便于记录这次实际走的是哪种 provider-native 搜索协议。

## 使用方式

当前推荐的接入方式：

1. 在设置里选择 `custom provider`
2. `protocol` 选 `openai`
3. `baseUrl` 使用官方兼容端点
4. `apiKey` 和 `model` 按 provider 自己的配置填写

当前会按下面的优先级推断 dialect：

1. route metadata 显式指定
2. 模型名推断
3. host 推断

模型名推断优先覆盖第三方代理场景，当前规则保持简单：

- 模型名包含 `glm` -> 识别为 GLM
- 模型名包含 `kimi` 或 `moonshot` -> 识别为 Kimi

典型例子：

- `zhipuai/glm-4.6`
- `glm-4.6:online`
- `moonshotai/kimi-k2`
- `kimi-k2:online`

对官方域名仍然会继续自动推断 dialect：

- `bigmodel.cn` -> GLM `web_search`
- `moonshot.ai` / `moonshot.cn` -> Kimi `builtin_function.$web_search`

因此用官方兼容地址时，不需要再额外加一个新的协议类型。

## 当前边界

- 这次补的是 OpenAI-compatible chat 路线，不是 OpenAI Responses 路线。
- GLM / Kimi 的原生搜索能力已经能在第三方代理上通过模型名自动识别；如果后面遇到命名不规则的代理模型，再考虑把 dialect 显式暴露到设置层。
- 这次没有新增 legacy `function_call` 兼容，统一按新式 `tools/tool_calls` 处理。

## 代码落点

- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`
- `app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt`
- `app/src/main/kotlin/com/opencray/app/facade/llm/LlmConfigFacade.kt`
- `llm/src/main/kotlin/com/opencray/llm/LiteLlmMetadataKeys.kt`

## 参考

- GLM OpenAI 兼容接口: <https://docs.bigmodel.cn/cn/guide/develop/openai/introduction>
- GLM Web Search: <https://docs.bigmodel.cn/cn/guide/tools/web-search>
- Kimi Tool Calls: <https://platform.moonshot.ai/docs/guide/use-kimi-api-to-complete-tool-calls>
- Kimi Web Search: <https://platform.moonshot.ai/docs/guide/use-web-search>
