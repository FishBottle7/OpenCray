# Chat 多模态输入与聊天附件导入方案

Last updated: 2026-03-27

## 目标

补齐两条能力链路：

1. `用户 -> agent` 的图片多模态输入链路
2. `agent -> workspace` 的聊天附件导入链路

这里的“多模态输入”当前只覆盖图片。普通文件、音频、语音上传仍然先以附件资源身份进入聊天上下文，agent 如需真正读取其内容，应显式决定是否把它导入工作区，再使用现有文件工具读取。

## 结论先行

本次采用两项明确设计：

1. 把聊天图片作为真正的多模态输入块直接发给支持视觉输入的模型
2. 增加显式工具 `import_chat_attachment(chat_attachment_id, destination_path)`，让 agent 自主决定是否把聊天附件落到工作区，以及落到哪里

不采用“自动内容理解工具先读图再给模型”的主方案。那只是纯文本架构下的补丁，不是正确链路。

## 设计原则

- 聊天附件和工作区文件是两类资源，不能因为 agent 要读它就默认把聊天附件暴露成工作区文件
- 多模态输入和聊天附件回发是两条不同链路
- `chat_attachment_id` 代表聊天资源标识，不代表工作区路径
- 模型是否支持看图，应在进入 provider 序列化前就被检测出来，并通过路由能力元数据显式传播
- 不向模型暴露 `.opencray/chat-media` 或 `.opencray/chat-drafts` 这样的宿主内部实现路径

## 当前问题

### 1. 聊天附件在 runtime 里会被压成纯文本

当前 `ChatRuntimeTextFormatter` 会把附件写成：

- `Attachments:`
- `- <displayName> [kind=..., chat_attachment_id=..., inline_markdown=...]`

然后 `ChatRuntimeSessionContextFactory` 把这段文本塞进 `RuntimeConversationMessage.content`。

结果是模型看到的只是附件清单，而不是图片本身。

### 2. 网关消息模型是纯文本

当前 `RuntimeConversationMessage` 和 `LiteLlmGatewayMessage` 都只有 `content: String`，没有任何结构化附件或多模态块字段。即使上游拿到了图片，本地网关层也没地方承载。

### 3. Provider 请求构造始终只发文本

当前 OpenAI Chat Completions、OpenAI Responses、Anthropic 三条分支都把用户消息序列化成字符串 `content`。这意味着是否接入了多模态模型都没有意义，因为图片根本没被发出去。

### 4. agent 目前只能“回发聊天附件”，不能“导入聊天附件”

提示词里已经教会 agent：

- 用 `chat_attachment_id` 把历史聊天附件重新发回聊天界面

但还没有任何工具允许 agent 显式把某个聊天附件保存进工作区。当前若想使用文件工具读它，只能依赖内部隐藏路径，这是不对的。

## Cherry 参考

多模态能力检测参考 Cherry 的思路，而不是运行时试探“给模型一张图看它会不会报错”。

采用的参考点是：

- 先做模型能力检测
- 检测结果进入模型/路由能力缓存
- 请求构造阶段依据能力位决定是否走多模态编码

在当前 OpenCray 代码基线里还没有独立的模型目录和 `modalities` 拉取链路，因此本次落地采用 Cherry 风格的“模型名归一化 + 规则匹配”检测法，作为第一阶段实现。后续若引入模型列表接口，再把供应商返回的 `modalities` 或 `supported_endpoint_types` 作为更高优先级覆盖源。

## 推荐架构

### 1. 为 runtime conversation 增加结构化附件

新增 `RuntimeConversationAttachment`，挂到 `RuntimeConversationMessage.attachments`。

字段最小集合：

- `attachmentId`
- `kind`
- `displayName`
- `filePath`
- `mimeType`
- `transcriptText`

约束：

- `content` 可以为空，只要 `attachments` 非空
- `filePath` 允许为空，表示该附件当前只能作为文本清单回退，不能直接做多模态输入

这样可以支持“只有图片没有文字”的用户消息。

### 2. 为网关消息增加结构化附件

新增 `LiteLlmGatewayAttachment`，挂到 `LiteLlmGatewayMessage.attachments`。

保留 `content: String?`，不做全量 block 抽象。原因是本次只需要补图片输入，结构化附件已经足以覆盖：

- OpenAI Chat Completions 的 `text + image_url[]`
- OpenAI Responses 的 `input_text + input_image[]`
- Anthropic 的 `text + image[]`

这是比“直接把整个消息模型重构成任意 content blocks”更小、更稳的增量方案。

### 3. 保留纯文本回退

Provider 请求构造时分三种情况：

1. 模型支持视觉输入，且附件里有可读取图片：
   - 文本部分走原始用户文本
   - 图片部分走 provider 对应的多模态块
   - 非图片附件继续以文本清单追加到文本部分
2. 模型不支持视觉输入：
   - 所有附件都降级成文本清单
3. 模型支持视觉输入，但图片文件不可读或 MIME 不合法：
   - 该图片回退成文本说明，不静默丢失

这保证功能完整性和兼容性都成立。

### 4. 当前 prompt 输入要从“格式化全文本”改成“原始文本 + 隐藏附件元数据”

当前 `AgentTask.input` 使用的是 `ChatRuntimeTextFormatter.format(...)` 的全文本结果。要支持多模态，当前轮用户输入不能只靠这串文本。

本次改法：

- `AgentTask.input` 仍然保留非空字符串语义，作为兼容兜底
- 新增隐藏元数据：
  - `_host.promptUserText`
  - `_host.promptRuntimeAttachmentsJson`

`seededConversation` 优先使用隐藏元数据重建当前轮用户消息：

- 文本来自 `_host.promptUserText`
- 附件来自 `_host.promptRuntimeAttachmentsJson`

这样不会因为 `AgentTask.input` 的兼容字符串污染真正的会话语义。

### 5. `ChatRuntimeSessionContextFactory` 负责把聊天附件解析成 runtime 附件

`ChatRuntimeSessionContextFactory` 增加工作区根路径解析能力。

它的职责变成：

- 用户/assistant/tool 文本继续进入 `content`
- 聊天附件进入 `attachments`
- 若 `ChatAttachmentEntry.localPath` 可解析到工作区实际文件，则生成 `filePath`
- 若不能解析，则仍保留附件元数据，但不提供 `filePath`

这层同时提供一个会话内按 `chat_attachment_id` 解析聊天附件的方法，供 `import_chat_attachment` 工具复用。

### 6. Provider 客户端在序列化阶段决定是否走多模态

新增路由能力位：

- `visionInputSupported`

它从 `LlmAgentCapabilitySnapshot` 进入：

- capability cache
- runtime metadata overrides
- `ProviderRoute.metadata`

`OpenAiCompatibleLiteLlmProviderClient` 在构造请求时读取这个能力位：

- `false` 或缺失：全部走文本
- `true`：用户消息中的图片附件按协议编码成多模态块

#### OpenAI Chat Completions

用户消息 `content` 变成数组：

- `{ "type": "text", "text": ... }`
- `{ "type": "image_url", "image_url": { "url": "data:..." } }`

#### OpenAI Responses

消息 `content` 变成数组：

- `{ "type": "input_text", "text": ... }`
- `{ "type": "input_image", "image_url": "data:..." }`

#### Anthropic

用户消息 `content` 变成 block 数组：

- `{ "type": "text", "text": ... }`
- `{ "type": "image", "source": { "type": "base64", "media_type": ..., "data": ... } }`

### 7. 多模态能力检测采用 Cherry 风格的规则检测

新增 `visionInputSupported` 检测器，第一阶段使用模型名规则匹配。

检测顺序：

1. 先归一化 `protocol + model`
2. 先命中排除规则
3. 再命中视觉模型规则
4. 结果缓存到 `LlmAgentCapabilitySnapshot`

排除规则优先识别明显不是视觉模型的类别，例如：

- embedding
- rerank
- moderation
- transcription
- tts
- realtime-only
- image generation only

视觉规则覆盖常见家族，例如：

- `gpt-4o`
- `gpt-4.1`
- `gpt-4-turbo`
- `gpt-5`
- `o1`
- `o3`
- `claude-3`
- `claude-sonnet-4`
- `claude-opus-4`
- `gemini`
- `pixtral`
- `qwen-vl`
- `qvq`
- `glm-4v`
- `internvl`
- `minicpm-v`
- `llava`
- 其他带 `vision` / `vl` / `omni` 明确信号的模型

说明：

- 这是工程上可接受的一阶段方案
- 它比“先发图试试看”更稳定，也更符合 Cherry 的思路
- 后续若引入 `/models` 拉取与 `modalities` 字段，应把远端返回的能力声明作为最高优先级

### 8. 增加工具 `import_chat_attachment`

工具签名：

- `chat_attachment_id: string`
- `destination_path: string`

语义：

- 如果 agent 不调用它，表示“不保存到工作区项目树”
- 如果 agent 调用它，表示“把指定聊天附件复制到工作区目标位置”

实现方式：

- 在 `OpenCrayToolDispatcherConfig` 中新增宿主注入型 resolver
- resolver 根据当前 `sessionId + chat_attachment_id` 找到对应 `ChatAttachmentEntry`
- 解析出真实源文件路径
- 走与 `workspace_import_file` 相同的复制与 policy pipeline

#### 为什么不直接暴露 `.opencray/chat-media/...`

因为那会把宿主内部存储细节泄露给模型，并且使聊天资源和项目文件边界混乱。

正确模型是：

- 聊天附件是聊天资源
- `import_chat_attachment` 是显式复制动作

### 9. `import_chat_attachment` 的 policy 和元数据约束

这个工具必须走 `ToolPolicyPipeline`。

要求：

- policy class 归类为写文件
- target path 是工作区目标路径
- 实际源路径参与 policy evaluation，但对模型侧元数据不暴露内部隐藏路径
- approval/deny/result metadata 里显示：
  - `chatAttachmentId`
  - `chatAttachmentDisplayName`
  - `destinationPath`

不要显示：

- `.opencray/chat-media/...`
- `.opencray/chat-drafts/...`

### 10. Prompt 指导同步加强

补充提示词规则：

- `chat_attachment_id` 用于把聊天附件重新发回聊天界面
- `import_chat_attachment` 用于把聊天附件保存进工作区
- 如果只想看图，不需要导入；支持视觉输入的模型会直接收到图片
- 如果是普通文件且需要真正读取内容，应先决定是否导入，再用文件工具读取

## 代码变更范围

### runtime

- `PromptModels.kt`
- `OpenCrayAgentRuntime.kt`
- `OpenCrayPromptResumeState.kt`
- `AgentTooling.kt`
- `PromptAssembler.kt`
- `ToolCallNormalizer.kt`
- `ToolCapabilityClassifier.kt`
- `RecentToolObservationSupport.kt`

### llm

- `LiteLlmGateway.kt`

### app

- `ChatRuntimeSessionContextFactory.kt`
- `ChatRuntimeTextFormatter.kt`
- `OpenCrayHostRuntime.kt`
- `AppAgentSessionTaskRuntimeFactory.kt`
- `InProcessOpenCrayRuntimeOwner.kt`
- `OpenAiCompatibleLiteLlmProviderClient.kt`
- `LlmAgentCapabilitySupport.kt`
- `LlmConfigFacade.kt`

### tests

- `ChatRuntimeSessionContextFactoryTest.kt`
- `OpenAiCompatibleLiteLlmProviderClientTest.kt`
- `LlmConfigFacadeTest.kt`
- `LlmSettingsStoreTest.kt`
- `OpenCrayToolDispatcherAttachmentArtifactTest.kt`
- `OpenCrayAgentRuntimeTest.kt`
- `PromptAssemblerTest.kt`

## 测试计划

至少覆盖以下行为：

1. `ChatRuntimeSessionContextFactory` 会把图片附件解析成结构化 runtime 附件
2. OpenAI Chat Completions 在 `visionInputSupported=true` 时发送 `text + image_url`
3. OpenAI Responses 在 `visionInputSupported=true` 时发送 `input_text + input_image`
4. Anthropic 在 `visionInputSupported=true` 时发送 `text + image`
5. `visionInputSupported=false` 时附件降级为文本清单
6. `import_chat_attachment` 能把聊天附件复制到工作区目标路径
7. `import_chat_attachment` 的结果元数据不泄露内部 `.opencray/chat-media` 路径
8. PromptAssembler 明确区分“直接看图”和“导入附件”
9. capability cache 会持久化 `visionInputSupported`

## 后续补充

### 工作区图片正式查看工具

当前实现已经补上 `view_workspace_image(path)`：

- 它不会把图片内容先转成文本
- 它会把可读工作区图片作为真正的图片附件注入下一轮模型输入
- agent 调用后必须等下一轮，再基于图片本身继续判断

这条链路解决的是“聊天上传图片能看，但工作区里的图片 agent 还不能正式看”的缺口。

## 风险与边界

### 1. 本次不覆盖普通文件直读

用户上传 PDF、DOCX、ZIP、源码文件时，模型不会自动拿到文件正文。agent 若要读取内容，需要：

1. 先决定是否导入
2. 再用现有文件工具读取

这不是缺陷，而是当前架构的明确资源边界。

同样地，当前也还没有 `view_workspace_pdf` / `view_workspace_docx` 这类正式文档查看工具。若后续要补，应按文件类型分别走：

1. 多页视觉查看
2. 文本抽取
3. 结构化页面导入

### 2. 视觉能力检测是规则检测，不是供应商权威声明

这意味着：

- 常见模型命中率会高
- 小众私有模型可能需要后续引入模型目录能力声明来覆盖

因此实现时应保证：

- 检测失败只会退回文本链路
- 不会破坏原有文本对话

### 3. 图片输入先不做压缩和 OCR

第一阶段直接发送原图字节。若后续出现：

- 图片过大
- provider 限制过严
- 需要 OCR/文档抽取

再单独补图像压缩或文档理解方案。

## 完成定义

满足以下条件后，本设计文档可移入 `docs/done`：

- 运行时能把聊天图片直接送入支持视觉输入的模型
- 不支持视觉输入时能够稳定回退到文本附件描述
- agent 可以通过 `import_chat_attachment` 自主把聊天附件复制到工作区
- policy metadata 不泄露内部聊天媒体存储路径
- 测试覆盖上述主链路
- 完成一轮高精度代码审查并修复发现的问题
