# Android `p4a` Python Runtime 包基线

截至 2026-03-19，Android 内嵌 Python runtime 的默认包基线已经调整为：

- 科学计算三层依赖一起内置
- 额外补齐 Office 文档生成能力
- 仍然不支持运行时 `pip install`

实际默认清单由 [requirements.lock](/D:/codes/MobileProjects/OpenCray/tools/android_python_runtime_p4a/requirements.lock) 控制，`build-p4a-service-library.sh` 在未显式传入 `P4A_REQUIREMENTS` 时会自动读取该文件。

## 1. 为什么要这样配

当前目标不是做一个“桌面级 Python 主机”，而是让 agent 在 Android 里具备下面几类稳定能力：

- 运行工作区 Python 脚本
- 做基础数值计算和数据处理
- 做图像处理
- 生成 Word / PowerPoint / Excel 文档
- 覆盖 `claude-scientific-skills` 中一部分高频、轻量、可维护的科学计算场景

这意味着默认基线不能只停留在 `python3 + 少量纯 Python 包`，也不能把所有重科研栈都打进去。

## 2. 默认内置层级

### 2.1 Science Core

这层是“高频、通用、收益大”的基础能力：

- `Pillow`
- `numpy`
- `sympy`
- `requests`
- `networkx`
- `pydicom`
- `simpy`

### 2.2 Science Extended

这层是“明显提升分析能力，但构建和体积成本更高”的扩展层：

- `scipy`
- `matplotlib`
- `lxml`

### 2.3 Science Experimental

这层价值高，但稳定性和维护成本都明显更高：

- `pandas`
- `plotly`
- `seaborn`
- `shapely`

说明：

- `pandas`、`shapely` 在当前本地 `p4a` 版本里依赖链更重。
- `plotly`、`seaborn` 虽然更偏纯 Python，但会继续推高体积和冷启动后首次导入成本。
- 这层已经纳入默认基线，但升级或排障时应优先单独验证。

## 3. Office 文档生成能力

为了让 agent 能直接生成文档，默认基线额外加入：

- `openpyxl`
- `XlsxWriter`
- `python-docx`
- `python-pptx`

这几项之所以可行，是因为：

- `python-docx` / `python-pptx` 的关键底层依赖是 `lxml`
- `python-pptx` 还会依赖 `Pillow`
- 这两条依赖链已经在默认基线中覆盖

## 4. 当前明确不放进默认基线的包

下面这些包即使在 `claude-scientific-skills` 里很常见，也不应放进当前 Android 默认 runtime：

- `scikit-learn`
- `RDKit`
- `Scanpy`
- `scVelo`
- `PyTorch Lightning`
- `OpenMM`
- `MDAnalysis`
- `Qiskit`
- `PennyLane`
- `Astropy`
- `GeoPandas`
- `Vaex`
- `pyOpenMS`

原因：

- 原生扩展依赖链太长
- 部分包默认假设桌面或服务器环境
- Android 端维护成本和失败面过大

## 5. 当前本地 `p4a` recipe 风险提醒

以下是当前仓库本地 `p4a` 安装里确认到的部分 recipe 版本：

- `Pillow`: `8.4.0`
- `numpy`: `1.22.3`
- `sympy`: `1.1.1`
- `scipy`: `1.11.3`
- `matplotlib`: `3.5.2`
- `lxml`: `4.8.0`
- `pandas`: `1.0.3`
- `shapely`: `1.7a1`

结论：

- `Pillow`、`numpy`、`scipy`、`matplotlib`、`lxml` 比较适合作为长期内置能力推进。
- `pandas`、`shapely`、`sympy` 在当前本地 recipe 版本下要额外警惕兼容性和回归风险。

## 6. 构建策略

默认构建：

```bash
./build-p4a-service-library.sh
```

此时脚本会读取 [requirements.lock](/D:/codes/MobileProjects/OpenCray/tools/android_python_runtime_p4a/requirements.lock)。

如果要临时裁剪成更小的 Office + 数值运行时，可以手动覆盖：

```bash
P4A_REQUIREMENTS="python3,Pillow,numpy,lxml,openpyxl,XlsxWriter,python-docx,python-pptx" ./build-p4a-service-library.sh
```

## 7. 建议的验收顺序

先验收 Office 场景，再验收科学扩展场景。

第一批建议直接做的真机脚本：

- 生成一个带图片的 `.docx`
- 生成一个包含图表和图片的 `.pptx`
- 生成一个多 sheet 的 `.xlsx`
- 跑一段 `numpy` 数值脚本
- 跑一段 `matplotlib` 出图脚本
- 跑一段 `pandas` 读写表格脚本

只要这几类通过，Android 端 agent 的“文档生产 + 轻量科学分析”能力就基本成型了。
