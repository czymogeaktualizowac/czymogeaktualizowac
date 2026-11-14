package pl.czymogeaktualizowac.watcher;

import com.fasterxml.jackson.databind.JsonNode;
import pl.czymogeaktualizowac.core.Model.ChecksumResult;
import pl.czymogeaktualizowac.core.Model.Ecosystem;
import pl.czymogeaktualizowac.core.Model.ForumTopic;
import pl.czymogeaktualizowac.core.Model.PluginHeaders;
import pl.czymogeaktualizowac.core.Model.TrackedPlugin;
import pl.czymogeaktualizowac.core.PluginZip;
import pl.czymogeaktualizowac.core.Store;
import pl.czymogeaktualizowac.core.Versions;
import pl.czymogeaktualizowac.core.WpOrg;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Spike 0 as code: checks every assumption the plan depends on against the live endpoints and renders a Markdown
 * report (data availability per tracked plugin, release frequency over 12 months, ecosystem cadence).
 */
public final class SpikeReport {

    private final Store store;
    private final WpOrg wp;
    private final Clock clock;
    private final Consumer<String> log;

    public SpikeReport(Store store, WpOrg wp, Clock clock, Consumer<String> log) {
        this.store = store;
        this.wp = wp;
        this.clock = clock;
        this.log = log;
    }

    public String render() {
        Instant now = clock.instant();
        StringBuilder md = new StringBuilder();
        md.append("# Spike 0 report (generated)\n\n")
                .append("Generated ").append(now).append(" by `spike`. Every row below was measured against live endpoints.\n\n");

        md.append("## Tracked plugins\n\n")
                .append("| Plugin | Latest | Installs | PHP req. | WC tested up to | Checksums | Forum topics | Tags 12 m | Releases / month | Note |\n")
                .append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        int ok = 0;
        int total = 0;
        for (TrackedPlugin tp : store.trackedPlugins()) {
            total++;
            log.accept("spike " + tp.slug());
            try {
                WpOrg.PluginInfo info = wp.pluginInfo(tp.slug());
                Map<String, Instant> tags = Map.of();
                String tagNote = "";
                try {
                    tags = wp.tagDates(tp.slug());
                } catch (IOException e) {
                    tagNote = "tag dates: " + e.getMessage();
                }
                Instant yearAgo = now.minus(Duration.ofDays(365));
                long last12 = tags.values().stream().filter(d -> d.isAfter(yearAgo)).count();
                String forum;
                try {
                    List<ForumTopic> t = wp.forumTopics(tp.slug());
                    forum = t.size() + " (feed OK)";
                } catch (IOException e) {
                    forum = "n/a: " + e.getMessage();
                }
                PluginHeaders h = null;
                ChecksumResult cs = null;
                String hdrNote = "";
                try {
                    byte[] zip = wp.zip(tp.slug(), info.version());
                    JsonNode checks = wp.checksums(tp.slug(), info.version());
                    cs = PluginZip.verify(zip, tp.slug(), checks);
                    h = PluginZip.headers(zip, tp.slug());
                } catch (IOException e) {
                    hdrNote = "zip: " + e.getMessage();
                }
                ok++;
                md.append("| ").append(tp.slug()).append(" | ").append(info.version())
                        .append(" | ").append(info.activeInstalls())
                        .append(" | ").append(h == null ? "?" : orDash(h.requiresPhp()))
                        .append(" | ").append(h == null ? "?" : orDash(h.wcTested()))
                        .append(" | ").append(cs == null ? "?" : cs.status() + " (" + cs.filesChecked() + ")")
                        .append(" | ").append(forum)
                        .append(" | ").append(tags.isEmpty() ? "?" : String.valueOf(last12))
                        .append(" | ").append(tags.isEmpty() ? "?" : String.format("%.1f", last12 / 12.0))
                        .append(" | ").append((tagNote + " " + hdrNote).trim())
                        .append(" |\n");
            } catch (IOException | RuntimeException e) {
                md.append("| ").append(tp.slug()).append(" | **NOT AVAILABLE** | | | | | | | | ").append(e.getMessage()).append(" |\n");
            }
        }
        md.append("\n").append(ok).append(" of ").append(total).append(" tracked plugins answered the plugin API.\n\n");

        md.append("## Ecosystem\n\n");
        try {
            Ecosystem eco = wp.ecosystem(now);
            md.append("- WordPress: latest **").append(eco.wp().latest()).append("**, previous line ")
                    .append(eco.wp().previousMajorLatest()).append(", development ").append(eco.wp().development()).append("\n")
                    .append("- WooCommerce: latest **").append(eco.woo().latest()).append("**, previous line ")
                    .append(eco.woo().previousMajorLatest()).append(", pre-release ").append(eco.woo().prerelease()).append("\n")
                    .append("- PHP cycles: ");
            eco.php().stream().limit(5).forEach(c -> md.append(c.cycle()).append(" (latest ").append(c.latest())
                    .append(", security support until ").append(c.eol()).append(", active ").append(c.activeSupportUntil()).append("); "));
            md.append("\n\n");
            WpOrg.PluginInfo woo = wp.pluginInfo("woocommerce");
            Instant yearAgo = now.minus(Duration.ofDays(365));
            Map<String, Instant> wooTags = wp.tagDates("woocommerce");
            long majors = wooTags.entrySet().stream()
                    .filter(e -> e.getValue().isAfter(yearAgo) && e.getKey().matches("\\d+\\.\\d+\\.0"))
                    .count();
            long all = wooTags.entrySet().stream().filter(e -> e.getValue().isAfter(yearAgo) && !Versions.isPrerelease(e.getKey())).count();
            md.append("- WooCommerce releases in the last 12 months (tags): ").append(all).append(" stable, of which ").append(majors)
                    .append(" are x.y.0 feature releases (").append(woo.versions().size()).append(" versions in total).\n");
        } catch (IOException | RuntimeException e) {
            md.append("ecosystem probe failed: ").append(e.getMessage()).append("\n");
        }
        md.append("\n## Notes\n\n")
                .append("- Release dates come from the creation time of the SVN tag (WebDAV PROPFIND on `plugins.svn.wordpress.org/<slug>/tags/`); ")
                .append("the plugins API has no per-version dates and Trac returns 403.\n")
                .append("- Przelewy24 and Tpay do not publish their official plugins on WordPress.org and are therefore not tracked.\n");
        return md.toString();
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "–" : s;
    }
}
