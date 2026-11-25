package pl.czymogeaktualizowac.core;

import com.fasterxml.jackson.databind.JsonNode;
import pl.czymogeaktualizowac.core.Model.ChecksumResult;
import pl.czymogeaktualizowac.core.Model.PluginHeaders;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads a plugin zip from WordPress.org: header + readme parsing and checksum verification.
 * The zip is untrusted input: entry count and sizes are capped and nothing is ever extracted to disk or executed here.
 */
public final class PluginZip {

    private static final int MAX_ENTRIES = 20_000;
    private static final long MAX_ENTRY_BYTES = 64L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 400L * 1024 * 1024;

    private PluginZip() {
    }

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Parses the main plugin file header and readme.txt. Returns nulls for fields that are not present. */
    public static PluginHeaders headers(byte[] zip, String slug) throws IOException {
        String mainPhp = null;
        String readme = null;
        String mainName = null;
        int entries = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw new IOException("zip has too many entries");
                }
                String name = e.getName();
                if (e.isDirectory() || name.contains("..")) {
                    continue;
                }
                String[] parts = name.split("/");
                if (parts.length != 2) {
                    continue; // only top-level files of the plugin folder
                }
                String file = parts[1];
                String lower = file.toLowerCase();
                if (lower.endsWith(".php") && mainPhp == null) {
                    String head = readLimited(in, 16 * 1024);
                    if (head.contains("Plugin Name:")) {
                        mainPhp = head;
                        mainName = name;
                    }
                } else if (lower.equals("readme.txt")) {
                    readme = readLimited(in, 256 * 1024);
                } else if (lower.equals("readme.md") && readme == null) {
                    readme = readLimited(in, 256 * 1024);
                }
            }
        }
        if (mainPhp == null) {
            throw new IOException("main plugin file with 'Plugin Name:' header not found in zip for " + slug);
        }
        String requiresPlugins = field(mainPhp, "Requires Plugins");
        List<String> deps = new ArrayList<>();
        if (requiresPlugins != null) {
            for (String d : requiresPlugins.split(",")) {
                if (!d.isBlank()) {
                    deps.add(d.trim());
                }
            }
        }
        // WordPress reads these from the main file header, with the readme as the fallback; so do we
        String testedWp = firstNonNull(readme == null ? null : field(readme, "Tested up to"), field(mainPhp, "Tested up to"));
        String stable = readme == null ? null : field(readme, "Stable tag");
        return new PluginHeaders(
                field(mainPhp, "Plugin Name"),
                field(mainPhp, "Version"),
                field(mainPhp, "License"),
                firstNonNull(field(mainPhp, "Requires PHP"), readme == null ? null : field(readme, "Requires PHP")),
                firstNonNull(field(mainPhp, "Requires at least"), readme == null ? null : field(readme, "Requires at least")),
                field(mainPhp, "WC requires at least"),
                field(mainPhp, "WC tested up to"),
                testedWp,
                stable,
                deps,
                readme == null ? null : changelogHead(readme));
    }

    /**
     * Verifies every file listed in the WordPress.org checksum JSON for this version against the zip.
     * Only files listed by WordPress.org count: the zip is built from the same SVN tag, so any mismatch
     * means the artefact is not what WordPress.org published.
     */
    public static ChecksumResult verify(byte[] zip, String slug, JsonNode checksums) throws IOException {
        if (checksums == null || !checksums.has("files")) {
            return new ChecksumResult("UNAVAILABLE", 0, List.of(), List.of(), "no checksum file published for this version");
        }
        Map<String, String> expected = new HashMap<>();
        checksums.get("files").fields().forEachRemaining(en -> {
            JsonNode sha = en.getValue().get("sha256");
            if (sha != null) {
                expected.put(en.getKey(), sha.isArray() ? sha.get(0).asText() : sha.asText());
            }
        });
        if (expected.isEmpty()) {
            return new ChecksumResult("UNAVAILABLE", 0, List.of(), List.of(), "checksum file has no sha256 entries");
        }
        Map<String, String> actual = new HashMap<>();
        long total = 0;
        int entries = 0;
        String prefix = slug + "/";
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw new IOException("zip has too many entries");
                }
                if (e.isDirectory() || !e.getName().startsWith(prefix) || e.getName().contains("..")) {
                    continue;
                }
                String rel = e.getName().substring(prefix.length());
                if (!expected.containsKey(rel)) {
                    continue;
                }
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] buf = new byte[8192];
                long size = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    size += n;
                    total += n;
                    if (size > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES) {
                        throw new IOException("zip entry too large");
                    }
                    md.update(buf, 0, n);
                }
                actual.put(rel, HexFormat.of().formatHex(md.digest()));
            }
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        List<String> mismatched = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> en : expected.entrySet()) {
            String got = actual.get(en.getKey());
            if (got == null) {
                missing.add(en.getKey());
            } else if (!got.equalsIgnoreCase(en.getValue())) {
                mismatched.add(en.getKey());
            }
        }
        // SVN "eol-style" can make the zip differ from the tag only in line endings: that is not tampering.
        List<String> eolOnly = new ArrayList<>();
        if (!mismatched.isEmpty()) {
            List<String> stillBad = new ArrayList<>(mismatched);
            try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
                ZipEntry e;
                while ((e = in.getNextEntry()) != null) {
                    if (e.isDirectory() || !e.getName().startsWith(prefix)) {
                        continue;
                    }
                    String rel = e.getName().substring(prefix.length());
                    if (!stillBad.contains(rel)) {
                        continue;
                    }
                    byte[] data = in.readNBytes((int) Math.min(MAX_ENTRY_BYTES, Integer.MAX_VALUE));
                    String asLf = new String(data, StandardCharsets.ISO_8859_1).replace("\r\n", "\n");
                    String lf = sha256(asLf.getBytes(StandardCharsets.ISO_8859_1));
                    String crlf = sha256(asLf.replace("\n", "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    String want = expected.get(rel);
                    if (lf.equalsIgnoreCase(want) || crlf.equalsIgnoreCase(want)) {
                        stillBad.remove(rel);
                        eolOnly.add(rel);
                    }
                }
            }
            mismatched = stillBad;
        }
        // Real data: readme.txt is routinely rewritten by WordPress.org and translation files move to
        // translate.wordpress.org, so differences in non-code files are noted but are not a failure.
        List<String> codeMismatched = mismatched.stream().filter(PluginZip::isCode).toList();
        List<String> codeMissing = missing.stream().filter(PluginZip::isCode).toList();
        boolean ok = codeMismatched.isEmpty() && codeMissing.isEmpty();
        int docs = (mismatched.size() - codeMismatched.size()) + (missing.size() - codeMissing.size());
        String detail;
        if (ok) {
            detail = "all " + expected.size() + " listed files checked: code files match"
                    + (eolOnly.isEmpty() ? "" : " (" + eolOnly.size() + " differ only by line endings)")
                    + (docs == 0 ? "" : " (" + docs + " non-code file(s) differ: readme/translations)");
        } else {
            detail = codeMismatched.size() + " code file(s) differ from the WordPress.org checksums, " + codeMissing.size() + " missing: "
                    + String.join(", ", java.util.stream.Stream.concat(codeMismatched.stream(), codeMissing.stream()).limit(5).toList());
        }
        return new ChecksumResult(ok ? "OK" : "MISMATCH", expected.size(), mismatched, missing, detail);
    }

    /** Code files decide the checksum status; readme and translation files are routinely changed by WordPress.org. */
    public static boolean isCode(String path) {
        String p = path.toLowerCase();
        for (String ext : new String[]{".php", ".phtml", ".inc", ".js", ".mjs", ".so", ".dll", ".exe", ".sh", ".phar"}) {
            if (p.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ helpers

    static String field(String text, String name) {
        Matcher m = Pattern.compile("(?im)^[ \\t/*#@-]*" + Pattern.quote(name) + ":[ \\t]*(.*?)[ \\t]*$").matcher(text);
        if (m.find()) {
            String v = m.group(1).replaceAll("\\*/\\s*$", "").trim();
            return v.isEmpty() ? null : v;
        }
        return null;
    }

    static String changelogHead(String readme) {
        Matcher m = Pattern.compile("(?im)^==\\s*Changelog\\s*==\\s*$").matcher(readme);
        if (!m.find()) {
            return null;
        }
        String rest = readme.substring(m.end()).strip();
        // stop at the next top-level "== Section ==" heading
        Matcher next = Pattern.compile("(?m)^==[^=].*==\\s*$").matcher(rest);
        if (next.find()) {
            rest = rest.substring(0, next.start()).strip();
        }
        // keep the first version block: from the first "= x =" heading up to the second one
        Matcher h = Pattern.compile("(?m)^=\\s*[^=\\n]+\\s*=\\s*$").matcher(rest);
        int first = -1;
        int second = -1;
        while (h.find()) {
            if (first < 0) {
                first = h.start();
            } else {
                second = h.start();
                break;
            }
        }
        String block = first >= 0 ? rest.substring(first, second > 0 ? second : rest.length()) : rest;
        block = block.strip();
        return block.length() > 700 ? block.substring(0, 700).strip() + " …" : block;
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private static String readLimited(InputStream in, int max) throws IOException {
        byte[] data = in.readNBytes(max);
        return new String(data, StandardCharsets.UTF_8);
    }
}
