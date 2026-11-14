package pl.czymogeaktualizowac.publisher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.czymogeaktualizowac.core.Json;
import pl.czymogeaktualizowac.core.Model.ChecksumResult;
import pl.czymogeaktualizowac.core.Model.Ecosystem;
import pl.czymogeaktualizowac.core.Model.ForumSignal;
import pl.czymogeaktualizowac.core.Model.ForumTopic;
import pl.czymogeaktualizowac.core.Model.PhpCycle;
import pl.czymogeaktualizowac.core.Model.PluginHeaders;
import pl.czymogeaktualizowac.core.Model.PluginState;
import pl.czymogeaktualizowac.core.Model.ReleaseRecord;
import pl.czymogeaktualizowac.core.Model.WooInfo;
import pl.czymogeaktualizowac.core.Model.WpInfo;
import pl.czymogeaktualizowac.core.Store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteBuilderTest {

    @TempDir
    Path root;

    @Test
    void rendersEscapedPagesFeedAndSitemapFromFixtureData() throws IOException {
        Files.createDirectories(root.resolve("config"));
        Files.writeString(root.resolve("config/plugins.json"),
                "[{\"slug\":\"evil\",\"name\":\"Evil <script>alert(1)</script>\",\"vendor\":\"V&Co\",\"category\":\"shipping\"}]");
        Files.writeString(root.resolve("config/profiles.json"), "[]");
        Instant now = Instant.parse("2026-11-06T12:00:00Z");
        Store store = new Store(root);

        PluginHeaders h = new PluginHeaders("Evil", "1.0.0", "GPL", "7.4", "6.0", "5.0", "11.2", "7.1", "1.0.0", List.of(), "= 1.0.0 =\n* <b>bold</b> change");
        ForumTopic topic = new ForumTopic("Błąd <img src=x onerror=alert(1)> po 1.0.0", "https://wordpress.org/support/topic/x/", now.minusSeconds(3600));
        ReleaseRecord rel = new ReleaseRecord("1.0.0", now.minusSeconds(86400), now, false, h,
                new ChecksumResult("OK", 3, List.of(), List.of(), "all ok"), "abc", 10,
                new ForumSignal(1, 1, "LOW", false, List.of(topic)));
        PluginState st = new PluginState("evil", "Evil <script>alert(1)</script>", "V&Co", "shipping", 1000, "1.0.0", "2026-11-05", "7.1", "7.4",
                0, 0, "https://example.invalid/<x>", now, List.of(rel));
        Map<String, PluginState> plugins = new LinkedHashMap<>();
        plugins.put("evil", st);
        Ecosystem eco = new Ecosystem(new WpInfo("7.1.4", "7.0.8", "7.2-beta4"), new WooInfo("11.2.2", "11.1.2", "11.3.0-beta.2"),
                List.of(new PhpCycle("8.2", "8.2.35", "2022-12-08", "2024-12-31", "2026-12-31")), now);
        store.saveReleases(plugins, eco, now);

        Path out = root.resolve("site-out");
        SiteBuilder.BuildResult r = new SiteBuilder().build(store, out, now);

        String plugin = Files.readString(out.resolve("pluginy/evil/index.html"), StandardCharsets.UTF_8);
        String release = Files.readString(out.resolve("pluginy/evil/1.0.0/index.html"), StandardCharsets.UTF_8);
        String index = Files.readString(out.resolve("index.html"), StandardCharsets.UTF_8);

        for (String html : List.of(plugin, release, index)) {
            assertFalse(html.contains("<script>alert(1)</script>"), "plugin names must be HTML-escaped");
            assertFalse(html.contains("<img src=x onerror"), "forum titles must be HTML-escaped");
        }
        assertTrue(release.contains("&lt;img src=x onerror=alert(1)&gt;"));
        assertTrue(index.contains("Czy mogę zaktualizować?"));
        assertTrue(Files.exists(out.resolve("kompatybilnosc/woocommerce-11.3.0-beta.2/index.html")), "pre-release compat page");
        assertTrue(Files.exists(out.resolve("kompatybilnosc/php/index.html")));
        assertTrue(Files.exists(out.resolve("feed/v1/feed.json")));
        assertTrue(Files.exists(out.resolve("pluginy/evil/rss.xml")));
        assertTrue(Files.exists(out.resolve("CNAME")));

        String sitemap = Files.readString(out.resolve("sitemap.xml"), StandardCharsets.UTF_8);
        assertTrue(sitemap.contains("https://czymogeaktualizowac.pl/pluginy/evil/1.0.0/"));
        assertFalse(sitemap.contains("/ankieta/"), "survey pages are noindex and out of the sitemap");
        assertFalse(sitemap.contains("404"));

        // the signup form is a disabled preview until a provider form URL is configured
        String signup = Files.readString(out.resolve("powiadomienia/index.html"), StandardCharsets.UTF_8);
        assertTrue(signup.contains("Zapisy ruszą wkrótce"));

        // verdict honesty: a plugin without lab runs is never presented as OK
        assertFalse(plugin.contains("badge ok"));
        assertTrue(Json.tree(Files.readString(out.resolve("feed/v1/feed.json"))).path("schemaVersion").asInt() == 1);
        assertTrue(r.pages() > 10);
    }
}
