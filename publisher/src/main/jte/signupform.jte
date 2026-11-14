@import pl.czymogeaktualizowac.publisher.View
@param View.Site site
@param java.util.List<View.PluginOption> plugins
@param String formId
@if(!site.signupConfigured())
  <p class="notice">Zapisy ruszą wkrótce. Formularz poniżej jest podglądem i na razie nie wysyła danych.</p>
@endif
<form class="signup" id="${formId}" method="post" action="${site.signupFormUrl()}">
  <fieldset disabled="${!site.signupConfigured()}">
    <label class="field">Adres e-mail
      <input type="email" name="email" autocomplete="email" required placeholder="ty@example.pl">
    </label>

    <fieldset class="group">
      <legend>Z jakich wtyczek korzystasz?</legend>
      <div class="checks">
      @for(var p : plugins)
        <label><input type="checkbox" name="plugins[]" value="${p.slug()}" checked="${p.preselected()}"> ${p.name()}</label>
      @endfor
      </div>
    </fieldset>

    <fieldset class="group">
      <legend>Kim jesteś?</legend>
      <label><input type="radio" name="role" value="agency"> Agencja lub freelancer (opiekuję się wieloma sklepami)</label>
      <label><input type="radio" name="role" value="owner" checked> Właściciel sklepu, aktualizuję go sam(a)</label>
      <label><input type="radio" name="role" value="delegate"> Właściciel sklepu, aktualizacje robi ktoś inny</label>
    </fieldset>

    <fieldset class="group">
      <legend>Co chcesz dostawać?</legend>
      <label><input type="checkbox" name="alerts[]" value="bad-release" checked> Alert o wadliwym wydaniu (rzadko)</label>
      <label><input type="checkbox" name="alerts[]" value="compat" checked> Nowe WooCommerce / WordPress / PHP: status polskich wtyczek</label>
      <label><input type="checkbox" name="alerts[]" value="digest"> Raz w miesiącu: podsumowanie przed oknem serwisowym</label>
    </fieldset>

    <label class="consent"><input type="checkbox" name="consent" value="yes" required>
      Zgadzam się na otrzymywanie wiadomości e-mail z alertami wybranymi powyżej. Zgodę mogę wycofać w każdej wiadomości. <a href="/polityka-prywatnosci/">Polityka prywatności</a>.</label>

    <p><button type="submit" class="btn">Zapisz mnie (potwierdzenie e-mailem)</button></p>
  </fieldset>
</form>
