package io.github.wxmyyds.discordautotranslate.hooks;

import io.github.wxmyyds.discordautotranslate.core.ProjectLog;

import android.view.View;
import android.widget.TextView;

import io.github.wxmyyds.discordautotranslate.core.HookConfiguration;
import io.github.wxmyyds.discordautotranslate.core.StateStore;
import io.github.wxmyyds.discordautotranslate.core.TextExtractor;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** Raw Poll text uses its native setter; state, requests, icons and rerenders remain shared. */
public final class PollHooks {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private static final class Scope implements AutoCloseable {
        final PollBinding binding;
        final Object original;
        final String key;
        final Scope previous;

        Scope(PollBinding binding, Object original, String key) {
            this.binding = binding;
            this.original = original;
            this.key = key;
            previous = CURRENT.get();
            CURRENT.set(this);
        }

        @Override public void close() {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }

    private PollHooks() {}

    public static void install(XposedModule module, ClassLoader cl, HookConfiguration config,
            Set<String> installedHookIds, List<XposedInterface.HookHandle> handles) {
        installSetter(module, cl, config, "poll.textSet", false, handles, installedHookIds);
        installSetter(module, cl, config, "poll.accessibilitySet", true, handles, installedHookIds);
        installBind(module, cl, config, "poll.bind", false, handles, installedHookIds);
        installBind(module, cl, config, "poll.answerBind", true, handles, installedHookIds);
    }

    private static Method target(HookConfiguration config, String id, ClassLoader cl) {
        Method method = config.findMethod(id, cl);
        if (method == null) throw new IllegalStateException("Configured Poll target unavailable");
        return method;
    }

    private static void installBind(XposedModule module, ClassLoader cl, HookConfiguration config,
            String id, boolean answer, List<XposedInterface.HookHandle> handles,
            Set<String> installedHookIds) {
        if (!config.isEnabled(id)) return;
        Method method = target(config, id, cl);
        JSONObject spec = config.hook(id);
        handles.add(module.hook(method).setId("dat." + id)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    try (HookRuntime.Lease lease = HookRuntime.enter()) {
                        if (lease == null) return chain.proceed();
                        PollBinding binding;
                        Object original;
                        String key;
                        HookBridge bridge = HookBridge.get();
                        try {
                            if (!RenderDispatcher.isRerendering()) {
                                Object receiver = PollBinding.receiver(chain.getThisObject(),
                                        chain.getArgs(), spec, answer);
                                if (!(receiver instanceof View))
                                    throw new IllegalStateException("Configured Poll receiver unavailable");
                                InlineIconSpan.stopForHost((View) receiver);
                                RerenderRegistry.unregister((View) receiver);
                                bridge.unbindView((View) receiver);
                                if (receiver instanceof TextView) PollIconTouch.detach((TextView) receiver);
                                if (!answer) {
                                    String incoming = PollBinding.questionRow(chain.getArgs(), spec);
                                    if (HookBridge.looksLikeSnowflake(incoming)) CardState.invalidate(incoming);
                                }
                            }
                            binding = answer ? PollBinding.answer(chain.getThisObject(), chain.getArgs(), spec)
                                    : PollBinding.question(chain.getThisObject(), chain.getArgs(), spec);
                            if (!(binding.host instanceof View) || !(binding.receiver instanceof View)) {
                                throw new IllegalStateException("Configured Poll views unavailable");
                            }
                            View view = (View) binding.receiver;
                            original = binding.text == null ? null : StructurableTextFactory.primitive(binding.text);
                            if (binding.text != null && original == null) {
                                throw new IllegalStateException("Poll structured input construction failed");
                            }
                            key = original == null || !HookBridge.looksLikeSnowflake(binding.messageId) ? null
                                    : bridge.pollStateKey(binding.messageId, binding.slot, original);
                            if (RenderDispatcher.isRerendering()) {
                                // An answer's saved adapter position may now name another slot or text.
                                // Never replay it merely because it still belongs to the same poll row.
                                if (!CardState.canReplay(key, bridge.directMessageIdOf(view))) return null;
                            } else {
                                if (key != null) {
                                    bridge.bindView(view, key);
                                    RerenderRegistry.register(key, view, chain.getThisObject(), method,
                                            chain.getArgs().toArray());
                                }
                                if (!answer) {
                                    registerCard(binding.messageId, key, original, chain.getArgs(),
                                            spec, config.hook("poll.answerBind"));
                                }
                            }
                            if (key != null) bridge.rememberContent(key, original);
                        } catch (Throwable failure) {
                            ProjectLog.warnOnce("poll.bind.failed." + answer,
                                    "poll binding failed error=" + failure.getClass().getSimpleName());
                            // Stale positional replays must not reach the native bind with invalid args.
                            if (RenderDispatcher.isRerendering()) return null;
                            try (Scope blocked = new Scope(null, null, null)) {
                                return chain.proceed();
                            }
                        }
                        try (Scope scope = new Scope(binding, original, key)) {
                            return chain.proceed();
                        }
                    }
                }));
        installedHookIds.add(id);
        ProjectLog.infoOnce(id + ".target", id + " target=" + method.toGenericString());
    }

    /** Capture every option before native list diff/individual row binding; no per-option producer. */
    private static void registerCard(String row, String titleKey, Object title,
            List<?> args, JSONObject questionSpec, JSONObject answerSpec) throws Exception {
        if (!HookBridge.looksLikeSnowflake(row)) return;
        HookBridge bridge = HookBridge.get();
        List<String> keys = new ArrayList<>();
        List<Object> originals = new ArrayList<>();
        if (titleKey != null) { keys.add(titleKey); originals.add(title); }
        for (Object item : PollBinding.options(args, questionSpec)) {
            String slot = PollBinding.optionSlot(item, row, answerSpec);
            String text = PollBinding.optionText(item, answerSpec);
            if (text == null || text.trim().isEmpty()) continue;
            Object original = StructurableTextFactory.primitive(text);
            if (original == null) throw new IllegalStateException("Poll option input construction failed");
            String key = bridge.pollStateKey(row, slot, original);
            keys.add(key);
            originals.add(original);
        }
        CardState.bind(row, titleKey, keys);
        for (int i = 0; i < keys.size(); i++) {
            bridge.rememberContent(keys.get(i), originals.get(i));
            bridge.onMessageBound(keys.get(i), originals.get(i));
        }
    }

    private static void installSetter(XposedModule module, ClassLoader cl, HookConfiguration config,
            String id, boolean accessibility, List<XposedInterface.HookHandle> handles,
            Set<String> installedHookIds) {
        if (!config.isEnabled(id)) return;
        Method method = target(config, id, cl);
        int contentArgument = config.hook(id).optInt("contentArgument", -1);
        handles.add(module.hook(method).setId("dat." + id)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    try (HookRuntime.Lease lease = HookRuntime.enter()) {
                        if (lease == null) return chain.proceed();
                        Scope scope = CURRENT.get();
                        if (scope == null || scope.key == null || contentArgument < 0
                                || contentArgument >= chain.getArgs().size()
                                || !scope.binding.matches(chain.getThisObject(),
                                        chain.getArg(contentArgument), accessibility)) {
                            return chain.proceed();
                        }
                        // finishBinding must observe the text after the native setter installs it.
                        try (InlineIconInjector.HostScope iconScope =
                                     !accessibility && "question".equals(scope.binding.slot)
                                             ? InlineIconInjector.enterHost(
                                                     (View) scope.binding.receiver, scope.key) : null) {
                            Object[] args = chain.getArgs().toArray();
                            CharSequence display;
                            try {
                                TextExtractor.Result extracted = TextExtractor.extract(scope.original);
                                StateStore.Entry state = HookBridge.get().matchingState(scope.key, extracted);
                                display = PollTextProjection.display(scope.binding.text, state,
                                        CardState.forKey(scope.key));
                                if (!accessibility && "question".equals(scope.binding.slot)) {
                                    android.text.SpannableStringBuilder text =
                                            new android.text.SpannableStringBuilder(display);
                                    InlineIconInjector.inject(scope.original, text,
                                            ((View) scope.binding.receiver).getContext(), cl, extracted);
                                    display = text;
                                }
                                args[contentArgument] = display;
                            } catch (Throwable failure) {
                                ProjectLog.warnOnce("poll.text.failed", "poll text projection failed error="
                                        + failure.getClass().getSimpleName());
                                return chain.proceed(); // Reject the projection before the one native write.
                            }
                            Object result = chain.proceed(args);
                            if (!accessibility && "question".equals(scope.binding.slot)) {
                                try {
                                    PollIconTouch.attach((TextView) scope.binding.receiver,
                                            (android.text.Spanned) display);
                                } catch (Throwable failure) {
                                    ProjectLog.warnOnce("poll.icon.touch.failed",
                                            "poll icon touch installation failed error="
                                                    + failure.getClass().getSimpleName());
                                }
                            }
                            return result;
                        }
                    }
                }));
        installedHookIds.add(id);
    }
}
