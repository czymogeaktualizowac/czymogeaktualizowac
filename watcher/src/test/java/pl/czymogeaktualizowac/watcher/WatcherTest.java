package pl.czymogeaktualizowac.watcher;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.czymogeaktualizowac.core.Json;
import pl.czymogeaktualizowac.core.Model.Ecosystem;
import pl.czymogeaktualizowac.core.Model.ForumTopic;
import pl.czymogeaktualizowac.core.Model.PluginState;
import pl.czymogeaktualizowac.core.Model.ReleaseRecord;
import pl.czymogeaktualizowac.core.Model.WooInfo;
import pl.czymogeaktualizowac.core.Model.WpInfo;
import pl.czymogeaktualizowac.core.PluginZip;
import pl.czymogeaktualizowac.core.Store;
import pl.czymogeaktualizowac.core.WpOrg;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Watcher behaviour against a fake WordPress.org (no network). */
class WatcherTest {

    @TempDir
    Path root;

    private static final String MAIN = "<?php\n/**\n * Plugin Name: Demo\n * Version: 1.0.1\n */\n";

    /** Stands in for WordPress.org; {@code brokenZips} / {@code brokenPlugins} simulate outages. */
    static final class FakeWpOrg extends WpOrg {
        final Set<String> brokenZips = new HashSet<>();
        final Set<String> brokenPlugins = new HashSet<>();
        final byte[] zip;

        FakeWpOrg(Path cache) throws IOException {
            super(null, cache);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ZipOutputStream z = new ZipOutputStream(bos)) {
                z.putNextEntry(new ZipEntry("demo/demo.php"));
                z.write(MAIN.getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
            zip = bos.toByteArray();
        }

        @Override
        public PluginInfo pluginInfo(String slug) throws IOException {
            if (brokenPlugins.contains(slug)) {
                throw new IOException("plugin '" + slug + "' not available: closed");
            }
            return new PluginInfo(slug, slug, "1.0.1", null, null, null, null, 100, 0, 0, null, List.of("1.0.0", "1.0.1"));
        }

        @Override
        public Map<String, Instant> tagDates(String slug) {
            return Map.of("1.0.1", Instant.parse("2026-11-01T00:00:00Z"));
        }

        @Override
        public List<ForumTopic> forumTopics(String slug) {
            return List.of();
        }

        @Override
        public JsonNode checksums(String slug, String version) {
            return Json.tree("{\"files\":{\"demo.php\":{\"sha256\":\"" + PluginZip.sha256(MAIN.getBytes(StandardCharsets.UTF_8)) + "\"}}}");
        }

        @Override
        public byte[] zip(String slug, String version) throws IOException {
            if (brokenZips.contains(slug)) {
                throw new IOException("HTTP 503");
            }
            return zip;
        }

        @Override
        public Ecosystem ecosystem(Instant now) {
            return new Ecosystem(new WpInfo("7.1.4", "7.0.8", null), new WooInfo("11.2.2", "11.1.2", null), List.of(), now);
        }
    }

    private Store store(String... slugs) throws IOException {
        Files.createDirectories(root.resolve("config"));
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < slugs.length; i++) {
            sb.append(i == 0 ? "" : ",").append("{\"slug\":\"").append(slugs[i]).append("\",\"name\":\"").append(slugs[i])
                    .append("\",\"vendor\":\"V\",\"category\":\"shipping\"}");
        }
        Files.writeString(root.resolve("config/plugins.json"), sb.append("]").toString());
        return new Store(root);
    }

    private static Watcher watcher(Store store, WpOrg wp, String at) {
        return new Watcher(store, wp, Clock.fixed(Instant.parse(at), ZoneOffset.UTC), s -> {
        });
    }

    private static ReleaseRecord release(Store store, String slug, String version) {
        PluginState st = store.loadReleases().plugins().get(slug);
        return st.releases().stream().filter(r -> r.version().equals(version)).findFirst().orElseThrow();
    }

    @Test
    void aFailedDownloadIsRetriedOnTheNextRunInsteadOfBeingRecordedForever() throws IOException {
        Store store = store("demo");
        FakeWpOrg wp = new FakeWpOrg(store.cacheDir);
        wp.brokenZips.add("demo");
        watcher(store, wp, "2026-11-02T00:00:00Z").run(Watcher.Options.defaults());
        ReleaseRecord failed = release(store, "demo", "1.0.1");
        assertEquals(Watcher.FETCH_FAILED, failed.checksum().status());
        assertNull(failed.zipSha256(), "an unverified release has no sha256, so the lab refuses it");

        wp.brokenZips.clear();
        watcher(store, wp, "2026-11-02T12:00:00Z").run(Watcher.Options.defaults());
        ReleaseRecord ok = release(store, "demo", "1.0.1");
        assertEquals("OK", ok.checksum().status());
        assertNotNull(ok.zipSha256());
        assertEquals(failed.firstSeen(), ok.firstSeen(), "a retry keeps the first-seen time");
    }

    @Test
    void oneBrokenPluginDoesNotStopTheOthersAndIsNotATotalFailure() throws IOException {
        Store store = store("demo", "closed-plugin");
        FakeWpOrg wp = new FakeWpOrg(store.cacheDir);
        wp.brokenPlugins.add("closed-plugin");
        Watcher.RunReport r = watcher(store, wp, "2026-11-02T00:00:00Z").run(Watcher.Options.defaults());
        assertEquals(1, r.errors().size());
        assertTrue(r.errors().get(0).startsWith("closed-plugin"));
        assertFalse(r.totalFailure());
        assertNotNull(store.loadReleases().plugins().get("demo"));

        wp.brokenPlugins.add("demo");
        assertTrue(watcher(store, wp, "2026-11-02T12:00:00Z").run(Watcher.Options.defaults()).totalFailure());
    }
}
