package io.github.wxmyyds.discordautotranslate.translate;

import androidx.annotation.Nullable;

import io.github.wxmyyds.discordautotranslate.core.Config;

/**
 * 连接测试与测试翻译（需求 §3.1、§5.5、§8：可测试 API 连接和实际翻译）。
 *
 * <p>由设置界面调用，同步执行，调用方需自行放到后台线程。
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
     * 测试连接：只做配置校验 + 一次真实请求。
     *
     * <p>说明：内置协议没有统一的“健康检查”端点，因此连接测试等价于
     * 用一段固定文本执行一次真实翻译请求，并校验响应能被解析。
     * 这样能同时覆盖 URL、认证、协议、模型四项配置。</p>
     */
    public static Result testConnection(Config.Snapshot cfg) {
        return testTranslation(cfg, DEFAULT_TEST_TEXT);
    }

    /** 测试翻译：用指定文本走完整流程，失败返回 unsuccessful result。 */
    public static Result testTranslation(Config.Snapshot cfg, String text) {
        long start = System.currentTimeMillis();
        try {
            // Real tests use the engine's one validation/request chain; selfCheck stays offline.
            JsonApiEngine engine = new JsonApiEngine(cfg);
            String translated = engine.translate(text);

            long ms = System.currentTimeMillis() - start;
            return new Result(true, translated, ms);

        } catch (Throwable failure) {
            return new Result(false, null, System.currentTimeMillis() - start);
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
