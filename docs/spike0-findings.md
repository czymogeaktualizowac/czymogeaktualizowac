# Spike 0 — findings (6 Nov 2026)

Answers to the seven questions of Phase 1 of the Door 1 plan. Raw per-plugin numbers: `docs/spike0-report.md`
(regenerate with `java -jar app/target/czymogeaktualizowac.jar spike`).

| # | Question | Answer |
| --- | --- | --- |
| 1 | What comes from the plugins API vs from the zip? | **API** (`api.wordpress.org/plugins/info/1.2/`): version, WordPress "tested up to", `requires_php`, active installs (rounded), support thread counts, the version list. **Zip** (`downloads.wordpress.org/plugin/<slug>.<version>.zip`): `WC tested up to`, `WC requires at least`, `Requires Plugins`, license, changelog. `Requires PHP` is sometimes only in `readme.txt` (InPost), so the parser falls back to the readme. Many plugins do not declare `WC tested up to` at all (InPost, Omnibus, GlobKurier, imoje, TrustMate). |
| 2 | Per-version checksums? | **Yes**: `downloads.wordpress.org/plugin-checksums/<slug>/<version>.json` (sha256 per file). Two real-data lessons: `readme.txt` is routinely different from the published checksum and translations (`.po/.mo`) move to translate.wordpress.org, so **only code files** (php/js/…) decide the status; and one `imoje` code file differs from its checksum (kept as a *warning*, never an accusation: usually a packaging artefact). A line-ending-only difference is accepted. |
| 3 | Support-forum signal? | **Yes**: `wordpress.org/support/plugin/<slug>/feed/` is an RSS feed of the latest ≤30 topics (fewer for quiet plugins), plus a reviews feed. It does not reach back for busy plugins, so older releases of busy plugins get `UNKNOWN`, not `NONE`. Backtest on Furgonetka: 1.8.1 → 4 topics in 7 days (ELEVATED), 1.9.0 → 1 topic that names the version ("Blad po aktualizacji do 1.9.0", LOW). **Note:** the research text places 1.9.0 in Mar–Apr 2026; WordPress.org's SVN says 1.9.0 was tagged 19 Jan 2026 (the March/April date matches 1.9.3). |
| 4 | Release frequency? | From SVN tag dates over the last 12 months: Furgonetka 1.3/month, InPost 2.1, Flexible Shipping 3.0, Flexible Invoices 3.0, KSeF add-on 3.3, Paynow 0.3, others 0.4–1.3. **Tracked list ≈ 18 releases/month.** WooCommerce: 103 stable tags in 12 months (9 feature releases x.y.0; betas/RCs weeks ahead); WordPress 7.1.4 current, 7.2-beta4 in development; PHP 8.5.12 current. Release dates are the creation time of the SVN tag via WebDAV `PROPFIND` on `plugins.svn.wordpress.org/<slug>/tags/` (Trac returns 403; the API has no per-version dates). |
| 5 | Sandboxes? | **Not yet answered** (needs accounts, Phase 0). L3/L4 are implemented but report SKIPPED without sandbox data. Przelewy24 and Tpay do not publish official plugins on WordPress.org, so they are not tracked. |
| 6 | Plugin list | 17 free WordPress.org plugins (`config/plugins.json`), all answer the API. Slug notes: PayU → `woo-payu-payment-gateway` (confirm it is PayU Polska's plugin), Autopay → `platnosci-online-blue-media` and `pay-wp`, Paynow → `pay-by-paynow-pl`, `flexible-shipping` added (100k installs, Polish vendor). |
| 7 | GitHub Actions quota, Omnibus licence, profiles | Quota **not verified from the terms** (do it in Phase 0). Omnibus: **GPLv3 or later** (main-file header of 3.0.5; the GitHub repo has no LICENSE file, the plugin header is what WordPress.org relies on), so forking/co-maintaining is legally open. Profile choice against crawl data not done (needs `research/phase1` crawl fields). |

## Lab results of the first local run (6 Nov 2026)

- `lab selftest` (fixture plugins) passes for classic **and** block checkout on PHP 8.3: harmless → L2, PHP fatal on load → L0 FAIL,
  checkout-only breaker → L0 PASS / L1 FAIL.
- Real releases, all L0–L2 PASS: Furgonetka 1.9.10 (P1 classic, P3 classic+block, P3 full stack classic, P4 block), InPost 2.0.0,
  Flexible Shipping 6.13.7, Paynow 2.5.13, Omnibus 3.0.5 (P3 classic+block; Omnibus also P4). L3/L4 report SKIPPED (no sandbox data).
- **Attribution bug found by a real run and fixed:** the first P4 run (WordPress 7.2-beta4 + WooCommerce 11.3.0-beta.2) reported
  Furgonetka as L0 FAIL because of a PHP fatal inside WordPress core's beta (`WP_AI_Client_Builder` not found). The same fatal appears
  without any plugin, so L0 now ignores errors present in the control pass and attributes the rest by stack trace (`DebugLog`, unit-tested).
  Worth remembering: edge-profile failures must always be checked against the control pass before they are published.

## Environment notes (this machine)

- On this machine's sandboxed shell, Java's `HttpClient` fails with "Unable to establish loopback connection" unless
  `-Djdk.net.unixdomain.tmpdir=<writable dir> -Djava.io.tmpdir=<writable dir>` is set. A normal shell and CI do not need it.
- Docker is not on the PATH of shells started before it was installed; open a new shell.
