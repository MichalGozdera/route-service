package velomarker.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import velomarker.entity.ElevationProfile;
import velomarker.entity.RouteCalculation;
import velomarker.port.in.CalculateRouteUseCase;
import velomarker.port.out.BrouterRoutingClient;
import velomarker.port.out.ElevationDataSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class CalculateRouteService implements CalculateRouteUseCase {

    private static final Logger log = LoggerFactory.getLogger(CalculateRouteService.class);

    /** Okno próbkowania wysokości po dystansie — ≤500 próbek/okno (cap /elevation) → ~240 m/próbkę. Spójne z asystentem
     *  i frontem, żeby gęstość profilu była taka sama niezależnie od tego, kto liczy trasę. */
    private static final double ELEV_WINDOW_KM = 120.0;

    private final BrouterRoutingClient brouterClient;
    private final ElevationDataSource elevation;

    // Profilowanie enrichWithElevation — rolling sample, log co 200 calls (osobno od BRouter timing).
    private static final int TIMING_LOG_EVERY = 200;
    private static final int TIMING_SAMPLE_SIZE = 200;
    private final AtomicLong enrichCalls = new AtomicLong();
    private final long[] enrichMsSample = new long[TIMING_SAMPLE_SIZE];
    private long lastEnrichLogAt = 0;

    public CalculateRouteService(BrouterRoutingClient brouterClient, ElevationDataSource elevation) {
        this.brouterClient = brouterClient;
        this.elevation = elevation;
    }

    /** v3.17: reset licznika enrich per plan — „Elevation enrichment timing last 200 of N" liczy TEN plan. */
    @Override
    public synchronized void resetPlanCounters() {
        enrichCalls.set(0);
        lastEnrichLogAt = 0;
    }

    @Override
    public RouteCalculation calculate(CalculateRouteCommand command) {
        if (command.waypoints() == null || command.waypoints().size() < 2) {
            throw new IllegalArgumentException("At least 2 waypoints required");
        }
        if (command.profile() == null || command.profile().isBlank()) {
            throw new IllegalArgumentException("Profile is required");
        }
        RouteCalculation result = brouterClient.calculate(command.waypoints(), command.profile(), command.computeStats());
        // ZAWSZE doliczamy wysokość z DEM (HGT/Copernicus) — Coverage cost'uje effort = distance + alpha*ascent,
        // więc elevation MUSI być w coords nawet dla intermediate probing calls (bez tego climbM byłby
        // pobierany przez Coverage z surowych lng/lat = drogi DEM sample per krawędź zamiast wartości z'ek).
        // computeStats=false WYŁĄCZA jedynie RouteStats/spans (drogie parsowanie messageList) — elevation NIE.
        long tEnrichStart = System.currentTimeMillis();
        List<double[]> withZ = enrichWithElevation(result.coordinates(), result.flatSpans());
        recordEnrichTiming(System.currentTimeMillis() - tEnrichStart);
        return new RouteCalculation(withZ, result.distanceKm(), result.flatSpans(), result.stats(),
                result.crosspointStart(), result.crosspointEnd());
    }

    private void recordEnrichTiming(long elapsedMs) {
        long n = enrichCalls.incrementAndGet();
        int slot = (int) ((n - 1) % TIMING_SAMPLE_SIZE);
        enrichMsSample[slot] = elapsedMs;
        if (n - lastEnrichLogAt >= TIMING_LOG_EVERY) {
            synchronized (this) {
                if (n - lastEnrichLogAt < TIMING_LOG_EVERY) return;
                lastEnrichLogAt = n;
                int sampleSize = (int) Math.min(n, TIMING_SAMPLE_SIZE);
                long[] copy = Arrays.copyOf(enrichMsSample, sampleSize);
                Arrays.sort(copy);
                long min = copy[0];
                long max = copy[sampleSize - 1];
                long p50 = copy[sampleSize * 50 / 100];
                long p95 = copy[Math.min(sampleSize - 1, sampleSize * 95 / 100)];
                long p99 = copy[Math.min(sampleSize - 1, sampleSize * 99 / 100)];
                double avg = Arrays.stream(copy).sum() / (double) sampleSize;
                log.info("Elevation enrichment timing (last {} of {}): min={}ms p50={}ms avg={}ms p95={}ms p99={}ms max={}ms",
                        new Object[]{sampleSize, n, min, p50, String.format("%.1f", avg), p95, p99, max});
            }
        }
    }

    /**
     * Dokleja wysokość z (Copernicus) per wierzchołek: dzieli ślad na okna ≤{@link #ELEV_WINDOW_KM}, próbkuje DEM w
     * każdym i interpoluje na wszystkie wierzchołki. Best-effort: gdy DEM padnie, zwraca geometrię BEZ z (front dobierze
     * sam) — trasa nigdy nie pada przez brak elewacji.
     */
    private List<double[]> enrichWithElevation(List<double[]> coords, List<int[]> flatSpans) {
        int n = coords == null ? 0 : coords.size();
        if (n < 2) {
            return coords;
        }
        try {
            double[] cumDist = cumulativeMeters(coords);
            double[] z = new double[n];
            int i = 0;
            while (i < n - 1) {
                int j = i;
                while (j + 1 < n && (cumDist[j + 1] - cumDist[i]) <= ELEV_WINDOW_KM * 1000.0) {
                    j++;
                }
                if (j == i) {
                    j = i + 1;
                }
                List<double[]> window = new ArrayList<>(coords.subList(i, j + 1));
                ElevationProfile prof = elevation.sample(window);   // [dist, ele] ≤ max-samples
                List<double[]> p = prof.profile();
                if (!p.isEmpty()) {
                    double[] pDist = new double[p.size()];
                    double[] pEle = new double[p.size()];
                    for (int k = 0; k < p.size(); k++) {
                        pDist[k] = p.get(k)[0];
                        pEle[k] = p.get(k)[1];
                    }
                    double start = cumDist[i];
                    for (int k = i; k <= j; k++) {
                        z[k] = interp(pDist, pEle, cumDist[k] - start);
                    }
                }
                i = j;
            }
            // Tunele/wiadukty: DEM dał teren NAD tunelem (fałszywy podjazd). Interpolujemy z liniowo po dystansie między
            // portalami (z[a], z[b] zostają z DEM — to realny grunt przy wlocie/wylocie), wnętrze spanu nadpisujemy.
            flattenSpans(z, cumDist, flatSpans);
            List<double[]> out = new ArrayList<>(n);
            for (int k = 0; k < n; k++) {
                out.add(new double[]{coords.get(k)[0], coords.get(k)[1], z[k]});
            }
            return out;
        } catch (RuntimeException e) {
            log.warn("Elevation enrichment failed ({}) — zwracam geometrię bez z", e.getMessage());
            return coords;   // best-effort: trasa zostaje, front dobierze DEM jak dawniej
        }
    }

    /** Dla każdego spanu [a,b] nadpisuje z wierzchołków a&lt;k&lt;b liniową interpolacją po dystansie między z[a] i z[b]. */
    private static void flattenSpans(double[] z, double[] cumDist, List<int[]> flatSpans) {
        if (flatSpans == null || flatSpans.isEmpty()) {
            return;
        }
        int n = z.length;
        for (int[] span : flatSpans) {
            int a = span[0];
            int b = span[1];
            if (a < 0 || b >= n || b <= a + 1) {
                continue;   // brak wierzchołków do interpolacji (portale sąsiadują)
            }
            double dSpan = cumDist[b] - cumDist[a];
            if (dSpan <= 0) {
                continue;
            }
            double zA = z[a];
            double zB = z[b];
            for (int k = a + 1; k < b; k++) {
                double t = (cumDist[k] - cumDist[a]) / dSpan;
                z[k] = zA + t * (zB - zA);
            }
        }
    }

    private static double[] cumulativeMeters(List<double[]> coords) {
        double[] cum = new double[coords.size()];
        for (int k = 1; k < coords.size(); k++) {
            cum[k] = cum[k - 1] + GeoMath.haversineM(coords.get(k - 1), coords.get(k));
        }
        return cum;
    }

    /** Liniowa interpolacja wartości w (rosnące xs) dla x; poza zakresem → skraj. */
    private static double interp(double[] xs, double[] vs, double x) {
        if (xs.length == 0) {
            return 0;
        }
        if (x <= xs[0]) {
            return vs[0];
        }
        if (x >= xs[xs.length - 1]) {
            return vs[vs.length - 1];
        }
        int lo = 0;
        int hi = xs.length - 1;
        while (lo + 1 < hi) {
            int mid = (lo + hi) >>> 1;
            if (xs[mid] <= x) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double span = xs[hi] - xs[lo];
        double t = span <= 0 ? 0 : (x - xs[lo]) / span;
        return vs[lo] + t * (vs[hi] - vs[lo]);
    }
}
