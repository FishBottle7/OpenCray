# Android `p4a` 上游级根修方案

截至 2026-03-29，OpenCray 的 Android 本地 `python_exec` 已经可以稳定执行脚本，但当前稳定性来自应用层规避，而不是 `p4a` 自身已经修好。

当前线上规避方案是：

- 宿主保留 native lib 提取修复和 payload 自愈
- `P4aPythonRuntime` 不再使用 `once=true`
- 改为 `persistent_stop_after_result`
- 结果落盘后由宿主显式 `stop()`

这套方案能解决用户可见故障，但它不是“上游级根修”。这份文档记录如果后续要把问题修到 `p4a` / bootstrap 层，应该怎么做。

相关文档：

- [docs/android-p4a-python-runtime-plan.md](/D:/codes/MobileProjects/OpenCray/docs/android-p4a-python-runtime-plan.md)
- [docs/android-p4a-python-runtime-package-baseline.md](/D:/codes/MobileProjects/OpenCray/docs/android-p4a-python-runtime-package-baseline.md)
- [novels/嵌入式Python运行时根因与修复记录.md](/D:/codes/MobileProjects/OpenCray/novels/嵌入式Python运行时根因与修复记录.md)

## 1. 问题边界

先把两类问题分开：

1. 启动超时
2. one-shot 退出崩溃

其中第一类问题已经确定是宿主 APK 的 native lib 提取与残缺 payload 状态问题，不属于 `p4a` 启动主循环本身的根修范围。这部分修复即使后续做了上游级退出修复，也仍然需要保留：

- [app/src/main/AndroidManifest.xml](/D:/codes/MobileProjects/OpenCray/app/src/main/AndroidManifest.xml)
- [flutter_app/android/app/build.gradle.kts](/D:/codes/MobileProjects/OpenCray/flutter_app/android/app/build.gradle.kts)
- [app/src/main/kotlin/com/opencray/app/P4aPythonRuntimeLauncher.kt](/D:/codes/MobileProjects/OpenCray/app/src/main/kotlin/com/opencray/app/P4aPythonRuntimeLauncher.kt)

本方案讨论的“上游级根修”，只针对第二类问题：`p4a` one-shot 路径在脚本执行成功后退出时触发 native 崩溃。

## 2. 当前根因定位

当前证据链已经足够明确：

1. Python worker 自身的 one-shot 逻辑是正常返回，不是它主动崩：
   - [python_runner/p4a_service_main.py](/D:/codes/MobileProjects/OpenCray/python_runner/p4a_service_main.py)
   - `main(...)` 在 `if ns.once:` 分支处理完一个请求后直接 `return 0`
2. `p4a` bootstrap 的 `start.c` 在 Python 主逻辑返回后，仍然会走一条 `sys.exit(ret)` 退出路径：
   - [start.c](/D:/codes/MobileProjects/OpenCray/.p4a-build-venv/lib/python3.12/site-packages/pythonforandroid/bootstraps/common/build/jni/application/src/start.c)
   - 其中已有注释写明 “regular shutdown causes issues sometimes”
   - 随后执行 `PyRun_SimpleString("import sys; sys.exit(...)")`
3. 旧真机日志已经抓到这一轮退出阶段的 native 层异常：
   - `FORTIFY: pthread_mutex_lock called on a destroyed mutex`
   - `destroying mutex with owner or contenders`

也就是说：

- 脚本执行成功
- Python worker 返回成功
- 崩在更下层的 `p4a` / native shutdown 路径

这就是为什么当前规避方案不是让 agent 处理，也不是让 Python worker 改逻辑，而是让宿主绕开 `once=true`。

## 3. 上游级根修要解决什么

如果要称为“上游级根修”，至少要同时满足下面几点：

1. 恢复 `once=true` 单次执行模型，不依赖宿主额外 `stop()`
2. `python_exec` 仍然保持现有工具语义，不新增模型侧参数
3. `service_library` 集成方式不被破坏
4. persistent 模式仍可保留，不能因为修 one-shot 把常驻 worker 也打坏
5. 修复点尽量落在 `p4a` bootstrap 或其生成的 service 路径，而不是继续在 OpenCray 宿主层堆补丁

更直白一点，上游级根修的目标不是“现在先跑起来”，而是：

- 让 `p4a` 自己在 service one-shot 场景下能正常退出
- 让 OpenCray 可以把 [P4aPythonRuntime.kt](/D:/codes/MobileProjects/OpenCray/app/src/main/kotlin/com/opencray/app/P4aPythonRuntime.kt) 里目前的宿主 stop 规避逻辑收回去

## 4. 当前应用层规避方案的局限

当前规避方案已经能用，但它有明确代价：

1. 宿主 runtime 必须额外管理 service 生命周期
2. 成功、结果解析失败、结果阶段超时三条分支都要记得 `stop()`
3. metadata 和状态机里出现了 `persistent_stop_after_result` 这类临时模式
4. 本地 `p4a` 后端的行为与远端 sandbox 后端不再完全对称
5. 以后如果换 Android 版本或 p4a 版本，仍然要继续维护这层规避

这些都不是致命问题，但它们说明：当前方案适合交付，不适合长期当“干净底座”。

## 5. 候选修法比较

### 5.1 方案 A：继续保持应用层规避

做法：

- 维持当前 `runOnce = false`
- 维持宿主 `stopService()`

优点：

- 已验证有效
- 对当前发版风险最低

缺点：

- 不是根修
- 宿主复杂度继续存在

结论：

- 适合作为当前稳定基线
- 不属于上游级根修

### 5.2 方案 B：把 `sys.exit(ret)` 直接改成 `Py_FinalizeEx()` 再 return

做法：

- 直接改 `start.c`
- 放弃 `sys.exit(ret)` 路径
- 改用更“正规”的 Python finalize 流程

优点：

- 从形式上更像标准解释器退出

缺点：

- `start.c` 当前注释已经明确说 regular shutdown 以前就有问题
- 这是对 native / Python 生命周期更激进的改动
- 风险比现在的问题还大，容易引入新的 shutdown / relaunch 问题

结论：

- 不推荐作为第一选择

### 5.3 方案 C：在 service one-shot 场景下跳过 `sys.exit(ret)`，直接从 native start 返回

做法：

- 仍然让 Python worker 在 `once=true` 下处理一个请求后 `return 0`
- `start.c` 检测当前是 service 场景时，不再执行 `sys.exit(ret)`
- 直接让 `main(...)` 返回给 Java service 入口

优点：

- 这是最贴近当前故障点的最小修复
- 不需要重新设计 Python worker
- 有机会恢复 OpenCray 原本的一次请求一次执行模型

缺点：

- 需要确认 `service_library` 下 native 返回后，Java service 线程和进程生命周期是否完全符合预期
- 对 `p4a` 其他 service 使用者是否通用，还需要验证

结论：

- 这是最有价值的根修方向

### 5.4 方案 D：给 `p4a` 增加显式退出模式开关

做法：

- 在 bootstrap 层新增退出模式，例如：
  - `sys_exit`
  - `return`
- 默认保持旧行为
- 由宿主或 service 启动参数显式启用 `return`

优点：

- 更容易 upstream
- 不会一刀切改变现有 `p4a` 用户行为

缺点：

- 需要额外把退出模式从宿主传到 bootstrap
- 实现比方案 C 更大

结论：

- 如果目标是向 `p4a` 社区提交补丁，方案 D 比方案 C 更像可合并的版本
- 如果目标只是先在 OpenCray 本地彻底修掉，方案 C 更务实

## 6. 推荐路线

推荐分成两个层次，不要一步到位混做。

### 6.1 第一层：本仓库 carry patch

目标：

- 在 OpenCray 本地 `p4a` 构建链里先把 one-shot crash 根修掉

推荐做法：

1. patch `start.c` 的退出段
2. 当检测到当前是 `service_library` one-shot 请求时，跳过 `sys.exit(ret)`
3. 直接从 native start 返回
4. 重建 AAR
5. 把 OpenCray 宿主重新切回：
   - `runOnce = true`
   - 去掉结果后的自动 `stop()`
6. 重新做真机回归

这一步的目标不是“先给上游发 PR”，而是先证明根修在 OpenCray 场景里成立。

### 6.2 第二层：整理成更可 upstream 的补丁

如果第一层验证通过，再考虑把本地 patch 整理成更容易被上游接受的版本：

1. 不做 OpenCray 专属判断
2. 改成显式的退出模式开关
3. 保持默认行为不变
4. 只让需要 one-shot return 的 service 用户 opt-in

这一步才接近真正意义上的“上游级”提交形态。

## 7. 推荐的本地根修实现

### 7.1 改动原则

本地根修应尽量只碰 bootstrap 退出路径，不去改：

- OpenCray 的 agent 工具语义
- `python_exec` 参数模型
- Python worker 的 request/result JSON 协议

也就是说，理想改动面应集中在：

- `pythonforandroid/bootstraps/common/build/jni/application/src/start.c`

### 7.2 推荐的最小行为变更

在 `start.c` 当前这段逻辑附近：

- 注释说明 regular shutdown 有问题
- 构造 `terminatecmd`
- `PyRun_SimpleString(terminatecmd)`

改成类似下面的行为：

1. 判断当前是否处于 service 请求路径
2. 如果是 one-shot service 返回场景，则直接 `return ret`
3. 否则继续保留原有 `sys.exit(ret)` 路径

这里“当前是否处于 service 请求路径”可以分两种实现：

#### 本地 carry patch 版本

使用已有环境变量或 service 环境特征判断，例如：

- `PYTHON_SERVICE_ARGUMENT`
- `ANDROID_ENTRYPOINT`
- `P4A_BOOTSTRAP`

优点：

- 不用额外改 OpenCray 宿主参数
- 改动最小

缺点：

- 判断逻辑更偏工程化
- 上游接受度未必高

#### 更适合 upstream 的版本

新增一个明确的退出模式控制，例如：

- `P4A_SERVICE_EXIT_BEHAVIOR=return`

或同类显式开关。

优点：

- 语义清楚
- 向后兼容更好

缺点：

- 需要再补一层参数传递链路

## 8. 为什么不建议先改 Python worker

[python_runner/p4a_service_main.py](/D:/codes/MobileProjects/OpenCray/python_runner/p4a_service_main.py#L410) 的 `once` 分支已经是：

1. 处理一个请求
2. 写回 `service-state.json`
3. `return 0`

这说明 Python 层当前已经把 one-shot 的职责完成了。

如果继续在 Python worker 里硬加：

- `os._exit(...)`
- 人工 kill 进程
- 额外 stop self 逻辑

很容易把问题从“bootstrap 退出路径有 bug”变成“多层都在抢进程生命周期”。

所以这里不推荐先改 Python worker。

## 9. 构建链落地建议

当前仓库的 `p4a` 构建入口是：

- [build-p4a-service-library.sh](/D:/codes/MobileProjects/OpenCray/build-p4a-service-library.sh)

建议把上游 patch 作为仓库显式资产维护，而不是靠手工改 venv 里的源码。

推荐做法：

1. 新增目录：
   - `tools/android_python_runtime_p4a/upstream_patches/`
2. 存放 patch 文件，例如：
   - `001-p4a-service-oneshot-return.patch`
3. 在 `build-p4a-service-library.sh` 里增加“安装/定位 pythonforandroid 源码后，先应用 patch，再开始 toolchain build”的步骤
4. 在文档中记录 patch 对应的 `p4a` 基线版本或 commit

这样做的好处是：

- 重建可重复
- 不依赖某台机器上手改过的 venv
- 后续升级 `p4a` 时，patch 冲突点可审计

## 10. 验收标准

只有满足下面这些条件，才算“上游级根修已在 OpenCray 落地成功”：

1. OpenCray 宿主切回 one-shot 模型：
   - `runOnce = true`
   - 不再依赖结果后的宿主 `stop()`
2. 真机连续多次运行 `hello.py` 或等效脚本都成功
3. 每次都能生成 result 文件，且 `status = success`
4. logcat 中不再出现：
   - `FORTIFY`
   - `destroyed mutex`
   - `Fatal signal`
   - `am_crash`
5. service 进程在脚本完成后可正常结束，不残留卡死或僵死状态
6. 再次启动新的 Python 请求时，不出现“上一次退出不干净导致下一次拉不起来”

建议最少做三类验证：

1. 单次执行验证
2. 连续重复执行验证
3. 执行成功后立刻再执行第二个请求的回归验证

## 11. 成功后的宿主清理项

如果上游级根修验证通过，OpenCray 宿主层应当同步回收临时规避逻辑。

重点清理项包括：

1. [app/src/main/kotlin/com/opencray/app/P4aPythonRuntime.kt](/D:/codes/MobileProjects/OpenCray/app/src/main/kotlin/com/opencray/app/P4aPythonRuntime.kt) 中的 `persistent_stop_after_result`
2. 结果成功、解析失败、结果阶段超时后的 `stopService()` 逻辑
3. 与当前临时模式绑定的 metadata 字段
4. 相关单测里对 `runOnce = false` 和 stop 次数的断言

但以下内容即使根修成功也不应删除：

1. native lib 提取修复
2. payload 自愈修复
3. request/result/log/service_state 这些 bridge 诊断能力

因为它们解决的是另一类问题。

## 12. 回滚策略

如果根修分支验证失败，回滚应非常直接：

1. 保留当前应用层稳定方案
2. 不把根修 patch 合入主线
3. 继续使用：
   - `runOnce = false`
   - `persistent_stop_after_result`
   - 宿主 `stopService()`

不要在验证失败时做“半切换”状态，比如：

- `runOnce = true` 但仍然保留部分 stop 逻辑
- 一部分构建使用 patch，一部分构建不使用 patch

这种状态最难排查。

## 13. 当前建议

当前优先级建议如下：

1. 继续以当前应用层规避方案作为稳定基线
2. 如果目标是近期发版，不必立刻做上游级根修
3. 如果目标是把 Android 内嵌 Python 当长期平台能力维护，应尽快做一轮本地 carry patch 验证
4. 只有本地 carry patch 验证成功，再考虑整理成更通用的 upstream 版本

最终判断标准不是“代码看起来更优雅”，而是：

- one-shot 模型是否恢复
- native crash 是否真的消失
- 宿主是否能删掉临时 stop 规避逻辑
