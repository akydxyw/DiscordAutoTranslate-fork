package io.github.wxmyyds.discordautotranslate.translate;

import androidx.annotation.Nullable;

import java.util.concurrent.Callable;

import io.github.wxmyyds.discordautotranslate.core.Config;
import io.github.wxmyyds.discordautotranslate.core.ProjectLog;
import io.github.wxmyyds.discordautotranslate.core.TranslationException;

/** Settings tests share the production request, decoder and cancellation owner. */
public final class ApiTester {
    public enum TestKind { CONFIGURATION, CONNECTION, TRANSLATION }

    /** Content-free failure metadata; never retains a response or an exception message/cause. */
    public static final class Failure {
        public final TranslationException.Kind kind;
        public final TranslationException.Reason reason;
        public final int httpStatus;
        public final String exceptionType;

        private Failure(TranslationException.Kind kind, TranslationException.Reason reason,
                        int httpStatus, String exceptionType) {
            this.kind = kind;
            this.reason = reason;
            this.httpStatus = httpStatus;
            this.exceptionType = exceptionType;
        }

        private static Failure from(Throwable failure) {
            if (failure instanceof TranslationException) {
                TranslationException error = (TranslationException) failure;
                Throwable cause = error.getCause();
                return new Failure(error.kind(), error.reason(), error.httpStatus(),
                        (cause == null ? error : cause).getClass().getSimpleName());
            }
            return new Failure(TranslationException.Kind.UNKNOWN,
                    TranslationException.Reason.UNKNOWN_FAILURE, 0,
                    failure.getClass().getSimpleName());
        }

        private static Failure cancelled(int httpStatus) {
            return new Failure(TranslationException.Kind.CANCELLED,
                    TranslationException.Reason.CANCELLED, httpStatus, "TranslationException");
        }
    }

    public static final class Result {
        public final boolean ok;
        @Nullable public final String translated;
        public final long elapsedMs;
        public final TestKind testKind;
        @Nullable public final Failure failure;

        private Result(TestKind testKind, @Nullable String translated, long elapsedMs,
                       @Nullable Failure failure) {
            this.ok = failure == null;
            this.translated = failure == null && testKind == TestKind.TRANSLATION
                    ? translated : null;
            this.elapsedMs = elapsedMs;
            this.testKind = testKind;
            this.failure = failure;
        }
    }

    /** Fixed request probe; quoting/newlines also exercise request JSON escaping. */
    public static final String DEFAULT_TEST_TEXT = "Hello, \"world\"\\\nThis is a test.";

    private ApiTester() {}

    /** Create before executor submission so cancellation also covers queued work. */
    public static Call newCall(Config.Snapshot cfg, TestKind testKind, String text) {
        return new Call(new JsonApiEngine(cfg), testKind, text, cfg);
    }

    /** One-shot owner; page cancellation actively disconnects HTTP I/O. */
    public static final class Call implements Callable<Result> {
        private final JsonApiEngine engine;
        private final TestKind testKind;
        private final String text;
        private final String protocol;
        private final boolean streaming;
        private boolean used;
        private boolean cancelled;
        @Nullable private Thread worker;

        Call(JsonApiEngine engine, TestKind testKind, String text, Config.Snapshot cfg) {
            if (testKind != TestKind.CONNECTION && testKind != TestKind.TRANSLATION) {
                throw new IllegalArgumentException("Network tests require a network test kind");
            }
            this.engine = engine;
            this.testKind = testKind;
            this.text = testKind == TestKind.CONNECTION ? DEFAULT_TEST_TEXT : text;
            this.protocol = diagnosticProtocol(cfg.protocol);
            this.streaming = cfg.streaming;
        }

        @Override
        public Result call() {
            long start = System.nanoTime();
            synchronized (this) {
                if (used) throw new IllegalStateException("API test calls cannot be reused");
                used = true;
                if (cancelled) {
                    Result result = new Result(testKind, null, elapsed(start), Failure.cancelled(0));
                    logResult(result);
                    return result;
                }
                // Publish before registration; cancel interrupts the pre-attach gap.
                worker = Thread.currentThread();
            }
            ProjectLog.info("api.test.start test=" + testKind + " protocol=" + protocol
                    + " streaming=" + streaming);
            Result result;
            int responseStatus = 0;
            try {
                JsonApiEngine.Exchange exchange = engine.execute(text,
                        testKind == TestKind.TRANSLATION);
                responseStatus = exchange.httpStatus;
                result = new Result(testKind, exchange.translated, elapsed(start), null);
            } catch (Exception failure) {
                result = new Result(testKind, null, elapsed(start), Failure.from(failure));
            } finally {
                synchronized (this) {
                    // Always release ownership, including Errors handled by the Future boundary.
                    worker = null;
                }
            }
            synchronized (this) {
                if (cancelled && (result.failure == null
                        || result.failure.kind != TranslationException.Kind.CANCELLED)) {
                    // Preserve known status even if cancellation wins after successful decoding.
                    int status = result.failure == null ? responseStatus : result.failure.httpStatus;
                    result = new Result(testKind, null, elapsed(start), Failure.cancelled(status));
                }
            }
            logResult(result);
            return result;
        }

        public synchronized void cancel() {
            cancelled = true;
            if (worker != null) engine.cancel(worker);
        }
    }

    /** Offline check consumes the same configuration validation, without network I/O. */
    public static Result selfCheck(Config.Snapshot cfg) {
        long start = System.nanoTime();
        ProjectLog.info("api.test.start test=CONFIGURATION protocol="
                + diagnosticProtocol(cfg.protocol) + " streaming=" + cfg.streaming);
        Result result;
        try {
            JsonApiEngine.validateConfig(cfg);
            result = new Result(TestKind.CONFIGURATION, null, elapsed(start), null);
        } catch (Exception failure) {
            result = new Result(TestKind.CONFIGURATION, null, elapsed(start), Failure.from(failure));
        }
        logResult(result);
        return result;
    }

    private static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000L;
    }

    private static String diagnosticProtocol(String protocol) {
        if (Config.PROTOCOL_OPENAI_COMPATIBLE.equals(protocol)
                || Config.PROTOCOL_OPENAI_RESPONSES.equals(protocol)
                || Config.PROTOCOL_ANTHROPIC_MESSAGES.equals(protocol)) return protocol;
        return "INVALID";
    }

    private static void logResult(Result result) {
        if (result.ok) {
            ProjectLog.info("api.test.complete test=" + result.testKind + " outcome=SUCCESS");
        } else {
            Failure failure = result.failure;
            ProjectLog.warn("api.test.complete test=" + result.testKind + " outcome=FAILURE kind="
                    + failure.kind + " reason=" + failure.reason + " httpStatus="
                    + failure.httpStatus + " errorClass=" + failure.exceptionType);
        }
    }
}
