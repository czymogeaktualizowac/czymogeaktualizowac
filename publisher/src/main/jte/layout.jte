@import pl.czymogeaktualizowac.publisher.View
@import pl.czymogeaktualizowac.publisher.Seo
@param View.Site site
@param View.Meta meta
@param java.util.List<View.Crumb> crumbs
@param gg.jte.Content content
<!doctype html>
<html lang="pl">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${meta.title()}</title>
<meta name="description" content="${meta.description()}">
<link rel="canonical" href="${site.baseUrl()}${meta.path()}">
@if(meta.noindex())
<meta name="robots" content="noindex">
@endif
<link rel="alternate" type="application/rss+xml" title="${site.name()} – wszystkie wydania" href="/rss.xml">
<link rel="stylesheet" href="/assets/style.css">
@if(crumbs != null && !crumbs.isEmpty())
<script type="application/ld+json">$unsafe{Seo.breadcrumbJsonLd(site, crumbs)}</script>
@endif
$unsafe{site.analyticsHtml()}
</head>
<body>
<a class="skip" href="#main">Przejdź do treści</a>
<header class="top">
  <div class="wrap">
    <a class="brand" href="/">${site.name()}</a>
    <nav aria-label="Główna nawigacja">
      @for(var n : site.nav())
        <a href="${n.href()}">${n.label()}</a>
      @endfor
    </nav>
  </div>
</header>
<main id="main" class="wrap">
@if(crumbs != null && crumbs.size() > 1)
  <nav class="crumbs" aria-label="Ścieżka">
    @for(int i = 0; i < crumbs.size(); i++)
      @if(crumbs.get(i).href() != null && i < crumbs.size() - 1)
        <a href="${crumbs.get(i).href()}">${crumbs.get(i).label()}</a> /
      @else
        <span>${crumbs.get(i).label()}</span>
      @endif
    @endfor
  </nav>
@endif
${content}
</main>
<footer class="foot">
  <div class="wrap">
    <p>${site.name()} to niezależny projekt. Nie jesteśmy powiązani z autorami testowanych wtyczek ani z WordPress.org. Nazwy wtyczek i firm używamy wyłącznie opisowo.</p>
    <p>Wyniki dotyczą naszych konfiguracji testowych, nie Twojego sklepu, i nie są gwarancją. <a href="/jak-testujemy/">Jak testujemy</a> · <a href="/powiadomienia/">Powiadomienia</a> · <a href="/polityka-prywatnosci/">Polityka prywatności</a> · <a href="/rss.xml">RSS</a></p>
    <p class="small">Dane zaktualizowano: ${site.buildDate()}.</p>
  </div>
</footer>
</body>
</html>
