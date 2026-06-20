package eu.cokeman.velomarker.out.coverage;

import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.index.strtree.ItemDistance;
import org.locationtech.jts.index.strtree.STRtree;
import velomarker.port.out.planning.SpatialIndex;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * JTS implementacja {@link SpatialIndex}: {@code STRtree} + projekcja równoodległościowa (x = lng·cosRef,
 * y = lat, w km), spójna z {@link JtsAreaCoverageIndex}. {@code nearestNeighbour}/{@code kNearestNeighbour}
 * robią wyszukiwanie; {@code countWithinKm} = range-query + filtr. Metryka planarna ≈ great-circle przy
 * skalach sąsiedztwa (mikro-dryf vs haversine — świadoma decyzja: niech biblioteka liczy).
 *
 * <p>Nie thread-safe (jedna instancja per zapytanie/pula).
 */
final class JtsSpatialIndex implements SpatialIndex {

    private static final double KM_PER_DEG = 111.0;
    private static final int[] EMPTY = new int[0];

    /** Dystans euklidesowy (km) między dwoma itemami {@code double[]{px,py,idx}}. */
    private static final ItemDistance DIST = (b1, b2) -> {
        double[] a = (double[]) b1.getItem();
        double[] c = (double[]) b2.getItem();
        return Math.hypot(a[0] - c[0], a[1] - c[1]);
    };

    private final int n;
    private final double cosRef;
    private final double[][] items; // items[i] = {px, py, i}
    private final STRtree tree = new STRtree();

    JtsSpatialIndex(double[][] pts) {
        this.n = pts == null ? 0 : pts.length;
        this.cosRef = refCos(pts);
        this.items = new double[n][];
        for (int i = 0; i < n; i++) {
            double px = pts[i][0] * cosRef * KM_PER_DEG;
            double py = pts[i][1] * KM_PER_DEG;
            items[i] = new double[]{px, py, i};
            tree.insert(new Envelope(px, px, py, py), items[i]);
        }
        if (n > 0) {
            tree.build(); // eager — lazy build NIE jest thread-safe (spójnie z JtsAreaCoverageIndex)
        }
    }

    private static double refCos(double[][] pts) {
        if (pts == null || pts.length == 0) return 1.0;
        double sum = 0;
        for (double[] p : pts) sum += p[1];
        return Math.cos(Math.toRadians(sum / pts.length));
    }

    private double[] project(double lng, double lat) {
        return new double[]{lng * cosRef * KM_PER_DEG, lat * KM_PER_DEG, -1};
    }

    @Override
    public double nearestDistKm(int i) {
        if (n < 2) return Double.MAX_VALUE;
        // nearestNeighbour(env,item,dist) NIE wyklucza punktu zapytania (zwróciłby self@0km) — bierzemy
        // najbliższego z kNearest(1), które self odfiltrowuje.
        int[] nn = kNearestIndices(i, 1);
        if (nn.length == 0) return Double.MAX_VALUE;
        return Math.hypot(items[i][0] - items[nn[0]][0], items[i][1] - items[nn[0]][1]);
    }

    @Override
    public int[] kNearestIndices(int i, int k) {
        if (k <= 0 || n < 2) return EMPTY;
        Object[] res = tree.nearestNeighbour(envOf(items[i]), items[i], DIST, Math.min(n, k + 1));
        List<double[]> others = new ArrayList<>(res.length);
        for (Object o : res) {
            double[] p = (double[]) o;
            if ((int) p[2] != i) others.add(p);
        }
        others.sort(Comparator.comparingDouble(p -> Math.hypot(items[i][0] - p[0], items[i][1] - p[1])));
        int m = Math.min(k, others.size());
        int[] out = new int[m];
        for (int t = 0; t < m; t++) out[t] = (int) others.get(t)[2];
        return out;
    }

    @Override
    public int countWithinKm(int i, double radiusKm) {
        if (n < 2 || radiusKm <= 0) return 0;
        double px = items[i][0], py = items[i][1];
        Envelope env = new Envelope(px - radiusKm, px + radiusKm, py - radiusKm, py + radiusKm);
        @SuppressWarnings("unchecked")
        List<double[]> cands = tree.query(env);
        int count = 0;
        for (double[] p : cands) {
            if ((int) p[2] == i) continue;
            if (Math.hypot(px - p[0], py - p[1]) < radiusKm) count++;
        }
        return count;
    }

    @Override
    public int nearestIndexTo(double lng, double lat) {
        if (n == 0) return -1;
        double[] q = project(lng, lat);
        double[] near = (double[]) tree.nearestNeighbour(envOf(q), q, DIST);
        return near == null ? -1 : (int) near[2];
    }

    @Override
    public double distKmFromExternal(int i, double lng, double lat) {
        double[] q = project(lng, lat);
        return Math.hypot(items[i][0] - q[0], items[i][1] - q[1]);
    }

    private static Envelope envOf(double[] p) {
        return new Envelope(p[0], p[0], p[1], p[1]);
    }
}
