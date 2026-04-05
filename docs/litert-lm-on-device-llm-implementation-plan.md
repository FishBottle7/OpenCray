# LiteRT-LM 端侧 LLM 接入实施计划

更新时间：2026-04-04

## 0. 实施状态快照

截至 2026-04-04，本计划对应的推进状态如下：

- 路线已确认：
  - OpenCray 采用 `LiteRT-LM` 作为首条正式端侧 LLM 路线。
  - 不采用 `localhost` / `127.0.0.1` loopback server 作为正式架构。
  - 不直接裸接 `LiteRT` 作为应用层对接面。
- 首发范围已确认：
  - 在 LLM 设置页新增“端侧模型”可选项。
  - 首发支持两个 Gemma 4 LiteRT-LM 模型包：
    - `litert-community/gemma-4-E2B-it-litert-lm`
    - `litert-community/gemma-4-E4B-it-litert-lm`
  - 首发只做文本对话和 tool calling。
  - 首发提供 `GPU / CPU` backend 选择，默认 `GPU`，不把 `NPU` 作为 v1 对用户承诺的通用能力。
- 现状诊断已完成：
  - 当前仓库 LLM 主链路是“远端 HTTP provider”架构。
  - 当前设置页、桥接层、运行时注入点都可以复用，但需要补一条端侧 provider 分支。
- 本计划对应的代码尚未开始实现。

## 1. 文档目的

本文档把 OpenCray 接入 LiteRT-LM 端侧模型的正式实施方案定下来，回答下面这些问题：

- `LiteRT` 和 `LiteRT-LM` 在 OpenCray 里分别应扮演什么角色
- 为什么正式方案不应走本地 HTTP server / sidecar
- 如何在不重写现有 `LiteLlmGateway` 的前提下接入端侧模型
- LLM 设置页如何同时支持“远端 provider”和“端侧模型”
- 模型下载、安装、校验、切换、删除应该怎么设计
- 首发接入 Gemma 4 `E2B` / `E4B` 的具体实施顺序是什么

本文档是当前版本的实施定稿，后续代码推进应尽量按这里的边界和分层收口。

## 2. 最终决策

### 2.1 技术路线

OpenCray 采用“宿主进程内原生 LiteRT-LM provider”方案：

- `LiteRT` 只作为底层运行时与硬件加速层存在。
- `LiteRT-LM` 作为 OpenCray 对接端侧 LLM 的正式集成面。
- 现有上层 `LiteLlmGateway` / `LiteLlmProviderClient` 抽象继续保留。
- 新增一个端侧 provider client，与现有 `OpenAiCompatibleLiteLlmProviderClient` 并列。

不采用的路线：

- 不采用 `App -> localhost -> 本地 OpenAI-compatible server` 作为正式架构。
- 不采用“直接在 Flutter 层拉模型并持有推理 session”的方案。
- 不把 `Android AI Core` 作为 v1 首发主线。

### 2.2 为什么不是直接接 LiteRT

`LiteRT` 负责的是底层推理执行：

- CPU / GPU / NPU delegate
- compiled model
- 权重加载
- 底层内存和执行优化

但 OpenCray 需要的不是“能跑张量”这么低层的能力，而是 LLM 级能力：

- tokenizer 和 prompt templating
- 多轮 session 与 KV cache
- 函数调用 / tool calling
- 与聊天 runtime 对接的 completion 结果结构

这些正是 `LiteRT-LM` 补齐的部分。

结论：

- 对 OpenCray 这种聊天式 agent 产品，应用层应该接 `LiteRT-LM`。
- `LiteRT` 只是 `LiteRT-LM` 下方的基础设施，不应直接暴露成应用主集成接口。

### 2.3 为什么不走本地 HTTP server

本地 HTTP server 的优势只是“接现有代码快”，但它有三个结构性问题：

- Android 进程和端口生命周期更脆，系统回收、重启、前后台切换更容易出问题。
- 多一层 JSON/HTTP 编解码和本地 IPC，不是最佳性能路径。
- 模型 session、KV cache、模型实例管理都被拆到另一条服务链路，不利于和现有 runtime 生命周期统一。

OpenCray 现有 LLM 抽象已经有 `LiteLlmProviderClient` 这一层，所以更合理的做法是：

- 继续复用 `LiteLlmGateway`
- 把“远端 HTTP provider”和“端侧 LiteRT-LM provider”统一到同一抽象下

### 2.4 首发范围

首发范围明确如下：

- LLM 设置页新增端侧模型 provider
- 支持两个 Gemma 4 LiteRT-LM 模型包：
  - `gemma-4-E2B-it.litertlm`
  - `gemma-4-E4B-it.litertlm`
- 支持模型下载、校验、删除、切换
- 支持 `GPU / CPU` backend 偏好，默认 `GPU`
- 支持文本输入、文本输出
- 支持 tool calling
- 支持本地 readiness validation

首发不做：

- 不做本地视觉输入
- 不做本地音频输入
- 不做用户自定义 LiteRT-LM 模型导入
- 不做 `Android AI Core` provider
- 不做 Qualcomm / MediaTek / Samsung 特定设备专用包分发

## 3. 外部资料结论

### 3.1 LiteRT 与 LiteRT-LM 的官方定位

根据 Google AI Edge 官方资料：

- `LiteRT` 是底层多平台高性能 runtime。
- `LiteRT-LM` 是建在 `LiteRT` 之上的 GenAI / LLM 专用编排层。
- `LiteRT-LM` 官方明确覆盖 `Gemma`、`Llama`、`Phi-4`、`Qwen` 等模型家族。

对 OpenCray 的含义是：

- 我们不需要从零自己拼一个“LiteRT + tokenizer + KV cache + function calling adapter”。
- 官方栈已经给出了更贴近产品集成的层级。

### 3.2 首发模型包现状

截至 2026-04-04，LiteRT 社区已经提供可直接用于 Android 的 Gemma 4 LiteRT-LM 模型包：

| 模型 | Hugging Face 包 | 文件 | 大小 | SHA-256 |
| --- | --- | --- | --- | --- |
| Gemma 4 E2B | `litert-community/gemma-4-E2B-it-litert-lm` | `gemma-4-E2B-it.litertlm` | `2,583,085,056` bytes | `ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42` |
| Gemma 4 E4B | `litert-community/gemma-4-E4B-it-litert-lm` | `gemma-4-E4B-it.litertlm` | `3,654,467,584` bytes | `f335f2bfd1b758dc6476db16c0f41854bd6237e2658d604cbe566bcefd00a7bc` |

推荐下载地址：

- `https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm`
- `https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm`

### 3.3 为什么不把 Android AI Core 作为 v1 主线

Gemma 4 的 LiteRT-LM 模型卡已经明确说明：

- 在支持的 Android 设备上，Gemma 4 也可通过 Android AI Core 以 Gemini Nano 形态使用。
- 对生产应用而言，这条路线是官方推荐路径。

但这条路线不适合作为 OpenCray 当前 v1 主线，原因如下：

- `Android AI Core` 受系统版本、设备型号和预装能力约束。
- 它更适合“系统提供模型，App 调系统能力”，不适合“用户在设置页下载并切换 Gemma 4 两个模型包”的当前需求。
- OpenCray v1 需要一条跨更多 Android 机型、可自带模型资产管理的路线。

结论：

- `Android AI Core` 保留为二期 provider。
- v1 先把 `LiteRT-LM + 用户下载模型包` 跑通。

### 3.4 为什么 v1 不承诺 NPU

Google 官方当前已经在推动 LiteRT 的 NPU 路径，也出现了特定设备专用模型包，例如 Qualcomm 目标包。但对 OpenCray v1 来说，直接把“NPU 通用支持”对用户做承诺有两个问题：

- 不同芯片和系统版本的支持状况并不一致。
- 同一个模型族会开始分化成“通用包”和“设备专用包”，增加模型管理复杂度。

结论：

- v1 默认只暴露 `GPU / CPU`，默认选中 `GPU`。
- 设备专用 NPU 包进入二期评估，不纳入首发交付边界。

## 4. 当前代码基线诊断

### 4.1 当前运行时默认就是远端 provider

当前 `AppAgentSessionTaskRuntimeFactory` 在创建 LLM gateway 时，会固定构造 `ProviderRoute`，然后直接注入 `OpenAiCompatibleLiteLlmProviderClient`：

- [`app/src/main/kotlin/com/opencray/app/AppAgentSessionTaskRuntimeFactory.kt`](../app/src/main/kotlin/com/opencray/app/AppAgentSessionTaskRuntimeFactory.kt)

这意味着：

- 现在没有 provider 选择分支。
- 运行时默认假设模型一定来自远端 HTTP endpoint。

### 4.2 当前 provider routing 偏向 HTTP endpoint

`ProviderRoute` 允许 `baseUrl` 为空，但一旦提供就必须是 `http/https`：

- [`llm/src/main/kotlin/com/opencray/llm/ProviderRouting.kt`](../llm/src/main/kotlin/com/opencray/llm/ProviderRouting.kt)

这本身没有问题，因为端侧 provider 可以走 `baseUrl = null`。真正的问题不在 `ProviderRoute`，而在上层设置和 provider client 默认都假设 `baseUrl` 一定存在。

### 4.3 当前设置存储把“有 baseUrl + 有 apiKey”当成已配置

`LlmSettingsState.isConfigured()` 当前定义如下：

- `baseUrl` 非空
- `apiKey` 非空

代码位置：

- [`app/src/main/kotlin/com/opencray/app/LlmSettingsStore.kt`](../app/src/main/kotlin/com/opencray/app/LlmSettingsStore.kt)

这对端侧 provider 明显不成立。

### 4.4 当前校验逻辑是远端连通性校验

`LlmConfigFacade.validate(...)` 当前做的是：

- 解析 provider preset
- 组装远端 `ProviderRoute`
- 调用现有 gateway 发一轮真实 provider 请求
- 做 native tool capability probe

代码位置：

- [`app/src/main/kotlin/com/opencray/app/facade/llm/LlmConfigFacade.kt`](../app/src/main/kotlin/com/opencray/app/facade/llm/LlmConfigFacade.kt)

这条链路对端侧 provider 也不适用。

### 4.5 当前 Flutter LLM 设置页是纯远端表单

当前设置页固定展示这些远端字段：

- `protocol`
- `Base URL`
- `API key`
- `model`
- `reasoning effort`

代码位置：

- [`flutter_app/lib/features/settings/settings_feature.dart`](../flutter_app/lib/features/settings/settings_feature.dart)
- [`flutter_app/lib/core/models/opencray_llm_config.dart`](../flutter_app/lib/core/models/opencray_llm_config.dart)

这意味着：

- UI 架构可以复用。
- 但字段层级必须分成“远端模式”和“端侧模式”。

### 4.6 当前 runtime owner 已有集中式 provider 注入点

`InProcessOpenCrayRuntimeOwner` 当前集中创建 `liteLlmProviderClient` 并注入多处 runtime 组件：

- [`app/src/main/kotlin/com/opencray/app/InProcessOpenCrayRuntimeOwner.kt`](../app/src/main/kotlin/com/opencray/app/InProcessOpenCrayRuntimeOwner.kt)

这对新 provider 接入是好事，因为：

- 我们可以在这里统一选择远端还是端侧 provider
- 不需要到每个 interpreter / service 手动散改一套不同调用链

### 4.7 仓库里已经有 on-device model 状态展示模式可借用

`Media & Speech` 设置里已经有一套“本地模型包 + 下载状态”的快照模式：

- [`flutter_app/lib/core/models/opencray_media_speech_config.dart`](../flutter_app/lib/core/models/opencray_media_speech_config.dart)
- [`app/src/main/kotlin/com/opencray/app/MediaSpeechSettingsStore.kt`](../app/src/main/kotlin/com/opencray/app/MediaSpeechSettingsStore.kt)

这说明：

- OpenCray 已经有“设置页展示本地模型状态”的产品语言和桥接经验。
- LLM 端侧模型不需要从零发明另一套 UI / bridge 响应风格。

## 5. 正式架构

### 5.1 总体原则

正式架构遵守下面四条原则：

1. 远端与端侧共享同一条上层 LLM gateway
2. 端侧模型生命周期由宿主 runtime 持有，而不是由 Flutter 页面持有
3. 设置配置和模型安装状态分离存储
4. 模型下载和删除是宿主设置动作，不是模型可见工具

### 5.2 推荐的新概念模型

#### A. provider 模式

新增一个 LLM 连接模式概念，建议命名为：

- `LlmProviderMode.REMOTE_API`
- `LlmProviderMode.ON_DEVICE_MODEL`

`protocol` 继续只表示远端 API 方言：

- `openai`
- `openai_responses`
- `anthropic`

不要把 `litert_lm` 塞进 `LlmProviderProtocols`，因为它不是 HTTP API 协议。

#### B. 端侧模型选择

建议新增一个端侧选择结构，至少覆盖：

- `runtimeId`
  - 当前固定为 `litert_lm`
- `modelId`
  - 例如 `gemma-4-e2b-it`
- `packageFileName`
  - 例如 `gemma-4-E2B-it.litertlm`
- `backendPreference`
  - `auto` / `cpu` / `gpu`

#### C. 模型安装状态

安装状态不应塞进主 `LlmSettingsState`，建议独立存储，至少覆盖：

- `modelId`
- `versionTag`
- `sourceUrl`
- `localFilePath`
- `fileSizeBytes`
- `sha256`
- `installState`
  - `not_downloaded`
  - `downloading`
  - `downloaded`
  - `verifying`
  - `ready`
  - `failed`
- `downloadedBytes`
- `lastError`
- `installedAtEpochMs`

### 5.3 推荐分层

正式内部分层如下：

1. 设置与配置层
   - 负责 provider 模式、模型选择、backend 偏好、UI 快照
2. 模型资产管理层
   - 负责 catalog、下载、校验、删除、磁盘状态
3. 端侧推理会话层
   - 负责模型加载、session 复用、backend 选择、内存回收
4. provider adapter 层
   - 把 LiteRT-LM 的结果映射回 OpenCray 的 `LiteLlmProviderResult`

### 5.4 推荐组件

建议新增组件如下。

`app/` 侧新增：

- `LiteRtOnDeviceModelCatalog.kt`
  - 维护首发模型静态目录
- `LiteRtOnDeviceModelInstallStore.kt`
  - 持久化安装记录
- `LiteRtOnDeviceModelDownloadManager.kt`
  - 负责下载、校验、删除
- `LiteRtOnDeviceRuntime.kt`
  - 负责模型加载、session、backend 选择
- `LiteRtOnDeviceLlmProviderClient.kt`
  - 实现 `LiteLlmProviderClient`
- `LiteRtOnDeviceValidationService.kt`
  - 实现本地 readiness check

现有组件修改：

- `LlmSettingsStore.kt`
- `LlmProviderCatalog.kt`
- `LlmConfigFacade.kt`
- `OpenCraySettingsGateway.kt`
- `ServiceBackedOpenCraySettingsGateway.kt`
- `OpenCrayFlutterHostBridge.kt`
- `InProcessOpenCrayRuntimeOwner.kt`
- `AppAgentSessionTaskRuntimeFactory.kt`
- `OpenCrayHostRuntime.kt`
- `OpenCrayLocalRuntimeServer.kt`

`flutter_app/` 侧新增或修改：

- `opencray_llm_config.dart`
- `opencray_host_bridge.dart`
- `bridge_settings_facade.dart`
- `settings_feature.dart`

## 6. 数据与配置设计

### 6.1 `LlmSettingsState` 的建议演化

建议在现有状态上新增下面这些字段：

- `providerMode`
- `selectedOnDeviceModelId`
- `onDeviceRuntimeId`
- `onDeviceBackendPreference`
  - 取值收敛为 `gpu | cpu`
  - 默认值建议为 `gpu`

同时调整 `isConfigured()`：

- `REMOTE_API`
  - 仍然要求 `baseUrl + apiKey`
- `ON_DEVICE_MODEL`
  - 要求“已选择模型 + 模型安装状态为 ready”

### 6.2 `LlmProviderCatalog` 的建议演化

当前 `LlmProviderCatalog` 只有远端 provider 预设。

建议新增一个端侧选项：

- `id = "on_device_litert"`
- `title = "On-device (LiteRT-LM)"`
- `subtitle = "Run supported Gemma 4 models directly on this device."`

这个 provider 不是“自定义 provider”。

它是一级正式 provider，和 `openai`、`deepseek`、`openrouter` 并列出现在 provider sheet 里。

### 6.3 端侧模型 catalog

v1 的静态 catalog 建议直接写在 Kotlin 侧，避免首轮上线前引入远端 catalog 同步逻辑。

建议字段至少包含：

- `id`
- `title`
- `description`
- `runtimeId`
- `sourceUrl`
- `fileName`
- `sha256`
- `fileSizeBytes`
- `recommendedBackend`
- `minimumFreeSpaceBytes`
- `experimental`

v1 建议条目：

1. `gemma-4-e2b-it`
   - `sourceUrl`:
     - `https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm`
   - `fileName`:
     - `gemma-4-E2B-it.litertlm`
   - `sha256`:
     - `ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42`
   - `fileSizeBytes`:
     - `2583085056`

2. `gemma-4-e4b-it`
   - `sourceUrl`:
     - `https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm`
   - `fileName`:
     - `gemma-4-E4B-it.litertlm`
   - `sha256`:
     - `f335f2bfd1b758dc6476db16c0f41854bd6237e2658d604cbe566bcefd00a7bc`
   - `fileSizeBytes`:
     - `3654467584`

### 6.4 模型落盘目录

推荐把模型放在 App 私有目录，例如：

- `files/models/litert-lm/`

不建议：

- 放进仓库
- 放进 APK assets
- 放到外部共享目录

理由：

- 文件过大
- 需要安全校验
- 应避免被外部应用直接篡改

### 6.5 磁盘空间策略

下载前需要做最小空间检查。

v1 建议按下面的保守规则执行：

- 最低剩余空间要求：
  - `模型文件大小 + 1.5 GB`
- 如果不满足：
  - 阻止下载
  - 在设置页显示明确错误

理由：

- 下载需要临时文件
- 安装后还需要运行期工作内存和缓存余量

## 7. 运行时设计

### 7.1 正式数据流

端侧 LLM 标准链路如下：

`设置页 -> LlmSettingsStore -> Runtime owner -> LiteRtOnDeviceLlmProviderClient -> LiteRtOnDeviceRuntime -> LiteRT-LM session -> LiteLlmProviderResult`

这条链路里：

- Flutter 只负责配置和状态展示
- Kotlin 宿主持有模型实例和 session
- `LiteLlmGateway` 继续作为统一上层入口

### 7.2 provider client 设计

`LiteRtOnDeviceLlmProviderClient` 的职责如下：

- 读取当前 route metadata 和端侧模型选择
- 向 `LiteRtOnDeviceRuntime` 提交 prompt / messages / tool schema
- 把 LiteRT-LM 输出转换成：
  - `LiteLlmProviderResult.Success`
  - `LiteLlmProviderResult.Failure`
  - `LiteLlmProviderResult.Timeout`
- 在 metadata 中写入端侧能力与 backend 信息

不应承担的职责：

- 不负责下载模型
- 不负责长期持久化安装记录
- 不负责 UI 文案拼接

### 7.3 model runtime 设计

`LiteRtOnDeviceRuntime` 建议统一管理：

- 当前活动模型实例
- backend 选择
- session 复用
- 模型切换时的 unload
- 低内存时的显式释放

v1 建议采取保守策略：

- 同一时刻只保留一个活跃加载模型
- 切换模型时主动卸载上一个模型
- 当 providerMode 从端侧切回远端时，允许按需释放端侧模型

### 7.4 backend 策略

v1 backend 偏好定义：

- `gpu`
  - 默认值
  - 明确请求 GPU
  - 不可用时给出可理解错误，提示用户改成 `CPU`
- `cpu`
  - 强制 CPU

`NPU` 不进入 v1 公共 UI，原因前文已说明。

### 7.5 tool calling

首发要求保留 tool calling，原因如下：

- OpenCray 不是纯聊天应用，agent runtime 依赖工具循环。
- 如果端侧模型没有 tool calling，切成端侧后产品能力会大幅缩水。

但 v1 只承诺：

- 单轮结构化 tool call 返回
- 与当前 `LiteLlmStructuredToolCall` 对齐

v1 不承诺：

- 并行 tool calls
- 视觉工具输入
- 复杂多模态 tool payload

如果 LiteRT-LM 当前版本对 tool calling 的结构约束与现有 gateway 有偏差，应在 provider client 层做适配，不要污染整个上层协议。

### 7.6 超时与中断

端侧推理的超时语义不能直接照搬远端 HTTP。

建议：

- 继续沿用 `LiteLlmProviderResult.Timeout`
- timeout 由宿主本地计时器控制
- cancel 走宿主 runtime 的本地中断接口

这样上层不需要知道“端侧是本地线程中断，还是远端 socket 超时”。

## 8. 设置页方案

### 8.1 Provider sheet

在 LLM provider sheet 中新增一个正式项：

- `On-device (LiteRT-LM)`

用户选择这个 provider 后：

- 当前页面切换成端侧模式
- 隐藏远端相关字段

### 8.2 首发设置页结构

首发的端侧模式不应再沿用“单屏塞满所有字段”的布局，而应改成标准纵向滚动设置页：

- 页面可以滚动，不要求把所有控件都放进首屏可视区。
- Pencil 原型用两张画板表达同一页面的两个滚动位置：
  - `LLM Screen - On-device LiteRT-LM`
  - `LLM Screen - On-device LiteRT-LM (Scrolled)`
- 首屏重点展示 provider/source、模型选择和前半段生成参数。
- 下滚后展示剩余生成参数和运行时选项。
- 远端 provider 专属字段在端侧模式下直接隐藏。

这更符合 iOS 设置页的阅读节奏，也更符合 OpenCray 这个场景下“先选模型，再调运行参数”的操作顺序。

### 8.3 端侧模式下显示的内容

首发原型当前建议显示：

- `Model source`
  - 用 segmented control 在 `Cloud` / `On-device` 间切换
- `On-device model`
  - 首发只展示两个官方 Gemma 4 LiteRT-LM 包
  - 模型行内直接展示安装状态和主动作
- `Sampling & limits`
  - 生成相关默认值
- `Runtime`
  - 端侧运行偏好

建议隐藏：

- `protocol`
- `Base URL`
- `API key`
- 自定义本地模型导入入口

建议保留：

- `model`
  - 由“用户自由输入”改成“从端侧 catalog 选择”
- `system prompt`
  - 继续沿用现有宿主配置能力，但不强行塞进首屏顶部

### 8.4 首发参数项与交互

首发 UI 先覆盖这 7 个端侧参数：

- `Max context window`
- `Max tokens`
- `Top K`
- `Top P`
- `Temperature`
- `Accelerator`
- `Thinking`

控件形态建议固定如下：

- `Max context window`
  - 独立数字输入框
- `Max tokens`
  - 滑条 + 左侧最小值 + 右侧数字输入框
- `Top K`
  - 滑条 + 左侧最小值 + 右侧数字输入框
- `Top P`
  - 滑条 + 左侧最小值 + 右侧数字输入框
- `Temperature`
  - 滑条 + 左侧最小值 + 右侧数字输入框
- `Accelerator`
  - segmented control：`GPU / CPU`
- `Thinking`
  - segmented control：`Off / On`

交互要求：

- 滑条与右侧数字输入框必须双向同步。
- 当用户手改数字时，滑条位置同步更新并做范围裁剪。
- `Sampling & limits` 与 `Runtime` 分成两个卡片区块，不必强行在首屏同时完整露出。
- 卡片内边距建议不小于 `16pt`，分组间距建议在 `12pt` 到 `14pt` 之间。
- `Accelerator` 默认选中 `GPU`。

### 8.5 模型状态文案

每个模型条目至少应显示：

- 模型名称
- 大小
- 当前状态
- 当前下载进度
- 是否已选中

建议状态集合：

- `未下载`
- `下载中`
- `校验中`
- `可用`
- `下载失败`
- `文件损坏`

### 8.6 用户动作

v1 建议支持的用户动作：

- 下载
- 取消下载
- 删除模型
- 设为当前模型
- 验证模型

不建议 v1 就支持：

- 手动导入自定义 `.litertlm`
- 多模型同时预热
- 更底层的 runtime / delegate 专家参数面板

### 8.7 设置页桥接协议

`loadLlmConfig()` 返回的快照建议扩展为：

- `providerMode`
- `selectedOnDeviceModelId`
- `onDeviceRuntimeId`
- `onDeviceBackendPreference`
  - 建议仅使用 `gpu | cpu`
- `onDeviceThinkingEnabled`
- `onDeviceMaxContextWindow`
- `onDeviceMaxTokens`
- `onDeviceTopK`
- `onDeviceTopP`
- `onDeviceTemperature`
- `onDeviceModels`

`onDeviceModels[]` 建议包含：

- `id`
- `title`
- `subtitle`
- `fileSizeBytes`
- `installState`
- `downloadedBytes`
- `downloadStatus`
- `sha256Verified`
- `isSelected`

另外新增宿主动作：

- `downloadOnDeviceLlmModel(modelId)`
- `cancelOnDeviceLlmModelDownload(modelId)`
- `deleteOnDeviceLlmModel(modelId)`
- `updateOnDeviceGenerationDefaults(config)`

如果不想新增太多 method channel 方法，也可以折中为：

- `performLlmOnDeviceAction(actionId, modelId, payload?)`

但从可读性和测试上看，建议直接使用显式方法名。

### 8.8 二期预留：自定义本地模型导入与自动检测

这一块明确不进入当前首发原型和首发实现范围，仅保留为二期能力预研：

- v1 仍然只交付 Gemma 4 `E2B` / `E4B` 官方模型包。
- 当前 Pencil 首发原型不再展示“Custom local model”入口。
- 自定义本地模型导入不进入当前首发代码范围。
- 下面这套自动检测规则仅用于后续二期设计约束和实现预研。

建议的 UI 原则：

- 默认走 `Auto-detect`，不要要求用户一开始手填全部模型能力。
- 首屏只让用户明确填写：
  - `Model alias`
  - `Model file / manifest`
- 自动检测出的低频配置放进不显眼的 `Advanced options`，但必须允许手动覆盖。
- `Preflight check` 作为最终收口动作，在保存前做一次静态校验和轻量 probe。

建议在主区优先展示的检测结果：

- `Conversation format`
- `Context / output`
- `Preflight check`

建议收进 `Advanced options` 的检测结果：

- `System prompt support`
- `Tool calling support`
- `Media support`
- `Structured output support`

自动检测顺序建议固定如下：

1. 先读取模型包 manifest、`config.json`、运行时元数据。
2. 再读取 `tokenizer_config`、`chat_template`、special tokens。
3. 根据模板和 token 规则推断 `Conversation format`。
   - 例如 `Qwen / ChatML`
   - `Gemma`
   - `Llama 3`
   - `Mistral / [INST]`
   - `Raw prompt`
4. 从 `max_position_embeddings`、`context_length` 或等价字段推断 `Max context`。
5. 基于 `Max context` 自动给出保守的 `Max output` 默认值。
   - 建议初始规则：`min(4096, maxContext / 4)`
6. 从模板结构推断是否支持独立 `system` role。
7. 从模型结构或伴随组件推断 `Media support`。
   - 是否存在 vision encoder
   - 是否存在 image token / projector
8. 对 `Tool calling` 和 `Structured output` 不只做静态判断，还应结合一次轻量 probe。
   - 发送极短的 tool schema / JSON 约束测试
   - 判断返回是否稳定、可解析
9. 任一步骤无法确定时，结果回退为 `Unknown`，并要求用户手动覆盖。

`Preflight check` 建议覆盖两类检查：

- 静态检查：
  - 文件存在
  - manifest 可解析
  - 必填字段齐全
  - tokenizer / template / special tokens 自洽
- 轻量运行检查：
  - runtime 能否初始化
  - 最小 prompt 能否成功走通
  - tool calling / structured output probe 是否通过

如果后续要把自动检测结果桥接到 Flutter，建议快照层至少预留：

- `detectionMode`
  - `auto`
  - `manual_override`
- `detectedConversationFormat`
- `detectedMaxContext`
- `detectedMaxOutput`
- `detectedSystemPromptSupport`
- `detectedToolCallingSupport`
- `detectedMediaSupport`
- `detectedStructuredOutputSupport`
- `detectionConfidence`
- `preflightStatus`
- `preflightErrorSummary`

## 9. 校验与可观察性

### 9.1 端侧 validate 的正式定义

端侧 validate 不再表示“连通性”，而表示“本地 readiness”。

应检查：

- 当前 providerMode 是否为端侧
- 当前模型是否已下载
- 本地文件是否存在
- SHA-256 是否匹配
- LiteRT-LM runtime 是否能成功创建实例
- 所选 backend 是否可初始化
- 能否完成最小文本 prompt smoke test

### 9.2 metadata

端侧 provider 成功返回后，建议在 metadata 中写入：

- `providerMode=on_device_model`
- `onDeviceRuntime=litert_lm`
- `onDeviceModelId=...`
- `onDeviceBackend=gpu|cpu`
- `onDeviceInstalled=true`
- `onDeviceSha256Verified=true|false`

如果能从 LiteRT-LM runtime 获取更多信息，也可补：

- `onDeviceContextWindowTokens`
- `onDeviceToolCallingSupported`

### 9.3 失败分层

端侧失败应区分为：

- `MODEL_NOT_INSTALLED`
- `MODEL_FILE_MISSING`
- `MODEL_HASH_MISMATCH`
- `BACKEND_UNAVAILABLE`
- `MODEL_LOAD_FAILED`
- `LOCAL_INFERENCE_TIMEOUT`
- `LOCAL_INFERENCE_CANCELLED`

这样设置页和聊天运行时都能给出更可理解的反馈。

## 10. 实施阶段

### Phase 1：配置模型和设置桥接

目标：

- 先把“远端 / 端侧”双模配置打通

工作项：

- 扩展 `LlmSettingsState`
- 扩展 `LlmConfigSnapshot`
- 扩展 Flutter `OpenCrayLlmConfigSnapshot`
- 在设置页 provider sheet 中新增端侧 provider
- 让端侧模式隐藏远端字段

成功标准：

- 设置页可切换到端侧模式
- 端侧选择可持久化
- 切回远端模式不破坏现有配置

### Phase 2：模型 catalog、下载和安装状态

目标：

- 把 Gemma 4 E2B / E4B 资产管理打通

工作项：

- 新增端侧模型静态 catalog
- 新增安装状态 store
- 新增下载管理器
- 新增 SHA-256 校验
- 新增删除模型动作
- 在设置页展示进度与状态

成功标准：

- 两个模型都可在设置页下载
- 下载后状态可恢复
- 错误状态可见
- 删除后磁盘文件和状态都能清理

### Phase 3：LiteRT-LM 文本推理打通

目标：

- 让聊天主链路能真正走本地模型

工作项：

- 接入 LiteRT-LM Android runtime
- 实现 `LiteRtOnDeviceRuntime`
- 实现 `LiteRtOnDeviceLlmProviderClient`
- 在 runtime owner 注入 provider 选择逻辑
- 让 `AppAgentSessionTaskRuntimeFactory` 按模式分支

成功标准：

- 选中端侧模型后可以完成一轮文本对话
- 远端 provider 不受影响
- 模型切换能够生效

### Phase 4：tool calling 与本地 validate

目标：

- 让端侧模型接入 agent 主循环，不只是单次聊天

工作项：

- 对接 LiteRT-LM function calling 输出
- 映射成 `LiteLlmStructuredToolCall`
- 实现端侧 validate
- 把 capability snapshot 接回设置页和 runtime

成功标准：

- 端侧模型可触发 tool calling
- validate 返回本地 readiness 结果
- 运行时对 tool 能力的判断不再默认沿用远端能力

### Phase 5：体验与稳定性硬化

目标：

- 让功能可长期使用，而不是只在开发机上跑通

工作项：

- session 复用与模型卸载策略
- 低内存处理
- backend fallback 策略
- 下载中断恢复
- 更清晰的错误文案
- 增量性能日志

成功标准：

- 连续多轮聊天稳定
- 前后台切换不出现明显状态错乱
- 下载与删除不会造成持久化脏状态

### Phase 6：后续二期

不纳入 v1，但建议保留的方向：

- `Android AI Core` provider
- Qualcomm / MediaTek 特定设备包
- 本地图像输入
- 本地音频输入
- Qwen LiteRT-LM provider catalog

## 11. 建议的文件改动面

### 11.1 Kotlin / Android

建议修改：

- [`app/src/main/kotlin/com/opencray/app/LlmSettingsStore.kt`](../app/src/main/kotlin/com/opencray/app/LlmSettingsStore.kt)
- [`app/src/main/kotlin/com/opencray/app/LlmProviderCatalog.kt`](../app/src/main/kotlin/com/opencray/app/LlmProviderCatalog.kt)
- [`app/src/main/kotlin/com/opencray/app/facade/llm/LlmConfigFacade.kt`](../app/src/main/kotlin/com/opencray/app/facade/llm/LlmConfigFacade.kt)
- [`app/src/main/kotlin/com/opencray/app/OpenCraySettingsGateway.kt`](../app/src/main/kotlin/com/opencray/app/OpenCraySettingsGateway.kt)
- [`app/src/main/kotlin/com/opencray/app/ServiceBackedOpenCraySettingsGateway.kt`](../app/src/main/kotlin/com/opencray/app/ServiceBackedOpenCraySettingsGateway.kt)
- [`app/src/main/kotlin/com/opencray/app/OpenCrayFlutterHostBridge.kt`](../app/src/main/kotlin/com/opencray/app/OpenCrayFlutterHostBridge.kt)
- [`app/src/main/kotlin/com/opencray/app/InProcessOpenCrayRuntimeOwner.kt`](../app/src/main/kotlin/com/opencray/app/InProcessOpenCrayRuntimeOwner.kt)
- [`app/src/main/kotlin/com/opencray/app/AppAgentSessionTaskRuntimeFactory.kt`](../app/src/main/kotlin/com/opencray/app/AppAgentSessionTaskRuntimeFactory.kt)
- [`app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt`](../app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt)
- [`app/src/main/kotlin/com/opencray/app/OpenCrayLocalRuntimeServer.kt`](../app/src/main/kotlin/com/opencray/app/OpenCrayLocalRuntimeServer.kt)

建议新增：

- `app/src/main/kotlin/com/opencray/app/LiteRtOnDeviceModelCatalog.kt`
- `app/src/main/kotlin/com/opencray/app/LiteRtOnDeviceModelInstallStore.kt`
- `app/src/main/kotlin/com/opencray/app/LiteRtOnDeviceModelDownloadManager.kt`
- `app/src/main/kotlin/com/opencray/app/LiteRtOnDeviceRuntime.kt`
- `app/src/main/kotlin/com/opencray/app/LiteRtOnDeviceLlmProviderClient.kt`
- `app/src/main/kotlin/com/opencray/app/LiteRtOnDeviceValidationService.kt`

### 11.2 Flutter

建议修改：

- [`flutter_app/lib/core/models/opencray_llm_config.dart`](../flutter_app/lib/core/models/opencray_llm_config.dart)
- [`flutter_app/lib/core/bridge/opencray_host_bridge.dart`](../flutter_app/lib/core/bridge/opencray_host_bridge.dart)
- [`flutter_app/lib/features/settings/bridge_settings_facade.dart`](../flutter_app/lib/features/settings/bridge_settings_facade.dart)
- [`flutter_app/lib/features/settings/settings_feature.dart`](../flutter_app/lib/features/settings/settings_feature.dart)

## 12. 测试清单

### 12.1 JVM / host tests

需要新增或更新的测试：

- `LlmSettingsStoreTest`
  - 端侧模式持久化
  - 远端与端侧切换
- `LlmConfigFacadeTest`
  - 端侧 validate 分支
- `AppAgentSessionTaskRuntimeFactory...Test`
  - provider client 分支选择
- `OpenCrayFlutterHostBridgeTest`
  - 端侧模型下载动作桥接
- `OpenCrayHostRuntime` 相关测试
  - 快照字段和写命令映射

### 12.2 Android 集成测试

建议覆盖：

- 模型未下载时切到端侧 provider
- 下载完成后切换为端侧模型
- 删除当前模型后的回退行为
- 后台服务重连后的状态恢复

### 12.3 Flutter tests

建议覆盖：

- 设置页 provider sheet 出现端侧项
- 端侧模式隐藏 `Base URL` / `API key`
- 模型状态卡片渲染
- 下载中 / 可用 / 失败三种状态

## 13. 风险与规避

### 风险 1：把端侧 provider 硬塞进 HTTP 协议枚举

风险：

- 会污染现有 `LlmProviderProtocols`
- 会让远端专用逻辑扩散更多 `if (protocol == ...)`

规避：

- 单独引入 `providerMode`
- `protocol` 继续只服务远端 API

### 风险 2：把安装状态和用户配置混成一个 store

风险：

- 删除模型、下载中断、配置回滚会互相污染

规避：

- 主配置和安装状态分开存储

### 风险 3：模型由 Flutter 页面持有

风险：

- 页面销毁即丢 session
- 前后台切换和长任务更脆

规避：

- 模型实例由宿主 runtime / service 持有

### 风险 4：v1 一上来就承诺 NPU

风险：

- 不同设备表现差异大
- 模型分发复杂度快速爆炸

规避：

- v1 只公开 `GPU / CPU`
- 设备专用包放二期

### 风险 5：把多模态一起打包到首发

风险：

- 当前聊天主链路虽然已有附件结构，但端侧多模态接入会明显扩大范围

规避：

- v1 只做文本 + tool calling

## 14. 明确不做的内容

本计划明确不包含下面这些事项：

- 自定义本地 `.litertlm` 导入
- 模型 marketplace
- 端侧视觉输入
- 端侧音频输入
- 多模型同时常驻
- 针对每一类芯片维护独立包矩阵
- `Android AI Core` provider 的正式接入

## 15. 推荐的第一实现切片

如果要把实现拆成最小但有价值的第一刀，建议顺序如下：

1. 扩展 `LlmSettingsStore` 和 LLM 设置页，让 provider 可以切到 `On-device (LiteRT-LM)`
2. 加入静态模型 catalog 和安装状态结构，但先用 mock 状态把 UI 跑通
3. 做真实下载、校验和删除
4. 接入 `LiteRtOnDeviceLlmProviderClient` 的文本推理
5. 再补 tool calling 和 validate

原因：

- 这条顺序先把产品结构立住，再把最重的原生 runtime 接入放到后面。
- 即便中途停在 Phase 2，也已经把端侧模型的设置和资产管理接口收口好了，不会返工。

## 16. 参考资料

### 本地代码

- [`app/src/main/kotlin/com/opencray/app/AppAgentSessionTaskRuntimeFactory.kt`](../app/src/main/kotlin/com/opencray/app/AppAgentSessionTaskRuntimeFactory.kt)
- [`app/src/main/kotlin/com/opencray/app/InProcessOpenCrayRuntimeOwner.kt`](../app/src/main/kotlin/com/opencray/app/InProcessOpenCrayRuntimeOwner.kt)
- [`app/src/main/kotlin/com/opencray/app/LlmProviderCatalog.kt`](../app/src/main/kotlin/com/opencray/app/LlmProviderCatalog.kt)
- [`app/src/main/kotlin/com/opencray/app/LlmSettingsStore.kt`](../app/src/main/kotlin/com/opencray/app/LlmSettingsStore.kt)
- [`app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt`](../app/src/main/kotlin/com/opencray/app/OpenAiCompatibleLiteLlmProviderClient.kt)
- [`app/src/main/kotlin/com/opencray/app/facade/llm/LlmConfigFacade.kt`](../app/src/main/kotlin/com/opencray/app/facade/llm/LlmConfigFacade.kt)
- [`flutter_app/lib/features/settings/settings_feature.dart`](../flutter_app/lib/features/settings/settings_feature.dart)
- [`flutter_app/lib/core/models/opencray_llm_config.dart`](../flutter_app/lib/core/models/opencray_llm_config.dart)

### 外部资料

- LiteRT overview:
  - https://ai.google.dev/edge/litert/overview
- LiteRT GenAI overview:
  - https://ai.google.dev/edge/litert/genai/overview
- LiteRT-LM repository:
  - https://github.com/google-ai-edge/LiteRT-LM
- Gemma 4 edge announcement, published 2026-04-02:
  - https://developers.googleblog.com/bring-state-of-the-art-agentic-skills-to-the-edge-with-gemma-4/
- Gemma 4 E2B LiteRT-LM model card:
  - https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
- Gemma 4 E4B LiteRT-LM model card:
  - https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm
- Android AI Core overview:
  - https://developer.android.com/ai/aicore
