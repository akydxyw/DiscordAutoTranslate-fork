package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.*;
import java.lang.reflect.Method;
import org.junit.Test;

/** Native invocation scope can be checked without Android views or a message queue. */
public class RenderDispatcherTest {
    private static void replay(Method method) throws Exception {
        Method invoke = RenderDispatcher.class.getDeclaredMethod("invokeRerender", Object.class,
                android.view.View.class, String.class, Method.class, Object[].class);
        invoke.setAccessible(true);
        invoke.invoke(null, null, null, null, method, new Object[0]);
    }

    private static boolean innerObserved;
    private static boolean outerBefore;
    private static boolean outerAfter;
    public static void inner() { innerObserved = RenderDispatcher.isRerendering(); }
    public static void outer() throws Exception {
        outerBefore = RenderDispatcher.isRerendering();
        replay(RenderDispatcherTest.class.getMethod("inner"));
        outerAfter = RenderDispatcher.isRerendering();
    }

    @Test public void nestedNativeReplayRestoresOuterThenClearsScope() throws Exception {
        assertFalse(RenderDispatcher.isRerendering());
        innerObserved = outerBefore = outerAfter = false;
        replay(RenderDispatcherTest.class.getMethod("outer"));
        assertTrue(outerBefore);
        assertTrue(innerObserved);
        assertTrue("Nested replay must restore the enclosing scope", outerAfter);
        assertFalse(RenderDispatcher.isRerendering());
    }

    @Test public void runtimeBarrierOnlyActivatesExplicitly() {
        try {
            HookRuntime.suspend();
            assertFalse(HookRuntime.isActive());
            HookRuntime.activate();
            assertTrue(HookRuntime.isActive());
            HookRuntime.suspend();
            assertFalse(HookRuntime.isActive());
        } finally { HookRuntime.suspend(); }
    }
}
