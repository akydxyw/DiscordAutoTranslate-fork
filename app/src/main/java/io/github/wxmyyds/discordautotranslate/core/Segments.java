package io.github.wxmyyds.discordautotranslate.core;

import androidx.annotation.Nullable;

import java.util.regex.Pattern;

/**
 * 单一文本翻译输入的防损坏校验（含格式与保留叶节点定位标记）。
 *
 * <p>引擎与渲染层共用 {@link #hasSegmentMarkers} 拒绝残留分段标记，
 * 不解析、剥离或修复标记。{@link #plainTextViolation} 还检查空文本、
 * 包装标签、模板占位符与长度比例异常，不把损坏内容送进节点树。</p>
 */
public final class Segments {

    /** 段内夹带的标记碎片（开/闭均算）：协议被破坏，会把标记泄漏成字面量。 */
    private static final Pattern STRAY_MARKER = Pattern.compile("</?s\\s*\\d");

    /**
     * 模型幻觉出的模板占位符（如 {@code XXXXXX}）。
     *
     * <p>实测小模型在长重复文本上会输出 {@code SENSENOVA_..._HHMMSS_XXXXXX}
     * 这类占位符；正常译文不会出现 4 个以上连续大写 X。</p>
     */
    private static final Pattern PLACEHOLDER_RUN = Pattern.compile("X{4,}");

    private Segments() {
    }

    /** 检测开/闭分段标记及未闭合碎片；只拒绝，不修复。 */
    public static boolean hasSegmentMarkers(@Nullable String text) {
        return text != null && STRAY_MARKER.matcher(text).find();
    }

    /**
     * 译文的防损坏校验：非空、无包装标签与旧分段标记、原样保留结构定位、长度比例合理。
     *
     * <p>单链路在引擎侧返回前调用：复读刷屏（长度
     * 暴涨）、内容大面积丢失（长度骤减）、标记泄漏与模板占位符都在这里
     * 拦下，判失败由用户手动重试。</p>
     *
     * <p>长度比例只对较长的原文（≥40 字符）生效。比例阈值按翻译方向
     * 区分：跨语系时长度天然不对称——英→中译文常缩到原文的两三成
     * （实测 91 字符英文 → 22 字中文被判“疑似丢失”），中→英则膨胀数倍。
     * 因此同语系仍按 1/4 与 3 倍把关；源文无 CJK 而译文有 CJK 时丢失线
     * 放宽到 1/8，源文有 CJK 而译文无 CJK 时复读线放宽到 6 倍。过短的
     * 文本比例噪声太大，不做校验。</p>
     *
     * @return 违规描述；合格返回 null
     */
    @Nullable
    public static String plainTextViolation(@Nullable String translated, String source) {
        if (translated == null || translated.trim().isEmpty()) {
            return "译文为空";
        }
        if (translated.contains(Protocols.WRAPPER_OPEN)
                || translated.contains(Protocols.WRAPPER_CLOSE)) {
            return "译文包含包装标签";
        }
        if (hasSegmentMarkers(translated)) {
            return "译文中残留分段标记";
        }
        if (PLACEHOLDER_RUN.matcher(translated).find()) {
            return "译文含模板占位符";
        }
        if (!RetainedNodeMarkers.matchesSource(translated, source)) {
            return "保留节点定位标记无效";
        }
        String sourceWithoutKeep = RetainedNodeMarkers.withoutMarkers(source);
        String translationWithoutKeep = RetainedNodeMarkers.withoutMarkers(translated);
        if (translationWithoutKeep.trim().isEmpty()) return "译文为空";
        int n = sourceWithoutKeep.length();
        if (n >= 40) {
            int m = translationWithoutKeep.length();
            boolean sourceCjk = containsCjk(sourceWithoutKeep);
            boolean translatedCjk = containsCjk(translationWithoutKeep);
            int lossFactor = !sourceCjk && translatedCjk ? 8 : 4;
            int spamFactor = sourceCjk && !translatedCjk ? 6 : 3;
            if (m * lossFactor < n) {
                return "译文长度异常（" + m + "/" + n + "）：疑似内容丢失";
            }
            if (m > n * spamFactor) {
                return "译文长度异常（" + m + "/" + n + "）：疑似复读";
            }
        }
        return null;
    }

    /** 是否含 CJK 统一表意文字（含扩展 A），用于判定翻译方向。 */
    private static boolean containsCjk(String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0x3400 && c <= 0x4DBF) || (c >= 0x4E00 && c <= 0x9FFF)) {
                return true;
            }
        }
        return false;
    }
}
