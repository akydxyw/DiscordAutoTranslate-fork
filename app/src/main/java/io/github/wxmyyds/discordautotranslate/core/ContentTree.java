package io.github.wxmyyds.discordautotranslate.core;

import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;

/** One occurrence-based host tree snapshot shared by extraction, alignment and reconstruction. */
public final class ContentTree {
    public enum Kind { SEQUENCE, CONTAINER, LIST, TEXT, URL, EMOJI, INLINE_CODE, CODE_BLOCK, BREAK, OPAQUE }
    public enum EventKind { NODE, LIST_START, ITEM_START, ITEM_END, LIST_END }
    private static final int MAX_DEPTH = 12;
    private static final int MAX_OCCURRENCES = 8192;

    public static final class Node {
        public final Object original;
        public final Kind kind;
        public final String semantic;
        public final String text;
        public final List<Node> children;
        /** Captured backing sequence, including URL descendants retained by native rendering. */
        private final Node sourceSequence;
        public final int segmentCount;
        public final int leadingEnd;
        public final int trailingStart;
        public final List<String> chunks;
        public final List<String> separators;
        /** Captured LF-based line count for each chunk, including multiline text in one slot. */
        public final List<Integer> chunkLineCounts;
        public final List<Boolean> translatable;

        private Node(Object original, Kind kind, String semantic, String text, List<Node> children) {
            this(original, kind, semantic, text, children, null);
        }

        private Node(Object original, Kind kind, String semantic, String text, List<Node> children, Node sourceSequence) {
            this.sourceSequence = sourceSequence;
            this.original = original;
            this.kind = kind;
            this.semantic = semantic;
            this.text = text;
            this.children = Collections.unmodifiableList(new ArrayList<>(children));
            leadingEnd = kind == Kind.TEXT && text != null ? TextExtractor.leadingWhitespace(text) : 0;
            trailingStart = kind == Kind.TEXT && text != null ? TextExtractor.trailingWhitespaceStart(text) : 0;
            BlankLines.Split split = leadingEnd < trailingStart
                    ? BlankLines.split(text.substring(leadingEnd, trailingStart)) : BlankLines.split("");
            chunks = Collections.unmodifiableList(new ArrayList<>(split.chunks));
            separators = Collections.unmodifiableList(new ArrayList<>(split.separators));
            List<Boolean> flags = new ArrayList<>();
            List<Integer> lines = new ArrayList<>();
            int count = 0;
            for (String chunk : chunks) {
                int lineCount = 1;
                for (int i = 0; i < chunk.length(); i++) if (chunk.charAt(i) == '\n') lineCount++;
                lines.add(lineCount);
                boolean translate = kind == Kind.TEXT && !BlankLines.isBlank(chunk)
                        && !TextExtractor.isUrlLike(chunk);
                flags.add(translate);
                if (translate) count++;
            }
            translatable = Collections.unmodifiableList(flags);
            chunkLineCounts = Collections.unmodifiableList(lines);
            segmentCount = count;
        }
    }

    public static final class Event {
        public final EventKind kind;
        public final Node node;
        public final boolean styled;
        /** Captured semantic container occurrences, root to leaf; no rereading host members. */
        public final List<Node> containers;
        private Event(EventKind kind, Node node, boolean styled, List<Node> containers) {
            this.kind = kind; this.node = node; this.styled = styled;
            this.containers = Collections.unmodifiableList(new ArrayList<>(containers));
        }
    }

    public final Node root;
    public final List<Event> events;
    public final int segmentCount;
    private ContentTree(Node root) {
        this.root = root;
        List<Event> output = new ArrayList<>();
        emit(root, false, new ArrayList<>(), output);
        events = Collections.unmodifiableList(output);
        int count = 0;
        for (Event event : events) if (event.kind == EventKind.NODE) count += event.node.segmentCount;
        segmentCount = count;
    }

    /** Depth counts native semantic containers, never Java list/item wrappers. Invalid trees reject whole. */
    @Nullable public static ContentTree compile(List<?> nodes) {
        if (nodes == null) return null;
        try {
            return new ContentTree(new Compiler().sequence(nodes, 0));
        } catch (IllegalStateException invalid) {
            return null;
        }
    }

    private static final class Compiler {
        final IdentityHashMap<Object, Boolean> path = new IdentityHashMap<>();
        int occurrences;
        void enter(Object value, int depth) {
            if (depth > MAX_DEPTH || path.size() >= 64 || ++occurrences > MAX_OCCURRENCES
                    || path.put(value, Boolean.TRUE) != null)
                throw new IllegalStateException("Invalid content structure");
        }
        Node sequence(List<?> values, int depth) {
            enter(values, depth);
            try {
                List<Node> children = new ArrayList<>();
                for (Object value : values) children.add(read(value, depth));
                return new Node(values, Kind.SEQUENCE, null, null, children);
            } finally { path.remove(values); }
        }
        Node read(Object value, int depth) {
            if (value instanceof List<?>) return sequence((List<?>) value, depth);
            if (value == null) {
                if (++occurrences > MAX_OCCURRENCES) throw new IllegalStateException("Invalid content structure");
                return leaf(null, Kind.OPAQUE, null, null);
            }
            enter(value, depth);
            try {
                String name = HookConfiguration.semanticTypeName(value.getClass());
                if ("TextContentNode".equals(name)) return leaf(value, Kind.TEXT, name, Reflect.callString(value, "getContent"));
                if ("InlineCodeContentNode".equals(name)) return leaf(value, Kind.INLINE_CODE, name, null);
                if ("CodeBlockContentNode".equals(name)) return leaf(value, Kind.CODE_BLOCK, name, null);
                if ("NewLineContentNode".equals(name) || "LineBreakContentNode".equals(name))
                    return leaf(value, Kind.BREAK, name, null);
                String emoji = TextExtractor.nodeEmojiText(value);
                if (emoji != null) return leaf(value, Kind.EMOJI, name, emoji);
                boolean list = "BulletListContentNode".equals(name);
                if (list || TextExtractor.isTextContainer(name)) {
                    Object raw = Reflect.callMember(value, list ? "getItems" : "getContent");
                    if (!(raw instanceof List<?>)) throw new IllegalStateException("Invalid content children");
                    Node sequence = sequence((List<?>) raw, depth + 1);
                    if ("LinkContentNode".equals(name)) {
                        StringBuilder display = new StringBuilder();
                        appendText(sequence, display);
                        String text = display.toString().trim();
                        if (TextExtractor.isUrlLike(text)) return new Node(value, Kind.URL, name, text,
                                Collections.emptyList(), sequence);
                    }
                    return new Node(value, list ? Kind.LIST : Kind.CONTAINER, name, null,
                            sequence.children, sequence);
                }
                return leaf(value, Kind.OPAQUE, name, null);
            } finally { path.remove(value); }
        }
    }

    /** Native untouched branches retain object identity, but may not silently adopt changed list shapes. */
    public static boolean retainedStructureMatches(Node node) {
        if (node.kind == Kind.SEQUENCE) {
            List<?> source = (List<?>) node.original;
            if (source.size() != node.children.size()) return false;
            for (int i = 0; i < node.children.size(); i++) {
                Node child = node.children.get(i);
                if (source.get(i) != child.original || !retainedStructureMatches(child)) return false;
            }
        } else if (node.sourceSequence != null) {
            return retainedStructureMatches(node.sourceSequence);
        }
        return true;
    }

    private static Node leaf(Object value, Kind kind, String semantic, String text) {
        return new Node(value, kind, semantic, text, Collections.emptyList());
    }

    private static void appendText(Node node, StringBuilder out) {
        if (node.kind == Kind.TEXT && node.text != null) out.append(node.text);
        else if (node.kind == Kind.CONTAINER || node.kind == Kind.SEQUENCE)
            for (Node child : node.children) appendText(child, out);
    }

    private static void emit(Node node, boolean styled, List<Node> containers, List<Event> out) {
        if (node.kind == Kind.SEQUENCE) {
            for (Node child : node.children) emit(child, styled, containers, out);
        } else if (node.kind == Kind.CONTAINER) {
            boolean nextStyle = styled || TextExtractor.isInlineStyleContainer(node.semantic);
            containers.add(node);
            for (Node child : node.children) emit(child, nextStyle, containers, out);
            containers.remove(containers.size() - 1);
        } else if (node.kind == Kind.LIST) {
            out.add(new Event(EventKind.LIST_START, node, styled, containers));
            containers.add(node);
            for (Node item : node.children) {
                out.add(new Event(EventKind.ITEM_START, item, styled, containers));
                emit(item, styled, containers, out);
                out.add(new Event(EventKind.ITEM_END, item, styled, containers));
            }
            containers.remove(containers.size() - 1);
            out.add(new Event(EventKind.LIST_END, node, styled, containers));
        } else if (node.original != null) {
            out.add(new Event(EventKind.NODE, node, styled, containers));
        }
    }
}
