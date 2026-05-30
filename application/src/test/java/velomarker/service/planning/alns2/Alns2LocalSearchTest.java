package velomarker.service.planning.alns2;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Alns2LocalSearchTest {

    @Test
    void twoOpt_anchorsPreserved() {
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

        int swaps = Alns2LocalSearch.twoOpt(route);
        // 2-opt powinien znaleźć improvement (B i C były skrzyżowane)
        assertThat(swaps).isGreaterThan(0);
        // Anchors niezmienne
        assertThat(route.get(0)).isSameAs(originalStart);
        assertThat(route.get(route.size() - 1)).isSameAs(originalEnd);
    }

    @Test
    void relocate_movesSinglePoint() {
        // A źle umiejscowiony. Tour pre-relocate jest długi, po relocate krótszy.
        List<double[]> route = new ArrayList<>(List.of(
                new double[]{0, 0},      // start
                new double[]{5, 0},      // A (zła pozycja — daleko)
                new double[]{1, 0},      // B
                new double[]{2, 0},      // C
                new double[]{3, 0},      // D
                new double[]{6, 0}       // end
        ));
        double totalBefore = totalKm(route);
        Alns2LocalSearch.relocate(route);
        double totalAfter = totalKm(route);
        // Tour po relocate powinien być KRÓTSZY (improvement)
        assertThat(totalAfter).isLessThan(totalBefore);
    }

    private static double totalKm(List<double[]> route) {
        double total = 0;
        for (int i = 0; i < route.size() - 1; i++) {
            total += velomarker.service.planning.WaypointSelector.haversineKm(
                    route.get(i), route.get(i + 1));
        }
        return total;
    }

    @Test
    void emptyRoute_noOp() {
        List<double[]> empty = new ArrayList<>(List.of(
                new double[]{0, 0}, new double[]{1, 1}
        ));
        int swaps = Alns2LocalSearch.twoOpt(empty);
        int moves = Alns2LocalSearch.relocate(empty);
        assertThat(swaps).isZero();
        assertThat(moves).isZero();
    }

    @Test
    void windowedTwoOpt_fullWindow_matchesDefaultForSmallRoute() {
        List<double[]> a = new ArrayList<>(List.of(
                new double[]{0, 0}, new double[]{1, 1}, new double[]{3, 0},
                new double[]{2, 1}, new double[]{4, 0}, new double[]{5, 0}));
        List<double[]> b = new ArrayList<>(List.of(
                new double[]{0, 0}, new double[]{1, 1}, new double[]{3, 0},
                new double[]{2, 1}, new double[]{4, 0}, new double[]{5, 0}));
        Alns2LocalSearch.twoOpt(a);             // default: małe → pełny skan
        Alns2LocalSearch.twoOpt(b, b.size());   // jawne pełne okno
        assertThat(totalKm(a)).isEqualTo(totalKm(b));
    }

    @Test
    void windowedTwoOpt_neverWorsens_andKeepsAnchors() {
        List<double[]> route = new ArrayList<>();
        route.add(new double[]{0, 0});
        for (int i = 1; i <= 40; i++) {
            double jitter = (i % 2 == 0) ? 0.3 : -0.3; // lokalne zygzaki
            route.add(new double[]{i * 0.1, jitter});
        }
        route.add(new double[]{4.5, 0});
        double[] start = route.get(0);
        double[] end = route.get(route.size() - 1);
        double before = totalKm(route);
        Alns2LocalSearch.twoOpt(route, 8); // wąskie okno
        assertThat(totalKm(route)).isLessThanOrEqualTo(before + 1e-9);
        assertThat(route.get(0)).isSameAs(start);
        assertThat(route.get(route.size() - 1)).isSameAs(end);
    }
}
