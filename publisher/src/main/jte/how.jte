@import pl.czymogeaktualizowac.publisher.View
@param View.SimplePage page
@template.layout(site = page.site(), meta = page.meta(), crumbs = page.crumbs(), content = @`
<h1>Jak testujemy</h1>
<p class="lead">To niezależny, zautomatyzowany projekt. Poniżej dokładnie opisujemy, co sprawdzamy, a czego nie: wyniki mają Ci pomóc podjąć decyzję, nie ją zastąpić.</p>

<h2>Skąd bierzemy wydania</h2>
<p>Co 12 godzin pytamy publiczne API WordPress.org o śledzone wtyczki. Nowe wydanie pobieramy w postaci pliku zip, porównujemy każdy plik z sumami kontrolnymi publikowanymi przez WordPress.org i odczytujemy nagłówki (wymagane PHP, wersja WooCommerce, do której autor testował wtyczkę). Daty wydań pochodzą z czasu utworzenia tagu w repozytorium wtyczki.</p>

<h2>Sygnał społeczności</h2>
<p>Każda wtyczka na WordPress.org ma publiczne forum. Liczymy nowe tematy w ciągu 7 dni od wydania. 0 tematów = „brak zgłoszeń”, 1–2 = „pojedyncze”, 3 lub więcej (albo 2 i jeden wspomina numer wersji) = „podwyższone”. To tylko wskaźnik: nie wiemy, czy zgłoszenie dotyczy wydania, a brak zgłoszeń nie znaczy, że wszystko działa.</p>

<h2>Poziomy testów laboratoryjnych</h2>
<p>Wydanie instalujemy w jednorazowym sklepie testowym (WordPress + WooCommerce + baza w kontenerach, polskie ustawienia sklepu, waluta PLN) i przechodzimy kolejne poziomy:</p>
<table class="data">
  <thead><tr><th>Poziom</th><th>Co sprawdzamy</th></tr></thead>
  <tbody>
    <tr><td><strong>L0</strong></td><td>Instalacja i aktywacja bez błędu krytycznego; strona główna, sklep i koszyk odpowiadają bez „fatal error” (stronę zamówienia sprawdza L1); dziennik błędów PHP bez błędów krytycznych.</td></tr>
    <tr><td><strong>L1</strong></td><td>Dodanie produktu do koszyka w przeglądarce; strona koszyka i strona zamówienia (klasyczna lub blokowa) wczytują się z formularzem.</td></tr>
    <tr><td><strong>L2</strong></td><td>Złożenie zamówienia testowego z płatnością przy odbiorze; strona potwierdzenia zamówienia.</td></tr>
    <tr><td><strong>L3</strong></td><td>Bramka płatności w trybie sandbox: przekierowanie dociera do strony płatności. Tylko tam, gdzie mamy dane sandboxowe; w innym razie „pominięto”.</td></tr>
    <tr><td><strong>L4</strong></td><td>Widget wyboru paczkomatu lub punktu odbioru pojawia się po wybraniu metody dostawy (samego wyboru punktu nie sprawdzamy). Tylko tam, gdzie mamy konfigurację; w innym razie „pominięto”.</td></tr>
  </tbody>
</table>
<p><strong>„Pominięto” nigdy nie oznacza „zaliczono”.</strong> Werdykt wymienia tylko poziomy zaliczone we wszystkich głównych profilach (P2, P3): „testy L0–L2 bez błędów” mówi tylko o L0–L2, a „L0–L3” dodatkowo o bramce płatności. „Nie dotyczy” oznacza poziom, który nie ma sensu dla danego rodzaju wtyczki (np. bramka płatności dla wtyczki kurierskiej).</p>
<p>Każdy test zaczynamy od przebiegu kontrolnego bez testowanej wtyczki (z jej zależnościami, a w trybie „stack” z pozostałymi wtyczkami zestawu). Jeśli sklep nie działa już wtedy, to problem laboratorium, nie wtyczki: pokazujemy „wynik nieznany” i powtarzamy test. Błąd przypisujemy wtyczce dopiero wtedy, gdy powtórzy się w drugim, niezależnym uruchomieniu.</p>

<h2>Profile testowe</h2>
<p>Nie testujemy wszystkich kombinacji wersji (to tysiące). Testujemy zmianę (nowe wydanie jednej wtyczki) w kilku profilach opartych na tym, co faktycznie działa w polskich sklepach:</p>
<table class="data">
  <thead><tr><th>Profil</th><th>PHP</th><th>WordPress / WooCommerce</th><th>Zestawienie z checkoutem</th></tr></thead>
  <tbody>
    <tr><td><strong>P1 Legacy-PL</strong></td><td>8.1</td><td>poprzednia linia WP i Woo</td><td>klasyczny</td></tr>
    <tr><td><strong>P2 Mainstream</strong></td><td>8.2</td><td>najnowsze</td><td>klasyczny i blokowy</td></tr>
    <tr><td><strong>P3 Current</strong></td><td>8.3</td><td>najnowsze</td><td>klasyczny i blokowy</td></tr>
    <tr><td><strong>P4 Edge</strong></td><td>8.4</td><td>wersje beta/RC WP i Woo (jeśli są)</td><td>blokowy</td></tr>
  </tbody>
</table>
<p>Poza testowaną wtyczką w sklepie jest tylko WooCommerce (tryb „solo”) albo cały zestaw śledzonych wtyczek w najnowszych wersjach (tryb „stack”, wykrywa konflikty). Błąd w P2 lub P3 daje werdykt „wstrzymaj”; błąd w P1 lub P4 daje „uwaga”.</p>

<h2>Czego nie robimy</h2>
<ul>
  <li>Nie testujemy Twojego sklepu, Twojego motywu ani Twoich innych wtyczek.</li>
  <li>Nie sprawdzamy płatności produkcyjnych, faktur, KSeF ani integracji z kontami Twoich dostawców.</li>
  <li>Nie testujemy wtyczek płatnych ani niedostępnych w WordPress.org (np. oficjalne wtyczki Przelewy24 i Tpay).</li>
  <li>Nie podajemy porad prawnych ani podatkowych.</li>
</ul>

<h2>Niezależność i błędy</h2>
<p>Nie jesteśmy powiązani z autorami wtyczek. O wykrytych błędach zawiadamiamy autorów, zanim opiszemy je publicznie, o ile to możliwe. Jeśli uważasz, że wynik jest błędny, napisz: ${page.site().contactEmail()}.</p>
`)
