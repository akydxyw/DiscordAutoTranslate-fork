<div align="center">

# Discord AutoTranslate

**Discord 消息自动翻译（多语言 → 可配置目标语言）的 LSPosed 模块**

基于 [LibXposed API 102](https://github.com/libxposed/api)（现代 Xposed API）

[![GitHub release](https://img.shields.io/github/v/release/wxmyyds/DiscordAutoTranslate)](https://github.com/wxmyyds/DiscordAutoTranslate/releases)
[![GitHub Actions](https://img.shields.io/github/actions/workflow/status/wxmyyds/DiscordAutoTranslate/build.yml?label=build)](https://github.com/wxmyyds/DiscordAutoTranslate/actions)

</div>

---

## 功能

- **自动翻译**：英文、俄语、日语、韩语等非目标语言消息可翻译到设置的目标语言，完成后自动替换正文
- **保留 Markdown**：标题 / 列表 / 提及 / 表情 / 代码等结构完整保留
- **行内翻译图标**：翻译中 / 成功 / 失败三态，点击切换原文 / 译文，失败可重试
- **回复引用（352.17）**：只复用被引用消息已有译文，保留原生两行省略和用户名避让；普通及带图片引用已由用户复测确认
- **版本化适配**：各版本只安装已核实的 Hook，具体能力见下表
- **编辑重译**：消息被编辑后自动重新翻译
- **供应商协议**：支持 OpenAI-Compatible / OpenAI-Responses / Anthropic Messages，
  可在设置中为支持的协议切换流式（SSE）请求
- **独立设置界面**：配置检查 / 测试连接 / 测试翻译
- **隐私友好**：消息正文与译文不写入日志，API 密钥仅保存在本机

## 系统要求

- Android 8.0+（API 26+）
- 已 Root 且安装 [LSPosed](https://github.com/LSPosed/LSPosed)（支持 API 102）
- Discord 必须有精确匹配的版本配置；未知版本不安装消息 Hook，不复用旧版配置。

| Discord | 当前能力与验证边界 |
|---|---|
| **352.17 Stable**（352017） | 正文翻译；普通及带图片回复预览由用户复测确认。富 Embed、投票整卡切换及架构优化版 `4493cee` 已获用户“功能正常”确认；未逐项覆盖的特殊场景仍需单独复测；帖子预览未接入 |
| **340.13 Stable**（340013） | 正文、富 Embed、图标点击及行刷新 `244306a` 已获用户确认正常。引用预览 `5f1d1fb` 已获用户确认修复；投票已按该版 smali 接入统一链路，待新版设备复测；帖子预览未接入 |

## 富 Embed 适配边界

- `352017` 的现有 `embed.bind` 直接匹配 `q9.q.b` 实例方法：34 个 Java 参数、返回 `void`，模型和消息 ID 分别来自参数 1 和 3，宿主为当前 View。`long`/`double` 各计一个参数，不按 smali 寄存器槽位计数。
- 描述、标题、字段及字段名/值通过 profile 的语义字段映射读取结构化文本，复用统一富 Embed 链路，不新增独立解析或渲染入口。该版本 `embed.bind` 为必需 Hook。
- `340013` 的 `EmbedViewHolder.bind` 为完整9参数实例方法，使用参数1的 accessory、`itemView` 宿主。已根据该版 smali 映射 `EmbedMessageAccessory.getMessageId-3Eiw7ao` 和真实 `Embed/EmbedField` 类型；列为必需 Hook。
- 当前 `4493cee` 已获用户整体功能正常确认；该反馈不等同于多卡片、全部字段/编辑和视图复用边界均逐项覆盖。

## 投票适配边界

- 352017 的问题绑定为 `ea.r0.v`，从原生 accessory `h` 读取全部选项，在根标题绑定时为问题及所有选项登记统一翻译输入；选项是否已显示不影响请求登记。`ba.h.f` 只绑定当前选项显示与重渲染，不另发请求。问题/选项是普通字符串，不经过 `B0`；仅在绑定作用域内按精确视图身份与原始输入拦截原生 `setText`，图片选项拦截其 `setContentDescription`。
- 原始文本包装为 Primitive 输入，复用现有语言检测、StateStore、Scheduler、缓存及重渲染。每张投票卡片只由标题图标切换整组原文/译文，涵盖全部文本选项及图片选项的无障碍文本；无可用译文的段仍显示原文。图标汇总当前各段翻译状态，原文/译文着色只取卡片选择，失败由标题入口重试整组失败段。
- 只有标题注入图标并处理图标区域触摸；所有选项都不注入图标、不安装独立切换触摸，选项点击完全交给原生投票。图片内容、投票模型、答案 ID、选中状态、票数、百分比和宿主回调均不修改。340013 使用同一链路，问题入口为 `PollViewHolder.bind`，选项入口为 typed `PollAnswerAdapter.onBindViewHolder(PollAnswerViewHolder,int)`，不同时挂 bridge 重载。根 accessory `answers` 提供完整选项，标题路径 `pollView.binding.questionText`；文本选项用 `binding.label`，图片选项只替换 `binding.image` 无障碍描述，不新增可见字幕。新版投票效果尚待设备确认。
- 重复绑定、编辑标题或选项列表时保留同一行的整组显示选择，并替换当前段键列表，移除旧标题/已删除选项；迟到译文完成不改变用户选择。重置/新代次清空卡片状态。
- 构建/单测与设备显示分别记账：`4493cee` 的 Actions 为 511 项测试、0 失败/跳过，用户已确认功能正常。图片选项无障碍文本、结果更新、长译文及特殊滚动/重试组合仍需针对性样例验证。该操作需要通过 GitHub Actions 执行。

## 安装与激活

1. 在本仓库 [Releases](https://github.com/wxmyyds/DiscordAutoTranslate/releases) 下载 APK 并安装。
2. 打开 LSPosed，启用 **Discord AutoTranslate**，作用域勾选 `com.discord`。
3. 强制停止并重启 Discord。
4. 打开模块设置，填写 API URL、密钥和模型。配置也可在启用模块前保存，启用后自动同步。

> 需要支持 API 102 的 LSPosed 版本。

## 使用说明

- 翻译默认开启；可在设置界面调整目标语言、供应商协议、流式请求、系统提示词、并发与超时等参数。
- 打开模块设置 →「测试连接」可验证 API 配置，「测试翻译」可预览翻译效果。
- 消息右下角 / 行内图标：翻译中为旋转状态，完成后可点击在原文 / 译文间切换。

## 构建

**本仓库禁止本地构建。** 所有编译与打包通过 GitHub Actions 执行：
`.github/workflows/build.yml`（手动触发或推送分支时运行），产物为 APK artifact。

## 隐私

启用后消息正文会发送到你配置的翻译服务。API 密钥保存在本机私有配置中，并同步到 LSPosed remote preferences；不会写入日志。
消息正文、译文、消息 ID 和内容哈希不写入日志。诊断记录安装、状态和异常类别；临时布局/空白统计已清理，当前没有详细日志开关。

## 常见问题

**翻译后列表项不在原位？**
列表按“每项一行”对齐回填；若模型把多项合并成一行，会保留原文而不是错位塞入。

**图标显示成功但正文仍是英文？**
说明译文结构与原文对不上被拒绝回填（保留原文），请提供复现步骤和 LSPosed 模块日志；日志不包含消息正文。

**切换协议后译文还是旧的？**
协议参与配置版本计算，切换后旧缓存自动失效并重新请求。

## 相关文档

- [架构总览](docs/architecture.md) / [渲染与回填](docs/rendering.md)
- [翻译引擎与配置](docs/translation-engine.md)
- [行内图标与布局](docs/icons-and-layout.md) / [维护经验](docs/maintenance-notes.md)

## 免责声明

本项目与 Discord Inc. 无关联。Discord 为 Discord Inc. 的商标。
使用本模块可能违反 Discord 服务条款，请自行评估风险。