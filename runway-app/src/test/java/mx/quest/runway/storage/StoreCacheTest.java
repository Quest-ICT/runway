package mx.quest.runway.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StoreCacheTest {

    @Test
    void loadsOnceThenServesFromCache() {
        StoreCache<String, String> cache = new StoreCache<>(100);
        AtomicInteger loads = new AtomicInteger();
        assertEquals("v:k", cache.get("k", key -> {
            loads.incrementAndGet();
            return "v:" + key;
        }));
        assertEquals("v:k", cache.get("k", key -> {
            loads.incrementAndGet();
            return "v:" + key;
        }));
        assertEquals(1, loads.get(), "loader runs only on the first miss");
    }

    @Test
    void invalidateForcesReload() {
        StoreCache<String, String> cache = new StoreCache<>(100);
        AtomicInteger loads = new AtomicInteger();
        cache.get("k", key -> "v" + loads.incrementAndGet());
        cache.invalidate("k");
        cache.get("k", key -> "v" + loads.incrementAndGet());
        assertEquals(2, loads.get());
    }

    @Test
    void invalidateAllClearsEverything() {
        StoreCache<String, String> cache = new StoreCache<>(100);
        cache.get("a", k -> "1");
        cache.get("b", k -> "2");
        cache.invalidateAll();
        assertNull(cache.peek("a"));
        assertNull(cache.peek("b"));
    }

    @Test
    void nullLoadResultIsNotCached() {
        StoreCache<String, String> cache = new StoreCache<>(100);
        AtomicInteger loads = new AtomicInteger();
        assertNull(cache.get("k", key -> {
            loads.incrementAndGet();
            return null;
        }));
        assertNull(cache.get("k", key -> {
            loads.incrementAndGet();
            return null;
        }));
        assertEquals(2, loads.get(), "a null result must not be cached");
    }

    @Test
    void peekDoesNotLoad() {
        StoreCache<String, String> cache = new StoreCache<>(100);
        assertNull(cache.peek("k"));
        cache.get("k", key -> "loaded");
        assertEquals("loaded", cache.peek("k"));
    }

    @Test
    void distinctTenantKeysAreIsolatedAndInvalidateIsPerKey() {
        // The multi-tenancy boundary at the cache layer: callers key by compoundKey(siteId, slug). Two sites'
        // entries must never collide, and invalidating one site's key must leave the other's untouched.
        StoreCache<String, String> cache = new StoreCache<>(100);
        String a = PathSafety.compoundKey("site-a", "home");
        String b = PathSafety.compoundKey("site-b", "home");
        assertEquals("A", cache.get(a, k -> "A"));
        assertEquals("B", cache.get(b, k -> "B"));
        assertEquals("A", cache.peek(a), "site A's entry did not pick up site B's value");
        assertEquals("B", cache.peek(b), "and vice-versa");

        cache.invalidate(a);
        assertNull(cache.peek(a), "invalidating A dropped A");
        assertEquals("B", cache.peek(b), "…and left B intact");
    }

    @Test
    void concurrentTenantAccessNeverLeaksAcrossKeys() throws Exception {
        // Under concurrent get/invalidate on two tenants' keys, a key must only ever return its OWN value.
        StoreCache<String, String> cache = new StoreCache<>(1000);
        String a = PathSafety.compoundKey("site-a", "home");
        String b = PathSafety.compoundKey("site-b", "home");
        int threads = 12;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var start = new java.util.concurrent.CountDownLatch(1);
        var errors = new java.util.concurrent.ConcurrentLinkedQueue<String>();
        var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        for (int t = 0; t < threads; t++) {
            final int id = t;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 3000; i++) {
                        if (id % 3 == 0) {
                            cache.invalidate(i % 2 == 0 ? a : b);
                        } else {
                            if (!"A".equals(cache.get(a, k -> "A"))) {
                                errors.add("site A returned a foreign value");
                            }
                            if (!"B".equals(cache.get(b, k -> "B"))) {
                                errors.add("site B returned a foreign value");
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    errors.add(e.toString());
                }
                return null;
            }));
        }
        start.countDown();
        for (var f : futures) {
            f.get();
        }
        pool.shutdown();
        assertTrue(errors.isEmpty(), "no cross-tenant leak or exception under concurrent access: " + errors);
    }
}
