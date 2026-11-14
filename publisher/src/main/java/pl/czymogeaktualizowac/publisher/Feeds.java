package pl.czymogeaktualizowac.publisher;

import pl.czymogeaktualizowac.core.Json;
import pl.czymogeaktualizowac.core.Model.PluginHeaders;
import pl.czymogeaktualizowac.core.Model.PluginState;
import pl.czymogeaktualizowac.core.Model.ReleaseRecord;
import pl.czymogeaktualizowac.core.Model.ReleasesFile;
import pl.czymogeaktualizowac.core.Model.ResultsFile;
import pl.czymogeaktualizowac.core.Model.RunResult;
import pl.czymogeaktualizowac.core.Model.TrackedPlugin;
import pl.czymogeaktualizowac.publisher.View.Site;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** RSS, sitemap and the machine-readable feed (feed/v1/feed.json). The feed is unsigned until a plugin needs signatures. */
final class Feeds {

    record Item(String title, String link, String description, Instant date) {
    }

    private Feeds() {
    }

    static String rss(Site site, String title, String path, List<Item> items) {
        DateTimeFormatter rfc = DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.ENGLISH).withZone(ZoneOffset.UTC);
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<rss version=\"2.0\" xmlns:atom=\"http://www.w3.org/2005/Atom\"><channel>\n");
        sb.append("<title>").append(esc(title)).append(" | ").append(esc(site.name())).append("</title>\n");
        sb.append("<link>").append(esc(site.baseUrl() + path)).append("</link>\n");
        sb.append("<description>").append(esc(site.tagline())).append("</description>\n<language>pl</language>\n");
        sb.append("<atom:link href=\"").append(esc(site.baseUrl() + (path.equals("/") ? "/rss.xml" : path + "rss.xml")))
                .append("\" rel=\"self\" type=\"application/rss+xml\"/>\n");
        for (Item i : items) {
            sb.append("<item><title>").append(esc(i.title())).append("</title><link>").append(esc(i.link()))
                    .append("</link><guid isPermaLink=\"true\">").append(esc(i.link())).append("</guid>");
            if (i.date() != null) {
                sb.append("<pubDate>").append(rfc.format(i.date())).append("</pubDate>");
            }
            sb.append("<description>").append(esc(i.description())).append("</description></item>\n");
        }
        sb.append("</channel></rss>\n");
        return sb.toString();
    }

    static String sitemap(Site site, List<String> pages, Instant now) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        String lastmod = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC).format(now);
        pages.stream().filter(p -> p.endsWith("index.html") && !p.startsWith("ankieta/")).sorted().forEach(p -> {
            String loc = p.equals("index.html") ? "/" : "/" + p.substring(0, p.length() - "index.html".length());
            sb.append("<url><loc>").append(esc(site.baseUrl() + loc)).append("</loc><lastmod>").append(lastmod).append("</lastmod></url>\n");
        });
        return sb.append("</urlset>\n").toString();
    }

    static String feedJson(SiteBuilder.SiteConfig cfg, List<TrackedPlugin> tracked, ReleasesFile releases,
                           Map<String, ResultsFile> results, Instant now) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 1);
        root.put("generatedAt", now.toString());
        root.put("signature", null);
        root.put("site", cfg.baseUrl());
        root.put("ecosystem", releases.ecosystem());
        List<Object> plugins = new ArrayList<>();
        for (TrackedPlugin tp : tracked) {
            PluginState st = releases.plugins().get(tp.slug());
            if (st == null) {
                continue;
            }
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("slug", tp.slug());
            p.put("name", st.name());
            p.put("category", st.category());
            p.put("latestVersion", st.latestVersion());
            List<Object> rels = new ArrayList<>();
            for (ReleaseRecord r : st.releases()) {
                ResultsFile rf = results.get(tp.slug() + "|" + r.version());
                List<RunResult> runs = rf == null ? List.of() : rf.runs();
                View.Verdict v = Verdicts.of(r, runs);
                PluginHeaders h = r.headers();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("version", r.version());
                m.put("released", r.released() == null ? null : r.released().toString());
                m.put("verdict", v.code());
                m.put("verdictLabel", v.label());
                m.put("labSummary", Verdicts.labSummary(runs));
                m.put("forumLevel", r.forum() == null ? null : r.forum().level());
                m.put("forumTopics7d", r.forum() == null ? null : r.forum().topics7d());
                m.put("wcTested", h == null ? null : h.wcTested());
                m.put("requiresPhp", h == null ? null : h.requiresPhp());
                m.put("checksum", r.checksum() == null ? null : r.checksum().status());
                m.put("url", cfg.baseUrl() + "/pluginy/" + tp.slug() + "/" + SiteBuilder.seg(r.version()) + "/");
                rels.add(m);
            }
            p.put("releases", rels);
            plugins.add(p);
        }
        root.put("plugins", plugins);
        return Json.MAPPER.valueToTree(root).toPrettyString() + "\n";
    }

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
