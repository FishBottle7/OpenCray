# 2026-04-16 流式与运行时排查记录

## 当前已确认的问题

1. 聊天气泡里的 `process` 会串气泡。
   第二个过程说明会继续写进第一个 `process` 气泡里，没有按轮次分开。

2. `run inspector` 有时是空的，或者看不到 `websearch`。
   但同一轮里界面上已经能看到 `todo` 和 `process`，说明运行并不是没开始。

3. 有些请求会一直“思考中”，API 侧已经有请求甚至已经返回，界面却没有正常落到聊天气泡。

4. 触发 `interrupt` 后，运行和恢复链路不稳定。
   用户实测出现过：
   - `unable to interrupt`
   - 回到 `seed` 页面
   - 停在 `seed` 页面后闪退

5. 画面会抽动。
   现象更像是运行时快照在高频重复推送，Flutter 侧被反复重建。

6. 曾复现过严重内存问题。
   某次真实设备运行里，最终错误明确是 `RUNTIME_EXCEPTION`，并带有大块内存分配失败信息。

## 已拿到的实证

### 1. 运行本身是创建成功的

Android 侧日志已经看到：

- `runtime.submit`
- `chat.submitPromptRun`
- `runtime.ensureProcessingScheduled`
- `runtime.taskStarted`
- `service.emitChatRuntimePayload ... activeRuns=1`

这说明 “inspector 里看不到” 不是因为 run 根本没创建。

### 2. 之前复现过的失败不是简单的上游 499

真机沙箱里持久化出的运行数据里，失败 run 的结果是：

- `lastResult = null`
- `lastEvent.phase = ERROR`
- `errorCode = RUNTIME_EXCEPTION`
- `errorMessage = Failed to allocate a 83170832 byte allocation ...`

对应会话任务状态也是：

- `task lifecycle = FAILED`
- `lastErrorCode = RUNTIME_EXCEPTION`

所以那次失败是应用侧内存打爆，不是单纯把责任推给供应商 499。

### 3. 失败 run 里已经持久化了多次 `WebSearch`

真机沙箱里的 `runtime-transcript.json` 里已经看到了多组：

- `WebSearch` tool call
- `WebSearch` tool result

而且已经累计到 `oc-call-7`

这说明：

- 模型确实进入了 `websearch`
- transcript 持久化链路至少部分是通的
- 但 inspector 展示链路和 transcript 持久化链路并不一致

### 4. 上下文预处理不是主要卡点

已经抓到：

- `context.memoryFlush` 很快，且 `NO_PRESSURE`
- `context.compaction` 很快
- `soul_turn_signal_interpreter` 启动了，但不是那次长时间卡住的根因

## 当前怀疑最大的几个代码点

1. `OpenAiCompatibleLiteLlmProviderClient.kt`
   重点查这些方法：
   - `readOpenAiResponsesStream`
   - `processOpenAiResponsesStreamEvent`
   - `storeResponsesOutputItem`
   - `replaceResponsesOutputItems`
   - `responsesVisibleText`

   这里最可疑的是：
   - 是否把 `web_search_call` 之类的大块原始 payload 一直累计在内存里
   - 是否在 `response.completed` 时又整包替换了一次，造成重复拷贝
   - 是否既保存原始协议，又重复生成了展示文本，导致内存和 CPU 双重放大

2. Flutter 聊天页的 run trace 映射
   重点查：
   - `_mapRunTraces`
   - `_buildRunTraceHistory`
   - `_mapRunTraceHistoryEntry`

   要确认：
   - 为什么 transcript 里已经有工具调用，inspector 里却没有
   - 为什么第二个 `process` 会写进第一个 `process` 气泡

3. 运行时快照推送
   已经加了 `service.emitChatRuntimePayload` 日志。
   目前看到它会持续高频发同样结构的 payload，可能就是界面抽动的直接原因。

## 已做过的辅助修改

1. 加了运行时和 provider 诊断日志，覆盖：
   - provider execute start/end
   - 上下文准备
   - runtime submit / ensure processing
   - service payload emit

2. 设置页调试页面已经修过一次：
   `recent runs` 不再只看 `activeRuns`，也会纳入 `retainedRuns`

## 当前结论

现在不能把问题归纳成“只是流式 UI 没接好”。

至少同时存在三条独立问题链：

1. Responses 流式事件在 Android 端的缓存/替换/裁剪策略有问题。
2. 运行轨迹展示和 transcript 持久化不是同一条数据通路，导致 inspector 丢内容。
3. Flutter 侧对 live draft / process 气泡 / pending message 的映射有串位问题。

## 下一步

1. 继续把 `OpenAiCompatibleLiteLlmProviderClient.kt` 里的 Responses 流式缓存链路逐段读透。
2. 对照 Flutter 聊天页把 `process` 气泡和 `run inspector` 的映射条件查清楚。
3. 在有确证后做最小修复，再上真机复现：
   - `process` 不串气泡
   - `websearch` 能进 inspector
   - run 不再异常退出
   - 聊天气泡里的流式输出与真实生成同步

## 2026-04-16 晚些时候已落地的修复

1. `process` 不再只靠运行时投影临时显示。
   现在收到可见的 `assistant_phase` 事件后，会把它作为正式聊天消息插到当前 pending assistant 气泡之前。

2. 当前正在生成的那一段，仍然继续走原来的 pending 气泡和 live draft。
   这样一个 prompt 可以形成：
   - `process 1`
   - `process 2`
   - `final answer`
   三个独立气泡，而且前面的 `process` 不会在 final 出来后消失。

3. 为了避免重复显示，host 侧现在会跳过“已经持久化到聊天消息里的 runtime projected bubble”。

4. 这个修复仍然保留了旧有的执行域过滤。
   也就是说：
   - 旧 execution 的 `assistant_phase` 不会误插成当前气泡
   - `llm_retry` / `responses_recovery` 这类隐藏阶段仍然只进 runtime activity，不进聊天气泡

## 2026-04-17 新增实证与修复

1. 最新一次真机问题会话里，设备持久化文件已经确认：
   - `run-journal` 没有任何 `assistant_phase`
   - `runtime-transcript.json` 也没有任何 `COMMENTARY`
   - 只有连续的 `WebSearch` tool call / tool result，最后直接 `RUNTIME_EXCEPTION` OOM

2. 这说明这次主链不是 Flutter 把已经存在的 process 渲染丢了。
   真正的问题更早，在 Responses 流式事件合并阶段。

3. 已定位到一个明确 bug：
   Responses 流式里，如果上游复用了同一个 `output_index`，当前实现会把前一个 message item 直接覆盖成后来的 `function_call` 或另一个 message。

4. 这个 bug 会造成非常典型的现象：
   - 运行中 live draft 里能短暂看到 process
   - 到最终结构化完成时，commentary message 已经被覆盖掉
   - runtime 无法产出 `assistant_phase`
   - inspector 里没有对应 process
   - 聊天气泡里的 process 只闪一下或者最后消失

5. 已修复：
   - Responses 流式合并现在会在 `output_index` 冲突时为“新的逻辑 item”分配新槽位，不再覆盖已有的 message / function_call
   - 同时补了回归测试，覆盖：
     - commentary message 被 tool call 复用同一 `output_index`
     - commentary message 被 final answer message 复用同一 `output_index`

6. 修复后本地验证通过：
   - `OpenAiCompatibleLiteLlmProviderClientTest`
   - `OpenCrayHostRuntimeTest`
