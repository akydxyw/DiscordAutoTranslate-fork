# 检测、翻译协议与配置

## 自然语言检测

`EnglishDetector` 消费同次捕获的输入：去除精确 `<xN>` 标签、以空格屏蔽 `<keepN/>`，再过滤 URL、反引号代码及自定义 emoji。必须有可提取 TEXT、至少 4 个 Unicode 字母，字母占非空白字符至少 0.5。不因存在代码块或 opaque/TEXT 节点数量比例否决混合正文。

目标文字体系命中阈值为字母的 0.2；中文目标还要求没有假名/韩文音节，日文目标遇假名即跳过。拉丁字母语言等共享文字体系无法可靠区分，纯汉字日文、短文和混合语言也存在误判。此处是脚本筛选，不是完整语言识别模型。

宿主已解析的 Annotated 代码、提及、时间戳等节点仅提供位置标记，不读取内部内容。纯保留节点没有自然语言，不触发翻译；Primitive 沿用已有反引号过滤，不保证识别所有原始 Markdown 代码形式。

## 协议与网络

| `provider_protocol` | 基础地址后追加端点 | 响应方式 |
|---|---|---|
| `openai-compatible`（默认） | `/chat/completions` | JSON `choices.0.message.content` 或 SSE delta |
| `openai-responses` | `/responses` | 固定 JSON；从 output 的 message 项取 output_text |
| `anthropic-messages` | `/messages` | JSON text 块或 SSE；请求 `max_tokens=8192` |

请求按所选协议构造，使用系统 `org.json` 和 `HttpURLConnection`。配置校验解析一次额外请求头，实际请求复用并追加协议认证头。默认提示词按目标语言生成，自定义提示词保持原文；协议层统一附加行段及保留标记规则。

每次请求只创建一个协议 decoder，不切换解析模式。`SseReader` 以空行提交完整事件，支持多行 data、event、注释和 CR/LF/CRLF。Compatible 必须收到 `[DONE]`，Anthropic 必须收到 `message_stop`；结束后不等待 EOF。缺结束事件、错误帧、截断、字段类型错误或包装污染均失败，不接受部分译文。

HTTP 累计响应最多 1 MiB 字节，decoder/SSE 累计最多 1 MiB UTF-16 单位，UTF-8 严格校验，禁止跟随重定向。非 2xx 仅读取有界 body 供错误分类，不输出 body。引擎在配置校验和构造请求体前登记 `Http.Request`，取消与连接 attach 原子协调，finally 才释放；实际 worker 是否退出由生命周期另行确认。

## 调度、缓存与错误

等待队列、状态仓库、译文缓存各有独立的 512 上限。并发实际限制为 1–8，默认 4；满队列明确失败。请求和交付都核对任务所有权，旧结果不得覆盖新消息/编辑版本。失败后只有用户点击才重新请求，无自动重试。

缓存键含输入及影响结果的配置版本；API URL、模型、目标语言、协议、流式、系统提示词、凭据指纹变化使旧结果失效。日志开关不影响请求版本、缓存或任务状态。密钥及额外请求头仅以 SHA-256 指纹参与版本编码，不以原值进入指纹字段。

错误类别为 `CONFIG / NETWORK / TIMEOUT / HTTP / RATE_LIMIT / PARSE / EMPTY / CANCELLED / UNKNOWN`。未知异常仅保留类型，服务端停止理由使用固定描述。请求校验失败不缓存；原生重建拒绝时删除对应缓存、标记失败并保留宿主原树。

## 设置与持久化

| 键 | 默认值 | 含义 |
|---|---|---|
| `auto_translate_enabled` | true | 自动翻译开关 |
| `logging_enabled` | false | 项目日志开关，不参与翻译版本 |
| `api_url` | `https://api.openai.com/v1` | 基础地址，自动追加协议端点 |
| `api_key` / `extra_headers` | 空 | 密钥及每行 `Name: Value` 的额外头 |
| `api_model` | `gpt-4o-mini` | 模型 |
| `target_lang` | `简体中文` | 目标语言；旧列表外值继续保留 |
| `provider_protocol` | `openai-compatible` | 上表三种协议之一 |
| `streaming_enabled` | true | Compatible/Anthropic 流式开关，Responses 固定非流式 |
| `system_prompt` | `Config.DEF_SYSTEM_PROMPT` | 规则式翻译提示词 |
| `timeout_ms` | 30000 | 正整数；HTTP 实际至少 1000 ms |
| `max_concurrency` | 4 | 正整数；调度器实际限制为 1–8 |

缺省键才使用默认值。显式错误类型、未知协议、必填空白或非正整数使快照无效；空密钥和空额外请求头有效。错误快照不能回填成可保存默认表单，只有用户确认重置才能删除已保存配置。

`ConfigurationSync` 是保存、重置、导入和导出的唯一策略。先持久化模块私有 SharedPreferences；本地尚未初始化时从 remote 导入，确认初始化后向 remote 导出。初始化标记随成功事务提交。`PreferenceCommit` 保存失败恢复原键快照并仍报告失败；恢复持久化也失败时本进程阻止读取/同步，直到显式重试成功，不自动更换权威来源。

设置页使用后台 `commit()`，弱订阅进程级服务连接。仅未保存草稿写入实例状态，干净页面重读权威配置；异步完成不得覆盖后续编辑。Discord 不监听 remote preferences 变化，**保存并成功同步后重启 Discord 生效**。

## 日志

`ProjectLog` 统一控制本项目 logcat 与 LSPosed 输出，包括启动、信息、警告和错误诊断。默认及无效配置均关闭；启动时先读取开关，再记录日志。关闭时不写输出、不消耗 once 去重键，重新开启可重新记录诊断。设置页只应用已确认的本地保存值，未保存草稿不切换日志；Discord 使用 remote 配置。

日志仅允许固定原因码、异常类型、必要宿主类型、结构布尔及数量。禁止正文、译文、消息 ID、hash、URL、emoji alt、凭据、文本长度/偏移、视图尺寸/位置及原异常消息。关闭日志不改变业务失败处理，也不删除既有日志或控制框架自身日志。
