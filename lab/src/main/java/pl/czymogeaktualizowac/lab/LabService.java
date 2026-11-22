package pl.czymogeaktualizowac.lab;

import pl.czymogeaktualizowac.core.Http;
import pl.czymogeaktualizowac.core.Model.Ecosystem;
import pl.czymogeaktualizowac.core.Model.LabHints;
import pl.czymogeaktualizowac.core.Model.Level;
import pl.czymogeaktualizowac.core.Model.PluginState;
import pl.czymogeaktualizowac.core.Model.Profile;
import pl.czymogeaktualizowac.core.Model.ReleasesFile;
import pl.czymogeaktualizowac.core.Model.RunResult;
import pl.czymogeaktualizowac.core.Model.Status;
import pl.czymogeaktualizowac.core.Model.TrackedPlugin;
import pl.czymogeaktualizowac.core.Store;
import pl.czymogeaktualizowac.core.WpOrg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Plans lab runs from config + watcher data, executes them and stores the results under data/results/. */
public final class LabService {

    private final Store store;
    private final Consumer<String> log;
    private final boolean headless;
    private final WpOrg wp;
    private Path phar;

    public LabService(Store store, Consumer<String> log, boolean headless) {
        this.store = store;
        this.log = log;
        this.headless = headless;
        this.wp = new WpOrg(new Http(), store.cacheDir);
    }

    private Path wpCli() throws IOException {
        if (phar == null) {
            phar = LabEnv.wpCli(store.cacheDir, new Http());
        }
        return phar;
    }

    /** Downloads/verifies WP-CLI, installs the Chromium build Playwright needs (only Chromium) and pulls the docker images. */
    public void prepare() throws IOException {
        log.accept("WP-CLI " + LabEnv.WP_CLI_VERSION + " -> " + wpCli());
        installChromium();
        // pinned images: pulled once per machine, then every run on it reuses the same local image
        var pins = store.labImages();
        List<String> images = new ArrayList<>(List.of(pins.database()));
        for (Profile p : store.profiles()) {
            images.add(pins.wordpressFor(p.php()));
        }
        for (String img : images.stream().distinct().toList()) {
            log.accept("docker pull " + img);
            Docker.Result r = Docker.run(List.of("docker", "pull", img), null, null, java.time.Duration.ofMinutes(20));
            if (!r.ok()) {
                throw new LabEnv.InfraException("docker pull " + img + " failed: " + LabEnv.tail(r.out(), 5));
            }
        }
    }

    /**
     * Playwright for Java would install Chromium, Firefox and WebKit on first use; the lab only needs Chromium, so runs set
     * PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD and this step installs Chromium explicitly (same JVM settings as the current process).
     */
    private void installChromium() throws IOException {
        String javaBin = ProcessHandle.current().info().command().orElse("java");
        List<String> cmd = new ArrayList<>(List.of(javaBin));
        for (String prop : new String[]{"jdk.net.unixdomain.tmpdir", "java.io.tmpdir"}) {
            String v = System.getProperty(prop);
            if (v != null && System.getenv("JAVA_TOOL_OPTIONS") == null) {
                cmd.add("-D" + prop + "=" + v);
            }
        }
        cmd.addAll(List.of("-cp", System.getProperty("java.class.path"), "com.microsoft.playwright.CLI", "install", "chromium"));
        log.accept("playwright install chromium");
        Docker.Result r = Docker.run(cmd, null, null, java.time.Duration.ofMinutes(15));
        if (!r.ok()) {
            throw new LabEnv.InfraException("playwright install chromium failed: " + LabEnv.tail(r.out(), 6));
        }
    }

    /**
     * @param version null = latest release known to the watcher
     * @param profileIds null/empty = all profiles
     * @param checkouts null/empty = every checkout type of each profile
     */
    public List<RunSpec> plan(String slug, String version, List<String> profileIds, Set<String> checkouts, String mode, boolean skipExisting)
            throws IOException {
        ReleasesFile rel = store.loadReleases();
        TrackedPlugin tp = store.trackedPlugins().stream().filter(t -> t.slug().equals(slug)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("plugin not tracked: " + slug));
        PluginState st = rel.plugins().get(slug);
        String v = version != null ? version : (st == null ? null : st.latestVersion());
        if (v == null) {
            throw new IllegalArgumentException("no known release for " + slug + " (run 'watch' first)");
        }
        Path zip = verifiedZip(rel, slug, v);
        Ecosystem eco = rel.ecosystem();
        var pins = store.labImages();
        List<RunSpec> specs = new ArrayList<>();
        for (Profile p : store.profiles()) {
            if (profileIds != null && !profileIds.isEmpty() && !profileIds.contains(p.id())) {
                continue;
            }
            for (String checkout : p.checkoutTypes()) {
                if (checkouts != null && !checkouts.isEmpty() && !checkouts.contains(checkout)) {
                    continue;
                }
                if (skipExisting && store.loadResults(slug, v).runs().stream().anyMatch(r -> r.profileId().equals(p.id())
                        && r.mode().equals(mode) && r.checkout().equals(checkout) && r.levels().stream().noneMatch(l -> l.status() == Status.ERROR))) {
                    continue;
                }
                StringBuilder notes = new StringBuilder();
                String wpV = resolve(p.wp(), eco == null ? null : eco.wp().latest(), eco == null ? null : eco.wp().previousMajorLatest(),
                        eco == null ? null : eco.wp().development(), "WordPress", notes);
                String wooV = resolve(p.woo(), eco == null ? null : eco.woo().latest(), eco == null ? null : eco.woo().previousMajorLatest(),
                        eco == null ? null : eco.woo().prerelease(), "WooCommerce", notes);
                List<RunSpec.StackItem> stack = "stack".equals(mode) ? stackFor(slug) : List.of();
                specs.add(new RunSpec(slug, v, zip, tp.category(), tp.lab(), p.id(), p.php(), wpV, wooV, checkout, mode, stack,
                        notes.toString().strip(), pins.wordpressFor(p.php()), pins.database()));
            }
        }
        return specs;
    }

    /** All other tracked plugins in their latest version ("typical Polish stack"; catches conflicts). */
    private List<RunSpec.StackItem> stackFor(String exceptSlug) {
        List<RunSpec.StackItem> out = new ArrayList<>();
        ReleasesFile rel = store.loadReleases();
        for (TrackedPlugin t : store.trackedPlugins()) {
            PluginState s = rel.plugins().get(t.slug());
            if (t.slug().equals(exceptSlug) || s == null) {
                continue;
            }
            try {
                out.add(new RunSpec.StackItem(t.slug(), verifiedZip(rel, t.slug(), s.latestVersion())));
            } catch (IOException | IllegalArgumentException e) {
                log.accept("stack: skipping " + t.slug() + " (" + e.getMessage() + ")");
            }
        }
        return out;
    }

    /**
     * The zip of a release, byte-identical to the one the watcher downloaded and checked against the WordPress.org
     * checksums (its sha256 is in data/releases.json). The lab job runs on another machine and downloads again, so
     * without this check the tested zip could differ from the verified one.
     */
    Path verifiedZip(ReleasesFile rel, String slug, String version) throws IOException {
        PluginState st = rel.plugins().get(slug);
        String expected = st == null || st.releases() == null ? null : st.releases().stream()
                .filter(r -> r.version().equals(version)).map(r -> r.zipSha256()).filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
        if (expected == null) {
            throw new IllegalArgumentException(slug + " " + version + " has not been downloaded and verified by the watcher yet"
                    + " (run 'watch', with --backfill for older versions)");
        }
        Path zip = wp.zipPath(slug, version);
        if (!Files.exists(zip)) {
            wp.zip(slug, version);
        }
        String actual = pl.czymogeaktualizowac.core.PluginZip.sha256(Files.readAllBytes(zip));
        if (!actual.equalsIgnoreCase(expected)) {
            Files.deleteIfExists(zip);
            throw new LabEnv.InfraException(slug + " " + version + ": the zip differs from the one the watcher verified"
                    + " (sha256 " + actual + ", expected " + expected + "); not tested");
        }
        return zip;
    }

    private static String resolve(String symbol, String latest, String previous, String pre, String name, StringBuilder notes) {
        String v = switch (symbol) {
            case "latest" -> latest;
            case "previous" -> previous != null ? previous : latest;
            case "development", "prerelease" -> pre != null ? pre : latest;
            // a typo must not silently become "pre-release" (ConfigCheck rejects it earlier; this is the last guard)
            default -> throw new IllegalArgumentException("unknown " + name + " version symbol '" + symbol + "'");
        };
        if (v == null) {
            throw new IllegalStateException("cannot resolve " + name + " '" + symbol + "': run 'watch' first");
        }
        if (("development".equals(symbol) || "prerelease".equals(symbol)) && pre == null) {
            notes.append(name).append(": brak wersji testowej, użyto najnowszej stabilnej. ");
        }
        if ("previous".equals(symbol) && previous == null) {
            notes.append(name).append(": brak poprzedniej linii, użyto najnowszej. ");
        }
        return v;
    }

    public RunResult execute(RunSpec spec) throws IOException {
        LabRunner runner = new LabRunner(wpCli(), log, headless);
        RunResult r = confirmFailures(() -> runner.run(spec), log);
        store.saveRun(r);
        log.accept("  -> " + spec.slug() + " " + spec.version() + " " + spec.profileId() + "/" + spec.checkout() + "/" + spec.mode()
                + ": highest passed = " + r.highestPassed() + " [" + summary(r) + "]");
        return r;
    }

    /**
     * A FAIL is published as the plugin's fault, so it must reproduce: a run with any FAIL is repeated once in a fresh
     * store. If the repeat fails too, the repeat is kept (confirmed). If not (a timeout, a slow runner), the repeat is
     * kept and the unconfirmed first failure is only noted, never published as a failure.
     */
    static RunResult confirmFailures(java.util.function.Supplier<RunResult> run, Consumer<String> log) {
        RunResult first = run.get();
        if (!hasFail(first)) {
            return first;
        }
        log.accept("  FAIL in the first run, repeating once to confirm: " + summary(first));
        RunResult second = run.get();
        String note = hasFail(second)
                ? "błąd potwierdzony w powtórzonym teście"
                : "pierwsze uruchomienie zgłosiło błąd (" + summary(first) + "), powtórzenie go nie potwierdziło";
        String notes = second.notes() == null || second.notes().isBlank() ? note : second.notes() + "; " + note;
        return new RunResult(second.pluginSlug(), second.pluginVersion(), second.profileId(), second.mode(), second.checkout(),
                second.php(), second.wp(), second.woo(), second.startedAt(), second.finishedAt(), second.levels(),
                second.highestPassed(), notes, second.logExcerpt(), second.environment());
    }

    private static boolean hasFail(RunResult r) {
        return r.levels().stream().anyMatch(l -> l.status() == Status.FAIL);
    }

    static String summary(RunResult r) {
        StringBuilder sb = new StringBuilder();
        r.levels().forEach(l -> sb.append(l.level()).append('=').append(l.status()).append(' '));
        return sb.toString().strip();
    }

    // ------------------------------------------------------------------ selftest

    /**
     * Proves the lab separates good from bad using fixture plugins. Returns human-readable lines; a line starting with
     * "FAIL" means the lab cannot be trusted. Results of selftest runs are NOT written to data/.
     */
    public List<String> selftest(String php, String checkout) throws IOException {
        Ecosystem eco = store.loadReleases().ecosystem();
        if (eco == null) {
            throw new IllegalStateException("run 'watch' first (needs the WordPress/WooCommerce versions)");
        }
        Path dir = Files.createTempDirectory("czm-fixtures");
        List<String> out = new ArrayList<>();
        LabRunner runner = new LabRunner(wpCli(), log, headless);
        for (String slug : List.of(Fixtures.OK, Fixtures.FATAL, Fixtures.CHECKOUT)) {
            RunSpec spec = new RunSpec(slug, "1.0.0", Fixtures.zip(slug, dir), "other", new LabHints(null, null, null, null, null, null, null),
                    "SELFTEST", php, eco.wp().latest(), eco.woo().latest(), checkout, "solo", List.of(), "selftest",
                    store.labImages().wordpressFor(php), store.labImages().database());
            RunResult r = runner.run(spec);
            Status s0 = status(r, Level.L0);
            Status s1 = status(r, Level.L1);
            Status s2 = status(r, Level.L2);
            boolean pass = switch (slug) {
                case Fixtures.OK -> s0 == Status.PASS && s1 == Status.PASS && s2 == Status.PASS;
                case Fixtures.FATAL -> s0 == Status.FAIL;
                default -> s0 == Status.PASS && s1 == Status.FAIL;
            };
            out.add((pass ? "PASS " : "FAIL ") + slug + " [" + checkout + ", php " + php + "]: " + summary(r)
                    + (pass ? "" : " | " + r.levels().stream().map(l -> l.level() + ": " + l.detail()).toList()));
        }
        return out;
    }

    private static Status status(RunResult r, Level l) {
        return r.levels().stream().filter(x -> x.level() == l).map(x -> x.status()).findFirst().orElse(Status.SKIPPED);
    }
}
