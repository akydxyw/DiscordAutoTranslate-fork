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
- 340013 保留原目标和 accessory 参数 1；`inputSource=accessory`、宿主字段 `itemView`、语义 getter `getMessageId/getEmbed/getDescription/getTitle/getFields/getName/getValue` 由 profile 明确声明。旧模型源码未找到，不猜测 `Embed` 或 `EmbedField` 全限定名。
- 专用全局 `embedHolderViewField` 与 `embed*Getter` 已移除，两版复用同一个配置选择的绑定入口。需要实机核查多卡片、描述/标题/字段、编辑、切换及回收复用；本次未确认设备回归。

## 投票配置核实

- 问题：`ea.r0.v(m9.g0, ea.n, ea.n, k9.l)`；消息 ID 为 accessory `e`，原始问题为 `g.b.a`，问题视图为 holder `u.P.w`，提示标签 `u.P.v` 不处理。根标题从 accessory `h` 读取完整原生选项列表，按答案 ID 与文本包装 Primitive，为问题及全部选项统一登记翻译输入，不等选项视图绑定才发请求。
- 选项：`ba.h.f(k3.k1, int)`；`this.d` 是当前选项列表，消息/答案 ID 为 `n9.a.a`/`n9.a.c.a`，文字为 `n9.a.c.b.a`。文本布局 `q8.f0.v` 调用 `TextView.setText(CharSequence)`；图片布局 `q8.d0.i` 调用 `View.setContentDescription(CharSequence)`。百分比/票数是不同视图，不处理。
- 无 `B0` 转换：只在精确绑定作用域匹配当前 receiver 和捕获的原始字符串，作用域在 finally 恢复。不能修改投票模型制造差分，更不能改答案 ID、图片、投票状态、结果或回调。
- 先解析视图并清理旧登记，再读取新文本/构造 Primitive；重放参数前核对当前槽位/文本键。每次绑定和重放都登记包装内容的身份；只有实际根标题绑定发起统一翻译流程，选项绑定只读状态并登记显示/重渲染，不另发请求。
- 每行仅有一个 `PollCardState` 显示选择和当前段键列表；重复绑定、标题/选项编辑保留选择并删除旧键，段 Entry 的 `showingOriginal` 不控制显示，完成回调不重置选择；新代次与桥接重置清空整组。仅当前标题图标汇总段状态、按卡片选择着色并控制整组切换/失败段重试，刷新全部当前键。选项无图标和独立切换触摸；图片选项无障碍文本也跟随标题，原生投票点击不变。340013 投票仍未接入。
- `PollCardStateTest` 与 `PollTextProjectionTest` 使用现有 StateStore 和假 Primitive 模型，覆盖整组选择、迟到译文、编辑/旧键隔离与代次清理，不代替设备投票/点击/布局回归。构建及单测需要通过 GitHub Actions 执行，设备回归尚未确认。

## 架构优化第一阶段

- 任务接口只接受完整 `TranslationTaskId`；旧 ID/hash submit 重载和信号量排队已删除。唯一 FIFO 负责等待、调度和去重；回调离开调度锁执行，不能阻止其他 worker 收尾。
- `StateStore` 负责任务状态迁移和规范 Source 生命周期；512 条上限覆盖全部状态，后台所有权检查不改变 LRU。旧回调即使被拒绝也不能迁移新 attempt。
- 原生重放移到 `RenderDispatcher`，HookBridge 不再创建独立 Handler 或持有 rawContent 平行仓库；行刷新也从自有队列派发，清理时移除旧代次回调。
- `ConfigurationSync` 统一本地事务与首次导入的初始化标记；页面不直接 commit。服务多订阅、未保存草稿恢复与干净表单重读分开。
- `HookRuntime` 在安装验证完成后才激活；暂停阻止 Hook 和点击重试生产。停止未确认线程终止时拒绝热重载；bootstrap-only 阶段也拒绝重载，不另建恢复链。
- 本阶段不改回复行段压平、富文本对齐及 Poll 模型/触摸语义。富文本单遍历计划、请求级取消协调和协议主导 SSE 结束仍未实施。编译/测试必须经 GitHub Actions，设备回归需另行确认。

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