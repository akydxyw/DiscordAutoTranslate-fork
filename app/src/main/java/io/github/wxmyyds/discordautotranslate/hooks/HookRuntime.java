package io.github.wxmyyds.discordautotranslate.hooks;

/** Installation barrier: no business processing until every configured hook is registered. */
public final class HookRuntime {
    private static volatile boolean active;
    private HookRuntime() {}
    public static boolean isActive() { return active; }
    public static void activate() { active = true; }
    public static void suspend() { active = false; }
}
