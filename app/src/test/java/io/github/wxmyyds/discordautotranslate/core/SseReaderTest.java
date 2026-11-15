package io.github.wxmyyds.discordautotranslate.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class SseReaderTest {
    private static void feed(SseReader reader, String text) throws TranslationException {
        for (int i = 0; i < text.length(); i++) {
            reader.accept(text.charAt(i));
        }
    }

    @Test
    public void eventsUseBlankFramesAndJoinDataWithNewlines() throws Exception {
        List<String> events = new ArrayList<>();
        SseReader reader = new SseReader((event, data) -> events.add(event + "|" + data));
        feed(reader, ": comment\nevent: delta\nid: ignored\nretry: 10\n"
                + "data: one\ndata:  two\ndata\n");
        assertTrue(events.isEmpty());
        feed(reader, "\n");
        assertEquals("delta|one\n two\n", events.get(0));
        feed(reader, "event: unused\n\ndata:next\n\n");
        assertEquals("|next", events.get(1));
    }

    @Test
    public void acceptsCrLfCrAndLfWithoutDoubleDispatch() throws Exception {
        for (String ending : new String[]{"\r\n", "\r", "\n"}) {
            List<String> events = new ArrayList<>();
            SseReader reader = new SseReader((event, data) -> events.add(data));
            feed(reader, "\ufeffdata: 你好" + ending + ending
                    + ":keepalive" + ending + ending + "data: 😀" + ending + ending);
            assertEquals(2, events.size());
            assertEquals("你好", events.get(0));
            assertEquals("😀", events.get(1));
        }
    }

    @Test
    public void eofDoesNotDispatchUnterminatedEvent() throws Exception {
        List<String> events = new ArrayList<>();
        SseReader reader = new SseReader((event, data) -> events.add(data));
        feed(reader, "data: [DONE]\n");
        assertTrue(events.isEmpty());
    }

    @Test
    public void hugeSingleLineIsRejectedDuringReading() throws Exception {
        SseReader reader = new SseReader((event, data) -> fail("No complete event"));
        for (int i = 0; i < SseReader.MAX_RESPONSE_CHARS; i++) {
            reader.accept('x');
        }
        try {
            reader.accept('x');
            fail("Expected character limit");
        } catch (TranslationException e) {
            assertEquals(TranslationException.Kind.PARSE, e.kind());
        }
    }

    @Test
    public void limitIsCumulativeAcrossCommentsAndEvents() throws Exception {
        SseReader reader = new SseReader((event, data) -> { });
        for (int i = 0; i < SseReader.MAX_RESPONSE_CHARS / 2; i++) {
            reader.accept(':');
            reader.accept('\n');
        }
        try {
            reader.accept('\n');
            fail("Expected cumulative limit");
        } catch (TranslationException e) {
            assertEquals(TranslationException.Kind.PARSE, e.kind());
        }
    }
}
