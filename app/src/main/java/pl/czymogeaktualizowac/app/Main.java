package pl.czymogeaktualizowac.app;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import pl.czymogeaktualizowac.core.Http;
import pl.czymogeaktualizowac.core.Store;
import pl.czymogeaktualizowac.core.WpOrg;
import pl.czymogeaktualizowac.watcher.SpikeReport;
import pl.czymogeaktualizowac.watcher.Watcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.Callable;

/** Single entry point: {@code java -jar czymogeaktualizowac.jar <command>}. */
@Command(name = "czymogeaktualizowac", mixinStandardHelpOptions = true, version = "0.1.0",
        description = "Independent, automated testing of Polish WooCommerce plugin releases.",
        subcommands = {Main.WatchCmd.class, Main.SpikeCmd.class, LabCommands.class, PublishCommands.PublishCmd.class,
                PublishCommands.AlertsCmd.class})
public class Main {

    @Option(names = "--root", description = "Repository root (contains config/, data/, cache/). Default: current directory.",
            scope = CommandLine.ScopeType.INHERIT)
    Path root = Path.of(".");

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }

    static Store store(Path root) {
        return new Store(root);
    }

    static void log(String s) {
        System.out.println(s);
    }

    @Command(name = "watch", description = "Poll WordPress.org, record new releases, verify zips, compute the community signal.")
    static class WatchCmd implements Callable<Integer> {
        @CommandLine.ParentCommand
        Main parent;
        @Option(names = "--backfill", description = "Also process the N previous stable releases of each plugin (history for launch).")
        int backfill = 0;
        @Option(names = "--only", split = ",", description = "Only these plugin slugs.")
        String[] only = new String[0];
        @Option(names = "--skip-forum", description = "Do not read the support forums.")
        boolean skipForum;

        @Override
        public Integer call() {
            Store store = store(parent.root);
            WpOrg wp = new WpOrg(new Http(), store.cacheDir);
            Watcher w = new Watcher(store, wp, Clock.systemUTC(), Main::log);
            Watcher.RunReport r = w.run(new Watcher.Options(backfill, new HashSet<>(Arrays.asList(only)), skipForum));
            log("changes: " + r.changes().size() + ", errors: " + r.errors().size());
            r.changes().forEach(c -> log("  " + c.kind() + " " + c.subject() + " " + c.version()));
            r.errors().forEach(e -> log("  ERROR " + e));
            return r.errors().isEmpty() ? 0 : 1;
        }
    }

    @Command(name = "spike", description = "Spike 0 as code: probe all endpoints and write docs/spike0-report.md.")
    static class SpikeCmd implements Callable<Integer> {
        @CommandLine.ParentCommand
        Main parent;

        @Override
        public Integer call() throws IOException {
            Store store = store(parent.root);
            SpikeReport report = new SpikeReport(store, new WpOrg(new Http(), store.cacheDir), Clock.systemUTC(), Main::log);
            String md = report.render();
            Path out = store.root.resolve("docs").resolve("spike0-report.md");
            Files.createDirectories(out.getParent());
            Files.writeString(out, md, StandardCharsets.UTF_8);
            log("wrote " + out);
            return 0;
        }
    }
}
