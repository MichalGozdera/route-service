package velomarker.service.planning.alns2;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Siatka regionów z kalibrowanymi współczynnikami proxy-effort (per-region, EMA).
 *
 * <p>Cel: zamiast realnego BRoutera per krawędź (drogie — tysiące callów), estymuj effort z
 * haversine × współczynnik regionu. Każda komórka (~cellDeg°) trzyma:
 * <ul>
 *   <li>{@code fDist} = realny_km / haversine_km (objazdowość dróg w regionie),</li>
 *   <li>{@code fClimbPerKm} = metry wzniosu / km (górzystość regionu).</li>
 * </ul>
 *
 * <p>Kalibracja LENIWA: pierwsza krawędź w komórce → realny BRouter probe → kalibruje komórkę
 * (i zwraca realne wartości); kolejne krawędzie → proxy. Co {@code recalibrateEvery} proxy-użyć
 * komórka jest re-probowana (EMA) — dryf terenu się dostraja. Dla całej Polski (~50 km komórki)
 * to ~150 realnych callów zamiast ~14k.
 *
 * <p>Dokładność OUTPUTU zachowana osobno: finalny realny chunked BRouter liczy trasę po proxy-search.
 * Proxy steruje TYLKO selekcją/kolejnością w trakcie.
 */
public final class RegionFactorGrid {

    /** Domyślne (= stałe z greedy: ROAD_FACTOR 1.3, CLIMB_PER_KM 3.0) — używane zanim komórka skalibrowana. */
    static final double DEFAULT_F_DIST = 1.3;
    static final double DEFAULT_F_CLIMB_PER_KM = 3.0;
    private static final double EMA_ALPHA = 0.4; // waga nowej próbki w EMA

    static final class Cell {
        double fDist = DEFAULT_F_DIST;
        double fClimbPerKm = DEFAULT_F_CLIMB_PER_KM;
        int samples = 0;
        int sinceProbe = 0;
    }

    private final double cellDeg;
    private final int recalibrateEvery;
    private final ConcurrentHashMap<Long, Cell> cells = new ConcurrentHashMap<>();

    public RegionFactorGrid(double cellDeg, int recalibrateEvery) {
        this.cellDeg = cellDeg > 0 ? cellDeg : 0.5;
        this.recalibrateEvery = Math.max(1, recalibrateEvery);
    }

    private long key(double lng, double lat) {
        long cx = (long) Math.floor(lng / cellDeg);
        long cy = (long) Math.floor(lat / cellDeg);
        return (cx << 32) ^ (cy & 0xffffffffL);
    }

    Cell cellFor(double lng, double lat) {
        return cells.computeIfAbsent(key(lng, lat), k -> new Cell());
    }

    /** Czy komórka wymaga realnego probe'a (niekalibrowana lub minął interwał re-kalibracji). */
    boolean needsProbe(Cell c) {
        synchronized (c) {
            return c.samples == 0 || c.sinceProbe >= recalibrateEvery;
        }
    }

    /** Zapisz realny pomiar (probe) → EMA update współczynników komórki. */
    void recordReal(Cell c, double realKm, double havKm, double climbM) {
        if (havKm < 1e-6 || realKm <= 0) return;
        double newFDist = realKm / havKm;
        double newFClimb = climbM / realKm;
        synchronized (c) {
            if (c.samples == 0) {
                c.fDist = newFDist;
                c.fClimbPerKm = Math.max(0, newFClimb);
            } else {
                c.fDist = EMA_ALPHA * newFDist + (1 - EMA_ALPHA) * c.fDist;
                c.fClimbPerKm = Math.max(0, EMA_ALPHA * newFClimb + (1 - EMA_ALPHA) * c.fClimbPerKm);
            }
            c.samples++;
            c.sinceProbe = 0;
        }
    }

    /** Odnotuj użycie proxy (bez probe'a) — zbliża komórkę do następnej re-kalibracji. */
    void recordProxyUse(Cell c) {
        synchronized (c) {
            c.sinceProbe++;
        }
    }

    double fDist(Cell c) {
        synchronized (c) { return c.fDist; }
    }

    double fClimbPerKm(Cell c) {
        synchronized (c) { return c.fClimbPerKm; }
    }

    public int calibratedCells() {
        int n = 0;
        for (Cell c : cells.values()) if (c.samples > 0) n++;
        return n;
    }
}
