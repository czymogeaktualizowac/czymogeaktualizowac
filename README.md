# czymogeaktualizowac

Independent, automated testing of Polish WooCommerce plugin releases, published as a static status site
("Czy mogę zaktualizować?") with alerts. This is **Door 1** of the ecosystem plan
(`research/Door 1 — Implementation Plan.md`): no software on the customer's store, no backend server.

```
WordPress.org (plugin API, zips, SVN tags, support forums) · WP / WooCommerce / PHP releases
      │ public, read-only
      ▼
 watcher ──► data/releases.json ──► lab (docker + Playwright) ──► data/results/<slug>/<version>.json
                                                  │
                                                  ▼
                                         publisher ──► site-out/ (static site, RSS, sitemap, feed.json)
                                                  └──► alert drafts (out/alerts/), sent by a mail provider later
```

| Module | What it does |
| --- | --- |
| `core` | Model, HTTP client (polite, identifying UA), WordPress.org client, zip header + checksum parsing, version ordering, forum signal, JSON store |
| `watcher` | `watch` (releases, verified zips, forum signal, ecosystem), `spike` (Spike 0 report) |
| `lab` | Disposable WooCommerce stores in docker compose (WordPress + MariaDB, PHP 8.1–8.5), WP-CLI, Playwright; levels L0–L4 |
| `publisher` | Static site (JTE templates, Polish), per-plugin and per-release pages, compatibility pages, RSS, sitemap, unsigned `feed/v1/feed.json`, alert planner |
| `app` | One picocli entry point, shaded into `app/target/czymogeaktualizowac.jar` |

Config lives in `config/` (`plugins.json`, `profiles.json`, `site.json`). Output data lives in `data/` (a git
branch called `data` in CI; ignored on the code branch). Downloaded zips and WP-CLI are cached in `cache/`.

## Build and test

Requires JDK 21+ (built with 25), Maven, and for the lab Docker with Compose v2.

```
mvn -B package                 # unit tests + shaded jar
```

## Commands

```
java -jar app/target/czymogeaktualizowac.jar [--root <repo>] <command>

spike                                   probe every endpoint, write docs/spike0-report.md
watch [--backfill N] [--only a,b]       poll WordPress.org, verify zips, compute the forum signal
lab prepare                             fetch + verify WP-CLI, pull docker images, install the Playwright browser
lab selftest [--php 8.3] [--checkout classic|block]
                                        prove the lab separates good from bad using fixture plugins
lab run --plugin <slug> [--version v] [--profiles P2,P3] [--checkout classic,block] [--mode solo|stack] [--force]
lab baseline [--profiles P3] [--only a,b]
publish [--out site-out] [--mark-published]
alerts [--digest]                       draft alert e-mails against the last published state (out/alerts/)
```

Typical local loop: `watch --backfill 5` → `lab baseline` → `publish --mark-published`, then open `site-out/`
with any static file server.

## What "tested" means

L0 install/activate, no PHP fatal, front pages 200 · L1 add to cart, classic or block checkout renders ·
L2 test order with cash on delivery completes · L3 gateway sandbox redirect · L4 locker/map widget.
**SKIPPED is never PASS**: L3/L4 need sandbox data (see `LabHints` in `core/Model.java` and `config/plugins.json`),
without it they are reported as "not tested". Every run first performs a **control pass without the plugin**; if the
store itself does not work, the run is an infrastructure ERROR and is never counted against the plugin.
See the site page "Jak testujemy" for the public wording.

## Security notes

- Plugin zips are untrusted: checked against the per-version checksums WordPress.org publishes (only code files decide),
  never extracted to the host, executed only inside throw-away containers.
- Only sandbox credentials may be given to the lab (environment variables `LAB_SANDBOX_*`), never live ones.
- WP-CLI is pinned to a version and verified against the published sha512 before it is copied into a container.
- The site is static; plugin/forum text is HTML-escaped by the templates (covered by a test).

## Not done / needs a decision

- Mail provider, signup form URL, analytics snippet, privacy-policy placeholders (`config/site.json`, `privacy.jte`).
- Sandbox accounts for L3/L4 and the `gatewayId`/`optionName`/selector hints per plugin.
- Pin GitHub Actions to commit SHAs, create the `data` branch and Pages, verify Actions quota (Phase 0).
- Spike 0 item 5 (sandbox availability) and the profile choice check against the crawl data.
- Signed feed and the WordPress plugin (Gate 2 of the plan).

On this machine's sandboxed shell, Java needs `-Djdk.net.unixdomain.tmpdir=<dir> -Djava.io.tmpdir=<dir>` (see `docs/spike0-findings.md`).
