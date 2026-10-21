package mx.quest.runway.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class YamlStoreTest {

    record Person(String name, int age, List<String> roles) {
    }

    record Simple(String name, int count) {
    }

    record Timed(String id, Instant at) {
    }

    private final YamlStore store = new YamlStore();

    @Test
    void roundTrips(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("person.yml");
        Person original = new Person("Ada", 36, List.of("admin", "editor"));
        store.write(file, original);
        Optional<Person> read = store.read(file, Person.class);
        assertTrue(read.isPresent());
        assertEquals(original, read.get());
    }

    @Test
    void readingAbsentFileIsEmpty(@TempDir Path dir) throws IOException {
        assertTrue(store.read(dir.resolve("missing.yml"), Person.class).isEmpty());
    }

    @Test
    void unknownKeysAreTolerated(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("simple.yml");
        Files.writeString(file, "name: hi\ncount: 5\nlegacyField: ignored\n", StandardCharsets.UTF_8);
        Optional<Simple> read = store.read(file, Simple.class);
        assertTrue(read.isPresent());
        assertEquals(new Simple("hi", 5), read.get());
    }

    @Test
    void instantRoundTrips(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("timed.yml");
        Timed original = new Timed("e1", Instant.ofEpochSecond(1_700_000_000L));
        store.write(file, original);
        assertEquals(original, store.read(file, Timed.class).orElseThrow());
    }

    @Test
    void existsAndDelete(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("d.yml");
        assertFalse(store.exists(file));
        store.write(file, new Simple("x", 1));
        assertTrue(store.exists(file));
        assertTrue(store.delete(file));
        assertFalse(store.exists(file));
        assertFalse(store.delete(file));
    }

    /**
     * The codec must not close the underlying stream, so many records stream into one
     * multi-entry ZIP without the stream dying after the first entry.
     */
    @Test
    void codecDoesNotCloseTheUnderlyingStream() throws IOException {
        List<Person> people = List.of(
                new Person("A", 1, List.of("x")),
                new Person("B", 2, List.of("y", "z")),
                new Person("C", 3, List.of()));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (int i = 0; i < people.size(); i++) {
                zip.putNextEntry(new ZipEntry("person-" + i + ".yml"));
                Yaml.write(zip, people.get(i)); // must NOT close zip
                zip.closeEntry();
            }
        }

        List<Person> readBack = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            while (zip.getNextEntry() != null) {
                readBack.add(Yaml.read(zip, Person.class)); // must NOT close zip
            }
        }
        assertEquals(people, readBack);
    }

    @Test
    void concurrentWritesToSameFileAreSerializedAndNeverCorrupt(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("hot.yml");
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int n = i;
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    store.write(file, new Simple("writer", n));
                } catch (Throwable e) {
                    failures.add(e);
                }
            });
            workers.add(t);
            t.start();
        }
        start.countDown();
        for (Thread t : workers) {
            t.join();
        }
        assertTrue(failures.isEmpty(), () -> "writers failed: " + failures);
        // The file is one complete, valid record (one writer won) — never a corrupted interleave.
        Simple result = store.read(file, Simple.class).orElseThrow();
        assertNotNull(result);
        assertEquals("writer", result.name());
        assertTrue(result.count() >= 0 && result.count() < threads);
    }
}
