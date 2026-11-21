package pl.czymogeaktualizowac.core;

import com.fasterxml.jackson.databind.JsonNode;
import pl.czymogeaktualizowac.core.Model.LabHints;
import pl.czymogeaktualizowac.core.Model.Profile;
import pl.czymogeaktualizowac.core.Model.TrackedPlugin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Validates config/*.json before anything uses it. The JSON mapper ignores unknown fields (data files must stay
 * readable across versions), so without this a typo such as {@code "gatewayID"} or {@code "wp": "lastest"} would
 * silently turn into "L3 skipped" or a different WordPress version. Allowed keys come from the model records, so the
 * model stays the single source of truth.
 *
 * <p>Errors make the config unusable; warnings are incomplete-but-intended states (e.g. a gateway without sandbox hints).
 */
public final class ConfigCheck {

    public record Report(List<String> errors, List<String> warnings) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    static final Set<String> CATEGORIES = Set.of("shipping", "payment", "invoicing", "compliance", "reviews", "other");
    static final Set<String> WP_SYMBOLS = Set.of("latest", "previous", "development");
    static final Set<String> WOO_SYMBOLS = Set.of("latest", "previous", "prerelease");
    static final Set<String> CHECKOUTS = Set.of("classic", "block");
    static final Set<String> DECISIONS = Set.of("publish", "hold");
    static final Set<String> SITE_KEYS = Set.of("baseUrl", "name", "tagline", "contactEmail", "signupFormUrl",
            "pluginCtaEnabled", "analyticsHtml");
    static final Set<String> EMBARGO_KEYS = Set.of("holdDays", "decisions");
    static final Set<String> DECISION_KEYS = Set.of("slug", "version", "decision", "note");

    private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9-]*");
    private static final Pattern ENV_PREFIX = Pattern.compile("[A-Z][A-Z0-9_]*");
    private static final Pattern PHP = Pattern.compile("\\d+\\.\\d+");
    private static final Pattern PINNED = Pattern.compile("[a-z0-9][a-z0-9._/-]*(:[\\w.-]+)?@sha256:[0-9a-f]{64}");

    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    private ConfigCheck() {
    }

    public static Report check(Path configDir) {
        ConfigCheck c = new ConfigCheck();
        Set<String> slugs = c.plugins(c.read(configDir.resolve("plugins.json"), true));
        Set<String> phps = c.profiles(c.read(configDir.resolve("profiles.json"), true));
        c.images(c.read(configDir.resolve("lab-images.json"), true), phps);
        c.embargo(c.read(configDir.resolve("embargo.json"), false), slugs);
        c.site(c.read(configDir.resolve("site.json"), false));
        return new Report(List.copyOf(c.errors), List.copyOf(c.warnings));
    }

    private JsonNode read(Path file, boolean required) {
        if (!Files.exists(file)) {
            if (required) {
                errors.add(file.getFileName() + ": missing");
            }
            return null;
        }
        try {
            return Json.tree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | UncheckedIOException e) {
            errors.add(file.getFileName() + ": not valid JSON (" + e.getMessage() + ")");
            return null;
        }
    }

    private Set<String> plugins(JsonNode root) {
        Set<String> slugs = new HashSet<>();
        if (root == null) {
            return slugs;
        }
        if (!root.isArray() || root.isEmpty()) {
            errors.add("plugins.json: must be a non-empty array");
            return slugs;
        }
        Set<String> pluginKeys = keysOf(TrackedPlugin.class);
        Set<String> labKeys = keysOf(LabHints.class);
        for (JsonNode p : root) {
            String slug = p.path("slug").asText("");
            String at = "plugins.json [" + (slug.isEmpty() ? "?" : slug) + "]";
            unknownKeys(p, pluginKeys, at);
            if (!SLUG.matcher(slug).matches()) {
                errors.add(at + ": slug must be a WordPress.org slug (a-z, 0-9, -)");
            } else if (!slugs.add(slug)) {
                errors.add(at + ": duplicate slug");
            }
            if (p.path("name").asText("").isBlank()) {
                errors.add(at + ": name is required");
            }
            String category = p.path("category").asText("");
            if (!CATEGORIES.contains(category)) {
                errors.add(at + ": category '" + category + "' is not one of " + CATEGORIES);
            }
            JsonNode lab = p.get("lab");
            if (lab == null || lab.isNull()) {
                continue;
            }
            unknownKeys(lab, labKeys, at + ".lab");
            for (String rx : List.of("redirectHostRegex", "shippingMethodRegex")) {
                String v = lab.path(rx).asText(null);
                if (v != null && !lab.path(rx).isNull()) {
                    try {
                        Pattern.compile(v);
                    } catch (PatternSyntaxException e) {
                        errors.add(at + ".lab." + rx + ": invalid regex (" + e.getDescription() + ")");
                    }
                }
            }
            String prefix = text(lab, "sandboxEnvPrefix");
            if (prefix != null && !ENV_PREFIX.matcher(prefix).matches()) {
                errors.add(at + ".lab.sandboxEnvPrefix: must be an upper-case environment variable prefix");
            }
            if ("payment".equals(category) && prefix != null
                    && (text(lab, "gatewayId") == null || text(lab, "optionName") == null || text(lab, "redirectHostRegex") == null)) {
                warnings.add(at + ": L3 stays SKIPPED until gatewayId, optionName and redirectHostRegex are set");
            }
            if ("shipping".equals(category) && text(lab, "shippingMethodRegex") != null && text(lab, "lockerSelector") == null) {
                warnings.add(at + ": L4 stays SKIPPED until lockerSelector is set");
            }
            JsonNode comp = lab.get("companions");
            if (comp != null && !comp.isNull()) {
                if (!comp.isArray()) {
                    errors.add(at + ".lab.companions: must be an array of slugs");
                } else {
                    comp.forEach(c -> {
                        if (!SLUG.matcher(c.asText("")).matches()) {
                            errors.add(at + ".lab.companions: '" + c.asText() + "' is not a slug");
                        }
                    });
                }
            }
        }
        return slugs;
    }

    /** @return the PHP versions the profiles need */
    private Set<String> profiles(JsonNode root) {
        Set<String> phps = new HashSet<>();
        if (root == null) {
            return phps;
        }
        if (!root.isArray() || root.isEmpty()) {
            errors.add("profiles.json: must be a non-empty array");
            return phps;
        }
        Set<String> keys = keysOf(Profile.class);
        Set<String> ids = new HashSet<>();
        for (JsonNode p : root) {
            phps.add(p.path("php").asText(""));
            String id = p.path("id").asText("");
            String at = "profiles.json [" + (id.isEmpty() ? "?" : id) + "]";
            unknownKeys(p, keys, at);
            if (id.isBlank() || !ids.add(id)) {
                errors.add(at + ": id is required and must be unique");
            }
            if (!PHP.matcher(p.path("php").asText("")).matches()) {
                errors.add(at + ": php must look like 8.3");
            }
            oneOf(p, "wp", WP_SYMBOLS, at);
            oneOf(p, "woo", WOO_SYMBOLS, at);
            JsonNode co = p.path("checkoutTypes");
            if (!co.isArray() || co.isEmpty()) {
                errors.add(at + ": checkoutTypes must be a non-empty array");
            } else {
                co.forEach(c -> {
                    if (!CHECKOUTS.contains(c.asText())) {
                        errors.add(at + ": checkout type '" + c.asText() + "' is not one of " + CHECKOUTS);
                    }
                });
            }
        }
        // the verdict logic treats P2/P3 as the core profiles ("bad") and the rest as legacy/edge ("warn")
        if (!ids.isEmpty() && !(ids.contains("P2") || ids.contains("P3"))) {
            errors.add("profiles.json: at least one core profile (P2 or P3) is required for a verdict");
        }
        return phps;
    }

    private void images(JsonNode root, Set<String> profilePhps) {
        if (root == null) {
            return;
        }
        unknownKeys(root, keysOf(Model.LabImages.class), "lab-images.json");
        String db = text(root, "database");
        if (db == null || !PINNED.matcher(db).matches()) {
            errors.add("lab-images.json: database must be pinned as name:tag@sha256:<64 hex>, got '" + db + "'");
        }
        JsonNode wp = root.path("wordpress");
        if (!wp.isObject()) {
            errors.add("lab-images.json: wordpress must map PHP versions to pinned images");
            return;
        }
        wp.fieldNames().forEachRemaining(php -> {
            String img = wp.path(php).asText("");
            if (!PHP.matcher(php).matches() || !PINNED.matcher(img).matches()) {
                errors.add("lab-images.json [" + php + "]: must map a PHP version to name:tag@sha256:<64 hex>, got '" + img + "'");
            }
        });
        for (String php : profilePhps) {
            if (!php.isEmpty() && !wp.has(php)) {
                errors.add("lab-images.json: no pinned WordPress image for PHP " + php + " used by a profile (run 'lab pin-images')");
            }
        }
    }

    private void embargo(JsonNode root, Set<String> slugs) {
        if (root == null) {
            return;
        }
        unknownKeys(root, EMBARGO_KEYS, "embargo.json");
        JsonNode hd = root.get("holdDays");
        if (hd != null && !hd.isNull() && (!hd.canConvertToInt() || hd.asInt() < 0)) {
            errors.add("embargo.json: holdDays must be a non-negative whole number");
        }
        JsonNode ds = root.path("decisions");
        if (ds.isMissingNode() || ds.isNull()) {
            return;
        }
        if (!ds.isArray()) {
            errors.add("embargo.json: decisions must be an array");
            return;
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode d : ds) {
            String key = d.path("slug").asText("") + "@" + d.path("version").asText("");
            String at = "embargo.json [" + key + "]";
            unknownKeys(d, DECISION_KEYS, at);
            if (!slugs.isEmpty() && !slugs.contains(d.path("slug").asText(""))) {
                errors.add(at + ": slug is not a tracked plugin");
            }
            if (d.path("version").asText("").isBlank()) {
                errors.add(at + ": version is required");
            }
            oneOf(d, "decision", DECISIONS, at);
            if (!seen.add(key)) {
                errors.add(at + ": more than one decision for the same release");
            }
        }
    }

    private void site(JsonNode root) {
        if (root == null) {
            return;
        }
        unknownKeys(root, SITE_KEYS, "site.json");
        String base = text(root, "baseUrl");
        if (base != null && (!base.startsWith("https://") || base.endsWith("/"))) {
            errors.add("site.json: baseUrl must be https:// without a trailing slash");
        }
        if (text(root, "signupFormUrl") == null) {
            warnings.add("site.json: signupFormUrl is empty, the signup page links nowhere");
        }
    }

    // ------------------------------------------------------------------ helpers

    private void oneOf(JsonNode n, String field, Set<String> allowed, String at) {
        String v = n.path(field).asText("");
        if (!allowed.contains(v)) {
            errors.add(at + ": " + field + " '" + v + "' is not one of " + allowed);
        }
    }

    private void unknownKeys(JsonNode n, Set<String> allowed, String at) {
        if (!n.isObject()) {
            errors.add(at + ": must be an object");
            return;
        }
        n.fieldNames().forEachRemaining(k -> {
            if (!allowed.contains(k)) {
                errors.add(at + ": unknown key '" + k + "' (allowed: " + allowed.stream().sorted().toList() + ")");
            }
        });
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText();
    }

    static Set<String> keysOf(Class<? extends Record> type) {
        Set<String> out = new HashSet<>();
        Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).forEach(out::add);
        return out;
    }
}
