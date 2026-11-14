package pl.czymogeaktualizowac.lab;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Known-behaviour plugins used to prove that the lab itself tells good from bad (selftest):
 * ok = harmless, fatal = PHP fatal error while loading, checkout = breaks only the checkout page.
 */
public final class Fixtures {

    public static final String OK = "czm-fixture-ok";
    public static final String FATAL = "czm-fixture-fatal";
    public static final String CHECKOUT = "czm-fixture-checkout";

    private Fixtures() {
    }

    public static Path zip(String slug, Path dir) throws IOException {
        String body = switch (slug) {
            case OK -> """
                    <?php
                    /**
                     * Plugin Name: CZM Fixture OK
                     * Version: 1.0.0
                     * Requires PHP: 7.4
                     * License: GPL-2.0+
                     */
                    if ( ! defined( 'ABSPATH' ) ) { exit; }
                    """;
            case FATAL -> """
                    <?php
                    /**
                     * Plugin Name: CZM Fixture Fatal
                     * Version: 1.0.0
                     * Requires PHP: 7.4
                     * License: GPL-2.0+
                     */
                    if ( ! defined( 'ABSPATH' ) ) { exit; }
                    czm_this_function_does_not_exist();
                    """;
            case CHECKOUT -> """
                    <?php
                    /**
                     * Plugin Name: CZM Fixture Checkout Breaker
                     * Version: 1.0.0
                     * Requires PHP: 7.4
                     * License: GPL-2.0+
                     */
                    if ( ! defined( 'ABSPATH' ) ) { exit; }
                    add_action( 'wp', function () {
                        if ( function_exists( 'is_checkout' ) && is_checkout() ) {
                            czm_this_function_does_not_exist();
                        }
                    } );
                    """;
            default -> throw new IllegalArgumentException(slug);
        };
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            z.putNextEntry(new ZipEntry(slug + "/" + slug + ".php"));
            z.write(body.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        Files.createDirectories(dir);
        Path out = dir.resolve(slug + ".1.0.0.zip");
        Files.write(out, bos.toByteArray());
        return out;
    }
}
