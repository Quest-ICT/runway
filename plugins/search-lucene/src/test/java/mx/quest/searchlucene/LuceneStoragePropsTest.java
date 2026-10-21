package mx.quest.searchlucene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The app-level storage settings: default heap, tolerant enum binding, and a never-blank index root. */
class LuceneStoragePropsTest {

    @Test
    void defaultsToHeapWithADefaultIndexRoot() {
        LuceneStorageProps p = new LuceneStorageProps();
        assertEquals(LuceneStorageProps.Storage.HEAP, p.getStorage());
        assertFalse(p.isDisk());
        assertFalse(p.getIndexRoot().isBlank());
    }

    @Test
    void storageBindingIsCaseInsensitiveAndTolerant() {
        LuceneStorageProps p = new LuceneStorageProps();
        p.setStorage("disk");
        assertTrue(p.isDisk());
        p.setStorage("HEAP");
        assertFalse(p.isDisk());
        p.setStorage("DISK");
        assertTrue(p.isDisk());
    }

    @Test
    void unknownOrBlankStorageFallsBackToHeap() {
        LuceneStorageProps p = new LuceneStorageProps();
        p.setStorage("disk");
        p.setStorage("nonsense"); // degrade-don't-crash: not a crash, resets to HEAP
        assertEquals(LuceneStorageProps.Storage.HEAP, p.getStorage());
        p.setStorage("disk");
        p.setStorage("");
        assertEquals(LuceneStorageProps.Storage.HEAP, p.getStorage());
    }

    @Test
    void blankIndexRootRestoresTheDefault() {
        LuceneStorageProps p = new LuceneStorageProps();
        p.setIndexRoot("/data/idx");
        assertEquals("/data/idx", p.getIndexRoot());
        p.setIndexRoot("   ");
        assertFalse(p.getIndexRoot().isBlank(), "blank restores the default, never leaves an empty path");
    }
}
