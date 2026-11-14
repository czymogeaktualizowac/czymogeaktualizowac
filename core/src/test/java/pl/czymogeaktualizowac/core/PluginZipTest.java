package pl.czymogeaktualizowac.core;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import pl.czymogeaktualizowac.core.Model.ChecksumResult;
import pl.czymogeaktualizowac.core.Model.PluginHeaders;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginZipTest {

    private static final String MAIN = """
            <?php
            /**
             * @wordpress-plugin
             * Plugin Name:          Furgonetka.pl
             * Version:              1.9.10
             * License:              GPL-2.0+
             * Requires Plugins:     woocommerce, flexible-invoices
             * Requires PHP:         7.1
             * WC requires at least: 4.0
             * WC tested up to:
             */
            """;

    private static final String README = """
            === Furgonetka ===
            Requires at least: 6.0
            Tested up to: 7.1.4
            Stable tag: 1.9.10

            == Description ==
            Text.

            == Changelog ==

            = 1.9.10 =
            * Fix: locker picker in block checkout.

            = 1.9.9 =
            * Older entry.

            == Upgrade Notice ==
            Nothing.
            """;

    private static byte[] zip(Map<String, String> files) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            for (var e : files.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    @Test
    void parsesHeadersAndReadme() throws IOException {
        byte[] zip = zip(Map.of("furgonetka/furgonetka.php", MAIN, "furgonetka/readme.txt", README, "furgonetka/inc/a.php", "<?php"));
        PluginHeaders h = PluginZip.headers(zip, "furgonetka");
        assertEquals("Furgonetka.pl", h.pluginName());
        assertEquals("1.9.10", h.version());
        assertEquals("7.1", h.requiresPhp());
        assertEquals("4.0", h.wcRequires());
        assertNull(h.wcTested(), "an empty 'WC tested up to' header must be null, not blank");
        assertEquals("7.1.4", h.testedWp());
        assertEquals("1.9.10", h.stableTag());
        assertEquals(java.util.List.of("woocommerce", "flexible-invoices"), h.requiresPlugins());
        assertTrue(h.changelogHead().contains("locker picker"));
        assertTrue(!h.changelogHead().contains("Older entry"), "only the newest changelog block is kept");
    }

    @Test
    void verifiesChecksums() throws IOException {
        byte[] zip = zip(Map.of("p/p.php", MAIN.replace("Furgonetka.pl", "P"), "p/a.txt", "hello"));
        String shaA = PluginZip.sha256("hello".getBytes(StandardCharsets.UTF_8));
        JsonNode good = Json.tree("{\"files\":{\"a.txt\":{\"md5\":\"x\",\"sha256\":\"" + shaA + "\"}}}");
        assertEquals("OK", PluginZip.verify(zip, "p", good).status());

        // a non-code file (readme, translations) that differs is noted, not a failure (seen on real plugins)
        JsonNode badDoc = Json.tree("{\"files\":{\"a.txt\":{\"sha256\":\"deadbeef\"}}}");
        ChecksumResult doc = PluginZip.verify(zip, "p", badDoc);
        assertEquals("OK", doc.status());
        assertEquals(java.util.List.of("a.txt"), doc.mismatched());
        assertTrue(doc.detail().contains("non-code"));

        // a code file that differs is a MISMATCH
        byte[] codeZip = zip(Map.of("p/x.php", "<?php echo 1;"));
        ChecksumResult bad = PluginZip.verify(codeZip, "p", Json.tree("{\"files\":{\"x.php\":{\"sha256\":\"deadbeef\"}}}"));
        assertEquals("MISMATCH", bad.status());
        assertEquals(java.util.List.of("x.php"), bad.mismatched());

        JsonNode missing = Json.tree("{\"files\":{\"gone.php\":{\"sha256\":\"" + shaA + "\"}}}");
        ChecksumResult miss = PluginZip.verify(zip, "p", missing);
        assertEquals("MISMATCH", miss.status());
        assertEquals(java.util.List.of("gone.php"), miss.missing());

        assertEquals("UNAVAILABLE", PluginZip.verify(zip, "p", null).status());
    }

    @Test
    void lineEndingOnlyDifferenceIsNotTampering() throws IOException {
        // real case seen on WordPress.org (imoje 4.16.1): the zip has CRLF, the published checksum was made from LF
        byte[] zip = zip(Map.of("p/a.php", "<?php\r\necho 1;\r\n"));
        String shaLf = PluginZip.sha256("<?php\necho 1;\n".getBytes(StandardCharsets.UTF_8));
        ChecksumResult r = PluginZip.verify(zip, "p", Json.tree("{\"files\":{\"a.php\":{\"sha256\":\"" + shaLf + "\"}}}"));
        assertEquals("OK", r.status());
        assertTrue(r.detail().contains("line endings"));
        // a real content change is still caught
        String shaOther = PluginZip.sha256("<?php\necho 2;\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("MISMATCH", PluginZip.verify(zip, "p", Json.tree("{\"files\":{\"a.php\":{\"sha256\":\"" + shaOther + "\"}}}")).status());
        assertEquals("OK", PluginZip.verify(zip(Map.of("p/a.php", "<?php\r\necho 1;\r\n")), "p", Json.tree("{\"files\":{\"a.php\":{\"sha256\":\"" + shaLf + "\"}}}")).status());
    }

    @Test
    void requiresPhpFallsBackToReadme() throws IOException {
        // real case seen on WordPress.org (InPost 2.0.0): no 'Requires PHP' in the main file, only in readme.txt
        String main = "<?php\n/*\n\tPlugin Name: InPost PL\n\tVersion: 2.0.0\n\tTested up to: 7.1\n\tLicense: GPLv3\n*/\n";
        String readme = "=== InPost ===\nRequires at least: 6.0\nRequires PHP: 7.4\nStable tag: 2.0.0\n\n== Changelog ==\n\n= 2.0.0 =\n* Major.\n";
        PluginHeaders h = PluginZip.headers(zip(Map.of("inpost/inpost.php", main, "inpost/readme.txt", readme)), "inpost");
        assertEquals("7.4", h.requiresPhp());
        assertEquals("6.0", h.requiresWp());
        assertEquals("7.1", h.testedWp(), "falls back to the main-file 'Tested up to' when the readme has none");
    }

    @Test
    void sha256ArrayFormIsAccepted() throws IOException {
        byte[] zip = zip(Map.of("p/a.txt", "hello"));
        String shaA = PluginZip.sha256("hello".getBytes(StandardCharsets.UTF_8));
        JsonNode arr = Json.tree("{\"files\":{\"a.txt\":{\"sha256\":[\"" + shaA + "\"]}}}");
        assertEquals("OK", PluginZip.verify(zip, "p", arr).status());
    }
}
