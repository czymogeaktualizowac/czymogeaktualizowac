package pl.czymogeaktualizowac.lab;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import pl.czymogeaktualizowac.core.Model.Level;
import pl.czymogeaktualizowac.core.Model.LevelResult;
import pl.czymogeaktualizowac.core.Model.Status;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Browser-level checks against one lab store (levels L1-L4). One instance = one browser context (own cookies/cart).
 * Selectors cover both the classic shortcode checkout and the WooCommerce block checkout.
 */
final class StoreBrowser implements AutoCloseable {

    record Store(String baseUrl, long productId, long cartPageId, long checkoutPageId) {
        String cart() {
            return baseUrl + "/?page_id=" + cartPageId;
        }

        String checkout() {
            return baseUrl + "/?page_id=" + checkoutPageId;
        }
    }

    private static final String CHECKOUT_ANY = "form.checkout, .wp-block-woocommerce-checkout, .wc-block-checkout";
    private static final double STEP_TIMEOUT_MS = 25_000;

    private final BrowserContext context;
    private final Page page;
    private final Store store;
    private final String pluginSlug;
    private final List<String> pageErrors = new ArrayList<>();
    private final List<String> consoleErrors = new ArrayList<>();

    StoreBrowser(Browser browser, Store store, String pluginSlug) {
        this.store = store;
        this.pluginSlug = pluginSlug;
        this.context = browser.newContext(new Browser.NewContextOptions().setLocale("en-US").setViewportSize(1280, 900));
        this.page = context.newPage();
        page.setDefaultTimeout(STEP_TIMEOUT_MS);
        page.onPageError(e -> pageErrors.add(e));
        page.onConsoleMessage(m -> {
            if ("error".equals(m.type())) {
                consoleErrors.add(m.text());
            }
        });
    }

    // ------------------------------------------------------------------ L1

    LevelResult l1(String expectedCheckout) {
        long t0 = System.currentTimeMillis();
        try {
            page.navigate(store.baseUrl() + "/?add-to-cart=" + store.productId());
            page.navigate(store.cart());
            page.getByText("Produkt testowy").first().waitFor();
            requireNoCriticalError("koszyk");
            page.navigate(store.checkout());
            page.waitForSelector(CHECKOUT_ANY);
            requireNoCriticalError("strona zamówienia");
            boolean classic = page.locator("form.checkout").count() > 0;
            String rendered = classic ? "classic" : "block";
            if (!rendered.equals(expectedCheckout)) {
                return new LevelResult(Level.L1, Status.ERROR, "błąd laboratorium: oczekiwano checkoutu " + expectedCheckout + ", wyrenderowano " + rendered, since(t0));
            }
            page.waitForLoadState(LoadState.NETWORKIDLE);
            String pluginErrors = pluginAttributed(pageErrors);
            if (pluginErrors != null) {
                return new LevelResult(Level.L1, Status.FAIL, "błąd JavaScript pochodzący z plików wtyczki: " + pluginErrors, since(t0));
            }
            return new LevelResult(Level.L1, Status.PASS, "koszyk pokazuje produkt, strona zamówienia (" + (classic ? "klasyczna" : "blokowa")
                    + ") wczytana z formularzem" + infoSuffix(), since(t0));
        } catch (CriticalError e) {
            return new LevelResult(Level.L1, Status.FAIL, e.getMessage(), since(t0));
        } catch (PlaywrightException e) {
            return new LevelResult(Level.L1, Status.FAIL, "przeglądarka: " + firstLine(e.getMessage()), since(t0));
        }
    }

    // ------------------------------------------------------------------ L2

    LevelResult l2(String checkout) {
        long t0 = System.currentTimeMillis();
        try {
            // the cart from L1 is still in this context; reload the checkout
            page.navigate(store.checkout());
            page.waitForSelector(CHECKOUT_ANY);
            if ("classic".equals(checkout)) {
                fillClassic();
                page.locator("#payment_method_cod").check();
                page.locator("#place_order").click();
            } else {
                fillBlock();
                selectBlockPayment("cod");
                page.locator(".wc-block-components-checkout-place-order-button").click();
            }
            page.waitForURL(Pattern.compile("order-received"), new Page.WaitForURLOptions().setTimeout(40_000));
            requireNoCriticalError("potwierdzenie zamówienia");
            return new LevelResult(Level.L2, Status.PASS, "zamówienie testowe (płatność przy odbiorze) złożone, strona potwierdzenia wczytana", since(t0));
        } catch (CriticalError e) {
            return new LevelResult(Level.L2, Status.FAIL, e.getMessage(), since(t0));
        } catch (PlaywrightException e) {
            String err = pluginAttributed(pageErrors);
            return new LevelResult(Level.L2, Status.FAIL, "nie udało się złożyć zamówienia testowego: " + firstLine(e.getMessage())
                    + (err == null ? "" : "; błąd JS wtyczki: " + err), since(t0));
        }
    }

    // ------------------------------------------------------------------ L3 / L4 (need sandbox data; see LabRunner)

    LevelResult l3(String checkout, String gatewayId, String redirectHostRegex) {
        long t0 = System.currentTimeMillis();
        String step = "dodanie produktu do koszyka";
        try {
            addProductToCart();
            step = "otwarcie checkoutu";
            page.navigate(store.checkout());
            page.waitForSelector(CHECKOUT_ANY);
            step = "wybór metody płatności " + gatewayId;
            if ("classic".equals(checkout)) {
                fillClassic();
                page.locator("#payment_method_" + gatewayId).check();
                step = "złożenie zamówienia";
                page.locator("#place_order").click();
            } else {
                fillBlock();
                selectBlockPayment(gatewayId);
                step = "złożenie zamówienia";
                page.locator(".wc-block-components-checkout-place-order-button").click();
            }
            step = "oczekiwanie na przekierowanie do bramki";
            Pattern host = Pattern.compile(redirectHostRegex);
            page.waitForURL(url -> {
                try {
                    return host.matcher(java.net.URI.create(url).getHost()).find();
                } catch (RuntimeException e) {
                    return false;
                }
            }, new Page.WaitForURLOptions().setTimeout(45_000));
            return new LevelResult(Level.L3, Status.PASS, "przekierowanie dotarło do strony bramki (" + java.net.URI.create(page.url()).getHost() + ")", since(t0));
        } catch (PlaywrightException e) {
            // where it stopped and what the store said: a gateway error notice usually names the real cause
            return new LevelResult(Level.L3, Status.FAIL, "brak przekierowania do bramki (krok: " + step + ", strona: "
                    + safeHostPath() + checkoutNotice() + "): " + firstLine(e.getMessage()), since(t0));
        }
    }

    LevelResult l4(String checkout, String shippingMethodRegex, String lockerSelector) {
        long t0 = System.currentTimeMillis();
        try {
            addProductToCart();
            page.navigate(store.checkout());
            page.waitForSelector(CHECKOUT_ANY);
            // address first: shipping methods depend on it
            if ("classic".equals(checkout)) {
                fillClassic();
            } else {
                fillBlock();
            }
            Locator method = page.getByLabel(Pattern.compile(shippingMethodRegex)).first();
            method.check();
            page.waitForSelector(lockerSelector, new Page.WaitForSelectorOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(30_000));
            return new LevelResult(Level.L4, Status.PASS, "widget punktu odbioru widoczny po wyborze metody dostawy", since(t0));
        } catch (PlaywrightException e) {
            return new LevelResult(Level.L4, Status.FAIL, "widget punktu odbioru nie pojawił się: " + firstLine(e.getMessage()), since(t0));
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * L3/L4 start from a fresh cart: L2 places an order, which empties the cart, and WooCommerce then redirects
     * the checkout page away (the first Paynow L3 runs timed out on the home page because of this).
     */
    private void addProductToCart() {
        page.navigate(store.baseUrl() + "/?add-to-cart=" + store.productId());
        page.navigate(store.cart());
        page.getByText("Produkt testowy").first().waitFor();
    }

    private void fillClassic() {
        fill("#billing_first_name", "Jan");
        fill("#billing_last_name", "Testowy");
        fill("#billing_address_1", "ul. Testowa 1");
        fill("#billing_postcode", "00-001");
        fill("#billing_city", "Warszawa");
        fill("#billing_phone", "500100200");
        fill("#billing_email", "lab@example.invalid");
        page.waitForLoadState(LoadState.NETWORKIDLE);
    }

    /** Block checkout: ids differ slightly between versions, so each field is optional. */
    private Locator fillBlock() {
        fill("#email", "lab@example.invalid");
        for (String prefix : new String[]{"shipping", "billing"}) {
            fill("#" + prefix + "-first_name", "Jan");
            fill("#" + prefix + "-last_name", "Testowy");
            fill("#" + prefix + "-address_1", "ul. Testowa 1");
            fill("#" + prefix + "-postcode", "00-001");
            fill("#" + prefix + "-city", "Warszawa");
            fill("#" + prefix + "-phone", "500100200");
        }
        page.waitForLoadState(LoadState.NETWORKIDLE);
        return page.locator(".wp-block-woocommerce-checkout");
    }

    private void selectBlockPayment(String gatewayId) {
        Locator radio = page.locator("input[id^='radio-control-wc-payment-method-options-" + gatewayId + "']").first();
        radio.waitFor();
        radio.check();
    }

    private void fill(String selector, String value) {
        Locator l = page.locator(selector);
        if (l.count() > 0 && l.first().isVisible()) {
            l.first().fill(value);
        }
    }

    private void requireNoCriticalError(String where) {
        String body = page.content();
        if (body.contains("critical error on this website") || body.contains("Fatal error") || body.contains("Parse error")) {
            throw new CriticalError(where + ": strona pokazuje błąd krytyczny PHP");
        }
    }

    private String pluginAttributed(List<String> errors) {
        String marker = "/wp-content/plugins/" + pluginSlug + "/";
        return errors.stream().filter(e -> e.contains(marker)).findFirst().map(StoreBrowser::firstLine).orElse(null);
    }

    private String infoSuffix() {
        int other = pageErrors.size() + consoleErrors.size();
        return other == 0 ? "" : " (informacyjnie: " + pageErrors.size() + " wyjątków JS i " + consoleErrors.size() + " błędów konsoli niezwiązanych jednoznacznie z wtyczką)";
    }

    private static long since(long t0) {
        return System.currentTimeMillis() - t0;
    }

    /** Host and path of the current page, without the query string. */
    private String safeHostPath() {
        try {
            java.net.URI u = java.net.URI.create(page.url());
            return u.getHost() + (u.getPath() == null ? "" : u.getPath());
        } catch (RuntimeException e) {
            return "?";
        }
    }

    /** Text of a visible WooCommerce error notice (classic or block checkout), or "". */
    private String checkoutNotice() {
        try {
            Locator n = page.locator(".woocommerce-error, .woocommerce-NoticeGroup-checkout, "
                    + ".wc-block-components-notice-banner.is-error, .wc-block-store-notice.is-error");
            for (int i = 0; i < n.count(); i++) {
                if (n.nth(i).isVisible()) {
                    String t = n.nth(i).innerText().strip().replaceAll("\\s+", " ");
                    if (!t.isEmpty()) {
                        return ", komunikat sklepu: \"" + (t.length() > 300 ? t.substring(0, 300) + "…" : t) + "\"";
                    }
                }
            }
        } catch (RuntimeException e) {
            // diagnostics only
        }
        return "";
    }

    /** First meaningful line. Playwright for Java wraps errors as "Error {\n  message='…'", so take the message. */
    static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        String[] lines = s.strip().split("\\R");
        String l = lines[0];
        if (l.strip().equals("Error {")) {
            l = "";
            for (String x : lines) {
                String t = x.strip();
                if (t.startsWith("message='")) {
                    l = t.substring("message='".length());
                    break;
                }
            }
            if (l.isEmpty() && lines.length > 1) {
                l = lines[1].strip();
            }
        }
        return l.length() > 300 ? l.substring(0, 300) + "…" : l;
    }

    private static final class CriticalError extends RuntimeException {
        CriticalError(String m) {
            super(m);
        }
    }

    @Override
    public void close() {
        try {
            context.close();
        } catch (RuntimeException ignored) {
            // already closed
        }
    }
}
