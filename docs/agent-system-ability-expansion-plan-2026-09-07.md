# Agent 系统能力扩展计划（2026-09-07）

## 一、目标与参考

让 OpenCray 的 agent 获得调用 Android 系统 API 的能力——创建系统闹钟/定时器、读写日历、
本地通知、TTS、应用列表与启动、系统设置页跳转——并为此建立一套可复用的"系统能力工具"
框架，后续能力按同一模式批量接入。

参考对象为 OmniBot（github.com/omnimind-ai/OmniBot，仓库曾在 2026-09-07 浅克隆调研）。
其系统级能力为：定时任务（子代理流程，OpenCray 已有对等物 `ScheduledTask*` + `spawn_agent`）、
系统闹钟（仅提醒用途）、日历创建/查询/更新、音频播放控制，外加 MCP 协议扩展。对照后
OpenCray 的真实缺口是：系统闹钟/定时器 intent、日历 provider、本地通知工具、TTS、
应用列表/启动、设置页跳转，以及配套的**运行时权限 UX 链路**。

OmniBot 的权限体验（授权页/权限行/弹层/声明式注册表/透明权限 Activity/PermissionRequired
事件闭环）作为第二层权限 UX 的交互模式参考；视觉按仓库规约使用 `ui/.../design/` 共享
token 重做，Pencil 原型仍是视觉事实来源。

## 二、现状与结论（2026-09-07 调研）

- 工具注册是三处硬编码联动：`AgentToolCatalog.kt`（`toolDefinitions()`）、
  `AgentTooling.kt` 的 `when` 分发（L297-370 附近）、
  `ToolCapabilityClassifier.classifyPolicyToolClass`（未登记的工具名直接抛错）。
- Android Context 不进 runtime：纯 Kotlin 接口（范本 `ScheduledTaskManager.kt` L393）
  → app 层 `fromContext(context)` 实现 → 经 `OpenCrayToolDispatcherConfig` 注入
  （范本 `AppScheduledTaskManager.kt` + `InProcessOpenCrayRuntimeOwner.kt` L451）。
- 可选宿主服务通过 config 判空条件注册工具（Python runtime、沙箱即此模式）。
- MCP 模块为 exposure-only（注册表/信任态/凭证引用），无 JSON-RPC client 与 tools/call
  桥；走 MCP 接系统能力目前基础不够，列为远期。
- manifest 已有：`POST_NOTIFICATIONS`、`SCHEDULE_EXACT_ALARM`、`RECEIVE_BOOT_COMPLETED`、
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 等；无 `SET_ALARM`、`READ/WRITE_CALENDAR`。
- 通知基础设施（`RuntimeNotificationCoordinator`）与权限引导页
  （`StrongBackgroundSettingsAccess`）已在 app 层就绪可复用。

## 三、架构设计

### 3.1 工具面（第一层：六个工具，全部免运行时权限或已有权限）

| 工具名 | 能力 | Android 实现要点 | 权限 |
|---|---|---|---|
| `system_alarm_create` | 系统闹钟（时钟 App） | `AlarmClock.ACTION_SET_ALARM` + `EXTRA_HOUR/MINUTE/MESSAGE/SKIP_UI` | `SET_ALARM`（普通权限） |
| `system_timer_start` | 系统定时器 | `AlarmClock.ACTION_SET_TIMER` + `EXTRA_LENGTH/MESSAGE/SKIP_UI` | `SET_ALARM` |
| `system_notification_post` | 本地通知（标题+正文+点击回跳） | 复用通知协调器的通道/样式 | `POST_NOTIFICATIONS`（已有） |
| `system_app_list` | 列出已装应用 | PackageManager + `<queries>` 全量可见声明 | 无 |
| `system_app_open` | 启动应用 | 包名 → launch intent | 无 |
| `system_settings_open` | 白名单设置页跳转 | 电池忽略/通知/精确闹钟等 `Settings.ACTION_*` | 无 |

命名统一 `system_` 前缀，与 `workspace_`、`ScheduledTask` 族并列。刻意**不做**通用
`fire_intent` 工具：它会让策略退化成"批准任意系统动作"，坚持一能力一工具。

### 3.2 策略层

- `PolicyToolClass` 新增两个类：
  - `SYSTEM_QUERY`：只读系统状态（`system_app_list`）。矩阵对齐 READ_FILE 档：
    SAFE/AUTO/DEVELOPER 全 ALLOW。
  - `SYSTEM_ACTION`：对系统产生可见副作用（闹钟/定时器/通知/开应用/设置页）。
    矩阵对齐 WRITE_FILE 档：SAFE → ASK（标准审批），AUTO/DEVELOPER → ALLOW。
  - 两者 `requiresTargetPath()=false`（与 EXECUTE_COMMAND 同型）。
- 新增运行时 intent 模型 `SystemAbilityIntent`（对齐 `ExecutionIntent` 的事后审计定位）：
  `action`（如 `alarm.set`）+ `actionSummary`（参数摘要，进 metadata 键
  `systemAction`/`systemActionSummary`）。intent 元数据从 pipeline 结果统一发出，
  下游不依赖工具名判断。
- 权限缺失不是策略拒绝：工具执行发现运行时权限未授予时返回 DENIED 专用错误码
  `SYSTEM_PERMISSION_REQUIRED`（见 3.4），不与 `APPROVAL_REQUIRED` 混用——前者引导
  "去系统授权"，后者引导"审批本次动作"。

### 3.3 宿主接口（runtime 纯 Kotlin，app 实现）

```kotlin
interface SystemAbilityGateway {
  fun createAlarm(request: SystemAlarmCreateRequest): SystemAlarmResult
  fun startTimer(request: SystemTimerRequest): SystemTimerResult
  fun postNotification(request: SystemNotificationRequest): SystemNotificationResult
  fun listApps(request: SystemAppListRequest): SystemAppListResult
  fun openApp(request: SystemAppOpenRequest): SystemAppOpenResult
  fun openSettings(request: SystemSettingsOpenRequest): SystemSettingsOpenResult
}
```

请求/结果为不可变 data class，全部纯 Kotlin 类型（String/Int/Boolean/Long/List），路径
类型不出现。`OpenCrayToolDispatcherConfig` 增加可空 `systemAbilityGateway` 字段，
判空条件注册——JVM 测试环境不注入则六个工具不暴露，模式同 Python runtime。

`AppSystemAbilityGateway`（app 层）持 `appContext`，逐方法实现：
- `createAlarm/startTimer`：`AlarmClock` intent + `EXTRA_SKIP_UI=true`，无时钟 App 时
  返回 `available=false` 的失败结果（错误码 `SYSTEM_ABILITY_UNAVAILABLE`）。
- `postNotification`：复用 `RuntimeNotificationCoordinator` 既有渠道；渠道未授权
  POST_NOTIFICATIONS 时返回 `SYSTEM_PERMISSION_REQUIRED` + `missingPermissions`。
- `listApps`：`queryIntentActivities`；`openApp`：`getLaunchIntentForPackage`，
  未安装返回 `SYSTEM_ABILITY_UNAVAILABLE`。
- `openSettings`：**白名单字典**（key → `Settings.ACTION_*`），未知 key 报参数错误。

### 3.4 错误码（E6xxx 启用为系统能力段）

E6xxx 从"MCP（保留）"改定义为"系统与设备能力"，与 `docs/error-codes.md` 同步更新：
`SYSTEM_PERMISSION_REQUIRED`、`SYSTEM_ABILITY_UNAVAILABLE`、
`SYSTEM_ACTION_FAILED`。短码由 `UserFacingErrorCodesTest` 的唯一性/格式约束覆盖。

### 3.5 权限 UX 链路（第二层，独立批次）

OmniBot 权限体验的交互模式（视觉按仓库 token 重做）：

1. 工具三段式：检查 → 请求（透明权限 Activity + 目的说明条，Service 进程可用）→
   拒绝则返回 `SYSTEM_PERMISSION_REQUIRED` + missing 列表，轮次终止，**不删除不重放**
   当前对话轮次，保留的用户消息即重试入口。
2. 事件闭环：DENIED 结果 → 聊天流权限卡（`requestApproval` 型）→ 授权页/弹层
   （最小范围提示 + 实时状态联动 + 自动续授权 + 防呆不重复弹同页）。
3. 声明式 `PermissionSpec` 注册表（id/文案/图标/checkMethod/openMethod/场景层级），
   `OpenCrayFlutterHostBridge` 提供原生方法端点。

第一层六个工具无需此链路（无运行时权限请求），`postNotification` 在通知权限已授予的
前提下直接可用；链路随日历/联系人等第二层能力一起交付。

### 3.6 明确不做（本期）

- 短信/电话（`SEND_SMS`/`READ_SMS` 受 Play 政策强限制；电话止步 `ACTION_DIAL` 的评估
  留待需要时单独做）。
- 通用 fire_intent、控制他 App MediaSession（需 NotificationListener，成本高）。
- 位置、相机、剪贴板等高敏感能力——待第一、二层跑通后逐个评估。
- MCP 工具桥（远期独立立项）：补 JSON-RPC client + tools/call 桥 + 远端 schema 合入。

## 四、改动清单（第一层）

| # | 文件 | 改动 |
|---|---|---|
| 1 | `policy/src/main/kotlin/com/opencray/policy/ModePolicy.kt` | `PolicyToolClass` + `SYSTEM_QUERY`/`SYSTEM_ACTION`，矩阵、`requiresTargetPath` |
| 2 | `runtime/.../policy/ToolCapabilityClassifier.kt` | 两个 classify 的工具名映射 |
| 3 | `runtime/.../policy/ToolPolicyPipeline.kt` 或 intent 文件 | `SystemAbilityIntent` 模型 + metadata |
| 4 | `runtime/.../SystemAbilityGateway.kt`（新） | 接口 + 请求/结果类型 |
| 5 | `runtime/.../AgentToolModels.kt` | `OpenCrayToolDispatcherConfig.systemAbilityGateway` 字段 |
| 6 | `runtime/.../AgentToolCatalog.kt` | 六个工具定义（config 判空条件注册） |
| 7 | `runtime/.../AgentTooling.kt` | dispatch 分支 + 六个实现函数（plan→gate→resultMetadata） |
| 8 | `core/.../error/UserFacingErrorCodes.kt` + `docs/error-codes.md` | E6 段三个错误码 |
| 9 | `app/.../AppSystemAbilityGateway.kt`（新） | `fromContext` 实现 |
| 10 | `app/.../AppAgentSessionTaskRuntimeFactory.kt` + `InProcessOpenCrayRuntimeOwner.kt` | 注入 |
| 11 | `app/src/main/AndroidManifest.xml` | `SET_ALARM` 权限 + `<queries>` intent 声明 |
| 12 | 测试 | dispatcher 工具测试（JVM fake gateway）+ classifier/policy 单测 |

## 五、验收标准

1. `.\gradlew.bat test` 全绿（含新增测试）。
2. 六工具在注入 gateway 的会话出现在模型工具清单，未注入会话不出现。
3. SAFE 模式下 `system_alarm_create` 产生标准 ASK 审批（带动作摘要），AUTO 下直接放行；
   `system_app_list` 任意模式直接放行；metadata 含 `capabilityKind`/`systemAction` 等共享键。
4. 缺权限/无时钟 App/未安装应用三类失败分别映射到三个 E6 错误码，文案经共享格式化器。
5. 真机冒烟：对话中"明早 7 点叫我"→ 审批后系统时钟 App 出现闹钟。

## 执行状态

- 第一层完成（2026-09-07）：六个系统工具（`system_alarm_create`/`system_timer_start`/
  `system_notification_post`/`system_app_list`/`system_app_open`/`system_settings_open`）
  全链路落地——`PolicyToolClass` 新增 `SYSTEM_QUERY`/`SYSTEM_ACTION`（SAFE 档查询放行、
  动作 ASK 标准审批，AUTO/DEVELOPER 放行）+ `SystemAbilityIntent` intent 模型；runtime 侧
  `SystemAbilityGateway` 接口 + config 判空条件注册；app 侧 `AppSystemAbilityGateway`
  （`AlarmClock` intent + `EXTRA_SKIP_UI`、白名单设置页、`RuntimeNotificationChannelRegistry`
  复用、PackageManager 查询）经 `InProcessOpenCrayRuntimeOwner` 注入；manifest 增
  `SET_ALARM` + `<queries>`；E6xxx 启用为系统能力段（`SYSTEM_PERMISSION_REQUIRED`/
  `SYSTEM_ABILITY_UNAVAILABLE`/`SYSTEM_ACTION_FAILED`），`docs/error-codes.md` 同步。
  新增 9 个 dispatcher 工具测试 + classifier 6 工具映射测试 + policy 矩阵 6 case；
  全量 `.\gradlew.bat test` 通过。
- 待做：第二层权限 UX 链路（OmniBot 模式：聊天权限卡 + 授权页/弹层 + 声明式
  PermissionSpec 注册表 + 透明权限 Activity）；日历/联系人等运行时权限工具；
  第三批高敏感能力逐个评估；MCP 工具桥远期独立立项。
