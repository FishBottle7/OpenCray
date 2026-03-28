# ZIP 系文档解包工具设计

Last updated: 2026-03-28

## 目标

为 OpenCray 增加一组正式的 ZIP 系包查看与选择性解包工具，让 agent 能像桌面端“打开压缩包看内部文件”那样处理 `docx`、`xlsx`、`pptx` 等本质上是 ZIP 容器的文件。

这里的目标不是一次性把所有 Office 内容都语义化读懂，而是先补齐一个可靠、可审计、可被 policy 管控的正式工具面：

1. 读取 ZIP 系文档包的内部条目列表
2. 预览其中可安全预览的 XML / 文本条目
3. 按条目名或 glob 选择性解包到工作区

## 结论先行

本次推荐先实现两项原生工具：

1. `inspect_workspace_package(...)`
2. `extract_workspace_package(...)`

v1 只覆盖 ZIP 系容器：

- `zip`
- `docx`
- `xlsx`
- `pptx`
- `odt`
- `ods`
- `odp`

不覆盖：

- `pdf`
- `doc`
- `xls`
- `ppt`
- 任何需要专有渲染器、扫描器或 OLE/Compound File 解析器的旧格式

## 为什么要这样分层

### 1. `docx/xlsx/pptx` 的本质不是“黑盒文档”

这类文件遵循 Open Packaging Conventions，物理上就是 ZIP 包，逻辑上是：

- 一组 part
- 一组 relationship
- 一组 XML 和二进制资源

也就是说，桌面端很多“解包查看”能力的底层并不是在“读 Word 文档”，而是在“打开一个标准化 ZIP 包并读取其中的 part”。

### 2. “解包”与“语义理解”是两层能力

用户当前要的是类似桌面端的“解开看看里面有什么”。这更接近：

- 包检查
- 包内文件列表
- 包内文件预览
- 选择性导出

这不等于：

- 还原 Word 正文段落结构
- 还原 Excel sheet 单元格模型
- 还原 PowerPoint slide 文本树

后者是下一层的 OOXML / ODF 语义解析，不应和 v1 混在一起。

### 3. PDF 不属于这条路线

`pdf` 不是 ZIP 容器，不能靠解包获得正文。它必须继续走解析器路线，例如当前已经在 runtime 里使用的 PDFBox 搜索与 native input 查看链路。

### 4. 老 Office 不属于这条路线

`doc/xls/ppt` 不是 ZIP，而是 OLE Compound File。它们需要另一套解析器和安全边界，不应该为了“工具名统一”被强塞进 ZIP 解包实现。

## 参考依据

本设计主要对齐以下公开资料：

- Microsoft Open XML SDK 文档说明 Open XML 文件存储在 ZIP archive 中，并由多个 document parts 与 relationships 构成
  - https://learn.microsoft.com/en-us/office/open-xml/about-the-open-xml-sdk
- ECMA-376 明确将 Office Open XML 的 packaging 定义在 Open Packaging Conventions 上
  - https://ecma-international.org/publications-and-standards/standards/ecma-376/
- Microsoft 关于 OPC 的概览说明 package / part / relationship 的基础模型
  - https://learn.microsoft.com/en-us/previous-versions/windows/desktop/opc/open-packaging-conventions-overview

这些资料足以支撑本设计中的关键判断：

- `docx/xlsx/pptx` 可被视为受约束的 ZIP 包
- 真正有用的“解包工具”通常至少需要看 entry、relationship 和主 part
- ZIP 查看和 OOXML 语义提取应拆层实现

## 当前仓库可复用基础

### 1. 已有 ZIP 安全解包模式

[RemoteSkillPackageSupport.kt](D:/codes/MobileProjects/OpenCray/runtime/src/main/kotlin/com/opencray/runtime/skills/RemoteSkillPackageSupport.kt) 已经实现过一套安全 ZIP 展开逻辑，至少覆盖了：

- entry path 归一化
- 防 Zip Slip 路径穿越
- 目录与文件分支处理

这套安全边界可以直接抽象复用到工作区包解包工具。

### 2. 已有正式文档工具链路

当前 runtime 已经有：

- [WorkspaceDocumentSupport.kt](D:/codes/MobileProjects/OpenCray/runtime/src/main/kotlin/com/opencray/runtime/WorkspaceDocumentSupport.kt)
- [AgentTooling.kt](D:/codes/MobileProjects/OpenCray/runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt)

以及已接上的：

- `search_workspace_document`
- `view_workspace_document`

这意味着 ZIP 系包工具不需要自建一套旁路体系，应该直接走现有正式工具分发、policy、prompt、test 链路。

### 3. Python Office 生态已被评估，但不应作为 v1 主方案

[android-p4a-python-runtime-package-baseline.md](D:/codes/MobileProjects/OpenCray/docs/android-p4a-python-runtime-package-baseline.md) 已记录：

- `python-docx`
- `python-pptx`
- `openpyxl`

这说明未来做更深的 Office 语义读取是有可选路径的。

但本设计明确不把 ZIP 解包工具依赖在 Python runtime 上，因为：

- 工具本质是文件包检查，不该先跨进程再解析
- 正式工具应优先使用宿主原生能力
- Python 作为 v1 依赖会显著增加进程、依赖、调试和 policy 复杂度

## 范围

### v1 in scope

- 识别 ZIP 系容器
- 区分 generic ZIP 与常见文档包类型
- 列出包内 entry
- 对小型 XML / 文本 entry 做只读预览
- 按条目或 glob 解包到工作区指定目录
- 对 agent 暴露 package kind、主 part 提示、典型 entry 提示

### v1 out of scope

- 直接把 `docx` 还原成“正文 + 样式 + 批注”的高层结构
- 直接把 `xlsx` 还原成 sheet / row / cell 模型
- 直接把 `pptx` 还原成 slide / note / shape 文本模型
- 解析宏
- 拉取外部资源
- 处理旧 Office 二进制格式
- 处理 PDF

## 用户面向的工具设计

### 1. `inspect_workspace_package(...)`

用途：

- 查看 ZIP 系文件内部结构
- 找到 agent 真正想看的 XML / 媒体 / 关系文件
- 在不落盘解包的前提下做有限预览

建议参数：

- `path`: `string`
  - 工作区相对路径，或批准的只读根内绝对路径
- `glob`: `string?`
  - 仅返回匹配的 entry
- `max_entries`: `number?`
  - 返回的最大条目数
- `preview_entries`: `string[]?`
  - 指定要额外预览内容的 entry 路径
- `preview_chars`: `number?`
  - 单个 preview 的最大字符数
- `include_relationship_hints`: `boolean?`
  - 是否返回 package kind 推断和主 part 提示

建议返回内容：

- package kind
- entry count
- entry list
- 每个 entry 的：
  - path
  - compressed size
  - uncompressed size
  - mime hint
  - isXml / isText / isBinary
- 选中的 preview 条目内容摘要
- 若能识别为 OOXML / ODF，则附带：
  - main part 提示
  - relationship part 提示
  - media part 统计

建议 metadata：

- `packageKind`
- `entryCount`
- `returnedEntryCount`
- `previewCount`
- `requestedGlob`
- `requestedPreviewEntries`
- `resultTruncated`
- `resultLimitKind`

### 2. `extract_workspace_package(...)`

用途：

- 把包中选定条目展开到工作区
- 供 agent 后续再用 `Read` / `Grep` / `Glob` / `view_workspace_image` 处理

建议参数：

- `path`: `string`
  - 源 package 路径
- `destination_dir`: `string`
  - 工作区内目标目录
- `entries`: `string[]?`
  - 显式指定要提取的 entry
- `glob`: `string?`
  - 用 glob 选择要提取的 entry
- `strip_top_level`: `boolean?`
  - 对顶层公共目录做可选裁剪
- `overwrite`: `boolean?`
  - 是否允许覆盖已存在文件

约束：

- `entries` 与 `glob` 至少提供一项；如果都不提供，则显式失败
- 目标目录必须在批准可写工作区内
- 不能把整个 package 默认“全部解压出来”，避免 agent 误触发大规模写入

建议返回内容：

- 解出的文件数
- 目标目录
- 主要输出路径列表
- 是否发生覆盖
- 是否因限制跳过某些 entry

建议 metadata：

- `packageKind`
- `entryCount`
- `extractedCount`
- `destinationDir`
- `requestedEntries`
- `requestedGlob`
- `overwrite`

## 包类型识别

### 1. 先做物理层识别

先判断：

- 扩展名
- 文件头是否为 ZIP magic `PK`

如果不是 ZIP，则直接失败，不做兼容猜测。

### 2. 再做逻辑层识别

对 ZIP 包内部条目做特征判断：

- OOXML Word:
  - `[Content_Types].xml`
  - `_rels/.rels`
  - `word/document.xml`
- OOXML Excel:
  - `xl/workbook.xml`
- OOXML PowerPoint:
  - `ppt/presentation.xml`
- OpenDocument Text:
  - `mimetype = application/vnd.oasis.opendocument.text`
- OpenDocument Spreadsheet:
  - `mimetype = application/vnd.oasis.opendocument.spreadsheet`
- OpenDocument Presentation:
  - `mimetype = application/vnd.oasis.opendocument.presentation`

若都不匹配，则归类为 `zip`。

建议新增枚举：

- `ZIP`
- `DOCX`
- `XLSX`
- `PPTX`
- `ODT`
- `ODS`
- `ODP`

## 为什么桌面端“解包工具”能做到这些

桌面端类似工具通常分三层：

### 1. 容器层

它们首先只是把文件当成 ZIP 包：

- 列 entry
- 读 entry metadata
- 读 entry bytes
- 解指定 entry 到目录

这一层与 Word / Excel / PowerPoint 无关。

### 2. 结构层

对于 `docx/xlsx/pptx`，工具会进一步识别：

- `[Content_Types].xml`
- `.rels`
- 主 part

这样它们就能告诉用户：

- 这是一个 Word 文档包
- 主文档在 `word/document.xml`
- 图片在 `word/media/*`

### 3. 语义层

更高级的工具才会继续解析 XML 语义：

- 段落
- 表格
- shared strings
- worksheet cells
- slides / notes

本设计只做前两层，不在 v1 引入第三层。

## 技术实现建议

### 1. 新增 support 层

建议新增：

- `WorkspacePackageSupport.kt`

其中包含：

- `WorkspacePackageKind`
- `WorkspacePackageEntry`
- `WorkspacePackageInspectionResult`
- `WorkspacePackageInspectionProvider`
- `DefaultWorkspacePackageInspectionProvider`
- `WorkspacePackageExtractionRequest`
- `WorkspacePackageExtractionResult`

### 2. inspection 优先使用 `ZipFile`

`inspect_workspace_package` 需要：

- 随机访问 entry
- 按路径读取指定 preview 条目
- 不落盘直接看内容

所以更适合使用 `java.util.zip.ZipFile`，而不是纯流式 `ZipInputStream`。

这样可以：

- 先遍历目录
- 再按需读取几个 preview 条目
- 避免为了 preview 做整包展开

### 3. extraction 复用现有 ZIP 安全策略

`extract_workspace_package` 可以：

- 继续使用 `ZipFile` 逐 entry 展开
- 或抽象复用当前 `RemoteSkillPackageSupport` 的安全路径校验思路

关键不是 API 形式，而是必须保留这些安全保证：

- 统一 entry path 归一化
- 禁止 `..` 路径穿越
- 输出路径必须 `startsWith(destinationRoot)`
- 明确处理目录 entry

### 4. preview 只支持安全可控类型

建议仅对以下 entry 做文本 preview：

- `.xml`
- `.rels`
- `.txt`
- `.csv`
- `.json`
- `.md`

二进制 entry 只返回 metadata，不直接内联。

### 5. 结果必须受限

需要统一限制：

- 最大 entry 数
- 最大总返回字符数
- 单条 preview 最大字符数
- 最大单 entry 解压字节数
- 最大总解压字节数

这部分必须走现有共享的 result limit metadata，而不是 handler 私有字段。

## 安全要求

### 1. 防 Zip Slip

任意 entry 输出路径都必须：

1. 标准化
2. 基于目标根目录 resolve
3. 再 normalize
4. 校验仍然在目标根目录之下

### 2. 防 zip bomb

至少要限制：

- entry 数上限
- 单 entry 解压大小上限
- 总解压大小上限
- 压缩比异常上限

### 3. 禁止默认全量展开

agent 很容易因为“想看看里面有什么”而误调用全量解包。默认必须要求：

- 指定 `entries`
- 或指定 `glob`

否则直接失败。

### 4. 禁止执行型内容

对以下内容不做任何执行：

- 宏
- 脚本
- 外部链接
- embedded object

工具只负责查看与复制 bytes，不做解释执行。

### 5. XML 解析禁止外部实体

如果后续对 `[Content_Types].xml`、`.rels`、`mimetype` 做更深 XML 解析，必须显式禁用外部实体与 DTD 外部加载，避免 XXE。

## 与现有文档工具的关系

### 1. `view_workspace_document`

继续负责：

- 图片
- PDF 的 native input 查看

不扩展成“所有文档都塞进模型”。

### 2. `search_workspace_document`

继续负责：

- PDF 文本搜索

不让 ZIP 包工具承担 PDF 职责。

### 3. 新的 package 工具

负责：

- ZIP 系容器检查
- 包内文件导出

这样职责边界清楚：

- PDF: 解析器路线
- image: 原生多模态路线
- OOXML / ODF: ZIP 包路线

## Prompt 指导建议

当以下工具可用时，prompt 应明确教 agent：

- 想看 `docx/xlsx/pptx/odt/ods/odp` 内部结构时，优先用 `inspect_workspace_package`
- 想把某几个 XML / media 文件拿出来再读时，使用 `extract_workspace_package`
- 不要因为文件扩展名像文档就直接猜内容
- 只有当确实需要模型直接看图片时，才在提取后转交给现有 `view_workspace_image`

## Policy 与 metadata 设计

### `inspect_workspace_package`

- 归类：`READ_FILE`
- target kind：`FILE`
- workspace relation：沿用现有 readable roots 逻辑

### `extract_workspace_package`

- 归类：`WRITE_FILE`
- target kind：
  - 源：`FILE`
  - 目标：`DIRECTORY`
- 必须通过统一 `ToolPolicyPipeline`
- 返回共享 artifact / result-limit metadata

## 测试计划

### runtime 单测

至少补：

1. ZIP magic 与 package kind 判断
2. `docx/xlsx/pptx/odt/ods/odp` 识别
3. inspection 返回 entry 列表
4. preview 仅对文本 entry 生效
5. extraction 按 entry 提取
6. extraction 按 glob 提取
7. Zip Slip 拦截
8. 超过 entry / size 限制时报错
9. prompt 指导文案出现
10. policy classifier / normalizer 接上

### 集成测试

使用最小样例包验证：

- 一个最小 `docx`
- 一个最小 `xlsx`
- 一个最小 `pptx`
- 一个 `odt`
- 一个恶意 zip slip 样例

## 分阶段实施

### Phase 1

- `inspect_workspace_package`
- `extract_workspace_package`
- ZIP kind 检测
- package kind hints
- prompt / policy / tests 接入

### Phase 2

新增更高层工具，但不修改 v1 工具职责：

- `search_workspace_ooxml_document`
- `read_workspace_spreadsheet_sheet`
- `read_workspace_presentation_slides`

这些属于语义层工具，不应和 ZIP 解包工具耦合在一起。

## 最终建议

OpenCray 不应把 `docx/xlsx/pptx` 的读取问题简单理解成“给模型一个文件看看”。

对 ZIP 系文档，最稳妥的正式能力应是：

1. 先把它当作受约束的 package 来检查
2. 再把感兴趣的 XML / media / metadata 部分选择性导出
3. 后续若确实有稳定需求，再补更高层的 OOXML / ODF 语义工具

这条路线比直接引入 Python Office 解析、兼容层猜测、或把所有文件都塞进模型更可控，也更符合 OpenCray 当前正式工具、policy、测试和审计体系。
