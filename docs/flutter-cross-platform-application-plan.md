# OpenCray 跨平台应用层改造方案

Last updated: 2026-03-13

## 目标

OpenCray 的跨平台形态应当稳定为：

```text
Flutter UI
  -> typed screen facade / snapshot contract
  -> host-owned application layer
  -> shared core and persistence
  -> platform adaptor
  -> Android / HarmonyOS / future hosts
```

这个目标意味着两件事同时成立：

- Flutter UI 本身是可跨平台复用的
- 核心应用层也必须尽量可跨平台复用

真正需要按平台重写的部分应限制在 adaptor，而不是扩散到页面和业务状态里。

## 结论

当前推荐架构是：

1. Flutter 负责表现层和交互捕获
2. 核心应用层负责页面快照、动作分发、状态组合、持久化协调
3. 平台 adaptor 负责文件选择、权限、分享、通知、系统入口、宿主生命周期等平台能力

因此，后续跨平台开发的核心不是“继续增加页面”，而是“把页面下方的契约做稳定”。

## 分层定义

### Layer 1. Flutter Presentation

职责：

- 页面结构
- 组件布局
- 动画
- 临时交互态
- 路由展示

不应负责：

- 运行时创建
- 文件权限语义
- 系统 intent 解释
- 持久化细节
- 平台能力判断

### Layer 2. Typed Facade Contract

职责：

- 为 Flutter 提供稳定的 snapshot
- 接收 typed action
- 隐藏宿主实现细节

统一约定优先采用：

- `loadSnapshot()`
- `watchSnapshot()`
- `dispatch(ActionDto)`

避免页面直接依赖：

- Android `Intent`
- `SharedPreferences`
- SAF tree URI
- 平台路径规则
- ad hoc method channel 方法名

### Layer 3. Host-owned Application Layer

职责：

- `SettingsFacade`
- `FilesFacade`
- `SkillsFacade`
- `ChatFacade`
- 组合本地 store、runtime、policy、persistence
- 把宿主状态整理成页面 snapshot

这一层应该尽量是平台无关的数据和业务逻辑。

### Layer 4. Platform Adaptor

职责：

- Android adaptor
- future HarmonyOS adaptor
- 文件权限
- 工作区选择
- 系统分享 / 打开文件
- 通知
- 宿主入口映射

这一层允许平台差异存在，但差异不能向上泄漏。

## 当前仓库里的落地原则

### 1. 旧原生 UI 不再参与运行时页面渲染

旧 Android `Activity/View` 只保留兼容入口或测试用途。

### 2. 新 Flutter 页面必须优先消费 facade snapshot

不要继续把页面内容散落在 widget 文件里硬编码。

### 3. 页面入口必须统一成 route contract

例如：

- `/chat`
- `/skills`
- `/files`
- `/settings/privacy`

平台只负责把系统入口映射到 route contract。

### 4. 文件与权限必须通过 adaptor 暴露

Flutter 只应知道：

- 当前工作区状态
- 是否允许访问
- 用户动作结果

Flutter 不应知道：

- SAF grant
- tree URI
- Android 文档树路径

## 推荐实施顺序

### Phase 1. Settings contract

目的：

- 先建立一套 typed snapshot / facade 模式
- 用低风险页面验证架构

本阶段交付：

- `SettingsOverviewSnapshot`
- `SettingsFacade`
- Flutter `SettingsFeatureScreen` 改为消费 facade

### Phase 2. Files contract

目的：

- 把最重的平台差异收敛进 adaptor

本阶段交付：

- `WorkspaceAccessSnapshot`
- `FilesWorkbenchSnapshot`
- `FilesFacade`
- Android SAF adaptor

### Phase 3. Skills contract

目的：

- 把技能列表、启用状态、安装来源从页面硬编码改为 facade 提供

### Phase 4. Chat contract

目的：

- 把 chat 页面从静态 seed 状态推进到 typed action / snapshot / runtime event bridge

## 本次开始落地的实现边界

本次改造先做：

- 补充这份方案文档
- 为 `Settings` 建立第一套 typed contract
- 让 Flutter Settings 首页先消费 facade overview snapshot

本次不做：

- 完整 method channel / pigeon bridge
- HarmonyOS adaptor
- Files SAF bridge 重构
- Chat runtime bridge 全量接入

## 对跨平台问题的直接回答

是的，目标方案就是：

- 核心应用层可以跨平台复用
- Flutter UI 也可以跨平台复用
- 平台差异通过 adaptor 和宿主桥接处理

但注意：

- Flutter UI 是“表现层跨平台”
- 核心应用层是“业务与状态组合层跨平台”
- 系统能力接入不是跨平台的，必须由每个平台各自实现 adaptor

也就是说，跨平台复用的是页面和契约，不是 Android 入口代码本身。
