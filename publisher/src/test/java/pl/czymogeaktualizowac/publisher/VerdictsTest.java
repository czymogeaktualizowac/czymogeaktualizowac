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
    void passedGatewayIsNamedOnlyWhenEveryCoreRunPassedIt() {
        RunResult p2 = run("P2", "classic", Level.L3, lr(Level.L0, Status.PASS), lr(Level.L1, Status.PASS), lr(Level.L2, Status.PASS),
                lr(Level.L3, Status.PASS), lr(Level.L4, Status.SKIPPED));
        RunResult p3 = run("P3", "block", Level.L3, lr(Level.L0, Status.PASS), lr(Level.L1, Status.PASS), lr(Level.L2, Status.PASS),
                lr(Level.L3, Status.PASS), lr(Level.L4, Status.SKIPPED));
        Verdict both = Verdicts.of(release("NONE", "OK"), List.of(p2, p3));
        assertEquals("ok", both.code());
        assertEquals("Testy L0–L3 bez błędów", both.label());
        assertTrue(both.detail().contains("bramki płatności"));
        // one core run without L3 (e.g. no sandbox data there): only L0–L2 is claimed
        RunResult p3noL3 = run("P3", "classic", Level.L2, lr(Level.L0, Status.PASS), lr(Level.L1, Status.PASS), lr(Level.L2, Status.PASS),
                lr(Level.L3, Status.SKIPPED));
        Verdict partial = Verdicts.of(release("NONE", "OK"), List.of(p2, p3noL3));
        assertEquals("Testy L0–L2 bez błędów", partial.label());
        assertEquals("Instalacja, koszyk i zamówienie testowe działają w naszych konfiguracjach. To nie jest gwarancja dla Twojego sklepu.",
                partial.detail(), "the L0–L2 wording is unchanged");
    }

    @Test
    void notApplicableIsASkipThatIsNeverAPass() {
        var na = new pl.czymogeaktualizowac.core.Model.LevelResult(Level.L3, Status.SKIPPED, "nie dotyczy wtyczek z kategorii shipping", 0);
        var missing = new pl.czymogeaktualizowac.core.Model.LevelResult(Level.L3, Status.SKIPPED, "brak danych sandboxowych", 0);
        assertTrue(na.notApplicable());
        assertTrue(!missing.notApplicable());
        assertTrue(!new pl.czymogeaktualizowac.core.Model.LevelResult(Level.L3, Status.PASS, "nie dotyczy", 0).notApplicable());
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
    void labErrorOnCoreProfileIsNeverBlamedOnThePlugin() {
        RunResult err = run("P3", "classic", null, lr(Level.L0, Status.ERROR), lr(Level.L1, Status.ERROR), lr(Level.L2, Status.ERROR));
        Verdict v = Verdicts.of(release("NONE", "OK"), List.of(err));
        assertEquals("none", v.code());
        assertTrue(v.label().contains("błąd środowiska"));
        assertEquals("P3 klasyczny: błąd środowiska", Verdicts.labSummary(List.of(err)));
        // a lab error elsewhere does not spoil a real pass, and does not mask a real failure
        RunResult ok = run("P2", "classic", Level.L2, lr(Level.L0, Status.PASS), lr(Level.L1, Status.PASS), lr(Level.L2, Status.PASS));
        assertEquals("ok", Verdicts.of(release("NONE", "OK"), List.of(ok, err)).code());
        RunResult fail = run("P2", "block", Level.L1, lr(Level.L0, Status.PASS), lr(Level.L1, Status.PASS), lr(Level.L2, Status.FAIL));
        assertEquals("bad", Verdicts.of(release("NONE", "OK"), List.of(fail, err)).code());
    }

    @Test
    void failedL3CountsOnlyWhenItActuallyFailed() {
        RunResult r = run("P3", "classic", Level.L2, lr(Level.L0, Status.PASS), lr(Level.L1, Status.PASS), lr(Level.L2, Status.PASS),
                lr(Level.L3, Status.FAIL));
        assertEquals("bad", Verdicts.of(release("NONE", "OK"), List.of(r)).code());
    }

    private static RunResult notApplicableRun(String profile) {
        String why = LevelResult.NOT_APPLICABLE + ": wtyczka wymaga PHP 8.2 lub nowszego (ten profil: PHP 8.1); autor nie wspiera tej konfiguracji";
        return run(profile, "classic", null, new LevelResult(Level.L0, Status.SKIPPED, why, 0), new LevelResult(Level.L1, Status.SKIPPED, why, 0),
                new LevelResult(Level.L2, Status.SKIPPED, why, 0), new LevelResult(Level.L3, Status.SKIPPED, why, 0),
                new LevelResult(Level.L4, Status.SKIPPED, why, 0));
    }

    @Test
    void aProfileBelowThePluginsMinimumIsNotApplicableNeverAnL0Failure() {
        RunResult p1 = notApplicableRun("P1");
        RunResult p3 = run("P3", "classic", Level.L2, lr(Level.L0, Status.PASS), lr(Level.L1, Status.PASS), lr(Level.L2, Status.PASS),
                lr(Level.L3, Status.SKIPPED), lr(Level.L4, Status.SKIPPED));
        assertTrue(Verdicts.notApplicable(p1));
        assertTrue(!Verdicts.notApplicable(p3));
        assertEquals("nie dotyczy", Verdicts.reached(p1));
        assertEquals("P1 klasyczny: nie dotyczy · P3 klasyczny: L2", Verdicts.labSummary(List.of(p1, p3)));
        assertEquals("ok", Verdicts.of(release("NONE", "OK"), List.of(p1, p3)).code());
        Verdict only = Verdicts.of(release("NONE", "OK"), List.of(p1));
        assertEquals("none", only.code());
        assertTrue(only.label().startsWith("Brak testów"));
        assertTrue(only.detail().contains("PHP 8.2"), only.detail());
    }

    @Test
    void checksumResultIsWordedInPolishFromTheFileLists() {
        ChecksumResult bad = new ChecksumResult("MISMATCH", 40, List.of("includes/Invoice.php", "readme.txt"), List.of(),
                "1 code file(s) differ from the WordPress.org checksums, 0 missing: includes/Invoice.php");
        assertEquals("niezgodne: pliki kodu inne niż w sumach kontrolnych WordPress.org: 1, brakujące: 0 (includes/Invoice.php)",
                Verdicts.checksumText(bad));
        ChecksumResult ok = new ChecksumResult("OK", 169, List.of("readme.txt"), List.of(), "all 169 listed files checked: code files match");
        assertEquals("zgodne: sprawdzono 169 plików z listy WordPress.org, pliki kodu się zgadzają (inne pliki, np. readme lub tłumaczenia, różnią się: 1)",
                Verdicts.checksumText(ok));
        Verdict v = Verdicts.of(new ReleaseRecord("1.0.0", Instant.EPOCH, Instant.EPOCH, false, null, bad, "sha", 1,
                new ForumSignal(0, 0, "NONE", true, List.of())), List.of());
        assertTrue(!v.detail().contains("code file(s)"), v.detail());
        assertEquals("plik", Verdicts.files(1));
        assertEquals("pliki", Verdicts.files(22));
        assertEquals("plików", Verdicts.files(12));
        assertEquals("plików", Verdicts.files(169));
    }
}
