package pl.czymogeaktualizowac.core;

import com.fasterxml.jackson.core.type.TypeReference;
import pl.czymogeaktualizowac.core.Model.Ecosystem;
import pl.czymogeaktualizowac.core.Model.PluginState;
import pl.czymogeaktualizowac.core.Model.Profile;
import pl.czymogeaktualizowac.core.Model.ReleasesFile;
import pl.czymogeaktualizowac.core.Model.ResultsFile;
import pl.czymogeaktualizowac.core.Model.RunResult;
import pl.czymogeaktualizowac.core.Model.TrackedPlugin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * File layout (the {@code data/} directory is the "git as database" store; in CI it lives on the data branch):
 * <pre>
 *   config/plugins.json, config/profiles.json     what we track and how we test
 *   data/releases.json                            watcher output (plugin state, releases, ecosystem)
 *   data/results/&lt;slug&gt;/&lt;version&gt;.json          lab output, one file per plugin version
 *   cache/zips/*.zip                              downloaded plugin zips (not committed)
 * </pre>
 */
public final class Store {

    public final Path root;
    public final Path configDir;
    public final Path dataDir;
    public final Path cacheDir;

    public Store(Path root) {
        this.root = root.toAbsolutePath().normalize();
        this.configDir = this.root.resolve("config");
        this.dataDir = this.root.resolve("data");
        this.cacheDir = this.root.resolve("cache");
    }

    public List<TrackedPlugin> trackedPlugins() {
        return Json.read(configDir.resolve("plugins.json"), new TypeReference<List<TrackedPlugin>>() {
        });
    }

    public List<Profile> profiles() {
        return Json.read(configDir.resolve("profiles.json"), new TypeReference<List<Profile>>() {
        });
    }

    public Path releasesPath() {
        return dataDir.resolve("releases.json");
    }

    public ReleasesFile loadReleases() {
        if (!Files.exists(releasesPath())) {
            return new ReleasesFile(null, new LinkedHashMap<>(), null);
        }
        ReleasesFile f = Json.read(releasesPath(), ReleasesFile.class);
        return new ReleasesFile(f.generatedAt(), new LinkedHashMap<>(f.plugins() == null ? Map.of() : f.plugins()), f.ecosystem());
    }

    public void saveReleases(Map<String, PluginState> plugins, Ecosystem ecosystem, Instant now) {
        Json.write(releasesPath(), new ReleasesFile(now, new LinkedHashMap<>(plugins), ecosystem));
    }

    public Path resultsPath(String slug, String version) {
        return dataDir.resolve("results").resolve(slug).resolve(version + ".json");
    }

    public ResultsFile loadResults(String slug, String version) {
        Path p = resultsPath(slug, version);
        if (!Files.exists(p)) {
            return new ResultsFile(slug, version, new ArrayList<>());
        }
        ResultsFile f = Json.read(p, ResultsFile.class);
        return new ResultsFile(slug, version, new ArrayList<>(f.runs() == null ? List.of() : f.runs()));
    }

    /** Replaces an earlier run with the same profile/mode/checkout (re-runs overwrite, they do not pile up). */
    public void saveRun(RunResult run) {
        ResultsFile f = loadResults(run.pluginSlug(), run.pluginVersion());
        List<RunResult> runs = new ArrayList<>(f.runs());
        runs.removeIf(r -> r.profileId().equals(run.profileId()) && r.mode().equals(run.mode()) && r.checkout().equals(run.checkout()));
        runs.add(run);
        runs.sort((a, b) -> (a.profileId() + a.mode() + a.checkout()).compareTo(b.profileId() + b.mode() + b.checkout()));
        Json.write(resultsPath(run.pluginSlug(), run.pluginVersion()), new ResultsFile(run.pluginSlug(), run.pluginVersion(), runs));
    }

    /** All lab results, keyed by "slug|version". */
    public Map<String, ResultsFile> allResults() {
        Map<String, ResultsFile> out = new LinkedHashMap<>();
        Path base = dataDir.resolve("results");
        if (!Files.isDirectory(base)) {
            return out;
        }
        try (Stream<Path> files = Files.walk(base)) {
            for (Path p : files.filter(x -> x.toString().endsWith(".json")).sorted().toList()) {
                ResultsFile f = Json.read(p, ResultsFile.class);
                out.put(f.pluginSlug() + "|" + f.pluginVersion(), f);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }
}
