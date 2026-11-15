# 渲染与回填（ContentNodes）

翻译替换发生在 profile 选择的 `message.textConversion` 方法。具体目标由 Discord `versionCode` 对应的 Hook 配置指定；富文本替换始终保留 `AnnotatedStructurableText` 及其结构节点，不将失败的富文本改写成纯文本。

## 请求文本

`TextExtractor` 只提交可翻译的 `TextContentNode` 内容。代码、提及、频道等保留叶由原节点绘制，统一贡献 `<keepN/>` occurrence 标记，不读取其内容或人为插入换行；URL 与识别出的 emoji 作为定位标记进入请求。行内样式文本使用 `<xN>...</xN>` 标记,模型必须按原序保留。

列表项以 `• ` 标记进入请求。回填时只对列表项移除模型回显的项目符号，实际符号仍由原列表节点绘制。

## 唯一对齐规则

`ContentTree` 按节点出现位置捕获原树，预计算文本分块及每块内部行数，并输出统一样式/列表事件。提取、片段计数、对齐和回填只消费这一结构定义；同一实例在两个位置分别捕获/消费。转换先重新捕获当前输入，哈希校验与回填共用它，不能用旧生产计划跳过可变正文校验。URL、emoji 和行内代码 occurrence 锚点必须按原数量和顺序出现；样式标记必须完整匹配。缺失、重复、乱序或损坏时，整条回填失败，不忽略或剥除锚点后重新尝试。

语义容器最大深度 12；Java 列表和列表项包装不增加语义深度。路径最多 64、出现位置最多 8192，循环、非法容器或超限整体拒绝，不返回部分提取。捕获不深复制提及、emoji、代码或链接模型；未改分支复用原对象，但捕获的列表形状发生变化时拒绝整树，不混合新的列表成员与旧对齐计划。

非 URL 链接的显示文字与格式范围共用 `<xN>...</xN>`，按同一次捕获的文本块编号；不是把显示文字当 URL 原样保留，也不新增嵌套容器标签。仅 URL 显示的链接仍按 URL 锚点处理。链接地址、上下文、样式及宿主回调沿原构造策略保留。

每段只按原结构选择一种映射规则：

- 列表项及已知源换行分隔的片段：按捕获的各片段内部行数分配，译文总行数必须匹配；一个多行文本槽不会被误当成一行。
- 源换行包括独立 BREAK 节点和 TextContentNode 的空白、前导/尾随空白中的真实 LF；一个 LF 为行边界，两个及以上按原段落分组。代码占位空白、URL/emoji 字符或不透明节点内部内容不是边界证据。
- 已确认 HeadingContentNode 的逻辑块边界由同一次 ContentTree Emitter 捕获为 Event.headingBreakBefore：相邻有效叶节点的最近标题出现位置发生切换，且任一侧属于标题时补一个请求 LF。已有前导/尾随/间隔真实 LF 或列表 ITEM_START 不重复补；空标题和标题内部普通容器切换不制造边界。提取和对齐消费同一个标记，不向宿主树插入换行，不改变原生标题等级、样式或布局。此 LF 是统一翻译输入的逻辑分隔，不声称逐字符复制宿主显示间距：原生 singleLine 用四空格，块显示可用 VerticalSpacerSpan。LHeading 未扩展。
- URL 已在树序 token 中精确消费，译文剩余 interval 不再过滤纯 URL 段落；额外 URL 段落必须参与段数校验并拒绝，不能静默删除来接受损坏输出。
- 每个非空的 INLINE_CODE、OPAQUE、CODE_BLOCK 保留叶节点 occurrence 在同一次 Event 捕获中获得 `<keepN/>`，请求发送无内容标记，删除旧空格占位。所有保留叶共用 RetainedNodeMarkers 和 PlanToken.anchor/segIndex，不设代码或时间戳专用分支；标记间隔只分配所属文本槽，宿主对象原样复用。标记缺失/重复/乱序/格式损坏或移入样式文本时明确拒绝；不补换行、不读取/发送代码、提及名称、时间戳或未知节点内部数据。null 出现位置不生成标记；URL/真实 emoji 锚点保持原规则。源正文与保留标记命名空间冲突时拒绝，包括跨相邻文本/样式节点拼出的保留前缀。列表项以保留叶开头时由同次 ITEM_START 捕获其首锚点的项目符号归属，只消费紧邻该锚点的原生项目符号，不吞正文。
- 其他文本片段：段落块数必须与片段数及原段落组一一对应；同段同行的多个文本槽没有精确边界时仍拒绝，不按译文猜测分配。单一多行槽仍整块回填。

数量或结构不匹配时返回失败；不会把多段译文合并到首个片段，也不会逐级尝试行、段落等其它解释。样式标记缺失时同样失败。

## 失败处理

只有全部文本片段成功对齐、节点完整重建后才应用替换。任一对齐或节点构造失败，宿主继续显示原始节点树，翻译状态更新为失败，错误译文不进入缓存；用户可通过图标手动重新发起请求。

尾部空白节点及显式换行保持原样，不再运行可选修剪或备用复制路径。源槽边界歧义时日志仅附捕获的两侧节点/语义容器路径、换行与间隔种类数量；行数拒绝附当前区间及失败行 run 的首尾源槽结构，区分原 LF 与标题逻辑边界；不输出文本、锚点值或消息身份，也不因诊断而改变分配规则。

## 340013 正文与 Embed 元数据修正

- 唯一正文绑定使用外层 `MessageView.setMessage(Message,MessageContext,ChatEventHandler,ComponentProvider,Function0,boolean,boolean)`；读取 Message 的完整ID getter、正文及编辑版本，引用scope不生产状态。正文render/转换均只取原生ID参数2，不再使用holder bind及boundView。
- Embed accessory 通过其独立类型映射读取同样修饰的 ID；描述/标题/字段仍是原生 StructurableText，复用统一捕获、回填和转换。
- 340013 公开节点的 copy/componentN 及 Primitive(String)/Annotated(List) 构造器已与 smali 核对，保留该版本唯一重建策略，不套用352017构造器。
- 点击和行高刷新修正版 `244306a` 已获用户确认正常；引用预览复用同一规范 Source/revision/hash及showingOriginal，登记 `replyPreview.binding.replyText` 后重放完整外层绑定。native同步回调经toSpannable→BackgroundSpanDrawer→ReplyUtilsKt.createReplyContent（同builder添加首行margin）→Drawee，不修改用户名/裁剪/布局。已应用规范译文才在转换后等长压平，系统正文不套译文，`5f1d1fb` 已获用户确认引用跟随修复。

## 340013 投票原生投影

- 问题入口唯一 `PollViewHolder.bind(PollMessageAccessory,Function3,Function3,Function8)`；原生同步调用 `BasePollWithRecyclerView.bind`，在答案列表 diff 前统一登记 accessory.answers 中所有答案。标题 `pollView.binding.questionText` 的 String setter 由现有 Poll Scope 精确匹配。
- 答案入口唯一 typed `PollAnswerAdapter.onBindViewHolder(PollAnswerViewHolder,int)`，不同时挂 bridge；保存完整原生绑定参数，重放仍核当前messageId/answerId/文本键及成员。文本布局 label 的 setText 和图片布局 image 的 setContentDescription 均在原生holder.bind内同步调用。
- 图片描述 setter 在 setMedia 的缓存早退之前，不需要清媒体缓存。只改这两个 setter 的文本参数，不修改模型、选项点击 root、图片点击/长按、票数/百分比、选择状态或回调。不新增 Java 分支、解析链或图片可见字幕；c4b70af 已获用户确认投票翻译正常，未逐项覆盖的点击/复用边界仍须专项设备复测。

## 回复预览（352017)

- `MessageView.f` 的引用模型 `c8.c` 携带被引用 `Message` 和可空的系统正文；其 `af.i` 回调直接调用 `kl.b.B0`，不经过正文的 `MessageContentView.c` 入口。
- 绑定前登记引用正文对象和消息 ID，并记录当前绑定的引用作用域。只有转换入参同时匹配引用正文对象与引用消息 ID 时，才作为回复消费者：按引用 ID/编辑时间选取规范正文快照，再以规范正文/配置哈希校验已有状态，将规范原树与已有译文交给同一个 `kl.b.B0`。预览正文是独立投影，不要求它与规范正文逐字一致，也不把整篇译文回填到投影树。绑定后登记该 ID 的重渲染消费者；预览不创建状态、不查可见文字缓存、不发请求。
- 引用译文完成规范节点对齐并由 `B0` 渲染后，在原生回调添加避让之前，将返回的同一个 Drawee `Editable` 内 LF/CR/Unicode 行段分隔符逐字符替换为空格。仅作用于已确认引用作用域且实际应用了译文的结果，不修改原正文、状态、译文缓存或系统正文。不得先压平译文再回填节点（会破坏逐行对齐）；不裁剪、不 trim、不合并横向空白、不复制 builder。
- 原生回调在转换结果上添加 `LeadingMarginSpan.Standard(前导区实测宽度, 0)`，再交给 `setDraweeSpanStringBuilder`。用户名与正文视图矩形相交属于设计允许的情况，是否避让必须看首行 span 和文字布局，不能仅看视图矩形。
- 模块不得在回调之后用普通 `TextView.setText` 覆盖结果，否则会丢失首行避让和 Drawee 富文本。预览布局及 `q()` 测量继续由 Discord 自己执行。
- `getContent()` 与 `getContent$annotations()` 是不同方法；反射成员必须精确匹配，不能把元数据方法返回的 null 当成“模型正文缺失”。
- 普通回复译文复用和用户名避让已由用户设备复测确认。带图片/Embed 的复现日志（诊断版 `4924fc8`）显示规范状态为 `TRANSLATED`，但引用正文与规范正文不同，旧投影哈希校验拒绝复用；两组长度分别同为 247 和 237。`ca9713d` 改为使用规范正文和译文后，用户确认附件引用可显示译文，但观察到错位/大量空白。`583ffbd` 在 19:31–19:32 的日志确认这两组规范正文分别有 2/6 个换行，而引用投影均为 0；排除空白后的文字相同。237 字符样例译文渲染有 6 个换行、仅 1 个横向空白，首行在第 18 个字符遇换行终止，造成留白；不是大量空格。`B0` 的原生 `singleLine` 参数已启用，却仍保留 NewLine 和文本内换行；已检查的原生代码未找到可复用的压平接口。`27451e9` 显示投影修正已由用户确认修复，其他样例仍不能按这一现象推定。
- 规范正文快照记录该消息自身绑定时的 `getEditedTimestamp()`。引用模型、最新绑定版本及快照版本必须一致，规范正文哈希也必须匹配状态；不匹配时拒绝旧译文。读取 getter 失败不等同于正常 null；正常 null 表示宿主未提供编辑时间，不能独立证明两份不同正文绝无编辑差异，此边界仍须设备验证。失败重试也必须先核对快照与当前绑定版本、正文状态哈希，不能将旧正文重新标为新编辑版本。系统正文不等同于正文，未将其强制替换为整篇正文。
- 从设备 Discord 352.17 APK 的 `res/layout/message_view_reply_preview.xml` 核实，正文 `MessageContentView` 设置 `maxLines=2`、`ellipsize=END`、`singleLine=false`。保留该原生设置；不按字符裁剪、不拼接省略号。此前诊断已核实实际两行、省略和空白原因；排障监听、几何/空白统计及对应配置已删除，不改变原生测量。
- 排障用预览消费、span/attachment 快照和空白统计已删除。等长编辑仍可能调整换行专用 exclusive span 或 paragraph span 边界；用户已确认图片引用修复，但纯逻辑单测不等同于所有富文本样例通过。没有规范消息译文时保持原生原文。
- Unicode emoji 请求锚点只读取 profile 的 `getEmojiAnchor`（352017 已核实为 surrogate 字段 `c`），读取失败/空值不会换用其它成员。

## 结构约束

- 片段按节点出现位置消费，不能按对象身份映射；同一文本节点实例可能在树中出现多次。
- 链接、emoji、提及、代码与列表等节点由宿主原样渲染。
- 节点重建策略由版本 profile 指定：公开 data class 使用 `componentN()/copy(...)`；混淆节点的 `nodeConstructors` 明确列出唯一构造器参数类型、mask、children 与字段顺序。不探测所有构造器、不依赖反射字段声明顺序；失败拒绝整树。特别是 Heading 的实际参数为 `(List, int mask, int level)`。
- 不允许部分成功：出现片段计数不符或任一节点重建失败时，整棵替换树都不提交。
