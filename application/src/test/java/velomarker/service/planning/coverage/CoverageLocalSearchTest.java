package velomarker.service.planning.coverage;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CoverageLocalSearchTest {

    @Test
    void optimize_anchorsPreserved() {
        // Route z krzyżującymi krawędziami: start → A → B → C → D → end gdzie A i C są blisko
        List<double[]> route = new ArrayList<>(List.of(
                new double[]{0, 0},      // start
                new double[]{1, 1},      // A
                new double[]{3, 0},      // B (far east)
                new double[]{2, 1},      // C (blisko A)
                new double[]{4, 0},      // D
                new double[]{5, 0}       // end
        ));
        double[] originalStart = route.get(0);
        double[] originalEnd = route.get(route.size() - 1);

        int moves = CoverageLocalSearch.optimize(route);
        assertThat(moves).isGreaterThan(0);                 // skrzyżowanie B/C → ruch
        assertThat(route.get(0)).isSameAs(originalStart);   // anchory niezmienne
        assertThat(route.get(route.size() - 1)).isSameAs(originalEnd);
    }

    @Test
    void optimize_shortensTour() {
        // A źle umiejscowiony (daleko). Po rozplątaniu tour krótszy.
        List<double[]> route = new ArrayList<>(List.of(
                new double[]{0, 0},      // start
                new double[]{5, 0},      // A (zła pozycja — daleko)
                new double[]{1, 0},      // B
                new double[]{2, 0},      // C
                new double[]{3, 0},      // D
                new double[]{6, 0}       // end
        ));
        double before = totalKm(route);
        CoverageLocalSearch.optimize(route);
        assertThat(totalKm(route)).isLessThan(before);
    }

    @Test
    void optimize_catchesLongDetour_geoCloseFarInOrder() {
        // Pas punktów wzdłuż x, a tuż przed metą punkt geograficznie BLISKO startu (długi nawrót w kolejności).
        // k-nearest (geograficzny) ma go znaleźć i przenieść na początek — okno-kolejność by nie dało rady.
        List<double[]> route = new ArrayList<>();
        route.add(new double[]{0, 0});                      // start
        for (int i = 1; i <= 30; i++) route.add(new double[]{i * 0.1, 0}); // pas x=0.1..3.0
        route.add(new double[]{0.05, 0.02});                // blisko startu, ale na pozycji ~31 (nawrót)
        route.add(new double[]{3.1, 0});                    // end
        double before = totalKm(route);
        CoverageLocalSearch.optimize(route);
        assertThat(totalKm(route)).isLessThan(before);      // nawrót rozplątany (or-opt przeniósł punkt)
    }

    @Test
    void shortRoute_noOp() {
        List<double[]> tiny = new ArrayList<>(List.of(new double[]{0, 0}, new double[]{1, 1}));
        assertThat(CoverageLocalSearch.optimize(tiny)).isZero();
    }

    private static double totalKm(List<double[]> route) {
        double total = 0;
        for (int i = 0; i < route.size() - 1; i++) {
            total += velomarker.service.planning.WaypointSelector.haversineKm(route.get(i), route.get(i + 1));
        }
        return total;
    }
}
