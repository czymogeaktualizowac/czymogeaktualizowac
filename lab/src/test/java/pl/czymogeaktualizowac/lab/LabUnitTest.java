package pl.czymogeaktualizowac.lab;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.czymogeaktualizowac.core.Model.Ecosystem;
import pl.czymogeaktualizowac.core.Model.Level;
import pl.czymogeaktualizowac.core.Model.LevelResult;
import pl.czymogeaktualizowac.core.Model.PluginState;
import pl.czymogeaktualizowac.core.Model.ReleaseRecord;
import pl.czymogeaktualizowac.core.Model.RunResult;
import pl.czymogeaktualizowac.core.Model.Status;
import pl.czymogeaktualizowac.core.Model.WooInfo;
import pl.czymogeaktualizowac.core.Model.WpInfo;
import pl.czymogeaktualizowac.core.PluginZip;
import pl.czymogeaktualizowac.core.Store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lab logic that needs no Docker. The Docker-backed proof is `lab selftest`. */
class LabUnitTest {

    @TempDir
    Path root;

    private static LevelResult lr(Level l, Status s) {
        return new LevelResult(l, s, "", 0);
    }

    @Test
    void highestPassedIsContiguousFromL0_andSkippedIsNeverAPass() {
        assertEquals(Level.L2, LabRunner.highest(List.of(lr(Level.L0, Status.PASS), lr(Level.L1, Status.PASS), lr(Level.L2, Status.PASS),
                lr(Level.L3, Status.SKIPPED), lr(Level.L4, Status.SKIPPED))));
        assertEquals(Level.L0, LabRunner.highest(List.of(lr(Level.L0, Status.PASS), lr(Level.L1, Status.FAIL), lr(Level.L2, Status.PASS))),
                "a pass above a failure does not count");
        assertNull(LabRunner.highest(List.of(lr(Level.L0, Status.FAIL))));
        assertNull(LabRunner.highest(List.of(lr(Level.L0, Status.ERROR))));
    }

    @Test
    void fixturesAreValidPluginZips() throws IOException {
        for (String slug : List.of(Fixtures.OK, Fixtures.FATAL, Fixtures.CHECKOUT)) {
            Path zip = Fixtures.zip(slug, root);
            var h = PluginZip.headers(Files.readAllBytes(zip), slug);
            assertEquals("1.0.0", h.version());
            assertEquals("7.4", h.requiresPhp());
        }
    }

    @Test
    void planResolvesSymbolicVersionsAndSkipsExistingResults() throws IOException {
        Files.createDirectories(root.resolve("config"));
        Files.writeString(root.resolve("config/plugins.json"), "[{\"slug\":\"demo\",\"name\":\"Demo\",\"vendor\":\"V\",\"category\":\"shipping\"}]");
        Files.writeString(root.resolve("config/profiles.json"),
                "[{\"id\":\"P2\",\"name\":\"Main\",\"php\":\"8.2\",\"wp\":\"latest\",\"woo\":\"latest\",\"checkoutTypes\":[\"classic\",\"block\"]},"
                        + "{\"id\":\"P1\",\"name\":\"Legacy\",\"php\":\"8.1\",\"wp\":\"previous\",\"woo\":\"previous\",\"checkoutTypes\":[\"classic\"]},"
                        + "{\"id\":\"P4\",\"name\":\"Edge\",\"php\":\"8.4\",\"wp\":\"development\",\"woo\":\"prerelease\",\"checkoutTypes\":[\"block\"]}]");
        Store store = new Store(root);
        Instant now = Instant.parse("2026-11-06T00:00:00Z");
        Map<String, PluginState> plugins = new LinkedHashMap<>();
        plugins.put("demo", new PluginState("demo", "Demo", "V", "shipping", 1, "1.2.3", null, null, null, 0, 0, null, now,
                List.of(new ReleaseRecord("1.2.3", now, now, false, null, null, null, 0, null))));
        store.saveReleases(plugins, new Ecosystem(new WpInfo("7.1.4", "7.0.8", null), new WooInfo("11.2.2", "11.1.2", "11.3.0-beta.2"), List.of(), now), now);
        // a cached zip stands in for the download
        Path zips = root.resolve("cache/zips");
        Files.createDirectories(zips);
        Files.copy(Fixtures.zip(Fixtures.OK, root.resolve("tmp")), zips.resolve("demo.1.2.3.zip"));

        LabService svc = new LabService(store, s -> {
        }, true);
        List<RunSpec> all = svc.plan("demo", null, List.of(), Set.of(), "solo", false);
        assertEquals(4, all.size(), "P2 classic+block, P1 classic, P4 block");
        RunSpec p1 = all.stream().filter(s -> s.profileId().equals("P1")).findFirst().orElseThrow();
        assertEquals("7.0.8", p1.wp());
        assertEquals("11.1.2", p1.woo());
        RunSpec p4 = all.stream().filter(s -> s.profileId().equals("P4")).findFirst().orElseThrow();
        assertEquals("11.3.0-beta.2", p4.woo());
        assertEquals("7.1.4", p4.wp(), "no WordPress pre-release available: falls back to latest and says so");
        assertTrue(p4.notes().contains("WordPress"));

        // an existing, non-ERROR result is skipped; an ERROR result is retried
        RunResult ok = new RunResult("demo", "1.2.3", "P2", "solo", "classic", "8.2", "7.1.4", "11.2.2", now, now,
                List.of(lr(Level.L0, Status.PASS)), Level.L0, null, List.of());
        store.saveRun(ok);
        assertEquals(3, svc.plan("demo", null, List.of(), Set.of(), "solo", true).size());
        store.saveRun(new RunResult("demo", "1.2.3", "P2", "solo", "classic", "8.2", "7.1.4", "11.2.2", now, now,
                List.of(lr(Level.L0, Status.ERROR)), null, null, List.of()));
        assertEquals(4, svc.plan("demo", null, List.of(), Set.of(), "solo", true).size());
    }

    @Test
    void firstLineUnwrapsPlaywrightErrors() {
        String pw = "Error {\n  message='Timeout 45000ms exceeded.\n=========================== logs ===\n  name='TimeoutError'\n}";
        assertEquals("Timeout 45000ms exceeded.", StoreBrowser.firstLine(pw));
        assertEquals("plain message", StoreBrowser.firstLine("plain message\nsecond line"));
        assertEquals("", StoreBrowser.firstLine(null));
    }
}
