# Agent 图片、语音与文件消息方案

## 目标

让 OpenCray 的 agent 可以在聊天里发送三类附件消息：

- 图片消息
- 语音消息
- 文件消息

本方案只覆盖 `agent -> 用户` 的发送链路。`用户 -> agent` 的图片/语音/文件输入不在这次主范围内，只在末尾列为后续可复用能力。

## 结论先行

推荐走 `文件承载的媒体消息` 方案，而不是让模型直接输出图片字节、音频字节或供应商私有多模态响应。

推荐方案分成两层：

1. 先把 `媒体消息传输层` 做对
2. 再接入 `媒体生成工具层`

这样做的原因很直接：

- 当前聊天存储已经有 `attachments` 雏形，适合增量演进
- 当前 LLM 网关、运行时协议、事件总线、Flutter 消息模型全部是文本中心
- 当前上下文裁剪器会主动压缩 base64/附件样文本，直接塞媒体载荷会把提示词链路搞坏
- 图片预览、文件分享、录音目录权限已经有部分基础设施，可以复用

## 当前落地状态

截至 `2026-03-20`，本方案已经有一版可运行实现，范围如下：

- 已支持 assistant 在 final action 里发送 `image`、`voice`、`file` 三类附件
- 仍兼容旧的 `audio` 输入语义，但宿主会把它归一化为聊天里的 `voice`
- 已支持将附件归档到工作区 `.opencray/chat-media/<sessionId>/...`
- 已支持 agent 直接调用 `GenerateImage` 工具生成图片
- 已支持 agent 直接调用 `SynthesizeSpeech` 工具把文本合成为语音
- 已支持生成图片默认写入工作区 `.opencray/generated-media/images/`
- 已支持生成语音默认写入工作区 `.opencray/generated-media/voices/`
- 已支持媒体工具通过 `attachmentArtifactsJson` 向 final action 暴露 `artifactId`
- 已支持 final action 用 `attachments[].artifact_id` 引用当前 run 内刚生成的图片和语音
- 已支持语音生成结果默认落到聊天里的 `voice` 语义；若 agent 明确用 `kind=file`，同一 artifact 也可作为音频文件发送
- 已支持媒体 provider 复用当前 LLM 路由配置里的鉴权头
- 已支持按 `SHA-256` 做 session 内去重
- 已支持单条消息最多 `9` 张图片
- 已支持多张图片、语音卡片、文件卡片渲染在同一个聊天气泡里
- 已支持图片点击大图预览
- 已支持语音消息在聊天内置播放器中播放/暂停
- 已支持语音附件落库存储 `durationMs`、`waveformBars`、`transcriptText`
- 已支持语音卡片显示波形、点击或拖动波形做 seek、内联转写折叠/展开
- 语音元数据优先使用 agent 显式提供的附件字段
- 已支持语音元数据异步回填与工作区级缓存：消息先发送，缺失的 `durationMs/waveformBars` 由宿主后台补齐
- 已支持按 `contentSha256` 把语音元数据缓存到工作区 `.opencray/voice-metadata-cache/voice-metadata-cache.json`，同内容可跨 `session` 复用
- 已支持文本文件附件走内置预览
- 已支持非文本文件附件继续通过宿主打开对应工作区文件
- 已支持当前 run 内通过 `artifactId` 引用 `Write / Import / Move` 等工具刚产出的文件附件
- 已支持音频类 `artifactId` 默认落到 `voice` 语义
- 当前真正展示给聊天消息的图片、语音、文件副本，仍然都是 `session` 私有副本
- 当前跨 `session` 复用媒体时，应以工作区里的稳定 `relativePath` 文件为源，而不是复用旧 `session` 私有媒体路径

本次实现里暂未落地的点：

- `artifactId` 还没有接入跨 run、跨会话可查询的真实宿主 artifact registry
- 当前实现是 `run` 级 alias：runtime 把文件产物写进 tool result metadata，host 仅在当前 run 内回查
- 因此当前 `artifactId` 主要适用于“本轮刚生成/导入/移动出来，接着立即发送”的附件
- `PublishMediaArtifact` 还没有落地；当前若要跨 `session` 复用图片、语音或文件，仍应先保留到工作区稳定相对路径
- 还没有工作区级全局 artifact/media registry；当前生成媒体更接近“本 run 内可引用的 artifact + 已落库的 session 私有副本”

这意味着当前代码状态更接近：

- `Phase 1` 已完成主链路
- `Phase 2` 已完成图片预览、语音内置播放、文本文件内置预览、普通文件外部打开
- `Phase 2` 语音增强已完成波形、拖动 seek、转写展示
- `Phase 3` 已部分完成：`GenerateImage` / `SynthesizeSpeech` 与最小可用 `artifactId` 发送闭环已经落地，但完整 artifact registry 与 `PublishMediaArtifact` 仍属于后续实现

说明：

- 下文“调研结论”章节保留了最初拆问题时的背景分析
- 若与本节“当前落地状态”冲突，以本节为准

## 调研结论

### 1. 当前存储层只支持“弱附件”，不支持语音，也没有富媒体元数据

现状：

- `ChatTranscriptMessageEntry` 已经有 `attachments`
- `ChatAttachmentKind` 只有 `IMAGE` 和 `FILE`
- `ChatAttachmentEntry` 只有 `displayName/localPath/mimeType/sizeBytes`

这意味着：

- assistant 理论上可以挂附件，但没有任何现成写入路径
- 语音没有 `AUDIO` 类型
- 图片没有宽高，语音没有时长、转写、波形等展示信息
- `localPath` 是脆弱的，长期更适合存相对工作区路径而不是绝对路径

### 2. 运行时上下文会把附件降级成纯文本清单

`ChatRuntimeTextFormatter` 会把附件格式化成：

- `Attachments:`
- `- file-name`

这说明当前附件只被当成提示词补充文本，不是用户可见的媒体消息协议。

### 3. 聊天提交链路现在是纯文本

`OpenCrayHostRuntime.submitChatMessage` 当前固定：

- 只收 `text`
- 调用 runtime 时 `attachments = emptyList()`
- 本地会话里也只写入文本用户消息和一个文本占位 assistant 消息

平台桥和本地 HTTP bridge 也都是：

- `submitChatMessage(String text)`

所以现在连用户侧真实附件都没接进宿主链路，更不用说 assistant 输出媒体。

### 4. 宿主驱动的聊天快照仍是纯文本，Flutter 只有附件雏形

当前宿主导出的聊天消息快照只有：

- `messageId`
- `kind`
- `text`
- `meta`
- `isEphemeral`

Flutter 的 `ChatMessageData` 也只有：

- `kind`
- `text`
- `meta`
- `isEphemeral`

但 Flutter 侧并不是完全没有附件相关代码，已经存在：

- `ChatAttachmentKind`
- `ChatAttachmentData`
- composer 区域的附件卡片样式和 seed data

问题在于这些能力目前主要服务于：

- 输入区附件展示
- 原型态 UI

而不是宿主真实聊天消息。也就是说当前仍然缺少：

- 宿主快照里的 message attachments
- host snapshot 到 `ChatMessageData` 的 attachments 映射
- `AUDIO` 类型
- 图片/语音/文件消息的点击预览、播放状态和交互协议

### 5. 现有 LLM 层只支持文本 prompt 和文本 completion

当前 `LiteLlmGatewayRequest` 只有：

- `prompt`
- `systemPrompt`

当前 `OpenAiCompatibleLiteLlmProviderClient` 只会调用：

- OpenAI 兼容 `POST /v1/chat/completions`
- Anthropic `POST /v1/messages`

并且只提取文本内容。

这意味着：

- 不能复用现有 LLM 网关直接做图片生成或 TTS
- 也不应该把图片生成、语音合成硬塞进现有 `chat/completions` 抽象

### 6. 当前 runtime 最终输出协议只认识文本 final answer

`OpenCrayAgentRuntime` 当前 final action 只解析：

- `type = final`
- `answer = <string>`

没有：

- `attachments`
- `media`
- `artifacts`

也没有 assistant 富媒体事件类型。

### 7. 直接把媒体载荷塞进 transcript 是错误方向

`ContextPruner` 会把 `data:`、`;base64,` 或类似附件长串视为 `attachment-like payload` 并重写。

这非常关键，意味着：

- 不应该让模型在最终回答里返回 base64 图片
- 不应该让 tool result 把大段音频/图片内容直接进 transcript
- 媒体必须走 `文件路径 + 元数据`，不能走 `文本载荷`

### 8. 图片链路已有可复用基础，语音链路没有

已有：

- `loadWorkspaceImagePreview`
- `AppAgentWorkspaceImagePreviewer`
- `shareWorkspaceEntries`

已有权限基础：

- `downloads`
- `documents`
- `recordings`

但没有：

- `loadWorkspaceAudioPreview`
- 音频播放器
- 波形或时长预览模型

## 方案比较

### 方案 A，推荐：文件承载的媒体消息

核心思路：

- agent 先通过工具生成或拿到媒体文件
- final action 只返回 `文本 + 附件引用`
- 宿主校验附件文件，落盘到消息存储
- Flutter 根据附件类型渲染图片卡片和语音卡片

优点：

- 贴合现有 `attachments` 模型，改造最小
- 不把二进制塞进 transcript，不破坏上下文管理
- 生成链路和展示链路可解耦
- 可以先支持“发送已有工作区图片/音频”，后面再接生成工具

缺点：

- 需要补一套附件元数据、校验、清理、预览协议
- 需要给音频补原生或 Flutter 播放能力

### 方案 B，不推荐：让模型直接输出多模态结果

核心思路：

- 直接改 LLM 网关，让模型返回图片/音频或供应商私有块结构

问题：

- 当前网关只适配文本 completion
- 供应商差异极大，OpenAI 兼容接口也不等于支持图片生成或音频输出
- runtime final 协议和宿主事件都得重写
- transcript、持久化、调试、回放、上下文裁剪都会一起被牵动

结论：

- 这条路成本高、耦合重、收益不成比例
- 不适合作为第一版

## 推荐架构

### 分层原则

先明确两层，不要混写：

#### A. 媒体消息传输层

负责：

- assistant 消息如何引用图片、语音和文件
- 宿主如何校验和持久化附件
- Flutter 如何渲染和播放

这一层完成后，即使附件来自现有工作区文件，agent 也已经能“发送图片/语音/文件消息”。

#### B. 媒体生成工具层

负责：

- 图片生成
- 文本转语音
- 可能的截图、录音导入、第三方媒体服务调用

这一层只是媒体来源，不应该决定聊天消息协议。

## 详细设计

### 1. 数据模型

推荐保留 `text + attachments` 的主结构，不在这一版直接升级为全量 `message parts`。

原因：

- 当前仓储和会话存储已经围绕 `attachments` 搭好基础
- 这次目标是支持图片、语音和文件消息，不是解决任意顺序的富文本混排

#### 1.1 扩展附件类型

逻辑语义上建议把 `ChatAttachmentKind` 扩成：

- `IMAGE`
- `VOICE`
- `FILE`

这里需要明确区分：

- `VOICE`：聊天语义里的语音消息，默认给 agent 生成的就是这一类
- `FILE`：通用文件消息；如果是 `mp3/wav/m4a` 这类音频文件但用户要的是“文件”，也走这一类

也就是说：

- “语音消息” 和 “音频文件” 不是同一个概念
- agent 生成语音时默认发 `VOICE`
- agent 可以根据用户意图、交付形式和后续使用方式，自主判断这次该发 `VOICE` 还是 `FILE`
- 默认优先发 `VOICE`
- 当 agent 判断“这次更适合交付一个文件”时，应主动发 `FILE`
- 这里不要靠关键词碰撞做硬编码判断；是否发文件应由 agent 做语义决策

若为了兼容现有代码需要保留 `AUDIO` 作为过渡枚举，也应只把它当成 `VOICE` 的实现过渡，不要把所有 `audio/*` 文件都自动当成语音消息

#### 1.2 扩展附件元数据

建议把 `ChatAttachmentEntry` 扩展为可承载展示元数据，而不是只存文件名和路径。

建议新增字段：

- `relativePath`
- `source`
- `promptLabel`
- `altText`
- `transcriptText`
- `widthPx`
- `heightPx`
- `durationMs`
- `waveformBars`

字段含义：

- `relativePath`：统一用工作区相对路径做主引用
- `source`：`generated`、`workspace`、`imported`
- `promptLabel`：后续轮次注入上下文时给模型看的简短标签
- `altText`：图片对用户和模型都可读的简短说明
- `transcriptText`：语音消息的文字版摘要或完整转写
- `widthPx/heightPx`：图片卡片展示
- `durationMs/waveformBars`：语音消息卡片展示

#### 1.3 路径策略

不要再把 assistant 附件长期存成外部绝对路径。

推荐规则：

- 所有真正发出的媒体，最终都归一到 session 私有媒体库内
- 生成媒体默认由宿主写入 `.opencray/chat-media/<sessionId>/<runId>/`
- `sessionId/runId` 只由宿主生成和管理，不由 agent 负责拼接
- 消息层只存 `relativePath` 或 `artifactId`，不存公共绝对路径

好处：

- 跨重启稳定
- 便于快照、预览和分享
- 后续做垃圾回收简单

#### 1.4 源路径与发布策略

需要区分三种“文件位置”：

- `公共受控路径`：如已授权的 `downloads/documents/recordings`
- `工作区相对路径`：当前 agent 工作区内已有文件
- `内部生成资产路径`：`.opencray/chat-media/...`

推荐协议分工：

- 工具层可以读取 `公共受控路径` 和 `工作区相对路径`
- 生成工具默认只返回 `artifactId + 元数据`
- `artifactId` 主要用于“本轮新生成、待发送”的媒体句柄
- final action 只接受：
  - `artifactId`
  - 或工作区 `relativePath`

不要让 final action 直接引用：

- 任意绝对路径
- 公共路径绝对地址
- 宿主内部的 `.opencray/chat-media/<sessionId>/<runId>/...` 细节路径模板

如果用户明确要求“把图片/语音/文件放到工作区某个位置”，推荐流程是：

1. 先生成内部 artifact
2. 再通过受控工具把 artifact 发布到用户指定的工作区相对路径
3. final action 最终引用发布后的 `relativePath`

这里的“发布”建议固定为 `copy-only`：

- session 私有媒体仓库是聊天消息的稳定真源，不能因为导出到工作区而被挪走
- 发布到工作区的文件是用户可见副本，不替代消息原始引用
- 这样旧消息、会话回放、分支和复制都不会因为文件被移动而失效

对“发送已有文件”也要统一处理：

- 若来源是 `公共受控路径`，宿主在发送时先复制到 session 私有媒体库
- 若来源是工作区已有文件，宿主在发送时也复制到 session 私有媒体库
- 消息记录最终只引用 session 私有媒体库里的稳定副本
- 因此当前 `.opencray/chat-media/<sessionId>/...` 只保证该 session 下消息展示稳定，不应作为跨 `session` 复用源路径
- 若希望跨 `session` 重发同一张图片或同一文件，当前应保留或复制一份到工作区稳定位置，再由新 session 重新发送

#### 1.5 兼容与迁移

如果历史数据里只有 `localPath`：

- 若文件仍在当前工作区下，可在加载时一次性归一为 `relativePath`
- 若文件在外部目录，不自动长期引用，优先走导入或标记为 legacy-unavailable

#### 1.6 格式、数量与大小约束

当前产品规则补充如下：

- 语音消息底层格式支持：`audio/mpeg(.mp3)`、`audio/wav(.wav)`、`audio/mp4(.m4a)`
- 图片先支持主流格式：`image/png`、`image/jpeg(.jpg/.jpeg)`、`image/webp`、`image/gif`
- 文件消息支持通用文件附件，沿用 `FILE` 类型，按路径引用和文件卡片展示
- 若 `mp3/wav/m4a` 以“文件”方式发送，仍按 `FILE` 渲染，不走语音播放器
- 单条消息最多 `9` 张图片
- 当前不设业务层文件大小上限

关于“不限大小”，实现上仍需保留两个技术事实：

- 导入、预览、播放或分享仍可能受设备存储、系统解码能力或第三方 provider 限制失败
- 这类失败应走可见错误恢复，不要静默吞掉

### 2. runtime 输出协议

在不推翻现有 JSON action 协议的前提下，扩展 final action。

推荐 final action 形状：

```json
{
  "type": "final",
  "answer": "我把示意图和语音说明都放在下面了。",
  "attachments": [
    {
      "kind": "image",
      "artifact_id": "image_1",
      "display_name": "mock.png",
      "mime_type": "image/png",
      "width_px": 1024,
      "height_px": 768,
      "alt_text": "首页布局示意图",
      "prompt_label": "首页示意图"
    },
    {
      "kind": "voice",
      "relative_path": "deliverables/voice/summary.m4a",
      "display_name": "summary.m4a",
      "mime_type": "audio/mp4",
      "duration_ms": 16200,
      "transcript_text": "这是本次修改建议的语音摘要。",
      "prompt_label": "修改建议语音摘要"
    },
    {
      "kind": "file",
      "relative_path": "deliverables/spec/opencray-agent-media-spec.pdf",
      "display_name": "opencray-agent-media-spec.pdf",
      "mime_type": "application/pdf",
      "prompt_label": "媒体消息规格说明"
    }
  ]
}
```

#### 2.1 校验规则

宿主在接受 final action 时必须校验：

- 附件数量上限
- 图片附件每条消息最多 `9` 个
- 每个 attachment 只能提供一种定位方式：`artifactId` 或 `relativePath`
- `artifactId` 必须能解析到当前 run 内真实存在的媒体产物
- `kind` 和文件实际 mime 是否匹配
- `relativePath` 必须在工作区内
- 文件必须存在
- 图片必须可预览
- 语音必须是允许播放的音频格式
- final action 不接受公共绝对路径或任意系统绝对路径

宿主在校验通过后还要做一次归档：

- `artifactId` 解析到的生成媒体，确认其 session 私有媒体路径
- `relativePath` 指向的工作区文件，复制进 session 私有媒体库
- 来自公共受控路径的文件不直接进入 final action，而是应先导入或发布到工作区，再按上一条归档

#### 2.2 错误恢复

如果 final action 引用了无效附件，不建议静默丢弃。

推荐行为：

- 生成一条 TOOL 观察，说明哪个附件校验失败
- 继续下一轮，让模型重新选择：
  - 重新生成
  - 改用已有 `artifactId`
  - 先发布到正确工作区路径
  - 改成纯文本回答
  - 或引用正确文件

这样和现有 protocol recovery 风格一致。

### 3. 宿主与持久化改造

#### 3.1 `ChatSessionLocalStore`

需要补齐：

- `replaceMessage` 支持 attachments
- `appendSubmittedTurn` 支持 assistant attachments
- preview/title 逻辑对媒体消息更合理

消息预览建议：

- 纯图片：显示 `图片`
- 纯语音：显示 `语音`
- 文本 + 附件：优先正文前 26 字，附件作为次级信息

#### 3.2 `OpenCrayHostRuntime`

需要新增能力：

- 从 runtime result 或 final action 里拿到附件引用
- 用统一校验器转成 `ChatAttachmentEntry`
- 在 `onTaskFinished` 时把 assistant 占位消息替换成 `文本 + 附件`

还需要扩展：

- `chatMessageToMap`
- `chatMessageSnapshotMap`
- 本地聊天快照 `messages`

让消息快照不再只有文本。

#### 3.3 run 事件

推荐保持 `消息存储` 为附件真源，不把完整附件元数据塞进每一条 runtime event。

但可以给 `OpenCrayAssistantEvent` 增加轻量字段：

- `attachmentCount`
- `attachmentKinds`

用于调试和运行回放。

### 4. Chat Snapshot 与 bridge 协议

`OpenCrayChatMessageSnapshot` 需要新增：

- `attachments`

每个 attachment 至少导出：

- `attachmentId`
- `kind`
- `displayName`
- `relativePath`
- `mimeType`
- `sizeBytes`
- `promptLabel`
- `altText`
- `transcriptText`
- `widthPx`
- `heightPx`
- `durationMs`
- `waveformBars`

桥接层要一起改：

- `OpenCrayFlutterHostBridge`
- `OpenCrayLocalRuntimeServer`
- `opencray_platform_bridge.dart`
- `opencray_local_runtime_bridge.dart`
- `opencray_chat_snapshot.dart`

### 5. Flutter 聊天 UI

#### 5.1 消息模型

`ChatMessageData` 建议扩展为：

- `text`
- `attachments`
- 保留 `kind/meta/isEphemeral`

#### 5.2 渲染规则

assistant 消息气泡内部按顺序渲染：

1. 图片附件区
2. 语音附件区
3. 文件附件区
4. 文本正文

这样可以继续兼容：

- 纯文本消息
- 纯附件消息
- 媒体 + 文本说明

#### 5.3 图片消息

图片卡片可以直接复用现有图片预览基础设施：

- 缩略图使用 `loadWorkspaceImagePreview`
- 点击进入全屏预览或大图弹层
- 长按菜单可继续保留复制、分支、删除、分享

#### 5.4 语音消息

需要新增 `loadWorkspaceAudioPreview`，返回：

- `relativePath`
- `mimeType`
- `durationMs`
- `sizeBytes`
- `waveformBars`
- 可播放 URI 或其他播放句柄

语音卡片建议包含：

- 播放/暂停按钮
- 时长
- 波形或简化进度条
- 可选转写折叠区

播放器实现建议：

- Flutter 侧接入音频播放依赖
- 宿主提供安全的工作区音频访问句柄
- 不通过 method channel 回传整段 base64 音频
- 语音消息默认走聊天内置播放器，不走外部应用打开

agent 侧选择规则建议固定为：

- 默认生成的是“语音消息”，也就是 `VOICE`
- agent 自主判断这次是“发语音消息”还是“交付音频文件”
- 默认优先走 `VOICE`
- 当 agent 认为用户要的是可保存、可转交、可复用的文件交付物时，走 `FILE`
- 这里不要依赖关键词命中来决定 `VOICE/FILE`

原因：

- 音频比图片更大
- base64 回传既重又没有必要

#### 5.5 文件消息

文件消息建议做成接近微信文件消息的文件卡片，而不是普通文本链接。

文件卡片建议包含：

- 文件名
- 文件类型或扩展名
- 文件大小
- 文件图标
- 点击打开或预览
- 长按继续保留复制、分支、删除、分享

文件消息不强制做内嵌预览：

- 能预览的类型可走已有工作区预览链路
- 不能预览的类型至少支持打开、分享和定位来源
- 文本文件应优先复用内置文本预览
- 非文本文件按文件系统现有行为交给系统或其他应用打开
- 即使文件本身是 `audio/*`，只要它是以文件消息发送，也不走语音播放器

#### 5.6 UI 约束

实际实现时继续遵守：

- `docs/mobile-ui-layout-spec.md`
- 现有聊天原型的气泡和卡片语言

不要把图片、语音和文件做成和文件工作台一样的原始文件列表，要维持聊天语义。

### 6. 媒体生成工具层

补充说明：

- 当前代码里已经落地 `GenerateImage` 和 `SynthesizeSpeech`
- 当前实现使用 `OpenCrayConfigurableMediaProviderClient` 直连可配置 provider
- 当前没有单独再抽一层独立 media gateway；接口抽象体现在 runtime 的 `OpenCrayImageGenerationClient` / `OpenCraySpeechSynthesisClient`
- 当前仍未落地 `PublishMediaArtifact`

这一层建议独立于 `LiteLlmGateway`，不要塞进当前文本 LLM 抽象。

#### 6.1 新建 provider 抽象

建议新增并行网关：

- `MediaGenerationGateway`
- `ImageGenerationProviderClient`
- `SpeechSynthesisProviderClient`

原因：

- 图片生成、TTS 的接口路径、请求体、返回体都和聊天 completion 不同
- 当前 `OpenAiCompatibleLiteLlmProviderClient` 只适配 `/chat/completions` 和 `/v1/messages`
- 把图片/TTS 强塞进去会让 LLM 层越来越难维护

#### 6.2 新工具

建议新增三类 host-managed 工具：

- `GenerateImage`
- `SynthesizeSpeech`
- `PublishMediaArtifact`

当前状态：

- `GenerateImage` 已落地
- `SynthesizeSpeech` 已落地
- `PublishMediaArtifact` 未落地

其中：

- `GenerateImage`
- `SynthesizeSpeech`

默认只返回：

- `artifactId`
- mime
- 尺寸或时长
- 可选摘要文本

不返回大块二进制。

`PublishMediaArtifact` 用于用户明确要求把生成媒体落到工作区指定位置时：

- 输入：`artifactId`、目标 `relativePath`
- 输出：发布后的 `relativePath` 和最终元数据

这样 agent 可以：

- 直接在 final action 里引用 `artifactId`
- 或先把 artifact 发布到工作区，再引用发布后的 `relativePath`

#### 6.3 工具策略

新工具必须走 `ToolPolicyPipeline`，因为它们同时跨：

- 网络
- 文件写入

若还会调用长任务或外部进程，再补明确的 runtime intent。

#### 6.4 与最终消息的关系

生成工具负责创建宿主管理的媒体 artifact，并返回元数据。

若用户要求把生成媒体显式落到工作区某个位置，则由发布工具负责把 artifact 导出到目标 `relativePath`。

这里要保持一个硬约束：

- `.opencray/chat-media/...` 是消息层稳定真源
- 工作区发布文件只是副本
- `PublishMediaArtifact` 不提供 `move`
- 当前还没有工作区级全局 artifact registry，所以跨 `session` 复用仍应优先依赖工作区稳定文件，而不是历史 session 私有副本或临时 `artifactId`

最终是否“发送”由 final action 决定：

- 在这个功能里，`GenerateImage` / `SynthesizeSpeech` 的主语义是“生成可发送媒体”
- 生成后通常应直接用 `artifactId` 在 final action 引用并发送
- 若用户要求工作区落点，则先发布到工作区，再用发布后的 `relativePath` 发送

这符合 agent 行为模型，也方便测试。

### 7. 资产生命周期与清理

这部分不能省，否则媒体功能上线后很快会变成垃圾文件制造机。

建议新增轻量 `ChatMediaAssetStore` 或等价引用计数机制。

目标：

- 同一个附件被分支会话、复制会话复用时，不重复拷贝
- 删除消息、撤回消息、删除会话时，只有在无引用时才清理生成资产
- `source = workspace` 的原始用户文件不自动删除
- `source = generated` 的 `.opencray/chat-media/...` 可做延迟 GC
- 发布到工作区的导出副本不反向变成消息真源
- session 私有媒体库对重复内容做 `SHA-256` 哈希去重，同一图片或文件内容在同一 session 只存一份

建议第一版：

- 只对 `generated` 资产做引用计数
- `workspace/imported` 资产不自动删
- `PublishMediaArtifact` 只做复制，不做移动
- 未被 final action 引用的临时 `artifactId` 产物按短期临时资产处理，避免堆积
- session 私有媒体库按 `SHA-256` 内容哈希建索引，统一覆盖图片、语音和文件附件去重

### 8. 上下文注入策略

因为 runtime 仍然是文本模型，所以媒体消息进入后续上下文时必须变成简短文本，而不是原始文件。

推荐注入格式：

- 图片：`Attachment(image): 首页示意图 [mock.png]`
- 语音：`Attachment(audio): 修改建议语音摘要 [summary.m4a, 16.2s]`
- 文件：`Attachment(file): 媒体消息规格说明 [opencray-agent-media-spec.pdf]`

注入来源：

- 优先 `promptLabel`
- 其次 `altText`
- 再其次 `transcriptText`
- 最后退化到 `displayName`

这样 agent 在后续轮次里仍然“知道自己发过什么”，但不会把上下文炸掉。

## 分阶段实施

### Phase 1：媒体消息基础链路

目标：

- assistant 可以发送工作区内已有图片、音频和文件

改动范围：

- 扩展 `ChatAttachmentKind`
- 扩展 `ChatAttachmentEntry`
- final action 支持 `attachments`
- `OpenCrayHostRuntime` 持久化 assistant attachments
- `OpenCrayChatSnapshot` 输出 attachments
- Flutter 聊天消息模型和气泡支持图片/语音/文件附件
- session 私有媒体库接入内容哈希去重

验收标准：

- agent 可发送图片消息
- agent 可发送语音消息
- agent 可发送文件消息
- 删除、分支、复制会话后附件仍可正确展示
- 同一图片或文件在同一 session 内不会重复落库
- 后续轮次上下文能看到媒体摘要而不是二进制

### Phase 2：图片预览与语音播放

目标：

- 图片可点开
- 语音可播放

改动范围：

- 图片卡片复用现有预览链路
- 新增音频预览与播放链路
- 新增简单波形和时长展示

验收标准：

- 图片可全屏查看
- 语音可在聊天内播放、暂停、拖动
- 无需把音频内容经聊天快照 base64 回传

### Phase 3：媒体生成工具

目标：

- agent 自己生成图片和语音，而不仅是发送已有文件

改动范围：

- `GenerateImage`
- `SynthesizeSpeech`
- `PublishMediaArtifact`
- 独立 media provider gateway
- 对应设置页或 provider 配置

当前状态：

- `GenerateImage` 已完成
- `SynthesizeSpeech` 已完成
- provider 配置页已进入实现范围
- `PublishMediaArtifact` 未完成
- 工作区级全局 artifact/media registry 未完成

验收标准：

- agent 可通过工具生成图片并在 final action 里附带发送
- agent 可把一段文本合成为语音消息并发送
- agent 也可在自行判断更合适时，产出并发送音频文件
- 用户要求落到工作区指定路径时，agent 可先发布再发送

### Phase 4：清理、导出、体验打磨

目标：

- 让功能长期可维护

改动范围：

- 生成资产 GC
- 分享到系统
- 保存到下载或录音目录
- 更好的失败文案和 unavailable 状态

## 建议的文件改动面

### 持久化与宿主

- `persistence/src/main/kotlin/com/opencray/persistence/model/ChatWorkspaceRecord.kt`
- `app/src/main/kotlin/com/opencray/app/ChatSessionLocalStore.kt`
- `app/src/main/kotlin/com/opencray/app/ChatRuntimeTextFormatter.kt`
- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt`
- `app/src/main/kotlin/com/opencray/app/OpenCrayFlutterHostBridge.kt`
- `app/src/main/kotlin/com/opencray/app/OpenCrayLocalRuntimeServer.kt`
- 新增 `app/src/main/kotlin/com/opencray/app/AppAgentWorkspaceAudioPreviewer.kt`

### runtime

- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRunEvents.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt`
- 如新增 tool intent，再补对应 policy/runtime intent 文件

### Flutter

- `flutter_app/lib/core/models/opencray_chat_snapshot.dart`
- `flutter_app/lib/core/bridge/opencray_host_bridge.dart`
- `flutter_app/lib/core/bridge/opencray_platform_bridge.dart`
- `flutter_app/lib/core/bridge/opencray_local_runtime_bridge.dart`
- `flutter_app/lib/features/chat/chat_models.dart`
- `flutter_app/lib/features/chat/chat_feature_screen.dart`
- `flutter_app/pubspec.yaml`

## 测试清单

### JVM / host tests

- final action 解析包含 attachments
- assistant 消息持久化包含 image/audio/file attachments
- invalid attachment 路径触发恢复而不是静默丢失
- 会话复制、分支、删除的资产引用行为
- session 私有媒体库对相同图片/文件内容只落库一次
- 本地 runtime server 与 platform bridge 的 attachments 序列化

### Flutter tests

- 聊天消息映射包含 attachments
- 图片卡片渲染
- 语音卡片渲染
- 文件卡片渲染
- 图片点击预览
- 语音播放状态切换

### 回归测试

- 纯文本消息完全不受影响
- approvals、run traces、progress events 不受影响
- 旧会话可正常加载

## 待定决策

下面这些不是架构方向问题，而是落地时仍需补最后一锤的实现细节：

### 1. 文件卡片的打开策略

- 哪些 `mime` 走内置预览
- 哪些 `mime` 直接调用系统打开
- 预览失败时优先回退到分享还是系统打开

### 2. `PublishMediaArtifact` 的目标冲突策略

- 目标路径已存在时是否直接报错
- 是否自动重命名
- 是否允许覆盖

当前更推荐第一版先“报错并要求 agent 改目标路径”，避免静默覆盖用户文件。

### 3. 音频元数据的补全策略

这一项现在已经定案并落代码：

- `durationMs`、`waveformBars`、`transcriptText` 如果 agent 显式带上，宿主直接入库并优先采用
- 发送主链路只做附件归档，不在消息发送时同步解码音频和提取波形，避免卡住回复出气泡
- 对缺失 `durationMs` 或 `waveformBars` 的语音附件，宿主在入库后异步分析并回填消息
- 分析结果按 `contentSha256` 写入 workspace 级缓存，同一工作区内跨 `session` 的相同语音内容可直接复用
- `transcriptText` 当前仍是可选字段，优先使用 agent 显式提供值，或复用缓存中已有值；本轮未接自动 ASR 服务

### 4. 临时 artifact 的清理时机

- run 成功但未发送时何时清理
- run 失败或取消时何时清理
- 是否需要后台定时 sweep

### 5. Phase 3 的 provider 策略

- 图片生成和 TTS 首批接哪些 provider
- 失败重试和超时策略怎么定
- 是否在设置页显式展示成本或额度提醒

## 主要风险与规避

### 风险 1：把媒体协议和生成供应商绑死

规避：

- 先做传输层
- 生成层独立 provider gateway

### 风险 2：把二进制塞进 transcript 导致上下文膨胀

规避：

- 只存文件引用和文本元数据
- 上下文只注入摘要

### 风险 3：外部绝对路径导致消息失效

规避：

- 统一归一到工作区相对路径
- assistant 消息不长期引用工作区外绝对路径

### 风险 4：删除消息后遗留大量垃圾文件

规避：

- 对生成资产做引用计数和延迟清理

### 风险 5：音频播放走 base64 造成 bridge 负担

规避：

- 音频走 metadata + 可播放句柄
- 不经聊天快照传整段音频

## 不建议现在一起做的事

- 不建议第一版就把聊天消息重构成任意顺序的富媒体 part 树
- 不建议第一版就做 `用户 -> agent` 图片和语音输入
- 不建议把图片生成和 TTS 硬塞进现有 `LiteLlmGateway`
- 不建议允许模型直接输出 base64 图片或音频

## 推荐落地顺序

如果只选一个最稳的实现顺序，建议按下面走：

1. 先做 assistant `image/audio/file attachments` 的存储、快照和 UI
2. 再补图片预览和语音播放
3. 最后补 `GenerateImage` 和 `SynthesizeSpeech`

这样第一阶段结束后，agent 就已经具备“发送图片、语音和文件消息”的协议能力；后续只是让它更容易自己生产这些媒体。
