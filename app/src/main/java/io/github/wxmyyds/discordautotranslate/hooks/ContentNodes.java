package io.github.wxmyyds.discordautotranslate.hooks;

import io.github.wxmyyds.discordautotranslate.core.ProjectLog;

import androidx.annotation.Nullable;

import io.github.wxmyyds.discordautotranslate.core.BlankLines;
import io.github.wxmyyds.discordautotranslate.core.ContentTree;
import io.github.wxmyyds.discordautotranslate.core.HookConfiguration;
import io.github.wxmyyds.discordautotranslate.core.RetainedNodeMarkers;
import io.github.wxmyyds.discordautotranslate.core.Reflect;
import io.github.wxmyyds.discordautotranslate.core.Segments;
import io.github.wxmyyds.discordautotranslate.core.TextExtractor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Discord 消息内容结构的反射访问层（需求 §7 阶段 3）。
 *
 * <p>Hook 配置选择版本对应的文本转换入口。已核对的渲染器接受两类正文：
 * {@code AnnotatedStructurableText} 遍历 {@code List<ContentNode>}，
 * {@code PrimitiveStructurableText} 按纯文本渲染；其他类型会抛出异常。
 * 本类保留 Annotated 结构，只替换自然语言文本节点，其他结构节点原样保留。</p>
 *
 * <p>节点重建策略由版本配置确定：保留公开 Kotlin data class 方法的版本用
 * {@code componentN()/copy(...)}；混淆节点通过 profile 的 {@code nodeConstructors}
 * 精确声明参数类型及参数来源，只调用选定构造器。未知类型不猜测。
 * 只遍历与 {@link TextExtractor} 相同的格式容器；提及、频道、emoji 和未知节点
 * 即使含有子列表也整节点保留，不参与译文分配。</p>
 */
public final class ContentNodes {

    private ContentNodes() {
    }

    // ---------------------------------------------------------------- 类型判断

    public static boolean isAnnotated(@Nullable Object structurableText) {
        return structurableText != null && "AnnotatedStructurableText".equals(
                HookConfiguration.semanticTypeName(structurableText.getClass()));
    }

    public static boolean isPrimitive(@Nullable Object structurableText) {
        return structurableText != null && "PrimitiveStructurableText".equals(
                HookConfiguration.semanticTypeName(structurableText.getClass()));
    }

    // ---------------------------------------------------------------- 变换

    /** Fixed classifications only; never carries source text, translation, anchors or identity. */
    enum RejectionReason {
        INVALID_INPUT, NO_TRANSLATABLE_SEGMENTS, SEGMENT_MARKER, EMPTY_TRANSLATION,
        URL_COUNT, EMOJI_COUNT, RETAINED_MARKER, ANCHOR_ORDER, STYLE_OPEN, STYLE_CLOSE, STYLE_EMPTY,
        UNASSIGNED_TEXT, PARAGRAPH_MISSING, PARAGRAPH_EXTRA, SOURCE_BOUNDARY_AMBIGUOUS,
        LINE_COUNT, LINE_EMPTY, LIST_ITEM_EMPTY, PART_COUNT,
        RETAINED_STRUCTURE_CHANGED, TEXT_CONSTRUCTOR, CONTAINER_CONSTRUCTOR, CONSUMPTION_COUNT
    }

    static final class Rejection extends RuntimeException {
        final RejectionReason reason;
        final int expected;
        final int actual;
        /** Interval-local right-slot ordinal; not a message identity or text offset. */
        int boundary = -1;
        /** Interval-local failing connected line run, source slot ordinals only. */
        int blockStart = -1;
        int blockEnd = -1;
        String structure = "";

        Rejection(RejectionReason reason, int expected, int actual) {
            super(reason.name());
            this.reason = reason;
            this.expected = expected;
            this.actual = actual;
        }
    }

    private static Rejection reject(RejectionReason reason) {
        return new Rejection(reason, -1, -1);
    }

    /** Rebuild only from the caller's captured, identity-verified occurrence plan. */
    @Nullable
    public static List<Object> replacePlanned(ContentTree tree, String translated) {
        try {
            return replaceChecked(tree, translated);
        } catch (Rejection rejection) {
            ProjectLog.info("content replacement rejected reason=" + rejection.reason.name()
                    + " expected=" + rejection.expected + " actual=" + rejection.actual
                    + rejection.structure);
            return null;
        }
    }

    /** The same operation before its null-return boundary, also inspected by JVM tests. */
    static List<Object> replaceChecked(ContentTree tree, String translated) {
        if (tree == null || translated == null) throw reject(RejectionReason.INVALID_INPUT);
        if (translated.trim().isEmpty()) throw reject(RejectionReason.EMPTY_TRANSLATION);
        int targetCount = tree.segmentCount;
        if (targetCount == 0) {
            throw reject(RejectionReason.NO_TRANSLATABLE_SEGMENTS);
        }
        // 锚点必须按原数量及顺序出现；各间隔只执行所属结构的唯一切分规则。
        if (Segments.hasSegmentMarkers(translated)) {
            throw reject(RejectionReason.SEGMENT_MARKER);
        }
        String plain = normalize(translated);
        if (plain.isEmpty()) throw reject(RejectionReason.EMPTY_TRANSLATION);
        AlignmentPlan plan = buildPlan(tree);
        List<String> parts = alignTranslation(plain, plan);
        if (parts.size() != targetCount) {
            throw new Rejection(RejectionReason.PART_COUNT, targetCount, parts.size());
        }
        // Do not allow reserved fragments split across adjacent styled text slots to reach rendering.
        if (RetainedNodeMarkers.hasReservedPrefix(String.join("", parts)))
            throw reject(RejectionReason.RETAINED_MARKER);
        // 按节点出现的位置分配片段，不能以实例身份为键：共享节点的后一次
        // 空片段会覆盖前一次译文，造成文字全部消失但 emoji 仍保留。
        Replacement replacement = new Replacement(parts);
        List<Object> result = new ArrayList<>(tree.root.children.size());
        for (ContentTree.Node occurrence : tree.root.children) {
            result.add(rebuild(occurrence, replacement));
        }
        // 容器复制失败可能丢弃已重建的子节点；不得把其他已清空的文本节点
        // 作为部分成功的结果交给宿主，必须整棵树替换成功。
        if (replacement.next != targetCount) {
            throw new Rejection(RejectionReason.CONSUMPTION_COUNT, targetCount, replacement.next);
        }
        return result;
    }

    /**
     * 统一换行符并去掉首尾空白：模型常以换行收尾（开头偶尔也会多一行），
     * 直接渲染就会在译文上下方留下多余空行。正文中间的空行保持不变。
     */
    static String normalize(String translated) {
        return translated.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private static final class Replacement {
        final List<String> parts;
        int next;

        Replacement(List<String> parts) {
            this.parts = parts;
        }
    }

    /**
     * 重建单个节点：递归处理子列表，并把命中的文本节点替换为译文。
     */
    private static Object rebuild(ContentTree.Node occurrence, Replacement replacement) {
        Object node = occurrence.original;
        if (node == null) return null;
        if (occurrence.kind == ContentTree.Kind.SEQUENCE) {
            List<ContentTree.Node> children = occurrence.children;
            List<Object> rebuiltChildren = new ArrayList<>(children.size());
            boolean localChanged = false;
            for (ContentTree.Node child : children) {
                Object rebuiltChild = rebuild(child, replacement);
                if (rebuiltChild != child.original) {
                    localChanged = true;
                }
                rebuiltChildren.add(rebuiltChild);
            }
            if (!localChanged && !ContentTree.retainedStructureMatches(occurrence))
                throw reject(RejectionReason.RETAINED_STRUCTURE_CHANGED);
            return localChanged ? rebuiltChildren : node;
        }

        ContentTree.Kind kind = occurrence.kind;

        // 按出现位置消费译文片段，同一实例在不同位置可以承载不同片段。
        if (kind == ContentTree.Kind.TEXT) {
            String original = occurrence.text;
            if (original == null) {
                return node;
            }
            int needed = occurrence.segmentCount;
            if (needed == 0) {
                // 空白/空行结构节点：不参与翻译，原样保留。
                return node;
            }
            if (replacement.next + needed > replacement.parts.size()) {
                throw new Rejection(RejectionReason.CONSUMPTION_COUNT,
                        replacement.parts.size(), replacement.next + needed);
            }
            // 保留首尾空白，并按原空行位置拼回译文片段：
            // 段落分隔由原节点提供，不依赖模型是否保留。
            int lead = occurrence.leadingEnd;
            int tail = occurrence.trailingStart;
            StringBuilder rebuilt = new StringBuilder();
            rebuilt.append(original, 0, lead);
            for (int i = 0; i < occurrence.chunks.size(); i++) {
                if (i > 0) rebuilt.append(occurrence.separators.get(i - 1));
                String chunk = occurrence.chunks.get(i);
                if (!occurrence.translatable.get(i)) {
                    // 空白块或 URL 块不消费片段，原样保留（与计数口径一致）。
                    rebuilt.append(chunk);
                    continue;
                }
                rebuilt.append(replacement.parts.get(replacement.next++));
            }
            rebuilt.append(original, tail, original.length());
            Object created = createTextNode(node, rebuilt.toString());
            if (created != null) {
                return created;
            }
            throw reject(RejectionReason.TEXT_CONSTRUCTOR);
        }

        if (kind != ContentTree.Kind.CONTAINER && kind != ContentTree.Kind.LIST) {
            if (!ContentTree.retainedStructureMatches(occurrence))
                throw reject(RejectionReason.RETAINED_STRUCTURE_CHANGED);
            return node;
        }
        List<ContentTree.Node> children = occurrence.children;

        List<Object> newChildren = new ArrayList<>(children.size());
        boolean localChanged = false;
        for (ContentTree.Node c : children) {
            Object nc = rebuild(c, replacement);
            if (nc != c.original) {
                localChanged = true;
            }
            newChildren.add(nc);
        }
        if (!localChanged) {
            if (!ContentTree.retainedStructureMatches(occurrence))
                throw reject(RejectionReason.RETAINED_STRUCTURE_CHANGED);
            return node;
        }
        Object rebuilt = copyWith(node, newChildren);
        if (rebuilt == null) throw reject(RejectionReason.CONTAINER_CONSTRUCTOR);
        return rebuilt;
    }

    /**
     * 按当前版本配置选择节点重建方案：公开 data class 使用 {@code copy(...)}，
     * 混淆类型使用 profile 声明的构造器；只替换子节点列表，其余字段保持原值。
     */
    @Nullable
    private static Object copyWith(Object node, List<Object> newChildren) {
        Class<?> cls = node.getClass();
        HookConfiguration configuration = HookConfiguration.activeConfiguration();
        String semanticType = HookConfiguration.semanticTypeName(cls);
        org.json.JSONObject constructor = configuration == null || semanticType == null
                ? null : configuration.nodeConstructor(semanticType);
        if (constructor != null) {
            return constructWithReplacedChildren(node, newChildren, constructor);
        }
        Method copy = findCopyMethod(cls);
        if (copy == null) {
            return null;
        }
        // 逐个参数：内容字段用新列表，其他字段用 componentN 原值。
        Class<?>[] types = copy.getParameterTypes();
        Object[] args = new Object[types.length];
        int contentIndex = -1;
        for (int i = 0; i < types.length; i++) {
            if (List.class.isAssignableFrom(types[i]) && contentIndex < 0) {
                contentIndex = i;
                args[i] = newChildren;
                continue;
            }
            args[i] = component(node, i + 1);
        }
        if (contentIndex < 0) {
            return null;
        }
        return Reflect.call(copy, node, args);
    }

    /** Invoke exactly the profile-selected constructor; a failure rejects the entire rebuild. */
    @Nullable
    private static Object constructWithReplacedChildren(Object node, List<Object> newChildren,
                                                        org.json.JSONObject specification) {
        Class<?> cls = node.getClass();
        try {
            org.json.JSONArray configuredTypes = specification.getJSONArray("parameterTypes");
            org.json.JSONArray configuredArguments = specification.getJSONArray("arguments");
            Class<?>[] parameterTypes = new Class<?>[configuredTypes.length()];
            for (int i = 0; i < parameterTypes.length; i++) {
                parameterTypes[i] = constructorParameterType(
                        configuredTypes.getString(i), cls.getClassLoader());
            }
            java.lang.reflect.Constructor<?> constructor =
                    cls.getDeclaredConstructor(parameterTypes);
            Object[] arguments = new Object[parameterTypes.length];
            for (int i = 0; i < arguments.length; i++) {
                org.json.JSONObject argument = configuredArguments.getJSONObject(i);
                switch (argument.getString("kind")) {
                    case "integer":
                        arguments[i] = argument.getInt("value");
                        break;
                    case "children":
                        arguments[i] = newChildren;
                        break;
                    case "field":
                        java.lang.reflect.Field field =
                                cls.getDeclaredField(argument.getString("name"));
                        field.setAccessible(true);
                        arguments[i] = field.get(node);
                        break;
                    default:
                        return null;
                }
            }
            constructor.setAccessible(true);
            return constructor.newInstance(arguments);
        } catch (Throwable failure) {
            return null; // Classified at the one replacement boundary, never reports arguments.
        }
    }

    private static Class<?> constructorParameterType(String name, ClassLoader classLoader)
            throws ClassNotFoundException {
        switch (name) {
            case "boolean": return boolean.class;
            case "byte": return byte.class;
            case "short": return short.class;
            case "int": return int.class;
            case "long": return long.class;
            case "float": return float.class;
            case "double": return double.class;
            case "char": return char.class;
            default: return Class.forName(name, false, classLoader);
        }
    }

    /** 新建承载译文的文本节点，类型跟随原节点（保持 TextContentNode 语义）。 */
    @Nullable
    private static Object createTextNode(Object original, String text) {
        try {
            java.lang.reflect.Constructor<?> ctor =
                    original.getClass().getDeclaredConstructor(String.class);
            ctor.setAccessible(true);
            return ctor.newInstance(text);
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    private static Method findCopyMethod(Class<?> cls) {
        for (Method m : cls.getDeclaredMethods()) {
            if ("copy".equals(m.getName()) && m.getParameterCount() > 0) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    /** 取 Kotlin data class 的第 n 个字段（componentN 从 1 开始）。 */
    @Nullable
    private static Object component(Object node, int n) {
        Method m = Reflect.findMethodExact(node.getClass(), "component" + n, 0);
        return Reflect.call(m, node);
    }

    // ---------------------------------------------------------------- 译文切分

    static List<String> alignTranslation(String plain, AlignmentPlan plan) {
        if (plan.groupIds.isEmpty()) throw reject(RejectionReason.NO_TRANSLATABLE_SEGMENTS);
        requireAnchorCounts(plain, plan.urls, RejectionReason.URL_COUNT);
        requireAnchorCounts(plain, plan.emojiAnchors, RejectionReason.EMOJI_COUNT);
        if (!RetainedNodeMarkers.matchesExpected(plain, plan.retainedMarkers))
            throw reject(RejectionReason.RETAINED_MARKER);
        if (plan.hasStyleMarkers()) {
            return alignByStyleMarkers(plain, plan);
        }
        return alignTokens(plain, plan);
    }

    /**
     * 按样式标记扫描译文：树序遍历令牌流，样式片段的译文取自
     * {@code <xN>…</xN>} 之间，其余文本按标记间隔分配给相邻的普通片段。
     * 任何标记缺失、错位或空内容都会拒绝整条回填。
     */
    private static List<String> alignByStyleMarkers(String plain, AlignmentPlan plan) {
        List<String> parts = new ArrayList<>(
                java.util.Collections.nCopies(plan.groupIds.size(), ""));
        List<Integer> pending = new ArrayList<>();
        int cursor = 0;
        for (PlanToken t : plan.tokens) {
            if (t.anchor != null) {
                int at = plain.indexOf(t.anchor, cursor);
                if (at < 0) throw reject(RejectionReason.ANCHOR_ORDER);
                String prefix = anchorPrefix(plain.substring(cursor, at), t);
                assignRun(prefix, pending, parts, plan);
                pending.clear();
                cursor = at + t.anchor.length();
            } else if (t.styleId > 0) {
                String open = "<x" + t.styleId + ">";
                int at = plain.indexOf(open, cursor);
                if (at < 0) throw reject(RejectionReason.STYLE_OPEN);
                assignRun(plain.substring(cursor, at), pending, parts, plan);
                pending.clear();
                String close = "</x" + t.styleId + ">";
                int end = plain.indexOf(close, at + open.length());
                if (end < 0) throw reject(RejectionReason.STYLE_CLOSE);
                String styled = plain.substring(at + open.length(), end).trim();
                if (styled.isEmpty()) throw reject(RejectionReason.STYLE_EMPTY);
                if (t.segIndex < plan.listFlags.size()
                        && Boolean.TRUE.equals(plan.listFlags.get(t.segIndex))) {
                    styled = stripListMarker(styled);
                }
                parts.set(t.segIndex, styled);
                cursor = end + close.length();
            } else {
                pending.add(t.segIndex);
            }
        }
        assignRun(plain.substring(cursor), pending, parts, plan);
        return parts;
    }

    /** 把一段结构对应的译文分配给待填片段；结构不符时拒绝整条回填。 */
    private static void assignRun(String run, List<Integer> pending,
                                  List<String> parts, AlignmentPlan plan) {
        if (pending.isEmpty()) {
            if (!run.trim().isEmpty()) throw reject(RejectionReason.UNASSIGNED_TEXT);
            return;
        }
        int first = pending.get(0);
        int last = pending.get(pending.size() - 1);
        List<String> runParts = alignInterval(run, plan, first, last + 1);
        for (int i = 0; i < runParts.size(); i++) {
            String p = runParts.get(i);
            if (p != null && !p.trim().isEmpty()) {
                parts.set(pending.get(i), p);
            }
        }
    }

    /**
     * 写入已完成的一一对齐结果。
     */
    private static void copySpan(List<String> spanParts, List<String> parts, int segStart) {
        for (int i = 0; i < spanParts.size(); i++) {
            String p = spanParts.get(i);
            if (p != null && !p.trim().isEmpty()) {
                parts.set(segStart + i, p);
            }
        }
    }

    /** Verify multiplicity without ever emitting the anchor itself. */
    private static void requireAnchorCounts(String plain, List<String> anchors,
                                             RejectionReason reason) {
        java.util.Map<String, Integer> expected = new java.util.LinkedHashMap<>();
        for (String anchor : anchors) expected.merge(anchor, 1, Integer::sum);
        for (java.util.Map.Entry<String, Integer> entry : expected.entrySet()) {
            int actual = countOccurrences(plain, entry.getKey());
            if (actual != entry.getValue()) throw new Rejection(reason, entry.getValue(), actual);
        }
    }

    /**
     * 按树序令牌流切分译文并对齐片段。
     *
     * <p>定位标记（URL / emoji / 保留叶节点）不消费译文片段。按树序依次定位标记，
     * 标记之间的文本交给 {@link #alignSpanToParts} 按结构类型唯一切分。</p>
     */
    private static List<String> alignTokens(String plain, AlignmentPlan plan) {
        boolean hasAnchors = !plan.urls.isEmpty() || !plan.emojiAnchors.isEmpty()
                || !plan.retainedMarkers.isEmpty();
        if (!hasAnchors) {
            return alignInterval(plain, plan, 0, plan.groupIds.size());
        }
        // 逐个令牌定位。锚点只切间隔、不产出片段；片段在其后的间隔里分配。
        List<String> parts = new ArrayList<>(
                java.util.Collections.nCopies(plan.groupIds.size(), ""));
        int from = 0;
        int segStart = 0;
        for (PlanToken t : plan.tokens) {
            if (t.anchor == null) {
                continue;
            }
            String anchor = t.anchor;
            int at = plain.indexOf(anchor, from);
            if (at < 0) throw reject(RejectionReason.ANCHOR_ORDER);
            int segEnd = t.segIndex;
            String prefix = anchorPrefix(plain.substring(from, at), t);
            List<String> spanParts = alignInterval(prefix, plan, segStart, segEnd);
            copySpan(spanParts, parts, segStart);
            from = at + anchor.length();
            segStart = segEnd;
        }
        List<String> tailParts = alignInterval(plain.substring(from), plan,
                segStart, plan.groupIds.size());
        copySpan(tailParts, parts, segStart);
        return parts;
    }

    /** A list item can begin with a retained leaf; consume its native item prefix, never body text. */
    private static String anchorPrefix(String prefix, PlanToken token) {
        if (!token.listPrefix) return prefix;
        int tail = prefix.length();
        while (tail > 0 && Character.isWhitespace(prefix.charAt(tail - 1))) tail--;
        int lineStart = prefix.lastIndexOf('\n', tail - 1) + 1;
        // Native source LF/BREAK may lie between ITEM_START and its first anchor.
        // Only a marker followed entirely by whitespace can be consumed, never item body text.
        if (!LIST_MARKER.matcher(prefix.substring(lineStart)).matches()) return prefix;
        int lineEnd = prefix.indexOf('\n', lineStart);
        return prefix.substring(0, lineStart) + (lineEnd < 0 ? "" : prefix.substring(lineEnd));
    }

    /** One interval operation; decoration consumes captured structure only, never the response. */
    private static List<String> alignInterval(String span, AlignmentPlan plan, int start, int end) {
        try {
            return alignSpanToParts(span, plan.groupIds.subList(start, end),
                    plan.listFlagsIn(start, end), plan.lineBreakFlagsIn(start, end),
                    plan.sourceLinesIn(start, end));
        } catch (Rejection rejection) {
            if (rejection.reason == RejectionReason.SOURCE_BOUNDARY_AMBIGUOUS) {
                int right = start + rejection.boundary;
                if (right > start && right < end) {
                    BoundaryContext leftContext = plan.boundaries.get(right - 1);
                    BoundaryContext rightContext = plan.boundaries.get(right);
                    rejection.structure = " intervalSlots=" + (end - start)
                            + " leftSlot=" + (right - 1) + " rightSlot=" + right
                            + leftContext.describe("left") + rightContext.describe("right")
                            + " sameContainerPath=" + leftContext.samePath(rightContext)
                            + " sameTextOccurrence=" + (leftContext.event.node == rightContext.event.node);
                }
            } else if (rejection.reason == RejectionReason.LINE_COUNT
                    && rejection.blockStart >= 0 && rejection.blockEnd > rejection.blockStart) {
                int first = start + rejection.blockStart;
                int last = start + rejection.blockEnd - 1;
                if (first >= start && last < end) {
                    rejection.structure = " intervalSlots=" + (end - start)
                            + " runSlots=" + (last - first + 1)
                            + " firstSlot=" + first + " lastSlot=" + last
                            + plan.boundaries.get(first).describe("first")
                            + plan.boundaries.get(last).describe("last");
                }
            }
            throw rejection;
        }
    }

    private static int countOccurrences(String text, String needle) {
        int n = 0;
        int at = 0;
        while ((at = text.indexOf(needle, at)) >= 0) {
            n++;
            at += needle.length();
        }
        return n;
    }

    /**
     * 按结构类型执行唯一切分：源换行/列表边界按每槽捕获行数，其余按段落块对应。
     * 任一数量或结构不匹配都拒绝整条回填。
     */
    static List<String> alignSpanToParts(String span, List<Integer> groups,
                                         @Nullable List<Boolean> listFlags,
                                         @Nullable List<Boolean> lineBreakBefore,
                                         List<Integer> sourceLineCounts) {
        int segments = groups.size();
        if (segments == 0) {
            if (!span.trim().isEmpty()) throw reject(RejectionReason.UNASSIGNED_TEXT);
            return new ArrayList<>();
        }
        List<String> chunks = translatableChunks(span);
        List<String> parts = new ArrayList<>(
                java.util.Collections.nCopies(segments, ""));
        int segment = 0;
        int chunk = 0;
        while (segment < segments) {
            if (chunk >= chunks.size()) {
                throw new Rejection(RejectionReason.PARAGRAPH_MISSING, chunk + 1, chunks.size());
            }
            int end = segment + 1;
            while (end < segments) {
                if (isLineBreakBefore(lineBreakBefore, end)) {
                    end++;
                } else if (groups.get(end).equals(groups.get(end - 1))) {
                    Rejection rejection = reject(RejectionReason.SOURCE_BOUNDARY_AMBIGUOUS);
                    rejection.boundary = end;
                    throw rejection;
                } else {
                    break;
                }
            }

            String block = chunks.get(chunk++);
            if (end - segment > 1) {
                String[] lines = block.split("\n", -1);
                int expectedLines = 0;
                for (int i = segment; i < end; i++) expectedLines += sourceLineCounts.get(i);
                if (lines.length != expectedLines) {
                    Rejection rejection = new Rejection(RejectionReason.LINE_COUNT, expectedLines, lines.length);
                    rejection.blockStart = segment;
                    rejection.blockEnd = end;
                    throw rejection;
                }
                for (String line : lines) {
                    if (line.trim().isEmpty()) throw reject(RejectionReason.LINE_EMPTY);
                }
                List<String> lineValues = java.util.Arrays.asList(lines);
                int offset = 0;
                for (int i = segment; i < end; i++) {
                    int count = sourceLineCounts.get(i);
                    String value = String.join("\n", lineValues.subList(offset, offset + count));
                    if (isListSegment(listFlags, i)) value = stripListMarker(value);
                    parts.set(i, value);
                    offset += count;
                }
            } else {
                String value = block;
                if (isListSegment(listFlags, segment)) value = stripListMarker(value);
                parts.set(segment, value);
            }
            segment = end;
        }
        if (chunk != chunks.size()) {
            throw new Rejection(RejectionReason.PARAGRAPH_EXTRA, chunk, chunks.size());
        }
        return parts;
    }

    private static boolean isListSegment(@Nullable List<Boolean> listFlags, int index) {
        return listFlags != null && index < listFlags.size()
                && Boolean.TRUE.equals(listFlags.get(index));
    }

    private static boolean isLineBreakBefore(@Nullable List<Boolean> flags, int index) {
        return flags != null && index < flags.size() && Boolean.TRUE.equals(flags.get(index));
    }

    /** 行首项目符号：{@code • }、{@code - }、{@code 1. } 等（需有分隔空白）。 */
    private static final java.util.regex.Pattern LIST_MARKER =
            java.util.regex.Pattern.compile(
                    "^\\s*(?:[-*+•◦·▪▫]\\s+|\\d+[.)]\\s+)");

    /**
     * 剔除行首项目符号。若剥除后为空，拒绝本次回填。
     */
    private static String stripListMarker(String line) {
        if (line == null || line.isEmpty()) {
            return line;
        }
        String stripped = LIST_MARKER.matcher(line).replaceFirst("");
        if (stripped.trim().isEmpty()) throw reject(RejectionReason.LIST_ITEM_EMPTY);
        return stripped;
    }

    /**
     * 译文 interval 的段落块：仅剔除空白块。源 URL 已由定位 token 消费，
     * 剩余 URL 块也是输出，必须参与段落数量校验，不能静默丢弃。
     */
    private static List<String> translatableChunks(String translated) {
        List<String> chunks = new ArrayList<>();
        for (String chunk : BlankLines.split(translated).chunks) {
            String trimmed = chunk.trim();
            if (!trimmed.isEmpty()) {
                chunks.add(trimmed);
            }
        }
        return chunks;
    }

    /**
     * 回填对齐计划：按树序枚举可翻译片段及必须精确保留的定位/样式标记。
     *
     * <p>{@code groupIds} 与片段消费顺序一致；URL、emoji 按原数量和顺序保留，
     * 样式片段保留对应的 {@code <xN>} 标记。</p>
     */
    static final class AlignmentPlan {
        /** 每个可翻译片段的段落组号（0 起，与片段枚举同序）。 */
        final List<Integer> groupIds = new ArrayList<>();
        /** 与 {@code groupIds} 平行：该片段是否位于列表项内。 */
        final List<Boolean> listFlags = new ArrayList<>();
        /** 与片段平行：当前片段前是否有已捕获的源换行（文本空白或 BREAK）。 */
        final List<Boolean> lineBreakBefore = new ArrayList<>();
        /** 与片段平行：该片段的源文本内部行数，不能假定每个槽只占一行。 */
        final List<Integer> sourceLineCounts = new ArrayList<>();
        /** Source-only structural diagnostics for each slot; discarded with this alignment plan. */
        final List<BoundaryContext> boundaries = new ArrayList<>();
        /** 按树序的 URL 定位标记（原样 URL 文本）。 */
        final List<String> urls = new ArrayList<>();
        /** Content-free retained-leaf anchors from captured occurrences. */
        final List<String> retainedMarkers = new ArrayList<>();
        /** 按树序的 emoji 定位标记：Unicode 字符或宿主提供的自定义 emoji alt。 */
        final List<String> emojiAnchors = new ArrayList<>();
        /** 按树序的定位与可翻译片段令牌。 */
        final List<PlanToken> tokens = new ArrayList<>();
        /** 样式标记总数（>0 表示请求里含 {@code <xN>} 标记）。 */
        int styleMarkers;

        boolean hasStyleMarkers() {
            return styleMarkers > 0;
        }

        List<Boolean> lineBreakFlagsIn(int segStart, int segEnd) {
            return lineBreakBefore.subList(segStart, segEnd);
        }

        List<Integer> sourceLinesIn(int segStart, int segEnd) {
            return sourceLineCounts.subList(segStart, segEnd);
        }

        /** [segStart, segEnd) 范围片段对应的列表项标记子列表。 */
        List<Boolean> listFlagsIn(int segStart, int segEnd) {
            return listFlags.subList(segStart, segEnd);
        }
    }

    /** Only captured kinds, type names, structural counts and occurrence-path equality reach logs. */
    private static final class BoundaryContext {
        final ContentTree.Event event;
        final int sourceLf;
        final int headingBreaks;
        final int sourceLines;
        final int listDepth;
        final int[] gapKinds;
        final int whitespaceNodes;
        final int itemStarts;
        final int listStarts;
        final int listEnds;
        final List<String> gapTypes;
        final boolean gapTypesTruncated;

        BoundaryContext(ContentTree.Event event, PlanWalk walk, int sourceLines) {
            this.event = event;
            this.sourceLf = walk.separatorNewlines - walk.headingBreaks;
            this.headingBreaks = walk.headingBreaks;
            this.sourceLines = sourceLines;
            this.listDepth = walk.listLevel;
            this.gapKinds = walk.gapKinds.clone();
            this.whitespaceNodes = walk.whitespaceNodes;
            this.itemStarts = walk.itemStarts;
            this.listStarts = walk.listStarts;
            this.listEnds = walk.listEnds;
            this.gapTypes = new ArrayList<>(walk.gapTypes);
            this.gapTypesTruncated = walk.gapTypesTruncated;
        }

        boolean samePath(BoundaryContext other) {
            return event.containers.equals(other.event.containers);
        }

        String describe(String side) {
            List<String> path = new ArrayList<>();
            for (ContentTree.Node container : event.containers) {
                path.add(container.semantic == null ? "UNKNOWN_CONTAINER" : container.semantic);
            }
            return " " + side + "Type=" + event.node.original.getClass().getName()
                    + " " + side + "Path=" + (path.isEmpty() ? "ROOT" : String.join("/", path))
                    + " " + side + "Styled=" + event.styled
                    + " " + side + "ListDepth=" + listDepth
                    + " " + side + "SourceLines=" + sourceLines
                    + " " + side + "SourceLfBefore=" + sourceLf
                    + " " + side + "GapHeadingBreaks=" + headingBreaks
                    + " " + side + "GapWhitespaceNodes=" + whitespaceNodes
                    + " " + side + "GapInlineCode=" + gapKinds[ContentTree.Kind.INLINE_CODE.ordinal()]
                    + " " + side + "GapOpaque=" + gapKinds[ContentTree.Kind.OPAQUE.ordinal()]
                    + " " + side + "GapCodeBlocks=" + gapKinds[ContentTree.Kind.CODE_BLOCK.ordinal()]
                    + " " + side + "GapUrls=" + gapKinds[ContentTree.Kind.URL.ordinal()]
                    + " " + side + "GapEmoji=" + gapKinds[ContentTree.Kind.EMOJI.ordinal()]
                    + " " + side + "GapBreaks=" + gapKinds[ContentTree.Kind.BREAK.ordinal()]
                    + " " + side + "GapListStarts=" + listStarts
                    + " " + side + "GapItemStarts=" + itemStarts
                    + " " + side + "GapListEnds=" + listEnds
                    + " " + side + "GapTypes=" + (gapTypes.isEmpty() ? "NONE" : String.join(",", gapTypes))
                    + " " + side + "GapTypesTruncated=" + gapTypesTruncated;
        }
    }

    /** One tree-order token representation for URL, emoji, retained-leaf anchors and text slots. */
    static final class PlanToken {
        final String anchor; // Non-null anchors consume no text slot.
        final int segIndex; // Anchor boundary or text-slot index, captured in tree order.
        final int styleId;
        final boolean listPrefix;

        PlanToken(String anchor, int segIndex, boolean listPrefix) {
            this.anchor = anchor;
            this.segIndex = segIndex;
            this.styleId = 0;
            this.listPrefix = listPrefix;
        }

        PlanToken(int segIndex, int styleId) {
            this.anchor = null;
            this.segIndex = segIndex;
            this.styleId = styleId;
            this.listPrefix = false;
        }
    }

    private static AlignmentPlan buildPlan(ContentTree tree) {
        PlanWalk w = new PlanWalk();
        java.util.ArrayDeque<Integer> listStarts = new java.util.ArrayDeque<>();
        for (ContentTree.Event event : tree.events) {
            if (event.headingBreakBefore) {
                w.separatorNewlines++;
                w.headingBreaks++;
            }
            switch (event.kind) {
                case LIST_START:
                    w.listStarts++;
                    listStarts.push(w.plan.groupIds.size());
                    continue;
                case ITEM_START:
                    w.itemStarts++;
                    w.pendingListPrefix = true;
                    if (!w.plan.groupIds.isEmpty()) w.forceNewGroup = true;
                    w.separatorNewlines++; // Native list-item line boundary, not opaque text.
                    w.listLevel++;
                    continue;
                case ITEM_END:
                    w.pendingListPrefix = false;
                    w.listLevel--;
                    continue;
                case LIST_END:
                    w.listEnds++;
                    if (w.plan.groupIds.size() > listStarts.pop()) w.forceNewGroup = true;
                    continue;
                case NODE: break;
                default: throw new IllegalStateException("Unknown structure event");
            }
            ContentTree.Node node = event.node;
            if (node.kind == ContentTree.Kind.TEXT) {
                String s = node.text;
                if (s == null) {
                    continue;
                }
                if (BlankLines.isBlank(s)) {
                    w.whitespaceNodes++;
                    addSourceWhitespace(w, s, 0, s.length());
                    continue;
                }
                int lead = node.leadingEnd;
                int tail = node.trailingStart;
                addSourceWhitespace(w, s, 0, lead);
                for (int i = 0; i < node.chunks.size(); i++) {
                    if (i > 0) {
                        String separator = node.separators.get(i - 1);
                        addSourceWhitespace(w, separator, 0, separator.length());
                    }
                    String chunk = node.chunks.get(i);
                    if (BlankLines.isBlank(chunk)) {
                        addSourceWhitespace(w, chunk, 0, chunk.length());
                        continue;
                    }
                    if (TextExtractor.isUrlLike(chunk)) {
                        // 节点内的 URL 文本块：定位标记，与链接节点同口径。
                        w.gapKinds[ContentTree.Kind.URL.ordinal()]++;
                        recordGapType(w, node);
                        addUrlToken(w, chunk.trim());
                        continue;
                    }
                    int segIndex = w.plan.groupIds.size();
                    addSegment(w, event, node.chunkLineCounts.get(i));
                    int styleId = 0;
                    if (event.styled) {
                        styleId = ++w.plan.styleMarkers;
                    }
                    w.plan.tokens.add(new PlanToken(segIndex, styleId));
                }
                addSourceWhitespace(w, s, tail, s.length());
                continue;
            }
            w.gapKinds[node.kind.ordinal()]++;
            recordGapType(w, node);
            switch (node.kind) {
                case INLINE_CODE: case OPAQUE: case CODE_BLOCK:
                    w.plan.retainedMarkers.add(event.retainedMarker);
                    addAnchor(w, event.retainedMarker);
                    break;
                case URL: addUrlToken(w, node.text); break;
                case EMOJI: addEmojiToken(w, node.text); break;
                case BREAK: w.separatorNewlines++; break;
                default: throw new IllegalStateException("Non-leaf content event");
            }
        }
        return w.plan;
    }

    private static final class PlanWalk {
        final AlignmentPlan plan = new AlignmentPlan();
        int group;
        int separatorNewlines;
        int headingBreaks;
        int listLevel;
        boolean forceNewGroup;
        boolean pendingListPrefix;
        final int[] gapKinds = new int[ContentTree.Kind.values().length];
        int whitespaceNodes;
        int itemStarts;
        int listStarts;
        int listEnds;
        final java.util.Set<String> gapTypes = new java.util.LinkedHashSet<>();
        boolean gapTypesTruncated;
    }

    private static void recordGapType(PlanWalk walk, ContentTree.Node node) {
        if (node.original == null) return;
        String type = node.original.getClass().getName();
        if (walk.gapTypes.contains(type)) return;
        if (walk.gapTypes.size() < 4) walk.gapTypes.add(type);
        else walk.gapTypesTruncated = true;
    }

    /**
     * 记录一个 emoji 定位标记。
     *
     * <p>emoji 本身不消费译文片段（显示由原节点完成），但它在请求正文里
     * 占一个位置，因此把它当作与 URL 同级的定位标记：对齐时按 emoji 切分
     * 译文间隔，每个间隔各自对齐。</p>
     */
    private static void addEmojiToken(PlanWalk w, String emoji) {
        w.plan.emojiAnchors.add(emoji);
        addAnchor(w, emoji);
    }

    private static void addUrlToken(PlanWalk w, String url) {
        w.plan.urls.add(url);
        addAnchor(w, url);
    }

    private static void addAnchor(PlanWalk walk, String anchor) {
        walk.plan.tokens.add(new PlanToken(anchor, walk.plan.groupIds.size(), walk.pendingListPrefix));
        walk.pendingListPrefix = false;
    }

    /**
     * 记录一个可翻译片段：空行分隔推进段落组。
     *
     * <p>URL 与 emoji 都作为必须保留的定位标记；列表项按独立行分组。
     * {@code forceNewGroup} 由列表项边界推进段落组。</p>
     */
    private static void addSegment(PlanWalk w, ContentTree.Event event, int sourceLines) {
        w.pendingListPrefix = false;
        w.plan.boundaries.add(new BoundaryContext(event, w, sourceLines));
        int separatorNewlines = w.separatorNewlines;
        boolean lineBoundary = separatorNewlines < 2
                && (w.forceNewGroup || separatorNewlines == 1);
        if (w.forceNewGroup) {
            w.group++;
            w.forceNewGroup = false;
        } else if (!w.plan.groupIds.isEmpty() && separatorNewlines >= 2) {
            w.group++;
        }
        w.plan.groupIds.add(w.group);
        w.plan.listFlags.add(w.listLevel > 0);
        w.plan.lineBreakBefore.add(lineBoundary);
        w.plan.sourceLineCounts.add(sourceLines);
        w.separatorNewlines = 0;
        w.headingBreaks = 0;
        java.util.Arrays.fill(w.gapKinds, 0);
        w.whitespaceNodes = 0;
        w.itemStarts = 0;
        w.listStarts = 0;
        w.listEnds = 0;
        w.gapTypes.clear();
        w.gapTypesTruncated = false;
    }

    /** Count only captured text whitespace; URL/emoji/opaque and synthetic code text give no proof. */
    private static void addSourceWhitespace(PlanWalk w, String text, int start, int end) {
        for (int i = start; i < end; i++) if (text.charAt(i) == '\n') w.separatorNewlines++;
    }

}
