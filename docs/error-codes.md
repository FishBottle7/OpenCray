# 错误码对照表

用户可见的错误都带一个短错误码（`E` + 4 位数字，例如 `E1001`），方便口头描述和记录问题。短码是内部字符串错误码的展示层映射；内部 UPPER_SNAKE_CASE 字符串码仍然是唯一事实来源，持久化与 Flutter 桥接继续使用字符串码。

## 显示规则

- 聊天终态失败文案统一为：中文 `Agent 请求失败 [E1001]: 具体原因`，英文 `Agent request failed [E1001]: 具体原因`。
- 内部错误码未注册时不显示短码，回退为原有无短码文案。
- 取消（Interrupted）、审批等待、Missing LLM 等非失败终态不追加短码。
- 渲染入口只有两个：`HostRuntimeStrings.agentFailed` 与 `AppAgentSessionTaskRuntimeFactory.transcriptAgentFailedText`。不要在其他地方手工拼短码。

## 段位划分

| 段位 | 领域 |
|---|---|
| E0xxx | 策略与审批 |
| E1xxx | 命令与进程执行 |
| E2xxx | LLM 与提供方 |
| E3xxx | 会话编排与运行时 |
| E4xxx | 文件系统 |
| E5xxx | 技能 |
| E6xxx | MCP（保留段，服务端返回的认证错误码为动态值） |
| E7xxx | 终端环境 |
| E8xxx | 子代理 |
| E9xxx | 未归类 / 未知 |

注册表源文件：`core/src/main/kotlin/com/opencray/core/error/UserFacingErrorCodes.kt`。新增或改名错误码时必须同步注册并更新本表。

## E0xxx 策略与审批

| 短码 | 内部码 | 含义与常见原因 |
|---|---|---|
| E0001 | DENY_POLICY | 当前模式下策略直接拒绝该操作 |
| E0002 | APPROVAL_REQUIRED | 操作需要用户审批后才能继续 |
| E0003 | HIGH_RISK_APPROVAL_REQUIRED | 高风险操作需要用户确认后才能继续 |
| E0004 | WORKSPACE_BOUNDARY_DENIED | 目标路径越出工作区边界 |
| E0005 | SKILL_TOOL_POLICY_BLOCKED | 技能内调用的工具被策略拦截 |
| E0006 | BLOCK_APPROVAL_REQUIRED | 命令门控：缺少所需审批 |
| E0007 | BLOCK_APPROVAL_TASK_MISMATCH | 命令门控：审批与目标任务不匹配 |
| E0008 | DENY_POLICY_DECISION | 命令门控：策略决定拒绝执行 |
| E0011 | DENY_INVALID_PATH | 路径非法，无法解析 |
| E0012 | DENY_PATH_TRAVERSAL | 路径穿越（如 `..`）被拒绝 |
| E0013 | DENY_PATH_ESCAPE | 路径逃出允许的根目录被拒绝 |
| E0014 | DENY_PROTECTED_FILE | 访问受保护文件被拒绝 |

## E1xxx 命令与进程执行

| 短码 | 内部码 | 含义与常见原因 |
|---|---|---|
| E1001 | TIMEOUT | 命令或进程超时 |
| E1002 | EXEC_ERROR | 进程启动后执行出错（非零退出等） |
| E1003 | SPAWN_ERROR | 进程无法启动 |
| E1004 | OUTPUT_LIMIT_EXCEEDED | 输出超过上限被截断终止 |
| E1005 | CANCELLED_BY_HOOK | 执行前被钩子取消 |
| E1006 | CANCELLED | 进程被取消 |
| E1011 | PYTHON_RUNTIME_EXECUTION_FAILED | Python 运行时执行失败 |
| E1012 | PROCESS_INTERRUPTED_ON_RESTORE | 宿主恢复时托管进程被中断 |

## E2xxx LLM 与提供方

| 短码 | 内部码 | 含义与常见原因 |
|---|---|---|
| E2001 | PROVIDER_FAILURE | 提供方请求失败（网络/鉴权/服务端错误） |
| E2002 | PROVIDER_COMPACT_FAILURE | 上下文压缩请求失败 |
| E2010 | PROVIDER_TIMEOUT_FALLBACK_APPLIED | 提供方超时，已切换备用路由重试 |
| E2011 | PROVIDER_TIMEOUT_TERMINAL_POLICY | 提供方超时且策略不允许回退，任务终止 |
| E2012 | PROVIDER_TIMEOUT_FALLBACK_EXHAUSTED | 提供方超时且回退路由全部耗尽 |
| E2020 | PROVIDER_RATE_LIMIT_429_FALLBACK_APPLIED | 触发限流（429），已切换备用路由重试 |
| E2021 | PROVIDER_RATE_LIMIT_429_TERMINAL_POLICY | 触发限流（429）且策略不允许回退，任务终止 |
| E2022 | PROVIDER_RATE_LIMIT_429_FALLBACK_EXHAUSTED | 触发限流（429）且回退路由全部耗尽 |
| E2031 | LLM_RETRY_EXHAUSTED_AWAITING_RESUME | LLM 重试耗尽，等待用户手动恢复 |
| E2032 | EMPTY_RESPONSE_RECOVERY_EXHAUSTED | 空响应恢复尝试耗尽 |
| E2040 | MISSING_LLM_CONFIG | LLM 配置不完整（显示为 Missing LLM 文案） |
| E2041 | ON_DEVICE_LLM_NOT_SUPPORTED | 所选端上模型不受支持或不可用 |

## E3xxx 会话编排与运行时

| 短码 | 内部码 | 含义与常见原因 |
|---|---|---|
| E3001 | RUNTIME_EXCEPTION | 运行时抛出未归类异常（兜底捕获） |
| E3002 | RUNTIME_INTERRUPTED | 运行时执行被中断 |
| E3003 | RESTART_REQUIRES_EXPLICIT_RETRY | 宿主重启后需要用户显式重试该任务 |

## E4xxx 文件系统

| 短码 | 内部码 | 含义与常见原因 |
|---|---|---|
| E4001 | INVALID_OPERATION | 文件操作类型非法 |
| E4002 | ALREADY_EXISTS | 目标文件或目录已存在 |
| E4003 | FILE_NOT_FOUND | 文件或目录不存在 |
| E4004 | IO_ERROR | 文件读写 IO 错误 |
| E4005 | ROLLBACK_FAILED | 批量写失败且回滚也失败（数据可能处于中间状态） |

## E5xxx 技能

| 短码 | 内部码 | 含义与常见原因 |
|---|---|---|
| E5001 | FILE_READ_FAILED | 技能文件读取失败 |
| E5002 | MISSING_FRONT_MATTER | 技能缺少 front matter |
| E5003 | UNTERMINATED_FRONT_MATTER | front matter 未正确闭合 |
| E5004 | INVALID_FRONT_MATTER | front matter 解析失败 |
| E5005 | DUPLICATE_SKILL_NAME | 技能名称重复 |
| E5006 | INVALID_SKILL_METADATA | 技能元数据校验失败（name/description 不合规） |

## E7xxx 终端环境

| 短码 | 内部码 | 含义与常见原因 |
|---|---|---|
| E7001 | TERMUX_UNAVAILABLE | Termux 运行环境不可用 |

## E8xxx 子代理

| 短码 | 内部码 | 含义与常见原因 |
|---|---|---|
| E8001 | SUBAGENT_BACKGROUND_INTERRUPTED | 后台子代理被中断 |
