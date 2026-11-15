package io.github.wxmyyds.discordautotranslate.core;

import androidx.annotation.Nullable;

import java.util.List;

/**
 * 从 Discord 的 {@code StructurableText} 结构中提取纯文本。
 *
 * <p>结构说明（Discord 340.13 已确认）：
 * <ul>
 *   <li>{@code StructurableText} 是抽象类，两个实现：
 *       {@code PrimitiveStructurableText(String content)} 与
 *       {@code AnnotatedStructurableText(List content)}。</li>
 *   <li>{@code AnnotatedStructurableText.content} 是 {@code List<ContentNode>}，
 *       节点类型位于 {@code com.discord.chat.bridge.contentnode}。</li>
 * </ul>
 *
 * <p>宿主类型和读取成员均由活动版本 profile 显式选择并精确匹配，不猜测成员或切换访问路径。</p>
 */
public final class TextExtractor {

    /** 提取结果。 */
    public static final class Result {
        /**
         * 可翻译的纯文本（含换行与 URL 链接定位标记；结构节点仅贡献空白分隔）。
         *
         * <p>它是无标记请求（单链路）的正文，也用于语言检测与内容哈希。
         * URL 链接显示文本原样进入请求充当定位标记：模型必须原样保留它，
         * 回填按它切分对齐并删除回显，显示永远用原链接节点。URL 字符
         * 不会损坏，也不会重复显示。</p>
         */
        public final String text;
        /**
         * 可翻译片段数（来自 ContentTree 捕获的分块，空白块与 URL 块不计）。
         * 仅用于把多片段消息的缓存条目与单片段
         * 消息隔离（见 HookBridge 的缓存后缀），不参与请求构造。
         */
        public final int segmentCount;
        /** 文本节点数量，用于判断消息是否包含自然语言。 */
        public final int textNodeCount;
        /** 不可解析的结构节点数量（提及、表情等）。 */
        public final int opaqueNodeCount;
        /** 是否为不可翻译结构（例如纯代码块消息）。 */
        public final boolean codeHeavy;
        /** Captured occurrence plan; Primitive input does not have a node tree. */
        public final ContentTree tree;

        Result(String text, int segmentCount, int textNodeCount,
               int opaqueNodeCount, boolean codeHeavy, ContentTree tree) {
            this.text = text;
            this.segmentCount = segmentCount;
            this.textNodeCount = textNodeCount;
            this.opaqueNodeCount = opaqueNodeCount;
            this.codeHeavy = codeHeavy;
            this.tree = tree;
        }
    }

    private static final Result EMPTY = new Result("", 0, 0, 0, false, null);

    /**
     * URL 形态判定：带 scheme 的完整 URL、www 前缀，或「无空白且以
     * 点+字母后缀（可带端口/路径）收尾」的裸域名（如 {@code gofile.io/d/xxx}）。
     *
     * <p>URL 绝不能交给模型翻译：模型会重新生成它，造成字符损坏
     * （实测 {@code IJYuv3wZ} 被改成 {@code lJYuv3wZ}）。判定只对整段文本做
     * 全匹配，普通句子（含空白）与版本号（{@code v1.2}）不会误判。</p>
     */
    private static final java.util.regex.Pattern URL_LIKE =
            java.util.regex.Pattern.compile(
                    "\\A(https?://|www\\.)\\S+\\z"
                            + "|\\A\\S+\\.[A-Za-z]{2,}(:\\d+)?(/\\S*)?\\z");

    private TextExtractor() {
    }

    /** 文本（整段）是否为 URL。是 URL 的内容不进请求、不翻译、原样保留。 */
    public static boolean isUrlLike(@Nullable String s) {
        if (s == null) {
            return false;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return false;
        }
        return URL_LIKE.matcher(t).matches();
    }

    /**
     * 取 emoji 节点在请求正文里的文本形式。
     *
     * <p>emoji 在宿主里是独立绘制节点（{@code UnicodeEmojiContentNode} /
     * {@code CustomEmojiContentNode}），旧实现只在请求里输出一个空格，模型
     * 完全看不到 emoji 的位置，译文行结构与原文对不上（实测 emoji 被挤到
     * 行尾/消息末尾）。这里把 emoji 字符（或 {@code :name:}）放进请求，让
     * 模型看到真实位置；回填时再从译文里剔除，显示仍由原节点完成，因此
     * emoji 永远是 Discord 原生绘制、位置来自节点树。</p>
     *
     * @return emoji 的文本形式；非 emoji 节点或无内容时返回 null
     */
    @Nullable
    public static String nodeEmojiText(Object node) {
        String name = HookConfiguration.semanticTypeName(node.getClass());
        if (name == null) {
            return null;
        }
        if (name.equals("UnicodeEmojiContentNode")) {
            String s = Reflect.callString(node, "getEmojiAnchor");
            return s == null || s.isEmpty() ? null : s;
        }
        if (name.equals("CustomEmojiContentNode")) {
            // 使用宿主提供的 alt 原值；可能已含 :name: 等格式，不自行改写。
            String alt = Reflect.callString(node, "getAlt");
            return alt == null || alt.isEmpty() ? null : alt;
        }
        return null;
    }

    /** Node type diagnostics consume the same captured plan; never reread host children. */
    public static String typeSummary(@Nullable Object input, @Nullable Result extracted) {
        List<String> types = new java.util.ArrayList<>();
        if (input != null) appendType(input, types);
        if (extracted != null && extracted.tree != null) {
            for (ContentTree.Event event : extracted.tree.events) {
                if (types.size() >= 24) break;
                if (event.node.original != null) appendType(event.node.original, types);
            }
        }
        return types.isEmpty() ? "empty" : String.join(",", types);
    }

    private static void appendType(Object node, List<String> types) {
        String semantic = HookConfiguration.semanticTypeName(node.getClass());
        String type = node.getClass().getName() + "="
                + (semantic == null ? "UNMAPPED" : semantic);
        if (!types.contains(type)) types.add(type);
    }

    @Nullable
    public static Result extract(@Nullable Object structurableText) {
        if (structurableText == null) {
            return null;
        }
        String cls = HookConfiguration.semanticTypeName(structurableText.getClass());
        if (cls == null) {
            return null;
        }

        if ("PrimitiveStructurableText".equals(cls)) {
            String s = Reflect.callString(structurableText, "getContent");
            if (s == null) {
                return EMPTY;
            }
            // Primitive 整体就是一个片段，不应用 Annotated 文本节点的分块规则。
            return new Result(s, s.isEmpty() ? 0 : 1, s.isEmpty() ? 0 : 1, 0, false, null);
        }

        if ("AnnotatedStructurableText".equals(cls)) {
            Object list = Reflect.callMember(structurableText, "getContent");
            if (!(list instanceof List)) {
                return EMPTY;
            }
            ContentTree tree = ContentTree.compile((List<?>) list);
            return tree == null ? null : extractTree(tree);
        }

        return null;
    }

    /**
     * 允许递归翻译的格式容器（列表节点另行处理）。
     * 提取与重建必须共享此边界：提及/频道/emoji 即使带有 content 子列表，
     * 也不能将其显示名称当成正文或译文的存放位置。
     */
    public static boolean isTextContainer(@Nullable String simpleName) {
        if (simpleName == null) {
            return false;
        }
        switch (simpleName) {
            case "ParagraphContentNode":
            case "BlockQuoteContentNode":
            case "HeadingContentNode":
            case "LHeadingContentNode":
            case "SubtextContentNode":
            case "StrongContentNode":
            case "EmphasisContentNode":
            case "UnderlineContentNode":
            case "StrikethroughContentNode":
            case "SpoilerContentNode":
            case "LinkContentNode":
                return true;
            default:
                return false;
        }
    }

    /**
     * 行内样式容器：其内的可翻译片段在请求里包样式标记
     * {@code <xN>…</xN>}（模型按提示词原样保留，回填按标记把译文
     * 精确落回样式节点，实测斜体/加粗不再丢失）。块级容器（段落/
     * 标题/引用）不在此列；其文本仍按捕获的源换行与段落边界对齐，
     * 容器名称本身不证明相邻文本之间存在换行。
     */
    public static boolean isInlineStyleContainer(@Nullable String simpleName) {
        if (simpleName == null) {
            return false;
        }
        switch (simpleName) {
            case "StrongContentNode":
            case "EmphasisContentNode":
            case "UnderlineContentNode":
            case "StrikethroughContentNode":
            case "SpoilerContentNode":
                return true;
            default:
                return false;
        }
    }

    // ------------------------------------------------- 空白边界

    /** 前导空白长度。 */
    public static int leadingWhitespace(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    /** 末尾空白之前的位置（即内容主体的结束下标，不含）。 */
    public static int trailingWhitespaceStart(String s) {
        int i = s.length();
        while (i > 0 && Character.isWhitespace(s.charAt(i - 1))) {
            i--;
        }
        return i;
    }

    /** Consume the shared occurrence stream; no host tree recursion or member rereads. */
    @Nullable
    public static Result extractTree(@Nullable ContentTree tree) {
        if (tree == null) return null;
        StringBuilder out = new StringBuilder();
        int textNodes = 0, opaque = 0, codeBlocks = 0, styleSeq = 0;
        for (ContentTree.Event event : tree.events) {
            if (event.kind == ContentTree.EventKind.ITEM_START) {
                out.append("\n• ");
                continue;
            }
            if (event.kind != ContentTree.EventKind.NODE) continue;
            ContentTree.Node node = event.node;
            switch (node.kind) {
                case TEXT:
                    String text = node.text;
                    if (text == null) break;
                    textNodes++;
                    if (!event.styled || BlankLines.isBlank(text)) {
                        out.append(text);
                        break;
                    }
                    int lead = node.leadingEnd;
                    int tail = node.trailingStart;
                    out.append(text, 0, lead);
                    for (int i = 0; i < node.chunks.size(); i++) {
                        if (i > 0) out.append(node.separators.get(i - 1));
                        String chunk = node.chunks.get(i);
                        if (!node.translatable.get(i)) out.append(chunk);
                        else {
                            styleSeq++;
                            out.append("<x").append(styleSeq).append('>').append(chunk)
                                    .append("</x").append(styleSeq).append('>');
                        }
                    }
                    out.append(text, tail, text.length());
                    break;
                case URL: case EMOJI:
                    opaque++; out.append(' ').append(node.text).append(' '); break;
                case INLINE_CODE: case OPAQUE:
                    opaque++; out.append(' '); break;
                case CODE_BLOCK:
                    codeBlocks++; out.append(' '); break;
                case BREAK:
                    out.append('\n'); break;
                default: throw new IllegalStateException("Non-leaf content event");
            }
        }
        return new Result(out.toString(), tree.segmentCount, textNodes, opaque, codeBlocks > 0, tree);
    }
}
