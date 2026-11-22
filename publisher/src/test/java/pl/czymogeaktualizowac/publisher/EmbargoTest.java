package pl.czymogeaktualizowac.publisher;

import org.junit.jupiter.api.Test;
import pl.czymogeaktualizowac.core.Model.ChecksumResult;
import pl.czymogeaktualizowac.core.Model.ForumSignal;
import pl.czymogeaktualizowac.core.Model.Level;
import pl.czymogeaktualizowac.core.Model.LevelResult;
import pl.czymogeaktualizowac.core.Model.PluginState;
import pl.czymogeaktualizowac.core.Model.ReleaseRecord;
import pl.czymogeaktualizowac.core.Model.ReleasesFile;
import pl.czymogeaktualizowac.core.Model.ResultsFile;
import pl.czymogeaktualizowac.core.Model.RunResult;
import pl.czymogeaktualizowac.core.Model.Status;
import pl.czymogeaktualizowac.core.Model.TrackedPlugin;
import pl.czymogeaktualizowac.publisher.View.Verdict;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbargoTest {

    private static final Instant RUN_AT = Instant.parse("2026-11-17T22:00:00Z");

    private static RunResult run(Status l2, Status l3) {
        return new RunResult("p", "1.0.0", "P3", "solo", "classic", "8.3", "7.1.4", "11.2.2", RUN_AT, RUN_AT,
                List.of(new LevelResult(Level.L0, Status.PASS, "d", 1), new LevelResult(Level.L1, Status.PASS, "d", 1),
                        new LevelResult(Level.L2, l2, "d", 1), new LevelResult(Level.L3, l3, "d", 1)),
                l2 == Status.PASS ? (l3 == Status.PASS ? Level.L3 : Level.L2) : Level.L1, null, List.of());
    }

    private static Map<String, ResultsFile> results(RunResult... runs) {
        return Map.of("p|1.0.0", new ResultsFile("p", "1.0.0", List.of(runs)));
    }

    private static Embargo embargo(String decision) {
        return new Embargo(new Embargo.Config(7, decision == null ? List.of()
                : List.of(new Embargo.Decision("p", "1.0.0", decision, "test"))));
    }

    private static ReleaseRecord release() {
        return new ReleaseRecord("1.0.0", RUN_AT, RUN_AT, false, null, new ChecksumResult("OK", 1, List.of(), List.of(), "ok"),
                "sha", 1, new ForumSignal(0, 0, "NONE", true, List.of()));
    }

    /** The release "p 1.0.0" as the site, feeds and alerts see it (see {@link Published}). */
    private static Published.Release published(Embargo embargo, Map<String, ResultsFile> results, Instant now) {
        var tracked = List.of(new TrackedPlugin("p", "P", "V", "shipping", null, null));
        var state = new PluginState("p", "P", "V", "shipping", 1, "1.0.0", null, null, null, 0, 0, null, RUN_AT, List.of(release()));
        var releases = new ReleasesFile(RUN_AT, Map.of("p", state), null);
        return Published.of(tracked, releases, results, embargo, now).plugins().get(0).latest();
    }

    @Test
    void failingReleaseIsHeldAndShownNeutrally() {
        Published.Release r = published(embargo(null), results(run(Status.PASS, Status.FAIL)), RUN_AT.plusSeconds(60));
        assertTrue(r.held());
        assertTrue(r.runs().isEmpty(), "held runs are hidden from everything downstream");
        Verdict v = r.verdict();
        assertEquals("none", v.code());
        assertTrue(v.label().contains("weryfikacji"));
        assertEquals("w weryfikacji", r.labSummary());
    }

    @Test
    void infrastructureErrorIsNotHeldBecauseItIsNeverPublishedAsAFailure() {
        Map<String, ResultsFile> in = results(run(Status.ERROR, Status.SKIPPED));
        assertTrue(embargo(null).held(in, RUN_AT).isEmpty());
        // even long after holdDays, an ERROR-only release reads "result unknown", never "bad"
        Published.Release r = published(embargo(null), in, RUN_AT.plus(Duration.ofDays(30)));
        assertFalse(r.held());
        assertEquals("none", r.verdict().code());
    }

    @Test
    void passingAndSkippedResultsAreNotHeld() {
        Map<String, ResultsFile> in = results(run(Status.PASS, Status.SKIPPED), run(Status.PASS, Status.PASS));
        assertTrue(embargo(null).held(in, RUN_AT).isEmpty());
        Published.Release r = published(embargo(null), in, RUN_AT);
        assertFalse(r.held());
        assertEquals(2, r.runs().size());
        assertEquals("ok", r.verdict().code());
    }

    @Test
    void heldStateSurvivesAnyCopyOfTheRuns() {
        // the old design recognised "held" by the identity of one empty list, so any copy silently lost it
        Published.Release r = published(embargo(null), results(run(Status.PASS, Status.FAIL)), RUN_AT);
        assertEquals("none", Verdicts.of(release(), new java.util.ArrayList<>(r.runs()), r.held()).code());
        assertTrue(Verdicts.of(release(), List.copyOf(r.runs()), r.held()).label().contains("weryfikacji"));
    }

    @Test
    void publishDecisionReleasesAtOnce() {
        assertTrue(embargo("publish").held(results(run(Status.PASS, Status.FAIL)), RUN_AT).isEmpty());
    }

    @Test
    void heldUntilHoldDaysThenPublishedAutomatically() {
        Map<String, ResultsFile> in = results(run(Status.PASS, Status.FAIL));
        Embargo.Held h = embargo(null).held(in, RUN_AT.plus(Duration.ofDays(6))).get(0);
        assertEquals(RUN_AT.plus(Duration.ofDays(7)), h.until());
        assertFalse(h.decided());
        assertTrue(embargo(null).held(in, RUN_AT.plus(Duration.ofDays(7))).isEmpty());
    }

    @Test
    void holdDecisionKeepsItBackPastHoldDays() {
        Embargo.Held h = embargo("hold").held(results(run(Status.PASS, Status.FAIL)), RUN_AT.plus(Duration.ofDays(30))).get(0);
        assertNull(h.until());
        assertTrue(h.decided());
    }
}
