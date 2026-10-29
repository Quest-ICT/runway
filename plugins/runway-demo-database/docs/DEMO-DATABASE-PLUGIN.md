# The guestbook example — a walkthrough

Each class shows one thing a database consumer does.

| Class | Shows |
|---|---|
| `GuestbookConsumer` | The one `DatabaseConsumer` bean: the plugin id and the `SITE` scope. |
| `DemoDatabaseConfig` | `Databases` injected by type from the database plugin; every bean named `runwayDemoDatabase…`. |
| `GuestbookStore` | The only SQL: `databases.of(consumer)` on first use; `nextValue` in the insert and `lastValue` after it in one `transaction`; paging with `limitOffset` in a `read`; the row cap applied in the insert's transaction. The same SQL on both products. |
| `GuestbookEndpoints` | An anonymous `POST` with a honeypot, length caps, the site's CAPTCHA (`CaptchaService`, when the site has one) and a per-address window; the site always from the request; `/guestbook/entries.json` with `Cache-Control: no-store`; `available()` checked and failures answered as "unavailable", never as an error page. |
| `GuestbookWidget` | A static shell — the form and an empty list — so the page's output never depends on database rows and stays cacheable. |
| `static/guestbook.js` | Fills the list from the endpoint, inserting text, never HTML. |
| `GuestbookConsoleController` | `currentSite()` in the site console; delete as a CSRF-checked `POST`. |
| `src/main/resources/db/runway-demo-database/…` | The migration scripts: an `index` and `V1__guestbook.sql` per product, every object starting with `runway_demo_database__`, the same table on both. |

**Why the list is not in the page.** The kernel caches rendered pages and answers `304` while a page's ETag holds. A
widget that read rows would freeze a page with its first entries. The verify script checks the design: after a new
entry, the page still answers `304`, while the entries endpoint answers `200` with the entry.

**What travels.** The entries are per site, so they are in the site's full backups and restore with it, on either
product. A transfer copy carries none: they are visitors' personal data, which a transfer may leave behind.

**Without the database.** Without the `database` plugin, this one is Blocked with the kernel's reason and its routes
do not exist. With the plugin but no reachable server, the page still renders, the list says "This example needs the
database", and signing answers "unavailable".

**Tests.** `GuestbookEndpointsTest` (the guards answer before the database is touched), `DemoDatabaseConfigTest`
(wiring, the widget's shell, linkage: only the Runway SPI and the database SPI), `GuestbookStoreRealServerTest` (the
SQL and the scripts on real PostgreSQL and MariaDB, through the real database plugin and providers), and
`scripts/verify-against-host.sh` (25 checks in a real Runway). `GuestbookStoreRealServerTest` uses the database
plugin's internal classes, which are AGPL-3.0 and not covered by the plugin permission: a copy of this example under
other terms should delete it (and its two test-scope provider dependencies) and keep the end-to-end script.
