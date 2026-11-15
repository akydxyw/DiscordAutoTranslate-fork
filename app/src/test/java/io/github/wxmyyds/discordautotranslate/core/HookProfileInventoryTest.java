package io.github.wxmyyds.discordautotranslate.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Every added profile is parsed by production code; this cannot verify host signatures. */
public class HookProfileInventoryTest {
    @Test
    public void everyShippedProfileMatchesItsFilenameAndDeclaresInstalledHooks() throws Exception {
        Set<String> supported = Set.of("message.bind", "message.render", "message.textConversion",
                "embed.bind", "poll.bind", "poll.answerBind", "poll.textSet", "poll.accessibilitySet");
        List<Path> paths;
        try (Stream<Path> files = Files.list(Path.of("src/main/assets/hook-config"))) {
            paths = files.sorted().collect(Collectors.toList());
        }
        assertFalse("No shipped hook profiles", paths.isEmpty());
        for (Path path : paths) {
            String filename = path.getFileName().toString();
            assertTrue("Only exact numeric version files: " + filename,
                    filename.matches("[1-9][0-9]*\\.json"));
            int version = Integer.parseInt(filename.substring(0, filename.length() - 5));
            String json = Files.readString(path);
            HookConfiguration config = HookConfiguration.parse(json, version);
            assertEquals(version, config.versionCode());
            JSONObject root = new JSONObject(json);
            JSONObject hooks = root.getJSONObject("hooks");
            Set<String> configured = new HashSet<>();
            hooks.keys().forEachRemaining(configured::add);
            assertEquals(supported, configured);
            JSONArray required = root.getJSONArray("requiredHooks");
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < required.length(); i++) {
                String id = required.getString(i);
                assertTrue("Duplicate required hook: " + id, ids.add(id));
                assertNotNull(config.hook(id));
                assertTrue(config.isEnabled(id));
            }
            assertEquals(supported, ids);
            assertTrue(config.hasAllRequiredHooks(supported));
            Set<String> incomplete = new HashSet<>(supported);
            incomplete.remove("message.bind");
            assertFalse(config.hasAllRequiredHooks(incomplete));
        }
    }
}
