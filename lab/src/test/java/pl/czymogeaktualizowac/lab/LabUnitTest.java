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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lab logic that needs no Docker. The Docker-backed proof is `lab selftest`. */
class LabUnitTest {

    @TempDir
    Path root;

    private static LevelResult lr(Level l, Status s) {
        return new LevelResult(l, s, "", 0);
    }

    private static RunResult result(Status l2) {
        return new RunResult("demo", "1.0", "P3", "solo", "classic", "8.3", "7.1.4", "11.2.2", Instant.EPOCH, Instant.EPOCH,
                List.of(lr(Level.L0, Status.PASS), lr(Level.L1, Status.PASS), lr(Level.L2, l2)), null, null, List.of());
    }

    @Test
    void aFailureIsRecordedOnlyWhenTheRepeatReproducesIt() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        // passing run: no repeat
        assertEquals(Status.PASS, LabService.confirmFailures(() -> { calls.incrementAndGet(); return result(Status.PASS); }, s -> { })
                .levels().get(2).status());
        assertEquals(1, calls.get());
        // flaky: FAIL then PASS -> the PASS is kept and the first failure is only noted
        var seq = new java.util.ArrayDeque<>(List.of(result(Status.FAIL), result(Status.PASS)));
        RunResult flaky = LabService.confirmFailures(seq::poll, s -> { });
        assertEquals(Status.PASS, flaky.levels().get(2).status());
        assertTrue(flaky.notes().contains("nie potwierdziło"));
        // reproducible: FAIL twice -> FAIL, marked as confirmed
        var twice = new java.util.ArrayDeque<>(List.of(result(Status.FAIL), result(Status.FAIL)));
        RunResult confirmed = LabService.confirmFailures(twice::poll, s -> { });
        assertEquals(Status.FAIL, confirmed.levels().get(2).status());
        assertTrue(confirmed.notes().contains("potwierdzony"));
    }

    @Test
    void missingOrBrokenSandboxSecretsSkipL3InsteadOfBreakingTheRun() {
        // GitHub Actions passes a secret that does not exist as "", which must mean "no sandbox data"
        assertNull(LabRunner.sandboxJson(null));
        assertNull(LabRunner.sandboxJson(""));
        assertNull(LabRunner.sandboxJson("  \n"));
        assertEquals("{\"a\":1}", LabRunner.sandboxJson(" {\"a\":1}\n"));
        assertNull(LabRunner.sandboxJsonProblem("{\"testmode\":\"yes\"}"));
        assertEquals("niepoprawny JSON", LabRunner.sandboxJsonProblem("{\"pos_id\": 123"));
        assertEquals("to nie jest obiekt JSON", LabRunner.sandboxJsonProblem("[1,2]"));
        // the reason never contains the secret value
        assertFalse(LabRunner.sandboxJsonProblem("{\"client_secret\":\"s3cr3t\"").contains("s3cr3t"));
    }

    @Test
    void onlyDigestPinnedImagesAreAccepted() {
        String sha = "@sha256:" + "0123456789abcdef".repeat(4);
        assertTrue(LabEnv.pinned("wordpress:php8.3-apache" + sha));
        assertTrue(LabEnv.pinned("mariadb:11.4" + sha));
        assertFalse(LabEnv.pinned("wordpress:php8.3-apache"), "a tag alone can move");
        assertFalse(LabEnv.pinned("wordpress:php8.3-apache@sha256:abc"));
        assertFalse(LabEnv.pinned(null));
        assertEquals("mariadb:11.4", ImagePins.unpinned("mariadb:11.4" + sha));
    }

    @Test
    void activePluginsAreRecordedSortedAsSlugAtVersion() {
        String csv = "name,version\nwoocommerce,11.2.2\nflexible-invoices,7.1.3\n";
        assertEquals("flexible-invoices@7.1.3,woocommerce@11.2.2", LabRunner.pluginCsv(csv));
        assertEquals("", LabRunner.pluginCsv("name,version\n"));
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
        String digest = "@sha256:" + "a".repeat(64);
        Files.writeString(root.resolve("config/lab-images.json"), "{\"database\":\"mariadb:11.4" + digest + "\",\"wordpress\":{"
                + "\"8.1\":\"wordpress:php8.1-apache" + digest + "\",\"8.2\":\"wordpress:php8.2-apache" + digest + "\","
                + "\"8.4\":\"wordpress:php8.4-apache" + digest + "\"}}");
        Store store = new Store(root);
        Instant now = Instant.parse("2026-11-06T00:00:00Z");
        // a cached zip stands in for the download; the watcher recorded its sha256
        Path zips = root.resolve("cache/zips");
        Files.createDirectories(zips);
        Path fixture = Fixtures.zip(Fixtures.OK, root.resolve("tmp"));
        Files.copy(fixture, zips.resolve("demo.1.2.3.zip"));
        String sha = PluginZip.sha256(Files.readAllBytes(fixture));
        Map<String, PluginState> plugins = new LinkedHashMap<>();
        plugins.put("demo", new PluginState("demo", "Demo", "V", "shipping", 1, "1.2.3", null, null, null, 0, 0, null, now,
                List.of(new ReleaseRecord("1.2.3", now, now, false, null, null, sha, 0, null),
                        new ReleaseRecord("1.2.2", now, now, false, null, null, null, 0, null))));
        store.saveReleases(plugins, new Ecosystem(new WpInfo("7.1.4", "7.0.8", null), new WooInfo("11.2.2", "11.1.2", "11.3.0-beta.2"), List.of(), now), now);

        LabService svc = new LabService(store, s -> {
        }, true);
        // a release the watcher could not verify is never handed to the lab
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> svc.plan("demo", "1.2.2", List.of(), Set.of(), "solo", false));
        List<RunSpec> all = svc.plan("demo", null, List.of(), Set.of(), "solo", false);
        assertEquals(4, all.size(), "P2 classic+block, P1 classic, P4 block");
        RunSpec p1 = all.stream().filter(s -> s.profileId().equals("P1")).findFirst().orElseThrow();
        assertEquals("7.0.8", p1.wp());
        assertEquals("11.1.2", p1.woo());
        RunSpec p4 = all.stream().filter(s -> s.profileId().equals("P4")).findFirst().orElseThrow();
        assertEquals("11.3.0-beta.2", p4.woo());
        assertEquals("7.1.4", p4.wp(), "no WordPress pre-release available: falls back to latest and says so");
        assertTrue(p4.notes().contains("WordPress"));
        // every run gets the image pinned for its profile's PHP version
        assertEquals("wordpress:php8.1-apache" + digest, p1.wordpressImage());
        assertEquals("wordpress:php8.4-apache" + digest, p4.wordpressImage());
        assertEquals("mariadb:11.4" + digest, p4.databaseImage());

        // an existing, non-ERROR result is skipped; an ERROR result is retried
        RunResult ok = new RunResult("demo", "1.2.3", "P2", "solo", "classic", "8.2", "7.1.4", "11.2.2", now, now,
                List.of(lr(Level.L0, Status.PASS)), Level.L0, null, List.of());
        store.saveRun(ok);
        assertEquals(3, svc.plan("demo", null, List.of(), Set.of(), "solo", true).size());
        store.saveRun(new RunResult("demo", "1.2.3", "P2", "solo", "classic", "8.2", "7.1.4", "11.2.2", now, now,
                List.of(lr(Level.L0, Status.ERROR)), null, null, List.of()));
        assertEquals(4, svc.plan("demo", null, List.of(), Set.of(), "solo", true).size());

        // a zip that is not byte-identical to the verified one is refused (and dropped from the cache)
        Files.write(zips.resolve("demo.1.2.3.zip"), new byte[]{1, 2, 3});
        org.junit.jupiter.api.Assertions.assertThrows(LabEnv.InfraException.class,
                () -> svc.plan("demo", null, List.of(), Set.of(), "solo", false));
        assertTrue(Files.notExists(zips.resolve("demo.1.2.3.zip")));
    }

    @Test
    void firstLineUnwrapsPlaywrightErrors() {
        String pw = "Error {\n  message='Timeout 45000ms exceeded.\n=========================== logs ===\n  name='TimeoutError'\n}";
        assertEquals("Timeout 45000ms exceeded.", StoreBrowser.firstLine(pw));
        assertEquals("plain message", StoreBrowser.firstLine("plain message\nsecond line"));
        assertEquals("", StoreBrowser.firstLine(null));
    }

    @Test
    void aProfileBelowTheDeclaredMinimumIsNotApplicable() {
        // InPost 2.0.1 and Omnibus 3.0.5 declare "Requires PHP: 8.2"; P1 runs PHP 8.1
        var h = new pl.czymogeaktualizowac.core.Model.PluginHeaders("X", "2.0.1", "GPL", "8.2", "5.3", "9.0", null, "7.1", "2.0.1",
                List.of(), null);
        assertEquals("PHP 8.2 lub nowszego (ten profil: PHP 8.1)", LabRunner.unmetRequirement(h, "8.1", "7.0.9", "11.2.2"));
        assertNull(LabRunner.unmetRequirement(h, "8.2", "7.1.5", "11.3.1"));
        var wc = new pl.czymogeaktualizowac.core.Model.PluginHeaders("X", "1", "GPL", null, "6.0", "11.3", null, null, "1", List.of(), null);
        assertTrue(LabRunner.unmetRequirement(wc, "8.1", "7.0.9", "11.2.2").startsWith("WooCommerce 11.3"));
        assertNull(LabRunner.unmetRequirement(null, "8.1", "7.0.9", "11.2.2"), "unreadable headers: run the test");
        var blank = new pl.czymogeaktualizowac.core.Model.PluginHeaders("X", "1", "GPL", " ", null, null, null, null, "1", List.of(), null);
        assertNull(LabRunner.unmetRequirement(blank, "8.1", "7.2-RC3", "11.2.2"));
    }
}
