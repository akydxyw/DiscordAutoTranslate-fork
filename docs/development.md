# 开发、验证与发布

协作和分支规则以 [AGENTS.md](../AGENTS.md) 为准。编译、打包、自动化测试和发布禁止在本地执行；**该操作需要通过 GitHub Actions 执行。** 新版本 Hook 适配使用 [项目 Skill](../.agents/skills/discord-hook-adaptation/SKILL.md)。

## GitHub Actions

| 工作流 | 用途与触发方式 |
|---|---|
| [build.yml](../.github/workflows/build.yml) | 推送 `app/**`、根 Gradle 配置或 `.github/workflows/**` 时运行 Debug 构建；手动可选 Debug/Release |
| [release.yml](../.github/workflows/release.yml) | 仅手动发布；stable 固定来源 main，prerelease 使用指定分支或完整 SHA |

纯文档/Skill 改动不触发自动构建；如需验证，手动选择对应分支。构建工作流执行单元测试、上传 HTML 报告、检查 Xposed 元数据及图标资源、验证 APK 签名并上传 APK。发布工作流执行测试、Release 打包及签名验证后创建 tag/Release。

报告验证时提供来源完整 SHA、Actions run 链接及实际结果；下载并读取测试报告中的 tests/failures/skipped。CI 绿色只代表已执行检查通过，不代表所有版本签名、Android 生命周期或设备行为已验证。交付 APK 时核对 artifact 来源，复制后比较 SHA-256。

## 签名与发布配置

两个工作流均要求仓库 Secrets：

| Secret | 用途 |
|---|---|
| `KEYSTORE_BASE64` | 已备份正式 keystore 的 base64 内容 |
| `KEYSTORE_PASSWORD` | keystore 与 key 共用口令 |
| `KEY_ALIAS` | 签名 alias |
| `LSPOSED_REPO_TOKEN` | 仅 stable 发布要求；对目标模块仓库具有 Contents 读写权限 |

工作流将密钥写入 runner 临时目录，通过 `KEYSTORE_PROPERTIES` 提供给 `app/build.gradle`。Debug/Release 使用同一证书；缺配置明确失败，不产生替代签名。密钥及 properties 不提交、不输出，原始 keystore 应独立离线备份。旧版本覆盖升级要求包名和签名一致。

发布版本名：stable 填 `X.Y.Z`，须高于最近正式版；prerelease 填 `X.Y.Z-rc.N`，基础版本按工作流校验。versionCode 从全部发布 tag 的高水位递增，tag 为 `<versionCode>-<versionName>`。版本修改只记录到 tag 引用的发布提交，不改写 main，不维护长期发布分支。stable 同步到 `Xposed-Modules-Repo/io.github.wxmyyds.discordautotranslate`；prerelease 仅发布到源码仓库。运行发布会创建外部产物，须遵循当前用户授权范围。

## 静态检查与测试边界

提交前检查 diff、忽略文件、配置版本号、引用链接和新旧链路残留。所有 `hook-config/*.json` 应进入通用配置解析检查；已有针对 340013/352017 的宿主 fixture 仍只覆盖这两个版本，不能冒充新宿主。

新增适配除静态 JSON 校验外，必须对目标 APK/反编译资料核对真实描述符、字段类型、构造器和原生调用顺序；参数数量相同或模拟类型通过不能证明宿主匹配。具体证据要求见 Skill。

JVM 单测无真实 Android 布局/Hook 环境。它可以验证纯逻辑、配置、模拟 data class 反射、调度/协议及严格拒绝，但不能证明 Drawee、触摸、RecyclerView 或框架热重载的设备效果。

## 设备回归矩阵

| 场景 | 核对重点 |
|---|---|
| 正文及编辑 | 开关、语言检测、相同正文不同消息、旧结果隔离、长文及编辑重译 |
| 富文本 | 标题、列表、多种样式、链接/emoji、提及、时间戳、代码、共享节点；损坏标记必须整树拒绝 |
| 回复 | 普通/图片/Embed 引用跟随规范译文及切换；编辑版本、系统内容、两行省略和用户名避让 |
| Embed | 标题/描述/字段、多卡、空卡、编辑、滚动复用及每卡单图标 |
| Poll | 完整选项、整卡切换、重排/增删/编辑、结果更新、图片无障碍、原生点击/长按、滚动复用 |
| 图标/刷新 | 进度动画、切换、失败手动重试、行高及绑定缓存失效 |
| 生命周期 | 安装失败清理、热重载、取消及 worker 退出确认、无重复 Hook/回调 |
| 配置/日志 | 保存、同步失败、重置、旋转/草稿；开启/关闭日志后重启 Discord，检查启动及业务日志均受控 |

设备记录应包含 Discord versionCode、模块提交/产物 SHA、复现步骤、预期与实测结果。区分静态核对、Actions、设备已验证及未验证项，不用整体“正常”推定全部边界通过。

## 排障

需要日志时先在设置页启用项目日志、保存并重启 Discord。按安装/身份映射、检测、请求、严格回填、刷新五个阶段定位，不凭单一图标判定根因。

LSPosed 导出日志未必包含完整 logcat。固定原因码和异常类型可帮助分类，但日志不携带消息身份，不能把相邻的成功、PARSE、NETWORK 或结构拒绝记录自动归为同一条消息。不得为排障增加正文、身份、视图几何输出或第二次宿主遍历。
