@import pl.czymogeaktualizowac.publisher.View
@param View.SimplePage page
@template.layout(site = page.site(), meta = page.meta(), crumbs = page.crumbs(), content = @`
<h1>${page.meta().title()}</h1>
<p class="lead">${page.meta().description()}</p>
<p>Jeśli chcesz opowiedzieć o tym, co się stało (która wtyczka, jaka wersja, ile to kosztowało), napisz na ${page.site().contactEmail()}. Z chęcią umówimy się na 15-minutową rozmowę.</p>
<p><a href="/">Wróć do listy wtyczek</a></p>
`)
