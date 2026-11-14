@import pl.czymogeaktualizowac.publisher.View
@param View.PluginPage page
@template.layout(site = page.site(), meta = page.meta(), crumbs = page.crumbs(), content = @`
<h1>${page.header().name()}: czy mogę zaktualizować?</h1>
<p class="lead">${page.header().category()} · ${page.header().vendor()} · ${page.header().installs()}
@if(page.homepage() != null) · <a href="${page.homepage()}" rel="nofollow noopener">strona wtyczki</a>@endif</p>

<div class="verdict ${page.header().verdict().code()}">
  <p class="verdict-title"><span class="badge ${page.header().verdict().code()}">${page.header().verdict().label()}</span>
     Najnowsze wydanie: <strong>${page.header().latestVersion()}</strong> (${page.header().released()})</p>
  @if(page.header().verdict().detail() != null)
    <p>${page.header().verdict().detail()}</p>
  @endif
  <p class="small">Testy: ${page.header().labSummary()} · Forum: ${page.header().forumText()}</p>
</div>

@if(page.note() != null)
  <p>${page.note()}</p>
@endif

<h2>Wydania i wyniki testów</h2>
<div class="table-wrap">
<table class="data">
  <thead><tr><th>Wersja</th><th>Data</th><th>Werdykt</th><th>Testy</th><th>Forum po wydaniu</th><th>WC testowana do</th><th>PHP min.</th></tr></thead>
  <tbody>
  @for(var r : page.releases())
    <tr>
      <td><a href="${r.href()}"><strong>${r.version()}</strong></a>@if(r.prerelease()) <span class="small">(pre-release)</span>@endif</td>
      <td class="small">${r.released()}</td>
      <td><span class="badge ${r.verdict().code()}">${r.verdict().label()}</span></td>
      <td class="small">${r.labSummary()}</td>
      <td><span class="pill ${r.forumCss()}">${r.forumText()}</span></td>
      <td class="small">${r.wcTested()}</td>
      <td class="small">${r.requiresPhp()}</td>
    </tr>
  @endfor
  </tbody>
</table>
</div>
<p class="small">Wydania starsze niż pokazane nie były jeszcze przetwarzane. Daty pochodzą z czasu utworzenia tagu w repozytorium WordPress.org.</p>

<section class="callout" id="powiadomienia">
  <h2>Powiadom mnie o problemach z tą wtyczką</h2>
  @template.signupform(site = page.site(), plugins = page.allPlugins(), formId = "signup-plugin")
</section>

<p class="small"><a href="/pluginy/${page.header().slug()}/rss.xml">RSS tej wtyczki</a></p>
`)
