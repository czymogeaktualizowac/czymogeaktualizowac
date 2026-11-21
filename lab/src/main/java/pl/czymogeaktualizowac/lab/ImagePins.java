package pl.czymogeaktualizowac.lab;

import pl.czymogeaktualizowac.core.Http;
import pl.czymogeaktualizowac.core.Json;
import pl.czymogeaktualizowac.core.Model.LabImages;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

/**
 * Refreshes config/lab-images.json: resolves each image tag to the digest Docker Hub serves for it right now.
 * Digests of the multi-platform index are used, so a pin works on amd64 (GitHub runners) and arm64 alike.
 * Run it on purpose ({@code lab pin-images}), review the diff and merge it through a PR: until then every lab run uses
 * exactly the previous images.
 */
public final class ImagePins {

    static final String WORDPRESS_TAG = "php%s-apache";
    static final String DEFAULT_DATABASE = "mariadb:11.4";
    private static final String ACCEPT = String.join(",",
            "application/vnd.oci.image.index.v1+json",
            "application/vnd.docker.distribution.manifest.list.v2+json",
            "application/vnd.oci.image.manifest.v1+json",
            "application/vnd.docker.distribution.manifest.v2+json");

    private final Http http;

    public ImagePins(Http http) {
        this.http = http;
    }

    /**
     * @param current the pins in use (may be null): the database keeps its repository and tag
     * @param phps    PHP versions that need a WordPress image
     */
    public LabImages resolve(LabImages current, Collection<String> phps) throws IOException {
        String dbRef = current == null || current.database() == null ? DEFAULT_DATABASE : unpinned(current.database());
        Map<String, String> wp = new TreeMap<>();
        for (String php : phps) {
            wp.put(php, pin("wordpress:" + String.format(WORDPRESS_TAG, php)));
        }
        return new LabImages(pin(dbRef), wp);
    }

    /** "mariadb:11.4" -> "mariadb:11.4@sha256:…" (official Docker Hub images). */
    String pin(String ref) throws IOException {
        int colon = ref.lastIndexOf(':');
        String repo = colon < 0 ? ref : ref.substring(0, colon);
        String tag = colon < 0 ? "latest" : ref.substring(colon + 1);
        String path = repo.contains("/") ? repo : "library/" + repo;
        String token = Json.tree(http.getString("https://auth.docker.io/token?service=registry.docker.io&scope=repository:"
                + path + ":pull")).path("token").asText();
        Http.Response r = http.get("https://registry-1.docker.io/v2/" + path + "/manifests/" + tag,
                Map.of("Accept", ACCEPT, "Authorization", "Bearer " + token));
        String digest = r.headers().get("docker-content-digest");
        if (digest == null || !digest.matches("sha256:[0-9a-f]{64}")) {
            throw new IOException("no digest for " + ref + " (registry answered " + r.status() + ")");
        }
        return repo + ":" + tag + "@" + digest;
    }

    /** "mariadb:11.4@sha256:…" -> "mariadb:11.4". */
    static String unpinned(String image) {
        int at = image.indexOf('@');
        return at < 0 ? image : image.substring(0, at);
    }
}
