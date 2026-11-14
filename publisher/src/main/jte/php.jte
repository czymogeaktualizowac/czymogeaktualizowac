@import pl.czymogeaktualizowac.publisher.View
@param View.PhpPage page
@template.layout(site = page.site(), meta = page.meta(), crumbs = page.crumbs(), content = @`
<h1>PHP a polskie wtyczki WooCommerce</h1>
<p class="lead">Wsparcie dla wersji PHP kończy się w ustalonych datach. Poniżej daty z endoflife.date oraz śledzone wtyczki, które już dziś wymagają nowszego PHP.</p>
<div class="table-wrap">
<table class="data">
  <thead><tr><th>PHP</th><th>Ostatnie wydanie</th><th>Wsparcie bezpieczeństwa do</th><th>Koniec życia (EOL)</th><th>Śledzone wtyczki, które nie uruchomią się na tej wersji (wymagają nowszej)</th></tr></thead>
  <tbody>
  @for(var r : page.rows())
    <tr><td><strong>${r.cycle()}</strong></td><td>${r.latest()}</td><td><span class="pill ${r.css()}">${r.security()}</span></td><td>${r.eol()}</td><td class="small">${r.blockers()}</td></tr>
  @endfor
  </tbody>
</table>
</div>
<p class="small">Źródła: endoflife.date, nagłówki „Requires PHP” w najnowszych wydaniach wtyczek. Hostingi mogą wydłużać wsparcie odpłatnie; sprawdź ofertę swojego hostingu.</p>
`)
