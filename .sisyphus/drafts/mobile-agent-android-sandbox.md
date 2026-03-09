# Draft: Android Agent App (Claude Code/OpenClaw-like)

## Requirements (confirmed)
- 目标产品：做一个手机端、类似 Claude Code / OpenClaw 的程序。
- 基础功能：Agent 可在应用程序目录内调用 Python。
- 基础功能：Agent 可安装 Python 插件/依赖，以满足用户任务需求。
- 基础功能：Agent 可写入 Markdown 文件（如任务输出）。
- 进阶功能：在安卓端结合 Termux 搭建 Linux 沙盒，让 Agent 在沙盒内完成任务。
- 进阶功能：沙盒内可部署类 OpenClaw 的程序，或轻量替代（如 ZeroClaw）。
- 权限能力：Agent 可在应用目录内写入/删除文件。
- 删除限制：`agent.md`、`memory.md`、`soul.md` 不能被删除。
- 执行能力：Agent 可运行脚本及终端命令。
- 沟通偏好：希望用苏格拉底提问法，优先选择题，来完善计划。
- 新增能力：Agent 需支持 **Skills** 与 **MCP**。
- 新增能力：Agent 可为自己安装 Skills。
- 新增能力：Agent 可为自己创建 Skills。
- 新增 UI：需要 Skills 管理界面，至少包含：创建、编辑、禁用（模型暂时不可见）、删除。

## Technical Decisions
- 当前阶段：需求澄清中（Interview Mode）。
- 初步判断：这是「架构 + 从零构建」类型任务，需先明确边界、风险与分阶段目标。
- 核心价值优先级：**自动化执行闭环**（接收任务→执行→产出证据/结果）。
- MVP策略：**先做 App 内 Python 执行闭环**，Termux 沙盒作为后续增强。
- Termux阶段路线：**可插拔双后端**（OpenClaw 类 / ZeroClaw 类可切换）。
- 安全倾向：**安全优先**（默认加保护与限制）。
- 模型运行方式：**云端 API 优先**。
- Python依赖治理：**开放 PyPI**（需在计划中补充风险控制机制）。
- 文件删除保护：**扩展系统保护清单**（不仅限于 3 个文件）。
- 文件系统边界：**App 私有目录 + SAF 授权访问共享存储**。
- 执行脚本联网：**默认开放网络**。
- 高危授权模型：**默认分级确认**，并允许用户在设置中开启“全自动模式（需风险提示）”。
- Agent 身份与环境：**单一长期 Agent**，跨会话持久化（memory/soul 连续）。
- 异常恢复语义：**回滚后重试**。
- 测试策略：**TDD + Agent 执行式 QA**。
- MVP Python方案：**内嵌 Python**。
- 回滚语义边界：**仅本地可回滚**（外部写操作默认不承诺回滚）。
- 模型供应商接入：**通过 LiteLLM 等统一层支持多供应商**。
- 执行编排：**单会话串行 + 队列**。
- 新增目标：**使用体验尽量接近 Claude Code / Codex / OpenCode / OpenClaw**，并补上可用 UI。
- 新增资料源：**system-prompts-and-models-of-ai-tools** 仓库可作为提示词与交互范式参考，需写入计划。
- 跨平台策略：**Android 原生先行**，后续扩展跨端。
- Skills安装源：**完全开放来源**。
- Skills权限策略：**统一跟随全局模式**（不做来源分级）。
- MCP策略：**完全开放**，非白名单MCP由用户手动启用且可持久化。
- 安全冲突拍板：**双配置方案**（默认有护栏，开发者模式可放开）。
- Skills UI 首版必需：**创建/编辑/禁用/删除 + 安装/卸载 + 导入/导出**。
- 全局执行模式：**三档模式**（安全默认 / 自动执行 / 开发者模式）。
- Skills 禁用语义：**全局 + 会话覆盖**。
- 自建Skill生效：**先验证再启用**。
- V1 排除项：**多Agent并行、iOS客户端、云端协作同步、Skill市场审核系统**。
- 测试基建：**Android JUnit+MockK(+Espresso) + Python pytest**。

## Research Findings
- 来源：Oracle 咨询（架构审查）
- 关键建议：先冻结统一运行时契约（`executeCommand` / `fileOp` / `pipInstall` / `policyCheck` / `checkpoint-resume`），避免后续 Internal Python 与 Termux 行为漂移。
- 关键建议：采用“策略引擎前置 + RuntimeAdapter 后置”，让 MVP 到 Termux 升级是后端替换而非整体重构。
- 关键风险：
  - 运行时契约漂移导致返工
  - 工作区越界（路径穿越/软链逃逸）
  - 开放 PyPI 供应链风险
  - Android 生命周期导致任务中断不一致
  - 缺乏审计日志导致不可追溯
- 推荐护栏：
  - 命令执行分级（Allow / Confirm / Block）+ 超时/并发/输出上限
  - 文件操作必须 canonical path 校验、受保护文件不可删改、批量删除二次确认、软删除回收站
  - pip 限定 workspace venv，默认仅 PyPI，限制 URL/git/editable/local 安装
- 外部参考（高置信）：
  - Claude Code 权限与沙盒文档：支持分层权限、规则优先级（deny→ask→allow）、沙盒文件/网络隔离。
  - Codex CLI 文档：有 sandbox/approval/full-auto/yolo 等模式与风险分级思路。
  - OpenCode README：有 build/plan agent 分工、多会话、provider-agnostic、桌面端与客户端/服务端架构倾向。
- 外部参考（中置信/需谨慎）：
  - `system-prompts-and-models-of-ai-tools` 作为“模式灵感库”可用于总结提示词结构与工具调用范式；
  - 但其内容来源与时效性不一定可验证，且仓库许可证为 GPL（`LICENSE.md`），应避免直接复制原文作为产品内置资产。
- Skills/MCP 通用标准调研（高置信）：
  - Claude Code 官方文档：Skills 基于 **Agent Skills** 开放标准（agentskills.io）；`SKILL.md` + 前置 frontmatter；支持插件市场分发、可配置自动调用与手动调用、可限制工具。
  - Codex 官方文档：Skills 也基于 **open agent skills standard**；目录结构为 `SKILL.md` + 可选 scripts/references/assets + 可选 `agents/openai.yaml` 元数据；支持显式与隐式触发。
  - OpenCode 官方文档：Skills 采用 `SKILL.md` 目录化结构，支持多路径发现（`.opencode/.claude/.agents`），并可按模式/通配符进行权限控制。
  - 三者共同交集：
    1) 技能目录化 + `SKILL.md` 入口
    2) `name/description` 元数据驱动触发
    3) 显式调用与（可选）隐式调用
    4) 技能可见性/权限控制
  - MCP 共同交集：支持本地进程（stdio）与远程服务（HTTP/SSE）接入，支持启用/禁用、作用域或配置分层、认证（OAuth/API Key）。

## Open Questions
- Python 运行时实现：Android 端选型（嵌入式 Python/Chaquopy/其他）及升级策略。
- 命令执行治理：白名单、黑名单、交互确认、超时/资源配额具体规则。
- 插件安装安全：开放 PyPI 前提下的签名校验、哈希锁定、沙盒权限限制。
- Agent 编排：单 Agent 串行，还是任务级多 Agent 并行。
- 任务结果“证据化”标准：日志、终端输出、文件差异、截图等格式要求。
- 发布策略：先 Android 单端，还是预留 iOS/桌面协议兼容层。
- 客户端技术栈：Kotlin 原生 / Flutter / React Native。
- 跨平台与性能折中：你希望“Android 先原生 + 抽象核心层后续跨端”，还是“一开始跨端框架 + 原生能力插件化”。
- 模型接入策略：首发支持哪些云模型供应商（OpenAI/Anthropic/兼容OpenAI协议）。
- 权限模型细化：全自动模式下哪些操作仍不可绕过确认（硬护栏）。
- 回滚机制边界：哪些操作可回滚（文件/依赖），哪些不可回滚（外部API写操作）。
- TDD落地框架：Android 侧（JUnit/Mockk/Espresso）与 Python 侧（pytest）组合。
- Skills 标准拍板：以 **Agent Skills** 作为主标准，外加哪些扩展字段（如 `allowed-tools`、`user-invocable`、`agents/openai.yaml`）进入V1。
- Skills 安装包格式：V1 是“目录包（zip/git）”还是“目录包 + 市场清单（manifest）”双制。
- Skills 导入导出格式：是否统一导出为标准目录结构（含 `SKILL.md` 与可选 metadata 文件）。
- Skills 禁用语义：全局禁用、会话级禁用、模型级可见性控制（你提到“让模型暂时看不到”）如何组合。
- 自创建 Skills 审核：是否需要 lint/安全扫描/手动确认后才能生效（在“统一跟随全局模式”下如何触发）。
- 全局模式定义：默认/自动/开发者模式对应哪些具体可执行动作。
- 计划边界（OUT）：首版明确不做哪些能力（如多Agent并发、端到端远程同步、iOS等）。

## Latest User Answers (Round 1)
- Q1 核心价值：自动化执行
- Q2 MVP范围：先 App 内 Python
- Q3 沙盒后端：可插拔双后端
- Q4 安全边界：安全优先
- Q5 模型部署：云端 API 优先
- Q6 依赖安装：开放 PyPI
- Q7 删除保护：扩展保护清单

## Latest User Answers (Round 2)
- Q8 目录访问：私有目录 + SAF
- Q9 脚本联网：默认开放
- Q10 授权模型：默认分级确认；可在设置中启用全自动（需警示）
- Q11 环境持久：单一长期 Agent，跨会话持续状态
- Q12 异常恢复：回滚后重试
- Q13 测试策略：TDD + AgentQA

## Test Strategy Decision
- **Infrastructure exists**: NO（新项目，需在计划内建立测试基建）
- **Automated tests**: YES（TDD）
- **If setting up**: Android 测试 + Python 测试双栈（待最终框架确认）
- **Agent-Executed QA**: ALWAYS（每任务必须有可执行场景和证据）

## Latest User Answers (Round 3)
- Q14 客户端技术栈：目标是跨平台，但不想牺牲原生性能（尚未最终定型）
- Q15 MVP Python方案：内嵌 Python
- Q16 全自动硬护栏：用户完全自定义
- Q17 回滚边界：仅本地可回滚
- Q18 模型供应商：LiteLLM 多供应商支持
- Q19 并发模型：单会话串行 + 队列
- Q20 竞品参考：希望参考 Claude Code / Codex / OpenCode / OpenClaw 的驱动方式
- Q21 提示词资料：纳入 `system-prompts-and-models-of-ai-tools` 项目作为计划输入

## Latest User Answers (Round 4)
- Q22 继续推进：已要求继续访谈与计划完善
- Q23 新增范围：必须支持 Skills/MCP 与可视化 Skills 管理（增改禁删）

## Latest User Answers (Round 5)
- Q24 跨平台路线：Android 原生先行
- Q25 Skills 来源：完全开放来源
- Q26 Skills 授权：统一跟随全局模式
- Q27 MCP：完全开放，非白名单需用户手动启用且可持久化
- Q28 安全冲突：双配置方案
- Q29 Skills UI 必需：创建/编辑/禁用/删除 + 安装/卸载 + 导入/导出

## Latest User Answers (Round 6)
- Q30 模式矩阵：三档模式
- Q31 Skill模型：按 Claude Code/Codex/OpenCode 等主流工具支持方式对齐“通用标准”（需调研）
- Q32 禁用语义：全局 + 会话覆盖
- Q33 自建Skill生效：先验证再启用
- Q34 V1排除：多Agent并行、iOS客户端、云端协作同步、Skill市场审核系统
- Q35 测试框架：JUnit+MockK+pytest

## Notes on Potential Conflicts
- “安全优先” 与 “全自动模式下用户完全自定义（可绕过全部护栏）”存在冲突，需确认是否保留不可绕过的最小系统护栏。

## Scope Boundaries
- INCLUDE：移动端 Agent、Python 执行、插件安装、文件写删策略、Termux 沙盒规划。
- EXCLUDE：暂未确认（待用户明确）。
