# Runway Lucene Libraries Provider (`lucene-provider`)

A **code-less library-provider plugin** for [Runway](../README.md). It ships **no Java code and no `mainClass`** —
only the **Apache Lucene** stack under `lib/`, so it loads **once** onto the shared plugin classloader as the
**single, deployment-wide** copy of Lucene. Any plugin that needs full-text search (e.g.
[`search-lucene`](../search-lucene)) compiles Lucene as `provided` and declares a hard dependency on this provider.

- **Plugin id:** `lucene-provider` — install folder `external/plugins/lucene-provider/`
- **Version:** `10.4.0` — the plugin version **IS** the Lucene version (the *version tracks the principal
  library* convention taken literally: a `lucene-provider 10.4.0` *is* Apache Lucene 10.4.0).
- **Bundled jars (`lib/`):**
  - `lucene-core-10.4.0.jar` — the Lucene core (index, `IndexWriter`/`IndexSearcher`, the query classes:
    `TermQuery`, `PhraseQuery`, `FuzzyQuery`, `BooleanQuery`, `ConstantScoreQuery`, `BoostQuery`; the
    directories: `ByteBuffersDirectory` (heap) and `MMapDirectory` (disk); BM25 similarity).
  - `lucene-analysis-common-10.4.0.jar` — analysis: the per-language `Analyzer`s and stemmers
    (`EnglishAnalyzer`, `SpanishAnalyzer`, …), `StandardAnalyzer`, `CustomAnalyzer`, the token-filter factories.
  - `lucene-highlighter-10.4.0.jar` — query-focused highlighting (`UnifiedHighlighter`,
    `DefaultPassageFormatter`) for `<mark>`-wrapped result snippets.
  - `lucene-memory-10.4.0.jar` — the in-memory index used by the highlighter (transitive of highlighter).
  - `lucene-queries-10.4.0.jar` — extra query support used by the highlighter (transitive of highlighter).

## Why a standalone provider

Lucene ships as a **first-class shared provider** rather than following the default
"a bundled library stays private until a *second* consumer exists" rule. The reasoning: the shared plugin
classloader holds exactly **one** version of any library deployment-wide, so every plugin that needs full-text
search binds to **one agreed** Lucene version here, never its own private (and possibly clashing) copy.

`search-lucene` (currently the only consumer) binds to this provider: its `pom.xml` marks Lucene `provided` and
it declares:

```yaml
requires: [ "lucene-provider == 10.4.0" ]
```

`==` (exact) rather than `>=` because a consumer is **compiled against these binaries** — a Lucene major/minor
can move or rename internal classes, so a consumer must run against the very version it was built with. If
`lucene-provider` is missing, disabled, or a different version, the consumer is **not loaded** (skipped with a
precise reason and shown "Blocked" in the management UI) — so it never wires against a class the classloader
can't supply.

## Consuming it (for plugin authors)

1. In your `plugin.yml`: `requires: [ "lucene-provider == 10.4.0" ]` (pin the exact version you compiled against).
2. In your `pom.xml`: depend on the Lucene artifacts you use (`org.apache.lucene:lucene-core`,
   `lucene-analysis-common`, `lucene-highlighter`, …) at **`provided`** scope — the provider supplies them at
   runtime. **Never** bundle Lucene into your own plugin's `lib/` (that would be a second copy on the shared
   classloader).
3. At runtime, use plain Lucene (`IndexWriter`, `IndexSearcher`, the query classes, the analyzers, the
   `UnifiedHighlighter`). All five bundled jars are on the shared classloader.

## Build

```bash
export JAVA_HOME=<path to a JDK 25>
mvn -DskipTests package
#   → target/lucene-provider/                            the install-ready FOLDER (plugin.yml + lib/)
#   → target/runway-plugin-lucene-provider-10.4.0.zip     the UI-uploadable ARCHIVE (/__app/plugins)
```

The first build downloads its Maven dependencies, the Lucene jars included. It needs nothing of Runway in `~/.m2`.

- **Filesystem install:** `cp -r target/lucene-provider <runway-external>/plugins/` then **restart** the host.
- **Management-UI install:** `/__app/plugins` → install → upload the `.zip`, then restart.

There are no tests: a code-less bundle has nothing to unit-test. Its correctness is "the five jars are present
and the descriptor has no `mainClass`" — verified by the `package` output above.

## Bumping the Lucene version

Edit `pom.xml`: change `lucene.version`, then set **both** the `<version>` element **and** `plugin.yml`'s
`version:` to the new Lucene version (they must stay identical). Rebuild. Every consumer pinned with
`== <version>` must be **recompiled and re-cut** against the new version — that is the deliberate cost of the
exact pin, and the price of a single shared Lucene copy.

## Log messages you can ignore

Once per start of the host, the first time a consumer (`search-lucene`) uses Lucene, the log shows:

```
WARN … o.a.l.i.v.VectorizationProvider : Java vector incubator module is not readable. For optimal vector performance, pass '--add-modules jdk.incubator.vector' to enable Vector API.
```

It comes from the Lucene jars in this bundle. Nothing is wrong. **Leave it off**:
the flag only speeds up Lucene's vector maths, which serves mainly k-nearest-neighbour vector search, and Runway's
search is keyword search over small indexes; and `jdk.incubator.vector` is an incubator module, so the JVM would
print `WARNING: Using incubator modules: jdk.incubator.vector` at every start and depend on a JDK API that is not
final. To turn it on anyway, use a `JAVA_OPTS` drop-in, never the main unit: the steps are in
[`search-lucene`'s README](../search-lucene/README.md#log-messages-you-can-ignore).

## License

GNU Affero General Public License v3.0 with an additional permission for plugins (`LICENSE`, `LICENSE-EXCEPTION`),
copyright Quest ICT SAS de CV. The permission means that a plugin which uses only the published SPI, including
search's own SPI for engines, may carry any license.

The zip bundles Apache Lucene, which stays under the Apache License 2.0: each Lucene jar in `lib/` carries its own
`LICENSE` and `NOTICE` (under `META-INF/`). The AGPL applies to this plugin's own packaging.
