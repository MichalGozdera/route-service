package velomarker.service.planning.coverage;

import velomarker.service.planning.*;
import velomarker.service.planning.route.*;
import velomarker.service.planning.day.*;
import velomarker.service.planning.coverage.*;
import velomarker.service.planning.coverage.prep.*;
import velomarker.service.planning.coverage.seed.*;
import velomarker.service.planning.coverage.index.*;
import velomarker.service.planning.coverage.metric.*;
import velomarker.service.planning.coverage.geom.*;
import velomarker.service.planning.coverage.scoring.*;
import velomarker.service.planning.coverage.debug.*;

import org.junit.jupiter.api.Test;
import velomarker.entity.planning.AreaPart;
import velomarker.entity.planning.UnvisitedArea;
import velomarker.port.out.planning.AreaCoverageIndex;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CoverageAreaIndex po refaktorze trzyma heurystyki seeda: {@code samplePointsFor} (czysta geometria, bez coverage)
 * oraz {@code distToRoute} (przez {@code deepestInteriorPoint} = coverage — tu mockowane). Coverage (zaliczenia)
 * w {@code AreaCoverageIndex} (JTS, test w adapterze: {@code JtsAreaCoverageIndexTest}).
 */
class CoverageAreaIndexTest {

    /** Helper: kwadratowa gmina centroid (lng, lat) z bokiem 2×sideHalfDeg. */
    private static UnvisitedArea squareGmina(int id, double lng, double lat, double sideHalfDeg) {
        double[][] ring = {
                {lng - sideHalfDeg, lat - sideHalfDeg},
                {lng + sideHalfDeg, lat - sideHalfDeg},
                {lng + sideHalfDeg, lat + sideHalfDeg},
                {lng - sideHalfDeg, lat + sideHalfDeg}
        };
        return UnvisitedArea.levelMulti(id, "G" + id, lat, lng, List.of(new AreaPart(ring, null)), 1, 4, "gmina");
    }

    @Test
    void samplePointsFor_returnsEntryPoints() {
        // samples = ring vertices przesunięte ~500m w kierunku centroidu (NIE centroid jako pierwszy
        // sample) — żeby trasa dotknęła krawędzi i wjechała w głąb, nie jechała do środka gminy.
        UnvisitedArea g = squareGmina(1, 15.0, 50.0, 0.05);
        CoverageAreaIndex idx = new CoverageAreaIndex(List.of(g), null, null);
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
        AreaCoverageIndex coverage = mock(AreaCoverageIndex.class);
        when(coverage.deepestInteriorPoint(1)).thenReturn(new double[]{15.0, 50.0}); // MIC = środek square
        CoverageAreaIndex idx = new CoverageAreaIndex(List.of(g), coverage, null);
        // Route przez corner (15.05, 49.95) = ring vertex; deepest (15,50) ~5km od trasy (mały dystans vs far ~35km).
        List<double[]> route = List.of(
                new double[]{14.5, 50.0},
                new double[]{15.05, 49.95},
                new double[]{15.5, 50.0}
        );
        assertThat(idx.distToRoute(g, route)).isLessThan(6.0);
    }

    @Test
    void distToRoute_far_whenOutside() {
        UnvisitedArea g = squareGmina(1, 15.0, 50.0, 0.05);
        AreaCoverageIndex coverage = mock(AreaCoverageIndex.class);
        when(coverage.deepestInteriorPoint(1)).thenReturn(new double[]{15.0, 50.0});
        CoverageAreaIndex idx = new CoverageAreaIndex(List.of(g), coverage, null);
        // Route 0.5° na zachód = ~35 km
        List<double[]> route = List.of(
                new double[]{14.0, 50.0},
                new double[]{14.5, 50.0}
        );
        assertThat(idx.distToRoute(g, route)).isGreaterThan(20.0);
    }
}
