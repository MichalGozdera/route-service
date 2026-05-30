package eu.cokeman.velomarker.mapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Kompaktowe kodowanie śladu 3D (lng, lat, z) do jednego stringa — wariant algorytmu Google Polyline z TRZECIM
 * kanałem na wysokość. Delta + zig-zag + base64-podobne 5-bitowe grupy. Drafty nie mają czasoznaczników, więc cały
 * ślad (z wysokością) mieści się w zwartym tekście zamiast rozdętego WKB/WKT LINESTRING Z — mniejszy zapis w bazie.
 *
 * Precyzja: lng/lat 1e-6 (~0.1 m), z 1e-1 (0.1 m) — praktycznie bezstratne dla trasy rowerowej. Punkty kontrolne
 * (waypoints) trzymane są OSOBNO (kolumna waypoints), więc ich rekonstrukcja nie zależy od tego kodowania.
 */
public final class Polyline3DCodec {

    private static final double LL_FACTOR = 1e6;   // lng/lat: ~0.1 m
    private static final double Z_FACTOR = 1e1;    // wysokość: 0.1 m

    private Polyline3DCodec() {
    }

    /** Koduje listę [lng, lat, z] (z opcjonalne — brak → 0) do zwartego stringa. */
    public static String encode(List<double[]> coords) {
        if (coords == null || coords.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(coords.size() * 6);
        long prevLng = 0;
        long prevLat = 0;
        long prevZ = 0;
        for (double[] c : coords) {
            long lng = Math.round(c[0] * LL_FACTOR);
            long lat = Math.round(c[1] * LL_FACTOR);
            long z = Math.round((c.length >= 3 ? c[2] : 0.0) * Z_FACTOR);
            encodeSigned(sb, lng - prevLng);
            encodeSigned(sb, lat - prevLat);
            encodeSigned(sb, z - prevZ);
            prevLng = lng;
            prevLat = lat;
            prevZ = z;
        }
        return sb.toString();
    }

    /** Dekoduje string z powrotem do listy [lng, lat, z]. */
    public static List<double[]> decode(String str) {
        List<double[]> out = new ArrayList<>();
        if (str == null || str.isEmpty()) {
            return out;
        }
        int[] i = {0};
        long lng = 0;
        long lat = 0;
        long z = 0;
        int n = str.length();
        while (i[0] < n) {
            lng += decodeSigned(str, i);
            lat += decodeSigned(str, i);
            z += decodeSigned(str, i);
            out.add(new double[]{lng / LL_FACTOR, lat / LL_FACTOR, z / Z_FACTOR});
        }
        return out;
    }

    private static void encodeSigned(StringBuilder sb, long value) {
        long v = value < 0 ? ~(value << 1) : (value << 1);
        while (v >= 0x20) {
            sb.append((char) ((0x20 | (int) (v & 0x1f)) + 63));
            v >>= 5;
        }
        sb.append((char) ((int) v + 63));
    }

    private static long decodeSigned(String str, int[] i) {
        long result = 0;
        int shift = 0;
        int b;
        do {
            b = str.charAt(i[0]++) - 63;
            result |= (long) (b & 0x1f) << shift;
            shift += 5;
        } while (b >= 0x20);
        return (result & 1) != 0 ? ~(result >> 1) : (result >> 1);
    }
}
