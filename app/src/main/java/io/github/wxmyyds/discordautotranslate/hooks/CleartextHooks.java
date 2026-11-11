package io.github.wxmyyds.discordautotranslate.hooks;

import androidx.annotation.Nullable;

import io.github.wxmyyds.discordautotranslate.core.CleartextHosts;
import io.github.wxmyyds.discordautotranslate.core.Reflect;

import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 允许访问回环 / 自建明文的 AI 端点（修复「回环 AI 地址请求失败」）。
 *
 * <p>Discord 的 Manifest 通过 {@code networkSecurityConfig} 禁止了明文 HTTP。
 * 模块在 Discord 进程内通过 {@link java.net.HttpURLConnection}（见 core/Http）访问
 * {@code http://127.0.0.1:PORT/v1} 时，实际抛出异常的是
 * {@code com.android.okhttp.HttpHandler$CleartextURLFilter#checkURLPermitted}，
 * 它内部的判断来自 {@code android.security.NetworkSecurityPolicy#isCleartextTrafficPermitted(String)}。
 * 因此在这里做两层放行，覆盖不同 Android 版本上的实现路径：</p>
 * <ol>
 *   <li>{@code NetworkSecurityPolicy.isCleartextTrafficPermitted(String)}：
 *       对 {@link CleartextHosts} 允许的主机直接返回 true；</li>
 *   <li>{@code HttpHandler$CleartextURLFilter.checkURLPermitted(URL)}：
 *       对允许的主机直接返回（跳过原方法的异常抛出）。</li>
 * </ol>
 *
 * <p>放行范围仅限本机回环与当前配置的 API 主机，其余主机完全沿用宿主原来的
 * 网络安全策略，不会放开 Discord 自身的明文流量限制。两个 Hook 都是可选安装：
 * 目标方法不存在时跳过，不影响其余功能。</p>
 */
public final class CleartextHooks {

    private static final String CLS_NETWORK_SECURITY_POLICY =
            "android.security.NetworkSecurityPolicy";
    private static final String CLS_OKHTTP_CLEARTEXT_FILTER =
            "com.android.okhttp.HttpHandler$CleartextURLFilter";

    private CleartextHooks() {
    }

    /**
     * 安装明文放行 Hook。
     *
     * @return 成功安装的 Hook 句柄（可能为空列表）
     */
    public static List<XposedInterface.HookHandle> install(XposedModule module, ClassLoader cl) {
        List<XposedInterface.HookHandle> handles = new ArrayList<>(2);
        installPolicyHook(module, cl, handles);
        installOkHttpFilterHook(module, cl, handles);
        return handles;
    }

    /** 第一层：网络安全策略按主机放行。 */
    private static void installPolicyHook(XposedModule module,
                                          @Nullable ClassLoader cl,
                                          List<XposedInterface.HookHandle> handles) {
        Class<?> policy = Reflect.loadClass(cl, CLS_NETWORK_SECURITY_POLICY);
        // 同时存在无参与 String 参数两个重载，这里只关心带主机名的那个。
        Method permitted = Reflect.findMethod(policy, "isCleartextTrafficPermitted", 1, String.class);
        if (permitted == null) {
            return;
        }
        try {
            handles.add(module.hook(permitted)
                    .setId("dat.cleartextPolicy")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        String host = asString(chain.getArg(0));
                        if (CleartextHosts.isPermitted(host)) {
                            return Boolean.TRUE;
                        }
                        return chain.proceed();
                    }));
        } catch (Throwable ignored) {
            // 安装失败时退化为系统默认策略。
        }
    }

    /** 第二层：直接跳过 HttpURLConnection 的明文拦截（部分版本不经第一层）。 */
    private static void installOkHttpFilterHook(XposedModule module,
                                               @Nullable ClassLoader cl,
                                               List<XposedInterface.HookHandle> handles) {
        Class<?> filter = Reflect.loadClass(cl, CLS_OKHTTP_CLEARTEXT_FILTER);
        Method check = Reflect.findMethod(filter, "checkURLPermitted", 1, URL.class);
        if (check == null) {
            return;
        }
        try {
            handles.add(module.hook(check)
                    .setId("dat.cleartextHttpUrlConnection")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object arg = chain.getArg(0);
                        String host = arg instanceof URL ? ((URL) arg).getHost() : null;
                        if (CleartextHosts.isPermitted(host)) {
                            // 原方法只做「不允许就抛 IOException」的判断，跳过即可。
                            return null;
                        }
                        return chain.proceed();
                    }));
        } catch (Throwable ignored) {
            // 安装失败时退化为系统默认策略。
        }
    }

    @Nullable
    private static String asString(@Nullable Object o) {
        return o instanceof String ? (String) o : null;
    }
}
