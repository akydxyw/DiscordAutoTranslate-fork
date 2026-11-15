package io.github.wxmyyds.discordautotranslate.translate;

import androidx.annotation.Nullable;

import java.util.concurrent.Callable;

import io.github.wxmyyds.discordautotranslate.core.Config;

/**
 * 连接测试与测试翻译（需求 §3.1、§5.5、§8：可测试 API 连接和实际翻译）。
 *
 * <p>由设置界面创建单次 Call，在后台线程同步执行并在页面停止时主动取消。
 * 返回设置页实际消费的成功标志、译文与耗时。</p>
 */
public final class ApiTester {

    /** 测试结果。 */
    public static final class Result {
        public final boolean ok;
        /** 测试翻译时的译文（仅成功时有值）。 */
        @Nullable
        public final String translated;
        /** 耗时毫秒。 */
        public final long elapsedMs;

        Result(boolean ok, @Nullable String translated, long elapsedMs) {
            this.ok = ok;
            this.translated = translated;
            this.elapsedMs = elapsedMs;
        }
    }

    /** 默认测试文本：包含引号、换行、反斜杠，同时验证请求体转义是否正确。 */
    public static final String DEFAULT_TEST_TEXT =
            "Hello, \"world\"\\\nThis is a test.";

    private ApiTester() {
    }

    /**
     * Create before executor submission so cancellation also covers queued work.
     * Connection checks use DEFAULT_TEST_TEXT through this same request chain.
     */
    public static Call newCall(Config.Snapshot cfg, String text) {
        return new Call(new JsonApiEngine(cfg), text);
    }

    /** One-shot synchronous request owner; cancellation actively disconnects HTTP I/O. */
    public static final class Call implements Callable<Result> {
        private final JsonApiEngine engine;
        private final String text;
        private boolean used;
        private boolean cancelled;
        @Nullable
        private Thread worker;

        Call(JsonApiEngine engine, String text) {
            this.engine = engine;
            this.text = text;
        }

        @Override
        public Result call() {
            long start = System.currentTimeMillis();
            synchronized (this) {
                if (used) {
                    throw new IllegalStateException("API test calls cannot be reused");
                }
                used = true;
                if (cancelled) {
                    return new Result(false, null, System.currentTimeMillis() - start);
                }
                // Publish before engine registration. cancel() interrupts this worker
                // in that gap; JsonApiEngine checks it before opening a connection.
                worker = Thread.currentThread();
            }
            Result result;
            try {
                String translated = engine.translate(text);
                result = new Result(true, translated, System.currentTimeMillis() - start);
            } catch (Throwable failure) {
                result = new Result(false, null, System.currentTimeMillis() - start);
            }
            synchronized (this) {
                worker = null;
                return cancelled
                        ? new Result(false, null, System.currentTimeMillis() - start) : result;
            }
        }

        public synchronized void cancel() {
            cancelled = true;
            if (worker != null) {
                // Keep ownership until cancel returns: a completed call must never
                // interrupt unrelated work subsequently assigned to the same thread.
                engine.cancel(worker);
            }
        }
    }

    /**
     * 离线自检：不发起网络请求，只校验配置本身的完整性。
     * 供设置界面在无网络时给出有用的提示。
     */
    public static Result selfCheck(Config.Snapshot cfg) {
        long start = System.currentTimeMillis();
        try {
            // 与真实请求共用同一套配置校验，避免两处规则不一致。
            JsonApiEngine.validateConfig(cfg);
            long ms = System.currentTimeMillis() - start;
            return new Result(true, null, ms);
        } catch (Throwable failure) {
            return new Result(false, null, System.currentTimeMillis() - start);
        }
    }
}
