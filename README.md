# Discord AutoTranslate

Discord Android 的 LSPosed 自动翻译模块，基于 LibXposed API 102。消息发送到用户配置的翻译服务，默认目标语言为简体中文。

[下载最新版](https://github.com/wxmyyds/DiscordAutoTranslate/releases/latest) · [GitHub Actions](https://github.com/wxmyyds/DiscordAutoTranslate/actions)

## 功能与支持版本

- 自动翻译消息正文及富 Embed，消息编辑后重新判定；图标显示进度、译文、原文或失败，点击切换，失败时手动重试。
- 保留宿主 Markdown 结构、链接、表情、提及和代码节点。无法严格对应原结构的译文会被拒绝，继续显示原文。
- 回复预览跟随被引用消息的已有译文和显示选择；引用本身不发起翻译请求。
- 投票标题控制整卡原文/译文切换，保留选项、图片及投票行为。图片选项仅翻译无障碍描述。
- 独立设置页支持目标语言、API、三种供应商协议、流式请求、配置检查和翻译测试。

| Discord Stable | versionCode | 已接入能力 |
|---|---|---|
| 340.13 | 340013 | 正文、回复预览、富 Embed、投票 |
| 352.17 | 352017 | 正文、回复预览、富 Embed、投票 |

两版均有用户功能确认；图片选项无障碍、投票结果更新、特殊触摸/滚动组合仍需专项设备回归。混合代码块正文的检测修正已通过自动化验证，实机效果待确认。帖子预览、OCR、语音及界面文本翻译未接入。未知 versionCode 不加载其他版本配置。

## 安装与设置

要求 Android 8.0+、Root，以及支持 LibXposed API 102 的 LSPosed。

1. 安装模块 APK，在 LSPosed 中启用模块，作用域勾选 `com.discord`。
2. 打开模块设置，填写 API 基础地址、密钥和模型，选择协议及目标语言。
3. 保存设置，强制停止并重启 Discord。模块尚未启用时也可先保存，服务连接后同步。
4. 使用“配置检查”检查格式，“测试连接”或“测试翻译”验证实际请求。

API 基础地址示例：`https://api.openai.com/v1`，协议端点由模块追加。支持 OpenAI Compatible、OpenAI Responses、Anthropic Messages；Responses 固定使用非流式响应。

“启用自动翻译”默认开启。“启用项目日志”默认关闭，统一控制 logcat 和 LSPosed 中本项目产生的诊断，包括启动及错误日志。**保存后重启 Discord 生效**；同步失败时先处理设置页提示。关闭日志不会删除已有日志，也不控制 Discord 或框架自身输出。

## 隐私与排障

消息的可翻译文本会发送到你配置的服务。代码、提及、时间戳等宿主保留节点仅发送位置标记；URL 和可识别的 emoji 锚点仍可能随请求发送。API 密钥保存在模块私有设置及本机 LSPosed remote preferences。

即使开启日志，也不记录正文、译文、消息 ID、内容 hash、URL、emoji alt、凭据、文本长度/偏移或视图几何信息；仅记录固定原因码、异常类型和必要结构信息。

- 完全不触发：检查作用域、精确版本支持、自动翻译开关和 API 配置；短文、纯链接/代码或符合目标文字体系的正文可能被检测跳过。
- 请求或回填失败：检查网络、协议、超时和模型是否保留行及标记；失败图标支持手动重试，不会自动重试。
- 需要诊断：开启日志、保存并重启 Discord，提供 Discord versionCode、模块来源提交、复现时间及操作步骤。无消息身份的日志不能单独证明对应哪条消息。

## 开发文档

- [架构与生命周期](docs/architecture.md)
- [渲染、回复、投票及图标](docs/rendering.md)
- [检测、翻译协议与配置](docs/translation-engine.md)
- [验证、签名与发布](docs/development.md)
- [Hook 版本适配 Skill](.agents/skills/discord-hook-adaptation/SKILL.md)
- [项目协作规则](AGENTS.md)

编译、打包、自动化测试与发布均通过 GitHub Actions 执行。该操作需要通过 GitHub Actions 执行。

本项目与 Discord Inc. 无关联；Discord 为其商标。使用模块可能违反 Discord 服务条款，请自行评估。
