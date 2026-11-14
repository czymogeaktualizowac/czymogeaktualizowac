package pl.czymogeaktualizowac.core;

import pl.czymogeaktualizowac.core.Model.ForumSignal;
import pl.czymogeaktualizowac.core.Model.ForumTopic;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/** Community signal: new support topics in the 7 days after a release (cheap proxy, works without the lab). */
public final class Signals {

    public static final Duration WINDOW = Duration.ofDays(7);
    /** The WordPress.org support feed holds about this many topics; a full feed may not reach back to older releases. */
    public static final int FEED_CAPACITY = 30;

    private Signals() {
    }

    /**
     * @param topics   latest topics from the plugin's support forum
     * @param version  the release version
     * @param released release time (null when unknown: then no signal is computed)
     * @param now      evaluation time
     * @return level NONE | LOW | ELEVATED, or UNKNOWN when the release date is unknown or the feed does not reach back to it
     */
    public static ForumSignal forum(List<ForumTopic> topics, String version, Instant released, Instant now) {
        if (released == null) {
            return new ForumSignal(0, 0, "UNKNOWN", false, List.of());
        }
        Instant end = released.plus(WINDOW);
        boolean complete = !now.isBefore(end);
        Instant oldest = topics.stream().map(ForumTopic::date).filter(d -> d != null).min(Instant::compareTo).orElse(null);
        boolean covered = topics.size() < FEED_CAPACITY || (oldest != null && !oldest.isAfter(released));
        if (!covered) {
            return new ForumSignal(0, 0, "UNKNOWN", complete, List.of());
        }
        Pattern mention = Pattern.compile("(?<![\\d.])" + Pattern.quote(version) + "(?![\\d])");
        List<ForumTopic> inWindow = topics.stream()
                .filter(t -> t.date() != null && !t.date().isBefore(released) && t.date().isBefore(end))
                .toList();
        int mentions = (int) inWindow.stream().filter(t -> mention.matcher(t.title()).find()).count();
        int n = inWindow.size();
        String level;
        if (n == 0) {
            level = "NONE";
        } else if (n >= 3 || (n >= 2 && mentions >= 1)) {
            level = "ELEVATED";
        } else {
            level = "LOW";
        }
        return new ForumSignal(n, mentions, level, complete, inWindow.stream().limit(5).toList());
    }
}
