# 上游合并流程规划（upstream merge playbook）

> 适用仓库：`wxmyyds/DiscordAutoTranslate` 的本地 fork 工作区（分支 `feat/miuix-settings-ui`）。
> 目标：定期同步上游 `origin/main` 的修复，同时**永久保留**本地的三类定制：
> ① miuix Compose 设置界面；② 回环/自建明文 HTTP 放行修复；③ 翻译链路的定向放宽（emoji 锚点容忍、长度比例按方向放宽、端点/错误信息优化）。
> 冲突总原则：**bug 修复/优化以上游为准；UI 与本地特性不跟随上游**。

## 一、合并前准备

1. 提交者身份：本机未配置全局 git 身份，仓库内已设 `user.name=DSH`、`user.email=dsh@local`（沿用历史提交），勿用 `--global` 改动其他仓库。
2. 网络：git 已配全局代理 `http://127.0.0.1:7890`，`git fetch origin --prune` 直接可用。
3. 同步前先看差异，明确这次要进来多少：
   ```bat
   git fetch origin --prune
   git log --oneline HEAD..origin/main
   git diff HEAD...origin/main --stat
   git diff --stat                 &rem 本地未提交改动
   ```

## 二、保护现场

1. 未提交改动先入 stash（含未跟踪文件），避免合并拒绝或混入：
   ```bat
   git stash push -u -m "pre-merge local WIP <日期>"
   ```
2. 合并基点：当前分支 HEAD（含本地已提交的 miuix UI / cleartext 修复）。

## 三、合并执行

```bat
git merge origin/main -X theirs -m "Merge origin/main <短SHA>: 优先采用上游修复，保留本地 miuix 设置界面与回环明文放行修复"
```

* `-X theirs`：内容冲突自动取上游（即"上游修复优先"）。
* **modify/delete 冲突必须手工裁决**（`-X theirs` 不会处理）：
  * `ui/SettingsActivity.java`（本地删除、上游修改）→ 维持本地删除，`git rm` 该文件，保留 miuix 的 `SettingsActivity.kt`；
  * 上游 `ui/SettingsActivityTest.java` 测的是被删的 Java Activity → 一并 `git rm`。
* 已知**自动合并错位点**（每次合并后必须检查）：
  * `hooks/ContentNodes.java`：`alignTokens` 里曾残留本地旧字段（`t.url`，上游已改名 `anchor`）→ 直接 `git checkout origin/main -- <该文件>` 后按第四节重放本地放宽逻辑；
  * `ModuleEntry.java`：`-X theirs` 会把本地 `hooks.addAll(CleartextHooks.install(this, cl));` 从上游新版 `install(ClassLoader, HookConfiguration)` 里挤掉 → 合并后必须手工补回（在 `PollHooks.install(...)` 之后）；
  * `app/build.gradle` / 根 `build.gradle`：确认本地的 Kotlin/Compose/miuix 配置块仍在（上游不含有这些配置，自动合并通常能保留）。

## 四、本地修复重放（从 stash 按文件挑拣）

`git checkout stash@{0} -- <路径>` 可按路径取回 WIP 版本；对已被上游重写的文件，按"意图"重放而不是按行合并：

| 文件 | 本地修复意图 | 重放方式 |
| --- | --- | --- |
| `ui/SettingsActivity.kt` 等 4 个 .kt | miuix 界面 + 隐藏系统标题栏 + 失败原因详情 | 直接取 stash 版本；对上游新 API 的调用差异手工适配（如 `ApiTester.newCall/TestKind/Failure`） |
| `core/CleartextHosts.java`、`hooks/CleartextHooks.java`、Manifest `usesCleartextTraffic` | 回环/自建明文放行 | 文件本地独有，直接保留；`ModuleEntry` 补 install 调用 |
| `core/CleartextHttp.java`（未跟踪） | 明文被拒时裸 Socket 兜底 | 需跟随上游 Http 新架构（`Request.attachSocket/detachSocket` + `readResponse` 解码管道）适配后再放入 |
| `core/Http.java` | 明文兜底接线、错误信息带原因 | 上游已重写为 `Request`/`ResponseDecoder` 流式架构，只重放"明文检测 → 兜底 → 二次兜底"逻辑 |
| `core/Protocols.java` | endpoint 不重复追加协议后缀；查询串前插入 | 上游实现与旧版相同，可直接重放 |
| `core/Segments.java` | 长度比例按翻译方向放宽（英→中 1/8、中→英 6 倍） | 上游实现与旧版相同，可直接重放 |
| `core/TranslationException.java` | NETWORK/TIMEOUT 消息带 detail | **与上游隐私测试冲突**（异常消息不得进入 lastError），不重放；诊断信息走 `ApiTester.Failure` |
| `translate/JsonApiEngine.java` | classifyHttpError 附带 `error.message` 摘要 | 在上游版本上追加 `briefServerError`，保留 `UpstreamHttpException` 原样带正文 |
| `hooks/ContentNodes.java` | emoji 锚点丢失→合并回填；样式标记修复（`</ x1>`、缺开/闭标记）；列表圆点允许无空格 | 上游重写了该文件，按上游结构重放（见 `alignTranslation`/`requireStyleMarkers`/`alignByStyleMarkers`/`alignSpanToParts` 现状） |
| `hooks/StructurableTextFactory.java` | 严格对齐失败降级为纯文本（译文必显示） | 上游版本上改 `replaced == null` 分支 |
| `core/Config.java` | 系统提示词第 8 条（emoji 原位保留） | 追加到 `DEF_SYSTEM_PROMPT` |
| `app/build.gradle` | `applicationId …fork`、`versionCode 2`、`versionName "1.2"` | 合并后覆盖 defaultConfig 对应行 |
| 测试文件 | 与上述行为对应的断言 | 跟随行为更新（见各测试文件中"2026-11-23 放宽"标注） |

不重放：`AGENTS.md`、`README.md`、`docs/*`、`.github/workflows/build.yml` 的本地政策性修改（与上游重写冲突，价值低；以本文档代替说明）。

## 五、验证

1. 编译 + 单测（**中文路径下测试 worker 会全部 ClassNotFoundException**，必须借纯 ASCII 盘符）：
   ```bat
   subst X: "E:\桌面\discord 翻译\DiscordAutoTranslate\DiscordAutoTranslate"
   set JAVA_HOME=C:\Program Files\Java\jdk-21
   set ANDROID_HOME=E:\android-sdk
   set KEYSTORE_PROPERTIES=E:\桌面\discord 翻译\signing\keystore.properties
   E:\gradle-9.6.0\bin\gradle.bat -p X:\ --no-daemon :app:testDebugUnitTest
   subst X: /d
   ```
2. 打包可直接用中文路径（编译/打包不受影响）：
   ```bat
   E:\gradle-9.6.0\bin\gradle.bat -p "E:\桌面\discord 翻译\DiscordAutoTranslate\DiscordAutoTranslate" --no-daemon :app:assembleDebug :app:assembleRelease
   ```
   签名：`KEYSTORE_PROPERTIES` 指向 `E:\桌面\discord 翻译\signing\keystore.properties`（alias `discordautotranslate`）。注意本地密钥与上游 CI 不同，本地包不能覆盖安装 CI 签名的发行版。
3. 测试预期：上游 937+ 用例中，仅"锚点/标记严格拒绝"类断言按本文放宽调整（文件内均有 `2026-11-23 放宽` 标注），其余必须全绿。

## 六、收尾

1. 合并提交只包含合并本身与重放的本地修复；**未经用户明确指示不 `git commit`、不 `git push`**（仓库铁律）。
2. stash 保留不删（其中可能有尚未适配的 WIP，如 `CleartextHttp` 旧版），确认无用后再清理。
3. 每次合并后更新本文档的"已知错位点"清单。
