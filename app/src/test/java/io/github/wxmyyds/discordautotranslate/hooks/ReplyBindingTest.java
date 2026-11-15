package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.*;

import io.github.wxmyyds.discordautotranslate.core.HookConfiguration;
import io.github.wxmyyds.discordautotranslate.core.StateStore;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Both profiles feed the same captured reference and canonical-state operation. */
public class ReplyBindingTest {
    private static final String ID = "123456789012345678";
    private static final String OTHER = "987654321098765432";
    public static final class Message {
        final Object content;
        final String revision;
        final Object reference;
        Message(Object content, String revision, Object reference) {
            this.content = content; this.revision = revision; this.reference = reference;
        }
        public Object getContent() { return content; }
        public String getIdNative() { return ID; }
        public String getIdNull() { return null; }
        public String getEditedTimestamp() { return revision; }
        public Object getReferencedMessage() { return reference; }
    }
    public static final class OlderReference {
        private final Message message;
        private final Object systemContent;
        OlderReference(Message message, Object system) { this.message = message; systemContent = system; }
    }
    public static final class CurrentReference {
        private final Message a;
        private final Object b;
        CurrentReference(Message message, Object system) { a = message; b = system; }
    }
    private final HookBridge bridge = HookBridge.get();
    private final StateStore store = StateStore.get();

    @Before public void setup() { bridge.reset(); store.onNewGeneration(); HookRuntime.suspend(); }
    @After public void cleanup() {
        bridge.reset(); store.onNewGeneration(); HookRuntime.suspend(); HookConfiguration.activate(null);
    }

    private JSONObject configure(int version) throws Exception {
        JSONObject root = new JSONObject(Files.readString(Path.of(
                "src/main/assets/hook-config/" + version + ".json")));
        root.getJSONObject("types")
                .put("Message", Message.class.getName())
                .put("PrimitiveStructurableText", ContentNodesTest.PrimitiveStructurableText.class.getName());
        root.getJSONObject("accessors").getJSONObject("Message").put("getId", "getIdNative");
        root.getJSONObject("accessors").put("PrimitiveStructurableText",
                new JSONObject().put("getContent", "getContent"));
        if (root.getJSONObject("fields").has("PrimitiveStructurableText"))
            root.getJSONObject("fields").remove("PrimitiveStructurableText");
        JSONObject spec = root.getJSONObject("hooks").getJSONObject("message.bind")
                .getJSONObject("referencedMessage");
        spec.put("loadedClass", version == 340013 ? OlderReference.class.getName() : CurrentReference.class.getName());
        HookConfiguration.activate(HookConfiguration.parse(root.toString(), version));
        return spec;
    }
    private static Object reference(int version, Message message, Object system) {
        return version == 340013 ? new OlderReference(message, system) : new CurrentReference(message, system);
    }
    private StateStore.Entry canonical(Object body, String revision) {
        String key = bridge.embedStateKey(ID, body);
        StateStore.Entry entry = store.obtain(ID, key.substring((ID + "\u0000embed\u0000").length()));
        bridge.rememberMessageRevision(ID, revision);
        bridge.rememberCanonicalContent(ID, body);
        entry.status = StateStore.Status.TRANSLATED;
        entry.translatedText = "正文\n译文";
        entry.showingOriginal = false;
        return entry;
    }

    @Test public void bothNativeFieldLayoutsSelectOneReferenceWithoutCreatingState() throws Exception {
        for (int version : new int[]{340013, 352017}) {
            JSONObject spec = configure(version);
            Object body = new ContentNodesTest.PrimitiveStructurableText("body with attachment");
            Message inner = new Message(body, "edit-1", null);
            ReplyBinding binding = ReplyBinding.read(new Message(null, null,
                    reference(version, inner, null)), spec);
            assertNotNull(binding);
            assertEquals(ID, binding.messageId);
            assertSame(body, binding.messageContent);
            assertNull(binding.systemContent);
            assertEquals("edit-1", binding.editedTimestamp);
            assertTrue(binding.matchesConversion(body, ID));
            assertTrue(binding.translatesConversion(body, ID));
            assertTrue(binding.ownsSource(body));
            assertFalse(binding.matchesConversion(body, OTHER));
            assertFalse(binding.translatesConversion(body, OTHER));
            assertFalse(binding.matchesConversion(new ContentNodesTest.PrimitiveStructurableText(
                    "body with attachment"), ID));
            assertFalse(binding.matchesConversion(null, ID));
            assertNull(store.peek(ID));
            assertNull(bridge.referencedTranslation(ID, binding.editedTimestamp));
        }
    }

    @Test public void bothProfilesReuseCanonicalStateAndItsCurrentToggleNotProjectionHash() throws Exception {
        for (int version : new int[]{340013, 352017}) {
            bridge.reset(); store.onNewGeneration();
            JSONObject spec = configure(version);
            Object canonical = new ContentNodesTest.PrimitiveStructurableText("body\nwith attachment");
            Object projection = new ContentNodesTest.PrimitiveStructurableText("body with attachment");
            StateStore.Entry entry = canonical(canonical, "edit-1");
            ReplyBinding binding = ReplyBinding.read(new Message(null, null,
                    reference(version, new Message(projection, "edit-1", null), null)), spec);
            assertTrue(binding.translatesConversion(projection, ID));
            assertNull(bridge.currentTranslation(ID, projection));
            HookBridge.ReferencedTranslation reused = bridge.referencedTranslation(ID, binding.editedTimestamp);
            assertNotNull(reused);
            assertSame(canonical, reused.originalContent);
            assertSame(entry, reused.state);
            assertFalse(reused.state.showingOriginal);
            assertTrue(store.toggle(ID));
            assertTrue(bridge.referencedTranslation(ID, binding.editedTimestamp).state.showingOriginal);
            assertTrue(store.toggle(ID));
            assertFalse(bridge.referencedTranslation(ID, binding.editedTimestamp).state.showingOriginal);
            bridge.rememberMessageRevision(ID, "edit-2");
            assertNull(bridge.referencedTranslation(ID, binding.editedTimestamp));
            assertSame(entry, store.peek(ID));
        }
    }

    @Test public void systemReferenceIsRecognizedButNeverTranslatedAsCanonicalBody() throws Exception {
        for (int version : new int[]{340013, 352017}) {
            JSONObject spec = configure(version);
            Object body = new Object();
            Object system = new Object();
            ReplyBinding binding = ReplyBinding.read(new Message(null, null,
                    reference(version, new Message(body, null, null), system)), spec);
            assertTrue(binding.matchesConversion(system, ID));
            assertTrue(binding.matchesConversion(body, ID));
            assertFalse(binding.translatesConversion(system, ID));
            assertFalse(binding.translatesConversion(body, ID));
            assertNull(store.peek(ID));
        }
    }

    @Test public void missingReferencedIdStillRetainsConsumerOwnershipWithoutUsingBodyState() throws Exception {
        JSONObject spec = configure(340013);
        Object body = new ContentNodesTest.PrimitiveStructurableText("body text");
        StateStore.Entry entry = canonical(body, null);
        bridge.rememberContent(ID, body);
        assertSame(entry, bridge.currentTranslation(ID, body).state);
        spec.put("messageIdGetter", "getIdNull");
        JSONObject profile = new JSONObject(HookConfiguration.activeConfiguration().rawJson());
        profile.getJSONObject("accessors").getJSONObject("Message").put("getIdNull", "getIdNull");
        HookConfiguration.activate(HookConfiguration.parse(profile.toString(), 340013));
        ReplyBinding binding = ReplyBinding.read(new Message(null, null,
                new OlderReference(new Message(body, null, null), null)), spec);
        assertNotNull(binding);
        assertNull(binding.messageId);
        assertTrue(binding.ownsSource(body));
        assertFalse(binding.matchesConversion(body, ID));
        assertFalse(binding.translatesConversion(body, ID));
        assertSame(entry, store.peek(ID));
    }

    @Test public void absentOrUnloadedReferencesCannotBeUsedAsReplyConsumers() throws Exception {
        JSONObject spec = configure(340013);
        assertNull(ReplyBinding.read(new Message(null, null, null), spec));
        assertNull(ReplyBinding.read(new Message(null, null, new Object()), spec));
        assertNull(ReplyBinding.read(new Message(null, null, new OlderReference(null, null)), spec));
        assertNull(ReplyBinding.read(new Message(null, null, new Object()), null));
    }

    @Test public void missingRevisionGetterIsRejectedNotTreatedAsNullRevision() throws Exception {
        JSONObject spec = configure(340013);
        spec.put("editedTimestampGetter", "getEditedTimestampOld");
        Message model = new Message(null, null, new OlderReference(new Message(new Object(), null, null), null));
        assertThrows(IllegalStateException.class, () -> ReplyBinding.read(model, spec));
        assertNull(store.peek(ID));
    }
}
