package io.github.wxmyyds.discordautotranslate.translate;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.github.wxmyyds.discordautotranslate.core.Config;
import io.github.wxmyyds.discordautotranslate.core.MemoryPrefs;

import org.junit.Test;

/** Failure and offline paths use no connections; real request coverage lives in JsonApiEngineTest. */
public class ApiTesterTest {
    @Test
    public void offlineCheckReturnsOnlyTheFieldsConsumedBySettings() {
        ApiTester.Result result = ApiTester.selfCheck(Config.read(new MemoryPrefs()));
        assertTrue(result.ok);
        assertNull(result.translated);
        assertTrue(result.elapsedMs >= 0);
    }

    @Test
    public void invalidConfigFailsOfflineAndConnectionTestWithoutOpeningIo() {
        Config.Snapshot config = Config.read(new MemoryPrefs().put(Config.KEY_API_URL, "invalid"));
        for (ApiTester.Result result : new ApiTester.Result[]{
                ApiTester.selfCheck(config), ApiTester.testConnection(config)}) {
            assertFalse(result.ok);
            assertNull(result.translated);
            assertTrue(result.elapsedMs >= 0);
        }
    }

    @Test
    public void blankTranslationUsesTheEnginesRejectionPath() {
        ApiTester.Result result = ApiTester.testTranslation(Config.read(new MemoryPrefs()), " \n\t");
        assertFalse(result.ok);
        assertNull(result.translated);
        assertTrue(result.elapsedMs >= 0);
    }
}
