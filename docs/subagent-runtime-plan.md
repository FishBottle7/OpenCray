# Subagent Runtime Plan

## 目标

先做一个收敛的一期 subagent runtime，让 OpenCray 在运行逻辑上更接近：

- Claude Code 的 `Task` 风格委派入口
- OpenClaw 的 reduced-context child runtime + lifecycle 设计

一期目标不是一次性做完整的 OpenClaw 子会话体系，而是先把最关键的能力做实：

- 主 agent 能显式委派一个子任务
- 子 agent 有独立、收敛过的上下文
- 子 agent 的结果能结构化返回给父 agent
- 取消能向下传播
- 生命周期和结果对 runtime / replay / host 可见

## 调研结论

### 1. OpenCray 现状

当前 OpenCray 已经具备做 subagent 的几个前提：

- `OpenCrayAgentRuntime` 已经是完整的 prompt loop，工具调用和事件流都在 runtime 内部
- `ContextManager` / `PromptAssembler` 已经能接收结构化 session context
- `AppAgentSessionTaskRuntimeFactory` 已经负责把 soul / memory / bootstrap / transcript 装配成 runtime context
- `AgentSessionRuntimeManager` 已经有 durable run record 和 queue snapshot
- `OpenCrayHostRuntime` 已经能投影 runtime event、pending approval 和 durable replay

但也有一个决定性约束：

- 当前 session queue 是串行的
- 如果父 task 在同一个 queue 里提交 child task，然后自己阻塞等待 child，child 永远跑不到

所以一期不能走“同 session queue 里再排一个 child task 再等待它”的方案。

### 2. OpenClaw 可借鉴的点

从 `D:\\codes\\Opensource\\openclaw\\src\\agents\\subagent-spawn.ts`、`subagent-registry.ts`、`system-prompt.ts` 里，最值得直接借鉴的是：

- child runtime 是显式 spawn 的，不是随手复制父 prompt
- child 有 purpose-built context，而不是拿父 transcript 全量硬塞
- child lifecycle 是独立跟踪的
- child completion 以结构化方式回到父级
- prompt mode 会降级到更轻的 `minimal`
- depth / workspace inheritance / cleanup 都是显式模型，不靠隐含约定

### 3. Claude Code 可借鉴的点

Claude Code 官方 SDK 文档已经暴露了 `Task` 这个委派入口，参数核心是：

- `description`
- `prompt`
- `subagent_type`

所以 OpenCray 这边一期最好直接采用 `Task` 作为规范入口名，而不是先发明一个 `SubAgent`。

## 一期建议方案

### 结论

一期采用：

- 对模型暴露 Claude 风格的 `Task` 工具
- 在 runtime 内部把 `Task` 视为“执行生命周期边界”而不是普通文件工具
- 由 runtime 直接创建一个同步 child runtime 执行
- child 完成后把结果包装成父级这一次 `Task` 工具调用的 tool result

这是一期最稳的形态，因为它：

- 不和当前串行 queue 打架
- 不需要先引入完整的 child session UI
- 能直接复用现有的 prompt loop、tool result、runtime event、durable replay
- 后续还能平滑升级成 OpenClaw 那种更完整的 registry / background child session

## 明确不选的方案

### 方案 A: 父 task 往当前 session queue 再 submit 一个 child task

一期不选。

原因：

- 当前 queue 是串行的
- 父 task 不结束，child task 就不会开始
- 要做成可用版本，必须先把 queue / scheduler 改成可重入或双层调度，风险太大

### 方案 B: 一上来就做完整的 child session / background session / approval resume

一期不选。

原因：

- durable registry、approval suspend/resume、UI surface、restore/reconcile 都会一起爆炸
- 当前最缺的是“能可靠委派一个 bounded 子任务”，不是“先把整套多会话子系统做满”

## 一期范围

### 要做

- `Task` 规范工具入口
- child runtime 的显式上下文构建
- 两个 child context mode：
  - `minimal`
  - `delegated`
- 子运行的 lifecycle event
- 父子 lineage metadata
- 结构化 child result 回流
- 取消向下传播
- 一层深度限制
- durable replay 可见的 child 结果摘要

### 当前已收口

- child 结果不再只返回一段压缩摘要，还会生成显式 `SubAgentExecutionSnapshot`
- snapshot 目前稳定包含：
  - `state`
  - `continuationKind`
  - `resumable`
  - `requiresUserAction`
  - `isHighRisk`
- `Task` tool result metadata、subagent runtime event、durable run record 都会携带这一层状态
- `subagent` lifecycle 现在也会写进 durable transcript replay
- transcript pruning / repair 会把 `subagent` lifecycle 当作受控 delegation 交互，而不是 generic 噪音
- waiting child approval 在通过后，host 也会补一个显式 `subagent resumed` event，避免 timeline 只看到 approval result 却看不到 child lifecycle 已续上
- 如果 waiting child approval 被用户拒绝，或 waiting child 所在 run 被用户取消，host 会补一个 terminal `subagent` event，避免 timeline 永远停在 `waiting_approval`
- 当前仍然只是“预抽象”：
  - child approval suspend / resume 还没有独立 UI / 调度
  - background child execution 还没有真正落地
  - 但 replay / host 不再需要靠错误码猜 child 是否可继续

### 先不做

- child 独立聊天 session
- child approval suspend / resume
- child 写文件、跑命令、跑 Python
- 并行 child graph
- child 内再次 `Task`
- `mirrored` 模式真正启用

## 工具表面

### Canonical 名称

`Task`

### 别名

后续可以补：

- `task`

但一期内部实现以 `Task` 为准。

### 一期参数

一期建议尽量贴近 Claude Code：

- `description: string`
- `prompt: string`
- `subagent_type: string`

其中：

- `description` 是父 agent 给自己的简短任务标签
- `prompt` 是真正发给 child 的任务正文
- `subagent_type` 先做小型内建目录，不开放自由扩展行为

### 一期支持的 `subagent_type`

- `general-purpose`
- `researcher`
- `reviewer`

一期这三个类型都只给 read-only 能力，但会映射到不同的默认上下文模式：

- `researcher` -> `minimal`
- `general-purpose` -> `delegated`
- `reviewer` -> `delegated`

## Child Context 设计

### 总原则

child 不重新从 host UI 取上下文，也不直接继承父 transcript 全量内容。

child context 必须来自一个显式的 `SubAgentContextBuilder`。

### 1. `minimal`

用途：

- 独立调查
- 文件探索
- 读仓库后回报

包含：

- 基础 system prompt
- 当前 session policy
- 轻量 bootstrap
- 父级已经解析好的 effective soul contract
- child 自己的任务正文
- 当前 active skill capsule（如果父级已经激活了一个 skill）

不包含：

- 父级完整 transcript
- 自动 memory recall
- durable compaction 摘要
- 父级最近的整段工具历史

这里的 soul 只继承“已经解出来的有效 soul 轮廓”，不在 child 里再跑一遍新的 memory recall / soul overlay。

### 2. `delegated`

用途：

- 父级已经做了一点调查，需要 child 接着做
- review / audit / compare 这类任务

包含：

- `minimal` 的全部内容
- 一个父级摘要块
- 少量父级最近观察
- 父级当前这次委派为什么发起

父级摘要块只保留：

- 当前用户目标
- 父级已确认的关键事实
- 父级要求 child 聚焦的问题

不直接塞整段 transcript。

### 3. `mirrored`

一期先保留模型，不对 prompt 暴露，也不实现真实继承。

原因：

- 现在就启用 mirrored，等于把 parent 的 budget 问题和 child 的 budget 问题叠在一起
- 这会明显放大重复读取、长上下文回放和 replay 修复的复杂度

## Soul / Memory / Bootstrap 怎么接

这是一期必须明确写死的规则。

### Soul

一期 child 继承的是“父 run 已经得到的 effective soul profile”，不是重新从持久层再算一遍。

这样做的好处：

- child 风格和父级保持一致
- 不会因为 child 再跑一次 recall，导致 tone / relationship guidance 发生漂移
- `ContextManager` 继续只做 budget / injection，不变成 soul 选择器

### Memory

一期 child 默认不做自动 memory recall。

原因：

- 一期 child 是 read-only delegation
- 当前最有价值的是让 child 看仓库、总结结果，而不是把 memory 复杂度也复制一份
- 如果确实需要历史事实，父级应该先在自己的上下文里拿到，再通过 delegated summary 传下去

后续二期再考虑：

- mirrored child 的自动 memory recall
- child 内显式 memory tools

### Bootstrap

一期 child 使用轻量 bootstrap。

建议映射：

- `minimal` -> `BootstrapMode.LIGHTWEIGHT`
- `delegated` -> `BootstrapMode.LIGHTWEIGHT`

这样 child 仍然知道仓库规则，但不会把整包 bootstrap 文件重复塞满 prompt。

## Child Tool Surface

一期 child 只给 read-only 工具。

建议 allowlist：

- `LS`
- `Read`
- `Grep`
- `Glob`

可选继承：

- `skill_read`
- `skills_list`

但这块可以在接入 active skill capsule 时再看是否真的需要。

一期明确不允许：

- `Write`
- `Edit`
- `MultiEdit`
- `Bash`
- `ProcessStart`
- `python_exec`
- `WebSearch`
- `WebFetch`
- 再次 `Task`

这样可以直接规避：

- approval suspend / resume
- child 写操作越权
- child 递归爆炸
- child 进程托管恢复

## Runtime 流程

建议流程如下：

1. 父 agent 在 prompt loop 里输出 `tool_call(Task)`
2. runtime 识别到 `Task`，不走普通 dispatcher 分支
3. `SubAgentContextBuilder` 根据 `subagent_type` 构建 child session context
4. `SubAgentRuntime` 用收敛后的 config 创建一个新的 `OpenCrayAgentRuntime`
5. child 在同步调用里跑完自己的 loop
6. child 的最终结果被压缩成一个结构化 summary
7. 父级得到这次 `Task` 的 `AgentToolResult`
8. 父级继续自己的后续推理，决定是否再调用工具或直接回答

## 事件与可观察性

一期建议补一类新的 runtime 事件，而不是只靠 `Task` 的普通 tool result。

建议新增：

- `OpenCraySubAgentEvent`

建议 phase：

- `STARTED`
- `COMPLETED`
- `FAILED`
- `CANCELLED`

建议字段：

- `childRunId`
- `parentRunId`
- `parentTaskId`
- `label`
- `subagentType`
- `contextMode`
- `depth`
- `summary`

这样 host 后面就能：

- 在 run trace 里显示 child lifecycle
- 在 durable run record 里恢复 child 完成态
- 后续继续扩展 child trace，而不是重新换模型

## Replay / Durable 规则

一期不把 child 的完整内部 transcript 混进父 transcript。

一期只做两层持久化：

1. 父 run 的普通 `tool_result(Task)` 摘要
2. 父 run 的 `subagent` lifecycle event

这样 replay 的语义是：

- 父级知道“我曾经委派过一个 child，child 返回了什么”
- 但不会把 child 的所有内部读文件过程无限堆进父 transcript

这和我们之前已经做的 pruning / repair 方向一致：

- replay 要保留决策需要的结果
- 不要把中间噪音无限累加

## Policy Pipeline

`Task` 虽然不是文件/命令工具，但它是明确的执行生命周期边界。

所以它不能只靠工具名下游猜。

建议在 `runtime/policy/ToolIntentModels.kt` 新增一层 intent：

- `DelegationIntent`

建议 metadata：

- `intentCategory=delegation`
- `delegationIntentKind=child_runtime`
- `delegationLabel`
- `delegationContextMode`
- `delegationDepth`
- `delegationSubagentType`

这样以后：

- host 审批卡
- run trace
- replay repair
- 调试面板

都不需要再从工具名 `Task` 反推行为。

## 一期深度与取消规则

### 深度

一期先做硬限制：

- root agent depth = 0
- child depth = 1
- child 内不暴露 `Task`

这等价于“最大深度 1”，实现最稳。

### 取消

父 run 被用户取消时：

- 共享同一个 cancellation hook 给 child runtime
- child 立刻结束
- 父级这次 `Task` 工具结果返回 `CANCELLED`
- 父 run 自然退出

## 文件落点建议

### 新增

- `runtime/src/main/kotlin/com/opencray/runtime/subagent/SubAgentContextMode.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/subagent/SubAgentProfile.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/subagent/SubAgentTask.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/subagent/SubAgentContextBuilder.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/subagent/SubAgentRuntime.kt`

### 修改

- `runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRunEvents.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/OpenCrayAgentRuntime.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/policy/ToolIntentModels.kt`
- `app/src/main/kotlin/com/opencray/app/AppAgentSessionTaskRuntimeFactory.kt`
- `app/src/main/kotlin/com/opencray/app/AgentRunRecordStoreFactory.kt`
- `app/src/main/kotlin/com/opencray/app/OpenCrayHostRuntime.kt`

## 具体实施顺序

### Step 1

先补 runtime 侧纯模型：

- `SubAgentContextMode`
- `SubAgentProfile`
- `SubAgentTask`
- `DelegationIntent`

### Step 2

补 `SubAgentContextBuilder`。

先只支持：

- `minimal`
- `delegated`

并把 soul / memory / bootstrap 继承规则固定下来。

### Step 3

补 `SubAgentRuntime`。

它负责：

- 创建 child runtime config
- 收窄 child tool surface
- 共享 cancellation hook
- 产出结构化 child summary

### Step 4

在 `OpenCrayAgentRuntime` 里接 `Task`。

要求：

- 对模型仍然表现为普通 tool call / tool result
- 对 runtime / host 多发一层 `subagent` lifecycle event

### Step 5

把 child result 和 lifecycle 接到 durable run record / host 投影。

一期先保证：

- run trace 可见
- replay 可继续

不急着做单独 child session UI。

### Step 6

补测试。

## 一期测试清单

### Runtime

- `Task` 能创建 child runtime 并返回结果
- `researcher` 走 `minimal`
- `general-purpose` / `reviewer` 走 `delegated`
- child 只能看到 read-only 工具
- child 不能再调用 `Task`
- 父取消时 child 跟着取消

### Context

- `minimal` 不带父 transcript
- `delegated` 只带父摘要和少量观察
- child 继承 effective soul，但不重新做 automatic memory recall
- child bootstrap 降到 lightweight

### Host / Replay

- `OpenCraySubAgentEvent` 能投影出来
- durable run record 能恢复最后一个 child lifecycle
- replay 时父级能看到 child 已经完成过的摘要

## 二期再做什么

一期稳定后，再往前推：

1. child 独立 registry
2. child approval suspend / resume
3. child write-capable worker profile
4. child 内再次 `Task`
5. `mirrored` context mode
6. child 独立 trace / UI surface

## 最终建议

一期不要贪心。

最正确的切法不是“先把完整 subagent 平台做满”，而是：

- 先把 `Task` 委派入口做成真的
- 先把 child context 收紧
- 先把结果回流和取消传播做实

这样做完之后，OpenCray 就已经跨过了“只有单 loop 单 agent”的门槛，而且后面的 mid-loop、worker 型 child、child approval、child trace 都有稳定基座可接。
