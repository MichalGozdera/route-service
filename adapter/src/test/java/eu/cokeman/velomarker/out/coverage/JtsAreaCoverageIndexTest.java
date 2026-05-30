package eu.cokeman.velomarker.out.coverage;

import org.junit.jupiter.api.Test;
import velomarker.entity.planning.AreaPart;
import velomarker.entity.planning.UnvisitedArea;
import velomarker.port.out.planning.AreaCoverageIndex;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage liczone JTS-em na PEŁNEJ geometrii. Kryterium zaliczenia na trasie BRoutera = wjazd
 * ≥100m W GŁĄB ({@code buffer(-100m).intersects(line)}) — przejazd po granicy / płytkie otarcie NIE
 * liczy (false-positives Grębów/Piotrków: trasa po drodze granicznej). {@code findAreaForPoint} =
 * pełna geometria bez bufora (lookup „która gmina zawiera punkt", nie kryterium zaliczenia).
 */
class JtsAreaCoverageIndexTest {

    private static final JtsAreaCoverageIndexFactory FACTORY = new JtsAreaCoverageIndexFactory();

    private static double[][] square(double cx, double cy, double h) {
        return new double[][]{{cx - h, cy - h}, {cx + h, cy - h}, {cx + h, cy + h}, {cx - h, cy + h}};
    }

    private static UnvisitedArea squareGmina(int id, double lng, double lat, double sideHalfDeg) {
        return UnvisitedArea.level(id, "G" + id, null, lat, lng, square(lng, lat, sideHalfDeg), 1, 4, "gmina");
    }

    @Test
    void findAreaForPoint_insideRing() {
        AreaCoverageIndex idx = FACTORY.build(List.of(squareGmina(1, 15.0, 50.0, 0.05)));
        UnvisitedArea found = idx.findAreaForPoint(15.01, 50.01);
        assertThat(found).isNotNull();
        assertThat(found.areaId()).isEqualTo(1);
    }

    @Test
    void findAreaForPoint_outsideRing() {
        AreaCoverageIndex idx = FACTORY.build(List.of(squareGmina(1, 15.0, 50.0, 0.05)));
        assertThat(idx.findAreaForPoint(15.2, 50.0)).isNull();
    }

    @Test
    void visitedAreaIds_deepEntry_credited() {
        // Trasa wjeżdża z zewnątrz aż do centroidu (głęboko) → zaliczona.
        AreaCoverageIndex idx = FACTORY.build(List.of(squareGmina(1, 15.0, 50.0, 0.05)));
        List<double[]> route = List.of(new double[]{15.06, 50.0}, new double[]{15.0, 50.0});
        assertThat(idx.visitedAreaIds(route)).contains(1);
    }

    @Test
    void visitedAreaIds_shallowEntry_lessThan100m_notCredited() {
        // Trasa przekracza prawą krawędź (15.05) ale wchodzi tylko ~36m (15.0495) — płytkie otarcie.
        // < 100m → buffer(-100m) NIE przecina → NIE zaliczona (to był false-positive Grębów/Piotrków).
        AreaCoverageIndex idx = FACTORY.build(List.of(squareGmina(1, 15.0, 50.0, 0.05)));
        List<double[]> route = List.of(new double[]{15.06, 50.0}, new double[]{15.0495, 50.0});
        assertThat(idx.visitedAreaIds(route)).doesNotContain(1);
    }

    /** Wąska gmina ~130m osiągalnej głębi (lng-półbok 0.001817° ≈ 130m przy cos(50°), lat-półbok 0.003°). */
    private static UnvisitedArea narrowGmina() {
        double[][] rect = {
                {15.0 - 0.001817, 50.0 - 0.003}, {15.0 + 0.001817, 50.0 - 0.003},
                {15.0 + 0.001817, 50.0 + 0.003}, {15.0 - 0.001817, 50.0 + 0.003}
        };
        return UnvisitedArea.level(1, "Waska", null, 50.0, 15.0, rect, 1, 4, "gmina");
    }

    @Test
    void adaptiveDepth_narrowGmina_creditedOnProportionalEntry() {
        // Próg adaptacyjny = min(100m, 0.6×130m) ≈ 78m (NIE pełne 100m). Wjazd ~85m → zaliczona.
        // (Przy twardym 100m byłaby odrzucona — to ratuje wąskie nadrzeczne gminy nad Wisłą.)
        AreaCoverageIndex idx = FACTORY.build(List.of(narrowGmina()));
        List<double[]> route = List.of(new double[]{15.000629, 49.999}, new double[]{15.000629, 50.001});
        assertThat(idx.visitedAreaIds(route)).contains(1);
    }

    @Test
    void adaptiveDepth_narrowGmina_tooShallowStillRejected() {
        // Wjazd ~30m < próg ~78m → nadal odrzucona (otarcie nie liczy nawet dla wąskiej gminy).
        AreaCoverageIndex idx = FACTORY.build(List.of(narrowGmina()));
        List<double[]> route = List.of(new double[]{15.0013977, 49.999}, new double[]{15.0013977, 50.001});
        assertThat(idx.visitedAreaIds(route)).doesNotContain(1);
    }

    @Test
    void visitedAreaIds_edgeGraze_outsideBorder_notCredited() {
        // Ślad biegnie TUŻ POZA prawą krawędzią, nie przekraczając jej (jak Gorzów Śląski/Prosna).
        AreaCoverageIndex idx = FACTORY.build(List.of(squareGmina(1, 15.0, 50.0, 0.05)));
        List<double[]> route = List.of(new double[]{15.051, 49.97}, new double[]{15.051, 50.03});
        assertThat(idx.visitedAreaIds(route)).doesNotContain(1);
    }

    @Test
    void visitedAreaIds_multipleAreas_onlyDeeplyEntered() {
        AreaCoverageIndex idx = FACTORY.build(List.of(
                squareGmina(1, 15.0, 50.0, 0.05),
                squareGmina(2, 15.5, 50.0, 0.05),
                squareGmina(3, 16.0, 50.0, 0.05)));
        // Wjazd głęboko w g1 i g3 (do centroidów), g2 ominięta od południa.
        List<double[]> route = List.of(
                new double[]{14.9, 50.0},   // outside
                new double[]{15.0, 50.0},   // centroid g1
                new double[]{15.45, 49.5},  // outside (na południe od g2)
                new double[]{16.0, 50.0}    // centroid g3
        );
        var visited = idx.visitedAreaIds(route);
        assertThat(visited).contains(1, 3);
        assertThat(visited).doesNotContain(2);
    }

    @Test
    void multipolygon_creditedViaAnyPart() {
        // Gmina z DWÓCH rozłącznych kawałków (czeski okres wokół miasta). Trasa głęboko w DRUGIM kawałku
        // ma zaliczyć gminę.
        AreaPart big = new AreaPart(square(15.0, 50.0, 0.05), null);
        AreaPart small = new AreaPart(square(15.5, 50.0, 0.04), null);
        UnvisitedArea g = UnvisitedArea.levelMulti(1, "Multi", null, 50.0, 15.0,
                List.of(big, small), 1, 4, "okres");
        AreaCoverageIndex idx = FACTORY.build(List.of(g));
        assertThat(idx.findAreaForPoint(15.5, 50.0)).isNotNull();
        // segment głęboko w środku małego kawałka (~4km od krawędzi) → zaliczony
        assertThat(idx.visitedAreaIds(List.of(new double[]{15.5, 50.0}, new double[]{15.51, 50.0}))).contains(1);
    }

    @Test
    void donutHole_cityNotShadowedByRural() {
        // Gmina wiejska = duży kwadrat z DZIURĄ w środku; gmina miejska = mały kwadrat w dziurze.
        double[][] outer = square(15.0, 50.0, 0.1);
        double[][] hole = square(15.0, 50.0, 0.03);
        AreaPart ruralPart = new AreaPart(outer, new double[][][]{hole});
        UnvisitedArea rural = UnvisitedArea.levelMulti(1, "RawaWiejska", null, 50.08, 15.08,
                List.of(ruralPart), 1, 4, "gmina");
        UnvisitedArea city = UnvisitedArea.level(2, "RawaMiejska", null, 50.0, 15.0,
                square(15.0, 50.0, 0.025), 1, 4, "gmina"); // wypełnia dziurę
        AreaCoverageIndex idx = FACTORY.build(List.of(rural, city));
        // środek = w dziurze wiejskiej (więc NIE wiejska) i w miejskiej → MIEJSKA
        UnvisitedArea found = idx.findAreaForPoint(15.0, 50.0);
        assertThat(found).isNotNull();
        assertThat(found.areaId()).isEqualTo(2);
        // punkt w paśmie wiejskiej (poza dziurą) → wiejska
        UnvisitedArea band = idx.findAreaForPoint(15.07, 50.0);
        assertThat(band).isNotNull();
        assertThat(band.areaId()).isEqualTo(1);
    }
}
