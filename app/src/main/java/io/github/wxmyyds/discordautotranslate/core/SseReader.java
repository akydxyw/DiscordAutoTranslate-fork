package io.github.wxmyyds.discordautotranslate.core;

/** Incremental SSE framing shared by HTTP and string-based protocol extraction. */
public final class SseReader {
    public static final int MAX_RESPONSE_CHARS = 1024 * 1024;

    public interface EventConsumer {
        void onEvent(String event, String data) throws TranslationException;
    }

    private final EventConsumer consumer;
    private final StringBuilder line = new StringBuilder();
    private final StringBuilder data = new StringBuilder();
    private String event = "";
    private boolean hasData;
    private boolean afterCr;
    private int chars;

    public SseReader(EventConsumer consumer) {
        this.consumer = consumer;
    }

    /** A CR itself ends a line; a following LF is swallowed without creating another line. */
    public void accept(char value) throws TranslationException {
        if (++chars > MAX_RESPONSE_CHARS) {
            throw new TranslationException(TranslationException.Kind.PARSE,
                    "SSE response exceeds 1 MiB character limit");
        }
        if (chars == 1 && value == '\ufeff') {
            return;
        }
        if (afterCr && value == '\n') {
            afterCr = false;
            return;
        }
        afterCr = value == '\r';
        if (value == '\r' || value == '\n') {
            endLine();
        } else {
            line.append(value);
        }
    }

    private void endLine() throws TranslationException {
        String value = line.toString();
        line.setLength(0);
        if (value.isEmpty()) {
            if (hasData) {
                // Each data field appends one LF; remove only that final separator.
                consumer.onEvent(event, data.substring(0, data.length() - 1));
            }
            event = "";
            data.setLength(0);
            hasData = false;
            return;
        }
        if (value.charAt(0) == ':') {
            return;
        }
        int colon = value.indexOf(':');
        String field = colon < 0 ? value : value.substring(0, colon);
        String content = colon < 0 ? "" : value.substring(colon + 1);
        if (content.startsWith(" ")) {
            content = content.substring(1);
        }
        if ("event".equals(field)) {
            event = content;
        } else if ("data".equals(field)) {
            hasData = true;
            data.append(content).append('\n');
        }
        // id/retry/unknown fields do not carry translation data.
    }
}
