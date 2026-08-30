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
| 1 | 完全没有暗色模式 | `lib/app/opencray_app.dart:45` 仅 `theme: OpenCrayTheme.light()`，无 `darkTheme`/`ThemeMode`；`OpenCrayColors` 全为单值 `const` | 高 → 阶段 C 已收 |
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

#### C1 令牌抽象（已完成，纯重构零视觉变化）

`OpenCrayColors` 等静态令牌不再直接进入 widget：新增 `core/design/opencray_palette.dart`，`OpenCrayPalette extends ThemeExtension<OpenCrayPalette>` 承载 31 个语义色 + 1 个渐变 + 4 组阴影，读法统一为 `context.palette`。

1. **light 实例用静态令牌定义**（`primary: OpenCrayColors.primary` …），所以迁移期每个颜色仍然只有一处字面量，light 主题不可能漂移。`OpenCrayColors` 保留为「light 数值表」，不是死代码。
2. **`context.palette` 在扩展缺失时回落 `OpenCrayPalette.light`**。测试普遍只 pump 裸 `MaterialApp`，没有本仓库的主题；有回落它们才继续画出既有 light 值，这是 459 条测试零改动的前提。
3. **`OpenCrayTheme.light()` 改为 `OpenCrayTheme.of(palette, brightness)` 的薄封装**，整份 `ThemeData`（含 `TextTheme` 与 20 个组件主题）由 palette 生成，并把 palette 挂到 `extensions`。所有走 `Theme.of` 的 Material 组件因此自动跟随明暗。
4. **三类局部别名收口**：`FilesFeatureScreen.surface` 等 10 个 `static const`、skills 的 8 个顶层 `const _surface`、settings 的 `_SettingsTextStyles`（12 条 `static const TextStyle` → 实例 getter + `context.settingsText`）、chat 的 `_ChatPalette` / `_ChatGlass` / `_ChatGradients` / `_ChatTextStyles`（→ 实例类 + `context.chatPalette` 等四个 getter）。
5. **零散字面量归位**：新增 `shadowInk` 令牌（阴影专用墨色，与 `textPrimary` 分开，便于 C2 单独处理阴影），吃掉开关拇指 `0x24101828`、Files 选择工具条 `0x12101828`、agent 主键光晕 `0x332563EB`、agent 头像药丸 `0xE0FFFFFF ×2`。chat glass 里纯白/纯墨底色改为 `_chatAlpha(_base.surface, 0xA8)` 这类写法——alpha 仍是字面量（它是对着模糊调出来的），底色则跟随 palette，`toARGB32()` 逐字节不变。
6. **有意保留字面量**：`chat_seed_data.dart` 的两个 `accentColor`（seed 数据不吃主题，`ChatAttachmentData.accentColor` 非空且参与 `chat_state_equivalence` 比较）、`agent_gradient_data.dart` 的 5 组头像渐变（装饰性色板数据，不是语义色）、`opencray_markdown_images.dart` 的 `0xCC000000`（全屏图片遮罩，两种明暗下都该是近黑）。

两处签名因此变化：`chatBubbleSelectionTheme(kind)` → `(kind, palette)`（它是 `@visibleForTesting` 的纯函数，测试改为显式传 `OpenCrayPalette.light`，断言的颜色值一字不改），`_RunTraceStatusTone.fromTrace` / `_runTracePreviewStatusStyle` / `_runTraceSandboxSessionStatusStyle` / `_buildWorkspaceAccessShared` / `_buildDetailSectionCards` / `_buildMemoryLinkDetails` / `_buildSoulProfileLines` 等 helper 补 `BuildContext`；6 处 `this.color = <token>` 构造默认值改可空 + build 内回落（默认值必须是 const，取不到主题）。

方法上值得记下来的：`OpenCrayColors.X` → `context.palette.X` 是纯文本替换，真正的成本是随之失效的 `const`。用 `flutter analyze` 的 `invalid_constant` 位置反向驱动脚本删掉最近的一个 `const`（`tool/palette_deconst.py`），一轮就从 381 条降到 0，比手改可靠。踩过的坑：临时脚本里写死 `\r\n` 分隔符——本仓库 CRLF/LF 混用，`agent_settings_widgets.dart` 是 LF，导致 `partition` 吃掉整个文件尾部；另一处正则以 `\n` 起头删行，在 CRLF 文件里留下 10 个孤立 `\r`，而孤立 CR 会让 git 放弃 CRLF 归一化、把整份 `files_feature.dart` 显示成全文件改动。脚本一律用 `\r?\n`，并在收尾做一次孤立 CR 审计。

验收：`flutter analyze lib` 零问题；`flutter test` 436 通过 / 23 失败，失败集与基线逐条一致（分五个切片各验一次）；chat / files / skills / 控件画廊四张 golden 与 `854fc98` 工作树（`git worktree` 出一份 HEAD 单独跑）**逐字节相同**。

#### C2 dark 配色（已完成）

`OpenCrayPalette.dark` 落在冷石板色系而不是纯黑——工作台的蓝灰调保留，色相锚点就是仓库里早有的 `inkSurface`（`0xFF16202E`）。三处相对 light 是**反向**的，值得单独记：

1. **状态色提亮**：light 的 `success`/`warning`/`danger` 是照着瓷白底调的（约 4.5:1），放到石板底上会塌掉，所以三色各自提亮度，配套的 tint/border 换成低彩度深色洗（如 `dangerTint: 0xFF33161C`）。
2. **阴影换成描边**：dark 的 `cardShadow` 是**空列表**——深色底上投影本来就看不见——卡片边界交给 `divider`（`0xFF2A3646`）；只有真正悬浮的层（`floatingShadow`）保留一层黑色柔光，品牌光晕 `brandGlow` 保留蓝色。
3. **`inkSurface` 变亮而不是变暗**（`0xFF202B39`）：snackbar / tooltip 必须读作「浮在页面之上」，深色页面上「之上」就是亮度更高。

`primary` 有意贴近 light（`0xFF3D7BF7`，正好是 chat 气泡渐变的头部色）：全仓约 40 处在强调色填充上画白色内容（发送键、出站气泡、审批三键），accent 必须先保住白色可读性。这个值在白色下是 3.9:1，作为强调文字压在 `shellBackground` 上是 4.8:1；再亮的蓝会赢第二项、输掉第一项。同理 `textOnPrimary` 保持白色而没有按 M3 惯例翻成深墨。

chat 中枢 24 处 light-only 字面量按 `_base.isDark` 分支给出 dark 版：terracotta 高风险色族整体提亮（`0xFFC2491D` → `0xFFFF8A5C`，色相不动，语义不变）、runTrace/inspector 描边转深、inspector 紫 `0xFF7C3AED` → `0xFFB292FF`、glass 的三个偏白底色换成石板底。`OpenCrayPalette` 因此新增 `brightness` 字段（附 `isDark`），需要按明暗挑字面量的 feature 从它分支，而不是反推某个颜色。

抓图暴露出 C1 没覆盖的一类问题：**硬编码白色**。全仓 111 处 `Colors.white`，其中约 2/3 是「强调色填充上的内容」（`isOutgoing ? Colors.white : …`），本来就该保持白；剩下 40 处是**当作表面用**的白——inbound 气泡、composer 输入框、顶栏状态药丸、7 个 sheet 面板、inspector 卡片、skills 分段指示块、files 搜索框——这些在 dark 下整块发白。逐处按「内容 vs 表面」分类后改 `context.palette.surface`（含 9 处 `Colors.white.withValues(alpha:)` 的半透明中性 chrome）。light 下 `surface` 就是 `Colors.white`，所以这批改动在 light 下逐字节不变。

#### C3 接线（已完成）

`MaterialApp` 挂 `darkTheme: OpenCrayTheme.dark()` + `themeMode: ThemeMode.system`。手动开关仍然不做——需要 host 侧持久化接口，另行评估。

验收：`flutter analyze` lib 零问题（`test/` 7 条 `unnecessary_import` 属既有项）；`flutter test` 436 通过 / 23 失败，失败集与基线逐条一致；chat / files / skills / 控件画廊四张 light golden 与 `854fc98`（`git worktree` 出一份 HEAD 单独跑，同一 DPR）**逐字节相同**；四张 dark golden 逐屏核对过对比度与层次。

留在字面量里的（有意）：`chat_seed_data.dart` 的两个 `accentColor`、`agent_gradient_data.dart` 的 5 组头像渐变、`opencray_markdown_images.dart` 的 `0xCC000000` 图片遮罩、以及 approval 卡与出站气泡内部那批压在强调色上的白色洗。

#### 真机核对与控件修正（2026-08-30）

装到 PLB110（Android 16，系统本来就是暗色）逐个 tab 走了一遍。抓图暴露三处 `flutter test` 与 light golden 都抓不到的问题，共同点是**颜色语义选错而不是颜色值选错**：

1. **开关拇指变黑**。C1 把 `Colors.white` 机械换成 `palette.surface` 时，`OpenCraySwitch` 的拇指跟着走了；dark 下 `surface` 是 `0xFF161E29`，压在蓝色轨道上读作「轨道上挖了个洞」。拇指属于**强调色填充上的内容**，不是表面。新增 `controlThumb` / `controlThumbDisabled`（light 指向 `surface` / `surfaceSubtle`，逐字节不变；dark 为 `0xFFEDF2FA` / `0xFF4A5769`），并把这一条补进 `OpenCrayPalette.dark` 的文档注释，与另外三处反向项并列。
2. **分段控件选中块失去层级**。C2 让 dark 的 `cardShadow` 变成空列表，而分段指示块正是靠那层投影浮起来的；`surface`（`0xFF161E29`）与 `surfaceSunken`（`0xFF0A1017`）只差 12 级亮度，投影一撤就分不出来。按 C2 既定的「阴影换描边」规则给 dark 补一条 `outline` 发丝线，light 传 `null` 不受影响。
3. **Files 搜索框套了两层框**。`InputDecorator` 解析边框时先取 `enabledBorder` / `focusedBorder`，只有它们为空才回落 `border`，而这两个来自主题——单写 `border: InputBorder.none` 拦不住主题的轮廓；`filled` 同理，主题的 `surface` 填充盖在宿主容器自己的底色上，聚焦时把 Files 搜索条的 `primaryTint` 盖掉一块。新增 `openCrayBareInputDecoration` 常量一次性关掉六个边框槽位与 `filled`，9 个自带外框的输入框（Files / Skills 搜索、composer、settings 与 llm 页内联编辑器）改为 `copyWith` 它；composer 原先手写了六个槽位、是全仓唯一写对的一处，现在也走同一常量。

第 3 条在 light 下同样是缺陷（发丝线颜色浅，只是不明显），修掉之后 light 不再与 `854fc98` 逐像素相同——这是有意的视觉修正，不算回归。

验收：`flutter analyze lib` 零问题；`flutter test` 436 通过 / 23 失败，失败集与基线**逐条一致**（并行会话新增的批量批准测试另算一笔提交，不在本轮）；真机上逐个核对了开关、分段控件、搜索框静止与聚焦两态。真机 light 没核对成——ColorOS 的 `cmd uimode night no` 报告已切换，应用仍然收到 dark。

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
| C1 令牌抽象 | 已完成（2026-08-28） |
| C2 dark 配色 | 已完成（2026-08-28） |
| C3 接线 darkTheme | 已完成（2026-08-28） |
| 真机核对与控件修正 | 已完成（2026-08-30） |
| D 加载态与刷新 | 下一步 |
| E 可选打磨 | 未开始 |

