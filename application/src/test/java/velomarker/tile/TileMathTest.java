package velomarker.tile;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parzystość z frontem ({@code tile-math.ts} / {@code tile-stats.worker.ts}). Wartości referencyjne
 * policzone tym samym wzorem slippy-map — rozjazd = "obiecane vs zdobyte" w trybie TILES.
 */
class TileMathTest {

    @Test
    void lonLatToTileXY_matchesFrontendReferences() {
        assertArrayEquals(new int[]{8192, 8192}, TileMath.lonLatToTileXY(0, 0, 14));
        // Berlin lon=13.37771 lat=52.51628 → (8800,5373) z14
        assertArrayEquals(new int[]{8800, 5373}, TileMath.lonLatToTileXY(13.37771, 52.51628, 14));
        // Kraków
        assertArrayEquals(new int[]{9099, 5552}, TileMath.lonLatToTileXY(19.94, 50.06, 14));
        assertArrayEquals(new int[]{36397, 22209}, TileMath.lonLatToTileXY(19.94, 50.06, 16));
    }

    @Test
    void zoom0_everythingIsTileZero() {
        assertArrayEquals(new int[]{0, 0}, TileMath.lonLatToTileXY(-179, 85, 0));
        assertArrayEquals(new int[]{0, 0}, TileMath.lonLatToTileXY(179, -85, 0));
    }

    @Test
    void clampAtAntimeridian() {
        long n = 1L << 14;
        assertEquals((int) (n - 1), TileMath.lonLatToTileXY(180, 0, 14)[0]);
        assertEquals(0, TileMath.lonLatToTileXY(-180, 0, 14)[0]);
    }

    @Test
    void tileKeyRoundTrip() {
        long k = TileMath.tileKey(8800, 5373);
        assertEquals(8800, TileMath.keyToX(k));
        assertEquals(5373, TileMath.keyToY(k));
    }

    @Test
    void bboxCenterMapsBackToSameTile() {
        int x = 8800, y = 5373, z = 14;
        double[] c = TileMath.tileCenter(x, y, z);
        assertArrayEquals(new int[]{x, y}, TileMath.lonLatToTileXY(c[0], c[1], z));
    }

    @Test
    void polygonRingIsClosed5Points() {
        double[][] ring = TileMath.tileToPolygonRing(100, 200, 14);
        assertEquals(5, ring.length);
        assertArrayEquals(ring[0], ring[4], 1e-12);
    }

    @Test
    void supercover_horizontalRunCoversEveryTile() {
        // Linia przez środki 4 kolejnych kafelków w x (ten sam y) → dokładnie te 4 kafelki.
        int z = 14, y = 5373;
        double[] a = TileMath.tileCenter(8800, y, z);
        double[] b = TileMath.tileCenter(8803, y, z);
        List<Long> tiles = TileMath.tilesOnPolyline(List.of(a, b), z);
        assertEquals(4, tiles.size());
        for (int x = 8800; x <= 8803; x++) {
            assertTrue(tiles.contains(TileMath.tileKey(x, y)), "brak kafelka x=" + x);
        }
    }

    @Test
    void supercover_singlePointIsOneTile() {
        double[] a = TileMath.tileCenter(8800, 5373, 14);
        assertEquals(1, TileMath.tilesOnPolyline(List.of(a), 14).size());
    }

    @Test
    void supercover_diagonalIsContiguous4Connected() {
        // Przekątna przez kilka kafelków — łańcuch 4-połączony, nie przeskakuje po rogach.
        int z = 14;
        double[] a = TileMath.tileCenter(8800, 5373, z);
        double[] b = TileMath.tileCenter(8803, 5376, z);
        List<Long> tiles = TileMath.tilesOnPolyline(List.of(a, b), z);
        // start i koniec na pewno obecne; łańcuch dłuższy niż 4 (4-połączony przez przekątną)
        assertTrue(tiles.contains(TileMath.tileKey(8800, 5373)));
        assertTrue(tiles.contains(TileMath.tileKey(8803, 5376)));
        assertTrue(tiles.size() >= 7, "4-połączony łańcuch przekątnej ma ≥ dx+dy+1 kafelków");
    }
}
