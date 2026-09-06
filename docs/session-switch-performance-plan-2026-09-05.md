# 会话切换性能分层方案 2026-09-05

## 现状瓶颈

每次 `selectChatSession`（service-owned 路径，`:runtime` 进程）在 coordinator 锁内完成：

```
selectChatSession
 1. loadState()                     ← workspace.json 全量读
 2. canDiscardEmptySession 探测链    ← 历史上各自独立全量读
 3. selectSession()                 ← 全量读 + 全量序列化 + fsync 写回
 4. repairTerminalReplay             ← 每条 run 全量读 transcript.json + 全量 normalize
 5. activateSession → resume()      ← 唤醒 runtime session（合理，保留）
 6. ensureWarmForActiveSession      ← LLM warmup 探测
 7. notifyChatSnapshotsChanged      ← 全量重算 chat snapshot
```

根因是单文件全量序列化：`chat-workspace.json` 把所有会话的所有消息放在
一个 JSON 里。任何读 = 全量反序列化；任何写 = 全量序列化 + fsync。
当前 5KB 数据感知不到；长到几 MB 后切换是秒级。

## 分层改法

### 第一层（已完成 2026-09-05）

`AppAgentSessionTaskRuntimeFactory.repairTerminalReplayFromRunSnapshots`：
循环内每条 run 调一次 `store.snapshot()`（文件锁 + 全文读 + 全量
normalize）改为整个批次一次快照 + 一次批量 `replaceReplayWorkingCopy`。
重复 repair 时不再写盘（normalize 后无 diff 即 write=false）。
N 条历史 run 的切换读次数从 O(N) 降到 O(1)。

回归测试：`AppAgentSessionTaskRuntimeFactoryTodoStoreTest.
repairTerminalReplayFromRunSnapshotsAppendsEachMissingRunObservationInOnePass`。

### 第二层（已完成 2026-09-05）

- `ChatSessionLocalStore.loadState()` 撤掉冗余的第二次
  `workspaceStore.load()`。
- `ChatSessionMutationCoordinator.selectChatSession` 探测链改为单次
  `loadWorkspaceRecord()` 读，`isReusableEmptySession` 走传入 record 的
  重载，不再独立读文件。
- 每次 select 的 workspace 读从 4 次降到 3 次（1 loadState + 1 探测 +
  1 selectSession 内部），写仍为 1 次（不可避免，activeSessionId 变了）。

### 第三层（评估后搁置）：workspace 进程内缓存

想法：`ChatSessionLocalStore` 持有进程内 cache，读写都走内存，写穿透。

风险：多实例真实存在——`OpenCrayRuntimeServiceGatewayBundle` 传一个实例，
但同进程内 `AppScheduledTaskManager`、`AppImageReferenceHostServices`、
bundle 第 161 行各建独立实例。A 实例写后 B 实例缓存必须失效，否则
UI 显示旧会话列表。需要 mtime+size 校验或失效广播兜底，复杂度换
2 次读的收益，且把当前"文件锁保证一致性"的简单模型打开缺口。
结论：不做，除非第二层实测后仍有明显瓶颈。

### 第四层（架构级，待单独立项）：分库存储

把 `sessions[].messages` 拆为每会话独立文件（如
`chat-sessions/<sessionId>/transcript.json`），`chat-workspace.json`
只留元数据（id/title/时间戳/activeSessionId/extensions key）。

- 切换只反序列化目标会话，成本与会话总数解耦。
- 写只动目标会话文件 + 小元数据文件。
- 需要迁移逻辑（旧单文件 → 新布局）、双格式兼容期、以及
  `referencedAttachmentLocalPaths`/`copySession`/`branchSessionFromMessage`
  等全量扫描调用的适配。
- 这是唯一保证"长年使用后依然快"的方案。预计 1-2 天独立任务。

## 验证方式

真机：切换会话前后 `logcat` 里 `serviceChatDebug` 的时间戳；
或 `adb shell am start` 后在 Sessions 抽屉快速连续切换，观察
`chat-workspace.json` 的 IO（`strace` 不可用时用 logcat 间接）。

## 执行状态

- 第四层（分库存储）完成（2026-09-06，d55e8eb）：`chat-workspace.json` 只留会话
  元数据与去规范化摘要字段，每会话消息拆至
  `chat-sessions/<base64url-sid>/transcript.json`（同一文件锁协议）。消息变更走
  T→W 两阶段锁序（transcript 锁改写→workspace 锁刷新元数据），不嵌套持锁。旧布局
  由 `loadWorkspaceOrCreate` 两阶段迁移（a:逐会话写 transcript；b:清空 inline），
  崩溃可重入、幂等、并发完成者优先。迁移/引导/并发回归测试 6 个新增；全量
  1864 app 单测 + 31 persistence 单测通过。
