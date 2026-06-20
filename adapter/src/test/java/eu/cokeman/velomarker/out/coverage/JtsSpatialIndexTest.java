package eu.cokeman.velomarker.out.coverage;

import org.junit.jupiter.api.Test;
import velomarker.port.out.planning.SpatialIndex;
import velomarker.port.out.planning.SpatialIndexFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test JTS {@link SpatialIndex} (planar STRtree). Punkty wokół 52°N (cosRef≈0.616): 0.01° lat ≈ 1.11 km,
 * 1° lng ≈ 68.3 km. Sprawdza zgodność z kontraktem siatki (nearest/kNN/count/external).
 */
class JtsSpatialIndexTest {

    private static final SpatialIndexFactory FACTORY = new JtsSpatialIndexFactory();
    private static final double TOL = 0.05; // km

    // p0,p1,p2 wzdłuż południka co 1.11 km; p3 daleko na wschód (~68 km).
    private static final double[][] PTS = {
            {21.0, 52.00}, // 0
            {21.0, 52.01}, // 1  → 1.11 km od p0
            {21.0, 52.02}, // 2  → 2.22 km od p0, 1.11 od p1
            {22.0, 52.00}, // 3  → ~68.3 km od p0
    };

    @Test
    void nearestDistKm_zwracaDystansDoNajblizszego() {
        SpatialIndex idx = FACTORY.build(PTS);
        assertThat(idx.nearestDistKm(0)).isCloseTo(1.11, org.assertj.core.data.Offset.offset(TOL));
        assertThat(idx.nearestDistKm(1)).isCloseTo(1.11, org.assertj.core.data.Offset.offset(TOL));
        assertThat(idx.nearestDistKm(3)).isCloseTo(68.3, org.assertj.core.data.Offset.offset(0.5));
    }

    @Test
    void nearestDistKm_pojedynczyPunkt_zwracaMax() {
        SpatialIndex idx = FACTORY.build(new double[][]{{21.0, 52.0}});
        assertThat(idx.nearestDistKm(0)).isEqualTo(Double.MAX_VALUE);
    }

    @Test
    void countWithinKm_liczyTylkoWPromieniu() {
        SpatialIndex idx = FACTORY.build(PTS);
        assertThat(idx.countWithinKm(1, 1.5)).isEqualTo(2); // p0 i p2 po 1.11 km
        assertThat(idx.countWithinKm(0, 1.5)).isEqualTo(1); // tylko p1 (p2=2.22 poza)
        assertThat(idx.countWithinKm(0, 100.0)).isEqualTo(3); // wszystkie inne
    }

    @Test
    void kNearestIndices_rosnacoDystansem_bezSiebie() {
        SpatialIndex idx = FACTORY.build(PTS);
        assertThat(idx.kNearestIndices(0, 2)).containsExactly(1, 2);
        assertThat(idx.kNearestIndices(2, 2)).containsExactly(1, 0);
    }

    @Test
    void nearestIndexTo_zewnetrznyPunkt() {
        SpatialIndex idx = FACTORY.build(PTS);
        assertThat(idx.nearestIndexTo(21.0, 52.003)).isEqualTo(0); // bliżej p0
        assertThat(idx.nearestIndexTo(21.9, 52.0)).isEqualTo(3);   // bliżej p3
    }

    @Test
    void distKmFromExternal_planar() {
        SpatialIndex idx = FACTORY.build(PTS);
        assertThat(idx.distKmFromExternal(0, 21.0, 52.01)).isCloseTo(1.11, org.assertj.core.data.Offset.offset(TOL));
    }

    @Test
    void pustaPula_bezWyjatkow() {
        SpatialIndex idx = FACTORY.build(new double[0][]);
        assertThat(idx.nearestIndexTo(21.0, 52.0)).isEqualTo(-1);
        assertThat(idx.kNearestIndices(0, 3)).isEmpty();
    }
}
