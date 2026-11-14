@import pl.czymogeaktualizowac.publisher.View
@param View.SimplePage page
@template.layout(site = page.site(), meta = page.meta(), crumbs = page.crumbs(), content = @`
<h1>Polityka prywatności</h1>
<p class="notice"><strong>Szkic do uzupełnienia przed uruchomieniem zapisów.</strong> Dane administratora poniżej to miejsca do uzupełnienia; szkic nie jest poradą prawną i wymaga weryfikacji (patrz pytania do prawnika w planie).</p>

<h2>Administrator danych</h2>
<p>[IMIĘ I NAZWISKO LUB NAZWA FIRMY], [ADRES], e-mail: ${page.site().contactEmail()}.</p>

<h2>Jakie dane i po co</h2>
<ul>
  <li><strong>Zapis na powiadomienia:</strong> adres e-mail oraz wybrane wtyczki, rola i rodzaje alertów. Cel: wysyłanie wybranych alertów. Podstawa: zgoda (art. 6 ust. 1 lit. a RODO), wyrażana przez potwierdzenie zapisu e-mailem (double opt-in).</li>
  <li><strong>Strona internetowa:</strong> statystyki odwiedzin bez plików cookie i bez identyfikacji osoby (agregaty: strony, kraje, urządzenia). Podstawa: prawnie uzasadniony interes (art. 6 ust. 1 lit. f RODO) w ocenie, czy serwis jest przydatny.</li>
  <li><strong>Odpowiedzi na pytanie „czy aktualizacja kiedyś zepsuła Twój sklep?”</strong>: kliknięcie w link „tak/nie” jest liczone jako zdarzenie bez danych osobowych. Jeśli odpiszesz e-mailem, przetwarzamy treść odpowiedzi w celu kontaktu i badań; podstawa: zgoda.</li>
</ul>

<h2>Odbiorcy danych</h2>
<p>Dostawca usługi wysyłki wiadomości e-mail ([NAZWA DOSTAWCY], serwery w UE) jako podmiot przetwarzający na podstawie umowy powierzenia. Nie sprzedajemy danych i nie przekazujemy ich reklamodawcom.</p>

<h2>Jak długo</h2>
<p>Do wypisania się z listy (link w każdej wiadomości). Po wypisaniu usuwamy adres, z wyjątkiem minimalnego zapisu potrzebnego do wykazania zgody, jeśli prawo tego wymaga.</p>

<h2>Twoje prawa</h2>
<p>Masz prawo dostępu do danych, sprostowania, usunięcia, ograniczenia przetwarzania, przenoszenia, sprzeciwu oraz wycofania zgody w dowolnym momencie (bez wpływu na zgodność z prawem wcześniejszego przetwarzania). Skargę możesz złożyć do Prezesa Urzędu Ochrony Danych Osobowych. Napisz: ${page.site().contactEmail()}.</p>

<h2>Wtyczka WordPress</h2>
<p>Strona nie wymaga instalowania żadnego oprogramowania w Twoim sklepie. Gdy udostępnimy wtyczkę, będzie łączyć się z naszym serwerem wyłącznie po jawnym włączeniu tej opcji i nie będzie wysyłać adresu ani danych sklepu; opiszemy to tutaj i w opisie wtyczki.</p>
`)
