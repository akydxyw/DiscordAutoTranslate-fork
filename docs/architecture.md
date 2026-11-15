# 架构与生命周期

用户功能及支持版本见 [README](../README.md)，协作要求见 [AGENTS.md](../AGENTS.md)。具体宿主类、字段、参数和构造器以 `app/src/main/assets/hook-config/<versionCode>.json` 为唯一配置来源。

## 唯一处理链

```text
原生绑定 → 规范消息身份/编辑版本 → ContentTree 捕获 → TextExtractor
→ EnglishDetector → HookBridge → StateStore / TranslationCache / Scheduler
→ JsonApiEngine → Http / SseReader / Protocols
→ 所有权校验 → RenderDispatcher 原生重放
→ 当前输入重新捕获及 hash 校验 → ContentNodes 重建 → 原生转换/显示
```

正文、Embed、Poll 共用检测、调度、状态和缓存；回复只消费规范正文已有译文。不同版本只更换 profile，不复制业务流程。捕获与重建规则见 [渲染文档](rendering.md)，网络与配置见 [翻译引擎](translation-engine.md)。

| 位置 | 职责 |
|---|---|
| `ModuleEntry` | 唯一 Xposed 入口、profile 选择、Hook 句柄所有权、运行屏障与生命周期 |
| `core/HookConfiguration*`、`Reflect` | APK 内精确版本配置加载、配置解析、类型及成员访问 |
| `core/ContentTree`、`TextExtractor` | 按出现位置捕获结构并生成请求输入 |
| `core/StateStore`、`TranslationTaskId`、`Scheduler`、`TranslationCache` | 状态、任务所有权、调度及结果复用 |
| `hooks/MessageHooks`、`EmbedHooks`、`PollHooks` | 原生绑定与转换作用域；输入由 profile 指定 |
| `hooks/HookBridge` | 唯一生产入口、身份映射、配置与引擎协调 |
| `hooks/RerenderRegistry`、`RenderDispatcher`、`RowRefresher` | 显示订阅、主线程原生重放及列表行刷新 |
| `hooks/ContentNodes`、`StructurableTextFactory` | 按捕获计划严格回填及宿主对象重建 |
| `translate/` | API 校验、请求及设置页测试 |
| `ui/`、`core/ConfigurationSync`、`PreferenceCommit` | 表单、服务连接与配置事务 |
| `core/ProjectLog` | 全项目日志开关、去重及唯一输出入口 |

## 身份、状态与异步结果

译文状态不存放在 View 上。View 只登记当前显示目标；内容对象使用弱引用和对象身份映射，避免 Kotlin 按正文相等比较使不同消息串位。

`TranslationTaskId` 贯穿消息/段键、内容 hash、generation 和唯一 attempt。编辑替换对应状态；派发、完成、丢弃回调及主线程交付均核对所有权，旧任务不得迁移新 attempt，也不得复活被淘汰状态。

`StateStore` 最多保留 512 条 LRU 状态，规范 Source 与编辑版本随条目失效。所有权检查不改变 LRU。译文缓存另有 512 条上限，调度等待队列也独立限制为 512；三者不是同一个仓库。等待不占工作线程，并发限制为 1–8；去重持续到主线程交付结束。

Embed 段键隔离正文并包含内容 hash；Poll 段键还包含问题/答案稳定 ID。回复不建立预览译文缓存、不创建翻译状态或独立请求。Poll 的整卡显示选择由 `PollCardState` 唯一拥有，最多 512 卡。

`RenderDispatcher` 拥有可清除的主线程队列、重放作用域和代次。执行前核对当前绑定，重放不再次生产翻译；列表刷新沿原生 adapter 通知，不手工测量或布局。

## 安装与退出

入口声明位于 `app/src/main/resources/META-INF/xposed/`：`java_init.list` 只列 `ModuleEntry`，`scope.list` 为 `com.discord`，`module.prop` 声明 API 102 及热重载。

`onModuleLoaded` 先读取日志开关再记录启动诊断；`onPackageReady` 安装唯一 `Application.attach` 启动 Hook。取得 Discord `PackageInfo.versionCode` 后，只从模块 APK 读取同名 JSON。未知版本不安装消息 Hook；无效配置或必需目标解析失败不能切换到其他版本。

Hook 安装期间 `HookRuntime` 暂停，拦截器仅执行宿主。全部必需 Hook 注册成功后才激活业务。当前 profile 的八项必需 ID 为正文绑定/渲染/转换、Embed 绑定、Poll 根绑定/选项绑定/文本 setter/无障碍 setter。句柄创建成功即登记到 `ModuleEntry` 的唯一列表。

`HookCleanup` 一次尝试全部解绑，仅移除成功句柄；失败句柄继续保留所有权。安装失败统一清理运行状态并要求重启，不自动重新安装。

## 热重载

旧代次先暂停生产，再取消请求并确认所有当前及退休调度器线程退出；超时拒绝热重载并要求重启，不能把“请求取消”当作“线程已退出”。随后解绑句柄并清理状态、缓存、宿主构造缓存、渲染队列和模块资源。

新代次在解绑前从旧句柄快照恢复目标 ClassLoader，用保存的 versionCode 从新 APK 读取 profile，再检查必需目标。只从该 profile 的消息绑定类恢复 loader，不能采用 `Application.attach` 的系统 loader。资料恢复、解绑或安装不完整均阻止继续安装并明确要求重启。

`ProjectLog` 的框架输出回调随模块入口挂载，清理时解除，避免日志额外持有旧入口。日志关闭仍执行相同安装、取消、拒绝与清理逻辑。
