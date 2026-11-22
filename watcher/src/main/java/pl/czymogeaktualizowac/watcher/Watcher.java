package pl.czymogeaktualizowac.watcher;

import com.fasterxml.jackson.databind.JsonNode;
import pl.czymogeaktualizowac.core.Json;
import pl.czymogeaktualizowac.core.Model.ChecksumResult;
import pl.czymogeaktualizowac.core.Model.Ecosystem;
import pl.czymogeaktualizowac.core.Model.ForumSignal;
import pl.czymogeaktualizowac.core.Model.ForumTopic;
import pl.czymogeaktualizowac.core.Model.PluginHeaders;
import pl.czymogeaktualizowac.core.Model.PluginState;
import pl.czymogeaktualizowac.core.Model.ReleaseRecord;
import pl.czymogeaktualizowac.core.Model.ReleasesFile;
import pl.czymogeaktualizowac.core.Model.TrackedPlugin;
import pl.czymogeaktualizowac.core.PluginZip;
import pl.czymogeaktualizowac.core.Signals;
import pl.czymogeaktualizowac.core.Store;
import pl.czymogeaktualizowac.core.Versions;
import pl.czymogeaktualizowac.core.WpOrg;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Component A. Polls WordPress.org for the tracked plugins and for the WordPress / WooCommerce / PHP ecosystem,
 * downloads and verifies new plugin versions, parses their headers, computes the community signal and writes
 * {@code data/releases.json} plus {@code data/last-run.json}, a diagnostic report of the run (what changed, which plugins
 * failed). Nothing downstream reads last-run.json: the lab queue is "every verified release without a result" (CI) and
 * alerts compare against {@code data/published-state.json}.
 */
public final class Watcher {

    /** What a run discovered; written to data/last-run.json. */
    public record Change(String kind, String subject, String version, String detail) {
    }

    /**
     * @param pluginsOk      tracked plugins processed without error
     * @param pluginsTracked tracked plugins this run looked at
     */
    public record RunReport(Instant at, List<Change> changes, List<String> errors, int pluginsOk, int pluginsTracked) {
        /** Nothing at all could be read (WordPress.org down, network gone): the only case that should stop the pipeline. */
        public boolean totalFailure() {
            return pluginsTracked > 0 && pluginsOk == 0;
        }
    }

    public record Options(int backfill, Set<String> onlyPlugins, boolean skipForum) {
        public static Options defaults() {
            return new Options(0, Set.of(), false);
        }
    }

    /** Checksum status of a release whose zip or checksum file could not be fetched; retried on the next run. */
    public static final String FETCH_FAILED = "FETCH_FAILED";

    private final Store store;
    private final WpOrg wp;
    private final Clock clock;
    private final Consumer<String> log;

    public Watcher(Store store, WpOrg wp, Clock clock, Consumer<String> log) {
        this.store = store;
        this.wp = wp;
        this.clock = clock;
        this.log = log;
    }

    public RunReport run(Options opts) {
        Instant now = clock.instant();
        ReleasesFile previous = store.loadReleases();
        Map<String, PluginState> state = new LinkedHashMap<>(previous.plugins());
        List<Change> changes = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int ok = 0;
        int seen = 0;

        for (TrackedPlugin tp : store.trackedPlugins()) {
            if (!opts.onlyPlugins().isEmpty() && !opts.onlyPlugins().contains(tp.slug())) {
                continue;
            }
            seen++;
            try {
                PluginState before = state.get(tp.slug());
                PluginState after = processPlugin(tp, before, opts, now, changes);
                state.put(tp.slug(), after);
                ok++;
            } catch (IOException | RuntimeException e) {
                String msg = tp.slug() + ": " + e.getMessage();
                errors.add(msg);
                log.accept("ERROR " + msg);
            }
        }

        Ecosystem eco = previous.ecosystem();
        try {
            Ecosystem fresh = wp.ecosystem(now);
            ecosystemChanges(previous.ecosystem(), fresh, changes);
            eco = fresh;
        } catch (IOException | RuntimeException e) {
            errors.add("ecosystem: " + e.getMessage());
            log.accept("ERROR ecosystem: " + e.getMessage());
        }

        store.saveReleases(state, eco, now);
        RunReport report = new RunReport(now, changes, errors, ok, seen);
        Json.write(store.dataDir.resolve("last-run.json"), report);
        return report;
    }

    private PluginState processPlugin(TrackedPlugin tp, PluginState before, Options opts, Instant now, List<Change> changes)
            throws IOException {
        WpOrg.PluginInfo info = wp.pluginInfo(tp.slug());
        Map<String, Instant> tagDates = Map.of();
        try {
            tagDates = wp.tagDates(tp.slug());
        } catch (IOException e) {
            log.accept("warn " + tp.slug() + ": no tag dates (" + e.getMessage() + ")");
        }
        List<ForumTopic> topics = List.of();
        if (!opts.skipForum()) {
            try {
                topics = wp.forumTopics(tp.slug());
            } catch (IOException e) {
                log.accept("warn " + tp.slug() + ": forum feed unavailable (" + e.getMessage() + ")");
            }
        }

        Map<String, ReleaseRecord> existing = new LinkedHashMap<>();
        if (before != null && before.releases() != null) {
            before.releases().forEach(r -> existing.put(r.version(), r));
        }

        // versions to process: latest stable + `backfill` previous stable ones; everything already known stays
        List<String> stable = info.versions().stream().filter(v -> !Versions.isPrerelease(v)).toList();
        List<String> wanted = new ArrayList<>();
        wanted.add(info.version());
        for (int i = stable.size() - 1, added = 0; i >= 0 && added < opts.backfill(); i--) {
            String v = stable.get(i);
            if (!v.equals(info.version())) {
                wanted.add(v);
                added++;
            }
        }
        for (String v : existing.keySet()) {
            if (!wanted.contains(v)) {
                wanted.add(v);
            }
        }

        List<ReleaseRecord> releases = new ArrayList<>();
        for (String version : wanted) {
            ReleaseRecord old = existing.get(version);
            Instant released = tagDates.get(version);
            boolean retry = old != null && old.checksum() != null && FETCH_FAILED.equals(old.checksum().status());
            if (old != null && !retry) {
                releases.add(refresh(old, released, topics, now, opts.skipForum()));
                continue;
            }
            ReleaseRecord rec = fetchRelease(tp.slug(), version, released, topics, now);
            if (retry) {
                // a download that failed earlier is retried on every run; it keeps its original first-seen time
                rec = new ReleaseRecord(rec.version(), rec.released() != null ? rec.released() : old.released(), old.firstSeen(),
                        rec.prerelease(), rec.headers(), rec.checksum(), rec.zipSha256(), rec.zipBytes(), rec.forum());
                releases.add(rec);
                log.accept("retried " + tp.slug() + " " + version + " checksum=" + rec.checksum().status());
                continue;
            }
            releases.add(rec);
            // the first run for a plugin is a baseline, and backfilled old versions are history: neither is "news"
            if (before != null && before.latestVersion() != null && Versions.compare(version, before.latestVersion()) > 0) {
                changes.add(new Change("plugin-release", tp.slug(), version, tp.name() + " " + version));
            }
            log.accept("new release " + tp.slug() + " " + version + " checksum=" + rec.checksum().status());
        }
        releases.sort((a, b) -> Versions.compare(b.version(), a.version()));

        return new PluginState(tp.slug(), info.name() != null ? info.name() : tp.name(), tp.vendor(), tp.category(),
                info.activeInstalls(), info.version(), info.lastUpdated(), info.testedWp(), info.requiresPhp(),
                info.supportThreads(), info.supportThreadsResolved(), info.homepage(), now, releases);
    }

    private ReleaseRecord fetchRelease(String slug, String version, Instant released, List<ForumTopic> topics, Instant now) {
        ForumSignal forum = Signals.forum(topics, version, released, now);
        byte[] zip;
        try {
            zip = wp.zip(slug, version);
        } catch (IOException e) {
            // transient (network, 5xx): FETCH_FAILED is retried on the next run and never handed to the lab
            return new ReleaseRecord(version, released, now, Versions.isPrerelease(version), null,
                    new ChecksumResult(FETCH_FAILED, 0, List.of(), List.of(), "download failed: " + e.getMessage()), null, 0, forum);
        }
        ChecksumResult checksum;
        try {
            JsonNode checks = wp.checksums(slug, version);
            checksum = PluginZip.verify(zip, slug, checks);
        } catch (IOException e) {
            return new ReleaseRecord(version, released, now, Versions.isPrerelease(version), null,
                    new ChecksumResult(FETCH_FAILED, 0, List.of(), List.of(), "checksum check failed: " + e.getMessage()), null, 0, forum);
        }
        // headers are informational: a zip whose header cannot be parsed keeps its (valid) checksum result
        PluginHeaders headers = null;
        try {
            headers = PluginZip.headers(zip, slug);
        } catch (IOException e) {
            log.accept("warn " + slug + " " + version + ": cannot parse plugin headers (" + e.getMessage() + ")");
        }
        return new ReleaseRecord(version, released, now, Versions.isPrerelease(version), headers, checksum,
                PluginZip.sha256(zip), zip.length, forum);
    }

    /** Fills in a release date that was unknown before and recomputes the signal until its 7-day window is complete. */
    private ReleaseRecord refresh(ReleaseRecord old, Instant released, List<ForumTopic> topics, Instant now, boolean skipForum) {
        Instant date = old.released() != null ? old.released() : released;
        ForumSignal forum = old.forum();
        boolean frozen = forum != null && forum.windowComplete() && !"UNKNOWN".equals(forum.level());
        if (!frozen && !skipForum) {
            forum = Signals.forum(topics, old.version(), date, now);
        }
        return new ReleaseRecord(old.version(), date, old.firstSeen(), old.prerelease(), old.headers(), old.checksum(),
                old.zipSha256(), old.zipBytes(), forum);
    }

    private static void ecosystemChanges(Ecosystem old, Ecosystem now, List<Change> changes) {
        if (old == null) {
            return;
        }
        if (!now.wp().latest().equals(old.wp().latest())) {
            changes.add(new Change("wordpress-release", "wordpress", now.wp().latest(), "WordPress " + now.wp().latest()));
        }
        if (now.wp().development() != null && !now.wp().development().equals(old.wp().development())) {
            changes.add(new Change("wordpress-prerelease", "wordpress", now.wp().development(), "WordPress " + now.wp().development()));
        }
        if (!now.woo().latest().equals(old.woo().latest())) {
            changes.add(new Change("woocommerce-release", "woocommerce", now.woo().latest(), "WooCommerce " + now.woo().latest()));
        }
        if (now.woo().prerelease() != null && !now.woo().prerelease().equals(old.woo().prerelease())) {
            changes.add(new Change("woocommerce-prerelease", "woocommerce", now.woo().prerelease(), "WooCommerce " + now.woo().prerelease()));
        }
        String oldPhp = old.php() == null || old.php().isEmpty() ? null : old.php().get(0).latest();
        String newPhp = now.php().isEmpty() ? null : now.php().get(0).latest();
        if (newPhp != null && !newPhp.equals(oldPhp)) {
            changes.add(new Change("php-release", "php", newPhp, "PHP " + newPhp));
        }
    }
}
