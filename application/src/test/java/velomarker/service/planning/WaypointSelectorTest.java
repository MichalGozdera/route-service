package velomarker.service.planning;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class WaypointSelectorTest {

    private final WaypointSelector selector = new WaypointSelector();

    @Test
    void straightLineDistanceKm_sumsHaversines() {
        // (14, 50) → (14, 51) ~ 111 km, (14, 51) → (15, 51) ~ 70 km → suma ~181 km
        double d = selector.straightLineDistanceKm(List.of(
                new double[]{14.0, 50.0},
                new double[]{14.0, 51.0},
                new double[]{15.0, 51.0}
        ));
        assertThat(d).isCloseTo(181.2, within(2.0));
    }

    @Test
    void haversineKm_zeroForSamePoint() {
        double d = WaypointSelector.haversineKm(new double[]{14.0, 50.0}, new double[]{14.0, 50.0});
        assertThat(d).isZero();
    }

    @Test
    void haversineKm_PragueToKošice_around540km() {
        double[] praha = {14.42, 50.08};
        double[] kosice = {21.26, 48.72};
        double d = WaypointSelector.haversineKm(praha, kosice);
        assertThat(d).isCloseTo(518, within(10.0)); // ~518 km linia prosta
    }
}
