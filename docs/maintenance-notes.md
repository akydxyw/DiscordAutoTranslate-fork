# 维护经验与边界

本页集中记录开发过程中确认过的教训、易踩的坑与当前已知边界，供后续修改参考。

## 版本化 Hook 配置

- Discord Hook 资料位于 `app/src/main/assets/hook-config/<versionCode>.json`；运行时从
  Discord `PackageInfo.versionCode` 精确读取对应文件，不存在匹配文件时不安装消息 Hook。
- `hooks.*.target` 描述类、方法、静态/实例类型、参数数量和可选参数签名；绑定参数索引、
  混淆类型语义名、访问器、字段别名及精确节点构造器参数也应放在该版本 JSON 中。
- `requiredHooks` 只列出该版本已通过 smali 核实且启动必须成功的 Hook；不能用其他版本的选择器作为默认值。352017 的富 Embed 已静态核实 `q9.q.b` 并列为必需 Hook；静态核实不等同于设备回归通过。回复复用统一转换，不安装独立 Hook；帖子预览旧实现已删除；352017 Poll 已重新核实原生绑定与 setter 并接入统一翻译状态，340013 未接入。
- 静态渲染方法将 View 和正文都作为显式参数；对应 profile 要设置 `viewArgument` 和
  `contentArgument`，重渲染登记会以静态调用方式执行它。
- 新增或修改 profile 后，先做 JSON/签名核对；编译与单元测试通过 GitHub Actions 运行，
  不在本地运行 Gradle 或 `javac`。

## 富 Embed 配置核实

- 352017 的 `smali/q9/q.smali:764` 为 `b` 的完整描述符；按 Java 参数计数为 34（`J`/`D` 各一个参数），返回 `void`。目标中的 0–33 每个参数类型都必须精确配置，不能只按数量或部分签名匹配。
- `inputSource=arguments`、`modelArgument=1`、`messageIdArgument=3`、`host.source=this` 与该实例方法对应。描述 `n7.c.j`、标题 `n7.c.f`、字段 `n7.c.g`；结构化字段名/值为 `n7.o.c/d`，不是原始字符串 `a/b`。
- 340013 已按用户提供的该版 smali 核实9参数入口及 accessory 参数1、宿主字段 itemView；真实 Embed/EmbedField 类型已映射。ID getter 必须通过两个 accessory 独立类型访问器映射 getMessageId-3Eiw7ao，不能继续使用未修饰的全局 getMessageId；其他结构化getter与smali吻合。
- 专用全局 `embedHolderViewField` 与 `embed*Getter` 已移除，两版复用同一个配置选择的绑定入口。当前 `4493cee` 已获用户整体功能正常确认；多卡片、全部字段、编辑及回收复用边界未逐项记录，不声称穷尽验证。

## 投票配置核实

- 问题：`ea.r0.v(m9.g0, ea.n, ea.n, k9.l)`；消息 ID 为 accessory `e`，原始问题为 `g.b.a`，问题视图为 holder `u.P.w`，提示标签 `u.P.v` 不处理。根标题从 accessory `h` 读取完整原生选项列表，按答案 ID 与文本包装 Primitive，为问题及全部选项统一登记翻译输入，不等选项视图绑定才发请求。
- 选项：`ba.h.f(k3.k1, int)`；`this.d` 是当前选项列表，消息/答案 ID 为 `n9.a.a`/`n9.a.c.a`，文字为 `n9.a.c.b.a`。文本布局 `q8.f0.v` 调用 `TextView.setText(CharSequence)`；图片布局 `q8.d0.i` 调用 `View.setContentDescription(CharSequence)`。百分比/票数是不同视图，不处理。
- 无 `B0` 转换：只在精确绑定作用域匹配当前 receiver 和捕获的原始字符串，作用域在 finally 恢复。不能修改投票模型制造差分，更不能改答案 ID、图片、投票状态、结果或回调。
- 先解析视图并清理旧登记，再读取新文本/构造 Primitive；重放参数前核对当前槽位/文本键。每次绑定和重放都登记包装内容的身份；只有实际根标题绑定发起统一翻译流程，选项绑定只读状态并登记显示/重渲染，不另发请求。
- 每行仅有一个 `PollCardState` 显示选择和当前段键列表；重复绑定、标题/选项编辑保留选择并删除旧键，段 Entry 的 `showingOriginal` 不控制显示，完成回调不重置选择；新代次与桥接重置清空整组。仅当前标题图标汇总段状态、按卡片选择着色并控制整组切换/失败段重试，刷新全部当前键。选项无图标和独立切换触摸；图片选项无障碍文本也跟随标题，原生投票点击不变。340013 投票仍未接入。
- `PollCardStateTest` 与 `PollTextProjectionTest` 使用现有 StateStore 和假 Primitive 模型，覆盖整组选择、迟到译文、编辑/旧键隔离与代次清理，不代替设备投票/点击/布局回归。构建及单测需要通过 GitHub Actions 执行；`4493cee` 已获用户整体功能正常确认，特殊投票组合仍须针对性验证。

## 架构优化第一阶段

- 任务接口只接受完整 `TranslationTaskId`；旧 ID/hash submit 重载和信号量排队已删除。唯一 FIFO 负责等待、调度和去重；回调离开调度锁执行，不能阻止其他 worker 收尾。
- `StateStore` 负责任务状态迁移和规范 Source 生命周期；512 条上限覆盖全部状态，后台所有权检查不改变 LRU。旧回调即使被拒绝也不能迁移新 attempt。
- 原生重放移到 `RenderDispatcher`，HookBridge 不再创建独立 Handler 或持有 rawContent 平行仓库；行刷新也从自有队列派发，清理时移除旧代次回调。
- `ConfigurationSync` 统一本地事务与首次导入的初始化标记；页面不直接 commit。服务多订阅、未保存草稿恢复与干净表单重读分开。
- `HookRuntime` 在安装验证完成后才激活；暂停阻止 Hook 和点击重试生产。停止未确认线程终止时拒绝热重载；bootstrap-only 阶段也拒绝重载，不另建恢复链。
- 第一阶段未改回复行段压平、富文本对齐及 Poll 模型/触摸语义；后续第二阶段补充如下。编译/测试必须经 GitHub Actions，设备回归需另行确认。

## 架构优化第二阶段

- `ContentTree` 是提取、片段计数、alignment plan 和原生重建的唯一结构定义。按出现位置而非对象身份处理共享节点；语义容器深度 12，列表包装不加深，循环/路径/出现次数超限整体拒绝。诊断消费已捕获计划，不再次遍历宿主树。旧 walker/countTextNodes 与重新发现子节点的路径已删除。
- 每次转换重新捕获当前输入，哈希验证和回填使用同一份计划；不把跨绑定的生产快照当成当前内容。Embed 同文不同对象可共享结果，但必须各自捕获和重建，不能因 Source 最后登记了另一对象而使共享译文 FAILED。原生未改分支保持对象身份，捕获的列表形状变化时拒绝提交；不声称完全克隆宿主可变模型。
- `Http.Request` 协调取消与连接 attach，引擎先登记后构造请求，finally 删除；删除 HTTP 的全局线程→连接表。SSE 由协议 decoder 主导结束，`SseReader` 是协议 decoder 的唯一分帧器，字符串 fixture 仅在 test source set 喂入同一 decoder，删除 HTTP `[DONE]` 与协议独立逐行 parser。
- SSE 必须收到完整空行帧和协议结束事件，完成后不继续读；严格 UTF-8 与累计响应上限拒绝损坏/超量内容，不切换响应模式。非 2xx 保留有界原始响应供错误分类，不输出正文。真实服务协议/断开连接、深层宿主树、正文/回复/Embed/Poll 仍需设备回归。

## 实机确认后的旧代码清理

- `4493cee`（Actions 511 项测试、0 失败/跳过）已由用户确认功能正常；保留 340013 精确 profile，不将受支持版本配置当作旧实现删除。
- 删除 `ContentNodes.replaceText` 旧列表入口与三参数对齐重载；生产只接受已捕获、已校验的 ContentTree。测试在 test source set 建立输入计划，仍覆盖相同严格拒绝和重建逻辑，不将测试辅助入口留在 APK。
- 删除 HookBridge 的 `lastView` 平行反向视图簿记及 producer 的 View 参数；显示登记/刷新仍唯一由 RerenderRegistry → RenderDispatcher 负责。删除旧可缺正文的 reply-state 接口，回复必须核对规范来源及编辑版本，retry 使用同一当前捕获/哈希校验。
- 删除重建计划的重复样式计数器、仅初始化使用的宿主 Class 缓存、无调用 `HookConfiguration.member` 和 340013 的 `viewSource`。成员元数据和该版本 boundView 选择仍由现有精确配置消费，不删仍有反射用途的类型/访问器。
- 删除仅供协议测试的字符串解析入口，测试直接喂入 createDecoder；真实测试请求只经引擎校验一次，离线自检仍独立保留。ApiTester 不再构造设置页从未读取的 message 文本，界面现有成功/失败、耗时与译文显示不变。服务订阅只建立弱分发快照，不再生成随后丢弃的强引用快照。
- StateStore 删除无人消费的原文 String 副本和 obtain 第三个参数；规范源仍保存在同生命周期的 Source 中，请求文本仅在请求任务中保留。Poll 显示原文来自真实绑定输入，不依赖旧 Entry 字段；测试原文由 test-only fixture 保存。
- 删除旧服务单槽时期的无调用提示资源，以及未使用的 BuildConfig 生成和未启用混淆的 ProGuard 配置。原生反射签名、构造器缓存、错误拒绝/事务及取消守卫不能按“旧”字样删除。

## 广告类消息失败排查（尚未确认根因）

- 服务端成功记录不等于客户端拿到完整可用响应。2026-11-15 设备日志的最近重试从 10:13:45.322 到 10:14:15.986，约 30.7 秒，回调为 translatedPresent=false / TranslationException，与默认 30 秒读超时相符，但日志不含异常分类，不能确认与用户所贴消息一一对应，也不能据此认定是超时。
- 回调诊断增加 errorKind 与 causeClass（只枚举及异常类别、不输出异常正文）；原生结构重建拒绝单独记录固定事件。协议结束/截断/类型和防损坏守卫不变，不添加解析备用链路或自动重试。
- 回归区分 HTTP 200 + 部分 SSE 后读超时（TIMEOUT），与 EOF 缺结束事件（PARSE），两者均不接受部分译文；业务回调失败不缓存，原生拒绝只清除匹配当前结果。构建/测试结果必须另按 GitHub Actions 实际运行记账。

- 10:35 后用户将 timeout_ms 保存为 999999999；10:37:13.024 回调为 translatedPresent=true / errorKind=none，10:37:13.030 明确出现原生回填拒绝，因此这次失败发生在译文取得之后，不能再用此前读超时解释。后台无法提供实际译文，下一步在现有回填操作中分类拒绝，不收集文本。
- `ContentNodes.replacePlanned` 是唯一 null-return 边界；其内部分类异常仅持固定枚举与 expected/actual 数量（无正文、译文、URL、emoji alt、ID、hash 或原异常消息）。区分 URL/emoji 次数、锚点顺序、样式开闭/空内容、未分配文本、缺失/多出段落、源边界歧义、行数、空行/列表项、构造及保留结构变化。Factory 构造失败独立固定码；旧配置构造失败日志合并到该边界，不新增替代回填、忽略锚点或纯文本降级。
- 测试仅在 test source set 将内部分类拒绝适配为旧 null 断言，保留既有覆盖；新增分类/隐私和同链接重复、emoji 与样式混合的成功覆盖。源单换行 TEXT 歧义明确标为 source-side，尚不能认定实际广告命中了该原因。该修改是诊断增强，不宣称修复该消息。

## 已捕获源换行的对齐修正

- `ae4506e` Actions 实际 529 项测试、0 失败/跳过；诊断 APK 设备最新 11:02:27、11:02:35、11:03:25 等多次在 translatedPresent=true 后报告 SOURCE_BOUNDARY_AMBIGUOUS，定位为源槽边界不足，不是该次超时或 URL/emoji 数量拒绝。无法读取完整实机树/译文，不能把所有歧义都推定成同一种节点形态。
- 源码确定的缺口是：原 TextContentNode 空白/前导/尾随 LF 已进入请求，却只有独立 BREAK 设置逐行对齐。修正为从当前捕获的源空白 LF 认定行/段落边界；不新增宿主树遍历，不更改文本分块、样式号或锚点策略。
- `ContentTree` 捕获每块内部行数；连续多槽行运行按总行数核对并分配各槽，保留单槽多行和原始首尾空白。删除旧 sep 文本仓库与 BREAK-only 标记，必需行数参数贯穿每个锚点/样式间隔；旧四参数生产重载不保留，单元测试 unit counts 仅在 test source set 构造。
- 独立复核发现代码块人为换行会让源 First\n + CODE + Second 提取成 First\n\nSecond；因此代码块统一只贡献空格占位，不改变源 LF/BREAK，也不把占位空白作为行边界。代码内容、language/inline 属性、原节点及 codeHeavy 计数不变；请求与当前计划保持同一种解释，无第二解析/修复链。
- 代码/不透明模型内部行数不作为边界，仍拒绝真实同行多槽歧义。该修正确认需要 GitHub Actions 及设备复测，不宣称所有实机歧义均已修复。

## 剩余源槽歧义的结构诊断

- `9a9d7c4` Actions 实际 556 项测试、0 失败/跳过，交付后最新 11:30:15 仍为译文取得成功后 SOURCE_BOUNDARY_AMBIGUOUS。日志没有消息身份且当时显示过代码/列表/提及混合内容，不能认定所有记录均对应最初广告，也不能继续只假定为单换行漏识别。
- `ContentTree.Event` 在原有 occurrence emit 中捕获不可变的语义容器路径；Java List 包装不加路径层、不新增事件或宿主 getter 读取。比较路径用捕获 Node 出现位置，不能因宿主复用同一实例而混淆两个容器出现位置。
- 四个对齐区间调用统一经 alignInterval；原有五参数规则不变。只有源歧义异常携带区间内右槽编号，边界处映射为全计划槽号，并附两侧类型、容器语义路径、样式/列表深度、源 LF/内部行数，以及中间空白/代码/不透明/锚点/列表边界数量。拒绝后仍按同一边界返回 null，不另试解析。
- 不记录正文、译文、长度、URL/emoji alt、消息 ID、hash 或宿主对象身份值。不透明间隔类型最多记录四种并明确 GapTypesTruncated，数量不截断；容器路径来自最多12层捕获结构。结构诊断只附当前拒绝事件，无全树 dump、平行缓存或 ThreadLocal。
- 新增结构诊断回归，覆盖区间编号映射、共享容器出现位置、源/译文不泄露、列表/代码间隔和有界类型枚举；这是诊断增强，尚未修复剩余歧义。

## 标题逻辑块边界修正

- `1aecb4a` Actions 实际 565 项测试、0 失败/跳过；设备 12:16:13、12:17:00、12:17:10 相同拒绝形态：leftPath=HeadingContentNode、rightPath=ROOT，两侧单行，间隔所有计数为0。该次 response 成功，不是 API/超时/原生构造失败。不能将此事实泛化为所有历史消息。
- 352017 宿主 `kl.b.f` 对 `j7.q0` 独立处理：`ka.k.j()` 对应 singleLine，紧凑模式为加粗内容后四空格；块模式调用 `z/p` 设置边界/字号及 VerticalSpacerSpan。标题原生结构拥有分隔语义，旧叶事件流只保留了路径诊断，统一输入却把标题和根文本连成同段同行。
- 现将已确认 HeadingContentNode 的最近容器 occurrence 切换记为同次捕获 Event.headingBreakBefore；仅缺真实 LF/list-item LF 时，在下一有效叶前归一化为一个逻辑 LF。空白节点继续原样，空标题不造边界；共享宿主实例的不同捕获位置分别处理。提取与对齐使用同一标记，没有第二 walker/译文分段猜测/降级。重建仍只替换原有文本槽，宿主自身负责显示间距。
- SourceLfBefore 继续仅统计源 LF/list-item 证据；新增 GapHeadingBreaks 独立计数，不把归一化 LF 冒充文本原有换行。LHeading/其他容器不凭名称新增块规则。
- 行数不匹配、普通同行多槽、标记/锚点或构造错误仍整树拒绝。已补标题直接接根文本、双向边界、源 LF 去重、空/共享标题、样式/锚点/列表和内部多行回归；CI 及设备效果另行核实，不能凭源码修正声称实际广告已修好。

## 行内代码两侧精确分界

- `e3506b4` Actions 实际 583 项测试、0 失败/跳过；交付后 12:42–12:43 新进程 diagnostic 显示 ROOT→ROOT 单行槽11/12（另一次13/14）之间仅 GapInlineCode=1、type=j7.t0。此次不是标题边界拒绝；响应成功后为 SOURCE_BOUNDARY_AMBIGUOUS。没有消息身份，不能推定所有记录均属于同条广告，独立 PARSE 请求也不能混同。
- 旧 INLINE_CODE 仅空格进入请求，使两侧译文没有精确回填位置。现同一次 ContentTree.Event 捕获生成无内容 `<keepN/>`，所有 URL/emoji/code 共用 anchor+segIndex token，不再保留 URL/emoji 平行段索引数组及 emoji 类型分支。代码原对象、真实源换行、标题逻辑边界与状态任务所有权不变。
- 保留前缀被源文本使用、标记丢失/复制/乱序/改名/残片、额外标记或样式内残留均拒绝；不猜测、不修复、不补换行。共享代码实例按 occurrence 分别编号；OPAQUE/CODE_BLOCK 仍保留原行为，未因行内代码证据扩大修改。
- 协议在用户提示词后统一追加强制保留说明；引擎与回填共用语法，自然语言字母和长度比例排除标记字符。日志仍仅固定原因码/结构计数，没有代码正文或凭据。此变更的 CI 与实机效果另行核实。

## 链接显示文字范围与行数拒绝

- `bd0377e` Actions 实际 607 项测试、0 失败/跳过；最新进程 13:02:29 response 成功后 LINE_COUNT expected=2 actual=1，缺失败区间信息，不能断言模型漏了哪一行。13:02:37 是另一成功 response 后 ROOT槽12→LinkContentNode槽13 的 SOURCE_BOUNDARY_AMBIGUOUS，两侧单行且无间隔；代码锚点在左槽前，不证明左槽与链接标签之间边界。此日志没有消息身份，不混同两条请求。
- 非 URL 显示的 LinkContentNode 是可翻译容器；现统一 isMarkedTextContainer 包含链接，沿现有 `<xN>` 样式范围链按 occurrence/text chunk 标记其显示文字。直接相邻 ROOT 文本/链接标签分配有精确边界，不凭译文换行猜测。不增加链接专用解析路径，不改 URL-only 链接锚点及 metadata 构造策略。
- LINE_COUNT 在原五参数对齐拒绝点记录 failing block 的源槽范围，经 alignInterval 映射全计划，附捕获的首尾类型/路径、LF/heading/list信息。只补诊断，不放宽数量或把缺行塞入一个节点。协议统一补充原样保留行段规则，自定义提示词也适用。
- 删除旧 isInlineStyleContainer 名称，Event.styled 注释明确为格式/链接标记，并非宿主字体样式。增加非URL链接/混合keep/样式/URL/共享实例回归与内容无关行数诊断；构建及实机结果另行验证。

## 全部保留叶节点位置统一

- `82c55e0` Actions 实际 622 项测试、0 失败/跳过；最新进程13:12:43 successful response 后 ROOT槽14→ROOT槽15，仅 GapOpaque=1 type=j7.h5，SOURCE_BOUNDARY_AMBIGUOUS。13:13:32 NETWORK/SocketException 是独立请求，不归因同一回填。此前链接分界修正不代表整条消息已成功。
- smali/j7/h5 的 toString 常量及 kl.b 的类型分支核实其为 TimestampContentNode；宿主按格式文字添加点击/样式。模块不读取时间戳 getter、不重造模型。此次不新增版本映射或时间戳专用 hook，问题是所有原样保留叶的空格占位丢失位置。
- 现将既有 `<keepN/>` 机制统一用于 INLINE_CODE/OPAQUE/CODE_BLOCK，事件字段 retainedMarker、公共语法 RetainedNodeMarkers、拒绝 RETAINED_MARKER。移除 InlineCodeMarkers/inlineCodeMarker/INLINE_CODE_MARKER 旧接口及空格占位路径；没有新旧标记协议同时运行。计数、源hash、请求/回填共用同次 occurrence，内部模型不读取/序列化。null 不产生标记，URL/实际emoji仍保留锚点规则，代码过滤不变。
- 列表项以保留叶开头时，ITEM_START 记录首锚点项目符号归属，唯一token只消费紧邻首锚点且独占该行前缀的项目符号，不在无文字槽的间隔报UNASSIGNED_TEXT，也不吞掉前一项正文。源LF/标题逻辑边界仍沿当前计划，不补换行。
- 回归覆盖时间戳样未知节点、共享/空/组合保留叶、代码/mention/URL/style/list混排、无getter读取、模型标记损坏和旧空格hash失效；原来缺标记拒绝保留，成功fixture显式回带标记，测试helper不偷偷补标记。CI和设备结果分别验证，不把测试成功当消息成功。

## 340013 退化定位与恢复

- b12de30 已安装到设备（APK SHA核对一致），Discord当前versionCode340013。14:10/14:11多次新进程都成功安装正文三Hook及Embed，但bind idValid=false/contentClass=null，后续identity=false而跳过图标；Embed读取报IllegalStateException。不是LSPosed未启用/目标未命中。
- 错误ID/content getter自初版profile遗留；7748df8公共反射改精确匹配却未更新340013 Kotlin修饰名，是明确退化。正文现在只读getMessageContent，两个Accessory ID采用完整类型映射，删除旧全局getMessageId。
- 四个入口按smali完整签名17/34/29/9参数配置，移除340013 namePrefix/minParameterCount/mostParameters，Embed列入必需Hook。Primitive/Annotated及公开copy策略核对有效，不新增构造兜底。
- fb8b499明确关闭旧版点击；现在复用唯一constructorProxy，选原生10参数synthetic构造器/mask127及float占位，原生默认priority/宽度/underline/长按，参数7保留统一切换回调。352017七参数协议不变。
- 刷新metadata从mViewHolder/notifyItemRangeChanged修正为LayoutParams.d/Observable.d，并映射holder.lastMessageContent清绑定缓存，所有操作仍在RowRefresher。
- 新增实际构造/运行屏障/点击状态切换及类型访问器/绑定缓存/adapter通知回归，模拟类型仅替换不可在Java声明的Kotlin修饰名，不冒充实机证据。新版Actions/设备结果另记，回复/Poll不在本轮扩展范围。

## 冗余与规则审计后的统一清理

- `8f4267b` Actions 已核 636 项、0 失败/跳过；用户确认此前复杂消息及 Markdown Test 3/3 可回填。中间 PARSE/NETWORK 记录无法对应具体消息，不以恢复成功倒推出失败原因。
- 本轮删除 interval 的纯 URL 静默过滤，源 URL 仍由锚点精确消费；新增 URL 段落按严格段数拒绝。修正样式标签干扰语言检测，原因改固定枚举，删除高度/刷新位置及原异常日志、未知异常消息和服务端任意停止理由插值。
- HookCleanup 统一句柄所有权清理：所有安装从创建时登记入口 hooks，不再 Message/Poll 各持局部列表回滚。退出、安装回滚、新代次清理均逐个尝试全部解绑，成功句柄移除、失败保留；解绑失败不再报告热重载成功，也不安装新 hooks，必须重启。不把只停 worker 等同完整退出。
- 删除 PollBinding.sameInput 及仅测试旧比较的断言，PollCardState.canReplay 是现有 key/当前成员校验的唯一操作，生产和重排/编辑/成员失效测试共用；保留 native setter 的精确 receiver 校验。
- 请求头只解析一次后追加认证；非文本节点不创建空 Split/flags/lines，容器共享捕获不可改 children；只读 flags 子区间不复制，长度检查去 keep 结果复用。删除不可达包装检查 null 分支及 requiredHookCount 无用接口。
- 新增清理、语言等价、URL 严格拒绝、协议理由隐私及真实 Poll 重放守卫回归。首轮 b52c73c Actions 实际执行665项、1失败：旧inline-code乱序负例预期PARAGRAPH_MISSING，实际已不删除URL而拒绝ANCHOR_ORDER；仅修正该原因码预期，继续保留整树拒绝断言。
- 热重载profile/loader在同次旧句柄快照中先读取再清理，避免解绑后重读框架列表；worker停止超时将installed清为false并阻止再安装，但继续保留未确认退出的scheduler/hooks。安装失败回滚即使成功，也明确要求重启，不自动重装。本轮最终Actions与实机结果另行验证，不执行本地构建或测试。

## 已确认的架构教训（改代码前先读）

1. **文本转换目标按 profile 变化**：`340013` 使用 `TextUtilsKt.toSpannable`，
   `352017` 使用已核对的 `kl.b.B0` 静态方法。绑定和转换目标由 profile 指定。
2. **扩展 Hook 按版本配置**：回复、Embed、帖子预览与 Poll 必须先核实目标。`352017` 的回复正文由 `af.i` 直接调用 `kl.b.B0`（不是 `MessageContentView.c`），必须复用引用消息 ID 的状态，不另加并行回复渲染链；原生首行避让依赖 `LeadingMarginSpan.Standard(前导区实测宽度, 0)`，不能用普通 `setText` 覆盖渲染结果；引用模型正文可能与完整正文不同，回复必须以消息 ID/编辑版本选取规范正文及已有译文，由同一个转换器生成预览，不按投影文本哈希拒绝规范译文。`583ffbd` 设备日志确认引用模型压平了规范正文换行，故译文必须在规范节点回填和原生转换之后、首行避让添加之前，对同一 Drawee builder 等长压平换行；不能先压平再回填或手动删 margin span。原生 `singleLine` 渲染参数不会清除文本内换行/显式 NewLine；保留 340013 富 Embed；原先缺少绑定参数的帖子预览和 Poll 业务体已清理。352017 Poll 此次核实为 `ea.r0.v` 问题绑定、`ba.h.f` 选项绑定与原生字符串 setter，不恢复旧实现。
3. **替换对象类型**：必须是 `AnnotatedStructurableText` 或
   `PrimitiveStructurableText` 之一，否则渲染器抛 `RuntimeException`；
   Discord 自带的 simpleast Markdown 解析器在 APK 内无调用方，不能复用。
4. **节点重建按 profile 选择**：`340013` 使用 Kotlin data class 的
   `componentN()/copy(...)`；`352017` 混淆节点仅按 `nodeConstructors`
   指定唯一签名、参数顺序和字段重建。不存在 constructorFallbackTypes、字段顺序推断或失败后尝试下一个构造器。
5. **正文存储绝不能依赖 View**：RecyclerView 复用视图，译文/状态一律以
   messageId 为键存 `StateStore`；同正文不同对象需身份映射（`IdentityMessageMap`）。
6. **旧默认值会静默失效**：配置读取是「已存值优先于默认值」。曾出现用户在
   设置页填了新提示词、但手机里存的是旧版默认模板（硬编码提示词、不含
   `{{system}}`），新配置项根本没被发送。教训：新增配置项时应考虑已安装用户
   的旧值，保存时可检查关键占位符并提示。
7. **热重载后不重放包生命周期**：`onHotReloaded` 必须自行重建状态、重装 Hook、
   重新 attach 模块资源、清理缓存；目标方法从旧 Hook 句柄反推。

## 排查与验证要点

- `HookDiagnostics` 使用 `DiscordAutoTranslate` 标签记录安装、状态和异常类别；不输出正文/译文/消息 ID/内容哈希。回复问题修复确认后移除了 trace、空白统计与逐条布局观察，不保留临时排障监听。
- 用户导出的 LSPosed 日志可能**不含 Discord 进程的 logcat**；若要确认上述启动错误，需检查 Discord 进程日志。
- 单元测试无 Robolectric，**不能测 Android 类**；`ContentNodes` 的测试用
  同名假节点类模拟 Kotlin data class，才能真正执行反射代码路径。
- 构建与自动化测试**只能通过 GitHub Actions**（AGENTS.md 强制），
  本地不运行 Gradle / javac / 打包。

## 当前已知边界（设计内行为，不是 bug）

- **失败后不自动重试**：`FAILED` 状态由用户点击图标重试，避免滚动/动画
  触发请求风暴与 API 限流。
- **模型效果不承诺**：提示词约束（只翻译、不接话、不拒译侮辱性内容）只能降低
  扩写概率，不能保证远端模型一定服从；`Dumbass → 混蛋` 这类失败案例曾由
  默认提示词第 8/9 条缓解，仍不承诺彻底消除。
- **流式响应**：支持 SSE 增量（见 translation-engine.md），其余边界以
  `Protocols` 实现为准。
- **富文本无法完整重建时保留原文并标记翻译失败**：无效译文不写入缓存。
- **富文本映射残余风险**：仅验证源结构和译文的标记、片段数量及固定边界；
  若模型在数量不变的情况下改写文本边界，纯文本无法证明语义位置，规则会拒绝无法一一对应的结构。
- **折叠/截断与布局**：宿主折叠截断是否参与布局、Spoiler 隐藏等场景需
  真机样例观察，不修改宿主 smali。
- **跨进程配置生效**：目标进程未注册 remote preferences 变更监听，
  设置页保存后运行中的 Discord 可能需重启才生效（已如实提示）。

## 已归档/移除（勿再引用）

- `Diagnostics` 诊断通道与布局诊断相关的临时代码已删除
  （原 `core/Diagnostics.java`、`logRerender`/`logLayoutFact` 等）；
  当时的交接文档已并入本系列文档，不再单独维护。
- 实验用代码（`InlineIconExperiment` 等）与实验分支已删除；
  正式实现见 `docs/icons-and-layout.md`。
- 阶段 1 桩引擎（`【原型译文】` 前缀）已被真实引擎取代。