---
name: discord-hook-adaptation
description: 当需要为新的 Discord Android versionCode 新增 Hook 版本配置时使用。核实目标 APK、原生绑定和转换链，只新增既有 schema 支持的 JSON profile，执行静态校验、GitHub Actions 和设备回归；不修改现有业务代码。
---

# Discord Hook 版本适配

## 范围与前置条件

先读仓库根目录 [AGENTS.md](../../../AGENTS.md)、[架构](../../../docs/architecture.md)、[渲染](../../../docs/rendering.md) 和 [验证要求](../../../docs/development.md)。路径均相对此 Skill 目录。

本 Skill 的适配交付仅新增 `app/src/main/assets/hook-config/<versionCode>.json`。不得改 Java/Kotlin 业务、现有版本配置、资源、依赖、工作流、测试或宿主 smali。证据表、反编译资料和临时脚本保存在仓库外；报告中说明验证状态即可，不复制 APK/smali 到 Git。

只有现有配置机制能完整表达目标宿主时才能完成适配。若新版本改变了输入类型、异步作用域、节点构造或渲染协议，记录无法表达的具体结构和消费者，报告本次配置适配阻塞。不要臆造 JSON 字段、降低 requiredHooks、关闭现有能力、猜测旧成员或增加另一条链路；架构扩展属于独立任务。

沿用用户已授权的短期分支；如确需新建，遵守 AGENTS.md。不得直接修改 main 或自动合并。

## 1. 确定唯一目标

记录目标包名 `com.discord`、versionName、实际 versionCode、APK SHA-256 及反编译资料根目录。versionCode 必须来自目标 APK/安装元数据；不要按显示版本或目录名猜测，不能混用其他版本 smali。

查阅已有两个 profile 以了解字段结构，并阅读实际消费者：

- `core/HookConfiguration.java`、`HookConfigurationLoader.java`、`Reflect.java`
- `hooks/MessageHooks.java`、`ReplyBinding.java`、`EmbedBinding.java`
- `hooks/PollHooks.java`、`PollBinding.java`
- `hooks/StructurableTextFactory.java`、`ContentNodes.java`
- `hooks/IconClickSpanFactory.java`、`InlineIconInjector.java`、`InlineIconSpan.java`、`RerenderRegistry.java`、`RowRefresher.java`

已有 profile 只能作为结构参考，不能作为新版本签名证据。

## 2. 先形成证据表

为每个目标保存“配置键 → 完整类/方法描述符或字段声明 → 资料路径及行号 → 原生调用位置/语义”。先证明调用关系，再填写 JSON。

| 区域 | 必须核实 |
|---|---|
| 正文 | 外层 Message 绑定、Message ID/content/edit getter；正文渲染 View/content/ID 参数；文本转换入口、返回类型及完整调用链；正文 View 私有折叠测量 TextView 的无参 getter、原生更新条件及注入 builder 的持有关系 |
| 回复 | Loaded 引用类、引用 Message 与 systemContent、预览 View 字段链；转换是否同步处于外层 bind scope；Drawee、margin 和省略仍由宿主执行 |
| 结构节点 | Primitive(String)、Annotated(List)、Text(String)；各语义容器/列表/emoji 的真实类型；公开 copy/componentN 或唯一配置构造器 |
| Embed | 唯一模型/消息 ID 来源与宿主来源；描述/标题/字段名值实际是 StructurableText；继承字段的声明位置 |
| Poll | 根 accessory 的完整答案列表、稳定消息/答案 ID、选项 adapter 列表、holder binding、文字及图片两种 binding 类和 receiver；两个 setter 都在同步 bind scope 内 |
| 点击 | 原生 ClickableSpan 父类/接口、唯一构造器、callback 参数、default mask 的每一位含义；禁止为类型不匹配试另一个构造器 |
| 刷新 | LayoutParams holder、holder itemView/所属 RecyclerView/adapter/position、observable 通知方法；原生内容相等早退缓存是否须清理 |

明确排除 bridge 转发入口，避免同时挂 typed 与 bridge 造成双重登记。不能只凭类型名相似、方法参数数量或反编译器显示的属性名认定语义。Kotlin 修饰名必须包含真实后缀，`getContent` 与 `getContent$annotations` 不能混同。

回复不得新增独立 Hook、独立状态或请求；Poll 不改模型来制造 diff，图片只替换无障碍描述。若原生异步调用已超出当前作用域能力，记录阻塞。

## 3. 创建版本配置

文件名、JSON `versionCode` 和目标版本必须一致，`schemaVersion` 为整数 1。仅使用已有消费者支持的语义键。

`requiredHooks` 恰好包含当前八项，不得重复、禁用或删减：

```text
message.bind
message.render
message.textConversion
embed.bind
poll.bind
poll.answerBind
poll.textSet
poll.accessibilitySet
```

每个 `target` 填写精确 `class`、`name`、`static`、`parameterCount`、`returnType`，并在 `parameterTypes` 对象中列全 `"0"` 到 `"N-1"`。参数索引从 0 起，不含 receiver；`long/double` 各计一个 Java 参数，不按 smali 寄存器槽位计数。数组按解析器格式填写，内部类使用真实 `$` 名。

生产 schema 要求精确 `name` 和 `parameterCount`，禁止 `namePrefix`、`minParameterCount`、`selection` 及通配符。`static`、已配置的 `returnType` 和各项 `parameterTypes` 必须同时匹配；零候选或多个候选均拒绝，不按反射枚举顺序选择。现有 352017 的部分参数类型仅作为必须满足的约束，不构成备用签名或重试路径。本 Skill 对新适配进一步要求完整参数签名及 `returnType`；不能照抄旧 profile 的部分签名。

- `types` 映射语义名到真实类；`typeAliases` 反向映射真实类到已支持语义名。
- 类型专属 getter 用 `accessors`，字段用 `fields`，已支持的公共语义 getter 用 `members`；读取策略由消费者决定，不配置竞争路径。
- 正文 render 的身份使用既有 `identitySource=argument`，明确 View/content/ID 参数；静态渲染额外配置 `viewArgument`。
- `message.render.shadowViewGetter` 填写正文 View 上声明的精确无参方法名，返回私有折叠测量 `TextView`。当前消费者仅在宿主运行时类名等于 `message.render.target.class` 时捕获该视图；静态入口也须核实该类与 `viewArgument` 的实际 View 一致。必须证明原生主文本与测量文本的更新路径，不能假定遍历子 View 即覆盖全部文本。getter 无法表达或返回类型不符时记录适配阻塞。
- Embed 只选 `inputSource=arguments`（模型/ID 参数）或 `accessory`（accessory 参数），宿主只选 `this` 或指定 `field`，不同时保留两套。
- Poll 四项须完整、启用且为实例入口。字段 path 是真实字段链，不是任意 getter 表达式。根 `optionsPath` 必须是完整答案 accessory 列表；文本和图片 binding 类不同。
- 未配置 `nodeConstructors` 的类型必须证明可用现有公开 copy 策略；配置构造器只支持当前 `children/field/integer` 参数，每个恰好一个 List children。参数顺序、mask 和保留字段逐项核实，不凭声明顺序猜测。
- 图标只用 `constructorProxy`；构造参数只支持 `null/integer/float/callback`，float 必须有限值；唯一 callback 执行 `toggleTranslation`。不得新增 interfaceProxy 或备用触摸实现。

## 4. 静态验证

在仓库根目录执行配置检查脚本（仅检查 JSON 元数据，不编译或运行 Android）：

```bash
node .agents/skills/discord-hook-adaptation/scripts/validate-profile.cjs <versionCode>
```

脚本检查文件/版本一致、八项必需入口、精确完整 selector、参数索引和现有构造元数据形态。它不读取目标 APK，不能证明 Hook 唯一命中、成员真实存在或业务成功；逐项对照步骤 2 的证据表补足这些核实。

检查每个 selector 在目标资料中恰好命中一个完整签名；沿原生调用链检查 getter、字段类型、setter 时机、点击和刷新。不从失败中挑选另一个重载。脚本当前不校验 `shadowViewGetter`，需单独核实其精确声明、返回类型及实际调用对象；图标注入还要求该宿主已有最新原生绑定登记，以供退出时恢复。

检查 Git diff 仅新增目标 JSON；不得夹带改旧版本、业务源码、测试、临时资料或被忽略内容。未知版本不复用旧配置，必需失败仍整体拒绝。

## 5. Actions 与设备验收

**该操作需要通过 GitHub Actions 执行。** 推送配置后检查 build 工作流对应完整 SHA；若未触发则手动选定分支。核实编译、单测 HTML、APK 内容、签名、artifact，并报告实际 tests/failures/skipped。

通用 `HookProfileInventoryTest` 自动枚举全部 JSON 并用生产解析器验证；它只证明配置可解析、requiredHooks 完整。旧的 `ShippedHookProfileTest` 和宿主 fixture 专门覆盖现有两版，不为新版本提供真实反射/布局证据，不因 CI 绿色宣称适配完成。

交付 exact SHA 的 APK，复制后核对 SHA-256。按开发文档设备矩阵验证正文/编辑/复用、复杂 Markdown、普通/图片回复、Embed、多种 Poll、图标切换/重试、刷新及生命周期。排障需要日志时，在设置页启用项目日志、保存并重启 Discord；日志隐私约束不放宽。

最终报告列出配置文件、目标 APK 身份、证据表、静态检查、Actions 链接及实际结果、模块产物 SHA 和设备已测/未测项目。未取得设备结果时明确“配置及 CI 已验证，实机待确认”。Actions 成功且用户确认后才可按项目规则通过 PR 合并。
