package pl.czymogeaktualizowac.lab;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugLogTest {

    // real shape of the entry that was wrongly blamed on Furgonetka (WordPress 7.2-beta4 core, no plugin involved)
    private static final String CORE_FATAL = """
            [06-Nov-2026 22:10:20 UTC] PHP Fatal error:  Uncaught Error: Class "WP_AI_Client_Builder" not found in /var/www/html/wp-includes/ai-client/class-wp-ai-client-prompt-builder.php:107
            Stack trace:
            #0 /var/www/html/wp-includes/foo.php(10): bar()
            #1 {main}
              thrown in /var/www/html/wp-includes/ai-client/class-wp-ai-client-prompt-builder.php on line 107
            """;

    @Test
    void parsesMultilineEntries() {
        String log = CORE_FATAL + "[06-Nov-2026 22:10:21 UTC] PHP Warning:  x in /var/www/html/wp-content/plugins/p/a.php on line 3\n";
        assertEquals(2, DebugLog.entries(log).size());
        assertTrue(DebugLog.isFatal(DebugLog.entries(log).get(0)));
    }

    @Test
    void fatalAlreadyPresentWithoutThePluginIsIgnored() {
        Set<String> baseline = DebugLog.fatalKeys(CORE_FATAL.replace("22:10:20", "22:09:00")); // same error, other timestamp
        DebugLog.Analysis a = DebugLog.analyze(CORE_FATAL, "furgonetka", baseline);
        assertFalse(a.hasFatal());
        assertEquals(1, a.ignoredBaselineFatals());
    }

    @Test
    void fatalWithPluginPathInItsTraceIsThePlugins() {
        String log = "[06-Nov-2026 22:10:20 UTC] PHP Fatal error:  Uncaught Error: Call to undefined function x() in /var/www/html/wp-includes/a.php:1\n"
                + "Stack trace:\n#0 /var/www/html/wp-content/plugins/furgonetka/inc/b.php(5): y()\n#1 {main}\n";
        DebugLog.Analysis a = DebugLog.analyze(log, "furgonetka", Set.of());
        assertEquals(1, a.pluginFatals().size());
        assertTrue(a.unattributedNewFatals().isEmpty());
    }

    @Test
    void newFatalWithoutPluginPathIsCountedButLabelledUnattributed() {
        DebugLog.Analysis a = DebugLog.analyze(CORE_FATAL, "furgonetka", Set.of());
        assertTrue(a.pluginFatals().isEmpty());
        assertEquals(1, a.unattributedNewFatals().size());
        assertTrue(a.hasFatal());
    }

    @Test
    void countsOnlyThePluginsOwnNotices() {
        String log = "[06-Nov-2026 22:10:20 UTC] PHP Deprecated:  x in /var/www/html/wp-content/plugins/p/a.php on line 3\n"
                + "[06-Nov-2026 22:10:21 UTC] PHP Deprecated:  y in /var/www/html/wp-content/plugins/other/a.php on line 3\n";
        assertEquals(1, DebugLog.analyze(log, "p", Set.of()).pluginNotices());
    }
}
