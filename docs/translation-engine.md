# 翻译引擎与配置

模块运行在 Discord 进程内，翻译引擎是纯逻辑层（不依赖 Android 框架），
单元测试在 JVM 上执行（`testDebugUnitTest`）。

## 多语言源文判定

自动翻译不再限定 ASCII 英文：过滤 URL、行内代码和自定义 emoji 后，按 Unicode code point 统计自然语言字母；达到最小字母数与正文占比后，非目标语言文本可进入同一翻译流程。目标语言为中文时跳过以汉字为主且没有假名/韩文音节的文本；日语假名与韩文 Hangul 可区分日/韩文消息。其他已知目标文字系统也会跳过占主导的同脚本文本。

该策略借鉴 Unicode Script 属性进行脚本层面的筛选，不新增运行时语言模型依赖。Unicode 明确指出，单靠汉字无法可靠地区分中日文；因此纯汉字日文在目标中文时会按中文跳过。含少量假名的中文引文可能被视为日文，短文本和混合语言也无法保证精确识别。目标语言与其他语言共用拉丁字母等同一文字系统时，仅靠脚本也无法区分，相关文本可能仍会提交翻译。参考：[Unicode Script 属性（UAX #24）](https://www.unicode.org/reports/tr24/) 与 [Unicode 中日韩问答](https://unicode.org/faq/han_cjk.html)。

完整语言识别可参考 [Google ML Kit Language Identification](https://developers.google.com/ml-kit/language/identification/android)：官方支持 100 多种语言；打包模型约增加 900 KB，Play Services 版本需等待模型下载。当前方案不引入此依赖，以免 Discord 进程内的模型加载/首次可用性成为翻译前置条件。

## 校验与诊断清理

- validateConfig 返回本次已解析请求头，translate → requestOnce 复用同一对象追加协议认证头；离线自检调用同一验证，不新增备用路径。
- 语言检测删除精确生成的格式标签而不插空格拆词，保留节点标记仍以空格掩蔽；请求正文不变。Verdict.reason 仅固定枚举，不携带字母数、比例或目标语言配置。
- 流式 finish_reason/stop_reason 异常只返回固定描述，不拼接服务端值；未知异常状态描述仅保留异常类型。重渲染/行刷新日志不输出高度或位置，入口不传原始异常给日志。

## 保留叶节点位置协议

统一富文本输入用 `<keepN/>` 表示捕获的保留叶节点出现位置，包括行内/块代码、提及、时间戳和未知不透明节点；内部内容不读取、不提交。协议构造统一追加保留规则，已保存/自定义提示词同样适用；模型必须按原位置、数量和顺序回带标记，不得改名或移入样式标记。引擎与原生回填共用 RetainedNodeMarkers 语法校验，删除旧 InlineCodeMarkers 及空格占位分支；损坏时明确拒绝，不补标记或另试请求。自然语言检测用空白掩蔽标记以保留词边界，长度比例不统计结构标记字符；API 测试和 Primitive 文本不产生宿主结构标记。保留节点本身没有翻译槽，纯结构或 codeHeavy 自动过滤规则不变。

非 URL 的链接显示文字也复用 `<xN>...</xN>` 格式范围标记，必须保留范围后翻译标签；仅 URL 显示仍保留 URL 锚点。请求层统一强调段落和行结构原样保留，不以译文猜测根文本与链接显示文字的分界。

## 网络层

- 用 JDK 自带 `HttpURLConnection`，**零第三方依赖**：Discord 已打包 okhttp3，
  模块再打包一份会类冲突/版本错配。
- JSON 用 `org.json`（Android 系统内置）；JVM 单测通过 `testImplementation`
  提供 `org.json:json`。
- 请求创建唯一 `Protocols.createDecoder(protocol, streaming)`，交给 `Http.postJson(..., request, decoder)`。设置选择 JSON 或 SSE，Compatible 与 Anthropic Messages 支持切换，Responses 固定非流式；格式不符时失败，不切换解析器。
- `SseReader` 统一增量分帧：空行提交完整事件，支持多行 data、event、注释、CR/LF/CRLF。OpenAI decoder 只在 `[DONE]` 完成，Anthropic 只在 `message_stop` 完成；HTTP 不写协议终止标记，完成后不继续读、不等待 EOF。缺终止、未完成帧、错误事件、截断理由或非法字符串字段均拒绝。协议测试在 test source set 喂入字符串 fixture，仍使用同一个 reader/decoder；APK 不保留另一字符串解析入口。
- HTTP 传输累计最多 1 MiB 字节，decoder/SSE 字符累计最多 1 MiB UTF-16 单位；超限立即失败。UTF-8 严格校验，禁止重定向跟随；非 2xx 只读取有界原始 body，不走成功响应 decoder，错误分类不回显 body。
- 引擎在验证配置/构造 body 前登记线程对应的 `Http.Request`，取消标记保持到 finally；取消与连接 attach 原子协调。登记前取消由中断标记阻止请求，attach 后取消断开连接并在 I/O 边界检查。真实 Android 连接断开/阻塞退出仍需设备验证，不能把请求取消等同于 worker 已终止。

## 供应商协议

配置项 `provider_protocol`，三种取值（`Config.PROTOCOL_*`）：

| 协议 | 请求端点/体 | 译文提取 |
|---|---|---|
| `openai-compatible`（默认） | `POST …/v1/chat/completions`：`model` + `messages`(system+user) + `temperature:0` + `stream` 按设置开关 | 流式取 SSE delta；非流式取 `choices.0.message.content` |
| `openai-responses` | `POST …/v1/responses`：`model` + `instructions`（系统提示词）+ `input` + `temperature:0` | 响应 `output` 数组按类型扫描 `message` → `output_text`（推理模型把 `reasoning` 排在 `message` 前，不能固定取 `output.0`） |
| `anthropic-messages` | `POST …/v1/messages`，`max_tokens` 必填（8192） | `content` 数组中 text 块的拼接 |

- Compatible 与 OpenAI-Responses 均不携带 `frequency_penalty`。
- API URL 填基础地址（例如 `https://api.openai.com/v1`），请求时追加当前协议端点。
- 协议与流式开关都参与配置版本计算：切换协议或流式模式后旧缓存不命中，按新配置重新请求。

## 调度与重试（core/Scheduler + TranslationException）

- 单一有界 FIFO 等待队列最多 512 项，按 `max_concurrency`（1–8）派发；等待任务不占工作线程。调低不打断已执行请求，调高可立即派发；队列满明确失败，不改走备用线程/主线程。
- `TranslationTaskId` 包含消息键、hash、generation 和唯一 attempt。调度按消息版本去重，直到主线程交付结束；状态回写额外检查 attempt 所有权，过期回调不能修改新状态。
- 错误分类：`CONFIG / NETWORK / TIMEOUT / HTTP / RATE_LIMIT / PARSE / EMPTY / CANCELLED`。
- 自动重试：无；一次失败后由用户手动点击图标重新发起请求。
- 派发执行前和主线程回写前核对任务所有权；过期等待项可移除，每个有效任务只调用一次引擎。

## 缓存

`TranslationCache`，LRU 上限 512；键 = `SHA-256(正文 + 配置版本)`。
配置版本纳入 `apiUrl / model / targetLang / protocol / streaming / systemPrompt /
凭据 SHA-256 指纹`：任何影响结果的配置变化都会使旧条目不再命中。

`StateStore` 与译文缓存不是同一仓库：状态、规范原内容快照和任务所有权共用最多 512 条 LRU 生命周期，所有状态均受上限约束。淘汰结果不创建新状态，重绑才重新进入正常生产入口。

## 配置项（core/Config；缺省键使用默认值，显式非法值使快照无效）

| 键 | 默认值 | 说明 |
|---|---|---|
| `auto_translate_enabled` | true | 总开关 |
| `api_url` | `https://api.openai.com/v1` | API 基础 URL |
| `api_key` | 空 | 仅保存在本机私有配置与 LSPosed remote preferences，不回显 |
| `api_model` | `gpt-4o-mini` | 模型名 |
| `target_lang` | `简体中文` | 设置页下拉选择目标语言（含简/繁中、英、日、韩、俄、乌克兰语、西语、法语、德语、葡语、意语、阿拉伯语、波斯语、印地语、孟加拉语、泰语、希伯来语、希腊语、越南语、土耳其语、印尼语）；旧版列表外值继续显示并保留 |
| `extra_headers` | 空 | 额外请求头（每行一个 `Key: Value`） |
| `provider_protocol` | `openai-compatible` | 见上表 |
| `streaming_enabled` | true | Compatible 与 Anthropic Messages 使用流式请求；Responses 固定非流式 |
| `system_prompt` | 英文规则式提示词 | 只翻译、不接话、不拒译侮辱性内容、无对照示例 |
| `timeout_ms` / `max_concurrency` | 正整数 | 超时与并发上限，≤0 视为未配置 |

- `apiKey` 与 `extraHeaders` 默认值为空串；空密钥与空请求头是有效配置。
- 缺省配置键使用默认值；已保存的类型或必填值无效会使配置整体不可用。设置页不把错误快照中的占位默认值填入可保存表单：锁定保存/测试/同步并保留原存储，只有用户明确确认“重置已保存设置”才删除全部配置。Android commit 可能先改变内存再失败；保存/重置统一保存原始键快照，失败时恢复快照并仍报告失败，恢复持久化也失败时本进程阻止读取/同步，直到用户明确重试成功。首次 remote→local 导入也使用相同事务和初始化标记，不再直接提交本地配置。这是配置事务原子性，不是替换翻译实现或自动重试请求。
- `ConfigurationSync` 拥有保存/重置、首次导入与导出规则。设置页先持久化模块私有 SharedPreferences，不依赖 service 在线；本地已确认初始化后才向 remote 导出，否则首次导入。导入失败不得切换权威来源。模块进程仍只读取 remote preferences；连接由进程级唯一 listener 管理，页面可独立订阅。
- 保存通过 `commit()` 在后台执行；成功后提示 Discord 可能需要重启，因目标进程不监听 remote preferences 变更。

## 隐私与日志

- `HookDiagnostics`/入口记录安装、调用状态和异常类别，正文、译文、消息 ID、内容哈希和密钥均不写入日志。回复排障的 trace、空白统计和布局监听已经删除。
- 当前代码没有 `XLog` 或详细诊断开关；不会序列化非字符串响应作为“译文”。所选协议指定的文本字段必须是实际字符串，类型错误直接失败。
- 配置版本中凭据只以 SHA-256 指纹参与，不含原始值。