package pl.czymogeaktualizowac.publisher;

import pl.czymogeaktualizowac.core.Model.Level;
import pl.czymogeaktualizowac.core.Model.LevelResult;
import pl.czymogeaktualizowac.core.Model.ReleaseRecord;
import pl.czymogeaktualizowac.core.Model.RunResult;
import pl.czymogeaktualizowac.core.Model.Status;
import pl.czymogeaktualizowac.publisher.View.Verdict;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Turns lab runs + checksum + community signal into one honest verdict per release.
 * We never say "safe": the wording always names what was and was not covered.
 */
public final class Verdicts {

    private Verdicts() {
    }

    /** Neutral on purpose: a held result is not published yet, so nothing may claim a pass or hint a failure. */
    public static final Verdict HELD = new Verdict("none", "Wyniki testów w weryfikacji",
            "Wyniki tej wersji sprawdzamy przed publikacją. Do tego czasu nie traktuj jej jako przetestowanej.");

    /** Verdict of a release whose results are not held by the embargo. */
    public static Verdict of(ReleaseRecord rel, List<RunResult> runs) {
        return of(rel, runs, false);
    }

    /** @param held the embargo holds this release back (see {@link Published}); {@code runs} are then ignored */
    public static Verdict of(ReleaseRecord rel, List<RunResult> runs, boolean held) {
        if (held) {
            return HELD;
        }
        // A code-file checksum mismatch is serious but, in real data, usually a packaging artefact (SVN keywords,
        // line endings), so we flag it as a warning and say so; we never accuse an author of tampering.
        boolean checksumWarning = rel.checksum() != null && "MISMATCH".equals(rel.checksum().status());
        if (checksumWarning && runs.isEmpty()) {
            return new Verdict("warn", "Uwaga: pliki kodu różnią się od sum kontrolnych WordPress.org",
                    rel.checksum().detail() + ". Zwykle to artefakt pakowania, ale sprawdzamy to; do czasu wyjaśnienia zachowaj ostrożność.");
        }
        List<String> failsCore = new ArrayList<>();
        List<String> failsEdge = new ArrayList<>();
        List<String> labErrors = new ArrayList<>();
        boolean anyCorePass = false;
        // L3/L4 are claimed in the verdict only when EVERY core run that passed L2 also passed them
        int coreL2Runs = 0;
        int coreL3Pass = 0;
        int coreL4Pass = 0;
        for (RunResult r : runs) {
            boolean coreProfile = r.profileId().equals("P2") || r.profileId().equals("P3");
            String where = r.profileId() + (r.checkout().equals("none") ? "" : ", " + r.checkout());
            if (hasLabError(r)) {
                // ERROR = the lab itself failed (docker, network, control pass): it says nothing about the plugin,
                // so it must never turn into a "bad"/"warn" verdict or an alert
                labErrors.add(where);
                continue;
            }
            for (LevelResult l : r.levels()) {
                if (l.level().ordinal() > Level.L2.ordinal() && l.status() != Status.FAIL) {
                    continue; // L3/L4 only count when they actually failed; SKIPPED is "not tested", never a pass
                }
                if (l.status() == Status.FAIL) {
                    (coreProfile ? failsCore : failsEdge).add(l.level() + " (" + where + ")");
                }
            }
            if (coreProfile && Level.L2.compareTo(r.highestPassed() == null ? Level.L0 : r.highestPassed()) <= 0
                    && r.highestPassed() != null) {
                anyCorePass = true;
                coreL2Runs++;
                coreL3Pass += status(r, Level.L3) == Status.PASS ? 1 : 0;
                coreL4Pass += status(r, Level.L4) == Status.PASS ? 1 : 0;
            }
        }
        boolean l3 = coreL2Runs > 0 && coreL3Pass == coreL2Runs;
        boolean l4 = coreL2Runs > 0 && coreL4Pass == coreL2Runs;
        String levels = l3 && l4 ? "L0–L4" : l3 ? "L0–L3" : l4 ? "L0–L2 i L4" : "L0–L2";
        boolean elevated = rel.forum() != null && "ELEVATED".equals(rel.forum().level());
        if (!failsCore.isEmpty()) {
            return new Verdict("bad", "Wstrzymaj aktualizację: testy wykryły błąd",
                    "Błąd w: " + String.join(", ", failsCore) + ".");
        }
        if (!failsEdge.isEmpty()) {
            return new Verdict("warn", "Uwaga: błąd na profilu brzegowym lub starszym PHP",
                    "Błąd w: " + String.join(", ", failsEdge) + ". Profile P2 i P3 bez błędów.");
        }
        if (anyCorePass) {
            if (checksumWarning) {
                return new Verdict("warn", "Testy " + levels + " bez błędów, ale pliki kodu różnią się od sum kontrolnych",
                        rel.checksum().detail() + ".");
            }
            if (elevated) {
                return new Verdict("warn", "Testy przeszły, ale użytkownicy zgłaszają problemy",
                        "Podwyższona liczba zgłoszeń na forum w 7 dni po wydaniu. Sprawdź tematy poniżej.");
            }
            List<String> works = new ArrayList<>(List.of("Instalacja", "koszyk", "zamówienie testowe"));
            if (l3) {
                works.add("przekierowanie do bramki płatności (sandbox)");
            }
            if (l4) {
                works.add("widget wyboru punktu odbioru");
            }
            String what = String.join(", ", works.subList(0, works.size() - 1)) + " i " + works.get(works.size() - 1);
            return new Verdict("ok", "Testy " + levels + " bez błędów",
                    what + " działają w naszych konfiguracjach. To nie jest gwarancja dla Twojego sklepu.");
        }
        if (!labErrors.isEmpty() && !elevated) {
            return new Verdict("none", "Wynik nieznany: błąd środowiska testowego",
                    "Test nie został ukończony z powodu problemu laboratorium, nie wtyczki (" + String.join("; ", labErrors)
                            + "). Powtórzymy go; do tego czasu nie traktuj tej wersji jako przetestowanej.");
        }
        if (elevated) {
            return new Verdict("warn", "Brak testów laboratoryjnych, ale są zgłoszenia na forum",
                    "Podwyższona liczba zgłoszeń w 7 dni po wydaniu.");
        }
        if (rel.forum() != null && "LOW".equals(rel.forum().level())) {
            return new Verdict("none", "Brak testów laboratoryjnych; pojedyncze zgłoszenia na forum", null);
        }
        return new Verdict("none", "Brak testów laboratoryjnych", null);
    }

    /** One-line summary of the levels reached, e.g. "P2 klasyczny: L2 · P2 blokowy: L2 · P3 klasyczny: L2". */
    public static String labSummary(List<RunResult> runs) {
        return labSummary(runs, false);
    }

    public static String labSummary(List<RunResult> runs, boolean held) {
        if (held) {
            return "w weryfikacji";
        }
        if (runs.isEmpty()) {
            return "brak";
        }
        return runs.stream()
                .filter(r -> r.mode().equals("solo"))
                .map(r -> r.profileId() + (r.checkout().equals("none") ? "" : " " + checkoutPl(r.checkout()))
                        + ": " + reached(r))
                .collect(Collectors.joining(" · "));
    }

    private static Status status(RunResult r, Level level) {
        return r.levels().stream().filter(l -> l.level() == level).map(LevelResult::status).findFirst().orElse(Status.SKIPPED);
    }

    /** True when the lab itself failed during this run (any ERROR level): the run says nothing about the plugin. */
    public static boolean hasLabError(RunResult r) {
        return r.levels().stream().anyMatch(l -> l.status() == Status.ERROR);
    }

    /** Highest level reached, worded so that a lab error is never shown as the plugin's L0 failure. */
    public static String reached(RunResult r) {
        if (hasLabError(r)) {
            return "błąd środowiska";
        }
        return r.highestPassed() == null ? "błąd L0" : r.highestPassed().name();
    }

    public static String checkoutPl(String c) {
        return switch (c) {
            case "classic" -> "klasyczny";
            case "block" -> "blokowy";
            default -> c;
        };
    }

    public static String forumText(pl.czymogeaktualizowac.core.Model.ForumSignal f) {
        if (f == null) {
            return "brak danych";
        }
        return switch (f.level()) {
            case "NONE" -> "0 zgłoszeń w 7 dni" + (f.windowComplete() ? "" : " (okno trwa)");
            case "LOW" -> f.topics7d() + (f.topics7d() == 1 ? " zgłoszenie" : " zgłoszenia") + " w 7 dni" + (f.windowComplete() ? "" : " (okno trwa)");
            case "ELEVATED" -> f.topics7d() + " zgłoszeń w 7 dni (podwyższone)";
            default -> "brak danych";
        };
    }

    public static String forumCss(pl.czymogeaktualizowac.core.Model.ForumSignal f) {
        if (f == null) {
            return "none";
        }
        return switch (f.level()) {
            case "NONE" -> "ok";
            case "LOW" -> "none";
            case "ELEVATED" -> "warn";
            default -> "none";
        };
    }
}
