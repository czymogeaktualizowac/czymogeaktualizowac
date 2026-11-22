package pl.czymogeaktualizowac.app;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import pl.czymogeaktualizowac.core.Http;
import pl.czymogeaktualizowac.core.Json;
import pl.czymogeaktualizowac.core.Model;
import pl.czymogeaktualizowac.core.Model.PluginState;
import pl.czymogeaktualizowac.core.Model.Status;
import pl.czymogeaktualizowac.core.Store;
import pl.czymogeaktualizowac.lab.ImagePins;
import pl.czymogeaktualizowac.lab.LabService;
import pl.czymogeaktualizowac.lab.RunSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;

/** {@code lab prepare | run | baseline | selftest | pin-images} */
@Command(name = "lab", description = "Disposable WooCommerce lab: run levels L0-L4 for plugin releases.",
        subcommands = {LabCommands.PrepareCmd.class, LabCommands.RunCmd.class, LabCommands.BaselineCmd.class, LabCommands.SelftestCmd.class,
                LabCommands.PinImagesCmd.class})
final class LabCommands implements Callable<Integer> {

    @Override
    public Integer call() {
        new CommandLine(this).usage(System.out);
        return 0;
    }

    abstract static class Base {
        @CommandLine.ParentCommand
        LabCommands lab;
        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        Store store() {
            Main main = spec.root().commandLine().getCommand();
            return Main.store(main.root);
        }
    }

    @Command(name = "prepare", description = "Download/verify WP-CLI and pull the docker images used by the profiles.")
    static class PrepareCmd extends Base implements Callable<Integer> {
        @Override
        public Integer call() throws IOException {
            new LabService(store(), Main::log, true).prepare();
            return 0;
        }
    }

    @Command(name = "pin-images", description = "Resolve the lab's docker images to their current digests and rewrite config/lab-images.json. "
            + "Review the diff and merge it via a PR.")
    static class PinImagesCmd extends Base implements Callable<Integer> {
        @Option(names = "--php", split = ",", description = "PHP versions to pin (default: those already pinned plus every profile's).")
        String[] php = new String[0];

        @Override
        public Integer call() throws IOException {
            Store store = store();
            Path file = store.configDir.resolve("lab-images.json");
            Model.LabImages current = Files.exists(file) ? store.labImages() : null;
            java.util.TreeSet<String> phps = new java.util.TreeSet<>(Arrays.asList(php));
            if (phps.isEmpty()) {
                if (current != null && current.wordpress() != null) {
                    phps.addAll(current.wordpress().keySet());
                }
                store.profiles().forEach(p -> phps.add(p.php()));
            }
            Model.LabImages fresh = new ImagePins(new Http()).resolve(current, phps);
            Json.write(file, fresh);
            int changed = 0;
            for (String v : phps) {
                String before = current == null || current.wordpress() == null ? null : current.wordpress().get(v);
                if (!fresh.wordpress().get(v).equals(before)) {
                    changed++;
                    Main.log("PHP " + v + ": " + fresh.wordpress().get(v));
                }
            }
            if (current == null || !fresh.database().equals(current.database())) {
                changed++;
                Main.log("database: " + fresh.database());
            }
            Main.log(changed == 0 ? "all pins are current" : changed + " pin(s) changed in " + file);
            return 0;
        }
    }

    @Command(name = "run", description = "Run one plugin release through the lab and store the results in data/results/.")
    static class RunCmd extends Base implements Callable<Integer> {
        @Option(names = "--plugin", required = true, description = "Tracked plugin slug.")
        String plugin;
        @Option(names = "--version", description = "Release version (default: latest known to the watcher).")
        String version;
        @Option(names = "--profiles", split = ",", description = "Profile ids, e.g. P2,P3 (default: all).")
        String[] profiles = new String[0];
        @Option(names = "--checkout", split = ",", description = "classic,block (default: whatever each profile defines).")
        String[] checkout = new String[0];
        @Option(names = "--mode", defaultValue = "solo", description = "solo = plugin + WooCommerce only; stack = with all other tracked plugins.")
        String mode;
        @Option(names = "--force", description = "Re-run even when a result already exists.")
        boolean force;
        @Option(names = "--headed", description = "Show the browser.")
        boolean headed;

        @Override
        public Integer call() throws IOException {
            LabService svc = new LabService(store(), Main::log, !headed);
            List<RunSpec> specs = svc.plan(plugin, version, Arrays.asList(profiles), new HashSet<>(Arrays.asList(checkout)), mode, !force);
            if (specs.isEmpty()) {
                Main.log("nothing to do (results exist; use --force)");
                return 0;
            }
            int bad = 0;
            for (RunSpec s : specs) {
                var r = svc.execute(s);
                if (r.levels().stream().anyMatch(l -> l.status() == Status.ERROR)) {
                    bad++;
                    Main.annotate("warning", "lab error", plugin + " " + r.pluginVersion() + " " + s.profileId() + "/" + s.checkout()
                            + "/" + s.mode() + ": " + r.notes());
                }
            }
            // a lab ERROR is a stored result ("result unknown", retried by the pipeline), not a crash of this command:
            // exiting non-zero would stop the remaining runs and the upload of the results that did complete
            if (bad > 0) {
                Main.log(bad + " run(s) ended with a lab (infrastructure) error; stored as 'result unknown'");
            }
            return 0;
        }
    }

    @Command(name = "baseline", description = "Run the latest release of every tracked plugin (or --only) for the given profiles.")
    static class BaselineCmd extends Base implements Callable<Integer> {
        @Option(names = "--profiles", split = ",", description = "Profile ids (default: P3).")
        String[] profiles = {"P3"};
        @Option(names = "--checkout", split = ",", description = "classic,block (default: both).")
        String[] checkout = new String[0];
        @Option(names = "--only", split = ",", description = "Only these plugin slugs.")
        String[] only = new String[0];
        @Option(names = "--mode", defaultValue = "solo")
        String mode;
        @Option(names = "--force")
        boolean force;

        @Override
        public Integer call() throws IOException {
            Store store = store();
            LabService svc = new LabService(store, Main::log, true);
            var rel = store.loadReleases();
            int errors = 0;
            for (var tp : store.trackedPlugins()) {
                if (only.length > 0 && !Arrays.asList(only).contains(tp.slug())) {
                    continue;
                }
                PluginState st = rel.plugins().get(tp.slug());
                if (st == null) {
                    continue;
                }
                for (RunSpec s : svc.plan(tp.slug(), null, Arrays.asList(profiles), new HashSet<>(Arrays.asList(checkout)), mode, !force)) {
                    var r = svc.execute(s);
                    if (r.levels().stream().anyMatch(l -> l.status() == Status.ERROR)) {
                        errors++;
                    }
                }
            }
            Main.log("baseline finished, runs with infrastructure errors (stored as 'result unknown'): " + errors);
            return 0;
        }
    }

    @Command(name = "selftest", description = "Prove the lab separates good from bad plugins using fixture plugins.")
    static class SelftestCmd extends Base implements Callable<Integer> {
        @Option(names = "--php", defaultValue = "8.3")
        String php;
        @Option(names = "--checkout", defaultValue = "classic")
        String checkout;

        @Override
        public Integer call() throws IOException {
            List<String> lines = new LabService(store(), Main::log, true).selftest(php, checkout);
            lines.forEach(Main::log);
            return lines.stream().anyMatch(l -> l.startsWith("FAIL")) ? 1 : 0;
        }
    }
}
