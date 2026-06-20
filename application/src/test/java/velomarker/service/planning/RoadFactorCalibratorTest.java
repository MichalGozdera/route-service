package velomarker.service.planning;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RoadFactorCalibratorTest {

    @Test
    void measure_setsRoadAreasFromRatio() {
        var c = new RoadFactorCalibrator();
        c.measure(160.0, 100.0);
        assertThat(c.roadAreas()).isEqualTo(1.6);
    }

    @Test
    void measure_overrides_previousValue() {
        var c = new RoadFactorCalibrator();
        c.measure(160.0, 100.0); // seed (baseline) → 1.6
        c.measure(190.0, 100.0); // density probe → 1.9
        assertThat(c.roadAreas()).isEqualTo(1.9);
    }

    @Test
    void measure_zeroStraight_isNoOp() {
        var c = new RoadFactorCalibrator();
        c.measure(160.0, 100.0);
        c.measure(100.0, 0.0); // degenerate → ignored
        assertThat(c.roadAreas()).isEqualTo(1.6);
    }

    @Test
    void clamp_lowerBound_1_05() {
        var c = new RoadFactorCalibrator();
        c.measure(50, 100); // ratio 0.5 → clamped to 1.05
        assertThat(c.roadAreas()).isEqualTo(1.05);
    }

    @Test
    void clamp_upperBound_3_5() {
        var c = new RoadFactorCalibrator();
        c.measure(500, 100); // ratio 5.0 → clamped to 3.5
        assertThat(c.roadAreas()).isEqualTo(3.5);
    }
}
