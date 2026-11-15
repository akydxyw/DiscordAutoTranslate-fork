package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.*;

import io.github.wxmyyds.discordautotranslate.core.HookConfiguration;
import io.github.wxmyyds.discordautotranslate.core.Reflect;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

/** The actual profile supplies all names; fixtures replace only JVM-unavailable host classes. */
public class OlderProfileMemberTest {
    public static final class Accessory {
        private final Object content = new Object();
        int decoyReads;
        public String nativeId() { return "123456789012345678"; }
        public String getMessageId() { decoyReads++; return "wrong"; }
        public Object getMessageContent() { return content; }
    }
    public static final class Holder { private Object lastMessageContent = new Object(); }
    public static final class Observable {
        int calls;
        int position;
        int count;
        Object payload;
        public void d(int position, int count, Object payload) {
            calls++; this.position = position; this.count = count; this.payload = payload;
        }
        public void notifyItemRangeChanged(int position, int count, Object payload) {
            throw new AssertionError("Unconfigured method must not be called");
        }
    }
    public static final class Adapter { private final Observable mObservable = new Observable(); }

    @After public void cleanup() { HookConfiguration.activate(null); }

    private JSONObject profile() throws Exception {
        return new JSONObject(Files.readString(Path.of("src/main/assets/hook-config/340013.json")));
    }
    private static void activate(JSONObject root) throws Exception {
        HookConfiguration.activate(HookConfiguration.parse(root.toString(), 340013));
    }
    private static Object invokeRow(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = RowRefresher.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(null, args);
    }

    @Test public void bodyAccessoryReadsTypeScopedIdAndCorrectContentGetter() throws Exception {
        JSONObject root = profile();
        root.getJSONObject("types").put("MessageContentAccessory", Accessory.class.getName());
        // Java source cannot spell a Kotlin hyphenated name. The shipped-name assertion is separate.
        root.getJSONObject("accessors").getJSONObject("MessageContentAccessory")
                .put("getMessageId", "nativeId");
        activate(root);
        Accessory model = new Accessory();
        JSONObject binding = root.getJSONObject("hooks").getJSONObject("message.bind");
        assertEquals("123456789012345678", Reflect.callString(model, binding.getString("messageIdGetter")));
        Object source = Reflect.call(Reflect.findMethodExact(model.getClass(),
                binding.getString("contentGetter"), 0), model);
        assertSame(model.content, source);
        assertEquals(0, model.decoyReads);
        assertNull(Reflect.findMethodExact(model.getClass(), "getContent", 0));
    }

    @Test public void wrongIdMappingDoesNotRevertToReadableSemanticGetter() throws Exception {
        JSONObject root = profile();
        root.getJSONObject("types").put("MessageContentAccessory", Accessory.class.getName());
        root.getJSONObject("accessors").getJSONObject("MessageContentAccessory")
                .put("getMessageId", "nativeI");
        activate(root);
        Accessory model = new Accessory();
        assertNull(Reflect.callString(model, "getMessageId"));
        assertEquals(0, model.decoyReads);
    }

    @Test public void rowRefreshClearsTheNativeBindCacheAndNotifiesOneItem() throws Exception {
        JSONObject root = profile();
        root.getJSONObject("types").put("MessageContentViewHolder", Holder.class.getName());
        activate(root);
        Holder holder = new Holder();
        assertNotNull(holder.lastMessageContent);
        assertEquals(Boolean.TRUE, invokeRow("invalidateMessageContentBindCache",
                new Class<?>[]{Object.class}, holder));
        assertNull(holder.lastMessageContent);
        Adapter adapter = new Adapter();
        assertEquals(Boolean.TRUE, invokeRow("notifyAdapterItemChanged",
                new Class<?>[]{Object.class, int.class}, adapter, 7));
        assertEquals(1, adapter.mObservable.calls);
        assertEquals(7, adapter.mObservable.position);
        assertEquals(1, adapter.mObservable.count);
        assertNull(adapter.mObservable.payload);
    }

    @Test public void refreshMissingConfiguredMembersDoesNotGuessOtherPaths() throws Exception {
        JSONObject root = profile();
        root.getJSONObject("types").put("MessageContentViewHolder", Holder.class.getName());
        root.getJSONObject("fields").getJSONObject("MessageContentViewHolder").put("bindCache", "oldCache");
        root.getJSONObject("accessors").getJSONObject("RecyclerViewAdapterObservable")
                .put("notifyItemChanged", "missing");
        activate(root);
        Holder holder = new Holder();
        assertEquals(Boolean.FALSE, invokeRow("invalidateMessageContentBindCache",
                new Class<?>[]{Object.class}, holder));
        assertNotNull(holder.lastMessageContent);
        Adapter adapter = new Adapter();
        assertEquals(Boolean.FALSE, invokeRow("notifyAdapterItemChanged",
                new Class<?>[]{Object.class, int.class}, adapter, 7));
        assertEquals(0, adapter.mObservable.calls);
    }
}
