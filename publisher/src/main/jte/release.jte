@import pl.czymogeaktualizowac.publisher.View
@param View.ReleasePage page
@template.layout(site = page.site(), meta = page.meta(), crumbs = page.crumbs(), content = @`
<h1>${page.pluginName()} ${page.version()}: czy mogę zaktualizować?</h1>
<p class="lead">Wydanie z dnia ${page.released()}@if(page.prerelease()) · wersja przedpremierowa@endif</p>

<div class="verdict ${page.verdict().code()}">
  <p class="verdict-title"><span class="badge ${page.verdict().code()}">${page.verdict().label()}</span></p>
  @if(page.verdict().detail() != null)
    <p>${page.verdict().detail()}</p>
  @endif
  <p class="small">Forum: ${page.forumSummary()}</p>
</div>

@if(!page.topics().isEmpty())
<h2>Zgłoszenia na forum w 7 dni po wydaniu</h2>
<ul>
  @for(var t : page.topics())
    <li><a href="${t.link()}" rel="nofollow noopener">${t.title()}</a> <span class="small">${t.date()}</span></li>
  @endfor
</ul>
@endif

<h2>Wyniki testów laboratoryjnych</h2>
@if(page.runs().isEmpty())
  <p>Tego wydania nie przetestowaliśmy jeszcze w laboratorium. Poziomy i profile opisuje strona <a href="/jak-testujemy/">Jak testujemy</a>.</p>
@else
  @for(var r : page.runs())
    <h3>${r.profile()} · ${r.checkout()} · ${r.mode()}</h3>
    <p class="small">PHP ${r.php()} · WordPress ${r.wp()} · WooCommerce ${r.woo()} · najwyższy zaliczony poziom: <strong>${r.highest()}</strong></p>
    <div class="table-wrap">
    <table class="data compact">
      <thead><tr><th>Poziom</th><th>Wynik</th><th>Szczegóły</th></tr></thead>
      <tbody>
      @for(var l : r.levels())
        <tr><td>${l.level()} <span class="small">${l.label()}</span></td><td><span class="pill ${l.css()}">${l.status()}</span></td><td class="small">${l.detail()}</td></tr>
      @endfor
      </tbody>
    </table>
    </div>
  @endfor
@endif

<h2>Dane wydania</h2>
<dl class="facts">
  <dt>Minimalne PHP</dt><dd>${page.requiresPhp()}</dd>
  <dt>WooCommerce: min. / testowana do</dt><dd>${page.wcRequires()} / ${page.wcTested()}</dd>
  <dt>WordPress: testowana do</dt><dd>${page.testedWp()}</dd>
  <dt>Licencja</dt><dd>${page.license()}</dd>
  <dt>Wymagane wtyczki</dt><dd>@if(page.requiresPlugins().isEmpty())–@else${String.join(", ", page.requiresPlugins())}@endif</dd>
  <dt>Sumy kontrolne WordPress.org</dt><dd>${page.checksumText()}</dd>
  <dt>SHA-256 pliku zip</dt><dd class="mono">${page.zipSha256()}</dd>
</dl>

@if(page.changelog() != null)
<h2>Lista zmian (fragment readme autora)</h2>
<pre class="changelog">${page.changelog()}</pre>
@endif

<p><a href="${page.wpOrgUrl()}" rel="nofollow noopener">Wtyczka na WordPress.org</a> · <a href="/pluginy/${page.slug()}/">Wszystkie wydania</a></p>
`)
