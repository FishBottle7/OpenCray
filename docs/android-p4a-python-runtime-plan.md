# Android `p4a` 内嵌 Python 实施方案

截至 2026-03-16，本方案的目标已经收敛为：

- 平台范围只覆盖 Android。
- Android 端先使用 `python-for-android`（`p4a`）。
- 只支持运行工作区内的 Python 脚本。
- Python 依赖全部随应用预编译内置。
- 明确不支持真 `venv`。
- 明确不支持运行时 `pip install`。

这份文档不是可行性讨论，而是面向仓库当前结构的实施方案。

相关背景文档见：

- `docs/p4a-python-runtime-feasibility.md`

## 1. 结论与决策

当前仓库里的 `python_exec` 仍然依赖外部命令：

- `runtime/src/main/kotlin/com/opencray/runtime/PythonRuntimeAdapter.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/PipInstaller.kt`
- `python_runner/runner.py`

这条路径在 Android 真机上失败是预期行为，因为设备上通常不存在可直接调用的 `python` 命令。

因此 Android 端的决策是：

1. 保留现有 `python_exec` 工具语义。
2. 替换其 Android 运行时后端，不再调用外部 `python`。
3. Android 新后端采用 `p4a` 打包的内嵌 Python runtime。
4. 运行时只执行工作区脚本，不提供环境创建与动态装包能力。

## 2. 为什么现在选 `p4a`

在当前目标下，`p4a` 是 Android 侧最低风险的路线，原因如下：

- 它本来就是 Android Python 打包方案。
- 支持将 Python 解释器和固定依赖打进 Android 产物。
- 支持 service 模型，适合从现有 Android 宿主应用发起执行请求。
- 比“从零自编并维护 Android 版 CPython runtime”更容易落地。

这里的关键不是追求“完整 Python 主机”，而是稳定提供一项能力：

- 给 agent 一个可审计、可超时、可受控的“运行工作区 Python 脚本”能力。

## 3. 本阶段明确不做的事

为了避免方案失控，本阶段以下内容全部排除：

- 真 `venv`
- `python -m venv`
- `ensurepip`
- 运行时 `pip install`
- 从 PyPI 动态下载依赖
- 工作区级独立 Python 环境
- Android 上的通用 Python 包管理器

如果后面重新讨论这些能力，应视为新项目，不要在本方案中隐式恢复。

## 4. 目标能力边界

本阶段 Android Python runtime 只承诺下面这些能力：

- 输入：
  - `workspaceRoot`
  - `scriptPath`
  - `args`
  - `timeoutMs`
- 校验：
  - 脚本路径必须落在批准的工作区内
  - 脚本必须存在且是文件
- 执行：
  - 在内嵌 Python runtime 内运行目标脚本
  - 将白名单依赖加入 `sys.path`
- 输出：
  - `stdout`
  - `stderr`
  - `exitCode`
  - `status`
  - `errorCode`
  - `errorMessage`
  - `startedAtEpochMs`
  - `finishedAtEpochMs`

这些输出仍然统一映射到现有 `ExecutionResult`。

## 5. 仓库中的关键改造点

### 5.1 现状问题

当前 `OpenCrayToolDispatcherConfig` 把 `pythonRuntimeAdapter` 定义成了具体类：

- `runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt`

这会导致 Android 想切换成 `p4a` 后端时，缺少明确的抽象边界。

### 5.2 建议的代码结构调整

建议把当前结构改成：

1. 引入运行时接口，例如：
   - `PythonScriptRuntime`
2. 现有实现重命名为：
   - `HostProcessPythonRuntime`
3. Android 新增实现：
   - `P4aPythonRuntime`
4. `OpenCrayToolDispatcherConfig` 依赖接口，而不是具体类。

建议的职责划分如下：

| 类型 | 职责 |
| --- | --- |
| `PythonExecRequest` | 保持现有请求模型 |
| `PythonScriptRuntime` | 统一 `exec(request)` 契约 |
| `HostProcessPythonRuntime` | 保留当前桌面/宿主子进程实现 |
| `P4aPythonRuntime` | Android 生产实现 |
| `UnsupportedPythonDependencyInstaller` | Android 上明确返回“不支持 install” |

这样做的好处是：

- Android 不需要再伪装成“外部命令模式”
- 当前 JVM 测试可以继续覆盖 host backend
- 后续 Harmony 也可以挂自己的 backend，而不改 agent 工具层语义

## 6. Android `p4a` 方案的推荐架构

### 6.1 总体架构

Android 侧采用三层结构：

1. Kotlin 宿主层
2. 请求/结果桥接层
3. `p4a` Python 执行层

示意如下：

```text
AgentTooling.python_exec
  -> P4aPythonRuntime.exec(request)
    -> 写入 request.json 到 app-private runtime dir
    -> 启动 p4a Python service
    -> Python 读取 request.json
    -> 校验路径并执行脚本
    -> 写入 result.json
    -> Kotlin 轮询/等待 result.json
    -> 映射成 ExecutionResult
```

### 6.2 为什么桥接层推荐用 JSON 文件

推荐首版桥接不要直接上 Binder、socket 或自定义 JNI，先用 app 私有目录下的请求/结果 JSON 文件。

原因：

- Kotlin 与 Python 双方都容易实现
- 易调试，失败时可以直接看 request/result 文件
- 与当前 `ExecutionResult` 结构天然兼容
- 后面移植到 Harmony 时，这个协议层也更容易复用

推荐目录：

- `files/python_runtime/requests/<requestId>.json`
- `files/python_runtime/results/<requestId>.json`
- `files/python_runtime/logs/<requestId>.log`

### 6.3 Python 端不要直接执行任意命令

不要在 `p4a` 里暴露类似 shell 的能力。

Python 侧只提供一个固定入口，例如：

- `opencray_runtime_main.py`

它的职责是：

1. 读取请求文件
2. 解析 `workspaceRoot` / `scriptPath` / `args`
3. 做工作区路径边界校验
4. 配置 `sys.path`
5. 用 `runpy.run_path(...)` 执行脚本
6. 捕获 `stdout/stderr`
7. 将结果写回 `result.json`

这能避免 Kotlin 宿主层重新把“命令执行能力”泄露给 Python backend。

## 7. `p4a` 产物组织建议

### 7.1 独立维护 `p4a` 构建目录

建议新增独立目录，例如：

- `tools/android_python_runtime_p4a/`

这个目录不直接混入现有 app 模块源码，职责只有：

- 维护 `p4a` build recipe
- 维护 Python 入口脚本
- 维护依赖白名单
- 在 Linux CI 中生成 Android 产物

### 7.2 Android App 消费方式

建议优先采用下面两种方式之一：

1. 本地 `AAR`
2. 内部 Maven 仓库

不建议一开始就把 `p4a` 构建过程塞进 `app/build.gradle.kts`。

原因：

- `p4a` 本身构建链重
- 更适合 Linux CI 产出固定制品
- 可以把 Android 工程和 Python runtime 打包过程解耦

### 7.3 ABI 策略

首版建议只支持：

- `arm64-v8a`

等链路稳定后再考虑：

- `armeabi-v7a`
- `x86_64`（如确有模拟器调试需求）

这样可以明显降低构建矩阵和 QA 复杂度。

## 8. 依赖打包策略

### 8.1 首版依赖原则

首版依赖只允许两类：

- Python 标准库
- 明确列入白名单的纯 Python 第三方库

不建议首版就引入大量 native 扩展库。

### 8.2 依赖清单管理

建议在 `tools/android_python_runtime_p4a/` 下维护：

- `requirements.lock`
- `dependency-policy.md`

规则建议如下：

- 所有依赖必须固定版本
- 每次升级依赖都要重新生成 Android runtime 制品
- 每次升级都要跑脚本执行回归测试
- 原生扩展库必须单独审批，不允许顺手加入

### 8.3 业务语义调整

Android 端对外不要再暗示“支持 pip 装包”。

需要明确的产品和工程语义是：

- Android Python 能力使用“内置依赖集合”
- 依赖更新随 App 版本发布

## 9. 对现有工具层的具体改动建议

### 9.1 `python_exec`

`python_exec` 继续保留，但实现改为：

- Kotlin 层做审批
- Kotlin 层做工作区边界解析
- Kotlin 层调用 `P4aPythonRuntime`
- Python 侧只负责运行目标脚本

这样 `python_exec` 的工具语义不变，变的是执行后端。

### 9.2 `PipInstaller`

Android 端建议明确禁用。

建议返回稳定错误：

- `UNSUPPORTED_RUNTIME_INSTALL`

错误文案建议类似：

- `Android embedded Python runtime does not support runtime dependency installation.`

这比保留一个会失败的伪接口更清晰。

### 9.3 `ProcessStart` 的 `script_path`

当前 `ProcessStart` 已支持管理型 Python 脚本启动。

Android 第一阶段不建议把 `ProcessStart(script_path=...)` 直接接到 `p4a` 上。

原因：

- `p4a` service 更适合先实现“一次请求，一次执行”
- 后台长生命周期 Python 进程会显著提高复杂度
- 前期最容易做稳的是同步/短任务执行

建议第一阶段只打通：

- `python_exec`

而把：

- `ProcessStart(script_path=...)`

标记为后续增强项。

## 10. `P4aPythonRuntime` 的建议行为

### 10.1 请求输入

建议沿用 `PythonExecRequest`，不新增 agent 侧参数。

### 10.2 执行流程

建议如下：

1. 生成 `requestId`
2. 写入 request JSON
3. 启动 `p4a` service
4. 等待结果文件
5. 超时则写取消标记并返回 `TIMEOUT`
6. 读取结果文件并转为 `ExecutionResult`
7. 清理临时 request/result 文件

### 10.3 元数据建议

在 `ExecutionResult.metadata` 中新增：

- `runtimeBackend=p4a`
- `runtimeTransport=file_json`
- `requestId=<uuid>`
- `pythonRuntimeVersion=<version>`
- `packagedDependenciesVersion=<version>`

这对后续排障和遥测很重要。

## 11. Python 入口脚本的建议职责

Python 入口脚本建议只处理一个请求，然后退出。

第一阶段不要做常驻 worker。

推荐行为：

1. 读取 request JSON
2. 确认脚本路径位于工作区中
3. 将内置库路径插入 `sys.path`
4. 将工作区根目录插入 `sys.path`
5. 用 `runpy.run_path` 运行脚本
6. 捕获异常并写标准化结果
7. 写入 `result.json`
8. 退出

这样做的好处是：

- 没有 Python worker 生命周期管理问题
- 更接近当前 `python_exec` 一次调用一次结果的语义
- 容易做 deterministic 测试

## 12. 实施阶段建议

### Phase 1：接口解耦

目标：

- 把 `PythonRuntimeAdapter` 从具体类改为接口边界
- 现有实现迁移为 host backend

当前状态（2026-03-16）：

- 已完成
- 已落地 `PythonScriptRuntime`
- 已落地 `HostProcessPythonRuntime`
- 已完成 Android 侧 `pythonRuntimeProvider` 注入入口

产出：

- `PythonScriptRuntime`
- `HostProcessPythonRuntime`
- Android 可注入 runtime 的配置入口

### Phase 2：最小 `p4a` PoC

目标：

- 用 `p4a` 产出最小 runtime 制品
- 只执行一个简单脚本并回传 stdout

当前状态（2026-03-16）：

- Kotlin 侧桥接前置工作已完成
- 已落地 `P4aPythonRuntime`
- 已固定 request/result JSON 文件协议
- 已加入 launcher 抽象与结果轮询
- 已有 JVM 测试覆盖“launcher unavailable”和“result 成功回传”
- 已加入 `tools/android_python_runtime_p4a/` 脚手架与 Python 入口脚本
- 已加入 Android launcher contract 与 `service/main.py` worker 脚手架
- `app` 已支持自动加载 `tools/android_python_runtime_p4a/dist/*.aar`
- Android launcher 已切换到 `p4a service_library` 模型，约定生成类名为 `org.opencray.app.ServiceOpencraypython`
- 已加入仓库根构建脚本 `build-p4a-service-library.sh`
- Python service worker 已支持从 `PYTHON_SERVICE_ARGUMENT` 读取 `runtimeRoot`
- 真正的 `p4a` 打包产物与 Python service 入口仍未接入，因此本阶段尚未验收完成

验收：

- 真机或模拟器上能运行 `print("hello")`
- Kotlin 能得到 `SUCCESS` 和 `stdout`

建议的首条实际构建命令：

```bash
./build-p4a-service-library.sh
```

Windows 日常构建入口：

```powershell
./build-apk.ps1
```

当前 Windows 构建行为：

- `build-apk.ps1` 会先检查 `tools/android_python_runtime_p4a/dist/*.aar`
- 如果 AAR 不存在，会直接退出并提示先去 WSL 运行 `build-p4a-service-library.sh`
- `build-apk.ps1` 不再负责跨 Windows/WSL 自动构建 `p4a`
- `p4a` 构建链单独在 WSL/Linux 内闭环

当前脚本约定：

- `python -m pythonforandroid.toolchain aar`
- `bootstrap=service_library`
- `service=opencraypython:python_runner/p4a_service_main.py`
- 输出 AAR 复制到 `tools/android_python_runtime_p4a/dist/`

建议流程：

1. 在 WSL 里运行 `./build-p4a-service-library.sh`
2. 确认 `tools/android_python_runtime_p4a/dist/*.aar` 已生成
3. 回到 Windows 运行 `./build-apk.ps1`

### Phase 3：接入 `python_exec`

目标：

- `python_exec` 走 Android `P4aPythonRuntime`
- 继续保留现有审批与路径校验

验收：

- agent 在工作区执行脚本成功
- 超时、路径非法、脚本异常都能正确返回

### Phase 4：白名单依赖

目标：

- 增加首批内置依赖
- 建立依赖锁定和版本管理

验收：

- 至少 3 个常用纯 Python 库在真机运行通过

## 13. 测试建议

### 13.1 JVM 测试

保留并扩展以下测试方向：

- `python_exec` 的路径边界校验
- Android runtime 禁用 install 的错误码
- dispatcher 配置注入正确性

### 13.2 Android Instrumentation 测试

至少新增：

- 执行成功用例
- 脚本抛异常用例
- 超时用例
- 非法脚本路径用例

### 13.3 手工验收

至少验证：

- 应用冷启动后第一次执行脚本
- 多次连续执行脚本
- 大量 stdout 输出
- 中文输出编码
- App 退到后台再返回后的执行稳定性

## 14. 风险与应对

### 风险 1：`p4a` service 集成复杂

应对：

- 先做最小 hello-world PoC
- 不在首版中引入后台常驻 Python worker

### 风险 2：包体积明显上升

应对：

- 首版只做 `arm64-v8a`
- 首版依赖只保留最小集合

### 风险 3：依赖升级不稳定

应对：

- 全部固定版本
- 依赖升级与 App 发布绑定

### 风险 4：调试成本高

应对：

- 采用文件式 JSON 协议
- 保留 Python 侧日志文件

## 15. 成功标准

本方案成功的定义不是“Android 获得完整 Python 主机能力”，而是：

- Android 上 `python_exec` 不再依赖外部命令
- agent 能稳定执行工作区中的 Python 脚本
- 结果能被统一映射进 `ExecutionResult`
- 依赖由应用内置并可控升级
- 调试、测试、审计路径清晰

## 16. 官方资料

- `python-for-android` GitHub: https://github.com/kivy/python-for-android
- `python-for-android` Quickstart: https://python-for-android.readthedocs.io/en/develop/quickstart/
- `python-for-android` Services: https://python-for-android.readthedocs.io/en/latest/services.html
- `python-for-android` Build options: https://python-for-android.readthedocs.io/en/latest/buildoptions.html

## 17. 这份方案的核心取舍

这份方案的核心不是“把 Android 变成一台完整 Python 主机”，而是：

- 接受 Android 侧 Python 是受控能力
- 用 `p4a` 尽快补齐脚本执行能力
- 把复杂度控制在“可交付的 runtime 集成”级别

这是当前阶段最现实、最有机会成功的路线。
