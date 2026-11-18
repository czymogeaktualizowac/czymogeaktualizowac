package pl.czymogeaktualizowac.publisher;

import pl.czymogeaktualizowac.core.Json;
import pl.czymogeaktualizowac.core.Model.LevelResult;
import pl.czymogeaktualizowac.core.Model.ResultsFile;
import pl.czymogeaktualizowac.core.Model.RunResult;
import pl.czymogeaktualizowac.core.Model.Status;
import pl.czymogeaktualizowac.core.Store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds back failing results before they reach the site, feeds and alerts (plan §2.4: vendors get a private failure
 * report before publication; and a failure may be a lab problem, which must never be published as the plugin's).
 *
 * <p>A release is held when any of its runs has a FAIL or ERROR level, until one of:
 * a {@code "publish"} decision in {@code config/embargo.json}; or {@code holdDays} have passed since the failing run
 * (unless the decision is {@code "hold"}, which keeps it back until changed). A retest that passes lifts it on its own.
 *
 * <p>Limits: this only controls what we publish. In a public repository the raw results stay readable on the
 * {@code data} branch, in the Actions logs and in the run artifacts.
 */
public final class Embargo {

    public record Decision(String slug, String version, /* publish | hold */ String decision, String note) {
    }

    public record Config(Integer holdDays, List<Decision> decisions) {
    }

    /** A held release; {@code until} is null when it is held until a decision; {@code decided} is a "hold" decision. */
    public record Held(String slug, String version, Instant since, Instant until, boolean decided, List<String> where) {
        public String describe() {
            return slug + " " + version + ": " + String.join(", ", where) + " (od " + since + ", "
                    + (until == null ? "do decyzji" : "publikacja automatyczna " + until) + ")";
        }
    }

    public static final int DEFAULT_HOLD_DAYS = 7;

    /** Replaces the runs of a held release; recognised by identity, so publisher code needs no other parameter. */
    static final List<RunResult> HELD_RUNS = Collections.unmodifiableList(new ArrayList<>());

    private final int holdDays;
    private final Map<String, Decision> decisions = new LinkedHashMap<>();

    public Embargo(Config cfg) {
        this.holdDays = cfg == null || cfg.holdDays() == null ? DEFAULT_HOLD_DAYS : cfg.holdDays();
        if (cfg != null && cfg.decisions() != null) {
            for (Decision d : cfg.decisions()) {
                decisions.put(d.slug() + "|" + d.version(), d);
            }
        }
    }

    public static Path configPath(Store store) {
        return store.configDir.resolve("embargo.json");
    }

    public static Embargo load(Store store) {
        Path p = configPath(store);
        return new Embargo(Files.exists(p) ? Json.read(p, Config.class) : null);
    }

    static boolean isHeld(List<RunResult> runs) {
        return runs == HELD_RUNS;
    }

    /** The releases held back at {@code now}. */
    public List<Held> held(Map<String, ResultsFile> results, Instant now) {
        List<Held> out = new ArrayList<>();
        for (ResultsFile f : results.values()) {
            Held h = check(f, now);
            if (h != null) {
                out.add(h);
            }
        }
        return out;
    }

    /** A copy of {@code results} in which every held release has {@link #HELD_RUNS} in place of its runs. */
    public Map<String, ResultsFile> apply(Map<String, ResultsFile> results, Instant now) {
        Map<String, ResultsFile> out = new LinkedHashMap<>();
        for (var en : results.entrySet()) {
            ResultsFile f = en.getValue();
            out.put(en.getKey(), check(f, now) == null ? f : new ResultsFile(f.pluginSlug(), f.pluginVersion(), HELD_RUNS));
        }
        return out;
    }

    private Held check(ResultsFile f, Instant now) {
        if (f == null || f.runs() == null) {
            return null;
        }
        List<String> where = new ArrayList<>();
        Instant since = null;
        for (RunResult r : f.runs()) {
            boolean failed = false;
            for (LevelResult l : r.levels()) {
                if (l.status() == Status.FAIL || l.status() == Status.ERROR) {
                    where.add(l.level() + " " + l.status() + " (" + r.profileId() + ", " + r.checkout() + ", " + r.mode() + ")");
                    failed = true;
                }
            }
            Instant t = r.finishedAt() != null ? r.finishedAt() : r.startedAt();
            if (failed && t != null && (since == null || t.isBefore(since))) {
                since = t;
            }
        }
        if (where.isEmpty()) {
            return null;
        }
        Decision d = decisions.get(f.pluginSlug() + "|" + f.pluginVersion());
        if (d != null && "publish".equals(d.decision())) {
            return null;
        }
        if (since == null) {
            since = now;
        }
        boolean hold = d != null && "hold".equals(d.decision());
        Instant until = hold ? null : since.plus(Duration.ofDays(holdDays));
        if (until != null && !now.isBefore(until)) {
            return null;
        }
        return new Held(f.pluginSlug(), f.pluginVersion(), since, until, hold, where);
    }
}
