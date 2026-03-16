# P4A Python Runtime 调研与可行性评估

截至 2026-03-16，本次结论是：

- 目前 OpenCray 给 agent 调用的 Python 能力在 Android 端还没有完整闭环。
- `python-for-android`（下文简称 `p4a`）可以作为“把 CPython 和固定依赖打进 Android”的方案，但它不适合直接 1:1 替换当前仓库里基于宿主 Python、`venv`、`pip`、`wheelhouse` 的动态运行模型。
- 如果目标是“让 agent 在 Android 里稳定执行受控 Python 脚本，并使用一组预打包依赖”，`p4a` 有中等可行性。
- 如果目标是“让 agent 像桌面环境一样动态创建 venv、运行 `pip install` 任意包、获得近似完整 Python 主机能力”，`p4a` 可行性低，不建议作为主路线。

Android 落地方案见：

- `docs/android-p4a-python-runtime-plan.md`

## 1. 当前 OpenCray 的 Python 能力现状

先说结论：从源码看，当前仓库的 Python 能力更像“桌面/宿主环境下的 runner 方案”，不是已经在 Android 端落地的嵌入式 Python 方案。

### 1.1 Kotlin 侧当前仍然依赖外部 `python`

`runtime/src/main/kotlin/com/opencray/runtime/PythonRuntimeAdapter.kt` 明确写了当前实现是：

- `python -m python_runner.runner exec ...`
- 注释里还直接写了：`On Android, this is expected to be replaced by an embedded interpreter implementation.`

这说明当前 Android 方案本身就是“待替换状态”，不是完整终态。

`runtime/src/main/kotlin/com/opencray/runtime/PipInstaller.kt` 也同样是：

- `python -m python_runner.runner install ...`

也就是说，执行脚本和安装依赖两条链路都还依赖宿主 Python。

### 1.2 Python runner 本身是 workspace 级 `venv` 模型

`python_runner/runner.py` 当前做的是：

- 在工作区创建 `.opencray/python/venv`
- 用 `sys.executable -m venv` 初始化环境
- 用 `ensurepip`
- 用 `pip install --no-index --find-links <wheelhouse>`
- 用 `pip freeze --all` 生成 manifest
- 再用该 venv 里的 Python 执行工作区脚本

这是一套典型的宿主机 Python 子进程模型，不是 Android 内嵌解释器模型。

### 1.3 当前 Android APK 内并没有看到 Python 运行时闭环

这部分是基于源码和工程结构的推断：

- `app/src/main/assets` 里没有打包 `python_runner`
- 工程里没有现成的 Python 二进制、JNI 包装、CMake/NDK Python 运行时接入痕迹
- `docs/termux-phase.md` 又明确写了：V1 不依赖真实 Termux，生产路径仍应是 in-app runtime

把这些合起来看，当前 `python_exec` 在 Android 上更像是接口和契约先行，但嵌入式后端尚未补齐。

所以，回答你的第一个问题：是的，当前软件给 agent 调用的 Python，在 Android 端可以认为“还没有完整功能”。

## 2. P4A 官方资料结论

这里的 `P4A` 指的是 `python-for-android`。

### 2.1 P4A 的本质

根据官方 quickstart 和 services 文档，`p4a` 的核心能力是：

- 把 Python 解释器和指定依赖打包进 Android 产物
- 支持多种 bootstrap
- 其中 `service_library` 可以产出 AAR，供其他 Android 构建系统集成

这意味着它更接近“Android Python runtime packager / build system”，而不是一个现成的、面向现有 Kotlin App 的轻量嵌入 SDK。

### 2.2 对现有 Android 工程最相关的 bootstrap 是 `service_library`

官方 quickstart 提到的 bootstrap 包括：

- `sdl2`
- `webview`
- `service_only`
- `service_library`

对 OpenCray 这种已有原生 Android 宿主的工程，只有 `service_library` 方向现实，因为它可以生成 AAR，而不是重新把整个 App 交给 `p4a` 生成。

官方 services 文档明确写到：

- service library bootstrap 可以生成一个 `.aar`
- 然后从现有 Android 项目调用它
- 会生成对应的 Java service 类，例如 `org.kivy.p4a.ServiceP4a_service`

这说明 `p4a` 的接入方式不是“在现有 Kotlin 代码里直接 new 一个 PythonEngine”，而是“打一个 Python service library，然后由 Android 宿主去启动/停止并自建通信桥”。

### 2.3 P4A 的依赖模型是构建期静态打包

官方 quickstart 里的分发命令使用 `--requirements=...` 在构建期声明依赖。

官方 overview 文档也写得很清楚：

- 纯 Python 包经常可以直接工作
- 含 C 代码的包通常需要 recipe
- 如果没有现成 recipe，开发者要自己写

这和当前 OpenCray `python_runner` 的模型有根本差异：

- 现在的代码是运行时在 workspace 里创建 venv
- 然后运行时装包
- 再运行脚本

而 `p4a` 更像：

- 构建期把 Python 和依赖预烘焙进产物
- 运行时使用这个固定 runtime

因此，`p4a` 不天然支持“agent 临时决定安装任意 Python 包”这种能力。

### 2.4 P4A 的构建链对环境要求高

官方 quickstart 明确要求 Android SDK、NDK、adb、OpenJDK、autoconf、automake、libtool、pkg-config、zlib 等环境，并直接建议 Windows 用户使用 GNU/Linux VM。

这对当前 OpenCray 很关键，因为仓库当前开发环境和脚本主要是标准 Android/Gradle 流程，而不是 Linux-only 的独立 Python 交叉编译流水线。

结论是：

- 如果选 `p4a`，就必须新增一条 Linux 构建链
- 最好在 CI 里生成 AAR，再由 Android 工程消费
- 不适合把 `p4a` 直接揉进当前 Windows 本地日常开发链路

### 2.5 维护状态

截至 2026-03-16，我检索到的 GitHub Releases 页面显示，最新 release/tag 仍是 `v2024.01.21`，发布日期为 2024-01-23。

这说明它不是彻底停更，但也不是一个更新频繁、Android 官方级别稳态生态。对生产项目来说，这意味着：

- 可以用
- 但要把“自维护、踩 recipe 坑、追 Android/NDK 兼容性”的成本算进方案

## 3. P4A 与 OpenCray 现状的适配差距

下面是关键差距，不是实现细节，而是模型差距。

| 维度 | OpenCray 当前模型 | P4A 模型 | 结论 |
| --- | --- | --- | --- |
| Python 启动方式 | `ProcessBuilder("python", "-m", "python_runner.runner", ...)` | 打包好的内嵌 CPython/service | 需要重写适配器，不是小改 |
| 依赖安装 | 运行时 `venv + ensurepip + pip install` | 构建期 `--requirements` 预打包 | 核心不匹配 |
| 工作区脚本执行 | 运行工作区任意脚本 | 可做，但需自建桥接和路径沙箱 | 可做 |
| 动态任意装包 | 当前设计预留了这条路 | 官方模型不擅长 | 低可行性 |
| Native 扩展依赖 | 依赖宿主 Python 生态 | 需要 recipe / 交叉编译 | 成本高 |
| 现有 Gradle 工程集成 | 纯 Kotlin/Android/Flutter 宿主 | 需额外 `service_library` AAR 流程 | 中高成本 |
| 本地开发环境 | 常规 Android/Gradle | Linux-heavy | 需新增 CI/容器链路 |

## 4. 可行性判断

### 4.1 结论先行

如果你问的是“`p4a` 能不能赋予 agent 调用 Python 的能力”，答案是：

- 能，但要收窄能力边界

如果你问的是“`p4a` 能不能补齐当前代码里隐含的完整 Python 宿主能力”，答案是：

- 不能低成本补齐
- 即使强行做，也会偏离 `p4a` 的设计中心

### 4.2 分场景可行性

#### 场景 A：固定依赖、执行工作区脚本

目标定义：

- 预打包一组固定 Python 依赖
- agent 可以运行工作区下的 `.py` 文件
- 不支持任意 `pip install`

可行性：中

原因：

- `service_library` AAR 方向在官方文档里是成立的
- OpenCray 已经有 `PythonRuntimeAdapter` 契约，适合替换后端
- `python_exec` 的审批、审计、工作区路径边界都已经在 Kotlin 层有现成约束

这里真正要补的是“Android 宿主 <-> p4a service”的通信桥，而不是 agent 侧语义。

#### 场景 B：固定依赖、有限制地安装预置包

目标定义：

- 不允许联网装包
- 只允许从预置依赖集中“启用”少量能力

可行性：偏低到中

原因：

- 这已经不是真正的 `pip install`
- 更像“功能开关”或“预编译依赖集合切换”
- 可以在产品语义上伪装成 install，但技术上不再是当前 `PipInstaller` 的实现逻辑

如果坚持这条路，建议改产品语义，不要继续叫 `pip install`。

#### 场景 C：动态 `venv`、动态 `pip install` 任意第三方包

目标定义：

- agent 在设备上像桌面一样装任意包
- 可以持续生成/切换隔离环境

可行性：低

原因：

- `p4a` 的官方主路径不是为这个设计的
- 编译型依赖需要 recipe
- Android 端本地构建 native wheel 的链路极重
- 维护成本、失败面、体积、ABI 问题都会显著上升

这条路如果继续推进，最终很可能会演变成自研 Android Python 发行版，而不是“接入一个库”。

## 5. 如果坚持采用 P4A，推荐的落地方案

这里给的是“能落地且不明显逆着 `p4a` 设计方向”的方案。

### 5.1 方案目标收敛

先把目标收敛成下面这个版本：

- 支持 `python_exec`
- 支持运行 workspace 内的脚本
- 支持固定依赖集合
- 不承诺运行时任意 `pip install`
- 保持现有审批、审计、超时、ExecutionResult 契约不变

如果目标不收敛，后面技术方案会失控。

### 5.2 架构方案

新增一条 `p4a` runtime 产物链：

1. 新建独立的 `python-runtime-p4a/` 构建目录
2. 用 `p4a service_library` 生成 AAR
3. AAR 内包含：
   - CPython runtime
   - 必需的标准库
   - 允许的第三方依赖
   - 一个固定的 Python service entrypoint
4. Android `app/` 通过普通 Gradle 依赖引入该 AAR
5. 在 `runtime/` 新增 `P4aPythonRuntimeAdapter`
6. 用它替换当前 `PythonRuntimeAdapter` 的 Android 实现

### 5.3 Kotlin 与 Python 的桥接方式

推荐桥接方式：

- Kotlin 侧仍然保留 `PythonExecRequest -> ExecutionResult`
- Android 启动 `p4a` 生成的 service
- 通过本地 IPC 发送执行请求

可选 IPC：

- 本地 socket
- 文件队列 + 轮询
- bound service + Binder

在 OpenCray 当前结构里，最务实的是：

- 先做文件队列或本地 socket
- 请求与响应都用 JSON
- 直接复用现有 `ExecutionResult` 字段语义

### 5.4 对当前 `PipInstaller` 的处理

这里必须明确，不建议保持现状语义。

建议二选一：

- 方案 1：在 Android/P4A 后端直接禁用 `install`，返回稳定错误码，例如 `UNSUPPORTED_RUNTIME_INSTALL`
- 方案 2：把 `install` 改成“启用预置依赖能力”，但这需要同步改文案和产品语义

不建议：

- 继续声称支持标准 `pip install`
- 在 `p4a` 上硬凿出通用运行时装包链路

### 5.5 对工作区和安全模型的保留

OpenCray 当前这部分是有价值的，不应该因为换后端丢掉：

- `script_path` 仍然只能指向 workspace
- 审批仍然走 `python_exec` 的现有 gate
- 超时控制仍在 Kotlin 宿主层兜底
- 结果仍统一映射成 `ExecutionResult`
- 元数据继续记录 runtime backend、scriptPath、policyReasonCode

这也是为什么我更建议“替换 runtime backend”，而不是改 agent 语义。

## 6. 分阶段实施建议

### Phase 0：先做技术决策，不写业务代码

确认三件事：

- 是否接受“Android 端不支持任意 `pip install`”
- 是否接受 Linux CI 生成 AAR
- 是否接受 Python 依赖白名单制

如果这三点不能接受，就不该选 `p4a`。

### Phase 1：最小 PoC

目标：

- 仅支持一个简单脚本执行
- 固定依赖只放标准库
- Kotlin 能拿到 stdout/stderr/exit_code

验收标准：

- 在真机或模拟器上执行 workspace 脚本成功
- 超时和失败能稳定回传
- 体积增量可测

### Phase 2：接入 OpenCray runtime 契约

目标：

- 实现 `P4aPythonRuntimeAdapter`
- 接入 `python_exec`
- 接入审计与审批元数据

### Phase 3：处理依赖策略

目标：

- 定义允许打包的 Python 依赖白名单
- 验证至少一个带原生扩展的包是否可稳定 recipe 化

如果这一步持续卡在 recipe/ABI/体积上，就应停止继续投入。

## 7. 主要风险

### 7.1 能力风险

- `p4a` 不能自然承接当前动态 `venv + pip` 模型
- 很容易出现“PoC 可跑，真实 agent 工作流不够用”的落差

### 7.2 构建风险

- 需要 Linux 构建链
- 需要维护 AAR 产物
- 需要处理多 ABI

### 7.3 生态风险

- 第三方包 recipe 覆盖度有限
- 含 native 扩展的依赖最容易成为阻塞点

### 7.4 维护风险

- 一旦接入，就要持续承担 Android/NDK/Python 版本兼容性成本
- 这部分成本不会被现有 Gradle/Kotlin 体系自动吸收

## 8. 最终建议

我的建议是：

- 不要把 `p4a` 当成“补齐完整 Python 主机能力”的方案
- 可以把它当成“给 OpenCray 增加一个受控的内嵌 Python backend”的方案

具体决策建议如下：

### 建议结论

- 如果你的产品目标是“Android 端 agent 能跑一批受控 Python 脚本”，可以做 `p4a PoC`
- 如果你的产品目标是“Android 端 agent 获得接近桌面 Codex 的完整 Python 能力”，不建议以 `p4a` 为主路线

### 我对可行性的评级

- `p4a` 作为固定 runtime 的 `python_exec` 后端：`中`
- `p4a` 作为当前 `python_runner + PipInstaller` 的完整替代：`低`

## 9. 参考资料

### 仓库内源码

- `runtime/src/main/kotlin/com/opencray/runtime/PythonRuntimeAdapter.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/PipInstaller.kt`
- `runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt`
- `python_runner/runner.py`
- `docs/termux-phase.md`

### 外部资料

- python-for-android Quickstart: https://python-for-android.readthedocs.io/en/latest/quickstart/
- python-for-android Services: https://python-for-android.readthedocs.io/en/latest/services/
- python-for-android Distribution tool / requirements 示例: https://python-for-android.readthedocs.io/en/latest/quickstart/#using-our-distribution-tool
- python-for-android GitHub Releases: https://github.com/kivy/python-for-android/releases

## 10. 补一句最直接的话

如果你的真实需求是“让 Android 里的 agent 真正拥有稳定、可维护、可审计的 Python 能力”，`p4a` 不是完全不可用，但它要求你主动放弃“动态完整 Python 主机”这个幻想，转而接受“静态打包、白名单依赖、宿主桥接”的产品边界。
