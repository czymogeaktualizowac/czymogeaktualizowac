package pl.czymogeaktualizowac.core;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Data model shared by watcher, lab and publisher. Everything here is serialised as JSON in the
 * {@code data/} directory (a git branch in CI), so field names are part of the public contract.
 */
public final class Model {

    private Model() {
    }

    // ---------------------------------------------------------------- configuration

    /** One plugin we track (config/plugins.json). */
    public record TrackedPlugin(
            String slug,
            String name,
            String vendor,
            /* shipping | payment | invoicing | compliance | reviews | other */ String category,
            String note,
            LabHints lab) {
        public LabHints labOrDefault() {
            return lab == null ? new LabHints(null, null, null, null, null, null, null) : lab;
        }
    }

    /**
     * Hints for the lab. Without sandbox credentials the L3/L4 levels are reported as SKIPPED, never as PASS.
     *
     * @param gatewayId          WooCommerce payment gateway id (L3)
     * @param redirectHostRegex  regex the sandbox redirect host must match (L3)
     * @param sandboxEnvPrefix   env var prefix holding the sandbox settings JSON, e.g. LAB_SANDBOX_PAYNOW (L3/L4)
     * @param optionName         wp_options row that receives the sandbox settings JSON
     * @param shippingMethodRegex regex of the shipping method label to select (L4)
     * @param lockerSelector     CSS selector that must become visible (locker/map widget) (L4)
     * @param companions         additional plugin slugs this plugin needs besides "Requires Plugins"
     */
    public record LabHints(
            String gatewayId,
            String redirectHostRegex,
            String sandboxEnvPrefix,
            String optionName,
            String shippingMethodRegex,
            String lockerSelector,
            List<String> companions) {
    }

    /** A reference profile (config/profiles.json). wp/woo are symbolic and resolved from the ecosystem data. */
    public record Profile(
            String id,
            String name,
            String php,
            /* latest | previous | development */ String wp,
            /* latest | previous | prerelease */ String woo,
            List<String> checkoutTypes,
            String why) {
    }

    /**
     * Docker images of the lab, pinned to digests (config/lab-images.json), so every run uses exactly the same
     * operating system, PHP build and Apache until the pins are refreshed on purpose ({@code lab pin-images}, via a PR).
     *
     * @param database  "name:tag@sha256:…" of the database image
     * @param wordpress PHP version ("8.3") -> "name:tag@sha256:…" of the WordPress image for that PHP version
     */
    public record LabImages(String database, Map<String, String> wordpress) {
        public String wordpressFor(String php) {
            String img = wordpress == null ? null : wordpress.get(php);
            if (img == null) {
                throw new IllegalArgumentException("no pinned WordPress image for PHP " + php
                        + " in config/lab-images.json (add it with 'lab pin-images --php " + php + "')");
            }
            return img;
        }
    }

    // ---------------------------------------------------------------- watcher output

    public record PluginHeaders(
            String pluginName,
            String version,
            String license,
            String requiresPhp,
            String requiresWp,
            String wcRequires,
            String wcTested,
            String testedWp,
            String stableTag,
            List<String> requiresPlugins,
            String changelogHead) {
    }

    /** Result of comparing the downloaded zip with the checksums WordPress.org publishes for that version. */
    public record ChecksumResult(
            /* OK | MISMATCH | UNAVAILABLE (WordPress.org publishes none) | FETCH_FAILED (transient, retried) */ String status,
            int filesChecked,
            List<String> mismatched,
            List<String> missing,
            String detail) {
    }

    public record ForumTopic(String title, String link, Instant date) {
    }

    /**
     * Cheap "this release hurts people" proxy: new support topics in the 7 days after a release.
     * level: NONE (0 topics) | LOW (1-2) | ELEVATED (3+, or 2 of which at least one mentions the version)
     * | UNKNOWN (release date unknown or the feed does not reach back to it). See {@link Signals#forum}.
     */
    public record ForumSignal(
            int topics7d,
            int mentioningVersion,
            String level,
            boolean windowComplete,
            List<ForumTopic> sample) {
    }

    public record ReleaseRecord(
            String version,
            /* tag creation time on WordPress.org SVN (null if unknown) */ Instant released,
            Instant firstSeen,
            boolean prerelease,
            PluginHeaders headers,
            ChecksumResult checksum,
            String zipSha256,
            long zipBytes,
            ForumSignal forum) {
    }

    public record PluginState(
            String slug,
            String name,
            String vendor,
            String category,
            long activeInstalls,
            String latestVersion,
            String lastUpdated,
            String apiTestedWp,
            String apiRequiresPhp,
            int supportThreads,
            int supportThreadsResolved,
            String homepage,
            Instant checkedAt,
            /* newest first */ List<ReleaseRecord> releases) {
    }

    public record WpInfo(String latest, String previousMajorLatest, String development) {
    }

    public record WooInfo(String latest, String previousMajorLatest, String prerelease) {
    }

    public record PhpCycle(String cycle, String latest, String releaseDate, String activeSupportUntil, String eol) {
    }

    public record Ecosystem(WpInfo wp, WooInfo woo, List<PhpCycle> php, Instant checkedAt) {
    }

    public record ReleasesFile(Instant generatedAt, Map<String, PluginState> plugins, Ecosystem ecosystem) {
    }

    // ---------------------------------------------------------------- lab output

    public enum Level {
        L0("Instalacja i aktywacja"),
        L1("Koszyk i strona zamówienia"),
        L2("Zamówienie testowe (przelew/pobranie)"),
        L3("Bramka płatności (sandbox)"),
        L4("Widget paczkomatu/punktu");

        public final String label;

        Level(String label) {
            this.label = label;
        }
    }

    public enum Status {PASS, FAIL, SKIPPED, ERROR}

    public record LevelResult(Level level, Status status, String detail, long durationMs) {
    }

    /** One lab run: one plugin version, one profile, one checkout type, solo or in the full Polish stack. */
    public record RunResult(
            String pluginSlug,
            String pluginVersion,
            String profileId,
            /* solo | stack */ String mode,
            /* classic | block | none */ String checkout,
            String php,
            String wp,
            String woo,
            Instant startedAt,
            Instant finishedAt,
            List<LevelResult> levels,
            /* highest level L0..Ln such that all lower levels PASS and this one PASSes; null if L0 failed */ Level highestPassed,
            String notes,
            List<String> logExcerpt,
            /* what the run actually used, so an old result can be reproduced: "wordpressImage", "databaseImage",
               "wpCli", "plugins" (every plugin active before the one under test, "slug@version" comma-separated);
               null in results written before this field existed */ Map<String, String> environment) {

        /** For results without a recorded environment (older data, tests). */
        public RunResult(String pluginSlug, String pluginVersion, String profileId, String mode, String checkout, String php,
                         String wp, String woo, Instant startedAt, Instant finishedAt, List<LevelResult> levels,
                         Level highestPassed, String notes, List<String> logExcerpt) {
            this(pluginSlug, pluginVersion, profileId, mode, checkout, php, wp, woo, startedAt, finishedAt, levels,
                    highestPassed, notes, logExcerpt, null);
        }
    }

    public record ResultsFile(String pluginSlug, String pluginVersion, List<RunResult> runs) {
    }
}
