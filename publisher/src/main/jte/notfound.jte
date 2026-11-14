@import pl.czymogeaktualizowac.publisher.View
@param View.SimplePage page
@template.layout(site = page.site(), meta = page.meta(), crumbs = java.util.List.of(), content = @`
<h1>Nie znaleziono strony</h1>
<p>Ta strona nie istnieje albo została przeniesiona. <a href="/">Wróć do listy wtyczek.</a></p>
`)
