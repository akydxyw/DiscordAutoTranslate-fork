package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.*;

import android.text.style.ClickableSpan;
import android.view.View;
import io.github.wxmyyds.discordautotranslate.core.HookConfiguration;
import io.github.wxmyyds.discordautotranslate.core.StateStore;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Exercise the sole constructor/callback operation with both shipped parameter protocols. */
public class IconClickSpanFactoryTest {
    public interface Callback { Object invoke(Object view); }
    public interface NestedClick { void onClick(View view); }
    public enum Priority { DEFAULT }
    public static final class DefaultMarker {}

    public static final class OlderSpan extends ClickableSpan implements NestedClick {
        final Priority priority;
        final float width;
        final Boolean underline;
        final Callback longClick;
        final Callback click;
        final int mask;
        public OlderSpan(Priority priority, Integer primary, Integer secondary, Integer tertiary,
                         float width, Boolean underline, Callback longClick, Callback click,
                         int mask, DefaultMarker marker) {
            this.priority = (mask & 1) != 0 ? Priority.DEFAULT : priority;
            this.width = (mask & 16) != 0 ? 150f : width;
            this.underline = (mask & 32) != 0 ? Boolean.FALSE : underline;
            this.longClick = (mask & 64) != 0 ? ignored -> Boolean.FALSE : longClick;
            this.click = click;
            this.mask = mask;
            if (this.priority == null || this.longClick == null || click == null)
                throw new IllegalArgumentException();
        }
        @Override public void onClick(View view) { click.invoke(view); }
    }

    public static final class CurrentSpan extends ClickableSpan implements NestedClick {
        final Callback click;
        final int mask;
        public CurrentSpan(Integer primary, Integer secondary, Integer tertiary, Boolean underline,
                           Callback longClick, Callback click, int mask) {
            this.click = click;
            this.mask = mask;
        }
        @Override public void onClick(View view) { click.invoke(view); }
    }

    private static final String ROW = "123456789012345678";
    private final StateStore store = StateStore.get();

    @Before public void setup() { HookRuntime.suspend(); HookBridge.get().reset(); store.onNewGeneration(); }
    @After public void cleanup() {
        HookRuntime.suspend(); HookBridge.get().reset(); store.onNewGeneration();
        HookConfiguration.activate(null);
    }

    private JSONObject specification(int version, Class<?> span) throws Exception {
        JSONObject root = new JSONObject(Files.readString(Path.of(
                "src/main/assets/hook-config/" + version + ".json")));
        HookConfiguration.activate(HookConfiguration.parse(root.toString(), version));
        JSONObject spec = root.getJSONObject("iconClick");
        spec.put("spanClass", span.getName());
        spec.put("interfaces", new JSONArray().put(NestedClick.class.getName()));
        JSONArray types = spec.getJSONObject("constructor").getJSONArray("parameterTypes");
        for (int i = 0; i < types.length(); i++) {
            String type = types.getString(i);
            if (type.equals("kotlin.jvm.functions.Function1")) types.put(i, Callback.class.getName());
            if (type.endsWith("$TouchPriority")) types.put(i, Priority.class.getName());
            if (type.equals("kotlin.jvm.internal.DefaultConstructorMarker"))
                types.put(i, DefaultMarker.class.getName());
        }
        JSONArray arguments = spec.getJSONObject("constructor").getJSONArray("arguments");
        for (int i = 0; i < arguments.length(); i++) {
            JSONObject argument = arguments.getJSONObject(i);
            if (argument.getString("kind").equals("callback"))
                argument.put("interface", Callback.class.getName());
        }
        return spec;
    }

    private Object create(JSONObject spec) throws Exception {
        return IconClickSpanFactory.createByConstructor(getClass().getClassLoader(), ROW, spec);
    }

    private StateStore.Entry translated() {
        StateStore.Entry entry = store.obtain(ROW, "hash");
        entry.status = StateStore.Status.TRANSLATED;
        entry.translatedText = "译文";
        entry.showingOriginal = false;
        return entry;
    }

    @Test public void olderSyntheticConstructorUsesNativeDefaultsAndOneToggleCallback() throws Exception {
        OlderSpan span = (OlderSpan) create(specification(340013, OlderSpan.class));
        assertNotNull(span);
        assertEquals(127, span.mask);
        assertEquals(Priority.DEFAULT, span.priority);
        assertEquals(150f, span.width, 0f);
        assertEquals(Boolean.FALSE, span.underline);
        assertEquals(Boolean.FALSE, span.longClick.invoke(null));
        StateStore.Entry entry = translated();
        span.onClick(null);
        assertFalse("Suspended runtime cannot toggle", entry.showingOriginal);
        HookRuntime.activate();
        span.onClick(null);
        assertTrue(entry.showingOriginal);
        span.onClick(null);
        assertFalse(entry.showingOriginal);
    }

    @Test public void currentConstructorStillUsesItsSevenArgumentsAndSameCallback() throws Exception {
        CurrentSpan span = (CurrentSpan) create(specification(352017, CurrentSpan.class));
        assertNotNull(span);
        assertEquals(255, span.mask);
        StateStore.Entry entry = translated();
        HookRuntime.activate();
        span.onClick(null);
        assertTrue(entry.showingOriginal);
    }

    @Test public void obsoleteConstructorSignatureIsNotTriedAsAnAlternative() throws Exception {
        JSONObject spec = specification(340013, OlderSpan.class);
        spec.getJSONObject("constructor").getJSONArray("parameterTypes").put(4, "java.lang.Float");
        assertThrows(NoSuchMethodException.class, () -> create(spec));
    }

    @Test public void nonFiniteFloatArgumentIsRejected() throws Exception {
        JSONObject spec = specification(340013, OlderSpan.class);
        spec.getJSONObject("constructor").getJSONArray("arguments").getJSONObject(4)
                .put("value", "Infinity");
        assertNull(create(spec));
    }
}
