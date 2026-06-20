package velomarker.service.planning.coverage;

import org.junit.jupiter.api.Test;
import velomarker.entity.planning.UnvisitedArea;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GminaIndex po refaktorze trzyma TYLKO heurystyki seeda (samplePointsFor, distToRoute) — coverage
 * (zaliczenia) przeniesione do {@code AreaCoverageIndex} (JTS, test w adapterze:
 * {@code JtsAreaCoverageIndexTest}). Coverage delegowane, więc tu konstruujemy z {@code null} oracle.
 */
class GminaIndexTest {

    /** Helper: kwadratowa gmina centroid (lng, lat) z bokiem 2×sideHalfDeg. */
    private static UnvisitedArea squareGmina(int id, double lng, double lat, double sideHalfDeg) {
        double[][] ring = {
                {lng - sideHalfDeg, lat - sideHalfDeg},
                {lng + sideHalfDeg, lat - sideHalfDeg},
                {lng + sideHalfDeg, lat + sideHalfDeg},
                {lng - sideHalfDeg, lat + sideHalfDeg}
        };
        return UnvisitedArea.level(id, "G" + id, lat, lng, ring, 1, 4, "gmina");
    }

    @Test
    void samplePointsFor_returnsEntryPoints() {
        // samples = ring vertices przesunięte ~500m w kierunku centroidu (NIE centroid jako pierwszy
        // sample) — żeby trasa dotknęła krawędzi i wjechała w głąb, nie jechała do środka gminy.
        UnvisitedArea g = squareGmina(1, 15.0, 50.0, 0.05);
        GminaIndex idx = new GminaIndex(List.of(g), null, null);
        double[][] samples = idx.samplePointsFor(g);
        assertThat(samples.length).isLessThanOrEqualTo(5);
        assertThat(samples.length).isGreaterThan(0);
        for (double[] s : samples) {
            double dist = Math.max(Math.abs(s[0] - 15.0), Math.abs(s[1] - 50.0));
            assertThat(dist).isLessThanOrEqualTo(0.05); // wewnątrz ringa
            assertThat(dist).isGreaterThan(0.04);        // blisko krawędzi (offset ~500m do środka)
        }
    }

    @Test
    void distToRoute_small_whenRouteThroughCorner() {
        UnvisitedArea g = squareGmina(1, 15.0, 50.0, 0.05);
        GminaIndex idx = new GminaIndex(List.of(g), null, null);
        // Route przez corner (15.05, 49.95) = ring vertex. Entry-point sample ~500m od niego.
        List<double[]> route = List.of(
                new double[]{14.5, 50.0},
                new double[]{15.05, 49.95},
                new double[]{15.5, 50.0}
        );
        assertThat(idx.distToRoute(g, route)).isLessThan(1.0);
    }

    @Test
    void distToRoute_far_whenOutside() {
        UnvisitedArea g = squareGmina(1, 15.0, 50.0, 0.05);
        GminaIndex idx = new GminaIndex(List.of(g), null, null);
        // Route 0.5° na zachód = ~35 km
        List<double[]> route = List.of(
                new double[]{14.0, 50.0},
                new double[]{14.5, 50.0}
        );
        assertThat(idx.distToRoute(g, route)).isGreaterThan(20.0);
    }
}
