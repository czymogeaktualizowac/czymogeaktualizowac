@import pl.czymogeaktualizowac.publisher.View
@param View.IndexPage page
@template.layout(site = page.site(), meta = page.meta(), crumbs = java.util.List.of(), content = @`
<section class="hero">
  <h1>Czy mogę zaktualizować?</h1>
  <p class="lead">Niezależne, automatyczne testy wydań polskich wtyczek WooCommerce: kurierzy, bramki płatności, faktury, Omnibus. Zanim klikniesz „Aktualizuj”, sprawdź, co pokazały testy i co piszą użytkownicy.</p>
  <p><a class="btn" href="/powiadomienia/">Powiadom mnie o problemach z moimi wtyczkami</a></p>
</section>

<section>
  <h2>Najnowsze wydania śledzonych wtyczek</h2>
  <div class="table-wrap">
  <table class="data">
    <thead><tr><th>Wtyczka</th><th>Najnowsza</th><th>Werdykt</th><th>Testy (profil: poziom)</th><th>Forum po wydaniu</th></tr></thead>
    <tbody>
    @for(var p : page.plugins())
      <tr>
        <td><a href="${p.href()}"><strong>${p.name()}</strong></a><br><span class="small">${p.category()} · ${p.vendor()} · ${p.installs()}</span></td>
        <td>${p.latestVersion()}<br><span class="small">${p.released()}</span></td>
        <td><span class="badge ${p.verdict().code()}">${p.verdict().label()}</span></td>
        <td class="small">${p.labSummary()}</td>
        <td><span class="pill ${p.forumCss()}">${p.forumText()}</span></td>
      </tr>
    @endfor
    </tbody>
  </table>
  </div>
  <p class="small">Werdykt opisuje, co faktycznie sprawdziliśmy (poziomy L0–L4, profile P1–P3), a nie „bezpieczeństwo” aktualizacji. <a href="/jak-testujemy/">Co oznaczają poziomy i profile?</a></p>
</section>

<section>
  <h2>Środowisko: WordPress, WooCommerce, PHP</h2>
  <ul class="plain">
    @for(var e : page.ecosystem())
      <li><strong>${e.name()}</strong>: ${e.value()} <span class="small">${e.note()}</span></li>
    @endfor
  </ul>
  <p><a href="/kompatybilnosc/">Zgodność polskich wtyczek z nowymi wersjami WooCommerce, WordPressa i PHP →</a></p>
</section>

<section class="callout">
  <h2>Jak to działa</h2>
  <ol>
    <li>Co 12 godzin sprawdzamy nowe wydania wtyczek na WordPress.org i weryfikujemy sumy kontrolne plików.</li>
    <li>Każde wydanie instalujemy w jednorazowych sklepach testowych (różne wersje PHP, WordPressa i WooCommerce) i przechodzimy przez koszyk oraz zamówienie testowe.</li>
    <li>Liczymy zgłoszenia na forum wtyczki w 7 dni po wydaniu: to wczesny sygnał, że coś jest nie tak, zanim ktokolwiek dopisze się do wątku.</li>
    <li>Jeśli coś się psuje, wysyłamy alert tym, którzy zaznaczyli daną wtyczkę.</li>
  </ol>
</section>
`)
