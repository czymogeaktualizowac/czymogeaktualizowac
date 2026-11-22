package pl.czymogeaktualizowac.publisher;

import pl.czymogeaktualizowac.core.Model.Ecosystem;
import pl.czymogeaktualizowac.core.Model.PluginState;
import pl.czymogeaktualizowac.core.Model.ReleaseRecord;
import pl.czymogeaktualizowac.core.Model.ReleasesFile;
import pl.czymogeaktualizowac.core.Model.ResultsFile;
import pl.czymogeaktualizowac.core.Model.RunResult;
import pl.czymogeaktualizowac.core.Model.TrackedPlugin;
import pl.czymogeaktualizowac.core.Store;
import pl.czymogeaktualizowac.publisher.View.Verdict;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What we publish, decided once: every tracked plugin with its releases, the embargo applied and each release's
 * verdict computed. The site, the feeds, the alerts and the published state all read this, so they can never
 * disagree about a release (before, each of them looked up runs, the latest release and the verdict on its own).
 *
 * <p>A held release has {@code held = true}, its runs are hidden (empty) and its verdict is {@link Verdicts#HELD}.
 */
public final class Published {

    /** One release as published. {@code runs} are empty when held, so nothing downstream can leak a held result. */
    public record Release(ReleaseRecord record, List<RunResult> runs, boolean held, Verdict verdict, String labSummary) {
        public String version() {
            return record.version();
        }

        /** Release time, or first-seen time when the tag date is unknown (for feeds and digests). */
        public Instant when() {
            return record.released() != null ? record.released() : record.firstSeen();
        }
    }

    /** One tracked plugin with at least one known release; {@code releases} newest first, as the watcher stores them. */
    public record Plugin(TrackedPlugin tracked, PluginState state, List<Release> releases, Release latest) {
        public String slug() {
            return tracked.slug();
        }

        public String name() {
            return state.name();
        }
    }

    private final List<Plugin> plugins;
    private final Ecosystem ecosystem;

    private Published(List<Plugin> plugins, Ecosystem ecosystem) {
        this.plugins = List.copyOf(plugins);
        this.ecosystem = ecosystem;
    }

    public static Published of(Store store, Instant now) {
        return of(store.trackedPlugins(), store.loadReleases(), store.allResults(), Embargo.load(store), now);
    }

    static Published of(List<TrackedPlugin> tracked, ReleasesFile releases, Map<String, ResultsFile> results,
                        Embargo embargo, Instant now) {
        List<Plugin> out = new ArrayList<>();
        for (TrackedPlugin tp : tracked) {
            PluginState st = releases.plugins().get(tp.slug());
            if (st == null || st.releases() == null || st.releases().isEmpty()) {
                continue; // nothing known yet (first watch failed): not published
            }
            List<Release> rels = new ArrayList<>();
            Release latest = null;
            for (ReleaseRecord r : st.releases()) {
                ResultsFile f = results.get(tp.slug() + "|" + r.version());
                boolean held = f != null && embargo.holds(f, now);
                List<RunResult> runs = held || f == null || f.runs() == null ? List.of() : List.copyOf(f.runs());
                Release rel = new Release(r, runs, held, Verdicts.of(r, runs, held), Verdicts.labSummary(runs, held));
                rels.add(rel);
                if (r.version().equals(st.latestVersion())) {
                    latest = rel;
                }
            }
            // the watcher's latest version is normally among the releases; if not, the newest known one stands in
            out.add(new Plugin(tp, st, List.copyOf(rels), latest != null ? latest : rels.get(0)));
        }
        return new Published(out, releases.ecosystem());
    }

    /** Tracked plugins that have at least one release, in the order of config/plugins.json. */
    public List<Plugin> plugins() {
        return plugins;
    }

    public Optional<Plugin> plugin(String slug) {
        return plugins.stream().filter(p -> p.slug().equals(slug)).findFirst();
    }

    public Ecosystem ecosystem() {
        return ecosystem;
    }
}
