package pl.czymogeaktualizowac.publisher;

import com.fasterxml.jackson.core.JsonProcessingException;
import pl.czymogeaktualizowac.core.Json;
import pl.czymogeaktualizowac.publisher.View.Crumb;
import pl.czymogeaktualizowac.publisher.View.Site;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Structured data helpers (called from the layout template). */
public final class Seo {

    private Seo() {
    }

    /** BreadcrumbList JSON-LD, safe to embed in a script tag (angle brackets are escaped). */
    public static String breadcrumbJsonLd(Site site, List<Crumb> crumbs) {
        if (crumbs == null || crumbs.isEmpty()) {
            return "";
        }
        List<Map<String, Object>> items = new ArrayList<>();
        int pos = 1;
        for (Crumb c : crumbs) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("@type", "ListItem");
            item.put("position", pos++);
            item.put("name", c.label());
            if (c.href() != null) {
                item.put("item", site.baseUrl() + c.href());
            }
            items.add(item);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("@context", "https://schema.org");
        root.put("@type", "BreadcrumbList");
        root.put("itemListElement", items);
        try {
            return Json.MAPPER.writer().without(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
                    .writeValueAsString(root).replace("<", "\\u003c");
        } catch (JsonProcessingException e) {
            return "";
        }
    }
}
