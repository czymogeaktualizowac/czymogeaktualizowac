package pl.czymogeaktualizowac.publisher;

import org.junit.jupiter.api.Test;
import pl.czymogeaktualizowac.core.Model.ChecksumResult;
import pl.czymogeaktualizowac.core.Model.ForumSignal;
import pl.czymogeaktualizowac.core.Model.Level;
import pl.czymogeaktualizowac.core.Model.LevelResult;
import pl.czymogeaktualizowac.core.Model.ReleaseRecord;
import pl.czymogeaktualizowac.core.Model.RunResult;
import pl.czymogeaktualizowac.core.Model.Status;
import pl.czymogeaktualizowac.publisher.View.Verdict;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerdictsTest {

    private static ReleaseRecord release(String forumLevel, String checksum) {
        return new ReleaseRecord("1.0.0", Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T01:00:00Z"), false, null,
                new ChecksumResult(checksum, 1, List.of(), List.of(), "detail"), "sha", 1,
                new ForumSignal(forumLevel.equals("ELEVATED") ? 3 : 0, 0, forumLevel, true, List.of()));
    }

    private static RunResult run(String profile, String checkout, Level highest, LevelResult... levels) {
        return new RunResult("p", "1.0.0", profile, "solo", checkout, "8.3", "7.1.4", "11.2.2", Instant.EPOCH, Instant.EPOCH,
                List.of(levels), highest, null, List.of());
    }

    private static LevelResult lr(Level l, Status s) {
        return new LevelResult(l, s, "d", 1);
    }

    @Test
    void noRunsAndQuietForumIsNoneNeverOk() {
        Verdict v = Verdicts.of(release("NONE", "OK"), List.of());
        assertEquals("none", v.code());
    }

    @Test
    void passingCoreProfileIsOkButSkippedHigherLevelsAreNotClaimed() {
        RunResult r = run("P3", "classic", Level.L2, lr(Level.L0, Status.PASS), lr(Level.L1, Status.PASS), lr(Level.L2, Status.PASS),
                lr(Level.L3, Status.SKIPPED), lr(Level.L4, Status.SKIPPED));
        Verdict v = Verdicts.of(release("NONE", "OK"), List.of(r));
        assertEquals("ok", v.code());
        assertTrue(v.label().contains("L0–L2"));
    }

    @Test
    void failureOnCoreProfileIsBad() {
        RunResult r = run("P3", "block", Level.L1, lr(Level.L0, Status.PASS), lr(Level.L1, Status.PASS), lr(Level.L2, Status.FAIL));
        Verdict v = Verdicts.of(release("NONE", "OK"), List.of(r));
        assertEquals("bad", v.code());
        assertTrue(v.detail().contains("P3"));
    }

    @Test
    void failureOnlyOnLegacyOrEdgeProfileIsWarn() {
        RunResult ok = run("P3", "classic", Level.L2, lr(Level.L0, Status.PASS), lr(Level.L1, Status.PASS), lr(Level.L2, Status.PASS));
        RunResult legacy = run("P1", "classic", null, lr(Level.L0, Status.FAIL));
        assertEquals("warn", Verdicts.of(release("NONE", "OK"), List.of(ok, legacy)).code());
    }

    @Test
    void elevatedForumDowngradesPassToWarn() {
        RunResult r = run("P2", "classic", Level.L2, lr(Level.L0, Status.PASS), lr(Level.L1, Status.PASS), lr(Level.L2, Status.PASS));
        assertEquals("warn", Verdicts.of(release("ELEVATED", "OK"), List.of(r)).code());
    }

    @Test
    void codeChecksumMismatchIsWarnNeverAccusation() {
        Verdict v = Verdicts.of(release("NONE", "MISMATCH"), List.of());
        assertEquals("warn", v.code());
        assertTrue(!v.label().toLowerCase().contains("nie używaj"));
    }

    @Test
    void failedL3CountsOnlyWhenItActuallyFailed() {
        RunResult r = run("P3", "classic", Level.L2, lr(Level.L0, Status.PASS), lr(Level.L1, Status.PASS), lr(Level.L2, Status.PASS),
                lr(Level.L3, Status.FAIL));
        assertEquals("bad", Verdicts.of(release("NONE", "OK"), List.of(r)).code());
    }
}
