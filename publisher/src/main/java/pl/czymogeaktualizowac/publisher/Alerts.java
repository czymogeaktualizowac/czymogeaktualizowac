package pl.czymogeaktualizowac.publisher;

import pl.czymogeaktualizowac.core.Json;
import pl.czymogeaktualizowac.core.Model.Ecosystem;
import pl.czymogeaktualizowac.core.Model.PluginState;
import pl.czymogeaktualizowac.core.Model.ReleaseRecord;
import pl.czymogeaktualizowac.core.Model.TrackedPlugin;
import pl.czymogeaktualizowac.core.Store;
import pl.czymogeaktualizowac.core.Versions;
import pl.czymogeaktualizowac.publisher.View.Verdict;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plans alert e-mails (component E). Drafts are written to a local outbox; sending is a provider concern
 * (MailerLite/Brevo hosted double opt-in, segments = tags) and is deliberately behind {@link MailProvider}.
 * The planner compares the current data with {@code data/published-state.json}, which {@code publish} writes.
 */
public final class Alerts {

    public record Draft(String kind, String slug, String version, String subject, List<String> segmentTags,
                        String html, String text, Instant createdAt) {
    }

    public record PublishedState(Instant at, Map<String, String> latestVersion, Map<String, String> verdict,
                                 String woo, String wooPre, String wp, String wpDev, String php) {
    }

    public interface MailProvider {
        void send(Draft draft) throws IOException;
    }

    /** Writes each draft as .json/.html/.txt into a directory. The default provider until a mail account exists. */
    public static final class FileOutbox implements MailProvider {
        private final Path dir;

        public FileOutbox(Path dir) {
            this.dir = dir;
        }

        @Override
        public void send(Draft d) throws IOException {
            Files.createDirectories(dir);
            String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(d.createdAt());
            String base = stamp + "-" + d.kind() + (d.slug() == null ? "" : "-" + d.slug()) + (d.version() == null ? "" : "-" + SiteBuilder.seg(d.version()));
            Json.write(dir.resolve(base + ".json"), d);
            Files.writeString(dir.resolve(base + ".html"), d.html(), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve(base + ".txt"), d.text(), StandardCharsets.UTF_8);
        }
    }

    private Alerts() {
    }

    public static Path statePath(Store store) {
        return store.dataDir.resolve("published-state.json");
    }

    public static PublishedState currentState(Store store, Instant now) {
        Published pub = Published.of(store, now);
        Map<String, String> latest = new LinkedHashMap<>();
        Map<String, String> verdict = new LinkedHashMap<>();
        for (Published.Plugin p : pub.plugins()) {
            latest.put(p.slug(), p.latest().version());
            verdict.put(p.slug(), p.latest().verdict().code());
        }
        Ecosystem e = pub.ecosystem();
        return new PublishedState(now, latest, verdict,
                e == null ? null : e.woo().latest(), e == null ? null : e.woo().prerelease(),
                e == null ? null : e.wp().latest(), e == null ? null : e.wp().development(),
                e == null || e.php().isEmpty() ? null : e.php().get(0).latest());
    }

    public static void saveState(Store store, PublishedState s) {
        Json.write(statePath(store), s);
    }

    public static List<Draft> plan(Store store, SiteBuilder.SiteConfig cfg, Instant now, boolean digest) {
        PublishedState prev = Files.exists(statePath(store)) ? Json.read(statePath(store), PublishedState.class) : null;
        List<Draft> out = new ArrayList<>();
        if (prev == null) {
            return out; // first publication is a baseline: nobody has been told anything yet, so nothing is "news"
        }
        Published pub = Published.of(store, now);
        for (Published.Plugin p : pub.plugins()) {
            ReleaseRecord latest = p.latest().record();
            Verdict v = p.latest().verdict();
            String before = prev.verdict().get(p.slug());
            boolean changed = !latest.version().equals(prev.latestVersion().get(p.slug())) || !v.code().equals(before);
            boolean alarming = v.code().equals("bad") || (v.code().equals("warn") && latest.forum() != null && "ELEVATED".equals(latest.forum().level()));
            if (changed && alarming) {
                out.add(badRelease(cfg, p.tracked(), p.state(), latest, v, now));
            }
        }
        Ecosystem e = pub.ecosystem();
        if (e != null) {
            List<String> news = new ArrayList<>();
            if (differs(prev.woo(), e.woo().latest())) news.add("WooCommerce " + e.woo().latest());
            if (e.woo().prerelease() != null && differs(prev.wooPre(), e.woo().prerelease())) news.add("WooCommerce " + e.woo().prerelease() + " (testowa)");
            if (differs(prev.wp(), e.wp().latest())) news.add("WordPress " + e.wp().latest());
            if (e.wp().development() != null && differs(prev.wpDev(), e.wp().development())) news.add("WordPress " + e.wp().development() + " (testowa)");
            if (!e.php().isEmpty() && differs(prev.php(), e.php().get(0).latest())) news.add("PHP " + e.php().get(0).latest());
            if (!news.isEmpty()) {
                out.add(compat(cfg, pub, news, now));
            }
        }
        if (digest) {
            out.add(digestDraft(cfg, pub, now));
        }
        return out;
    }

    // ------------------------------------------------------------------ drafts

    private static Draft badRelease(SiteBuilder.SiteConfig cfg, TrackedPlugin tp, PluginState st, ReleaseRecord rel, Verdict v, Instant now) {
        String link = cfg.baseUrl() + "/pluginy/" + tp.slug() + "/" + SiteBuilder.seg(rel.version()) + "/";
        String prev = st.releases().stream().map(ReleaseRecord::version)
                .filter(x -> Versions.compare(x, rel.version()) < 0 && !Versions.isPrerelease(x))
                .max(Versions.ORDER).orElse(null);
        String subject = "Uwaga: " + st.name() + " " + rel.version() + ". " + v.label();
        List<String> lines = new ArrayList<>();
        lines.add("Wydanie " + st.name() + " " + rel.version() + " ma status: " + v.label() + ".");
        if (v.detail() != null) {
            lines.add(v.detail());
        }
        lines.add("Forum wtyczki: " + Verdicts.forumText(rel.forum()) + ".");
        lines.add("Co zrobić: jeśli jeszcze nie zaktualizowałeś(-aś), wstrzymaj aktualizację tej wtyczki i sprawdź stronę wyniku."
                + (prev == null ? "" : " Jeśli już zaktualizowałeś(-aś) i widzisz problem, zainstaluj ręcznie poprzednią wersję (" + prev
                + ") z archiwum na WordPress.org (zakładka „Advanced View”, sekcja „Previous Versions”), najlepiej po zrobieniu kopii zapasowej."));
        lines.add("To nie jest porada prawna ani gwarancja: wynik dotyczy naszych konfiguracji testowych.");
        return draft("bad-release", tp.slug(), rel.version(), subject, List.of("plugin:" + tp.slug(), "alert:bad-release"), cfg, link,
                lines, now);
    }

    private static Draft compat(SiteBuilder.SiteConfig cfg, Published pub, List<String> news, Instant now) {
        List<String> lines = new ArrayList<>();
        lines.add("Nowe wydania: " + String.join(", ", news) + ".");
        lines.add("Status śledzonych polskich wtyczek (deklaracje autorów i nasze testy) znajdziesz na stronie zgodności.");
        for (Published.Plugin p : pub.plugins()) {
            ReleaseRecord latest = p.latest().record();
            Verdict v = p.latest().verdict();
            lines.add("• " + p.name() + " " + latest.version() + ": " + v.label()
                    + (latest.headers() != null && latest.headers().wcTested() != null ? " (autor testował z WooCommerce " + latest.headers().wcTested() + ")" : ""));
        }
        return draft("compat", null, null, "Nowe wersje: " + String.join(", ", news) + ". Status polskich wtyczek",
                List.of("alert:compat"), cfg, cfg.baseUrl() + "/kompatybilnosc/", lines, now);
    }

    private static Draft digestDraft(SiteBuilder.SiteConfig cfg, Published pub, Instant now) {
        Instant since = now.minus(Duration.ofDays(30));
        List<String> lines = new ArrayList<>();
        lines.add("Wydania z ostatnich 30 dni:");
        int n = 0;
        for (Published.Plugin p : pub.plugins()) {
            for (Published.Release pr : p.releases()) {
                Instant when = pr.when();
                if (when != null && when.isAfter(since) && !pr.record().prerelease()) {
                    lines.add("• " + p.name() + " " + pr.version() + ": " + pr.verdict().label() + ". Forum: "
                            + Verdicts.forumText(pr.record().forum()));
                    n++;
                }
            }
        }
        if (n == 0) {
            lines.add("• brak nowych wydań śledzonych wtyczek.");
        }
        return draft("digest", null, null, "Podsumowanie miesiąca: wydania polskich wtyczek WooCommerce", List.of("alert:digest"), cfg,
                cfg.baseUrl() + "/", lines, now);
    }

    private static Draft draft(String kind, String slug, String version, String subject, List<String> tags, SiteBuilder.SiteConfig cfg,
                               String link, List<String> lines, Instant now) {
        String forward = "mailto:?subject=" + java.net.URLEncoder.encode(subject, StandardCharsets.UTF_8).replace("+", "%20")
                + "&body=" + java.net.URLEncoder.encode(link, StandardCharsets.UTF_8);
        StringBuilder html = new StringBuilder("<!doctype html><html lang=\"pl\"><body style=\"font-family:system-ui,sans-serif;max-width:600px\">\n");
        html.append("<h2>").append(Feeds.esc(subject)).append("</h2>\n");
        for (String l : lines) {
            html.append("<p>").append(Feeds.esc(l)).append("</p>\n");
        }
        html.append("<p><a href=\"").append(Feeds.esc(link)).append("\">Zobacz wyniki</a> · <a href=\"").append(Feeds.esc(forward))
                .append("\">Przekaż osobie, która aktualizuje sklep</a></p>\n");
        html.append("<hr><p><strong>Jedno pytanie:</strong> czy aktualizacja kiedyś zepsuła Twój sklep? ")
                .append("<a href=\"").append(cfg.baseUrl()).append("/ankieta/tak/\">Tak</a> · <a href=\"")
                .append(cfg.baseUrl()).append("/ankieta/nie/\">Nie</a> (jedno kliknięcie, bez danych osobowych).</p>\n");
        html.append("<p style=\"font-size:12px;color:#555\">Dostajesz tę wiadomość, bo zapisałeś(-aś) się na powiadomienia na ")
                .append(Feeds.esc(cfg.baseUrl())).append(". <a href=\"{{preferences_url}}\">Zmień wybrane wtyczki</a> · <a href=\"{{unsubscribe_url}}\">Wypisz się</a>.</p>\n");
        html.append("</body></html>\n");
        StringBuilder text = new StringBuilder(subject).append("\n\n");
        for (String l : lines) {
            text.append(l).append("\n\n");
        }
        text.append("Wyniki: ").append(link).append("\nPrzekaż dalej: ").append(forward).append("\n\n")
                .append("Jedno pytanie: czy aktualizacja kiedyś zepsuła Twój sklep?\nTak: ").append(cfg.baseUrl()).append("/ankieta/tak/\nNie: ")
                .append(cfg.baseUrl()).append("/ankieta/nie/\n\nZmień wybrane wtyczki: {{preferences_url}}\nWypisz się: {{unsubscribe_url}}\n");
        return new Draft(kind, slug, version, subject, tags, html.toString(), text.toString(), now);
    }

    // ------------------------------------------------------------------ helpers

    private static boolean differs(String a, String b) {
        return a == null ? b != null : !a.equals(b);
    }
}
