package pl.czymogeaktualizowac.lab;

import pl.czymogeaktualizowac.core.Model.LabHints;

import java.nio.file.Path;
import java.util.List;

/**
 * Everything needed for one lab run. wp/woo are concrete versions (already resolved from the profile).
 *
 * @param stack other plugins to install next to the plugin under test (mode "stack"); empty for "solo"
 */
public record RunSpec(
        String slug,
        String version,
        Path zip,
        String category,
        LabHints hints,
        String profileId,
        String php,
        String wp,
        String woo,
        /* classic | block */ String checkout,
        /* solo | stack */ String mode,
        List<StackItem> stack,
        String notes) {

    public record StackItem(String slug, Path zip) {
    }
}
