package pl.czymogeaktualizowac.lab;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads WordPress's debug.log and attributes errors, so that a bug in WordPress core, WooCommerce or the lab is never
 * blamed on the plugin. An entry starts with "[dd-Mon-yyyy hh:mm:ss UTC]" and may continue with stack-trace lines.
 * Rules, in order: (1) already present in the control pass (no plugin installed) -> ignored;
 * (2) mentions /wp-content/plugins/<slug>/ anywhere in the entry or its trace -> the plugin's;
 * (3) otherwise a NEW fatal that appeared only with the plugin -> counted, but labelled as not clearly the plugin's.
 */
final class DebugLog {

    private static final Pattern ENTRY_START = Pattern.compile("(?m)^(?=\\[\\d{2}-[A-Za-z]{3}-\\d{4} \\d{2}:\\d{2}:\\d{2} [A-Za-z]+\\])");
    private static final Pattern TIMESTAMP = Pattern.compile("^\\[[^\\]]+\\]\\s*");

    record Analysis(List<String> pluginFatals, List<String> unattributedNewFatals, long ignoredBaselineFatals, long pluginNotices) {
        boolean hasFatal() {
            return !pluginFatals.isEmpty() || !unattributedNewFatals.isEmpty();
        }
    }

    private DebugLog() {
    }

    static List<String> entries(String log) {
        List<String> out = new ArrayList<>();
        if (log == null || log.isBlank()) {
            return out;
        }
        for (String e : ENTRY_START.split(log)) {
            if (!e.isBlank()) {
                out.add(e.strip());
            }
        }
        return out;
    }

    static boolean isFatal(String entry) {
        return entry.contains("PHP Fatal error") || entry.contains("PHP Parse error");
    }

    /** Normalised form of a fatal entry (timestamp removed) for comparison with the baseline. */
    static String key(String entry) {
        return TIMESTAMP.matcher(entry).replaceFirst("");
    }

    static Set<String> fatalKeys(String log) {
        Set<String> keys = new HashSet<>();
        for (String e : entries(log)) {
            if (isFatal(e)) {
                keys.add(key(e));
            }
        }
        return keys;
    }

    static Analysis analyze(String log, String slug, Set<String> baselineFatalKeys) {
        String marker = "/wp-content/plugins/" + slug + "/";
        List<String> mine = new ArrayList<>();
        List<String> other = new ArrayList<>();
        long ignored = 0;
        long notices = 0;
        for (String e : entries(log)) {
            if (isFatal(e)) {
                if (baselineFatalKeys.contains(key(e))) {
                    ignored++;
                } else if (e.contains(marker)) {
                    mine.add(firstLine(e));
                } else {
                    other.add(firstLine(e));
                }
            } else if (e.contains(marker) && (e.contains("PHP Warning") || e.contains("PHP Notice") || e.contains("PHP Deprecated"))) {
                notices++;
            }
        }
        return new Analysis(mine, other, ignored, notices);
    }

    private static String firstLine(String e) {
        String l = e.split("\\R")[0];
        return l.length() > 300 ? l.substring(0, 300) + "…" : l;
    }
}
