# Runway Lucene Libraries Provider — design & reference

> A **code-less library-provider plugin**: a `plugin.yml` with **no `mainClass`** plus `lib/*.jar`, whose only
> job is to place Apache Lucene **once** on the shared plugin classloader for other plugins to consume. Grounded
> in the Runway kernel as built — the reference code-less library provider.

## 1. What it is

`lucene-provider` ships **no Java code**. Its bundle is exactly:

```
lucene-provider/
├── plugin.yml          # id, name, version — NO mainClass
└── lib/
    ├── lucene-core-10.4.0.jar              # index + queries + directories + BM25 (the principal library)
    ├── lucene-analysis-common-10.4.0.jar   # per-language analyzers + stemmers + token filters
    ├── lucene-highlighter-10.4.0.jar       # UnifiedHighlighter → <mark>-wrapped query-focused snippets
    ├── lucene-memory-10.4.0.jar            # in-memory index the highlighter needs (transitive)
    └── lucene-queries-10.4.0.jar           # extra query support the highlighter needs (transitive)
```

The kernel scans `lib/` one level deep and loads every jar into the **one** shared child classloader, so the
Lucene stack is available deployment-wide as a single copy. A code-less plugin (no `mainClass`) is first-class:
nothing is registered into the Spring context; it only contributes libraries.

## 2. Why a standalone provider (a deliberate divergence)

The general rule is "a bundled library stays **private** inside its one consumer until a **second** consumer
exists, then it graduates to a provider plugin." Here Lucene is a **standalone provider** even though
`search-lucene` is still the only consumer. The reasoning holds regardless of
consumer count: the shared classloader permits exactly **one** version of any library deployment-wide, so Lucene
belongs in **one agreed place** that every full-text plugin binds to with `requires:` — never a private copy per
plugin that could clash. (`search-lucene` binds here.)

## 3. Version tracks the principal library — literally

The plugin `version` is **`10.4.0`**: the plugin version *is* the Lucene version. Consumers gate on it with the
**exact** operator:

- `requires: [ "lucene-provider == 10.4.0" ]` — the only correct form for a Lucene consumer.

Why `==` and not `>=`: a consumer is **compiled against these binaries**. Lucene does not promise cross-version
binary stability for the internal classes plugins touch (index formats, analysis, query internals), so a
consumer must run against the very version it was built with. `>=` would let a newer, binary-incompatible Lucene
satisfy the gate and blow up at wire/run time.

Bumping: change `lucene.version` in `pom.xml`, then set **both** the pom `<version>` and `plugin.yml` `version:`
to the new Lucene version (identical), and rebuild. Every `==` consumer must be **recompiled and re-cut** — the
deliberate cost of one shared Lucene copy.

## 4. Consuming it (the `provided` rule)

A consumer plugin (see [`../../search-lucene`](../../search-lucene) for the worked example):

1. `plugin.yml`: `requires: [ "lucene-provider == 10.4.0" ]` — a **hard load gate**. If the provider is missing,
   disabled, or a different version, the consumer is **not loaded** (skipped with a reason; "Blocked" in the
   management UI), so it never wires against a class the classloader can't supply.
2. `pom.xml`: the Lucene artifacts at **`provided`** scope (compile-only). **Never** bundle Lucene into the
   consumer's own `lib/` — that would be a second copy on the shared classloader.
3. Runtime: plain Lucene (`IndexWriter`, `IndexSearcher`, the query classes, the analyzers, the
   `UnifiedHighlighter`). All five bundled jars are on the shared classloader.

## 5. Build & install

```bash
export JAVA_HOME=<path to a JDK 25>
mvn -DskipTests package
#   → target/lucene-provider/                          the install-ready FOLDER (plugin.yml + lib/)
#   → target/runway-plugin-lucene-provider-10.4.0.zip   the UI-uploadable ARCHIVE
```

The first build downloads its Maven dependencies, the Lucene jars included. It needs nothing of Runway in `~/.m2`.

- **Filesystem:** `cp -r target/lucene-provider <runway-external>/plugins/` then **restart**.
- **Management UI:** `/__app/plugins` → install → upload the `.zip`, then restart.

## 6. Verify

There is nothing to unit-test in a code-less bundle. Correctness = "the descriptor has no `mainClass` and the
five Lucene jars are present in `lib/`", which the `package` output shows. Functionally it is verified **through a
consumer**: install `lucene-provider` + `search` + `search-lucene`, boot the host in ACTIVE mode, and confirm the
startup log shows all three plugins `registered, 0 blocked` (i.e. `search-lucene`'s
`requires: lucene-provider == 10.4.0` was satisfied) and that `/search` returns Lucene-ranked results.

## 7. Pitfalls

- **A code-less plugin has NO `mainClass` and NO thin jar** — do not add one. The pom packaging is `pom` and
  produces only the assembled bundle.
- **One Lucene version deployment-wide.** Never let a second plugin bundle Lucene privately; add it as a
  `requires:` consumer of this provider instead.
- **Keep the plugin version == the Lucene version** and consumers pinned with `==`. A drift defeats the whole
  "version tracks the principal library" contract, and a `>=` consumer would risk a binary-incompatible Lucene.
- **The `jdk.incubator.vector` log line is expected.** Lucene logs `Java vector incubator module is not readable.
  For optimal vector performance, pass '--add-modules jdk.incubator.vector' to enable Vector API.` once per start.
  Runway leaves the flag off: its search uses no vectors, and the module is an incubating JDK API that
  makes the JVM warn at every start. See the lucene-provider [README](../README.md#log-messages-you-can-ignore) and
  search-lucene's [design doc §8](../../search-lucene/docs/SEARCH-LUCENE-PLUGIN.md#8-log-messages-you-can-ignore).
