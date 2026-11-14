package pl.czymogeaktualizowac.app;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import pl.czymogeaktualizowac.core.Store;
import pl.czymogeaktualizowac.publisher.Alerts;
import pl.czymogeaktualizowac.publisher.SiteBuilder;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;

final class PublishCommands {

    private PublishCommands() {
    }

    @Command(name = "publish", description = "Build the static site (pages, RSS, sitemap, feed.json) from data/ and config/.")
    static class PublishCmd implements Callable<Integer> {
        @CommandLine.ParentCommand
        Main parent;
        @Option(names = "--out", description = "Output directory (default: site-out).")
        Path out = Path.of("site-out");
        @Option(names = "--mark-published", description = "Record the published state (data/published-state.json), the baseline for the next 'alerts' run.")
        boolean markPublished;

        @Override
        public Integer call() throws IOException {
            Store store = Main.store(parent.root);
            Instant now = Clock.systemUTC().instant();
            SiteBuilder.BuildResult r = new SiteBuilder().build(store, parent.root.resolve(out).normalize(), now);
            Main.log("site written: " + r.outDir() + " (" + r.pages() + " files)");
            if (markPublished) {
                Alerts.saveState(store, Alerts.currentState(store, now));
                Main.log("published state recorded");
            }
            return 0;
        }
    }

    @Command(name = "alerts", description = "Plan alert e-mails by comparing current data with the last published state; writes drafts to an outbox.")
    static class AlertsCmd implements Callable<Integer> {
        @CommandLine.ParentCommand
        Main parent;
        @Option(names = "--outbox", description = "Directory for drafts (default: out/alerts).")
        Path outbox = Path.of("out/alerts");
        @Option(names = "--digest", description = "Also draft the monthly digest.")
        boolean digest;

        @Override
        public Integer call() throws IOException {
            Store store = Main.store(parent.root);
            Instant now = Clock.systemUTC().instant();
            List<Alerts.Draft> drafts = Alerts.plan(store, SiteBuilder.loadConfig(store), now, digest);
            Alerts.MailProvider provider = new Alerts.FileOutbox(parent.root.resolve(outbox).normalize());
            for (Alerts.Draft d : drafts) {
                provider.send(d);
                Main.log("draft: " + d.kind() + " " + d.subject() + " -> " + d.segmentTags());
            }
            Main.log(drafts.isEmpty() ? "no alerts to send" : drafts.size() + " draft(s) written to " + outbox);
            return 0;
        }
    }
}
