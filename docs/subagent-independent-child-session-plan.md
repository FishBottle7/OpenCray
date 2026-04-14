# 独立 Child Session 设计草案

Updated: 2026-04-11

## 0. 文档定位

这份文档是 [`subagent-runtime-plan.md`](./subagent-runtime-plan.md) 里“下一阶段目标”的收口设计稿，专门回答一个问题：

- subagent 什么时候应该从“parent session 内的一条 durable child lane”，升级成“真实独立的 child session”

实现时以这份文档作为下一阶段主参考即可，不需要再额外保留旧 hidden owner lane 的过渡设计。

## 1. 背景

当前 subagent 已经具备这些能力：

- `spawn_agent / send_input / wait_agent / close_agent / list_subagents` control plane
- durable child handle
- hidden actor owner lane
- visible recovery/wait observer lane
- cold restart 后的 detached child 恢复

这套实现已经能稳定支撑“parent 代管 child”的模型，但 child 仍然不是一个真正独立的 session owner。

当前本质上还是：

- parent session 持有 child 的 durable state
- parent session 负责 child 的恢复与 owner orchestration
- host/UI 主要投影的是 parent 内部的 child lane，而不是一个真正独立的 child session

下一步如果要继续收口，正确方向不是继续堆 hidden actor 壳，而是把 child 提升成独立 child session。

## 2. 目标

这份设计的目标是：

- 让每个 detached child 拥有真实 `childSessionId`
- 让 child 的 queue / run record / checkpoint / transcript / mailbox 归 child session 自己持有
- 让 parent 只保留 control plane 和 read model 投影职责
- 保持当前产品边界不变：
  - 仍然只有一个 main agent identity
  - child 不是独立 agent 产品实体
  - child 不拥有独立 soul / 长期 memory / workspace ownership

## 3. 非目标

这轮设计明确不做：

- 多 agent 产品化
- 独立 child `SOUL.md`
- 独立 child 长期 memory store
- 独立 child workspace root / worktree
- 旧数据兼容

开发阶段可以直接改 durable schema，不需要 migration，不需要 dual-read，不需要兼容旧 handle/旧 link。

## 4. 核心判断

推荐方案是：

- subagent 升级成独立 child session
- 但 child session 仍然是 main agent 派生出的 delegated runtime unit
- 它不是新的 persistent agent identity

换句话说：

- 现在：`child = parent session 内的一条 durable child lane`
- 目标：`child = 一个真实独立 session，由 parent session 通过 control plane 引用`

## 5. 架构边界

### 5.1 Parent Session 持有的东西

parent session 只保留：

- user-facing control plane
- parent-child lineage
- child latest state 的投影入口
- 对 child 的 mailbox/control 请求入口

parent 不再持有 child 的真正 execution ownership。

### 5.2 Child Session 持有的东西

child session 持有：

- 自己的 `sessionId`
- 自己的 session queue / owner
- 自己的 run records
- 自己的 prompt checkpoints
- 自己的 transcript / run journal
- 自己的 mailbox / follow-up input
- 自己的 keepalive / recovery / cancel

### 5.3 Parent 与 Child 的连接物

新增一等 durable 实体：`SubAgentSessionLink`

建议最小字段：

```kotlin
data class SubAgentSessionLink(
  val parentSessionId: String,
  val parentRunId: String,
  val agentId: String,
  val childSessionId: String,
  val childRootRunId: String?,
  val childRootTaskId: String?,
  val subagentType: String,
  val contextMode: String,
  val depth: Int,
  val label: String,
  val status: String,
  val closed: Boolean,
  val createdAtEpochMs: Long,
  val updatedAtEpochMs: Long,
)
```

这条 link 的职责只是：

- parent 如何找到 child
- host/UI 如何把 child 投影回 parent
- control plane 如何把 `agentId` 路由到真实 `childSessionId`

它不替代 child 自己的 session 数据。

## 6. 控制面语义

### 6.1 `spawn_agent`

`spawn_agent` 改成：

1. parent runtime 生成 `agentId`
2. 创建 `childSessionId`
3. 创建一条 `SubAgentSessionLink`
4. 用 child session 提交 child root run
5. 立即启动 child session owner
6. 返回给 parent 一条 link-aware result

这里不再创建 hidden actor owner lane。

### 6.2 `wait_agent`

`wait_agent` 改成：

1. 通过 parent session 的 `agentId` 找到 `SubAgentSessionLink`
2. 解析出 `childSessionId`
3. 读取 child session 当前最新稳定状态
4. 如果 child 正在运行，就 join child session 的 owner/work
5. 返回 child latest stable snapshot/result

也就是说，`wait_agent` 不再等待 parent 内部的 synthetic recovery task，而是直接观察 child session。

### 6.3 `send_input`

`send_input` 改成：

1. 通过 `agentId` 找到 `childSessionId`
2. 把 follow-up 写入 child session mailbox
3. 如果 child session 当前空闲且可继续，唤醒 child session owner
4. 如果 child 正在跑，则保留 mailbox，等 child 自己下一轮消费

### 6.4 `close_agent`

`close_agent` 改成：

1. 通过 `agentId` 找到 `childSessionId`
2. 请求 child session cancel/terminate active work
3. 把 `SubAgentSessionLink.closed=true`
4. child session 进入 terminal or closed state
5. host/UI 投影 closed child

### 6.5 `Task`

`Task` 继续保留为 sugar：

- 语义上等于 `spawn_agent + wait_agent`
- 但内部直接复用 child session path

## 7. 恢复与持久化

### 7.1 Child 恢复

child 恢复以后由 child session 自己负责：

- cold restart 后，runtime manager 重建 child session
- child session 自己从 checkpoint / run journal / transcript 恢复
- parent 不再替 child 挂 hidden actor owner 壳

### 7.2 Parent 恢复

parent 恢复只需要：

- 恢复 `SubAgentSessionLink`
- 把 open child session 重新纳入 active work / keepalive
- 重新建立 host 投影

### 7.3 持久化布局

建议最小落点：

- parent session：
  - `subagent-session-links.json`
- child session：
  - 直接复用现有 session durable 目录结构

不需要保留旧的 parent-owned hidden actor durable schema。

## 8. Host / UI 投影

`runtimeActivity.subAgents` 后续改成由两部分合成：

- parent side link metadata
- child session latest snapshot

这样 host/UI 看到的是：

- 这条 child 属于哪个 parent
- 这条 child 当前真实 session 状态是什么

而不是继续依赖 parent 内部的 hidden actor / visible wait 壳去猜。

## 9. 保持不变的产品边界

即使做成独立 child session，下面这些也不变：

- 只有一个 main agent identity
- child session 不是独立 agent 产品实体
- child 不拥有独立 soul
- child 不拥有独立长期 memory store
- child 不拥有独立 workspace ownership
- child 结果是否进入 durable memory，仍由 main agent / host 主链决定

## 10. 落地阶段

### Phase 1

先引入 `SubAgentSessionLink` 和 `childSessionId`。

目标：

- durable parent-child 映射先成立
- 新 link 从引入开始就是唯一 durable source of truth
- 代码重构可以分阶段推进，但不为旧 schema 保留兼容语义

### Phase 2

把 `spawn_agent` 改成真实创建 child session 并提交 child root run。

目标：

- child execution owner 开始从 parent 转到 child session

### Phase 3

把 `wait_agent / send_input / close_agent / list_subagents` 全部改成通过 link 路由 child session。

目标：

- parent 不再通过内部 hidden actor/recovery lane 直接持有 child control plane

### Phase 4

把 host/runtimeActivity/UI 投影切到 `link + child snapshot`。

目标：

- 读模型不再依赖 parent 内部 child lane

### Phase 5

删掉 hidden actor owner lane 和 parent-owned recovery shell。

目标：

- 完成 owner 语义切换

## 11. 为什么这次不做兼容

当前处于开发阶段，可以接受：

- durable schema 直接改
- parent-owned hidden actor 数据直接废弃
- 不保留 migration
- 不写 dual-read / dual-write

这样可以显著降低复杂度，避免为了过渡态把最终语义再次污染。

## 12. 一句话结论

下一阶段正确的切法不是继续强化 parent 内部 hidden actor，而是：

- 新增 `SubAgentSessionLink`
- 给每个 child 分配真实 `childSessionId`
- 让 child 用独立 session owner 跑起来
- 让 parent 退回 control plane + projection 角色

这仍然是单主 agent 架构，不是多 agent 产品化。
