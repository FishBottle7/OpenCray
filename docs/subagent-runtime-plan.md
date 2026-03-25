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

## 当前基线更新

在这份文档最初写完之后，一期能力又往前推了半步，当前已经不再是“只有 read-only child”：

- 内建 profile 现在已有：
  - `researcher`
  - `general-purpose`
  - `reviewer`
  - `worker`
- 其中 `worker` 已支持 bounded workspace edits：
  - `LS`
  - `Read`
  - `Grep`
  - `Glob`
  - `Write`
  - `Edit`
  - `MultiEdit`
- `worker` 当前仍明确不允许：
  - `Bash`
  - `python_exec`
  - `ProcessStart` / `ProcessRead` / `ProcessWait` / `ProcessTerminate`
  - nested `Task`
- host / runtime snapshot 现在也已经有轻量 child registry 投影：
  - session runtime snapshot 会暴露 `subAgents`
  - 每个 child 会携带 latest phase / execution state / continuation kind / resumable / requires user action / summary

所以当前正确的判断是：

- OpenCray 已经不是“只有 explorer 型 child”
- 但 OpenCray 还不是 Codex 那种显式 `spawn / wait / send_input / close` 控制面
- 当前更像一个同步的 `delegate_and_wait`

## 二期目标：补成更接近 Codex 的 subagent control plane

这一步的目标，不是继续把更多隐式行为塞进 `Task`，而是把 child runtime 从“同步工具调用”提升成“显式 runtime actor”。

二期做完之后，希望 OpenCray 的抽象更接近：

- `spawn_agent(...)`
- `send_input(...)`
- `wait_agent(...)`
- `close_agent(...)`

同时保留当前的 `Task` 作为兼容层和简单糖衣。

### 二期总原则

1. 不推翻当前 `Task`

`Task` 已经是稳定可用的 Claude 风格入口，不应该废掉。

二期里它应该变成：

- 一个 convenience wrapper
- 语义等价于 `spawn_agent + wait_agent`

也就是说：

- 简单委派仍然可以只用 `Task`
- 复杂编排再显式使用 control plane

2. 不一上来做 worktree / cloud / detached swarm

OpenCray 当前产品目标不是 Codex 的完整执行环境矩阵。

二期先做：

- 本地 runtime 内的 child control plane
- durable child registry
- 显式等待 / 路由 / 关闭

先不做：

- worktree isolation
- cloud execution
- 多环境切换

3. 先把控制语义做实，再谈更激进的并发

如果没有显式 child handle / lifecycle / mailbox / wait 机制，就算表面上能并发，也会在 replay、approval、恢复、UI 投影上持续失真。

所以二期优先级应该是：

- 先显式建模
- 再允许更多 child 行为

## 二期工具表面建议

### 1. `spawn_agent`

用途：

- 启动一个 child runtime
- 立刻返回 child handle
- 父 agent 不必同步等待结果

建议参数：

- `task: string`
- `subagent_type: string`
- `context_mode?: string`

可选后续参数：

- `approval_mode?: string`
- `label?: string`

建议返回：

- `agent_id`
- `status`
- `subagent_type`
- `context_mode`
- `depth`

### 2. `send_input`

用途：

- 给一个已存在 child 追加输入
- 用于 follow-up、修正、补充约束

建议参数：

- `agent_id: string`
- `message: string`

建议返回：

- `agent_id`
- `status`
- `queued: boolean`

第一版建议只允许：

- child 在 waiting / idle / approval_wait 边界时收新输入

先不支持：

- mid-loop 任意位置强插

### 3. `wait_agent`

用途：

- 显式等待一个或多个 child 的状态推进
- 支持等到 terminal，或等到 approval / user action / timeout

建议参数：

- `agent_ids: string[]`
- `timeout_ms?: number`

建议返回：

- 每个 child 的：
  - `agent_id`
  - `status`
  - `execution_state`
  - `summary`
  - `requires_user_action`
  - `resumable`

第一版的关键不是“花哨的多路选择”，而是：

- wait 结果必须 durable
- timeout 必须可恢复
- approval wait 必须可见

### 4. `close_agent`

用途：

- 显式关闭一个 child thread / child handle
- 标记后续不再向它路由输入

建议参数：

- `agent_id: string`

建议返回：

- `agent_id`
- `closed: boolean`
- `final_status`

这里的重点不是“杀进程”，而是：

- 关闭 child control-plane handle
- 让 replay / host / UI 知道这个 child 生命周期已经收束

### 5. 可选补一个 `list_agents`

如果后面模型真的需要枚举 child，或者 UI 想更干净地调试，可以补：

- `list_agents()`

但这不是第一优先级。

第一优先级仍然是：

- `spawn_agent`
- `send_input`
- `wait_agent`
- `close_agent`

## profile 命名建议

如果希望更接近 Codex，不应该继续只停留在：

- `researcher`
- `general-purpose`
- `reviewer`
- `worker`

二期建议把对模型暴露的命名收敛成更贴近 Codex 的表面：

- `default`
- `worker`
- `explorer`

兼容策略：

- `default` -> 映射到当前 `general-purpose`
- `explorer` -> 映射到当前 `researcher`
- `worker` -> 保持 `worker`
- `reviewer` 可以先保留为兼容 alias，但不一定继续作为主推荐名称

这样做的原因不是“盲目模仿名字”，而是：

- 模型更容易迁移既有工具习惯
- prompt / system instruction 可以更贴近公开范式
- 后续 control plane 不会出现“功能像 Codex，但 profile 名完全另一套”的割裂感

## 二期 runtime 模型建议

### 1. 显式 child handle

需要新增一层独立模型，例如：

- `SubAgentHandle`

建议字段：

- `agentId`
- `parentRunId`
- `parentTaskId`
- `childRunId`
- `childTaskId`
- `subagentType`
- `contextMode`
- `depth`
- `lifecycleState`
- `executionState`
- `continuationKind`
- `resumable`
- `requiresUserAction`
- `isHighRisk`
- `summary`
- `createdAtEpochMs`
- `updatedAtEpochMs`

### 2. child mailbox

需要把 child 的 follow-up 输入显式存起来，而不是靠拼 prompt。

建议新增：

- `SubAgentMailbox`

最小能力：

- pending messages queue
- last delivered message id
- approval wait 时可挂起

### 3. child registry

当前 host snapshot 的 `subAgents` 只是投影层。

二期要把它补成真正的 runtime registry：

- runtime 持有 active child handles
- host 可恢复 child latest state
- replay 可恢复 child 生命周期边界

### 4. `Task` 改成 sugar

`Task` 在二期里不应继续直接 new child runtime 后同步执行到底。

更合理的方式是：

1. `Task` 内部调用 `spawn_agent`
2. 然后调用 `wait_agent`
3. 把 terminal / waiting summary 再包装成 tool result

这样可以避免维护两套 child 语义。

## approval / cancel / replay 规则

### approval

二期里 child approval 不能只表现成父级 `Task` 的 denied result。

还需要：

- child handle 自己进入 `waiting_approval`
- `wait_agent` 返回 `requires_user_action=true`
- host / replay / UI 都能知道是哪个 child 卡住了

### cancel

要区分两种 cancel：

1. 关闭 child handle
2. 用户主动取消整个 parent run

规则建议：

- parent cancel -> 级联 cancel child
- close_agent -> 只关闭指定 child
- 被关闭或被取消的 child 都要写 durable replay

### replay

child 不能只留下最后一句摘要。

至少要 durable：

- child spawned
- child resumed
- child waiting approval
- child completed / failed / cancelled
- child closed

但也不能把 child 全 transcript 无限制塞回 parent。

所以 replay 仍然坚持：

- lifecycle + bounded summary
- 不混入 child 全量内部过程

## UI / host 最小落点

二期不要求先做完整 child session UI，但至少要支持：

1. runtime snapshot 可列出当前 children
2. inspector 能按 child handle 看历史
3. approval 卡能明确知道挂起的是哪个 child
4. parent run 卡能看到：
   - spawn 了哪个 child
   - child 现在在运行 / 等审批 / 已完成

换句话说：

- 先做 control plane
- 再让 UI 消费这个 control plane
- 不要继续让 UI 只从零散 event 猜 child 状态

## 具体实施顺序

### P2-1 命名标准化

- 对模型暴露 `default / explorer / worker`
- 保留 `general-purpose / researcher / reviewer` 作为兼容 alias
- 更新工具描述和系统提示，不再把旧名字当主推荐面

### P2-2 child registry 落地

- runtime 内引入显式 child handle registry
- host snapshot 改为优先读 registry，而不是只读 event 聚合
- durable store 能恢复 latest child state

### P2-3 `spawn_agent`

- child 可被显式启动
- 先只支持同 parent run 下的 local child
- 第一版仍限制深度和工具面

### P2-4 `wait_agent`

- wait terminal
- wait approval
- wait timeout
- wait 返回结构化 child states

### P2-5 `Task` 改 sugar

- 复用 `spawn_agent + wait_agent`
- 不再保留第二套同步 child execution 语义

### P2-6 `send_input`

- 先支持 boundary-based supplement
- 不做 mid-loop arbitrary interrupt

### P2-7 `close_agent`

- 收束 child handle
- 让 replay / host / UI 都能看到 closed terminal edge

### P2-8 tests

至少补：

- spawn -> wait -> complete
- spawn -> approval wait -> approve -> wait
- spawn -> approval wait -> reject -> wait
- spawn -> send_input while idle / waiting
- spawn -> close
- parent cancel cascades to child
- replay restores child registry and wait-visible states

## 暂时不做的内容

- child worktree isolation
- child cloud execution
- child arbitrary mid-loop interrupt injection
- 自动子代理图调度
- 大规模并发 child swarm

## 最终建议

如果目标是“更接近 Codex 的 subagent 行为”，下一步最重要的不是继续增强 `Task` 的单次能力，而是：

- 把 child 变成显式 handle
- 把控制动作显式化
- 把 `Task` 下沉成 sugar

只有这一步做完，后面的：

- 更复杂的 worker 任务
- child follow-up
- child background execution
- child close / cleanup
- 更接近 Codex 的 UI 和 runtime 行为

才会有稳定基座。

## 最终建议

一期不要贪心。

最正确的切法不是“先把完整 subagent 平台做满”，而是：

- 先把 `Task` 委派入口做成真的
- 先把 child context 收紧
- 先把结果回流和取消传播做实

这样做完之后，OpenCray 就已经跨过了“只有单 loop 单 agent”的门槛，而且后面的 mid-loop、worker 型 child、child approval、child trace 都有稳定基座可接。
