package io.github.wxmyyds.discordautotranslate.hooks;

import io.github.wxmyyds.discordautotranslate.core.ContentTree;

import java.util.List;

/** Test-only list setup; reconstruction still uses the production occurrence-plan API. */
final class ContentPlanTestSupport {
    private ContentPlanTestSupport() {
    }

    static List<Object> replace(List<?> nodes, String translated) {
        return ContentNodes.replacePlanned(ContentTree.compile(nodes), translated);
    }

    static List<String> align(String span, List<Integer> groups,
                              List<Boolean> lists, List<Boolean> lineBreaks) {
        try {
            return ContentNodes.alignSpanToParts(span, groups, lists, lineBreaks);
        } catch (ContentNodes.Rejection rejected) {
            return null;
        }
    }
}
