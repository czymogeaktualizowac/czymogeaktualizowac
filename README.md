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

Config lives in `config/` (`plugins.json`, `profiles.json`, `lab-images.json`, `embargo.json`, `site.json`). Output data lives in `data/` (a git
branch called `data` in CI; ignored on the code branch). Downloaded zips and WP-CLI are cached in `cache/`.

## Build and test

Requires JDK 21+ (built with 25), Maven, and for the lab Docker with Compose v2.

```
mvn -B package                 # unit tests + shaded jar
```

CI: `.github/workflows/ci.yml` runs the tests on every push and pull request to `main` (make its `test` job a required
check in the branch protection of `main`); the data pipeline also runs them before it touches the lab or the site.

## Commands

```
java -jar app/target/czymogeaktualizowac.jar [--root <repo>] <command>

validate                                check config/*.json (unknown keys, symbols, regexes, embargo decisions)
spike                                   probe every endpoint, write docs/spike0-report.md
watch [--backfill N] [--only a,b]       poll WordPress.org, verify zips, compute the forum signal
lab prepare                             fetch + verify WP-CLI, pull the pinned docker images, install the Playwright browser
lab pin-images [--php 8.1,8.2]          resolve the lab images to their current digests, rewrite config/lab-images.json
lab selftest [--php 8.3] [--checkout classic|block]
                                        prove the lab separates good from bad using fixture plugins
lab run --plugin <slug> [--version v] [--profiles P2,P3] [--checkout classic,block] [--mode solo|stack] [--force]
lab baseline [--profiles P3] [--only a,b]
publish [--out site-out] [--mark-published]
alerts [--digest] [--outbox dir]        draft alert e-mails against the last published state (default out/alerts/;
                                        CI writes them to data/outbox/ on the data branch)
```

Exit codes: `watch` fails only when no tracked plugin could be read (errors of single plugins are warnings, so one
plugin removed from WordPress.org does not stop the site); `lab run`/`baseline` store a lab ERROR as "result unknown"
and exit 0, so the remaining runs and the result upload still happen.

Typical local loop: `watch --backfill 5` → `lab baseline` → `publish --mark-published`, then open `site-out/`
with any static file server.

## What "tested" means

L0 install/activate, no PHP fatal, front pages 200 · L1 add to cart, classic or block checkout renders ·
L2 test order with cash on delivery completes · L3 gateway sandbox redirect · L4 locker/map widget.
**SKIPPED is never PASS**: L3/L4 need sandbox data (see `LabHints` in `core/Model.java` and `config/plugins.json`),
without it they are reported as "not tested". Every run first performs a **control pass without the plugin** (but with
its dependencies and, in mode `stack`, all other tracked plugins already installed, so their errors are part of the
baseline); if the store itself does not work, the run is an infrastructure ERROR.

- **ERROR is never counted against the plugin**: the verdict is "Wynik nieznany: błąd środowiska testowego" (result
  unknown), never "bad"/"warn", and no alert is sent. The pipeline re-queues releases whose result contains an ERROR.
- **A FAIL must reproduce**: a run with a FAIL is repeated once in a fresh store; only a repeated failure is stored as
  FAIL, an unconfirmed one is only noted in the run's `notes`.

See the site page "Jak testujemy" for the public wording.

## Reproducible lab environment

The database and WordPress images (one per PHP version) are pinned to digests in `config/lab-images.json`; the lab
refuses an image that is only a tag. A tag such as `wordpress:php8.3-apache` is rebuilt upstream every few weeks (new
OS packages, PHP patch release), so without the pin two runs a week apart could test on different systems. Each run
records what it used in `RunResult.environment`: both images, the WP-CLI version and every plugin active next to the
plugin under test (`slug@version`). `lab prepare` pulls each pinned image once per machine; every run on that machine
then starts from the same local image. To move to newer images: `lab pin-images`, review the diff, merge it via a PR.

## Embargo for failing results

A release with any FAIL level is held back from the site, feeds and alerts: its pages say
"Wyniki testów w weryfikacji" (results under review) and claim neither a pass nor a failure. The pipeline opens one
GitHub issue labelled `embargo` per held release (GitHub e-mails you; the run itself stays green). Then either:

- **a lab problem:** fix it and retest (`retest: slug@version` in Run workflow); a pass lifts the hold by itself;
- **a real failure:** tell the vendor, then add `{"slug": "…", "version": "…", "decision": "publish", "note": "…"}`
  to `config/embargo.json` (or `"hold"` to keep it back longer).

Without a decision the result is published automatically `holdDays` (7) after the failing run. The embargo only
controls what we publish: in this public repository the raw results stay readable on the `data` branch, in the
Actions logs and in the run artifacts.

## Security notes

- Plugin zips are untrusted: checked against the per-version checksums WordPress.org publishes (only code files decide),
  never extracted to the host, executed only inside throw-away containers. The lab only runs a zip whose sha256 equals
  the one the watcher recorded after that check (`data/releases.json`); a release the watcher could not download is
  `FETCH_FAILED`, retried on the next watch and never handed to the lab.
- Only sandbox credentials may be given to the lab (environment variables `LAB_SANDBOX_*`), never live ones.
- WP-CLI is pinned to a version and verified against the published sha512 before it is copied into a container.
- The site is static; plugin/forum text is HTML-escaped by the templates (covered by a test).

## Not done / needs a decision

- Mail provider, signup form URL, analytics snippet, privacy-policy placeholders (`config/site.json`, `privacy.jte`).
  The privacy and signup pages are deployed with every run, placeholders included: fill them before announcing the site.
  When a provider sends mail, `publish --mark-published` must move the baseline only after a successful send
  (today drafts are kept in `data/outbox/`, nothing is sent).
- **Policy decision:** failing results are auto-published after `holdDays` without a human decision, and raw results
  are public on the `data` branch at once, while "Jak testujemy" says vendors are told before publication. Decide
  whether publication of a FAIL needs an explicit `publish` decision, and whether raw results should stay public.
- Sandbox accounts for L3/L4 and the `gatewayId`/`optionName`/selector hints per plugin (no plugin has a
  `lockerSelector` yet, so L4 never runs).
- Refresh the pinned lab images on a schedule (e.g. monthly: `lab pin-images`, then a PR), so security fixes in the
  base images are picked up on purpose; a scheduled workflow could open that PR automatically.
- Create the `data` branch and Pages, verify Actions quota (Phase 0).
- Spike 0 item 5 (sandbox availability) and the profile choice check against the crawl data.
- Signed feed and the WordPress plugin (Gate 2 of the plan).

On this machine's sandboxed shell, Java needs `-Djdk.net.unixdomain.tmpdir=<dir> -Djava.io.tmpdir=<dir>` (see `docs/spike0-findings.md`).
