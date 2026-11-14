package pl.czymogeaktualizowac.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionsTest {

    @Test
    void ordersNumericallyNotLexically() {
        List<String> v = new ArrayList<>(List.of("1.9.10", "1.9.2", "1.10.0", "1.9.9"));
        v.sort(Versions.ORDER);
        assertEquals(List.of("1.9.2", "1.9.9", "1.9.10", "1.10.0"), v);
    }

    @Test
    void releaseSortsAfterItsPrereleases() {
        List<String> v = new ArrayList<>(List.of("9.9.0", "9.9.0-rc.1", "9.9.0-beta.1", "9.9.1", "9.8.7"));
        v.sort(Versions.ORDER);
        assertEquals(List.of("9.8.7", "9.9.0-beta.1", "9.9.0-rc.1", "9.9.0", "9.9.1"), v);
    }

    @Test
    void prereleaseDetectionAndLines() {
        assertTrue(Versions.isPrerelease("11.3.0-beta.1"));
        assertFalse(Versions.isPrerelease("11.2.2"));
        assertEquals("11.2", Versions.majorMinor("11.2.2"));
        assertEquals("11", Versions.major("11.2.2"));
        assertEquals("7.1", Versions.majorMinor("7.1.4"));
    }

    @Test
    void shortAndLongVersionsCompareEqualWhenPaddedWithZeros() {
        assertEquals(0, Versions.compare("2.0", "2.0.0"));
        assertTrue(Versions.compare("2.0.1", "2.0") > 0);
    }
}
