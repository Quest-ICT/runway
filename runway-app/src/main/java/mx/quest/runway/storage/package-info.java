/**
 * The kernel's storage layer: typed YAML files under the external, operator-editable folder —
 * no database, no ORM. Each store reads and writes immutable records to a file with a
 * crash-safe atomic write, behind traversal-safe, tenant-scoped path handling, with bounded
 * Caffeine caches over the hot reads.
 *
 * <p>Design constraints realized here:
 * <ul>
 *   <li><b>Atomic writes</b> — write to a temp file, {@code fsync}, then atomic rename, so a
 *       crash never leaves a half-written store ({@link mx.quest.runway.storage.AtomicFiles}).</li>
 *   <li><b>Path safety</b> — identifiers are validated and every derived path is confined under
 *       its root; compound keys use a printable separator, never a control char
 *       ({@link mx.quest.runway.storage.PathSafety}).</li>
 *   <li><b>Forward/backward-compatible YAML</b> — unknown keys are tolerated, and the codec does
 *       not close the underlying stream, so many records can be streamed into one backup archive
 *       ({@link mx.quest.runway.storage.Yaml}).</li>
 *   <li><b>Single-JVM concurrency</b> — concurrent in-process writers to one file are serialized
 *       by a per-path lock ({@link mx.quest.runway.storage.YamlStore}).</li>
 * </ul>
 *
 * <p>This is single-JVM by design; a relational database is an out-of-kernel capability a plugin
 * brings, never a kernel dependency.
 */
package mx.quest.runway.storage;
