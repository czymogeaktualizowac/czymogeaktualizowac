package pl.czymogeaktualizowac.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigCheckTest {

    @TempDir
    Path dir;

    private static final String PROFILES = "[{\"id\":\"P3\",\"name\":\"Current\",\"php\":\"8.3\",\"wp\":\"latest\",\"woo\":\"latest\","
            + "\"checkoutTypes\":[\"classic\",\"block\"],\"why\":\"x\"}]";

    private ConfigCheck.Report check(String plugins, String profiles, String embargo) throws IOException {
        Files.writeString(dir.resolve("plugins.json"), plugins);
        Files.writeString(dir.resolve("profiles.json"), profiles);
        if (embargo != null) {
            Files.writeString(dir.resolve("embargo.json"), embargo);
        }
        return ConfigCheck.check(dir);
    }

    private static boolean mentions(ConfigCheck.Report r, String text) {
        return r.errors().stream().anyMatch(e -> e.contains(text));
    }

    /** The repository's own config must always validate (Maven runs tests from the module directory). */
    @Test
    void theRepositoryConfigIsValid() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("config/plugins.json"))) {
            p = p.getParent();
        }
        assertTrue(p != null, "config/ not found above " + Path.of("").toAbsolutePath());
        ConfigCheck.Report r = ConfigCheck.check(p.resolve("config"));
        assertTrue(r.ok(), "config errors: " + r.errors());
    }

    @Test
    void misspeltKeysAreErrorsNotSilentNulls() throws IOException {
        ConfigCheck.Report r = check("[{\"slug\":\"pay\",\"name\":\"Pay\",\"category\":\"payment\","
                + "\"lab\":{\"gatewayID\":\"x\",\"sandboxEnvPrefix\":\"LAB_SANDBOX_PAY\"}}]", PROFILES, null);
        assertTrue(mentions(r, "unknown key 'gatewayID'"), r.errors().toString());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("L3 stays SKIPPED")));
    }

    @Test
    void symbolicVersionsCategoriesAndRegexesAreChecked() throws IOException {
        ConfigCheck.Report r = check("[{\"slug\":\"a\",\"name\":\"A\",\"category\":\"shiping\",\"lab\":{\"shippingMethodRegex\":\"(oops\"}},"
                        + "{\"slug\":\"a\",\"name\":\"A2\",\"category\":\"other\"}]",
                "[{\"id\":\"P3\",\"name\":\"C\",\"php\":\"8.3\",\"wp\":\"lastest\",\"woo\":\"latest\",\"checkoutTypes\":[\"blocks\"]}]", null);
        assertTrue(mentions(r, "category 'shiping'"));
        assertTrue(mentions(r, "invalid regex"));
        assertTrue(mentions(r, "duplicate slug"));
        assertTrue(mentions(r, "wp 'lastest'"));
        assertTrue(mentions(r, "checkout type 'blocks'"));
    }

    @Test
    void labImagesMustBeDigestPinnedAndCoverEveryProfilePhp() throws IOException {
        String sha = "@sha256:" + "b".repeat(64);
        Files.writeString(dir.resolve("lab-images.json"), "{\"database\":\"mariadb:11.4\",\"wordpress\":{\"8.2\":\"wordpress:php8.2-apache" + sha + "\"}}");
        ConfigCheck.Report r = check("[{\"slug\":\"a\",\"name\":\"A\",\"category\":\"other\"}]", PROFILES, null);
        assertTrue(mentions(r, "database must be pinned"), r.errors().toString());
        assertTrue(mentions(r, "no pinned WordPress image for PHP 8.3"), r.errors().toString());

        Files.writeString(dir.resolve("lab-images.json"), "{\"database\":\"mariadb:11.4" + sha + "\",\"wordpress\":{\"8.3\":\"wordpress:php8.3-apache" + sha + "\"}}");
        assertTrue(check("[{\"slug\":\"a\",\"name\":\"A\",\"category\":\"other\"}]", PROFILES, null).ok());
    }

    @Test
    void embargoDecisionsMustNameATrackedReleaseAndAKnownDecision() throws IOException {
        ConfigCheck.Report r = check("[{\"slug\":\"a\",\"name\":\"A\",\"category\":\"other\"}]", PROFILES,
                "{\"holdDays\":7,\"decisions\":[{\"slug\":\"b\",\"version\":\"1.0\",\"decision\":\"publish\"},"
                        + "{\"slug\":\"a\",\"version\":\"1.0\",\"decision\":\"relase\"}]}");
        assertTrue(mentions(r, "slug is not a tracked plugin"));
        assertTrue(mentions(r, "decision 'relase'"));
        assertFalse(r.ok());
    }
}
