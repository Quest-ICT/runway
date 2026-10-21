# Runway Search — Lucene engine

A Lucene-backed `SiteSearchEngine` for the [`search`](../search) plugin: a full-text index over the search
plugin's `SearchCorpus` — every published page and content-source item the site has not excluded
from search — each item's
**title, description and body** with **per-locale stemming analysis**, a **tiered constant-score** relevance
model (exact phrase → near phrase → all terms → any term), **fuzzy** (typo-tolerant) matching, and query-focused
`<mark>` highlighting. Install it alongside `search` and it **transparently upgrades** the search box + `/search`
results page — no change to that query surface.

- **Plugin id:** `search-lucene` (v`3.3.0`) — install folder `external/plugins/search-lucene/`
- **Requires:** a Runway kernel with SPI `1.0.0` or later (`requiresSpi: ">=1.0.0"`, a floor every `1.x` kernel meets), `search >= 2.2.0` **and**
  `lucene-provider == 10.4.0`. With search 2.1.x, keep 3.1.0; with search 2.0.0, keep 3.0.0.
- **Links:** only the Runway SPI (`mx.quest.runway.spi`) and the search SPI (`mx.quest.search.spi`)
- **Java package:** `mx.quest.searchlucene` · **Lucene:** supplied by the [`lucene-provider`](../lucene-provider)
  plugin (compiled `provided`, **not** bundled here)

## Why a separate plugin

`search` splits search into a **query surface** (box + `/search`) and a swappable **engine** (`SiteSearchEngine`).
The built-in engine matches with plain substrings; this one indexes with Lucene analysis and
ranks by relevance, registering at a higher `priority()` so it wins wherever installed. It indexes the same
`SearchCorpus` the built-in engine does — so the `search` plugin's per-site **`exclude`** list applies without this
plugin reading it — drops a site's index the moment the kernel announces a change (a `SiteEventListener`), and
implements the SPI's manual **reindex** hook so the operator's rebuild-index button rebuilds this index too. Lucene itself
lives in the shared [`lucene-provider`](../lucene-provider) plugin (one copy deployment-wide). See
[`docs/SEARCH-LUCENE-PLUGIN.md`](docs/SEARCH-LUCENE-PLUGIN.md) and [`CHANGELOG.md`](CHANGELOG.md).

## Tuning (per-site `siteSettings:`)

`fuzzy`, `fuzzyBoost`, `titleBoost`, `descriptionBoost`, and the four tier scores (`tierPhraseTitle`,
`tierPhraseBody`, `tierNearPhrase`, `tierAllTerms`). App-level `settings:` choose index `storage` (heap/disk) and
the disk `indexRoot`. Defaults reproduce the historical ranking, so an unconfigured site behaves as before.

## Build

```bash
export JAVA_HOME=/path/to/jdk-25
(cd ../search && mvn -DskipTests install)  # the jar this compiles against, into ~/.m2
mvn verify           # 72 unit tests + the package
#   → target/runway-plugin-search-lucene.jar        (thin jar — NO bundled Lucene)
#     target/search-lucene/                          (folder — cp -r into <external>/plugins/ + restart)
#     target/runway-plugin-search-lucene-3.3.0.zip   (upload at /__app/plugins → install)
JAVA_HOME=/path/to/jdk-25 HOST_JAR=/path/to/runway-app-<ver>-exec.jar VERIFY_TMPDIR=/some/scratch scripts/verify-against-host.sh
```

The build compiles against the search plugin, `provided` from `~/.m2`: install it first with
`mvn -DskipTests install` in `../search` (the verify script does). The first build downloads its Maven dependencies,
and needs the `runway-spi` jars installed in `~/.m2` as the
[authoring guide §2](../docs/PLUGIN-AUTHORING-GUIDE.md#2-prerequisites) describes. This plugin itself is never installed. At runtime the deployment must also carry the `search` plugin (2.2.0 or later) **and**
the `lucene-provider` plugin (the Lucene binaries): drop all three folders into `external/plugins/`.

## Log messages you can ignore

### `Java vector incubator module is not readable`

Once per start of the host, the first time Lucene is used (usually the first search, on a request thread), the log
shows:

```
WARN … o.a.l.i.v.VectorizationProvider : Java vector incubator module is not readable. For optimal vector performance, pass '--add-modules jdk.incubator.vector' to enable Vector API.
```

Lucene prints it (the library the `lucene-provider` plugin supplies). Nothing is wrong. **Leave it off**, for three
reasons.

- The flag makes Lucene's vector maths faster. Lucene needs that mainly for k-nearest-neighbour *vector* search.
  Runway's search is keyword search (terms, phrases, typo-tolerant matches) and uses no vectors.
- A site's index is small (its published pages and items), so there is no time to win.
- `jdk.incubator.vector` is an *incubator* module, a JDK API that is not final. With it, the JVM prints
  `WARNING: Using incubator modules: jdk.incubator.vector` at every start, and a later JDK may change or drop the
  module.

If you want it anyway, add `--add-modules jdk.incubator.vector` to `JAVA_OPTS` in a systemd **drop-in** under
`/etc/systemd/system/runway.service.d/`. Never edit the main unit, `runway.service`: the installer rewrites it at
every upgrade. `Environment=` sets the whole variable, so the line must also carry your heap flags:

```ini
# /etc/systemd/system/runway.service.d/20-heap.conf
[Service]
Environment="JAVA_OPTS=-Xms256m -Xmx1g --add-modules jdk.incubator.vector"
```

Then run `sudo systemctl daemon-reload && sudo systemctl restart runway`. The installer's `--heap` rewrites
`20-heap.conf` whole and drops the flag, so add it again after each `--heap`. Runway's administration manual
(§15, *Memory tuning*) explains the drop-ins.

## License

GNU Affero General Public License v3.0 with an additional permission for plugins (`LICENSE`, `LICENSE-EXCEPTION`),
copyright Quest ICT SAS de CV. The permission means that a plugin which uses only the published SPI, including
search's own SPI for engines, may carry any license.
