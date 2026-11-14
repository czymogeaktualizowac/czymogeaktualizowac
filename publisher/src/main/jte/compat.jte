@import pl.czymogeaktualizowac.publisher.View
@param View.CompatPage page
@template.layout(site = page.site(), meta = page.meta(), crumbs = page.crumbs(), content = @`
<h1>${page.heading()}</h1>
<p class="lead">${page.intro()}</p>
<div class="table-wrap">
<table class="data">
  <thead><tr><th>Wtyczka</th><th>Najnowsza</th><th>Deklaracja autora</th><th>Nasze testy</th><th>Werdykt</th></tr></thead>
  <tbody>
  @for(var i : page.items())
    <tr>
      <td><a href="${i.href()}"><strong>${i.plugin()}</strong></a></td>
      <td>${i.version()}</td>
      <td><span class="pill ${i.declaredCss()}">${i.declared()}</span></td>
      <td class="small">${i.lab()}</td>
      <td><span class="badge ${i.verdict().code()}">${i.verdict().label()}</span></td>
    </tr>
  @endfor
  </tbody>
</table>
</div>
<p class="small">${page.disclaimer()}</p>
`)
