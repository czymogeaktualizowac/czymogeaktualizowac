@import pl.czymogeaktualizowac.publisher.View
@param View.CompatIndexPage page
@template.layout(site = page.site(), meta = page.meta(), crumbs = page.crumbs(), content = @`
<h1>Zgodność z nowymi wersjami WooCommerce, WordPressa i PHP</h1>
<p class="lead">Dla każdego nowego wydania (także wersji RC) pokazujemy deklaracje autorów i wyniki naszych testów dla śledzonych wtyczek.</p>
<ul class="plain">
  @for(var l : page.links())
    <li><a href="${l.href()}"><strong>${l.label()}</strong></a> <span class="small">${l.note()}</span></li>
  @endfor
</ul>
`)
