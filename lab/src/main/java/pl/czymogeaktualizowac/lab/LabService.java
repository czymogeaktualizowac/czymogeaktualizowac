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
        List<String> images = new ArrayList<>(List.of("mariadb:11.4"));
        for (Profile p : store.profiles()) {
            images.add("wordpress:php" + p.php() + "-apache");
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
        Path zip = wp.zipPath(slug, v);
        if (!Files.exists(zip)) {
            wp.zip(slug, v);
        }
        Ecosystem eco = rel.ecosystem();
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
                specs.add(new RunSpec(slug, v, zip, tp.category(), tp.lab(), p.id(), p.php(), wpV, wooV, checkout, mode, stack, notes.toString().strip()));
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
                Path z = wp.zipPath(t.slug(), s.latestVersion());
                if (!Files.exists(z)) {
                    wp.zip(t.slug(), s.latestVersion());
                }
                out.add(new RunSpec.StackItem(t.slug(), z));
            } catch (IOException e) {
                log.accept("stack: skipping " + t.slug() + " (" + e.getMessage() + ")");
            }
        }
        return out;
    }

    private static String resolve(String symbol, String latest, String previous, String pre, String name, StringBuilder notes) {
        String v = switch (symbol) {
            case "latest" -> latest;
            case "previous" -> previous != null ? previous : latest;
            default -> pre != null ? pre : latest;
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
        RunResult r = new LabRunner(wpCli(), log, headless).run(spec);
        store.saveRun(r);
        log.accept("  -> " + spec.slug() + " " + spec.version() + " " + spec.profileId() + "/" + spec.checkout() + "/" + spec.mode()
                + ": highest passed = " + r.highestPassed() + " [" + summary(r) + "]");
        return r;
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
                    "SELFTEST", php, eco.wp().latest(), eco.woo().latest(), checkout, "solo", List.of(), "selftest");
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
