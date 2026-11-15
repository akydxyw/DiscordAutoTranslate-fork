package io.github.wxmyyds.discordautotranslate.hooks;

import androidx.annotation.Nullable;
import io.github.wxmyyds.discordautotranslate.core.Reflect;
import java.lang.reflect.Method;
import org.json.JSONObject;

/** Captured native reference identity; not a preview cache or translation state. */
final class ReplyBinding {
    @Nullable final String messageId;
    final Object systemContent;
    final Object messageContent;
    final String editedTimestamp;

    private ReplyBinding(@Nullable String messageId, Object systemContent, Object messageContent,
                         @Nullable String editedTimestamp) {
        this.messageId = messageId;
        this.systemContent = systemContent;
        this.messageContent = messageContent;
        this.editedTimestamp = editedTimestamp;
    }

    @Nullable
    static ReplyBinding read(Object model, @Nullable JSONObject spec) {
        if (spec == null) return null;
        String accessor = spec.optString("accessor", null);
        String loadedClass = spec.optString("loadedClass", null);
        String messageField = spec.optString("messageField", null);
        String idGetter = spec.optString("messageIdGetter", null);
        String contentField = spec.optString("contentField", null);
        String contentGetter = spec.optString("messageContentGetter", null);
        String revisionGetter = spec.optString("editedTimestampGetter", null);
        if (accessor == null || loadedClass == null || messageField == null
                || idGetter == null || contentField == null || contentGetter == null
                || revisionGetter == null) return null;
        Method method = Reflect.findMethodExact(model.getClass(), accessor, 0);
        if (method == null) return null;
        Object referenced;
        try {
            referenced = method.invoke(model);
        } catch (Throwable failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            HookDiagnostics.warnOnce("message.bind.replyAccessor",
                    "referenced message accessor failed with " + cause.getClass().getName());
            return null;
        }
        if (referenced == null || !Reflect.isInstanceOfName(referenced, loadedClass)) return null;
        Object message = Reflect.getField(referenced, messageField);
        if (message == null) return null;
        String id = Reflect.callString(message, idGetter);
        // Keep source ownership even when its identity is unavailable. Such a
        // conversion is still a reply, not permission to read ordinary body state.
        Object system = Reflect.getField(referenced, contentField);
        Object content = Reflect.call(Reflect.findMethodExact(message.getClass(), contentGetter, 0), message);
        return new ReplyBinding(id, system, content, readEditedTimestamp(message, revisionGetter));
    }

    boolean ownsSource(@Nullable Object source) {
        return source != null && (source == messageContent || source == systemContent);
    }

    boolean matchesConversion(@Nullable Object source, @Nullable Object configuredId) {
        return ownsSource(source) && HookBridge.looksLikeSnowflake(messageId)
                && messageId.equals(configuredId);
    }

    boolean translatesConversion(@Nullable Object source, @Nullable Object configuredId) {
        return systemContent == null && matchesConversion(source, configuredId);
    }

    @Nullable
    static String readEditedTimestamp(Object model, String getter) {
        Method method = Reflect.findMethodExact(model.getClass(), getter, 0);
        if (method == null || method.getReturnType() != String.class)
            throw new IllegalStateException("configured edit revision getter unavailable");
        try {
            return (String) method.invoke(model);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("edit revision read failed", failure);
        }
    }
}
