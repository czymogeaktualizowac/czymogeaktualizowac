package pl.czymogeaktualizowac.core;

import org.junit.jupiter.api.Test;
import pl.czymogeaktualizowac.core.Model.ForumSignal;
import pl.czymogeaktualizowac.core.Model.ForumTopic;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalsTest {

    private static final Instant RELEASE = Instant.parse("2026-03-10T10:00:00Z");
    private static final Instant NOW = Instant.parse("2026-04-30T10:00:00Z");

    private static ForumTopic topic(String title, Duration afterRelease) {
        return new ForumTopic(title, "https://example.invalid/t", RELEASE.plus(afterRelease));
    }

    @Test
    void noTopicsInWindowIsNone() {
        List<ForumTopic> t = List.of(topic("old", Duration.ofDays(-40)), topic("late", Duration.ofDays(20)));
        ForumSignal s = Signals.forum(t, "1.9.0", RELEASE, NOW);
        assertEquals("NONE", s.level());
        assertTrue(s.windowComplete());
    }

    @Test
    void threeTopicsIsElevated() {
        List<ForumTopic> t = List.of(topic("a", Duration.ofDays(1)), topic("b", Duration.ofDays(2)), topic("c", Duration.ofDays(6)));
        assertEquals("ELEVATED", Signals.forum(t, "1.9.0", RELEASE, NOW).level());
    }

    @Test
    void twoTopicsOneMentioningVersionIsElevated_butVersionMatchIsExact() {
        List<ForumTopic> t = List.of(topic("Blad po aktualizacji do 1.9.0", Duration.ofDays(1)), topic("inne", Duration.ofDays(2)));
        ForumSignal s = Signals.forum(t, "1.9.0", RELEASE, NOW);
        assertEquals("ELEVATED", s.level());
        assertEquals(1, s.mentioningVersion());
        // "1.9.0" must not match inside "11.9.0" or "1.9.01"
        List<ForumTopic> t2 = List.of(topic("problem 11.9.0", Duration.ofDays(1)), topic("problem 1.9.01", Duration.ofDays(2)));
        assertEquals(0, Signals.forum(t2, "1.9.0", RELEASE, NOW).mentioningVersion());
    }

    @Test
    void oneTopicIsLow() {
        assertEquals("LOW", Signals.forum(List.of(topic("x", Duration.ofDays(3))), "1.9.0", RELEASE, NOW).level());
    }

    @Test
    void windowNotCompleteForFreshRelease() {
        ForumSignal s = Signals.forum(List.of(), "1.9.0", RELEASE, RELEASE.plus(Duration.ofDays(2)));
        assertFalse(s.windowComplete());
    }

    @Test
    void unknownWhenReleaseDateUnknownOrFeedDoesNotReachBack() {
        assertEquals("UNKNOWN", Signals.forum(List.of(), "1.0", null, NOW).level());
        List<ForumTopic> full = new ArrayList<>();
        for (int i = 0; i < Signals.FEED_CAPACITY; i++) {
            full.add(topic("t" + i, Duration.ofDays(30 + i))); // feed starts 30 days after the release: cannot see its window
        }
        assertEquals("UNKNOWN", Signals.forum(full, "1.9.0", RELEASE, NOW).level());
    }
}
