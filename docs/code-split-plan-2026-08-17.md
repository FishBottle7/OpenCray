# 单文件臃肿情况统计与打散计划（2026-08-17）

## 一、现状统计

主源码（`app/src/main` + `runtime/src/main` + `flutter_app/lib`）共 496 个 Kotlin/Dart 文件、约 234,700 行。

| 指标 | 数值 |
|---|---|
| ≥ 5000 行的文件 | 7 个，合计 72,968 行（占主源码 31%） |
| ≥ 2000 行的文件 | 18 个，合计 105,471 行（占主源码 45%，文件数仅占 3.6%） |
| 行数前 10 的文件 | 合计 84,602 行（占主源码 36%） |

分模块看：

| 模块 | 文件数 | 行数 | 平均行/文件 | 问题 |
|---|---|---|---|---|
| `flutter_app/lib` | 69 | 75,074 | **1,088** | 单个 chat 文件占全 lib 的 32% |
| `app/src/main` | 287 | 106,643 | 372 | 根包 260 个文件平铺，仅 3 个子包；头部文件极大 |
| `runtime/src/main` | 140 | 53,325 | 381 | 顶层包两个巨型文件占顶层包 80% |
| 其余模块（core/filesystem/skills/mcp/llm/policy/persistence/ui） | 62 | ~10,800 | ~175 | 健康 |

测试侧同样存在巨型文件（拆源时应同步拆测试）：`flutter_app/test/chat_feature_screen_test.dart` 17,965 行、`OpenCrayHostRuntimeTest.kt` 13,318 行、`OpenCrayRuntimeServiceHostTest.kt` 9,455 行、`OpenCrayAgentRuntimeTest.kt` 9,450 行、`OpenCrayAgentRuntimeServiceBootstrapTest.kt` 8,583 行、`OpenAiCompatibleLiteLlmProviderClientTest.kt` 7,473 行、`settings_feature_test.dart` 6,336 行。

## 二、臃肿文件清单（主源码，按行数排序）

| # | 文件 | 行数 | 内部构成概要 |
|---|---|---|---|
| 1 | `flutter_app/lib/features/chat/chat_feature_screen.dart` | 24,124 | 三个文件合一：纯快照合并库（118–2705）、10,354 行的 God State 类（2842–13195）、约 120 个 widget 类（13196–23784）+ 设计 tokens（23785–24124） |
| 2 | `runtime/.../AgentTooling.kt` | 10,714 | 工具模型 + 9,887 行的 `OpenCrayToolDispatcher`（约 60 个工具的 handler）+ schema 尾部 |
| 3 | `runtime/.../OpenCrayAgentRuntime.kt` | 10,106 | 会话主循环 + `OpenCrayAgentRuntime`（9,887 行、305 个函数，含 2,251 行子代理机制） |
| 4 | `app/.../OpenCrayHostRuntime.kt` | 9,421 | 实现 5 个网关接口的 God 类（9,010 行），混合设置/技能/聊天投影/序列化/本地化 |
| 5 | `flutter_app/lib/features/settings/settings_feature.dart` | 6,438 | 路由中枢 + 7 个页面（含 2,386 行的 `_LlmSettingsPage`）+ 1,576 行共享 widget 工具箱 |
| 6 | `app/.../OpenAiCompatibleLiteLlmProviderClient.kt` | 6,093 | 单类实现 OpenAI-chat / Responses / Anthropic 三种方言的请求构建、解析、SSE 流 |
| 7 | `flutter_app/lib/features/settings/settings_debug_pages.dart` | 6,072 | 6 个调试页 + 2,137 行顶层纯格式化函数 |
| 8 | `flutter_app/lib/core/bridge/opencray_seed_bridge.dart` | 3,776 | 130 方法假实现的桥 + 1,525 行顶层种子数据构建 |
| 9 | `app/.../AppAgentSessionTaskRuntimeFactory.kt` | 3,760 | `executeTask` 编排 + 设备端预热 + 转录附件 markdown（与 HostRuntime 重复的副本） |
| 10 | `flutter_app/lib/features/files/files_feature.dart` | 3,424 | 1,162 行 State + 1,117 行展示 widget + 808 行对话框 |
| 11 | `app/.../E2BEnvdNativeCommandExecution.kt` | 3,213 | 传输层 + 手写 protobuf 编解码 + 989 行重连状态机 |
| 12 | `app/.../ProjectionOnlyOpenCrayChatRuntimeGateway.kt` | 2,550 | 快照只读投影网关；**大量逻辑是 HostRuntime 的第二份拷贝** |
| 13 | `app/.../E2BCodeInterpreterPythonRuntime.kt` | 2,548 | 沙箱生命周期 + 工作区同步 + HTTP 传输层 + DTO |
| 14 | `app/.../AgentSessionRuntimeManager.kt` | 2,335 | 4 个 SPI 接口 + 1,720 行 `ManagedAgentSessionHandle` |
| 15 | `flutter_app/lib/features/skills/skills_feature.dart` | 2,332 | 1,344 行 State + 约 880 行自包含叶子 widget |
| 16 | `app/.../OpenCrayRuntimeServiceGatewayBundle.kt` | 2,272 | 4 个独立类同居一文件（bundle + 3 个 ServiceOwned 网关） |
| 17 | `flutter_app/lib/core/models/opencray_chat_snapshot.dart` | 2,195 | 34 个纯数据类按域可分组 |
| 18 | `app/.../ChatSessionLocalStore.kt` | 1,939 | 会话存储 + 消息操作 + 工作区记录内部机制 |

1500 行级别的次级目标：`RecentToolObservationSupport.kt`（1,843）、`ProjectionOnlyChatDebugProjector.kt`（1,775）、`safety_settings_pages.dart`（1,724）、`OpenCrayConfigurableMediaProviderClient.kt`（1,661）、`SkillPackageManager.kt`（1,613）、`opencray_markdown.dart`（1,573）、`opencray_local_runtime_bridge.dart`（1,566）、`ScheduledTaskWorkManager.kt`（1,561）、`opencray_platform_bridge.dart`（1,437）。

### 跨文件重复（拆分时必须顺带消除）

结构性分析发现多处整段复制，拆分是去重的契机：

1. 聊天运行时事件 payload 序列化：`OpenCrayHostRuntime.kt:6481–6668` ≈ `ProjectionOnlyOpenCrayChatRuntimeGateway.kt:1956–2140`
2. Run/托管进程快照 → Map：`HostRuntime:5579–5680` ≈ 投影网关 `1852–1944`
3. 子代理活动快照：`HostRuntime:3237–3461` ≈ 投影网关 `1267–1491`（类型也各有一份）
4. 投影聊天消息：`HostRuntime:3697–4085` ≈ 投影网关 `410–741`
5. 附件 markdown 兼容层：`HostRuntime:7258–7491` ≈ `AppAgentSessionTaskRuntimeFactory.kt:2611–3135`
6. 恢复中断投影：`AgentSessionRuntimeManager.kt:1996–2223` ≈ 投影网关 `1740–1837`
7. 设置/技能网关方法体：`HostRuntime:881–1756` ≈ `ServiceOwnedSettingsGateway` / `ServiceOwnedSkillsGateway`
8. E2B HTTP 传输层：`E2BEnvdNativeCommandExecution.kt:292–423` 与 `E2BCodeInterpreterPythonRuntime.kt:2271–2467` 平行实现

## 三、打散计划

### 执行原则

- **纯移动不改逻辑**：每一步只做搬移与必要的可见性调整，行为变更（去重、重命名）单独成步、单独提交。
- **同包优先**：Kotlin 拆分尽量先落在同包新文件（零 import 变更、测试不受影响），建子包时才动 import。
- **保持外部 API 不变**：Flutter 侧沿用现有 barrel（`chat_feature.dart`）与 `part` 机制；Kotlin 侧沿用现有接口文件。
- **每文件一个 PR/提交序列**：每次拆一个源文件，跑 `.\gradlew.bat test` + `dart analyze flutter_app`（+ 可行时 `flutter test`），配套测试文件同批拆分。
- **目标体量**：单文件 ≤ 1,500 行，单类 ≤ 800 行；God State 类拆出协作对象后主体 ≤ 2,000 行。

### P0 — chat_feature_screen.dart（24,124 行 → 约 15 个文件）

收益最大且外部耦合极小（全仓库只有 `opencray_app.dart` / `opencray_app_shell.dart` 经 barrel 使用其中 2 个公开符号），兄弟文件模式（`chat_models.dart` 等）已就绪。按文件内部天然边界拆：

| 新文件 | 来源行段 | 约行数 | 内容 |
|---|---|---|---|
| `chat_runtime_merge.dart` | 118–1252 | ~1,100 | 快照解析/合并/版本号（纯函数） |
| `chat_state_equivalence.dart` | 1254–2556 | ~1,300 | 替换策略、display-signature、状态等价比较（纯函数） |
| `chat_live_draft_projection.dart` | 2557–2705 + State 内草稿解析 | ~200 | 流式草稿文本投影、部分 JSON 解析 |
| `chat_realtime_queue.dart` | 65–117、2800–2815 | ~120 | 实时事件重同步队列模型 |
| `chat_runtime_projector.dart` | State 7326–13195 中投影/映射/追踪历史构建 | ~5,700 → 再按域拆 2–3 个文件 | 从 State 抽出 `ChatRuntimeProjector` 协作类，入参为快照 + 墓碑/覆盖等少量状态 |
| `chat_widgets_chrome.dart` | 13196–13725、14660–15489 | ~1,300 | 顶栏、工具栏、滚动装配、时间戳分隔、动效 |
| `chat_widgets_approvals.dart` | 13918–14767 | ~850 | 待审批浮层全家 |
| `chat_widgets_run_trace.dart` | 15597–18725 | ~3,100 | 运行轨迹气泡/检查器/沙箱预览（最大簇，可再分出 `chat_widgets_run_trace_inspector.dart`） |
| `chat_widgets_message.dart` | 18726–20107 + 15510–15596 | ~1,600 | 消息气泡、长按菜单、内联附件解析、markdown 样式 |
| `chat_widgets_attachments.dart` | 20108–21830 + 21167–21625 | ~1,700 | 图片/文件/语音附件瓦片与预览对话框 |
| `chat_widgets_composer.dart` | 21831–23373 | ~1,550 | 输入区、附件条、Todo 面板、命令菜单 |
| `chat_widgets_sessions_drawer.dart` | 23374–23784 | ~410 | 会话抽屉 |
| `chat_design_tokens.dart` | 23785–24124 | ~340 | `_ChatPalette` / `_ChatTextStyles` / `_ChatDecorations`（改为公开或库内共享） |
| `chat_feature_screen.dart`（保留） | State 骨架 + build | ≤ 2,000 | 生命周期、流订阅、发消息/审批/会话操作等编排 |

注意点：`_isRuntimeProjectedAgentMessageId`（15573 行）被 State 与 widget 两侧使用，需放入共享模块；widget 簇依赖 tokens，tokens 先行拆出。

### P1 — runtime 模块两大文件（20,820 行）

**AgentTooling.kt**（顶层包两个巨型文件占 80%，已有 `policy/`、`process/`、`skills/` 等子包可承接）：

| 新文件/位置 | 来源行段 | 约行数 | 内容 |
|---|---|---|---|
| `AgentToolModels.kt`（同包） | 99–285 | ~190 | `AgentToolParameter/Definition/Call/Result`、`OpenCrayToolDispatcherConfig` |
| `AgentToolSchema.kt`（同包） | 10316–10713 | ~400 | toJsonSchema/strict 归一化（纯函数，测试在同包零改动） |
| `process/AgentProcessObservation.kt` | 287–427 + 7050–7953 | ~1,050 | 观察游标/交付模型、tracker、快照渲染（只依赖 `process/` 类型） |
| 新 `media/` 包 | 2899–4151 + 5978–6423 | ~1,700 | 媒体生成/轮询/取消/产物持久化；共享 `mediaJobs` 状态收进新的 `MediaJobCoordinator`，`registerMediaArtifactsFromResult` 保留为 dispatch() 后置钩子 |
| `ScheduledTaskTooling.kt` 或新包 | 4294–5307 | ~1,000 | 计划任务 7 个工具 + 触发器解析/渲染 |
| `skills/SkillPackageTools.kt` | 7954–9115 | ~1,160 | 技能包安装/更新/检查工具 |
| memory/session 工具 → 对应子包 | 9232–9584 | ~350 | `searchProjectedMemory` 等 |
| `ToolArgumentParsers.kt` / `ToolResultRenderers.kt`（同包） | 9709–9837 / 6424–6553 + 10001–10315 | ~600 | JsonObject 取值扩展、文本工具、渲染器 |
| `AgentToolCatalog.kt`（同包） | 499–1116 | ~620 | `definitions()` 工具目录 |
| `AgentTooling.kt`（保留） | 构造、dispatch() 路由、preflight、委派桥、copy 工厂 | ~2,000 | 调度核心 |

**OpenCrayAgentRuntime.kt**：

| 新文件/位置 | 来源行段 | 约行数 | 内容 |
|---|---|---|---|
| `OpenCrayAgentEngine.kt`（同包） | 10085–10106 | ~25 | 工厂类（独立，零风险） |
| `subagent/` 收编 | 6985–9236 | ~2,250 | 子代理生成/等待/邮箱/审批续跑机制；需先把类级 `pendingApproved/RejectedSubAgentResume` 状态与 `PromptTurnCursor` 的子集通过参数传递 |
| `GatewayMessagePlanner`（context 相邻或同包） | 2256–2944 + 6486–6691 | ~850 | 网关消息规划、Responses/本地续跑决策 |
| `AssistantDraftExtractor`（同包） | 1675–2068 + 4194–4293 | ~600 | 草稿流可见文本/部分 JSON（纯逻辑） |
| `ModelActionParser`（同包） | 1273–1392 + 2945–3154 | ~500 | 动作批解析 |
| `GatewayRecoveryPolicy`（同包） | 1393–1674 | ~280 | 轮次重试/失败分类 |
| `web/` 收编 | 5785–6023 | ~240 | Provider 原生网络搜索 |
| 检查点 → 与 `OpenCrayPromptResumeState.kt` 同文件或相邻 | 9312–9552 | ~240 | 提示检查点 |
| `OpenCrayAgentRuntime.kt`（保留） | execute、executePromptTask 循环、PromptTurnCursor、buildResultMetadata、结果构造 | ~4,000 | 会话核心 |

留在核心的依据：`PromptTurnCursor`（19 个可变字段）是几乎每个区块都会碰的状态枢纽，凡强依赖它的区块先留在核心，抽出的协作类通过入参接收所需子集。

### P2 — app 模块 God 类与投影去重（约 2.2 万行）

**OpenCrayHostRuntime.kt**（9,421 行，实现 5 个网关接口）分三步：

1. **按接口竖切**（低风险）：`OpenCrayLocalHostGateway` / `OpenCrayShellGateway` / `OpenCrayChatRuntimeGateway` / `OpenCraySkillsGateway` / `OpenCraySettingsGateway` 五个接口已有独立文件。把 HostRuntime 按接口实现拆成同包的 `HostSettingsGatewayImpl.kt`（881–1336，456 行）、`HostSkillsGatewayImpl.kt`（1337–1756，420 行）等，HostRuntime 持有并委托。
2. **抽出共享投影模块**（消重关键）：把"投影消息构建（3697–4085）、事件 payload 序列化（6481–6668）、run 快照 → Map（5579–5680）、子代理活动快照（3237–3461）"抽成同包 `projection/`（如 `ChatProjectionSupport.kt`、`RuntimeSnapshotSerialization.kt`），让 `ProjectionOnlyOpenCrayChatRuntimeGateway.kt` 和 `ServiceOwnedChatRuntimeGateway` 改为引用同一实现，消除上文重复清单第 1–4、7 项。
3. **杂项归位**：`toMap()` 映射器群（8452–8769）、审批文案组装（7839–8330）、附件 markdown 兼容层（7258–7491，与 RuntimeFactory 共享后仅留一份）、`companion` 里的 `createWithRuntimeAccess` 工厂（8959–9158）各自成文件。

**OpenAiCompatibleLiteLlmProviderClient.kt**（6,093 行，纯 JVM 逻辑、无 Android 依赖）：三方言的函数组几乎不相交，按方言拆到 `facade/llm/`（或新 `llm/dialect/`）：

- `OpenAiChatDialect.kt`（请求 584–、解析 834–960、消息组装 2691–3152、SSE 4284–4461、自动续跑 2165–2258）
- `OpenAiResponsesDialect.kt`（请求 671–、解析 973–1145、SSE 4462–5534、续跑决策）
- `AnthropicDialect.kt`（请求 626–、解析 1209–1230、消息 3153–3500、SSE 4137–4283、可见文本 5535–5601）
- `LlmProviderClientCore.kt`（`execute`/`compactConversation` 入口、URL、metadata/prompt-cache 1645–2142、错误重试、可见草稿 5649–5995、嵌套类型）

**E2B 两文件**（均零 Android 依赖）→ 新 `e2b/` 子包：
- `E2BTransport.kt`：合并两份 `UrlConnection` 传输层（重复清单第 8 项）
- `E2BProtoCodec.kt`：protobuf 编解码 + ProtoWriter/Reader（E2BEnvd 472–709、3082–3213）
- `E2BEnvdCommandController.kt`：重连状态机（1736–2724）与其 runner/factory
- `E2BSandboxRuntime.kt`：Python 运行时主体 + 工作区同步 + 归档管理
- DTO/常量按归属随迁

**其余 app 模块**：
- `OpenCrayRuntimeServiceGatewayBundle.kt` → 4 个类拆 4 文件（`ServiceOwnedChatRuntimeGateway.kt` 1,076 行单独成文件），最机械的一步
- `AgentSessionRuntimeManager.kt` → 先抽 4 个 SPI 接口到 `AgentSessionRuntimeContracts.kt`（168–346 行，trivial），`ManagedAgentSessionHandle` 的运行记录持久化（1484–1872）与恢复修复（1996–2223）再各自成文件
- `AppAgentSessionTaskRuntimeFactory.kt` → 设备端预热（1175–1738）、转录事件汇（2295–2610）、转录附件 markdown（2611–3135，改为引用共享实现）各成文件
- `ChatSessionLocalStore.kt` → 持久化类型（1839–1939）成 `ChatSessionPersistedModels.kt`；工作区记录内部机制（1198–1557 + 1631–1779）成 `ChatWorkspaceRecordSupport.kt`

### P3 — Flutter 其余（settings / files / skills / bridges / 模型）

**settings 库**（已用 `part` 机制，继续在其内拆分，私有名互通、零 import 变更）：
- `settings_feature.dart`：`_LlmSettingsPage`（1740–4125，2,386 行）→ 新 part `llm_settings_pages.dart`；共享 widget 工具箱（4863–6438，约 30 个 `_Prototype*`/`_Settings*`）→ 新 part `settings_widgets.dart`；`_MemoryDebugPage`（476 行）与网络搜索页归入相应 part
- `settings_debug_pages.dart`：顶层格式化函数（3936–6072，2,137 行，纯函数零耦合）→ 新 part `settings_debug_formatters.dart`；`_ContextMemoryTracePage`（1,529 行）可独立成 part
- `agent_settings_pages.dart`：`_agentGradientSets` 颜色数据（409–835）→ `agent_gradient_data.dart`；24 个共享 widget（2956–4098，1,143 行）→ `agent_settings_widgets.dart`
- `safety_settings_pages.dart`：11 个原型 widget（1250–1724）与 `settings_widgets.dart` 合并归一

**files_feature.dart**：对话框 4 个（2367–3174，808 行）→ `files_dialogs.dart`；展示 widget（1250–2366）→ `files_widgets.dart`；路径/字节工具（3192–3424）→ `files_path_utils.dart`；主文件留 State（1,162 行，可再拆控制器）
**skills_feature.dart**：叶子 widget 约 880 行（1410–2332）→ `skills_widgets.dart`
**opencray_chat_snapshot.dart**：34 个纯数据类按分析报告的域分组（聊天基础/运行事件/run 记忆聚合/托管进程/增量与草稿/子代理与根聚合等）拆 5–6 个文件，barrel 保持 `export` 兼容
**opencray_markdown.dart**：三个近独立子系统 → 渲染主体与链接路由（164–412）、图片解析/渲染/预览（413–1186）、选区投影与纯文本（1192–1573）
**三个桥**（platform 1,437 / local_runtime 1,566 / seed 3,776）：四个桥实现的域顺序完全一致（镜像 `OpenCrayHostBridge` 的约 130 个方法），按相同域边界拆 per-domain `part`/mixin（shell / files / agents / settings / llm / personalization / mcp+safety / skills / chat）；seed 桥的 1,525 行种子数据构建先行拆到 `seed_data_*.dart`

### P4 — 测试文件跟随

每个源文件拆分完成后的同批提交中，把对应巨型测试按新文件主题拆分（如 `OpenCrayHostRuntimeTest.kt` 13,318 行 → HostSettings/Skills/ChatProjection/Serialization 各自的测试类；`chat_feature_screen_test.dart` 17,965 行 → merge/equivalence/projector/各 widget 簇测试）。拆测试只搬移不改断言。

## 四、建议实施顺序与理由

| 顺序 | 批次 | 预计拆出文件数 | 理由 |
|---|---|---|---|
| 1 | 各文件内的纯函数/常量/DTO（P0 tokens、P1 模型+schema、P3 格式化函数与种子数据） | ~10 | 零风险热身，立刻降低每个大文件的观感尺寸 |
| 2 | chat_feature_screen.dart 全量拆分 | ~15 | 全仓最大单点，占 lib 32%，外部仅 2 个符号依赖，收益/风险比最高 |
| 3 | AgentTooling.kt + OpenCrayAgentRuntime.kt | ~16 | runtime 顶层包 80% 集中在这两个文件；同包拆分测试零改动 |
| 4 | HostRuntime 竖切 + 投影共享模块（连带 ProjectionOnly 网关、GatewayBundle、RuntimeFactory 去重） | ~15 | 消除 8 处跨文件重复，是长期维护收益最大的一步 |
| 5 | LLM client 按方言拆 + E2B 子包 | ~10 | 边界清晰（方言不相交、E2B 零 Android 依赖），可独立验证 |
| 6 | Flutter settings/files/skills/模型/桥 | ~20 | part 机制与 barrel 保证兼容 |
| 7 | 测试文件跟随拆分 | ~15 | 收尾 |

单文件臃肿的根因是"God State/God 类 + 按接口平铺"两种模式；本计划除了拆文件，也把结构惯例固化下来：新代码按域入子包/子目录，Kotlin 单类超过约 800 行、Dart 单文件超过约 1,500 行时即触发拆分评审。
