# OpenCray UI 重构续期计划（2026-08-27）

## 一、背景与已完成基线

「Refined Workbench」视觉语言已落地：设计令牌（`opencray_tokens.dart`）、主题（`opencray_theme.dart`）、共享组件与底部导航、Chat 调色板中枢、Files/Skills/Settings 对齐。本轮（2026-08-27）在此之上补了交互层：

| 项 | 落点 |
|---|---|
| 共享交互控件 | 新增 `lib/core/design/opencray_controls.dart`：`OpenCraySwitch`（滑块位移 + 按压缩放 + 触感）、`OpenCraySegmentedControl`（指示块滑动）、`OpenCraySelectionCheck`（环填充 + 勾号弹入）、`OpenCrayInkSurface` |
| 卡内点击反馈 | 卡片是 `DecoratedBox`，卡内 `InkWell` 的水波被加到外层 shell `Material`、绘制在卡片背景之下＝全设置页无反馈；卡片内层补裁剪透明 `Material`，主题 `splashColor` 从 `transparent` 改为 7% 品牌蓝 + `InkRipple`；另翻转 15 处「InkWell 包不透明 Container」写法 |
| 设置首页 | 灰底裸行列表改为按类别 4 张分组卡（图标底板 + 缩进分隔线 + chevron），设备卡加品牌渐变图标块 |
| 行控件 | 步进器改一体化 pill 组（− │ 值 │ +）、返回键 36dp 可点区、`Text('›')` 统一为 `Icons.chevron_right_rounded`、开关行整行可点 |

统一验证口径（后续每阶段沿用）：

- `flutter analyze`：`lib/` 必须零问题（`test/` 现存 7 条 `unnecessary_import` 属既有项，不在本计划范围）
- `flutter test`：当前基线 **436 通过 / 23 失败**，23 条失败全部位于 `chat_run_trace_test` / `chat_projector_test` / `chat_widgets_chrome_test` / `chat_merge_test` / `chat_attachments_test`（既有，非回归）。每阶段前后按**失败测试名逐条比对**，新增为空才算通过
- 临时 golden 抓图核对版式（测试字体渲染为方块，只核版式/颜色/形状/尺寸），阶段结束即删除

## 二、缺口清单（2026-08-27 实测）

| # | 缺口 | 证据 | 影响 |
|---|---|---|---|
| 1 | 完全没有暗色模式 | `lib/app/opencray_app.dart:45` 仅 `theme: OpenCrayTheme.light()`，无 `darkTheme`/`ThemeMode`；`OpenCrayColors` 全为单值 `const` | 高 |
| 2 | Chat 页未收口 | feature 内硬编码 `Color(0x…)` 共 63 处，其中 chat 占 38（`chat_widgets_chrome.dart` 14、`chat_design_tokens.dart` 10、`chat_widgets_composer.dart` 6）；`chat_widgets_run_trace_inspector.dart` 内 `AnimatedContainer`/`AnimatedDefaultTextStyle` 为 0 个，actor tab（`:185 _buildActorTabs`）、展开收起、inspector 开合均为瞬变 | 高 → 阶段 B 已收 |
| 3 | 页头四套并行 + 死代码 | chat/files/settings/skills 各自定义 28px 标题 + eyebrow + 副标题；`core/design/opencray_widgets.dart` 中 `OpenCrayPageTemplate`/`OpenCrayTabPlaceholder`/`OpenCrayShellHeader`/`OpenCrayPillButton`/`OpenCrayTopBar` **均为 0 调用点、0 测试覆盖** | 中高 |
| 4 | 图标两套体系 | `CupertinoIcons` 28 处（5 个文件，集中在 files/chat）对 Material `Icons` 144 处 | 中 |
| 5 | 无障碍与字号缩放 | 全应用 `Semantics(` 仅 10 处；零 `textScaler` 处理；开关/步进/导航使用固定高度（28/38/44/48/54） | 中 |
| 6 | 加载与刷新态 | 13 个 `CircularProgressIndicator`、0 骨架屏、`RefreshIndicator` 0 处（Skills/Files 仅文字 Refresh + 后台轮询） | 中 |
| 7 | 平板/横屏 | 应用壳与设置页无 `LayoutBuilder`、无最大宽度约束，单列直接拉伸 | 低中 |
| 8 | 动效纵深 | 无 hero/共享元素（开会话、开文件预览）、列表无入场 stagger、底部 sheet 无拖拽把手 | 低中 |
| 9 | 触感 | 仅新增控件有 `HapticFeedback`，发送/审批/保存/删除等主操作没有 | 低 → 阶段 B 已补发送与审批，保存/删除待办 |
| 10 | 弹窗主题化 | agent 设置内手写 `Dialog` + `Container` 绕过主题 `dialogTheme` | 低 |

## 三、执行顺序与阶段拆解

顺序原则：先做低风险高收益的结构收敛（A），再补主战场 Chat（B，同时为暗色铺路），最后做工作量最大的暗色模式（C）。无障碍与加载态（D）夹带在 A/B 中顺路完成。

### 阶段 A：页头统一与死代码清理（已完成）

目标：四个 tab 与设置子页的标题区共用一套组件，数值对齐 `docs/mobile-ui-layout-spec.md` 的 *Large-title page template*（标题→摘要 6dp、标题块→首节 20dp）。

实际落地：

1. `OpenCrayTypography`（`opencray_tokens.dart`）：`pageEyebrow` / `pageTitle`（28 / 1.12 / w700 / −0.6）/ `pageSummary` 与 `eyebrowGap` `summaryGap` `headerBottomGap` 三个间距常量，成为页头唯一数值来源。
2. `OpenCrayPageHeader`（`opencray_widgets.dart`）：eyebrow / title / summary / `leading`（返回键）/ `trailing`（右侧动作）/ `bottomGap`。
3. 接入点：设置首页 + **25 处设置子页页头**（脚本迁移后逐个复核缩进与换行）+ Agents 页 + Skills 页 + Files `_TitleRow`（`trailing` 承载「完成」按钮）。`_SettingsTextStyles` 中 `eyebrow`/`pageTitle`/`pageTitleSubpage`/`subtitle` 四个重复定义随之删除。
4. Chat 只改排版不动结构：`_ChatTextStyles.pageTitle` 由 30px 改为指向 `OpenCrayTypography.pageTitle`（28px，符合本仓库自己的 spec），其滚动联动的颜色补间与折叠行为保持原样。
5. 删除 5 个 0 调用点 0 覆盖的组件（`OpenCrayPageTemplate`、`OpenCrayTabPlaceholder`、`OpenCrayShellHeader`、`OpenCrayPillButton`、`OpenCrayTopBar`）与 `OpenCrayPageContentBuilder` typedef，`opencray_widgets.dart` 由 555 行降到 ~430 行。
6. 字号缩放（缺口 5 的一部分）：步进器改 `IntrinsicHeight` + `minHeight`，agent 三个按钮的 `height:` 改 `minHeight:` + 垂直内边距，Files 弹窗关闭键补 `semanticLabel`（按 locale 从 `copy` 取）。

一个实测教训：底部导航从 `SizedBox(height: 54)` 改成 `ConstrainedBox(minHeight: 54)` 会连带改变壳层布局，导致 6 条壳层/文件测试失败（Chat 页头掉出已构建区域、选择工具条相对 tab bar 的偏移变化）。最终改为**保持 54dp 固定高度 + 用 `MediaQuery.withClampedTextScaling(maxScaleFactor: 1.2)` 限制标签放大**，与 Material `NavigationBar` 的策略一致，默认字号下零布局变化。

验收：`flutter analyze` lib 零问题；`flutter test` 436 通过 / 23 失败，失败集与基线**逐条一致**（新增与消失均为空）。

### 阶段 B：Chat 视觉与动效补齐（已完成）

实际落地：

1. **硬编码色收口**：chat 内 38 处 `Color(0x…)` 全部进入令牌，`chat_design_tokens.dart` 成为唯一中枢——`_ChatPalette` 补语义色（`highRiskSurface`/`highRiskReasonText`/`summaryQuietTitle`/`surfaceClear`/`borderClear`/`linkOnDarkSurface`/`previewBarrier`/`imageBarrier`/`textSelectionOnAccent`/`textSelectionOnSurface`/`runTraceRetryMark`），新增 `_ChatGlass`（顶栏玻璃 9 个 rest/active 端点、composer 光泽 4 个、浮层描边与阴影 3 个）与 `_ChatGradients.outboundBubble`。数值全部保持原样，只命名不换色。
2. **上提到核心令牌**：`OpenCrayShadows.brandGlow` / `brandGlowLarge`（`Color(0x3D2563EB)` 原本在 composer 发送键、会话抽屉 CTA、agent 主按钮各写一遍）、`OpenCrayColors.scrimSoft`（抽屉遮罩）、`OpenCrayColors.warningMark`（小尺寸状态点比 `warning` 更亮）。
3. **seed 数据对齐真实通路**：`chat_seed_data.dart` 两个 `accentColor` 是 `primaryTint` / `surfaceMuted` 的陈旧近似值（`0xFFE6F0FF` vs `0xFFEAF1FE`），真实附件通路（`chat_composer_attachments.dart`）早就用令牌了；这里改成同一组令牌，属于对齐而非近似替换。
4. **inspector 动效**：actor tab 改 `_RunTraceActorTab`——`AnimatedContainer` 承载 `primaryTint` 选中药丸 + `AnimatedDefaultTextStyle` 补色与字重，带 `Semantics(button, selected)`；长文本展开收起包 `AnimatedSize`（子节点仍即时切换，只动高度）；箭头 `AnimatedRotation` 的硬编码 180ms 换成 `OpenCrayMotion.quick`。
5. **inspector 开合改页面路由**：`showDialog` + `Dialog.fullscreen` → `openCrayHorizontalPageRoute` + `Material`，与设置子页同一套下钻语言。
6. **approval 复核**：terracotta 语义色、左侧风险轨、警示文案一律不动；HIGH RISK 徽章补 `Icons.warning_amber_rounded`（形状冗余，不依赖色觉）；三个决策键从 `GestureDetector` + `Container` 改 `AnimatedContainer` + `OpenCrayInkSurface` + `InkWell`（填充态用 `Colors.white24` 水波，7% 品牌蓝在 terracotta 上看不见），`height: 38` 改 `minHeight: 38` + 内边距。
7. **触感**（缺口 9 的主操作部分）：approval 三键与 composer 的 `_CircleButton`（发送 / 中断 / 加号）补 `HapticFeedback.selectionClick()`；`_CircleButton` 同时补 0.9 按压缩放。触感一律在业务回调**之前**触发——`chat_message_menu_test` 断言 `platformCalls.last.method == 'Clipboard.setData'`，放在之后会打翻它。

一个实测教训（比阶段 A 那次更隐蔽）：inspector 改成 `openCrayHorizontalPageRoute` 后，`chat_projector_test` 新挂 2 条「open inspector 持续收更新」的用例。根因是 **不透明路由会把下层路由挪到 offstage，offstage 不做 layout，会话列表的 `ListView` 就不再构建子节点**——而 inspector 的实时更新恰恰依赖气泡 rebuild 时把新 trace 推给 `ValueNotifier`。`showDialog` 的半透明 barrier 路由不会触发这条路径，所以原来看不出来。修法是给 `openCrayHorizontalPageRoute` 加 `opaque` 参数（默认 `true`，不影响设置页既有调用点），inspector 传 `opaque: false`——sheet 自己是实心背景，视觉无差别。用 A/B 对跑（先还原 `showDialog` 确认 8 条 = 基线，再上非不透明路由确认仍是 8 条）定位。

验收：`flutter analyze` lib 零问题；`flutter test` 436 通过 / 23 失败，失败集与基线**逐条一致**。chat 目录现在只有中枢文件里有 `Color(0x…)`（37 处，全部是令牌定义本身），其余 feature 还剩 9 处（`agent_gradient_data.dart` 5 处是渐变色板数据、`agent_settings_widgets.dart` 3 处、`files_widgets.dart` 1 处），留给阶段 C1 统一搬进调色板扩展。

### 阶段 C：暗色模式（工作量最大，分三步）

- **C1 令牌抽象（纯重构，零视觉变化）**：`OpenCrayColors` 单值 `const` 改为 `ThemeExtension<OpenCrayPalette>`，提供 light 实例并保持数值完全一致；feature 局部别名（`_shellBackground`、`FilesFeatureScreen.surface` 等）改为从 `context` 取。验收标准是测试**全绿且与基线一致**、抓图与改动前逐像素同构。
- **C2 dark 配色**：出一套 dark 值（ink 反转、状态色提亮、渐变降饱和、阴影改为边框强化），逐屏抓图核对对比度。
- **C3 接线**：`MaterialApp` 挂 `darkTheme` + `ThemeMode.system`。手动开关需要 host 侧持久化接口，本阶段不做，只跟随系统，接口另行评估。

### 阶段 D：加载态与刷新（夹带在 A/B 之后）

骨架屏替换设置详情/会话列表/文件树的转圈；Skills、Files、设置详情接 `RefreshIndicator`（复用已有 reload 通路，不新增业务逻辑）。

### 阶段 E：可选打磨

平板/横屏最大宽度约束与两栏、hero/共享元素、列表 stagger、sheet 拖拽把手、自定义 Dialog 主题化。

## 四、边界（明确不做）

- 不引入新的第三方 UI 库
- 不改 host 侧协议、数据与文案；设置首页的分组只是展示层分组，组内保留 facade 给的相对顺序
- 不改业务逻辑、`ValueKey`、文案与既有动效时长语义（阶段内明确列出的除外）
- 图标迁移到 Lucide 曾在 `deprecated/ui-refine` 分支尝试并被废弃（`363d309`），本计划不重启；缺口 4 的方向是收敛到 Material rounded

## 五、进度

| 阶段 | 状态 |
|---|---|
| A 页头统一与死代码清理 | 已完成（2026-08-27） |
| B Chat 视觉与动效 | 已完成（2026-08-28） |
| C 暗色模式 | 下一步 |
| D 加载态与刷新 | 未开始 |
| E 可选打磨 | 未开始 |

