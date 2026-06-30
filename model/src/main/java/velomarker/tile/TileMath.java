package velomarker.tile;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

/**
 * Matematyka kafelków slippy-map (StatsHunters / VeloViewer "kwadraty") — port 1:1 z frontu
 * ({@code velomarker-front/src/app/services/tile-math.ts} + DDA supercover z
 * {@code workers/tile-stats.worker.ts}).
 *
 * <p>Kafelek z14 to czysta funkcja współrzędnych — NIE obiekt w bazie. Backendowy tryb TILES
 * planera tras generuje kafelki z geometrii (centroid/ring) i liczy, które trasa przecięła.
 * Algorytm MUSI być identyczny z frontem, inaczej "ile zdobędę" (backend) rozjedzie się z
 * "co zdobyłem" (re-detekcja frontu).
 *
 * <p>Współrzędne wszędzie jako {@code [lng, lat]} (jak geometria BRouter / {@code AreaPart.outer}).
 */
public final class TileMath {

    private TileMath() {}

    /** y &lt; 2^17 dla z&lt;=17 → mnożnik 2^17 daje bezkolizyjny klucz {@code x*MUL+y}. Spójne z frontem. */
    public static final long KEY_MUL = 131072L; // 2^17

    public static long tileKey(int x, int y) {
        return (long) x * KEY_MUL + y;
    }

    public static int keyToX(long key) {
        return (int) (key / KEY_MUL);
    }

    public static int keyToY(long key) {
        return (int) (key % KEY_MUL);
    }

    /** (lng,lat) → współrzędne kafelka (x,y) na zoomie z. Clamp do prawidłowego zakresu. */
    public static int[] lonLatToTileXY(double lon, double lat, int z) {
        long n = 1L << z;
        int x = (int) Math.floor(((lon + 180.0) / 360.0) * n);
        double latRad = Math.toRadians(lat);
        int y = (int) Math.floor(
                ((1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0) * n);
        if (x < 0) x = 0;
        else if (x >= n) x = (int) (n - 1);
        if (y < 0) y = 0;
        else if (y >= n) y = (int) (n - 1);
        return new int[]{x, y};
    }

    /** (lng,lat) → FRAKCYJNE współrzędne kafelka (bez floor) — do DDA supercover. */
    public static double[] lonLatToFracTile(double lon, double lat, int z) {
        long n = 1L << z;
        double fx = ((lon + 180.0) / 360.0) * n;
        double latRad = Math.toRadians(lat);
        double fy = ((1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0) * n;
        return new double[]{fx, fy};
    }

    private static double tileLat(double y, long n) {
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - (2.0 * y) / n))));
    }

    /** Kafelek (x,y,z) → bbox [minLon, minLat, maxLon, maxLat]. */
    public static double[] tileToBBox(int x, int y, int z) {
        long n = 1L << z;
        double minLon = ((double) x / n) * 360.0 - 180.0;
        double maxLon = ((double) (x + 1) / n) * 360.0 - 180.0;
        double maxLat = tileLat(y, n);
        double minLat = tileLat(y + 1, n);
        return new double[]{minLon, minLat, maxLon, maxLat};
    }

    /** Środek kafelka jako {@code [lng, lat]}. */
    public static double[] tileCenter(int x, int y, int z) {
        double[] b = tileToBBox(x, y, z);
        return new double[]{(b[0] + b[2]) / 2.0, (b[1] + b[3]) / 2.0};
    }

    /** Kafelek (x,y,z) → zamknięty ring poligonu {@code [[lng,lat] × 5]} (CW od górnego-lewego). */
    public static double[][] tileToPolygonRing(int x, int y, int z) {
        double[] b = tileToBBox(x, y, z); // [minLon, minLat, maxLon, maxLat]
        double minLon = b[0], minLat = b[1], maxLon = b[2], maxLat = b[3];
        return new double[][]{
                {minLon, maxLat},
                {maxLon, maxLat},
                {maxLon, minLat},
                {minLon, minLat},
                {minLon, maxLat},
        };
    }

    /**
     * Wszystkie kafelki (klucze {@code x*MUL+y}) przecięte przez polilinię {@code [lng,lat]} na zoomie z.
     * Supercover (DDA Amanatides-Woo) między kolejnymi punktami — kafelek liczy się, jeśli trasa
     * przez niego przechodzi. Segmenty z osobnych linii nie są łączone (tu polilinia = jedna linia).
     */
    public static List<Long> tilesOnPolyline(List<double[]> polyline, int z) {
        List<Long> out = new ArrayList<>();
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        LongConsumer add = key -> { if (seen.add(key)) out.add(key); };
        traversePolyline(polyline, z, add);
        return out;
    }

    /** Jak {@link #tilesOnPolyline}, ale emituje klucze do konsumenta (bez alokacji listy). */
    public static void traversePolyline(List<double[]> polyline, int z, LongConsumer add) {
        if (polyline == null || polyline.isEmpty()) return;
        long n = 1L << z;
        double prevFx = 0, prevFy = 0;
        boolean first = true;
        for (double[] p : polyline) {
            double[] f = lonLatToFracTile(p[0], p[1], z);
            double fx = f[0], fy = f[1];
            if (first) {
                emitClamped((int) Math.floor(fx), (int) Math.floor(fy), n, add);
                first = false;
            } else {
                traverseSegment(prevFx, prevFy, fx, fy, n, add);
            }
            prevFx = fx;
            prevFy = fy;
        }
    }

    private static void emitClamped(int x, int y, long n, LongConsumer add) {
        if (x < 0 || y < 0 || x >= n || y >= n) return;
        add.accept(tileKey(x, y));
    }

    /**
     * Amanatides-Woo: odwiedza wszystkie komórki siatki pod odcinkiem (f0)->(f1) we frakcyjnych
     * współrzędnych kafelków. 4-połączony łańcuch komórek. Identyczny z {@code tile-stats.worker.ts}.
     */
    private static void traverseSegment(double fx0, double fy0, double fx1, double fy1, long n, LongConsumer add) {
        int x = (int) Math.floor(fx0), y = (int) Math.floor(fy0);
        int xEnd = (int) Math.floor(fx1), yEnd = (int) Math.floor(fy1);
        emitClamped(x, y, n, add);
        if (x == xEnd && y == yEnd) return;

        double dx = fx1 - fx0, dy = fy1 - fy0;
        int stepX = dx > 0 ? 1 : -1;
        int stepY = dy > 0 ? 1 : -1;
        double tDeltaX = dx != 0 ? 1.0 / Math.abs(dx) : Double.POSITIVE_INFINITY;
        double tDeltaY = dy != 0 ? 1.0 / Math.abs(dy) : Double.POSITIVE_INFINITY;
        double tMaxX = dx != 0 ? (stepX > 0 ? x + 1 - fx0 : fx0 - x) / Math.abs(dx) : Double.POSITIVE_INFINITY;
        double tMaxY = dy != 0 ? (stepY > 0 ? y + 1 - fy0 : fy0 - y) / Math.abs(dy) : Double.POSITIVE_INFINITY;

        int maxSteps = Math.abs(xEnd - x) + Math.abs(yEnd - y) + 2;
        for (int s = 0; s < maxSteps; s++) {
            if (tMaxX < tMaxY) { x += stepX; tMaxX += tDeltaX; }
            else { y += stepY; tMaxY += tDeltaY; }
            emitClamped(x, y, n, add);
            if (x == xEnd && y == yEnd) break;
        }
    }
}
