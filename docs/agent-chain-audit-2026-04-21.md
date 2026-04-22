# Agent Chain Audit

Audit date: 2026-04-21

## Scope

This document records a static code audit of four chains in OpenCray:

- E2B 调用链路
- agent 引用图片与文件链路
- 本地模型调用链路
- agent 生图、生成语音、视频链路

This audit focuses on:

- 功能是否完整
- 是否存在明显的性能问题
- 是否存在明显的用户体验问题

This is primarily a repository-level static review. It is not a claim that every path has been fully executed end to end on device.

## Executive Summary

这四条链路的主要问题不是单点 bug，而是几类系统性断链：

- E2B 的会话生命周期和取消语义不可靠，存在跨任务误杀和原生命令不可见的问题。
- 引用型附件在模型面、服务面、Flutter/UI 面并没有真正 round-trip，导致“重发已有附件”这条能力名义上存在、实际上不稳定或不可用。
- 媒体链路缺少 provider 级鉴权建模、异步任务契约和视频链路，长任务取消也基本无效。
- 本地模型链路主路径可以跑通，但取消、资源释放、流式开关一致性和失败反馈都还有明显缺口。

## High Severity Findings

### 1. [E2B] Sandbox 生命周期只看 Python `activeRequests`，看不到原生命令

`sandbox_session_info` / `sandbox_session_close` 只观察 Python runtime 的活动请求，无法覆盖仍在运行的 envd native managed process。结果是 sandbox 可能在原生命令仍存活时被错误判空和回收。

Evidence:

- [InProcessOpenCrayRuntimeOwner.kt](../app/src/main/kotlin/com/opencray/app/InProcessOpenCrayRuntimeOwner.kt#L96)
- [E2BCodeInterpreterPythonRuntime.kt](../app/src/main/kotlin/com/opencray/app/E2BCodeInterpreterPythonRuntime.kt#L84)
- [E2BSandboxSessionInfoService.kt](../app/src/main/kotlin/com/opencray/app/E2BSandboxSessionInfoService.kt#L62)
- [E2BEnvdNativeCommandExecution.kt](../app/src/main/kotlin/com/opencray/app/E2BEnvdNativeCommandExecution.kt#L1022)

### 2. [E2B] 取消或超时一个请求会删除整个 sandbox

E2B Python-backed 请求在取消或超时时会直接删除 sandbox，而不是只取消当前请求。共享同一个 sticky sandbox 的其它并发工作会被一起杀掉。

Evidence:

- [E2BCodeInterpreterPythonRuntime.kt](../app/src/main/kotlin/com/opencray/app/E2BCodeInterpreterPythonRuntime.kt#L84)
- [CommandExecutor.kt](../runtime/src/main/kotlin/com/opencray/runtime/CommandExecutor.kt#L159)

### 3. [Attachment] `artifactId` / `chatAttachmentId` 引用型附件提交链路没有真正打通

共享模型、服务层和 fallback transport 都接受 `artifactId` / `chatAttachmentId`，但归档层只认 `relativePath` / `path`。结果是“重发已有会话附件”或“重发运行产物”在能力面存在，在真实提交时会被丢弃。

Evidence:

- [OpenCrayFinalAttachment.kt](../runtime/src/main/kotlin/com/opencray/runtime/OpenCrayFinalAttachment.kt#L13)
- [ServiceOwnedChatSubmissionAccess.kt](../app/src/main/kotlin/com/opencray/app/ServiceOwnedChatSubmissionAccess.kt#L329)
- [AppChatAttachmentArchiver.kt](../app/src/main/kotlin/com/opencray/app/AppChatAttachmentArchiver.kt#L112)
- [OpenCrayFlutterHostBridge.kt](../app/src/main/kotlin/com/opencray/app/OpenCrayFlutterHostBridge.kt#L928)
- [OpenCrayLocalRuntimeServer.kt](../app/src/main/kotlin/com/opencray/app/OpenCrayLocalRuntimeServer.kt#L902)

### 4. [Media] 媒体鉴权错误绑定到当前 LLM 路由，而不是媒体 provider 自身配置

媒体设置没有独立的 provider 级鉴权建模，运行时却复用当前 LLM 路由的协议和 key 生成请求头。常见组合如 Anthropic 或本地模型配 Fal/OpenAI TTS 会直接失败。

Evidence:

- [MediaSpeechSettingsStore.kt](../app/src/main/kotlin/com/opencray/app/MediaSpeechSettingsStore.kt#L152)
- [AppAgentSessionTaskRuntimeFactory.kt](../app/src/main/kotlin/com/opencray/app/AppAgentSessionTaskRuntimeFactory.kt#L3062)
- [LlmProviderRequestSupport.kt](../app/src/main/kotlin/com/opencray/app/LlmProviderRequestSupport.kt#L17)

### 5. [Media] 没有异步媒体任务契约，也没有视频链路

当前代码只支持同步 `GenerateImage` 和 `SynthesizeSpeech`。没有 `202 + job id` 的轮询恢复协议，没有视频工具，没有视频 provider/runtime/UI 入口。

Evidence:

- [OpenCrayMediaToolModels.kt](../runtime/src/main/kotlin/com/opencray/runtime/OpenCrayMediaToolModels.kt#L56)
- [AgentTooling.kt](../runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt#L527)
- [AgentTooling.kt](../runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt#L538)
- [settings_api_pages.dart](../flutter_app/lib/features/settings/settings_api_pages.dart#L660)
- [settings_api_pages.dart](../flutter_app/lib/features/settings/settings_api_pages.dart#L727)
- [settings_api_pages.dart](../flutter_app/lib/features/settings/settings_api_pages.dart#L794)

### 6. [Media] 生图和 TTS 的“中断”基本无效

UI 虽然提供了中断按钮，但底层请求仍是阻塞式网络调用，不消费运行时取消信号。用户点击取消后，请求通常会继续跑到自然返回或超时。

Evidence:

- [AgentTooling.kt](../runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt#L2786)
- [OpenCrayConfigurableMediaProviderClient.kt](../app/src/main/kotlin/com/opencray/app/OpenCrayConfigurableMediaProviderClient.kt#L142)
- [OpenCrayConfigurableMediaProviderClient.kt](../app/src/main/kotlin/com/opencray/app/OpenCrayConfigurableMediaProviderClient.kt#L441)
- [chat_feature_screen.dart](../flutter_app/lib/features/chat/chat_feature_screen.dart#L2966)

### 7. [Local Model] 本地 OpenAI-compatible 端点被强制要求 `apiKey`

设置层和启用条件把无 key 的本地 OpenAI-compatible 端点判成“未配置”，这会直接阻断一大类本地后端，如 LM Studio、部分 Ollama/OpenAI-compatible 网关。鉴权层本身其实允许空 key。

Evidence:

- [LlmSettingsStore.kt](../app/src/main/kotlin/com/opencray/app/LlmSettingsStore.kt#L86)
- [LlmSettingsStore.kt](../app/src/main/kotlin/com/opencray/app/LlmSettingsStore.kt#L495)
- [settings_feature.dart](../flutter_app/lib/features/settings/settings_feature.dart#L2949)
- [LlmConfigFacade.kt](../app/src/main/kotlin/com/opencray/app/facade/llm/LlmConfigFacade.kt#L265)
- [AppAgentSessionTaskRuntimeFactory.kt](../app/src/main/kotlin/com/opencray/app/AppAgentSessionTaskRuntimeFactory.kt#L333)
- [LlmProviderRequestSupport.kt](../app/src/main/kotlin/com/opencray/app/LlmProviderRequestSupport.kt#L17)

### 8. [Local Model] 普通聊天 prompt 的取消不能中断 on-device 推理

队列只打 `CANCEL_REQUESTED` 标记，主执行路径没有可中断 `Future`，而 LiteRT-LM 只有在超时或线程被中断时才会 `cancelActiveGeneration()`。结果是用户点击取消后，本地推理通常仍继续占用 CPU/GPU。

Evidence:

- [SessionQueue.kt](../core/src/main/kotlin/com/opencray/core/orchestrator/SessionQueue.kt#L244)
- [AgentSessionRuntimeManager.kt](../app/src/main/kotlin/com/opencray/app/AgentSessionRuntimeManager.kt#L727)
- [OpenCrayAgentRuntime.kt](../runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt#L1300)
- [LiteRtOnDeviceRuntime.kt](../app/src/main/kotlin/com/opencray/app/LiteRtOnDeviceRuntime.kt#L381)

## Medium Severity Findings

### 9. [Attachment] 本地 runtime 模式下“添加附件”按钮是死链

local runtime bridge 的 `pickChatAttachments` 直接返回空列表，UI 又把空列表当成用户取消选择，因此入口会静默失效，没有错误提示。

Evidence:

- [opencray_local_runtime_bridge.dart](../flutter_app/lib/core/bridge/opencray_local_runtime_bridge.dart#L995)
- [chat_feature_screen.dart](../flutter_app/lib/features/chat/chat_feature_screen.dart#L2100)

### 10. [Attachment] 附件预览同步执行重 I/O；多轮附件会反复全量编码

预览链路把图片解码、压缩、base64、文本读取、音频探测放在同步 MethodChannel 路径里，容易阻塞聊天界面。另一个问题是 provider 每轮都会重新读取并 base64 编码所有历史图片/PDF，历史越长成本越高。

Evidence:

- [OpenCrayFlutterHostBridge.kt](../app/src/main/kotlin/com/opencray/app/OpenCrayFlutterHostBridge.kt#L954)
- [OpenCrayLocalHostGateway.kt](../app/src/main/kotlin/com/opencray/app/OpenCrayLocalHostGateway.kt#L129)
- [AppAgentWorkspaceImagePreviewer.kt](../app/src/main/kotlin/com/opencray/app/AppAgentWorkspaceImagePreviewer.kt#L56)
- [OpenCrayAgentRuntime.kt](../runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt#L5442)
- [OpenAiCompatibleLiteLlmProviderClient.kt](../app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt#L2202)
- [OpenAiCompatibleLiteLlmProviderClient.kt](../app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt#L2483)
- [OpenAiCompatibleLiteLlmProviderClient.kt](../app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt#L5343)

### 11. [Media] 大媒体文件会被重复下载、重复缓冲、重复落盘和重复归档

provider 返回 URL 时，客户端会再发 GET 并把整个文件读进内存，成功后写一份 generated media，消息归档时再复制一份进 chat-media。对于大图、长音频，后续若接视频，网络、堆内存和磁盘 I/O 都会被放大。

Evidence:

- [OpenCrayConfigurableMediaProviderClient.kt](../app/src/main/kotlin/com/opencray/app/OpenCrayConfigurableMediaProviderClient.kt#L248)
- [OpenCrayConfigurableMediaProviderClient.kt](../app/src/main/kotlin/com/opencray/app/OpenCrayConfigurableMediaProviderClient.kt#L397)
- [AgentTooling.kt](../runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt#L4771)
- [OpenCrayHostRuntime.kt](../app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt#L6341)
- [AppChatAttachmentArchiver.kt](../app/src/main/kotlin/com/opencray/app/AppChatAttachmentArchiver.kt#L158)

### 12. [Media] TTS 配置链路不完整

设置层只允许用户配置 `voicePreset`，没有稳定的 TTS `model` 配置；运行时每次仍会带上 `model`，默认值还硬编码成 `tts-1`。这会让非 OpenAI TTS 或需要切换模型的后端无法稳定配置。

Evidence:

- [MediaSpeechSettingsStore.kt](../app/src/main/kotlin/com/opencray/app/MediaSpeechSettingsStore.kt#L63)
- [settings_api_pages.dart](../flutter_app/lib/features/settings/settings_api_pages.dart#L777)
- [OpenCrayMediaToolModels.kt](../runtime/src/main/kotlin/com/opencray/runtime/OpenCrayMediaToolModels.kt#L32)
- [AppAgentSessionTaskRuntimeFactory.kt](../app/src/main/kotlin/com/opencray/app/AppAgentSessionTaskRuntimeFactory.kt#L3080)
- [OpenCrayConfigurableMediaProviderClient.kt](../app/src/main/kotlin/com/opencray/app/OpenCrayConfigurableMediaProviderClient.kt#L134)

### 13. [Local Model] on-device 模型实例是进程级常驻缓存，但正常生命周期没有释放路径

on-device runtime 持有进程级 `activeEngineHandle`，生产装配使用单例。切回云端、离开会话或 warmup 清空后，本地模型仍可能常驻内存/GPU，直到切模型或进程退出。

Evidence:

- [LiteRtOnDeviceRuntime.kt](../app/src/main/kotlin/com/opencray/app/LiteRtOnDeviceRuntime.kt#L161)
- [LiteRtOnDeviceRuntime.kt](../app/src/main/kotlin/com/opencray/app/LiteRtOnDeviceRuntime.kt#L193)
- [LiteRtOnDeviceRuntime.kt](../app/src/main/kotlin/com/opencray/app/LiteRtOnDeviceRuntime.kt#L530)
- [InProcessOpenCrayRuntimeOwner.kt](../app/src/main/kotlin/com/opencray/app/InProcessOpenCrayRuntimeOwner.kt#L170)
- [AppAgentSessionTaskRuntimeFactory.kt](../app/src/main/kotlin/com/opencray/app/AppAgentSessionTaskRuntimeFactory.kt#L244)
- [OnDeviceLlmWarmupController.kt](../app/src/main/kotlin/com/opencray/app/OnDeviceLlmWarmupController.kt#L142)

### 14. [Local Model] on-device 路径静默忽略全局流式开关

设置里有 `streamingEnabled`，但 on-device route metadata 不携带 `stream`，provider 也不消费 `streamObserver`。用户看到的是“已开流式”，实际行为却是长时间无增量输出，最后一次性返回。

Evidence:

- [LlmSettingsStore.kt](../app/src/main/kotlin/com/opencray/app/LlmSettingsStore.kt#L49)
- [LlmSettingsStore.kt](../app/src/main/kotlin/com/opencray/app/LlmSettingsStore.kt#L171)
- [LlmModelCapabilityRegistry.kt](../app/src/main/kotlin/com/opencray/app/LlmModelCapabilityRegistry.kt#L1067)
- [LiteLlmGateway.kt](../llm/src/main/kotlin/com/opencray/llm/LiteLlmGateway.kt#L629)
- [LiteRtOnDeviceLlmProviderClient.kt](../app/src/main/kotlin/com/opencray/app/LiteRtOnDeviceLlmProviderClient.kt#L12)
- [OpenAiCompatibleLiteLlmProviderClient.kt](../app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt#L159)

## Low Severity Findings

### 15. [Local Model / Attachment] warmup 失败和附件导入失败都偏静默

on-device warmup 失败会记录 `FAILED` 和 `failureMessage`，但聊天 UI 基本不展示；附件导入失败则会直接掉项，用户难以区分“取消了选择”还是“导入失败”。

Evidence:

- [OnDeviceLlmWarmupController.kt](../app/src/main/kotlin/com/opencray/app/OnDeviceLlmWarmupController.kt#L171)
- [OpenCrayRuntimeServiceGatewayBundle.kt](../app/src/main/kotlin/com/opencray/app/OpenCrayRuntimeServiceGatewayBundle.kt#L1044)
- [OpenCrayHostRuntime.kt](../app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt#L1649)
- [AppChatAttachmentDraftImporter.kt](../app/src/main/kotlin/com/opencray/app/AppChatAttachmentDraftImporter.kt#L34)
- [OpenCrayFlutterHostBridge.kt](../app/src/main/kotlin/com/opencray/app/OpenCrayFlutterHostBridge.kt#L904)

## Residual Risks And Test Gaps

- 没看到覆盖 E2B 并发请求取消、sandbox 生命周期与 native process 可见性的回归测试。
- 没看到覆盖 `chatAttachmentId` 从 Flutter / local HTTP 公开入口 round-trip 的测试。
- 没看到覆盖异步媒体任务、视频生成、媒体中断恢复、大文件 URL 回源的测试。
- 没看到覆盖本地模型“运行中取消”“流式 observer 行为”“切会话/切 provider 后释放引擎”“warmup 失败是否对 UI 可见”的测试。
- 本地模型链路还有一处外部集成残余风险：`LiteLlmGateway` 会投影 `messages` 进 `prompt`，而 `LiteRtOnDeviceRuntime` 又把 `prompt` 和 `messages` 一起传给 `org.opencray.litertlmbridge`。bridge 源码不在本仓库内，因此无法在仓库内证明是否存在上下文重复计入，只能保留为残余风险。

Evidence:

- [LiteLlmGateway.kt](../llm/src/main/kotlin/com/opencray/llm/LiteLlmGateway.kt#L20)
- [LiteRtOnDeviceRuntime.kt](../app/src/main/kotlin/com/opencray/app/LiteRtOnDeviceRuntime.kt#L609)

## Verification

- 这次以静态审查为主，没有补跑完整应用链路。
- 本地模型链路额外验证了 `./gradlew.bat :app:testDebugUnitTest --tests "com.opencray.app.LiteRtOnDeviceProviderClientTest"` 通过。
- 更广的 Gradle 校验没有在这次审查里继续扩展到完整 app/runtime 套件。
