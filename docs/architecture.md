# 架构总览

Discord AutoTranslate 是运行在 Discord 进程内的 LSPosed 模块（LibXposed API **102**），
为 Discord Android 官方稳定版提供多语言消息到可配置目标语言的自动翻译。

## 模块入口与生命周期

- 入口：`app/src/main/java/io/github/wxmyyds/discordautotranslate/ModuleEntry.java`
  （`extends io.github.libxposed.api.XposedModule`，必须有公开无参构造）。
- 入口声明在 `app/src/main/resources/META-INF/xposed/`：
  - `java_init.list`：入口类全限定名（每行一个，模块只支持一个 Java 入口）；
  - `module.prop`：`minApiVersion=102` / `targetApiVersion=102` / `autoHotReload=true`；
  - `scope.list`：`com.discord`（作用域）。
- 生命周期回调：`onModuleLoaded`、`onPackageLoaded`、`onPackageReady`、
  `onHotReloading`（旧代码，返回 false 可拒绝重载）、`onHotReloaded`（新代码，
  默认 unhook 旧句柄后必须自行重建状态并重新安装 Hook）。
- Hook 模型是**拦截器链**：`hook(Executable).setId(..).intercept(chain -> ...)`，
  改参用 `chain.proceed(Object[])`，短路则直接返回。
- 配置读写用 remote preferences：目标进程 `getRemotePreferences(group)`，
  设置页进程 `XposedService.getRemotePreferences(group)`，两端读写同一份数据。

## 包结构

```
io.github.wxmyyds.discordautotranslate
  ModuleEntry.java            模块唯一入口：生命周期、Hook 安装、状态重建
  core/                       与 Discord 无关的纯逻辑
    Config / MemoryPrefs / PreferenceCommit / ConfigurationSync
    StateStore / TranslationTaskId / Scheduler / TranslationCache
    ContentTree / TextExtractor / BlankLines / Segments / EnglishDetector
    Http / SseReader / Protocols / JsonPath（有界传输、事件分帧与协议解码）
    Reflect / Sha256 / ModuleResources / TranslationException
  hooks/                      Discord Hook 与渲染替换
    MessageHooks / HookBridge / EmbedHooks
    PollHooks / PollBinding / PollCardState / PollTextProjection / PollIconTouch
    ContentNodes / StructurableTextFactory / RerenderRegistry / RenderDispatcher / RowRefresher
    HookRuntime                  注册完成前的统一业务屏障
    IdentityMessageMap / ReplyTextProjection / HookDiagnostics
    InlineIconInjector / InlineIconSpan / InlineIconGeometry / IconClickSpanFactory
  translate/                  JsonApiEngine / ApiTester（翻译请求与连接测试）
  ui/                         SettingsActivity / XposedServiceConnectionHolder
```

## Hook 体系（受支持的版本配置）

每个 Discord `versionCode` 只加载同名 profile；没有精确匹配的 JSON，或必需目标无法解析时，消息 Hook 不会安装。具体 selector、参数索引和混淆字段见 `app/src/main/assets/hook-config/`。

| `versionCode` | 必需消息 Hook | 可选 Hook | 正文渲染与转换 |
|---|---|---|---|
| `340013` | 消息绑定、正文渲染、文本转换（回复消费者映射未提供） | 富 Embed | `MessageContentView` 实例方法；`TextUtilsKt.toSpannable` |
| `352017` | `MessageView.f` 建立引用作用域并登记预览消费者；回复按消息 ID/编辑版本读取规范正文及已有状态；`embed.bind` 精确匹配 `q9.q.b` 实例方法（34 个 Java 参数，返回 `void`） | Poll 使用精确绑定作用域及原生文本/无障碍 setter；帖子预览未接入 | 正文 `MessageContentView.c`；回复 `af.i` 直接调用 `kl.b.B0`，添加首行 `LeadingMarginSpan` 后交给 Drawee，模块不覆盖 `TextView` |

必需 Hook ID 与数量由 profile 声明；安装前逐项解析目标，注册期间 `HookRuntime` 不激活，拦截器只执行宿主原方法。全部必需 Hook 注册成功后才激活业务并保存成功 profile。失败统一解绑本次句柄，并清理运行配置、宿主构造缓存、状态、调度和渲染代次；不保留半安装业务。


## 状态与并发（防串位 / 防旧结果覆盖）

- 译文与状态全部存 `StateStore`，以 **messageId** 为键，**绝不存在 View 上**
  （RecyclerView 复用视图，存 View 会串位）。
- `view → messageId` 映射存 `HookBridge`；重渲染前用
  `RerenderRegistry.stillBound` 校验“视图当前绑定是否仍是目标消息”。
- 同正文不同对象用 `IdentityMessageMap`（弱引用 + identity hash）反查，避免
  Kotlin data class 按内容 equal 造成两条相同消息互相覆盖。
- `TranslationTaskId` 将消息/段键、内容 hash、generation 和唯一 attempt 贯穿状态、调度与回写；包括被丢弃的回调，也只能迁移自己仍拥有的状态。编辑替换状态对象；同内容重新请求不会复用旧 attempt。
- `StateStore` 强制按 LRU 保留最多 512 条，覆盖所有状态；生产/渲染读取更新活跃顺序，后台任务所有权核对不改变顺序；规范原始内容与观察到的编辑版本保存在对应状态条目的 Source 中，同步失效/淘汰；不另保存无人消费的原文 String 副本。被淘汰任务不复活条目；回复仍只消费保留的规范状态，不增加请求。
- 调度器唯一 FIFO 最多等待 512 项，只把获得执行额度的任务派发到工作线程；并发 1–8，等待不占线程。去重覆盖等待、执行和主线程结果交付；过期等待项可移除，满队列明确报告失败，无备用执行或自动重试。
- `RenderDispatcher` 独立拥有原生重放作用域、主线程队列与代次失效；嵌套重放恢复前一作用域。渲染订阅和原生行布局传播不由翻译协调器执行。HookBridge 不再保存最近 View 反向簿记，producer 只接收消息身份和规范内容，显示端登记仍由 RerenderRegistry 独立拥有。

## 热重载

- `onHotReloaded` 以保存的版本号从新模块 APK 读取当前 profile，只从该 profile 的消息绑定目标取得宿主 ClassLoader，并核对全部必需目标；排除 Android `Application.attach` 的启动类加载器。不保存/复用旧代次 JSON。读取失败或 ClassLoader 缺失时明确报告需要重启，不重新挂载已经执行完的 `Application.attach` 伪装恢复。
- `ModuleResources.reset()` 后重新 `attach(this)`，否则图标资源解析失败。
- `TranslationCache.reset()` 清理旧单例；`Scheduler.shutdown()` 经 `Engine.cancel(Thread)` → 引擎已登记的 `Http.Request.cancel()` 标记取消并请求关闭在途连接；登记前的取消由线程中断阻止后续 I/O。热重载只接受已成功安装的运行代次；仅有 bootstrap 或安装失败时拒绝拆卸并报告需重启。停止先暂停所有生产入口，再确认所有当前/退休调度器线程终止，超时明确拒绝重载；不能把请求停止当作已退出。渲染回调与行刷新都使用可清除的自有 Handler，并按代次失效，不把旧模块回调留在脱离窗口的 View 队列。

## 版本适配

模块读取 Discord `PackageInfo.versionCode`，只加载 `hook-config/<versionCode>.json`。未知版本、JSON 无效或任一必需 Hook 目标无法解析时，不安装消息 Hook；不会退回到其他版本配置。可选 Hook 只有在对应 profile 已核实并启用后才安装。

## 能力范围（按 profile）

- `340013`：保留正文和富 Embed 配置；帖子预览绑定缺少模型参数、Poll 绑定缺少附件参数，其业务体无法执行，已连同配置和相关状态接口删除；旧独立回复 Hook 已移除，当前 profile 未提供统一回复消费者映射，不声称回复支持。
- `352017`：正文翻译与重渲染；回复预览复用引用消息 ID，经 `message.textConversion` 读取其翻译状态（预览不经过正文 `message.render`），不安装独立回复 Hook。普通回复译文复用及用户名避让已由用户设备复测确认；带图片/Embed 的引用投影与规范正文不同导致校验拒绝；`ca9713d` 的复用修正已由用户确认可显示译文，随后观察到空白/错位。`583ffbd` 日志确认原生预览模型已压平换行，但复用的正文译文渲染仍保留换行、提前终止首行。当前在同一个原生转换结果上、原生避让 span 添加之前等长压平行段分隔符；不改正文状态，不新增请求或渲染链，`27451e9` 的布局修正已由用户确认修复带图片引用的空白/错位。富 Embed 已复用现有 `embed.bind` 链路完成 smali 静态适配并启用；`4493cee` 已获用户整体功能正常确认，不推定所有边界样例均覆盖。投票根标题 `ea.r0.v` 从 accessory `h` 读取所有选项并统一登记翻译输入，`ba.h.f` 只登记显示/重渲染、不发请求；两者建立字符串与精确视图的作用域，只在原生 setter 参数处替换。标题单一图标切换整张投票卡片，涵盖所有选项及图片选项的无障碍文本；选项不注入图标或安装独立切换触摸。投票复用现有调度/状态/重渲染，不修改模型或投票结果；帖子预览没有实现。
- 无可用译文或严格回填失败时显示宿主原始节点树，这是状态/失败处理，不是尝试另一套翻译实现。352017 的点击接口已核实；340013 点击配置禁用，本次未设备复测。

## 单链路与清理边界

- 正文 ID 来源由 `message.render.identitySource` 明确指定：352017 只取消息 ID 参数；340013 只取绑定正文视图，不逐级尝试内容或父级映射。
- 富 Embed 输入和宿主均由同一 `embed.bind` profile 显式选择：352017 的 `inputSource=arguments` 只取模型参数 1、消息 ID 参数 3，`host.source=this`；340013 的 `inputSource=accessory` 只取 accessory 参数 1，`host.source=field` 只读 `itemView`。不探测其他输入或宿主。
- 352017 的 `Embed=n7.c`、`EmbedField=n7.o` 通过语义字段访问结构化描述 `j`、标题 `f`、字段 `g`、字段名 `c` 和值 `d`；340013 保留已配置的语义 getter，旧模型类型源码不可用，不新增未经核实的类型别名。旧 `embed*Getter`/`embedHolderViewField` 专用全局配置已移除。
- Embed 每次实际绑定先移除该视图全部旧段登记；空卡片和同一消息的卡片编辑也不能重放旧参数。段状态共享现有 StateStore/Scheduler/缓存/转换器；当前绑定作用域选择一个图标承载段并最多注入一次，不保留跨卡片全局图标键。嵌套渲染退出后恢复之前的宿主作用域。
- Poll 的原始字符串通过 Primitive 输入适配已有翻译链；段状态键为行 ID + poll + question/answer ID + 正文 hash，与正文/Embed 隔离，等标签不同答案 ID 仍是不同段。根标题从 accessory `h` 登记问题和完整选项列表，选项绑定只投影已有状态；不以选项视图出现与否决定请求。`PollCardState` 按行保存唯一整组显示选择，显示端忽略段 Entry 的 `showingOriginal`；当前卡片缺失或段无有效译文时显示原文。
- Poll 重复绑定、标题/选项编辑时替换当前段键集合并保留行选择，旧标题/移除选项不能再控制卡片；完成回调不重置选择。新代次和 `HookBridge.reset` 清空卡片状态。投票选项保存的 adapter position 在重放前核对当前 ID、槽位与文本键，不能因同一行 ID 而套用旧参数。
- Poll 仅当前标题图标控制整组切换/失败段重试，并刷新所有当前段；其状态汇总当前段翻译进度/结果，着色读取卡片选择。标题触摸仅消费图标范围；文本和图片选项均无图标或独立切换触摸，普通投票点击完全交给宿主，图片选项只替换无障碍文本。
- 混淆容器只调用 `nodeConstructors` 指定的精确构造器，参数顺序和字段明确配置；失败不换构造器或改走 copy。未指定构造器的类型使用原 data class copy 规则。
- 点击 span 只有配置构造器/回调一种实现；无 interfaceProxy。
- 回复排障的 trace、逐条空白/布局监听和附件快照已删除；保留已验证的显示投影和必要错误日志。
- `ConfigurationSync` 唯一拥有本地配置写入、首次导入和导出策略；本地写入均经过 `PreferenceCommit`，初始化标记只随成功持久化建立权威。设置读取错误不能填默认值覆盖；失败恢复原快照，回滚也失败时阻止读取/同步。页面只消费操作结果。
- service 连接为进程级唯一监听，页面在 onStart/onStop 独立弱订阅；停止期间完成的持久化延后消费，不覆盖未保存草稿。只有真正未保存的草稿写入实例状态；干净页面重新读取当前权威配置，不恢复旧的干净表单覆盖其他页面已保存内容。
- 第二阶段用 `ContentTree` 统一按出现位置捕获节点、样式与列表边界，提取/计数/对齐/回填消费同一计划。转换必须重新捕获当前输入，哈希校验、回填和图标判断共用该次捕获；不因对象身份相同而复用陈旧计划，同文不同对象的 Embed 也各自重建。计划不持久保存在 Source；Source 仍与状态同生命周期，只保存规范对象与编辑版本。
- 换行对齐使用捕获的源空白 LF、BREAK 和列表项边界；TextContentNode 内的单换行不再因缺少独立 BREAK 节点而丢失边界。多槽连续行按捕获的每槽内部行数精确切分，单一多行槽仍整块回填；不将代码占位空白或不透明内容当作源边界，不根据译文猜测同行槽划分。
- 已确认标题容器的最近 occurrence 切换由同一次 Emitter 归一化为 Event.headingBreakBefore，已有源 LF/列表项 LF 不重复增加；提取和对齐共用一个逻辑块 LF，回填不插入新节点/换行，仍交宿主单一渲染链处理标题间距。该归一化输入也用于内容 hash，避免旧压平输入复用译文；空标题及其他未确认容器不制造边界。
- Hook 安装句柄从创建起登记在 ModuleEntry 的唯一所有权列表；Message/Embed/Poll 不再各自维护回滚列表。HookCleanup 一次尝试全部解绑，仅删除成功句柄，失败句柄保留并阻止安装/热重载成功，要求重启。安装回滚、退出及新代次清理复用同一操作，运行状态清理统一 clearRuntime。
- ContentTree 仅为 SEQUENCE 建不可改 children 快照，容器复用该 captured children；非文本节点共享空文本元数据。只读对齐子区间不复制 flags；去保留标记和请求头解析均按本次请求复用结果。
- 所有 INLINE_CODE/OPAQUE/CODE_BLOCK 保留叶统一使用当前 Event 捕获的无内容 occurrence 锚点 `<keepN/>`，提取/回填共用且不读取内部数据；所有 URL/emoji/retained 锚点统一保存 token 内段边界索引。删除行内代码专用 helper/分支与不透明空格占位；严格要求回带原位置/数量，不以缺标记后的猜测分配替代。列表项起始锚点的原生项目符号归属同样记录在唯一 token 内。
- 非URL链接显示文字通过统一格式范围谓词 isMarkedTextContainer 和既有 `<xN>` 链定位，链接上下文/地址仍沿原版本构造规则保留；URL-only链接仍为原锚点。行数拒绝也使用当前计划的失败run范围输出内容无关结构诊断，不修改对齐规则。
- 结构深度最多 12 个语义容器，Java 列表/列表项包装不增加语义深度；路径最多 64、出现位置最多 8192，循环/超限整体拒绝，不截断翻译。保留原生对象的未改分支校验捕获的列表形状，变化时拒绝整树；不复制提及/emoji/代码等宿主内部模型。
- 请求级取消协调统一在 `Http.Request`：attach 与 cancelled 共用锁，引擎在配置验证/请求体构造前登记请求，finally 才移除。HTTP 只负责有界、严格 UTF-8 传输；每次请求唯一协议 decoder 经 `SseReader` 分帧，以 OpenAI `[DONE]` 或 Anthropic `message_stop` 判定结束，完成事件之后不等待 EOF、不读取后续 keep-alive。缺结束帧明确失败；Responses 保持非流式。
- HookBridge 仍负责业务协调，本次没有重写已确认的回复压平或 Poll 整卡显示策略；`4493cee` 已通过 Actions（511 项测试、0 失败/跳过）并获用户整体功能正常确认；取消竞态、深层节点等边界不能仅由整体反馈推定实机均覆盖。