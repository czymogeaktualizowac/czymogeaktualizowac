package pl.czymogeaktualizowac.lab;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import pl.czymogeaktualizowac.core.Json;
import pl.czymogeaktualizowac.core.Model.LabHints;
import pl.czymogeaktualizowac.core.Model.Level;
import pl.czymogeaktualizowac.core.Model.LevelResult;
import pl.czymogeaktualizowac.core.Model.PluginHeaders;
import pl.czymogeaktualizowac.core.Model.RunResult;
import pl.czymogeaktualizowac.core.Model.Status;
import pl.czymogeaktualizowac.core.PluginZip;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.function.Consumer;

/**
 * Executes one {@link RunSpec}: builds a disposable store, runs a control pass WITHOUT the plugin (so that lab or
 * WooCommerce problems are never blamed on the plugin), installs the plugin, and walks through levels L0..L4.
 * A level that was not run is SKIPPED, never PASS.
 */
public final class LabRunner {

    private static final Pattern FATAL_TEXT = Pattern.compile("Fatal error|critical error on this website|Parse error|Uncaught (Error|Exception|TypeError)", Pattern.CASE_INSENSITIVE);

    private final Path wpCliPhar;
    private final Consumer<String> log;
    private final boolean headless;

    public LabRunner(Path wpCliPhar, Consumer<String> log, boolean headless) {
        this.wpCliPhar = wpCliPhar;
        this.log = log;
        this.headless = headless;
    }

    public RunResult run(RunSpec spec) {
        Instant started = Instant.now();
        List<LevelResult> levels = new ArrayList<>();
        List<String> excerpt = new ArrayList<>();
        String notes = spec.notes();
        log.accept("lab run: " + spec.slug() + " " + spec.version() + " | " + spec.profileId() + " php " + spec.php() + " wp " + spec.wp()
                + " woo " + spec.woo() + " | " + spec.checkout() + " | " + spec.mode());
        try (LabEnv env = new LabEnv(spec.php());
             Playwright pw = Playwright.create(new Playwright.CreateOptions().setEnv(java.util.Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")))) {
            Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(headless));
            try {
                StoreBrowser.Store store = prepareStore(env, spec);

                // ---- control pass: the store must work without the plugin, otherwise the run is an infrastructure ERROR
                try (StoreBrowser control = new StoreBrowser(browser, store, "none")) {
                    LevelResult c1 = control.l1(spec.checkout());
                    LevelResult c2 = c1.status() == Status.PASS ? control.l2(spec.checkout()) : null;
                    if (c1.status() != Status.PASS || c2 == null || c2.status() != Status.PASS) {
                        String why = c1.status() != Status.PASS ? "L1: " + c1.detail() : "L2: " + (c2 == null ? "n/a" : c2.detail());
                        throw new LabEnv.InfraException("kontrola bez wtyczki nie przeszła (" + why + ")");
                    }
                }
                // errors already in the PHP log without the plugin are not the plugin's (see DebugLog)
                java.util.Set<String> baseline = DebugLog.fatalKeys(env.debugLog());
                levels.add(installAndLevel0(env, spec, store, excerpt, baseline));
                if (levels.get(0).status() == Status.PASS) {
                    try (StoreBrowser sb = new StoreBrowser(browser, store, spec.slug())) {
                        LevelResult l1 = sb.l1(spec.checkout());
                        levels.add(l1);
                        if (l1.status() == Status.PASS) {
                            levels.add(sb.l2(spec.checkout()));
                        } else {
                            levels.add(skipped(Level.L2, "poprzedni poziom (L1) nie przeszedł"));
                        }
                        Level reached = highest(levels);
                        levels.addAll(optionalLevels(sb, env, spec, reached));
                    }
                    // anything the plugin wrote to the PHP error log while the browser used it
                    String log2 = env.debugLog();
                    excerpt.addAll(relevantLines(log2, spec.slug(), 20));
                } else {
                    levels.add(skipped(Level.L1, "poprzedni poziom (L0) nie przeszedł"));
                    levels.add(skipped(Level.L2, "poprzedni poziom (L0) nie przeszedł"));
                    levels.add(skipped(Level.L3, "poprzedni poziom (L0) nie przeszedł"));
                    levels.add(skipped(Level.L4, "poprzedni poziom (L0) nie przeszedł"));
                }
            } finally {
                browser.close();
            }
        } catch (LabEnv.InfraException e) {
            log.accept("INFRA ERROR: " + e.getMessage());
            levels = errorLevels("błąd laboratorium (nie wtyczki): " + e.getMessage());
            notes = join(notes, "błąd infrastruktury; wynik nie dotyczy wtyczki");
        } catch (IOException | RuntimeException e) {
            log.accept("INFRA ERROR: " + e);
            levels = errorLevels("błąd laboratorium (nie wtyczki): " + e.getClass().getSimpleName() + ": " + e.getMessage());
            notes = join(notes, "błąd infrastruktury; wynik nie dotyczy wtyczki");
        }
        while (levels.size() < 5) {
            levels.add(skipped(Level.values()[levels.size()], "nie uruchomiono"));
        }
        return new RunResult(spec.slug(), spec.version(), spec.profileId(), spec.mode(), spec.checkout(), spec.php(), spec.wp(), spec.woo(),
                started, Instant.now(), levels, highest(levels), notes, excerpt.stream().limit(40).toList());
    }

    // ------------------------------------------------------------------ store preparation (infrastructure)

    private StoreBrowser.Store prepareStore(LabEnv env, RunSpec spec) throws IOException {
        env.start(wpCliPhar);
        String have = env.wp("core", "version").out().strip();
        if (!have.equals(spec.wp())) {
            log.accept("  WordPress " + have + " -> " + spec.wp());
            infra(env.wp("core", "download", "--version=" + spec.wp(), "--force", "--skip-content"), "wp core download " + spec.wp());
        }
        infra(env.wp("core", "install", "--url=" + env.baseUrl(), "--title=Lab", "--admin_user=admin",
                "--admin_password=" + java.util.UUID.randomUUID(), "--admin_email=lab@example.invalid", "--skip-email"), "wp core install");
        infra(env.wp("plugin", "install", "woocommerce", "--version=" + spec.woo(), "--activate"), "install WooCommerce " + spec.woo());
        Docker.Result setup = env.wp("eval-file", "/tmp/setup-store.php", spec.checkout());
        infra(setup, "store setup");
        JsonNode j = lastJson(setup.out());
        if (j == null) {
            throw new LabEnv.InfraException("store setup printed no JSON: " + LabEnv.tail(setup.out(), 5));
        }
        return new StoreBrowser.Store(env.baseUrl(), j.path("product_id").asLong(), j.path("cart_id").asLong(), j.path("checkout_id").asLong());
    }

    /** Installs the plugin (and companions) and runs L0. Failures here belong to the plugin: they are FAIL. */
    private LevelResult installAndLevel0(LabEnv env, RunSpec spec, StoreBrowser.Store store, List<String> excerpt,
                                         java.util.Set<String> baseline) throws IOException {
        long t0 = System.currentTimeMillis();
        // dependencies declared by the plugin itself ("Requires Plugins") and by our hints, then the stack (mode "stack")
        List<String> deps = new ArrayList<>();
        try {
            PluginHeaders h = PluginZip.headers(Files.readAllBytes(spec.zip()), spec.slug());
            if (h.requiresPlugins() != null) {
                deps.addAll(h.requiresPlugins());
            }
        } catch (IOException e) {
            log.accept("  warn: cannot read headers of " + spec.zip().getFileName() + ": " + e.getMessage());
        }
        LabHints hints = spec.hints() == null ? new LabHints(null, null, null, null, null, null, null) : spec.hints();
        if (hints.companions() != null) {
            deps.addAll(hints.companions());
        }
        deps.removeIf(d -> d.equals("woocommerce") || d.equals(spec.slug()));
        for (String dep : deps.stream().distinct().toList()) {
            infra(env.wp("plugin", "install", dep, "--activate"), "install dependency " + dep);
        }
        for (RunSpec.StackItem other : spec.stack()) {
            Docker.Result r = installZip(env, other.slug(), other.zip());
            if (!r.ok()) {
                excerpt.add("stack: " + other.slug() + " could not be activated: " + LabEnv.tail(r.out(), 3));
            }
        }
        Docker.Result inst = installZip(env, spec.slug(), spec.zip());
        if (!inst.ok()) {
            boolean pluginFault = Pattern.compile("fatal error|could not be activated|Parse error|PHP Fatal", Pattern.CASE_INSENSITIVE).matcher(inst.out()).find();
            String detail = "instalacja/aktywacja nie powiodła się: " + LabEnv.tail(inst.out(), 4);
            excerpt.addAll(relevantLines(inst.out(), spec.slug(), 10));
            return new LevelResult(Level.L0, pluginFault ? Status.FAIL : Status.ERROR, detail, System.currentTimeMillis() - t0);
        }
        // WP-CLI itself must still load WordPress with the plugin active
        Docker.Result load = env.wp("eval", "echo 'ok';");
        if (!load.ok() || FATAL_TEXT.matcher(load.out()).find()) {
            return new LevelResult(Level.L0, Status.FAIL, "WordPress nie ładuje się z aktywną wtyczką: " + LabEnv.tail(load.out(), 3), System.currentTimeMillis() - t0);
        }
        // front pages
        List<String> bad = new ArrayList<>();
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build();
        for (String path : new String[]{"/", "/?post_type=product", "/?page_id=" + store.cartPageId()}) {
            try {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(store.baseUrl() + path)).timeout(Duration.ofSeconds(60)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200 || FATAL_TEXT.matcher(r.body()).find()) {
                    bad.add(path + " -> HTTP " + r.statusCode() + (FATAL_TEXT.matcher(r.body()).find() ? " (błąd krytyczny w treści)" : ""));
                }
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                bad.add(path + " -> " + e.getClass().getSimpleName());
            }
        }
        String dlog = env.debugLog();
        DebugLog.Analysis an = DebugLog.analyze(dlog, spec.slug(), baseline);
        excerpt.addAll(relevantLines(dlog, spec.slug(), 20));
        if (!bad.isEmpty() || an.hasFatal()) {
            StringBuilder d = new StringBuilder();
            if (!bad.isEmpty()) {
                d.append("strony: ").append(String.join("; ", bad)).append(". ");
            }
            if (!an.pluginFatals().isEmpty()) {
                d.append("dziennik PHP: błąd krytyczny w plikach wtyczki: ").append(an.pluginFatals().get(0)).append(". ");
            }
            if (!an.unattributedNewFatals().isEmpty()) {
                d.append("dziennik PHP: nowy błąd krytyczny po instalacji wtyczki, bez jednoznacznego wskazania jej plików (do weryfikacji): ")
                        .append(an.unattributedNewFatals().get(0)).append(". ");
            }
            return new LevelResult(Level.L0, Status.FAIL, d.toString().strip(), System.currentTimeMillis() - t0);
        }
        return new LevelResult(Level.L0, Status.PASS, "wtyczka zainstalowana i aktywna; strona główna, sklep i koszyk odpowiadają 200 bez błędów krytycznych; dziennik PHP: 0 błędów krytycznych"
                + (an.ignoredBaselineFatals() == 0 ? "" : " (pominięto " + an.ignoredBaselineFatals() + " błędów, które występują też bez wtyczki: to błąd WordPressa/WooCommerce lub laboratorium)")
                + (an.pluginNotices() == 0 ? "" : ", " + an.pluginNotices() + " ostrzeżeń/uwag/przestarzałych wywołań z plików wtyczki"), System.currentTimeMillis() - t0);
    }

    private Docker.Result installZip(LabEnv env, String slug, Path zip) throws IOException {
        String target = "/tmp/" + slug + ".zip";
        env.copyFile(zip, target);
        return env.wp("plugin", "install", target, "--activate");
    }

    // ------------------------------------------------------------------ L3 / L4

    private List<LevelResult> optionalLevels(StoreBrowser sb, LabEnv env, RunSpec spec, Level reached) throws IOException {
        List<LevelResult> out = new ArrayList<>();
        LabHints h = spec.hints() == null ? new LabHints(null, null, null, null, null, null, null) : spec.hints();
        boolean l2ok = reached != null && reached.ordinal() >= Level.L2.ordinal();
        // L3: payment gateways with sandbox data
        if (!"payment".equals(spec.category())) {
            out.add(skipped(Level.L3, "nie dotyczy wtyczek z kategorii " + spec.category()));
        } else if (!l2ok) {
            out.add(skipped(Level.L3, "poprzedni poziom nie przeszedł"));
        } else {
            String json = h.sandboxEnvPrefix() == null ? null : System.getenv(h.sandboxEnvPrefix() + "_OPTION_JSON");
            if (json == null || h.gatewayId() == null || h.optionName() == null || h.redirectHostRegex() == null) {
                out.add(skipped(Level.L3, "brak danych sandboxowych lub konfiguracji bramki (gatewayId/optionName w config/plugins.json, zmienna "
                        + (h.sandboxEnvPrefix() == null ? "?" : h.sandboxEnvPrefix() + "_OPTION_JSON") + ")"));
            } else {
                infra(env.wp("option", "update", h.optionName(), json, "--format=json"), "apply sandbox settings");
                out.add(sb.l3(spec.checkout(), h.gatewayId(), h.redirectHostRegex()));
            }
        }
        // L4: carrier widgets
        if (!"shipping".equals(spec.category())) {
            out.add(skipped(Level.L4, "nie dotyczy wtyczek z kategorii " + spec.category()));
        } else if (!l2ok) {
            out.add(skipped(Level.L4, "poprzedni poziom nie przeszedł"));
        } else if (h.shippingMethodRegex() == null || h.lockerSelector() == null) {
            out.add(skipped(Level.L4, "brak konfiguracji widgetu (shippingMethodRegex/lockerSelector w config/plugins.json) i danych sandboxowych przewoźnika"));
        } else {
            out.add(sb.l4(spec.checkout(), h.shippingMethodRegex(), h.lockerSelector()));
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    static Level highest(List<LevelResult> levels) {
        Level best = null;
        for (Level l : Level.values()) {
            LevelResult r = levels.stream().filter(x -> x.level() == l).findFirst().orElse(null);
            if (r != null && r.status() == Status.PASS) {
                best = l;
            } else {
                break;
            }
        }
        return best;
    }

    private static LevelResult skipped(Level l, String why) {
        return new LevelResult(l, Status.SKIPPED, why, 0);
    }

    private static List<LevelResult> errorLevels(String why) {
        List<LevelResult> l = new ArrayList<>();
        for (Level level : Level.values()) {
            l.add(new LevelResult(level, Status.ERROR, why, 0));
        }
        return l;
    }

    private static void infra(Docker.Result r, String what) throws LabEnv.InfraException {
        if (!r.ok()) {
            throw new LabEnv.InfraException(what + ": " + LabEnv.tail(r.out(), 6));
        }
    }

    private static JsonNode lastJson(String out) {
        for (String line : out.strip().split("\\R")) {
            String s = line.strip();
            if (s.startsWith("{") && s.endsWith("}")) {
                try {
                    return Json.tree(s);
                } catch (RuntimeException ignored) {
                    // not our line
                }
            }
        }
        return null;
    }

    private static List<String> relevantLines(String text, String slug, int max) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\\R")) {
            if (line.contains("/wp-content/plugins/" + slug + "/") && line.contains("PHP ")) {
                out.add(line.length() > 400 ? line.substring(0, 400) + "…" : line);
            } else if (line.contains("PHP Fatal error") || line.contains("PHP Parse error")) {
                out.add(line.length() > 400 ? line.substring(0, 400) + "…" : line);
            }
            if (out.size() >= max) {
                break;
            }
        }
        return out;
    }

    private static String join(String a, String b) {
        return a == null || a.isBlank() ? b : a + "; " + b;
    }
}
