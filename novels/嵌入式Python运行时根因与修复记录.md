# OpenCray 嵌入式 Python 运行时根因与修复记录

## 1. 问题背景

2026-03-28 至 2026-03-29 期间，OpenCray Android 端嵌入式 Python 运行时出现了两层问题：

1. 第一层是启动超时。界面提示 `Timed out waiting for the embedded Python runtime service to become ready.`，并且诊断信息显示：
   - `request` 文件已经写入
   - `result` 文件不存在
   - `log` 文件不存在
   - `service-ready.json` 不存在
   - `service-state.json` 不存在
2. 第二层是启动链路修复后，脚本虽然已经执行成功，但 p4a service 在退出阶段发生 native crash。

这两个问题叠加后，表面现象会让人误以为“脚本根本没有运行起来”。实际上后续定位证明，前后是两个不同层级的问题。

## 2. 第一层根因：不是 p4a 没重建，而是宿主没有把 `libpybundle.so` 解到磁盘

### 2.1 现象

虽然已经重新构建过 p4a，但应用运行后仍然持续超时，且 `python_runtime/service_state/service-ready.json` 始终没有生成。这说明问题发生在 service 进入稳定轮询之前。

进一步排查发现，p4a 依赖的 Python bundle 并没有正确落到应用私有目录下，`/data/user/0/org.opencray.app/files/app/_python_bundle` 缺失或不完整。

### 2.2 根因

根因不在“p4a AAR 里有没有把东西打进去”，而在“Android 安装后的 native lib 是否真的被解到文件系统里”。

p4a 的 `PythonUtil.unpackPyBundle()` 依赖 `nativeLibraryDir` 里存在实际可访问的 `libpybundle.so` 文件，再从该 so 中解出 `_python_bundle`。如果宿主 APK 的打包方式导致 native libs 没有按 p4a 预期被解到磁盘，那么：

1. `libpybundle.so` 虽然可能已经在 APK 里
2. 但运行时拿不到磁盘上的实际文件
3. `_python_bundle` 就不会被解包出来
4. Python 运行时无法完成初始化
5. service 还没来得及写 `service-ready.json` 就已经失败

因此，单纯“重建 p4a”并不能解决这个问题。因为问题点在宿主 APK 的 native library 提取策略，而不是 p4a 产物内容本身。

### 2.3 证据

代码与配置侧的关键证据如下：

- [build.gradle.kts](/D:/codes/MobileProjects/OpenCray/flutter_app/android/app/build.gradle.kts#L18) 增加了 `jniLibs.useLegacyPackaging = true`
- [AndroidManifest.xml](/D:/codes/MobileProjects/OpenCray/app/src/main/AndroidManifest.xml#L16) 增加了 `android:extractNativeLibs="true"`

这两个配置的目的都是确保 p4a 依赖的 native libs 能被按传统方式提取到设备文件系统，满足 p4a 的解包路径假设。

### 2.4 为什么之前会“修一次还不生效”

即使后续配置改对了，设备上如果已经残留一份“版本标记还在，但 payload 没解全”的半残状态，p4a 仍然可能误判为“已经准备好了”，从而不重新解包。

这也是为什么修完配置后，还需要补一层“自愈修复”。

### 2.5 应用侧修复

在 [P4aPythonRuntimeLauncher.kt](/D:/codes/MobileProjects/OpenCray/app/src/main/kotlin/com/opencray/app/P4aPythonRuntimeLauncher.kt#L149) 新增了 `P4aPythonRuntimeExtractedPayloadRepair`，逻辑是：

1. 检查 `files/app/_python_bundle/stdlib.zip` 和 `modules/` 是否存在
2. 检查私有 payload，例如 `p4a_env_vars.txt`、`python_runner/p4a_service_main.py` 是否存在
3. 如果 payload 缺失，则清除 `libpybundle.version` 或 `private.version`
4. 让 p4a 在下一次 `prepare()` 时强制重新解包

这一层解决的是“历史残缺解包状态导致的假阳性准备完成”问题。

### 2.6 真机验证

修复后已在真机 `QCV4MZGYGEQWDURS` 上确认：

- `/data/user/0/org.opencray.app/files/app/_python_bundle/stdlib.zip` 存在
- `/data/user/0/org.opencray.app/files/python_runtime/service_state/service-ready.json` 存在
- 请求结果文件成功生成

其中一次成功执行结果文件为：

- `/data/user/0/org.opencray.app/files/python_runtime/results/debug-python-f1b44973-f840-4173-93ad-76c2b94635af.json`

结果内容显示：

- `status = success`
- `exitCode = 0`
- `stdout` 包含 `hello from embedded Python`

这说明原始“启动超时”问题已经被修复。

## 3. 第二层根因：p4a one-shot 退出路径在 Android 16 / ART 环境下触发 native 崩溃

### 3.1 现象

在第一层问题修复后，脚本已经可以正常执行并生成结果文件，但 service 退出阶段仍会出现 native 层错误。旧日志中出现过：

- `FORTIFY: pthread_mutex_lock called on a destroyed mutex`
- `destroying mutex with owner or contenders`

这说明问题不再是“脚本没运行”，而是“脚本运行完以后，p4a 进程退出方式有问题”。

### 3.2 关键证据

p4a 的 `start.c` 末尾明确走了一条 one-shot 退出路径。文件位置：

- [start.c](/D:/codes/MobileProjects/OpenCray/.p4a-build-venv/lib/python3.12/site-packages/pythonforandroid/bootstraps/common/build/jni/application/src/start.c)

其中包含如下逻辑：

1. 注释直接写明“regular shutdown causes issues sometimes”
2. 随后构造 `import sys; sys.exit(%d)`
3. 最终调用 `PyRun_SimpleString(terminatecmd)`

也就是说，在 `once=true` 的模式下，p4a 本身就在用 `sys.exit(ret)` 作为终止路径。

旧日志里已经抓到对应崩溃证据：

- [tmp_after_manual_run_logcat.txt](/D:/codes/MobileProjects/OpenCray/tmp_after_manual_run_logcat.txt) 中包含 `FORTIFY: pthread_mutex_lock called on a destroyed mutex`

这说明问题大概率落在 p4a one-shot 退出链路与 Android 16 / ART / bionic 的线程或锁析构时序冲突上。

这里的“Android 16 触发”是基于日志和行为的推断，不是已经拿到上游官方结论。

## 4. 第二层修复策略：不再走 one-shot `sys.exit(ret)`，改为 persistent service + host stop

### 4.1 修复思路

当前没有继续直接修改 p4a C 源并重建 AAR，而是优先采用应用侧规避方案：

1. 不再让 p4a 以 `once=true` 跑完即自杀
2. 改为 persistent service 常驻轮询
3. 等 bridge result 文件已经写完后
4. 由宿主显式发起 `stop()`

这样做的核心目标是避开 p4a `sys.exit(ret)` 这条可疑退出路径。

### 4.2 代码改动

在 [P4aPythonRuntime.kt](/D:/codes/MobileProjects/OpenCray/app/src/main/kotlin/com/opencray/app/P4aPythonRuntime.kt#L64) 中：

- `serviceRunMode` 改为 `persistent_stop_after_result`
- [P4aPythonRuntime.kt](/D:/codes/MobileProjects/OpenCray/app/src/main/kotlin/com/opencray/app/P4aPythonRuntime.kt#L102) 中 `runOnce = false`
- [P4aPythonRuntime.kt](/D:/codes/MobileProjects/OpenCray/app/src/main/kotlin/com/opencray/app/P4aPythonRuntime.kt#L265) 在读取结果后调用 `stopService()`
- [P4aPythonRuntime.kt](/D:/codes/MobileProjects/OpenCray/app/src/main/kotlin/com/opencray/app/P4aPythonRuntime.kt#L364) 新增安全 stop 包装，避免 stop 自身异常污染主结果

这个修复是应用层规避，不是上游 p4a 根修。

## 5. 最新验证结论

2026-03-29 00:34 这一轮真机执行已经完成闭环验证：

1. 最新结果文件存在：
   - `/data/user/0/org.opencray.app/files/python_runtime/results/debug-python-f1b44973-f840-4173-93ad-76c2b94635af.json`
2. 结果内容为：
   - `status = success`
   - `exitCode = 0`
   - `stdout` 含 `hello from embedded Python`
3. 对应日志文件存在：
   - `/data/user/0/org.opencray.app/files/python_runtime/logs/debug-python-f1b44973-f840-4173-93ad-76c2b94635af.log`
4. `service-state.json` 记录：
   - `lastProcessedStatus = success`
5. 事件日志显示 service 进程先启动再结束：
   - `03-29 00:34:20 am_proc_start ... org.opencray.app:service_opencraypython`
   - `03-29 00:34:21 am_proc_died ... org.opencray.app:service_opencraypython`
6. 但这一轮没有再抓到：
   - `am_crash`
   - `FORTIFY`
   - `destroyed mutex`
   - `Fatal signal`
   - crash buffer 内容

基于以上证据，可以认为：

- “脚本执行成功但退出时 native crash” 这一用户可见故障，当前已经通过 `persistent service + host stop` 方案规避成功
- 目前看到的 `am_proc_died` 更像是宿主 stop 后的正常进程终止，而不是上一轮那种崩溃退出

这里“更像正常 stop”属于结合结果文件、crash buffer 为空、fatal 关键词缺失后的工程判断。

## 6. 为什么这次不继续直接重打 p4a C 源

原因很简单：

1. 第一层问题的主因不是 p4a 源码，而是宿主 APK 的 native lib 提取策略
2. 第二层问题目前已经用应用侧规避方案消除了用户可见故障
3. 在结果已经稳定成功、且不再出现 crash 签名的前提下，继续 patch p4a `start.c` 并重建 AAR 的收益暂时不高

如果后续又在真机上复现到明确的 native crash，再考虑做下一层处理更稳妥：

1. patch p4a `start.c`
2. 去掉 one-shot `sys.exit(ret)` 退出方式
3. 重新构建 p4a AAR
4. 再做宿主集成回归

## 7. 当前结论

本次问题最终不是单一故障，而是两个根因串联：

1. 启动超时根因：宿主 APK 没把 p4a 依赖的 `libpybundle.so` 以 p4a 需要的方式落到磁盘，导致 `_python_bundle` 无法解包
2. 退出崩溃根因：p4a one-shot 模式的 `sys.exit(ret)` 退出路径在当前 Android 环境下存在 native 崩溃风险

对应修复为：

1. 宿主侧开启 native lib 提取，并加入残缺 payload 自愈
2. 运行模式改为 persistent service，结果落盘后由宿主主动 stop

截至 2026-03-29 00:34 的真机验证结果，两个用户可见问题都已解除。
