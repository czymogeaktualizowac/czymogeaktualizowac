package pl.czymogeaktualizowac.lab;

import pl.czymogeaktualizowac.core.Http;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One disposable store: WordPress (chosen PHP) + MariaDB in docker compose, with WP-CLI copied in.
 * Closing it removes containers and volumes. All commands go through the docker CLI; nothing here touches the host
 * except a temp directory and one localhost port.
 */
public final class LabEnv implements AutoCloseable {

    /** WP-CLI is pinned and verified against the sha512 published next to the release. */
    static final String WP_CLI_VERSION = "2.12.0";

    /** Raised for failures of the lab itself (docker, network, images), as opposed to failures of the plugin under test. */
    public static class InfraException extends IOException {
        public InfraException(String message) {
            super(message);
        }
    }

    private final Path composeFile;
    private final Path tmpDir;
    private final Map<String, String> env;
    private final String project;
    private final int port;
    private boolean started;

    /**
     * @param wordpressImage pinned WordPress image ("name:tag@sha256:…") for the PHP version of the run
     * @param databaseImage  pinned database image
     */
    public LabEnv(String wordpressImage, String databaseImage) throws IOException {
        if (!pinned(wordpressImage) || !pinned(databaseImage)) {
            throw new InfraException("the lab only runs images pinned to a digest, got: " + wordpressImage + ", " + databaseImage);
        }
        this.project = "czm-" + UUID.randomUUID().toString().substring(0, 8);
        this.port = freePort();
        this.tmpDir = Files.createTempDirectory(project);
        this.composeFile = tmpDir.resolve("docker-compose.yml");
        try (InputStream in = LabEnv.class.getResourceAsStream("/lab/docker-compose.yml")) {
            Files.copy(in, composeFile);
        }
        this.env = Map.of("LAB_PROJECT", project, "WP_IMAGE", wordpressImage, "DB_IMAGE", databaseImage, "PORT", String.valueOf(port));
    }

    /** "name:tag@sha256:<64 hex>" (a tag alone can move to a different image at any time). */
    static boolean pinned(String image) {
        return image != null && image.matches("[a-z0-9][a-z0-9._/-]*(:[\\w.-]+)?@sha256:[0-9a-f]{64}");
    }

    public String baseUrl() {
        return "http://localhost:" + port;
    }

    public void start(Path wpCliPhar) throws IOException {
        Docker.Result r = Docker.run(List.of("docker", "compose", "-f", composeFile.toString(), "up", "-d", "--wait", "--wait-timeout", "240"),
                env, tmpDir, Duration.ofMinutes(10));
        if (!r.ok()) {
            started = true; // still try to clean up
            throw new InfraException("docker compose up failed: " + tail(r.out(), 12));
        }
        started = true;
        // WP-CLI + helper files
        mustOk(compose("cp", wpCliPhar.toString(), "wp:/usr/local/bin/wp"), "copy wp-cli");
        mustOk(compose("exec", "-T", "-u", "root", "wp", "chmod", "755", "/usr/local/bin/wp"), "chmod wp-cli");
        copyResource("/lab/setup-store.php", "/tmp/setup-store.php");
        mustOk(compose("exec", "-T", "-u", "root", "wp", "mkdir", "-p", "/var/www/html/wp-content/mu-plugins"), "mkdir mu-plugins");
        copyResource("/lab/mu-lab.php", "/var/www/html/wp-content/mu-plugins/mu-lab.php");
        mustOk(compose("exec", "-T", "-u", "root", "wp", "chown", "-R", "www-data:www-data", "/var/www/html/wp-content", "/tmp/setup-store.php"), "chown");
    }

    /** Runs WP-CLI as www-data inside the store. */
    public Docker.Result wp(String... args) throws IOException {
        List<String> cmd = new ArrayList<>(List.of("docker", "compose", "-f", composeFile.toString(), "exec", "-T", "-u", "www-data",
                "-w", "/var/www/html", "wp", "php", "-d", "memory_limit=1G", "/usr/local/bin/wp"));
        cmd.addAll(List.of(args));
        return Docker.run(cmd, env, tmpDir, Duration.ofMinutes(8));
    }

    public Docker.Result sh(String script) throws IOException {
        return compose("exec", "-T", "-u", "root", "wp", "sh", "-c", script);
    }

    public void copyFile(Path host, String containerPath) throws IOException {
        mustOk(compose("cp", host.toString(), "wp:" + containerPath), "copy " + host.getFileName());
        mustOk(compose("exec", "-T", "-u", "root", "wp", "chown", "www-data:www-data", containerPath), "chown " + containerPath);
    }

    public String debugLog() throws IOException {
        return sh("cat /var/www/html/wp-content/debug.log 2>/dev/null || true").out();
    }

    public String containerLogs() throws IOException {
        return compose("logs", "--no-color", "--tail", "60", "wp").out();
    }

    private void copyResource(String resource, String target) throws IOException {
        Path f = tmpDir.resolve(Path.of(target).getFileName().toString());
        try (InputStream in = LabEnv.class.getResourceAsStream(resource)) {
            Files.copy(in, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        mustOk(compose("cp", f.toString(), "wp:" + target), "copy " + resource);
    }

    private Docker.Result compose(String... args) throws IOException {
        List<String> cmd = new ArrayList<>(List.of("docker", "compose", "-f", composeFile.toString()));
        cmd.addAll(List.of(args));
        return Docker.run(cmd, env, tmpDir, Duration.ofMinutes(5));
    }

    private static void mustOk(Docker.Result r, String what) throws IOException {
        if (!r.ok()) {
            throw new InfraException(what + " failed: " + tail(r.out(), 8));
        }
    }

    @Override
    public void close() {
        if (started) {
            try {
                Docker.run(List.of("docker", "compose", "-f", composeFile.toString(), "down", "-v", "--remove-orphans", "-t", "2"),
                        env, tmpDir, Duration.ofMinutes(3));
            } catch (IOException ignored) {
                // best effort
            }
        }
        try (var s = Files.walk(tmpDir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // temp dir cleanup is best effort
        }
    }

    // ------------------------------------------------------------------ static helpers

    /** Downloads WP-CLI once, verifies the published sha512, caches it. */
    public static Path wpCli(Path cacheDir, Http http) throws IOException {
        Path phar = cacheDir.resolve("wp-cli-" + WP_CLI_VERSION + ".phar");
        if (Files.exists(phar)) {
            return phar;
        }
        String base = "https://github.com/wp-cli/wp-cli/releases/download/v" + WP_CLI_VERSION + "/wp-cli-" + WP_CLI_VERSION + ".phar";
        byte[] data = http.getBytes(base);
        String expected = http.getString(base + ".sha512").trim().split("\\s+")[0].toLowerCase();
        String actual;
        try {
            actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        if (!actual.equals(expected)) {
            throw new InfraException("WP-CLI sha512 mismatch: expected " + expected + " got " + actual);
        }
        Files.createDirectories(cacheDir);
        Files.write(phar, data);
        return phar;
    }

    static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    static String tail(String s, int lines) {
        String[] l = s.strip().split("\\R");
        int from = Math.max(0, l.length - lines);
        return String.join(" | ", java.util.Arrays.copyOfRange(l, from, l.length));
    }
}
