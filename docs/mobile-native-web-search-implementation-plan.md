# 移动端原生联网搜索实施定稿

更新时间：2026-04-04

## 1. 文档目的

本文档把移动端联网搜索的正式落地方案定下来，回答下面这些问题：

- OpenCray 是否要把搜索服务直接接进 App 内部
- 是否允许本地 HTTP server / sidecar
- 宿主工具、连接器、抓取器之间如何分层
- 当前已有的 `WebSearch` / `WebFetch` / 搜索槽位代码后续怎么演化
- 已调研的 `Vane` / `Morphic` 应该怎么利用

本文档是当前版本的定稿，不再把方案停留在“路线讨论”层面。

配套方向说明：

- [移动端原生联网搜索路线说明](./mobile-native-web-search-direction.md)

## 2. 最终决策

### 2.1 总体架构

OpenCray 采用“App 内进程原生搜索”方案：

- 搜索能力直接接入 App 和 runtime 进程内。
- 不单独启动本地 HTTP server。
- 不引入默认常驻 sidecar 搜索服务。
- 不把 `Vane`、`Morphic`、`SearXNG` 等完整产品直接嵌成移动端运行时依赖。

允许存在的网络访问只有一类：

- App 进程自己向外部搜索源、网页或用户显式配置的远端搜索端点发起网络请求。

不允许作为默认架构存在的通信方式：

- `App -> localhost/127.0.0.1 -> 本地搜索服务`
- `App -> sidecar 进程 -> HTTP/RPC`

### 2.2 模型可见工具面

当前阶段继续保留下列宿主工具：

- `WebSearch`
- `WebFetch`

原因：

- 这两个工具已经在 runtime 中存在并可工作。
- 它们把“召回搜索结果”和“读取已有 URL 页面”拆成两个明确动作，便于模型和 policy 理解。
- 在移动端原生化阶段，先保留现有模型可见工具面，避免一边换搜索架构一边改模型提示和 transcript 语义。

因此，本轮不做下面这些事情：

- 不把 `WebSearch` 和 `WebFetch` 合并成单一大工具。
- 不新增本地 server 风格的搜索工具。
- 不让模型直接看到 `VaneSearch`、`MorphicSearch`、`SearxngSearch` 这类厂商/实现名工具。

### 2.3 内部分层

宿主对模型暴露的工具面保持不变，但内部架构正式收口为下面四层：

1. 工具层
   - 继续由 `AgentTooling` 调度 `WebSearch` / `WebFetch`
2. 宿主搜索服务层
   - 负责选择搜索连接器、执行回退、统一结果结构、补齐引用和错误语义
3. 连接器层
   - 每个连接器只对接一种外部搜索源或一种远端搜索协议
4. 页面抓取与抽取层
   - 负责 URL 拉取、正文抽取、标题抽取、截断与错误归一

对应到当前代码基线：

- 工具层：
  - [`runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt`](../runtime/src/main/kotlin/com/opencray/runtime/AgentTooling.kt)
- 当前模型可见宿主接口：
  - [`runtime/src/main/kotlin/com/opencray/runtime/web/WebSearchProvider.kt`](../runtime/src/main/kotlin/com/opencray/runtime/web/WebSearchProvider.kt)
  - [`runtime/src/main/kotlin/com/opencray/runtime/web/WebContentFetcher.kt`](../runtime/src/main/kotlin/com/opencray/runtime/web/WebContentFetcher.kt)
- 当前单体实现：
  - [`runtime/src/main/kotlin/com/opencray/runtime/web/SequentialWebSearchProvider.kt`](../runtime/src/main/kotlin/com/opencray/runtime/web/SequentialWebSearchProvider.kt)
  - [`runtime/src/main/kotlin/com/opencray/runtime/web/HttpUrlWebContentFetcher.kt`](../runtime/src/main/kotlin/com/opencray/runtime/web/HttpUrlWebContentFetcher.kt)

### 2.4 正式演化方向

正式决定如下：

- `WebSearchProvider` 保留，继续作为工具层注入点。
- `WebContentFetcher` 保留，继续作为页面抓取接口。
- 当前 `SequentialWebSearchProvider` 不再继续扩大成一个越来越大的 provider 特判文件，而是演化成“宿主搜索服务层”的路由器/编排器。
- provider 级搜索逻辑应逐步从 `SequentialWebSearchProvider` 中拆出，落成独立连接器。

建议的正式内部分层命名如下：

- `WebSearchProvider`
  - 模型工具调用看到的宿主搜索接口
- `SearchConnector`
  - 单一搜索源连接器接口
- `SearchConnectorRegistry`
  - 根据配置构建连接器
- `SequentialSearchRouter`
  - 按槽位顺序执行、失败回退、统一结果结构
- `WebContentFetcher`
  - 页面抓取接口
- `PageContentExtractor`
  - 页面正文抽取与规范化逻辑

这里的命名是本次定稿决定，后续实现应尽量按这个收口，除非出现明显代码上下文冲突。

## 3. 数据流定稿

标准数据流如下：

`模型 -> AgentTooling.WebSearch -> WebSearchProvider -> SequentialSearchRouter -> SearchConnector -> 外部搜索源`

如果模型已经拿到 URL，则走：

`模型 -> AgentTooling.WebFetch -> WebContentFetcher -> 目标网页 -> PageContentExtractor -> 宿主统一结果`

关键点：

- `WebSearch` 和 `WebFetch` 都在进程内通过对象注入直接调用。
- 不引入本地回环地址。
- 网络访问只发生在连接器和抓取器直接访问外部目标时。
- 所有结果统一回到宿主 `AgentToolResult` 与 transcript。

## 4. 配置形态定稿

### 4.1 搜索槽位继续保留

当前已经存在搜索槽位配置：

- [`app/src/main/kotlin/com/opencray/app/WebSearchSettingsStore.kt`](../app/src/main/kotlin/com/opencray/app/WebSearchSettingsStore.kt)
- [`app/src/main/kotlin/com/opencray/app/facade/search/NetworkSearchConfigFacade.kt`](../app/src/main/kotlin/com/opencray/app/facade/search/NetworkSearchConfigFacade.kt)

本次正式决定：

- 继续保留“搜索槽位”这一配置形态。
- 每个槽位代表一个进程内连接器配置，而不是一个本地服务地址。
- 槽位按顺序执行，用于回退和优先级。

### 4.2 搜索槽位的语义调整

虽然继续保留 `slot`，但后续语义正式调整为：

- `slot.providerId`
  - 代表连接器类型，不代表必须走某个远端完整产品
- `slot.baseUrl`
  - 只在该连接器确实需要远端端点时使用
- `slot.model`
  - 只给需要模型型搜索端点的连接器使用
- `slot.apiKey`
  - 对应当前连接器的凭据

因此，后续应把“搜索槽位 = 某个搜索 API 的账户配置”升级成：

- “搜索槽位 = 一个宿主内搜索连接器实例配置”

### 4.3 首批保留的连接器

基于当前代码，首批继续保留下面这些连接器类型：

- `exa`
- `tavily`
- `brave`
- `openai_web_search`

原因不是它们理想，而是：

- 当前代码和测试已经覆盖它们。
- 它们能保证宿主搜索在过渡阶段继续可用。
- 先把架构收口成进程内连接器模型，比立刻替换全部搜索源更重要。

对应测试基线：

- [`runtime/src/test/kotlin/com/opencray/runtime/web/SequentialWebSearchProviderTest.kt`](../runtime/src/test/kotlin/com/opencray/runtime/web/SequentialWebSearchProviderTest.kt)

### 4.4 后续优先新增的连接器类型

后续优先级正式定为：

1. `custom_json_search`
   - 面向用户自建远端搜索端点
2. `searxng`
   - 面向自托管聚合搜索
3. `html_serp`
   - 面向无专用 API 的搜索结果页抓取

这三类连接器都属于“宿主内连接器”，不是本地 server。

## 5. 代码结构定稿

### 5.1 runtime 层

正式决定把搜索核心继续放在 `runtime/.../web/` 下，而不是放到 app UI 层：

- 原因一：`WebSearch` / `WebFetch` 本身是 runtime 工具能力，不是页面功能。
- 原因二：policy、tool metadata、transcript、resume 都在 runtime 层对接更自然。
- 原因三：App 侧更适合承载配置、工厂与 Android 上下文依赖。

因此后续代码归属原则是：

- `runtime/web`
  - 放接口、连接器、路由器、抓取器、解析器
- `app`
  - 放 settings store、connector factory、Android 依赖注入、用户配置映射

### 5.2 App 注入点

当前进程内注入点已经存在：

- [`app/src/main/kotlin/com/opencray/app/AppConfiguredWebSearchProviderFactory.kt`](../app/src/main/kotlin/com/opencray/app/AppConfiguredWebSearchProviderFactory.kt)
- [`app/src/main/kotlin/com/opencray/app/InProcessOpenCrayRuntimeOwner.kt`](../app/src/main/kotlin/com/opencray/app/InProcessOpenCrayRuntimeOwner.kt)
- [`app/src/main/kotlin/com/opencray/app/AppAgentSessionTaskRuntimeFactory.kt`](../app/src/main/kotlin/com/opencray/app/AppAgentSessionTaskRuntimeFactory.kt)

本次决定：

- 继续沿用当前进程内注入路径。
- 不为搜索单独引入新的本地 runtime server。
- 连接器工厂从 App 侧注入到 runtime，保持和当前模式一致。

## 6. Policy 与 transcript 定稿

所有跨网络边界的搜索与抓取能力都继续走宿主统一 pipeline：

- [`runtime/src/main/kotlin/com/opencray/runtime/policy/ToolPolicyPipeline.kt`](../runtime/src/main/kotlin/com/opencray/runtime/policy/ToolPolicyPipeline.kt)

正式决定：

- 新增连接器时，不得在连接器内部私自组装审批或 deny 结果。
- `WebSearch` / `WebFetch` 继续作为模型可见工具进入 transcript。
- 连接器只负责执行外部访问与结果归一，不负责替代工具层 policy。

## 7. provider-native web search 的位置

本次正式决定：

- provider-native web search 保留。
- 但它继续被视为 route-specific 增强能力，而不是移动端搜索架构的中心。
- 宿主 `WebSearch` 仍然是“任意文字模型都能用”的主路径。

也就是说，OpenAI / GLM / Kimi / Anthropic 的 provider-native 搜索可以继续存在，但它们不会改变下面这个事实：

- OpenCray 的默认移动端搜索架构是宿主原生搜索。

## 8. 如何利用 Vane

`Vane` 不作为运行时依赖接入手机，但有三类能力值得明确吸收：

### 8.1 借 API 形状，不借部署形状

可借的东西：

- 搜索模式区分
- citation/source 返回形状
- sources 列表与回答文本的对应关系
- 搜索结果里“回答 + 引用”的宿主输出思路

不借的东西：

- Node/Web 服务部署形态
- 本地 sidecar server 运行方式
- `App -> Vane HTTP API` 作为默认搜索架构

### 8.2 作为未来可选远端连接器参考

如果未来要支持“用户自己部署 Vane，再接入 OpenCray”，正确落地方式是：

- 新增一个可选连接器，例如 `custom_json_search` 或 `vane_remote`
- 它只是众多 `SearchConnector` 之一
- 不改变宿主搜索总架构

### 8.3 对当前实现最有价值的部分

`Vane` 当前最值得借的是：

- 请求参数设计
- 回答与 sources 的归一方式
- 搜索模式命名
- 输出里 citation 的组织方法

## 9. 如何利用 Morphic

`Morphic` 同样不作为移动端运行时依赖，但它对 OpenCray 的价值比 `Vane` 更偏“内部架构设计参考”。

### 9.1 借 provider 抽象

最值得借的是：

- 多搜索后端 provider registry
- 多 provider 配置化
- 对 `SearXNG / Brave / Exa / Firecrawl` 这类后端的统一抽象思路

这正好对应 OpenCray 里应新增的：

- `SearchConnector`
- `SearchConnectorRegistry`
- `SequentialSearchRouter`

### 9.2 借搜索产品参数模型

可借的东西：

- 搜索源可切换
- 搜索深度/数量控制
- 搜索配置集中化

不借的东西：

- 完整 Web app 产品壳
- 数据库、分享、登录、历史等和移动端原生搜索无关的部分

### 9.3 对当前实现最有价值的部分

`Morphic` 当前最值得借的是：

- provider registry 思路
- 配置组织方式
- “把外部搜索源做成可替换连接器”的做法

## 10. 明确拒绝的方案

本次定稿后，下面这些方案被明确排除为默认实现路径：

- 在手机上直接跑 `Vane`
- 在手机上直接跑 `Morphic`
- 为搜索单独起一个本地 HTTP server
- 让 App 通过 `localhost` 或回环地址和本地搜索服务通信
- 把移动端正式架构建立在某个 provider-native 搜索能力之上
- 让模型直接看到多个实现级搜索工具名

## 11. 后续实现顺序

正式实施顺序定为：

1. 保持 `WebSearch` / `WebFetch` 工具面不变
2. 从 `SequentialWebSearchProvider` 中拆出 provider 级连接器
3. 把 `SequentialWebSearchProvider` 收口成 `SequentialSearchRouter`
4. 在 App 侧把搜索槽位解释成连接器配置
5. 补 `custom_json_search` / `searxng` 这类新连接器
6. 再评估是否需要更强的页面抽取器或 HTML SERP 连接器

## 12. 一句话结论

OpenCray 的移动端联网搜索正式定为“进程内原生连接器架构”：保留宿主 `WebSearch` / `WebFetch`，内部通过 `SearchConnector` 体系直接访问外部搜索源和网页，不启本地 server，不依赖 sidecar；`Vane` 用来借搜索模式和引用输出，`Morphic` 用来借 provider registry 和连接器化设计。
