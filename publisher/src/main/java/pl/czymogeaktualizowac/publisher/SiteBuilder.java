package pl.czymogeaktualizowac.publisher;

import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import gg.jte.output.StringOutput;
import gg.jte.resolve.ResourceCodeResolver;
import pl.czymogeaktualizowac.core.Json;
import pl.czymogeaktualizowac.core.Model.Ecosystem;
import pl.czymogeaktualizowac.core.Model.PluginHeaders;
import pl.czymogeaktualizowac.core.Model.PluginState;
import pl.czymogeaktualizowac.core.Model.PhpCycle;
import pl.czymogeaktualizowac.core.Model.ReleaseRecord;
import pl.czymogeaktualizowac.core.Model.RunResult;
import pl.czymogeaktualizowac.core.Model.TrackedPlugin;
import pl.czymogeaktualizowac.core.Store;
import pl.czymogeaktualizowac.core.Versions;
import pl.czymogeaktualizowac.publisher.View.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Component C/D: renders the static status site from data/ + config/ into an output directory. */
public final class SiteBuilder {

    /** config/site.json. Missing fields fall back to sensible defaults; signupFormUrl stays empty until Phase 0. */
    public record SiteConfig(String baseUrl, String name, String tagline, String contactEmail, String signupFormUrl,
                             Boolean pluginCtaEnabled, String analyticsHtml) {
        static SiteConfig defaults() {
            return new SiteConfig("https://czymogeaktualizowac.pl", "Czy mogę zaktualizować?",
                    "Niezależne testy wydań polskich wtyczek WooCommerce", "kontakt@czymogeaktualizowac.pl", "", false, "");
        }
    }

    public record BuildResult(int pages, Path outDir, List<String> paths) {
    }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("pl-PL"))
            .withZone(ZoneId.of("Europe/Warsaw"));
    private static final Map<String, String> CATEGORY = Map.of(
            "shipping", "Wysyłka", "payment", "Płatności", "invoicing", "Faktury / KSeF",
            "compliance", "Zgodność z prawem (Omnibus)", "reviews", "Opinie klientów", "other", "Inne");

    private final TemplateEngine engine;
    private final List<String> written = new ArrayList<>();

    public SiteBuilder() {
        try {
            Path classes = Files.createTempDirectory("jte-classes");
            this.engine = TemplateEngine.create(new ResourceCodeResolver("jte", SiteBuilder.class.getClassLoader()), classes,
                    ContentType.Html, SiteBuilder.class.getClassLoader());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static SiteConfig loadConfig(Store store) {
        Path p = store.configDir.resolve("site.json");
        if (!Files.exists(p)) {
            return SiteConfig.defaults();
        }
        SiteConfig c = Json.read(p, SiteConfig.class);
        SiteConfig d = SiteConfig.defaults();
        return new SiteConfig(or(c.baseUrl(), d.baseUrl()), or(c.name(), d.name()), or(c.tagline(), d.tagline()),
                or(c.contactEmail(), d.contactEmail()), or(c.signupFormUrl(), ""), Boolean.TRUE.equals(c.pluginCtaEnabled()),
                or(c.analyticsHtml(), ""));
    }

    public BuildResult build(Store store, Path out, Instant now) throws IOException {
        SiteConfig cfg = loadConfig(store);
        List<TrackedPlugin> tracked = store.trackedPlugins();
        Published pub = Published.of(store, now);
        Ecosystem eco = pub.ecosystem();

        Site site = new Site(cfg.baseUrl(), cfg.name(), cfg.tagline(), cfg.contactEmail(),
                cfg.signupFormUrl().isBlank() ? "#" : cfg.signupFormUrl(), !cfg.signupFormUrl().isBlank(),
                Boolean.TRUE.equals(cfg.pluginCtaEnabled()), cfg.analyticsHtml(), DATE.format(now),
                List.of(new NavItem("Wtyczki", "/"), new NavItem("Zgodność", "/kompatybilnosc/"),
                        new NavItem("Jak testujemy", "/jak-testujemy/"), new NavItem("Powiadomienia", "/powiadomienia/")));

        if (Files.exists(out)) {
            deleteTree(out);
        }
        Files.createDirectories(out);

        List<PluginOption> options = tracked.stream().map(t -> new PluginOption(t.slug(), t.name(), false)).toList();
        List<PluginRow> rows = new ArrayList<>();

        for (Published.Plugin p : pub.plugins()) {
            TrackedPlugin tp = p.tracked();
            PluginState st = p.state();
            PluginRow row = pluginRow(p);
            rows.add(row);

            List<ReleaseRow> relRows = new ArrayList<>();
            for (Published.Release rel : p.releases()) {
                relRows.add(releaseRow(tp.slug(), rel));
                writeRelease(out, site, p, rel);
            }
            List<PluginOption> opt = options.stream()
                    .map(o -> new PluginOption(o.slug(), o.name(), o.slug().equals(tp.slug()))).toList();
            PluginPage page = new PluginPage(site,
                    new Meta(st.name() + ": czy mogę zaktualizować? Testy wydań i zgłoszenia | " + cfg.name(),
                            "Wyniki testów i zgłoszenia użytkowników dla kolejnych wydań wtyczki " + st.name()
                                    + ". Najnowsze: " + st.latestVersion() + ". " + row.verdict().label() + ".",
                            "/pluginy/" + tp.slug() + "/", false),
                    row, List.of(new Crumb("Wtyczki", "/"), new Crumb(st.name(), null)), relRows, tp.note(),
                    st.homepage(), "powiadomienia", opt);
            render(out, "pluginy/" + tp.slug() + "/index.html", "plugin.jte", page);
            write(out, "pluginy/" + tp.slug() + "/rss.xml", Feeds.rss(site, "Wydania: " + st.name(),
                    "/pluginy/" + tp.slug() + "/", feedItems(site, p)));
        }

        // index
        int plugins12m = rows.size();
        render(out, "index.html", "index.jte", new IndexPage(site,
                new Meta("Czy mogę zaktualizować? Testy wydań polskich wtyczek WooCommerce",
                        "Niezależne testy wydań polskich wtyczek WooCommerce: InPost, Furgonetka, bramki płatności, Omnibus. Sprawdź wynik testów i zgłoszenia użytkowników, zanim zaktualizujesz.",
                        "/", false),
                rows, ecosystemRows(eco), plugins12m));

        // compatibility pages
        List<Link> compatLinks = new ArrayList<>();
        if (eco != null) {
            compatLinks.add(compatEcosystem(out, site, pub, "woocommerce", eco.woo().latest(), false));
            if (eco.woo().prerelease() != null) {
                compatLinks.add(compatEcosystem(out, site, pub, "woocommerce", eco.woo().prerelease(), true));
            }
            compatLinks.add(compatEcosystem(out, site, pub, "wordpress", eco.wp().latest(), false));
            if (eco.wp().development() != null) {
                compatLinks.add(compatEcosystem(out, site, pub, "wordpress", eco.wp().development(), true));
            }
            compatLinks.add(new Link("PHP: koniec wsparcia i wtyczki, które wymagają nowszej wersji", "/kompatybilnosc/php/",
                    "daty EOL z endoflife.date"));
            writePhp(out, site, eco, pub, now);
        }
        render(out, "kompatybilnosc/index.html", "compat-index.jte", new CompatIndexPage(site,
                new Meta("Zgodność polskich wtyczek z nowym WooCommerce, WordPress i PHP | " + cfg.name(),
                        "Status śledzonych polskich wtyczek wobec najnowszych (i testowych) wersji WooCommerce, WordPressa i PHP.",
                        "/kompatybilnosc/", false),
                List.of(new Crumb("Wtyczki", "/"), new Crumb("Zgodność", null)), compatLinks));

        // static pages
        render(out, "jak-testujemy/index.html", "how.jte", simple(site, "Jak testujemy wydania wtyczek WooCommerce | " + cfg.name(),
                "Co sprawdzamy (poziomy L0–L4), w jakich konfiguracjach (profile P1–P4) i czego nie robimy.", "/jak-testujemy/", "Jak testujemy"));
        render(out, "powiadomienia/index.html", "signup.jte", new SignupPage(site,
                new Meta("Powiadomienia o problemach z wtyczkami WooCommerce | " + cfg.name(),
                        "Zapisz się na alerty o wadliwych wydaniach polskich wtyczek WooCommerce i o zgodności z nowymi wersjami.",
                        "/powiadomienia/", false),
                List.of(new Crumb("Wtyczki", "/"), new Crumb("Powiadomienia", null)), options));
        render(out, "polityka-prywatnosci/index.html", "privacy.jte", simple(site, "Polityka prywatności | " + cfg.name(),
                "Jakie dane przetwarzamy przy zapisie na powiadomienia i w statystykach strony.", "/polityka-prywatnosci/", "Polityka prywatności"));
        render(out, "ankieta/tak/index.html", "thanks.jte", thanks(site, "Dziękujemy za odpowiedź: aktualizacja zepsuła Twój sklep",
                "Zapisaliśmy to jako anonimowe zdarzenie. Dzięki temu wiemy, jak często aktualizacje naprawdę sprawiają problemy.", "/ankieta/tak/"));
        render(out, "ankieta/nie/index.html", "thanks.jte", thanks(site, "Dziękujemy za odpowiedź: aktualizacje nie zepsuły Twojego sklepu",
                "Zapisaliśmy to jako anonimowe zdarzenie. Dzięki temu wiemy, jak często aktualizacje naprawdę sprawiają problemy.", "/ankieta/nie/"));
        render(out, "404.html", "notfound.jte", simple(site, "Nie znaleziono strony | " + cfg.name(), "Nie znaleziono strony.", "/404.html", "404"));

        // machine-readable and technical files
        write(out, "rss.xml", Feeds.rss(site, "Wszystkie wydania śledzonych wtyczek", "/", globalFeedItems(site, pub)));
        write(out, "feed/v1/feed.json", Feeds.feedJson(cfg, pub, now));
        write(out, "robots.txt", "User-agent: *\nAllow: /\nDisallow: /ankieta/\n\nSitemap: " + cfg.baseUrl() + "/sitemap.xml\n");
        write(out, "CNAME", cfg.baseUrl().replaceFirst("^https?://", "") + "\n");
        write(out, ".nojekyll", "");
        copyResource("assets/style.css", out.resolve("assets/style.css"));
        written.add("assets/style.css");
        write(out, "sitemap.xml", Feeds.sitemap(site, written, now));
        return new BuildResult(written.size(), out, List.copyOf(written));
    }

    // ------------------------------------------------------------------ pages

    private void writeRelease(Path out, Site site, Published.Plugin p, Published.Release pr) throws IOException {
        TrackedPlugin tp = p.tracked();
        PluginState st = p.state();
        ReleaseRecord rel = pr.record();
        PluginHeaders h = rel.headers();
        View.Verdict verdict = pr.verdict();
        List<TopicRow> topics = rel.forum() == null ? List.of() : rel.forum().sample().stream()
                .map(t -> new TopicRow(t.title(), t.link(), DATE.format(t.date()))).toList();
        List<RunRow> runRows = pr.runs().stream().map(this::runRow).toList();
        String path = "/pluginy/" + tp.slug() + "/" + seg(rel.version()) + "/";
        ReleasePage page = new ReleasePage(site,
                new Meta(st.name() + " " + rel.version() + ": czy mogę zaktualizować? | " + site.name(),
                        st.name() + " " + rel.version() + ": " + verdict.label() + ". Wyniki testów, zgłoszenia na forum i dane wydania.",
                        path, false),
                List.of(new Crumb("Wtyczki", "/"), new Crumb(st.name(), "/pluginy/" + tp.slug() + "/"), new Crumb(rel.version(), null)),
                st.name(), tp.slug(), rel.version(), date(rel.released()), verdict, Verdicts.forumText(rel.forum()), topics, runRows,
                h == null ? null : h.changelogHead(), dash(h == null ? null : h.requiresPhp()), dash(h == null ? null : h.wcRequires()),
                dash(h == null ? null : h.wcTested()), dash(h == null ? null : h.testedWp()), dash(h == null ? null : h.license()),
                h == null || h.requiresPlugins() == null ? List.of() : h.requiresPlugins(),
                rel.checksum() == null ? "–" : Verdicts.checksumText(rel.checksum()),
                rel.zipSha256() == null ? "–" : rel.zipSha256(), "https://wordpress.org/plugins/" + tp.slug() + "/", rel.prerelease(),
                pr.held());
        render(out, "pluginy/" + tp.slug() + "/" + seg(rel.version()) + "/index.html", "release.jte", page);
    }

    private RunRow runRow(RunResult r) {
        List<LevelRow> levels = r.levels().stream().map(l -> new LevelRow(l.level().name(), l.level().label,
                l.notApplicable() ? "nie dotyczy" : statusPl(l.status().name()), statusCss(l.status().name()),
                l.detail() == null ? "" : l.detail())).toList();
        return new RunRow(r.profileId(), r.php(), r.wp(), r.woo(), Verdicts.checkoutPl(r.checkout()),
                r.mode().equals("stack") ? "pełny zestaw wtyczek" : "wtyczka solo",
                Verdicts.hasLabError(r) ? "nieznany (błąd środowiska, nie wtyczki)"
                        : Verdicts.notApplicable(r) ? "nie dotyczy (nie testowano: wymagania wtyczki)"
                        : r.highestPassed() == null ? "żaden (błąd L0)" : r.highestPassed().name(), levels);
    }

    private PluginRow pluginRow(Published.Plugin p) {
        PluginState st = p.state();
        ReleaseRecord latest = p.latest().record();
        return new PluginRow(p.slug(), st.name(), nz(st.vendor()), CATEGORY.getOrDefault(st.category(), "Inne"),
                installs(st.activeInstalls()), latest.version(), date(latest.released()), p.latest().verdict(),
                Verdicts.forumText(latest.forum()), Verdicts.forumCss(latest.forum()), "/pluginy/" + p.slug() + "/",
                p.latest().labSummary());
    }

    private ReleaseRow releaseRow(String slug, Published.Release pr) {
        ReleaseRecord rel = pr.record();
        PluginHeaders h = rel.headers();
        return new ReleaseRow(rel.version(), date(rel.released()), pr.verdict(), pr.labSummary(),
                Verdicts.forumText(rel.forum()), Verdicts.forumCss(rel.forum()), dash(h == null ? null : h.wcTested()),
                dash(h == null ? null : h.requiresPhp()), rel.checksum() == null ? "–" : rel.checksum().status(),
                rel.prerelease(), "/pluginy/" + slug + "/" + seg(rel.version()) + "/");
    }

    private Link compatEcosystem(Path out, Site site, Published pub, String product, String version, boolean pre) throws IOException {
        boolean woo = product.equals("woocommerce");
        String productName = woo ? "WooCommerce" : "WordPress";
        String heading = productName + " " + version + (pre ? " (wersja testowa)" : "") + ": status polskich wtyczek";
        List<CompatItem> items = new ArrayList<>();
        for (Published.Plugin p : pub.plugins()) {
            TrackedPlugin tp = p.tracked();
            PluginState st = p.state();
            ReleaseRecord latest = p.latest().record();
            PluginHeaders h = latest.headers();
            String declaredVal = h == null ? null : (woo ? h.wcTested() : h.testedWp());
            String declared;
            String css;
            if (declaredVal == null) {
                declared = "autor nie deklaruje";
                css = "none";
            } else if (Versions.compare(Versions.majorMinor(version), Versions.majorMinor(declaredVal)) <= 0) {
                declared = "deklaruje zgodność (do " + declaredVal + ")";
                css = "ok";
            } else {
                declared = "testowana tylko do " + declaredVal;
                css = "warn";
            }
            List<RunResult> matching = p.latest().runs().stream()
                    .filter(r -> version.equals(woo ? r.woo() : r.wp())).toList();
            View.Verdict v;
            String lab;
            if (p.latest().held()) {
                // held results are hidden here too, but the page must not claim there were no tests
                v = Verdicts.HELD;
                lab = p.latest().labSummary();
            } else if (matching.isEmpty()) {
                v = new View.Verdict("none", "Brak testów na tej wersji", null);
                lab = "brak";
            } else {
                v = Verdicts.of(latest, matching);
                lab = Verdicts.labSummary(matching);
            }
            items.add(new CompatItem(st.name(), tp.slug(), latest.version(), declared, css, lab, v, "/pluginy/" + tp.slug() + "/"));
        }
        String path = "/kompatybilnosc/" + product + "-" + seg(version) + "/";
        CompatPage page = new CompatPage(site,
                new Meta(heading + " | " + site.name(),
                        "Czy polskie wtyczki działają z " + productName + " " + version + "? Deklaracje autorów i wyniki naszych testów.",
                        path, false),
                List.of(new Crumb("Wtyczki", "/"), new Crumb("Zgodność", "/kompatybilnosc/"), new Crumb(productName + " " + version, null)),
                heading,
                pre ? "To wersja przedpremierowa. Pokazujemy, czy autorzy deklarują z nią zgodność; testy laboratoryjne wersji"
                        + " przedpremierowych (profil P4) jeszcze nie są uruchamiane."
                        : "Najnowsza stabilna wersja. Deklaracja autora to nagłówek wtyczki; nasze testy dotyczą konfiguracji laboratoryjnych.",
                items, "Deklaracja autora pochodzi z nagłówka wtyczki („" + (woo ? "WC tested up to" : "Tested up to")
                + "”). Brak testów nie oznacza błędu: oznacza, że jeszcze nie sprawdzaliśmy tej konfiguracji.");
        render(out, path.substring(1) + "index.html", "compat.jte", page);
        return new Link(productName + " " + version + (pre ? " (wersja testowa)" : ""), path,
                pre ? "wcześniejsze ostrzeżenie" : "najnowsza stabilna");
    }

    private void writePhp(Path out, Site site, Ecosystem eco, Published pub, Instant now) throws IOException {
        // the build time, not the wall clock: the same data always renders the same page
        LocalDate today = LocalDate.ofInstant(now, ZoneId.of("Europe/Warsaw"));
        List<PhpRow> rows = new ArrayList<>();
        for (PhpCycle c : eco.php()) {
            String blockers = pub.plugins().stream().map(p -> p.latest().record())
                    .filter(r -> r.headers() != null && r.headers().requiresPhp() != null
                            && Versions.compare(r.headers().requiresPhp(), c.cycle()) > 0)
                    .map(r -> r.headers().pluginName() + " (PHP " + r.headers().requiresPhp() + "+)")
                    .collect(Collectors.joining(", "));
            String css = "ok";
            String security = "brak danych";
            if (c.eol() != null && c.eol().matches("\\d{4}-\\d{2}-\\d{2}")) {
                LocalDate eol = LocalDate.parse(c.eol());
                long days = ChronoUnit.DAYS.between(today, eol);
                if (days < 0) {
                    css = "bad";
                    security = "zakończone " + c.eol();
                } else if (days < 120) {
                    css = "warn";
                    security = "do " + c.eol() + " (za " + days + " dni)";
                } else {
                    security = "do " + c.eol();
                }
            }
            rows.add(new PhpRow(c.cycle(), nz(c.latest()), security, nz(c.eol()), css, blockers.isEmpty() ? "–" : blockers));
        }
        render(out, "kompatybilnosc/php/index.html", "php.jte", new PhpPage(site,
                new Meta("PHP a polskie wtyczki WooCommerce: koniec wsparcia | " + site.name(),
                        "Daty końca wsparcia kolejnych wersji PHP i śledzone wtyczki, które wymagają nowszej wersji.",
                        "/kompatybilnosc/php/", false),
                List.of(new Crumb("Wtyczki", "/"), new Crumb("Zgodność", "/kompatybilnosc/"), new Crumb("PHP", null)), rows));
    }

    private List<EcoRow> ecosystemRows(Ecosystem eco) {
        if (eco == null) {
            return List.of();
        }
        List<EcoRow> r = new ArrayList<>();
        r.add(new EcoRow("WooCommerce", eco.woo().latest(), eco.woo().prerelease() == null ? "" : "(testowa: " + eco.woo().prerelease() + ")"));
        r.add(new EcoRow("WordPress", eco.wp().latest(), eco.wp().development() == null ? "" : "(testowa: " + eco.wp().development() + ")"));
        if (!eco.php().isEmpty()) {
            PhpCycle p = eco.php().get(0);
            r.add(new EcoRow("PHP", p.latest(), "(najnowsza linia " + p.cycle() + ")"));
        }
        return r;
    }

    private List<Feeds.Item> globalFeedItems(Site site, Published pub) {
        List<Feeds.Item> items = new ArrayList<>();
        for (Published.Plugin p : pub.plugins()) {
            items.addAll(feedItems(site, p));
        }
        items.sort(Comparator.comparing(Feeds.Item::date).reversed());
        return items.stream().limit(60).toList();
    }

    private List<Feeds.Item> feedItems(Site site, Published.Plugin p) {
        List<Feeds.Item> items = new ArrayList<>();
        for (Published.Release pr : p.releases()) {
            View.Verdict v = pr.verdict();
            String link = site.baseUrl() + "/pluginy/" + p.slug() + "/" + seg(pr.version()) + "/";
            items.add(new Feeds.Item(p.name() + " " + pr.version() + ": " + v.label(), link,
                    (v.detail() == null ? "" : v.detail() + " ") + "Forum: " + Verdicts.forumText(pr.record().forum()) + ".",
                    pr.when()));
        }
        return items;
    }

    // ------------------------------------------------------------------ helpers

    private SimplePage simple(Site site, String title, String desc, String path, String crumb) {
        return new SimplePage(site, new Meta(title, desc, path, false),
                List.of(new Crumb("Wtyczki", "/"), new Crumb(crumb, null)));
    }

    private SimplePage thanks(Site site, String title, String desc, String path) {
        return new SimplePage(site, new Meta(title, desc, path, true), List.of(new Crumb("Wtyczki", "/"), new Crumb("Ankieta", null)));
    }

    private void render(Path out, String relPath, String template, Object model) throws IOException {
        StringOutput so = new StringOutput();
        engine.render(template, model, so);
        write(out, relPath, so.toString());
    }

    private void write(Path out, String relPath, String content) throws IOException {
        Path f = out.resolve(relPath);
        Files.createDirectories(f.getParent());
        Files.writeString(f, content, StandardCharsets.UTF_8);
        if (relPath.endsWith("index.html") || relPath.equals("404.html")) {
            // only HTML pages go to the sitemap (404 is excluded there)
            written.add(relPath);
        }
    }

    private static void copyResource(String res, Path target) throws IOException {
        try (InputStream in = SiteBuilder.class.getClassLoader().getResourceAsStream(res)) {
            if (in == null) {
                throw new IOException("missing resource " + res);
            }
            Files.createDirectories(target.getParent());
            Files.copy(in, target);
        }
    }

    private static void deleteTree(Path p) throws IOException {
        try (var s = Files.walk(p)) {
            for (Path x : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(x);
            }
        }
    }

    static String seg(String v) {
        return v.replaceAll("[^A-Za-z0-9._-]", "-");
    }

    private static String date(Instant i) {
        return i == null ? "data nieznana" : DATE.format(i);
    }

    private static String installs(long n) {
        return n <= 0 ? "liczba instalacji nieznana" : String.format(Locale.ROOT, "%,d+", n).replace(',', ' ') + " instalacji";
    }

    private static String dash(String s) {
        return s == null || s.isBlank() ? "–" : s;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String or(String v, String d) {
        return v == null || v.isBlank() ? d : v;
    }

    static String statusPl(String s) {
        return switch (s) {
            case "PASS" -> "zaliczono";
            case "FAIL" -> "błąd";
            case "SKIPPED" -> "pominięto";
            default -> "błąd środowiska";
        };
    }

    static String statusCss(String s) {
        return switch (s) {
            case "PASS" -> "ok";
            case "FAIL" -> "bad";
            case "ERROR" -> "warn"; // the lab's problem, not the plugin's: never shown like a failure
            default -> "none";
        };
    }
}
