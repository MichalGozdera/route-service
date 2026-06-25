package eu.cokeman.velomarker.out.coverage;

import org.junit.jupiter.api.Test;
import velomarker.entity.planning.AreaPart;
import velomarker.entity.planning.UnvisitedArea;
import velomarker.port.out.planning.AreaCoverageIndex;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage liczone JTS-em na PEŁNEJ geometrii. Kryterium zaliczenia na trasie BRoutera = wjazd
 * ≥200m W GŁĄB ({@code buffer(-200m).intersects(line)}) — przejazd po granicy / płytkie otarcie /
 * smyranie po peryferiach NIE liczy (false-positives Grębów/Piotrków/Kołobrzeg).
 * {@code findAreaForPoint} = pełna geometria bez bufora (lookup „która gmina zawiera punkt", nie
 * kryterium zaliczenia).
 */
class JtsAreaCoverageIndexTest {

    private static final JtsAreaCoverageIndexFactory FACTORY = new JtsAreaCoverageIndexFactory();

    private static double[][] square(double cx, double cy, double h) {
        return new double[][]{{cx - h, cy - h}, {cx + h, cy - h}, {cx + h, cy + h}, {cx - h, cy + h}};
    }

    private static UnvisitedArea squareGmina(int id, double lng, double lat, double sideHalfDeg) {
        return UnvisitedArea.levelMulti(id, "G" + id, lat, lng, List.of(new AreaPart(square(lng, lat, sideHalfDeg), null)), 1, 4, "gmina");
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
    void visitedAreaIds_shallowEntry_lessThan200m_notCredited() {
        // Trasa przekracza prawą krawędź (15.05) ale wchodzi tylko ~36m (15.0495) — płytkie otarcie.
        // < 200m → buffer(-200m) NIE przecina → NIE zaliczona (false-positive Grębów/Piotrków/Kołobrzeg).
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
        return UnvisitedArea.levelMulti(1, "Waska", 50.0, 15.0, List.of(new AreaPart(rect, null)), 1, 4, "gmina");
    }

    @Test
    void adaptiveDepth_narrowGmina_creditedOnProportionalEntry() {
        // Próg adaptacyjny = min(200m, 0.6×130m) ≈ 78m (NIE pełne 200m). Wjazd ~85m → zaliczona.
        // (Przy twardym 200m byłaby odrzucona — to ratuje wąskie nadrzeczne gminy nad Wisłą.)
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
        UnvisitedArea g = UnvisitedArea.levelMulti(1, "Multi", 50.0, 15.0,
                List.of(big, small), 1, 4, "okres");
        AreaCoverageIndex idx = FACTORY.build(List.of(g));
        assertThat(idx.findAreaForPoint(15.5, 50.0)).isNotNull();
        // segment głęboko w środku małego kawałka (~4km od krawędzi) → zaliczony
        assertThat(idx.visitedAreaIds(List.of(new double[]{15.5, 50.0}, new double[]{15.51, 50.0}))).contains(1);
    }

    // === v3.15: operacje przestrzenne portu (jeden silnik, kryterium kredytu) ===

    @Test
    void enclosedUnvisited_centerSurroundedByVisitedNeighbors() {
        List<UnvisitedArea> grid = new java.util.ArrayList<>();
        int id = 1;
        for (int row = -1; row <= 1; row++) {
            for (int col = -1; col <= 1; col++) {
                grid.add(squareGmina(id++, 15.0 + col * 0.1, 50.0 + row * 0.1, 0.05));
            }
        }
        AreaCoverageIndex idx = FACTORY.build(grid);
        // id 5 = środek (15.0,50.0); reszta zaliczona dookoła → środek otoczony.
        Set<Integer> visited = Set.of(1, 2, 3, 4, 6, 7, 8, 9);
        assertThat(idx.enclosedUnvisited(visited)).containsExactly(5);
    }

    @Test
    void donutHole_cityNotShadowedByRural() {
        // Gmina wiejska = duży kwadrat z DZIURĄ w środku; gmina miejska = mały kwadrat w dziurze.
        double[][] outer = square(15.0, 50.0, 0.1);
        double[][] hole = square(15.0, 50.0, 0.03);
        AreaPart ruralPart = new AreaPart(outer, new double[][][]{hole});
        UnvisitedArea rural = UnvisitedArea.levelMulti(1, "RawaWiejska", 50.08, 15.08,
                List.of(ruralPart), 1, 4, "gmina");
        UnvisitedArea city = UnvisitedArea.levelMulti(2, "RawaMiejska", 50.0, 15.0,
                List.of(new AreaPart(square(15.0, 50.0, 0.025), null)), 1, 4, "gmina"); // wypełnia dziurę
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

    @Test
    void allNeighborsVisited_trueAcrossCountries() {
        // Środek (kraj 1) otoczony 8 sąsiadami z INNEGO kraju (kraj 2). Adjacency jest geometryczna,
        // bez filtra kraju → otoczenie liczy też sąsiadów zza granicy.
        List<UnvisitedArea> grid = new java.util.ArrayList<>();
        int id = 1;
        for (int row = -1; row <= 1; row++) {
            for (int col = -1; col <= 1; col++) {
                int country = (row == 0 && col == 0) ? 1 : 2; // środek kraj 1, reszta kraj 2
                grid.add(UnvisitedArea.levelMulti(id++, "g", 50.0 + row * 0.1, 15.0 + col * 0.1,
                        List.of(new AreaPart(square(15.0 + col * 0.1, 50.0 + row * 0.1, 0.05), null)), country, 4, "gmina"));
            }
        }
        AreaCoverageIndex idx = FACTORY.build(grid);
        // id 5 = środek; sąsiedzi 1,2,3,4,6,7,8,9 (kraj 2) wszyscy zaliczeni → otoczony.
        assertThat(idx.allNeighborsVisited(5, Set.of(1, 2, 3, 4, 6, 7, 8, 9))).isTrue();
        // jeden sąsiad (9) niezaliczony → NIE otoczony.
        assertThat(idx.allNeighborsVisited(5, Set.of(1, 2, 3, 4, 6, 7, 8))).isFalse();
    }

    @Test
    void allNeighborsVisited_noFloorOnNeighborCount() {
        // Rząd 3 gmin: środkowa ma TYLKO 2 sąsiadów. Oba zaliczone → otoczona mimo małej liczby (bez progu).
        AreaCoverageIndex idx = FACTORY.build(List.of(
                squareGmina(1, 14.9, 50.0, 0.05),
                squareGmina(2, 15.0, 50.0, 0.05),
                squareGmina(3, 15.1, 50.0, 0.05)));
        assertThat(idx.allNeighborsVisited(2, Set.of(1, 3))).isTrue();  // 2 sąsiadów, oba → otoczona
        assertThat(idx.allNeighborsVisited(1, Set.of(2))).isTrue();     // skrajna: 1 sąsiad zaliczony → otoczona
        assertThat(idx.allNeighborsVisited(2, Set.of(1))).isFalse();    // sąsiad 3 niezaliczony → NIE
    }

    @Test
    void allNeighborsVisited_falseWhenNoNeighbors() {
        // Realna wyspa: 0 sąsiadów → nie jest „otoczona" (length>0 ją odsiewa).
        AreaCoverageIndex idx = FACTORY.build(List.of(squareGmina(1, 15.0, 50.0, 0.05)));
        assertThat(idx.allNeighborsVisited(1, Set.of())).isFalse();
    }

    @Test
    void borderAreaIds_crossCountry_bothRim() {
        // Dwie stykające się gminy z RÓŻNYCH krajów → obie na obwodzie (każda ma sąsiada innego kraju).
        AreaCoverageIndex idx = FACTORY.build(List.of(
                UnvisitedArea.levelMulti(1, "a", 50.0, 14.9, List.of(new AreaPart(square(14.9, 50.0, 0.05), null)), 1, 4, "gmina"),
                UnvisitedArea.levelMulti(2, "b", 50.0, 15.0, List.of(new AreaPart(square(15.0, 50.0, 0.05), null)), 2, 4, "gmina")));
        assertThat(idx.borderAreaIds(Set.of(1, 2))).containsExactlyInAnyOrder(1, 2);
    }

    @Test
    void borderAreaIds_singleCountry_rimByNeighborCount() {
        // Grid 3×3 jeden kraj: środek (5) ma NAJWIĘCEJ sąsiadów (max-deg) → NIE rim; reszta (mniej sąsiadów) = obwód.
        List<UnvisitedArea> grid = new java.util.ArrayList<>();
        int id = 1;
        for (int row = -1; row <= 1; row++) {
            for (int col = -1; col <= 1; col++) {
                grid.add(squareGmina(id++, 15.0 + col * 0.1, 50.0 + row * 0.1, 0.05));
            }
        }
        AreaCoverageIndex idx = FACTORY.build(grid);
        assertThat(idx.borderAreaIds(Set.of(1, 2, 3, 4, 5, 6, 7, 8, 9)))
                .containsExactlyInAnyOrder(1, 2, 3, 4, 6, 7, 8, 9)
                .doesNotContain(5);
    }
}
