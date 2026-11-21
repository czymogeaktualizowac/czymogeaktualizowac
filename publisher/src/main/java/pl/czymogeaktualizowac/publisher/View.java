package pl.czymogeaktualizowac.publisher;

import java.util.List;

/** View models handed to the JTE templates. All strings are plain text (the templates escape them). */
public final class View {

    private View() {
    }

    public record Site(String baseUrl, String name, String tagline, String contactEmail, String signupFormUrl,
                       boolean signupConfigured, boolean pluginCta, String analyticsHtml, String buildDate,
                       List<NavItem> nav) {
    }

    public record NavItem(String label, String href) {
    }

    public record Meta(String title, String description, String path, boolean noindex) {
    }

    public record Crumb(String label, String href) {
    }

    /** code: ok | warn | bad | none (drives the colour class; the label is always spelled out as well). */
    public record Verdict(String code, String label, String detail) {
    }

    public record PluginRow(String slug, String name, String vendor, String category, String installs,
                            String latestVersion, String released, Verdict verdict, String forumText,
                            String forumCss, String href, String labSummary) {
    }

    public record IndexPage(Site site, Meta meta, List<PluginRow> plugins, List<EcoRow> ecosystem, int plugins12m) {
    }

    public record EcoRow(String name, String value, String note) {
    }

    public record ReleaseRow(String version, String released, Verdict verdict, String labSummary, String forumText,
                             String forumCss, String wcTested, String requiresPhp, String checksumText,
                             boolean prerelease, String href) {
    }

    public record PluginPage(Site site, Meta meta, PluginRow header, List<Crumb> crumbs, List<ReleaseRow> releases,
                             String note, String homepage, String signupAnchor, List<PluginOption> allPlugins) {
    }

    public record PluginOption(String slug, String name, boolean preselected) {
    }

    public record TopicRow(String title, String link, String date) {
    }

    public record LevelRow(String level, String label, String status, String css, String detail) {
    }

    public record RunRow(String profile, String php, String wp, String woo, String checkout, String mode,
                         String highest, List<LevelRow> levels) {
    }

    public record ReleasePage(Site site, Meta meta, List<Crumb> crumbs, String pluginName, String slug, String version,
                              String released, Verdict verdict, String forumSummary, List<TopicRow> topics,
                              List<RunRow> runs, String changelog, String requiresPhp, String wcRequires,
                              String wcTested, String testedWp, String license, List<String> requiresPlugins,
                              String checksumText, String zipSha256, String wpOrgUrl, boolean prerelease,
                              /* results exist but are held by the embargo: never say "not tested" */ boolean held) {
    }

    public record CompatItem(String plugin, String slug, String version, String declared, String declaredCss,
                             String lab, Verdict verdict, String href) {
    }

    public record CompatPage(Site site, Meta meta, List<Crumb> crumbs, String heading, String intro,
                             List<CompatItem> items, String disclaimer) {
    }

    public record PhpRow(String cycle, String latest, String security, String eol, String css, String blockers) {
    }

    public record PhpPage(Site site, Meta meta, List<Crumb> crumbs, List<PhpRow> rows) {
    }

    public record SimplePage(Site site, Meta meta, List<Crumb> crumbs) {
    }

    public record Link(String label, String href, String note) {
    }

    public record CompatIndexPage(Site site, Meta meta, List<Crumb> crumbs, List<Link> links) {
    }

    public record SignupPage(Site site, Meta meta, List<Crumb> crumbs, List<PluginOption> plugins) {
    }
}
