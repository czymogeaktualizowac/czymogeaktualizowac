@import pl.czymogeaktualizowac.publisher.View
@param View.SignupPage page
@template.layout(site = page.site(), meta = page.meta(), crumbs = page.crumbs(), content = @`
<h1>Powiadomienia o problemach z wtyczkami</h1>
<p class="lead">Wybierz wtyczki, z których korzystasz. Napiszemy, gdy któreś wydanie sprawia problemy, albo gdy nowe WooCommerce, WordPress lub PHP zmienia sytuację. Bez newslettera i reklam.</p>
@template.signupform(site = page.site(), plugins = page.plugins(), formId = "signup-main")
<h2>Co dostaniesz</h2>
<ul>
  <li><strong>Alert o wadliwym wydaniu</strong>: tylko dla zaznaczonych wtyczek, rzadko, z informacją które wersje dotyczy i co zrobić.</li>
  <li><strong>Zgodność z nowymi wersjami</strong>: gdy wychodzi (lub jest w wersji RC) nowe WooCommerce, WordPress lub PHP, piszemy, jak z nim radzą sobie Twoje wtyczki.</li>
  <li><strong>Miesięczne podsumowanie</strong> (opcjonalnie): co się zmieniło, zanim zaplanujesz okno serwisowe.</li>
</ul>
<p class="small">Nie znamy wersji wtyczek w Twoim sklepie: alerty są na poziomie wtyczki, z zakresem dotkniętych wersji. Jeśli aktualizacje robi za Ciebie ktoś inny, przekaż mu alert: w każdej wiadomości jest przycisk „Przekaż osobie, która aktualizuje sklep”. Nie wysyłamy wiadomości na adresy osób trzecich bez ich zgody.</p>
`)
