# 移动端原生联网搜索路线说明

更新时间：2026-04-04

## 1. 文档目的

本文档只记录当前已经确认的方向，用来约束后续实现讨论，避免把移动端联网搜索继续误解成“必须依赖 provider 原生搜索”或“必须先搭一个 Tavily/Vane 式独立后端”。

本文档不是详细实施计划，也不在这里锁定最终类名、配置结构、UI 形态或搜索源优先级。

配套实施定稿：

- [移动端原生联网搜索实施定稿](./mobile-native-web-search-implementation-plan.md)

## 2. 已确认结论

- OpenCray 的正式移动端方向采用“路线三”思路：搜索工具优先作为宿主内原生能力实现，而不是把 `Vane`、`Morphic` 这类 Web 搜索产品直接部署到手机上。
- OpenCray 的默认移动端落地形态应为“进程内搜索模块”：
  - 不单独启动本地 HTTP 服务器。
  - 不要求 `App -> sidecar` 形式的服务间 HTTP 通信。
  - 搜索连接器直接作为 App 内 Kotlin/Java 组件被调用。
- 搜索能力要继续分层理解：
  - 模型层负责判断何时搜索、如何改写 query、如何整合结果。
  - 工具层负责对模型暴露统一的宿主搜索能力。
  - 检索后端负责真正访问搜索源、抓取页面、抽取正文。
- 不要求主模型必须自带原生搜索。只要模型能稳定使用宿主工具调用，任意文字模型都可以使用宿主联网搜索。
- 不要求搜索能力必须依赖某个 Tavily 式独立后端。移动端可以直接从宿主工具层访问外部搜索源。
- “不依赖独立搜索后端”不等于“完全没有 HTTP”：
  - 路线三消除的是 `App -> 独立搜索服务` 这一层强依赖。
  - 但只要目标是联网搜索，宿主搜索工具仍然可能需要在进程内直接发出 `HTTP/HTTPS` 请求，访问外部搜索 API、网页、或用户自建搜索端点。
  - 因此这里禁止的是“把搜索能力包装成另一个本地服务再用 HTTP 调它”，不是禁止 App 自己进行网络访问。
- `Vane` 和 `Morphic` 当前定位为参考实现，而不是移动端运行时依赖：
  - `Vane` 更适合借鉴其 API 形状、引用输出、搜索模式设计。
  - `Morphic` 更适合借鉴其 provider 抽象、搜索配置和部署拓扑。
- provider-native web search 仍然是可选能力，但它不应成为 OpenCray 搜索架构的唯一入口，也不应替代宿主 `WebSearch`。

## 3. 与当前代码基线的对应关系

当前代码库已经具备一部分“路线三”所需的宿主侧基础，不是从零开始：

- 宿主搜索抽象已经存在：
  - [`runtime/src/main/kotlin/com/opencray/runtime/web/WebSearchProvider.kt`](../runtime/src/main/kotlin/com/opencray/runtime/web/WebSearchProvider.kt)
- 当前宿主搜索 backend 已支持按槽位顺序回退：
  - [`runtime/src/main/kotlin/com/opencray/runtime/web/SequentialWebSearchProvider.kt`](../runtime/src/main/kotlin/com/opencray/runtime/web/SequentialWebSearchProvider.kt)
  - 当前已接入的 provider 有 `exa`、`tavily`、`brave`、`openai_web_search`
- App 侧已经有搜索槽位配置与 provider 工厂：
  - [`app/src/main/kotlin/com/opencray/app/WebSearchSettingsStore.kt`](../app/src/main/kotlin/com/opencray/app/WebSearchSettingsStore.kt)
  - [`app/src/main/kotlin/com/opencray/app/AppConfiguredWebSearchProviderFactory.kt`](../app/src/main/kotlin/com/opencray/app/AppConfiguredWebSearchProviderFactory.kt)
- 宿主页面抓取与正文抽取基础已经存在：
  - [`runtime/src/main/kotlin/com/opencray/runtime/web/WebContentFetcher.kt`](../runtime/src/main/kotlin/com/opencray/runtime/web/WebContentFetcher.kt)
  - [`runtime/src/main/kotlin/com/opencray/runtime/web/HttpUrlWebContentFetcher.kt`](../runtime/src/main/kotlin/com/opencray/runtime/web/HttpUrlWebContentFetcher.kt)
- provider-native web search 的兼容支持也已存在，但它当前应被视为“可选补充”，不是移动端唯一方案：
  - [`runtime/src/main/kotlin/com/opencray/runtime/ProviderNativeWebSearchSupport.kt`](../runtime/src/main/kotlin/com/opencray/runtime/ProviderNativeWebSearchSupport.kt)
  - [`docs/openai-responses-web-search-implementation-plan.md`](./openai-responses-web-search-implementation-plan.md)
  - [`docs/done/openai-compatible-native-tool-search-support.md`](./done/openai-compatible-native-tool-search-support.md)

换句话说，路线三不是推翻现有搜索体系，而是把当前“宿主 `WebSearch` 已存在”的事实进一步收口成正式产品方向。

## 4. 目标边界

路线三下，OpenCray 的目标边界应该是：

- 模型看到的是统一宿主搜索工具，而不是某个特定搜索厂商的私有能力。
- 搜索编排在 OpenCray 宿主内完成，而不是要求手机端再常驻一个 Node/Web 搜索服务。
- 搜索执行对象应以内存内接口和组件注入的方式接入 `AgentTooling`，而不是通过本地回环 HTTP 调用同机服务。
- 搜索结果、抓取结果、引用和错误信息都回到宿主统一结果结构，而不是散落在各 provider 或外部服务自己的协议里。
- 后端搜索源可以替换，但宿主工具协议和 policy 管线不应随之分叉。

可以用下面这条链路理解目标形态：

`主模型 -> 宿主 WebSearch 工具 -> 宿主搜索执行层 -> 外部搜索源/网页 -> 宿主统一结果 -> 主模型`

这里唯一必须稳定的是宿主工具边界。外部搜索源可以是：

- 第三方搜索 API
- provider 官方搜索接口
- 用户自建搜索端点
- 直接网页抓取与抽取

但这些都应被收敛到同一个宿主搜索工具下面，而不是演化成多个彼此重复的模型可见搜索入口。

## 5. 路线三对 Vane 与 Morphic 的利用方式

当前确认的利用方式是“借能力，不部署产品”：

- 不把 `Vane` 直接作为移动端常驻服务部署到手机里。
- 不把 `Morphic` 直接作为 OpenCray 的运行时依赖整套嵌入。
- 不把它们包装成本地 server 再让 App 通过 `localhost` 或其他回环地址访问。
- 只吸收它们已经证明有效的部分设计：
  - 搜索模式设计
  - 引用与来源输出结构
  - 多后端 provider 抽象
  - 搜索与抓取分层
  - 搜索产品常见的请求参数和结果字段

如果未来用户自己部署了 `Vane`、`SearXNG` 或其他服务，这些服务也应只作为“一个可选外部搜索连接器”存在，而不是让 OpenCray 架构默认依赖它们。

## 6. 架构原则

后续实现路线三时，默认遵守下面这些原则：

- 宿主优先：优先增强宿主 `WebSearch`，而不是继续堆叠 provider 特判。
- 进程内优先：搜索连接器默认以 App 内对象形式接入，不新增本地搜索 server 进程。
- 模型无关：宿主搜索能力不绑定某个模型品牌，只要求模型能稳定完成工具调用。
- 后端可替换：可以更换搜索源，但不改变宿主工具协议。
- 引用统一：搜索命中、页面抓取、摘要引用应回到统一宿主结果结构。
- policy 统一：任何跨网络边界的新搜索工具或抓取工具都必须走 `ToolPolicyPipeline`，不能绕开共享审批与元数据路径。
- transcript 统一：搜索与抓取结果仍应作为宿主工具事件进入统一 transcript、resume、audit 和 projection 体系。

## 7. 非目标

截至本文档更新时间，下面这些事情没有被确认为当前方向的一部分：

- 不要求在手机端内运行 `Vane`、`Morphic` 或其他独立 Web 搜索产品。
- 不要求为了搜索先起一个本地 HTTP 服务，再让 App 回环调用它。
- 不要求先做一个 Tavily-compatible 自建后端，才能给任意文字模型提供联网搜索。
- 不要求移除已有的 provider-native web search。
- 不要求立即决定后续第一批新增搜索连接器应该是 `SearXNG`、HTML SERP 抓取、还是别的来源。
- 不要求在本文件里决定搜索结果 rerank、摘要、深度研究模式、JS 渲染页面抓取等细节。
- 不要求现在就改写设置页或最终 UI 交互。

## 8. 当前仍待后续决定的问题

下面这些问题后续还需要单独设计或评估：

- 宿主搜索连接器的正式抽象层应该如何命名和分层。
- 当前“搜索槽位”配置是否继续沿用，还是演化成更通用的“连接器配置”。
- 页面抓取和搜索召回是否保持两个独立工具，还是只对模型暴露一个复合搜索工具。
- 首批新增的非厂商依赖搜索来源应该是什么。
- 对于需要 JavaScript 渲染、反爬或验证码的网页，宿主侧处理边界在哪里。

## 9. 一句话总结

OpenCray 已确认的移动端搜索方向是：保留并增强宿主 `WebSearch`，让搜索能力首先成为 App 内原生工具层能力；外部搜索 API、自建搜索端点、provider 官方搜索都只是可替换的数据来源，而不是移动端必须依赖的独立搜索产品或唯一架构入口。
