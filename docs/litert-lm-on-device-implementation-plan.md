# LiteRT-LM 端侧技能模式实施计划

Date: 2026-04-06

## 1. 结论

本计划替换此前“生产路径继续使用宿主侧 tool loop、LiteRT automatic execution 仅限 debug”的路线。

新的正式方向是：

- 在 OpenCray 中新增一个端侧专属的轻量执行档位，暂定名为 `On-device Skills Mode`
- 该模式下，LiteRT-LM SDK automatic tool execution 作为生产默认路径
- automatic execution 不直接绕过宿主控制面，工具实际执行仍复用 OpenCray 的 `OpenCrayToolDispatcher` 与 `ToolPolicyPipeline`
- 风险工具不允许“直接调用就弹系统审批然后继续执行”，而是改成“先申请审批，再调用工具”
- 未先申请审批或审批不匹配时，风险工具返回权限不足 / 需要先提权的结构化警告
- 其余运行方式跟随此前推荐的方案 A：
  - 小工具集
  - 轻量 skill capsule
  - 更短 prompt
  - 限制 turn 数与上下文预算
  - 不把桌面全量 skill 原样塞进端侧 prompt
- `WebSearch` 纳入端侧技能模式首发工具集

## 2. 为什么改路线

此前路线的优点是稳，但不适合“端侧技能模式”这个产品目标。

现状问题：

- 端侧 `lite mode` 会直接把工具集清空，模型根本看不到工具
- 端侧 `lite mode` 还把工具协议压到极简档，更适合纯对话，不适合稳定的 tool calling
- 现有 skill 体系默认面向更通用、更重的 agent runtime，不适合直接塞给端侧模型
- 如果仍坚持“生产路径只用宿主侧多轮 tool loop”，体验会更像通用 agent，而不是更接近 Edge Gallery 的轻量技能执行

要做出端侧技能模式，需要接受一个新的边界：

- 在这个特定模式下，以 LiteRT automatic execution 为主执行路径
- 但 approval、policy、audit、resume 仍由 OpenCray 宿主层掌控

这不是完全让 SDK 接管，也不是完全沿用宿主多轮 loop，而是中间路线：

- 让 LiteRT 负责单轮内的工具编排
- 让 OpenCray 负责工具执行的真实边界和审批恢复

## 3. 产品形态

### 3.1 模式拆分

端侧能力分成两个产品档位：

1. `On-device Chat`
   - 面向普通本地对话
   - 保留当前端侧聊天路径
   - 不默认打开生产 automatic execution

2. `On-device Skills Mode`
   - 面向端侧技能与本地工具调用
   - 以 LiteRT automatic execution 为默认执行模型
   - 使用轻量 skill capsule 和小工具集

这意味着当前的 `onDeviceLiteModeEnabled` 不再只是“更省电的聊天模式”，而会逐步演进成“端侧技能模式”的兼容存储开关。

### 3.2 端侧技能模式首发能力

首发范围限定为：

- LiteRT-LM
- `gemma-4-e2b-it`
- `gemma-4-e4b-it`
- Android 端本地推理
- native tool calling
- thinking
- 轻量 skills
- `WebSearch`

首发不做：

- 桌面级全量 skill 原样注入
- subagent
- 复杂多媒体工具链
- provider-managed builtin web search
- 端侧原生图片理解 / 音频理解 / PDF 解析

## 4. 运行路线

### 4.1 标准端侧聊天

`On-device Chat` 保持现状：

- 更偏向普通聊天
- 工具能力继续保守
- 不把 LiteRT automatic execution 作为生产默认

### 4.2 端侧技能模式

`On-device Skills Mode` 的生产默认路线：

1. OpenCray runtime 构造 LiteRT request
2. 端侧请求里携带精简后的可见工具集与轻量 skill capsule
3. bridge 把 `ToolExecutor` 传给 LiteRT-LM SDK
4. SDK 在单轮推理内自动调用工具
5. `ToolExecutor` 实际回调到 OpenCray `OpenCrayToolDispatcher`
6. 工具结果返回给 SDK，继续同轮推理
7. 若命中风险审批，则转成“先审批、后执行”的宿主控制路径
8. 最终仍由宿主记录 transcript、pending approval、audit metadata、resume state

### 4.3 为什么在这个模式里接受 production automatic execution

因为这个模式的目标不是最大化通用 agent 透明性，而是：

- 减少多轮编排开销
- 降低端侧小模型在工具协议上的 token 负担
- 让本地技能体验更接近“单轮内完成动作”

但这只对 `On-device Skills Mode` 成立，不意味着所有端侧模式都改成这个路线。

### 4.4 冷启动、预热与缓存策略

端侧首发需要额外处理 LiteRT-LM 的冷启动成本，否则进入会话后的首条消息会明显变慢。

首发实现采用以下策略：

1. `engine prewarm`
   - 进入应用或进入聊天后，如果当前已经选中并配置好可用的端侧模型，宿主会异步触发一次无输出预热
   - 预热只负责把模型 engine 和 conversation 初始化起来，不让模型产生用户可见输出

2. UI 输入门控
   - 预热进行中，聊天输入框和发送按钮置灰
   - 聊天摘要与输入框占位文案显示“正在准备端侧模型”
   - 预热完成后恢复输入

3. 安全静态缓存
   - LiteRT bridge 缓存工具定义对应的 `ToolProvider`
   - 这类缓存只覆盖工具 schema / provider 构建，不缓存带有会话状态的 conversation 本身

4. 当前明确不做的事
   - 不在首发里做跨轮 conversation 复用
   - 不直接做跨轮 KV cache / session cache

原因是当前 OpenCray 的 prompt 组装仍会把 transcript、task prompt、memory layer、skill layer 重新拼装后送进 LLM。  
如果直接把 LiteRT conversation 跨轮复用，很容易把隐藏 prompt 和显式 transcript 混在一起，导致状态漂移或重复注入。

因此当前路线是：

- 先做安全的 engine 预热
- 先做工具定义层面的静态缓存
- 等 prompt pipeline 能稳定生成“可增量续写”的单一路径后，再考虑真正的跨轮上下文缓存

## 5. 工具分层

### 5.1 首发常驻工具

端侧技能模式首发常驻工具集：

- `LS`
- `Read`
- `Glob`
- `Grep`
- `TodoWrite`
- `skills_list`
- `skill_read`
- `WebSearch`

这些工具的共同点：

- 输入 schema 相对稳定
- 单次执行成本可控
- tool result 较容易压缩
- 更适合小模型在单轮自动执行中使用

### 5.2 首发不纳入常驻工具

以下工具不进入首发常驻端侧工具集：

- `Bash`
- `python_exec`
- `ProcessStart`
- `ProcessTerminate`
- 文件写删改移动全套高风险工具
- subagent 相关工具

### 5.3 风险工具分层

虽然首发不把高风险工具全部放进默认工具集，但审批机制需要一步到位设计好。

工具分层如下：

1. 直接执行工具
   - 低风险只读或低副作用工具
   - 命中 policy allow 时由 automatic execution 直接执行

2. 审批后执行工具
   - 写操作
   - 进程 / 命令
   - 网络写操作
   - 其它命中 `ASK` / high-risk 的工具

3. 永不进入端侧技能模式的工具
   - 与当前产品目标明显不匹配
   - 单次执行成本过高
   - 难以在小模型提示预算内稳定使用

## 6. 风险工具审批模型

### 6.1 核心要求

用户要求的产品行为是：

- 模型不能直接调用风险工具然后等待系统兜底
- 模型必须先提交“准备调用什么工具”
- 用户审批通过后，模型才能真正调用该工具
- 如果模型跳过审批直接调用风险工具，工具返回权限不足警告

这要求把现有 approval 流程从“工具执行阶段触发”前移到“工具意图申请阶段”。

### 6.2 新增宿主侧审批工具

新增一个宿主可见的端侧专用审批工具，暂定名：

- `RequestToolApproval`

输入字段建议为：

- `tool_name`
- `reason`
- `arguments_preview`
- `target_summary`
- `working_directory`
- `risk_hint`

用途：

- 不是执行真实工具
- 只是把“我要调用某个风险工具”的意图提交给宿主审批系统

### 6.3 风险工具直接调用时的返回

当端侧模型直接调用风险工具，且当前没有匹配的审批授权时：

- `ToolExecutor` 不执行真实工具
- 返回结构化权限不足结果

返回语义要求：

- 状态为失败或权限不足
- 明确告知“必须先调用 `RequestToolApproval`”
- 返回可用于重试匹配的审批指纹信息

建议统一错误码：

- `APPROVAL_INTENT_REQUIRED`
- `HIGH_RISK_APPROVAL_INTENT_REQUIRED`

这类错误码不替代现有 `APPROVAL_REQUIRED` / `HIGH_RISK_APPROVAL_REQUIRED`

区别是：

- `APPROVAL_REQUIRED` 是宿主工具执行阶段的 policy ask 结果
- `APPROVAL_INTENT_REQUIRED` 是端侧技能模式下对 automatic execution 的更前置约束

### 6.4 审批粒度

审批不按“仅工具名”授权，而按以下指纹绑定：

- `taskId`
- `toolName`
- 规范化参数摘要
- 目标路径 / URL / 命令摘要
- 可选 `workingDirectory`

这样能避免：

- 模型申请了一个低风险路径，实际执行时换成别的目标
- 审批通过后被滥用成“该工具无限制放行”

### 6.5 审批通过后的恢复

审批通过后：

1. 宿主记录 pending approval grant
2. 当前 run 进入等待恢复态
3. 用户批准后由现有 approval resume 路径恢复 run
4. runtime 在恢复时向模型追加一条短观察：
   - 某项工具审批已通过
   - 可重试调用该工具
5. 模型重新发出工具调用
6. automatic executor 检查该调用是否与已批准的审批指纹匹配
7. 匹配则自动附加 approval token / grant 并放行真实工具执行

### 6.6 与现有审批体系的关系

新的“审批意图申请”不会替代现有 `ToolPolicyPipeline` 和 host approval 体系，而是套在它前面。

执行顺序变为：

1. 端侧模型尝试调工具
2. automatic executor 判断该工具是否属于风险工具
3. 若风险工具且无审批意图授权，直接返回 `APPROVAL_INTENT_REQUIRED`
4. 模型调用 `RequestToolApproval`
5. 宿主记录 pending approval
6. 用户审批
7. run 恢复
8. 模型重新调工具
9. 真实工具执行时仍继续走 `ToolPolicyPipeline`

这样即使实现有漏洞，也还有现有 policy ask / deny 路径兜底。

## 7. Skills 设计

### 7.1 不直接复用桌面完整 skill prompt

桌面 `SKILL.md` 一般过长，不适合端侧技能模式。

端侧技能模式引入轻量 capsule：

- `OnDeviceSkillCapsule`

字段建议：

- `name`
- `description`
- `goal`
- `allowedTools`
- `instructions`
- `examples`

其中：

- `instructions` 必须非常短
- `examples` 最多保留 `1-2` 个
- 不保留长篇背景和扩展说明

### 7.2 与现有 Active Skill Capsule 的关系

沿用现有 Active Skill 的选择机制，但端侧投影不同。

桌面 / 通用 runtime：

- 继续使用现有 `Active Skill` 注入方式

端侧技能模式：

- 把已激活 skill 投影成 `OnDeviceSkillCapsule`
- 只保留能帮助模型工具调用的最小信息

### 7.3 技能来源

首发允许两类来源：

1. 宿主内置 / 已安装技能中，可安全端侧投影的技能
2. 用户手动启用的“端侧可用技能”

端侧不可用技能不进入端侧 prompt。

### 7.4 技能与工具集的交集

端侧 skill 的有效工具集为：

- `skill.allowedTools`
  与
- `On-device Skills Mode` 当前工具集
  与
- 当前 runtime policy 可见工具

三者交集。

这保证：

- skill 不会扩大端侧模式的工具面
- 用户关闭的工具不会被 skill 偷偷重新打开

## 8. WebSearch

### 8.1 首发要求

`WebSearch` 必须进入端侧技能模式首发工具集。

### 8.2 接入方式

不走 LiteRT provider-managed builtin web search。

仍然走普通函数工具：

- 工具名：`WebSearch`
- 执行方：OpenCray `WebSearch` 工具

### 8.3 审批行为

`WebSearch` 作为网络读取工具，是否需要审批由现有 policy 决定：

- 如果当前 policy 允许，automatic execution 直接执行
- 如果当前 policy 需要审批，则走新的风险工具审批意图路径

### 8.4 结果压缩

端侧技能模式下，`WebSearch` 返回需要更短：

- 限制结果数
- 限制单条 snippet 长度
- 尽量输出结构化摘要

否则小模型很容易把搜索结果吃满上下文预算。

## 9. Prompt 与协议

### 9.1 主协议

端侧技能模式使用：

- native tool calling first
- plain assistant text final

### 9.2 legacy JSON fallback

端侧技能模式不再把 legacy JSON fallback 作为主提示协议。

计划改动：

- 不注入 JSON action 示例
- 去掉“最后一轮仍可回退 JSON final”那类提醒

运行时是否保留解析兜底，可在迁移期间暂时保留，但不应再把它作为端侧技能模式的模型教学内容。

### 9.3 工具协议详细度

端侧技能模式不使用当前 `minimal` 档位，而使用 `compact`。

原因：

- `minimal` 更适合纯对话
- 端侧要稳定调用工具，需要比 `minimal` 更多的 schema 与行为提示
- 但仍不应回到桌面级 `full` 提示密度

### 9.4 上下文预算

端侧技能模式需要更明确的预算策略：

- 缩短系统提示
- 缩短技能 capsule
- 缩短工具说明
- 减少 transcript 重放
- 避免预先注入重型 memory 内容

## 10. 运行时与宿主改造点

### 10.1 `AppAgentSessionTaskRuntimeFactory`

需要新增模式分支：

- 标准端侧聊天
- 端侧技能模式

端侧技能模式下：

- 不再直接把所有工具清空
- 构造端侧专用工具集
- 绑定 production automatic tool execution context
- 压缩 session context 组装链路

### 10.2 `LiteRtAutomaticToolExecution`

需要从“debug 验证能力”提升为正式生产能力：

- 去掉 debug-only 绑定限制
- 支持风险工具审批意图
- 支持 approval grant 匹配
- 对权限不足场景返回结构化警告

### 10.3 `LiteRtOnDeviceRuntime`

需要支持：

- 端侧技能模式元数据
- production automatic execution enable
- `WebSearch`
- 轻量 skill capsule 透传
- 审批相关结果的结构化映射

### 10.4 `OpenCrayAgentRuntime`

需要新增端侧技能模式分支：

- 更小的 visible tool set
- 更轻的 prompt conversation
- 禁掉端侧技能模式中的 legacy JSON 协议注入
- 恢复后注入“审批已通过，可重试工具”的短观察

### 10.5 host approval / pending approval

现有 host approval 体系已经存在，但需要新增一层：

- 审批意图记录
- 审批 grant 与工具调用指纹匹配
- 恢复后对端侧模型的短提示

### 10.6 skill projection

需要新增：

- `OnDeviceSkillCapsuleProjector`
- skill 端侧可用性过滤
- 端侧 capsule 序列化

## 11. UI 与设置

### 11.1 LLM 设置

新增或重命名以下开关：

- `On-device skills mode`
- `Enable WebSearch in on-device skills mode`
- `Allow risk tools after approval`

如果沿用旧存储键：

- UI 文案改成新的产品名称
- 内部逐步兼容旧键迁移

### 11.2 审批卡片

风险工具审批卡片需要显式展示：

- 工具名
- 原因
- 目标摘要
- 关键参数摘要
- 工作目录
- 风险等级

### 11.3 技能入口

端侧技能模式下要有一个轻量 skill 入口：

- 查看可用于端侧的 skills
- 启用 / 禁用端侧技能
- 查看某个 skill 暴露给端侧模型的 capsule 预览

## 12. 分阶段实施

### Phase 1

- 文档更新
- 新模式元数据
- production automatic execution 默认启用
- 小工具集接通
- `WebSearch` 接通

### Phase 2

- `RequestToolApproval`
- 风险工具权限不足返回
- 审批意图记录
- 审批 grant 恢复与匹配

### Phase 3

- `OnDeviceSkillCapsule`
- skill 过滤与投影
- 端侧技能 UI

### Phase 4

- prompt 进一步瘦身
- 关掉端侧技能模式的 legacy fallback 教学
- 性能、温控、成功率调优

## 13. 验证计划

需要新增验证：

- production automatic execution 在端侧技能模式中默认启用
- 低风险工具可直接自动执行
- 风险工具在未审批时返回 `APPROVAL_INTENT_REQUIRED`
- 模型调用 `RequestToolApproval` 后能正确进入 pending approval
- 用户审批后恢复 run，模型可重试同一风险工具
- 审批 grant 与工具调用指纹不匹配时拒绝执行
- `WebSearch` 在 allow / ask / deny 三种策略下行为正确
- 端侧 skill capsule 不会泄露桌面长 prompt
- 端侧技能模式不再注入 legacy JSON action 主协议

需要新增真机指标：

- 首 token 时间
- 单轮完成率
- 单轮工具成功率
- 平均工具调用次数
- 设备温升
- 10 分钟连续使用掉电

## 14. 风险

主要风险：

- production automatic execution 会让工具执行时序更难观测
- 审批意图设计不好会造成模型反复卡在提权循环
- `WebSearch` 结果过长会放大端侧上下文压力
- skill capsule 太长会抵消端侧轻量模式的意义

对应缓解：

- 所有 automatic execution 仍走宿主 dispatcher 和 policy pipeline
- 审批 grant 绑定到明确的工具调用指纹
- `WebSearch` 结果做更强裁剪
- 端侧 capsule 单独投影，不直接复用桌面长说明

## 15. 首发口径

首发对外口径：

- OpenCray 支持 LiteRT-LM 端侧技能模式
- 首发模型为 `gemma-4-e2b-it` 与 `gemma-4-e4b-it`
- 支持 native tool calling
- 支持轻量 skills
- 支持 `WebSearch`
- 风险工具采用“先提权、后执行”的审批方式
- 该模式以本地执行效率与温控优先，不承诺桌面级通用 agent 全能力等价
