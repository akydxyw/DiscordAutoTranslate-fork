package io.github.wxmyyds.discordautotranslate.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.github.wxmyyds.discordautotranslate.core.Config;
import io.github.wxmyyds.discordautotranslate.core.MemoryPrefs;
import io.github.wxmyyds.discordautotranslate.translate.ApiTester;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

/** Android stubs permit ownership/guard checks; widget lifecycle loading is tested via SettingsPersistence. */
public class SettingsActivityTest {
    @Test
    public void pageCancellationCancelsBothTheQueuedOwnerAndItsFuture() throws Exception {
        SettingsActivity page = new SettingsActivity();
        ApiTester.Call call = ApiTester.newCall(Config.read(new MemoryPrefs()
                .put(Config.KEY_API_URL, "invalid")), ApiTester.DEFAULT_TEST_TEXT);
        FutureTask<ApiTester.Result> future = new FutureTask<>(call);
        set(page, "testCall", call);
        set(page, "testFuture", future);
        set(page, "testGeneration", 7L);

        invoke(page, "cancelPendingTest", new Class<?>[0]);

        assertTrue(future.isCancelled());
        assertEquals(Boolean.TRUE, get(call, "cancelled"));
        assertFalse(call.call().ok);
        assertNull(get(page, "testCall"));
        assertNull(get(page, "testFuture"));
        assertEquals(8L, get(page, "testGeneration"));
        invoke(page, "cancelPendingTest", new Class<?>[0]);
        assertEquals(9L, get(page, "testGeneration"));
    }

    @Test
    public void lateTestDeliveryCannotConsumeStoppedDestroyedOrReplacedPageWork() throws Exception {
        for (String reason : new String[]{"stopped", "destroyed", "generation", "task"}) {
            SettingsActivity page = new SettingsActivity();
            ApiTester.Call currentCall = ApiTester.newCall(Config.read(null), "hello");
            FutureTask<ApiTester.Result> currentTask = new FutureTask<>(currentCall);
            FutureTask<ApiTester.Result> oldTask = new FutureTask<>(
                    ApiTester.newCall(Config.read(null), "old"));
            set(page, "started", !reason.equals("stopped"));
            set(page, "destroyed", reason.equals("destroyed"));
            set(page, "testGeneration", 2L);
            set(page, "testCall", currentCall);
            set(page, "testFuture", currentTask);

            invoke(page, "finishTest", new Class<?>[]{long.class, Future.class,
                            ApiTester.Result.class, Throwable.class},
                    reason.equals("generation") ? 1L : 2L,
                    reason.equals("task") ? oldTask : currentTask,
                    ApiTester.selfCheck(Config.read(null)), null);

            // Accepted delivery would clear ownership and touch absent stub widgets.
            assertSame(currentTask, get(page, "testFuture"));
            assertSame(currentCall, get(page, "testCall"));
        }
    }

    private static Object invoke(SettingsActivity page, String name, Class<?>[] parameters,
                                 Object... arguments) throws Exception {
        Method method = SettingsActivity.class.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method.invoke(page, arguments);
    }

    private static void set(SettingsActivity page, String name, Object value) throws Exception {
        Field field = SettingsActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(page, value);
    }

    private static Object get(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
}
