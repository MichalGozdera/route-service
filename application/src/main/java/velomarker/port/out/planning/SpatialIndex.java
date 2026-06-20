package velomarker.port.out.planning;

/**
 * Indeks przestrzenny nad zbiorem punktów {@code [lng,lat]} do szybkich zapytań sąsiedztwa (~O(log n)
 * zamiast O(n²)). Zastępuje ręczną siatkę — implementacja w adapterze (JTS STRtree, projekcja planarna
 * x=lng·cosRef). Punkty adresowane indeksem w kolejności podania do {@link SpatialIndexFactory#build}.
 *
 * <p>Dystanse w km (projekcja równoodległościowa ≈ great-circle przy tych skalach — patrz adapter).
 */
public interface SpatialIndex {

    /** Odległość (km) do najbliższego INNEGO punktu względem {@code i}; {@code Double.MAX_VALUE} gdy <2 punkty. */
    double nearestDistKm(int i);

    /** Indeksy (max) {@code k} najbliższych punktów względem {@code i}, rosnąco dystansem (bez {@code i}). */
    int[] kNearestIndices(int i, int k);

    /** Liczba INNYCH punktów w promieniu {@code radiusKm} od {@code i}. */
    int countWithinKm(int i, double radiusKm);

    /** Indeks punktu najbliższego ZEWNĘTRZNEJ współrzędnej {@code (lng,lat)}; {@code -1} gdy pusto. */
    int nearestIndexTo(double lng, double lat);

    /** Dystans (km) między punktem {@code i} a zewnętrzną współrzędną {@code (lng,lat)}. */
    double distKmFromExternal(int i, double lng, double lat);
}
