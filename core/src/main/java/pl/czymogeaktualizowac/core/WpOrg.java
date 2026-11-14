package pl.czymogeaktualizowac.core;

import com.fasterxml.jackson.databind.JsonNode;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import pl.czymogeaktualizowac.core.Model.Ecosystem;
import pl.czymogeaktualizowac.core.Model.ForumTopic;
import pl.czymogeaktualizowac.core.Model.PhpCycle;
import pl.czymogeaktualizowac.core.Model.WooInfo;
import pl.czymogeaktualizowac.core.Model.WpInfo;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Client for the public, read-only WordPress.org endpoints we depend on (see Spike 0 notes in docs/spike0.md). */
public final class WpOrg {

    private final Http http;
    private final Path cacheDir;

    public WpOrg(Http http, Path cacheDir) {
        this.http = http;
        this.cacheDir = cacheDir;
    }

    /** Subset of the plugins API response we use. */
    public record PluginInfo(
            String slug, String name, String version, String lastUpdated, String testedWp, String requires,
            String requiresPhp, long activeInstalls, int supportThreads, int supportThreadsResolved,
            String homepage, List<String> versions) {
    }

    public PluginInfo pluginInfo(String slug) throws IOException {
        String url = "https://api.wordpress.org/plugins/info/1.2/?action=plugin_information"
                + "&request%5Bslug%5D=" + enc(slug)
                + "&request%5Bfields%5D%5Bactive_installs%5D=1&request%5Bfields%5D%5Bversions%5D=1"
                + "&request%5Bfields%5D%5Bsections%5D=0&request%5Bfields%5D%5Bbanners%5D=0&request%5Bfields%5D%5Bicons%5D=0";
        JsonNode n = Json.tree(http.getString(url));
        if (n.has("error") || !n.has("version")) {
            throw new IOException("plugin '" + slug + "' not available: " + n.path("error").asText("unknown error"));
        }
        List<String> versions = new ArrayList<>();
        JsonNode v = n.get("versions");
        if (v != null && v.isObject()) {
            v.fieldNames().forEachRemaining(name -> {
                if (!"trunk".equals(name)) {
                    versions.add(name);
                }
            });
        }
        versions.sort(Versions.ORDER);
        return new PluginInfo(
                n.path("slug").asText(slug), clean(n.path("name").asText()), n.path("version").asText(),
                n.path("last_updated").asText(null), n.path("tested").asText(null), n.path("requires").asText(null),
                n.path("requires_php").asText(null), n.path("active_installs").asLong(0),
                n.path("support_threads").asInt(0), n.path("support_threads_resolved").asInt(0),
                n.path("homepage").asText(null), versions);
    }

    /** Tag creation times from the WordPress.org SVN (one PROPFIND per plugin). Key = version string. */
    public Map<String, Instant> tagDates(String slug) throws IOException {
        String body = "<?xml version=\"1.0\"?><D:propfind xmlns:D=\"DAV:\"><D:prop><D:creationdate/></D:prop></D:propfind>";
        Http.Response r = http.propfind("https://plugins.svn.wordpress.org/" + enc(slug) + "/tags/", body, 1);
        Document doc = parseXml(r.body());
        NodeList responses = doc.getElementsByTagNameNS("DAV:", "response");
        Map<String, Instant> out = new HashMap<>();
        for (int i = 0; i < responses.getLength(); i++) {
            Element resp = (Element) responses.item(i);
            String href = text(resp, "href");
            String created = text(resp, "creationdate");
            if (href == null || created == null) {
                continue;
            }
            String path = href.endsWith("/") ? href.substring(0, href.length() - 1) : href;
            String version = path.substring(path.lastIndexOf('/') + 1);
            if (version.equals("tags") || version.isBlank()) {
                continue;
            }
            try {
                out.put(java.net.URLDecoder.decode(version, StandardCharsets.UTF_8), Instant.parse(created));
            } catch (RuntimeException ignored) {
                // unparsable date: leave the version without a date
            }
        }
        return out;
    }

    /** Published per-version checksums, or null when WordPress.org has none (404). */
    public JsonNode checksums(String slug, String version) throws IOException {
        try {
            return Json.tree(http.getString("https://downloads.wordpress.org/plugin-checksums/" + enc(slug) + "/" + enc(version) + ".json"));
        } catch (Http.HttpException e) {
            if (e.status == 404) {
                return null;
            }
            throw e;
        }
    }

    /** Downloads (or reads from cache) the zip of a plugin version. */
    public byte[] zip(String slug, String version) throws IOException {
        Path file = cacheDir.resolve("zips").resolve(slug + "." + version + ".zip");
        if (Files.exists(file)) {
            return Files.readAllBytes(file);
        }
        byte[] data = http.getBytes("https://downloads.wordpress.org/plugin/" + enc(slug) + "." + enc(version) + ".zip");
        Files.createDirectories(file.getParent());
        Files.write(file, data);
        return data;
    }

    public Path zipPath(String slug, String version) {
        return cacheDir.resolve("zips").resolve(slug + "." + version + ".zip");
    }

    /** Latest topics from the plugin's public support forum (RSS, at most ~30 items). */
    public List<ForumTopic> forumTopics(String slug) throws IOException {
        Http.Response r = http.get("https://wordpress.org/support/plugin/" + enc(slug) + "/feed/");
        Document doc = parseXml(r.body());
        NodeList items = doc.getElementsByTagName("item");
        List<ForumTopic> out = new ArrayList<>();
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            String title = text(item, "title");
            String link = text(item, "link");
            String date = text(item, "pubDate");
            if (title == null || date == null) {
                continue;
            }
            try {
                out.add(new ForumTopic(title.trim(), link, ZonedDateTime.parse(date.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()));
            } catch (RuntimeException ignored) {
                // skip items with unparsable dates
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ ecosystem

    public Ecosystem ecosystem(Instant now) throws IOException {
        return new Ecosystem(wp(), woo(), php(), now);
    }

    WpInfo wp() throws IOException {
        JsonNode stable = Json.tree(http.getString("https://api.wordpress.org/core/stable-check/1.0/"));
        List<String> versions = new ArrayList<>();
        String latest = null;
        var it = stable.fields();
        while (it.hasNext()) {
            var en = it.next();
            if (!Versions.isPrerelease(en.getKey())) {
                versions.add(en.getKey());
            }
            if ("latest".equals(en.getValue().asText())) {
                latest = en.getKey();
            }
        }
        if (latest == null) {
            versions.sort(Versions.ORDER);
            latest = versions.get(versions.size() - 1);
        }
        String development = null;
        try {
            JsonNode beta = Json.tree(http.getString("https://api.wordpress.org/core/version-check/1.7/?channel=beta"));
            String v = beta.path("offers").path(0).path("version").asText(null);
            if (v != null && (v.contains("beta") || v.contains("RC") || v.contains("rc"))) {
                development = v;
            }
        } catch (IOException ignored) {
            // development channel is optional
        }
        return new WpInfo(latest, previousLine(versions, latest), development);
    }

    WooInfo woo() throws IOException {
        PluginInfo info = pluginInfo("woocommerce");
        List<String> stable = info.versions().stream().filter(v -> !Versions.isPrerelease(v)).toList();
        String prerelease = info.versions().stream()
                .filter(Versions::isPrerelease)
                .filter(v -> Versions.compare(v, info.version()) > 0)
                .max(Versions.ORDER).orElse(null);
        return new WooInfo(info.version(), previousLine(stable, info.version()), prerelease);
    }

    List<PhpCycle> php() throws IOException {
        JsonNode n = Json.tree(http.getString("https://endoflife.date/api/php.json"));
        List<PhpCycle> out = new ArrayList<>();
        for (JsonNode c : n) {
            String cycle = c.path("cycle").asText();
            if (Versions.compare(cycle, "7.4") < 0) {
                continue;
            }
            out.add(new PhpCycle(cycle, c.path("latest").asText(null), c.path("releaseDate").asText(null),
                    c.path("support").asText(null), c.path("eol").asText(null)));
        }
        out.sort((a, b) -> Versions.compare(b.cycle(), a.cycle()));
        return out;
    }

    /** Highest stable version in a release line (major.minor) lower than the latest line. */
    static String previousLine(List<String> stableVersions, String latest) {
        String line = Versions.majorMinor(latest);
        return stableVersions.stream()
                .filter(v -> Versions.compare(Versions.majorMinor(v), line) < 0)
                .max(Versions.ORDER).orElse(null);
    }

    // ------------------------------------------------------------------ xml helpers

    private static Document parseXml(byte[] data) throws IOException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            // hardening against XXE: remote content is untrusted
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            return f.newDocumentBuilder().parse(new ByteArrayInputStream(data));
        } catch (Exception e) {
            throw new IOException("cannot parse XML: " + e.getMessage(), e);
        }
    }

    private static String text(Element parent, String localName) {
        NodeList nl = parent.getElementsByTagNameNS("*", localName);
        if (nl.getLength() == 0) {
            nl = parent.getElementsByTagName(localName);
        }
        return nl.getLength() == 0 ? null : nl.item(0).getTextContent();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String clean(String s) {
        return s == null ? null : s.replace("&#8211;", "–").replace("&#8212;", "—").replace("&amp;", "&").trim();
    }
}
