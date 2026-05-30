package velomarker.service.planning;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RoadFactorCalibratorTest {

    @Test
    void defaults_areReasonable() {
        var c = new RoadFactorCalibrator();
        assertThat(c.roadAnchors()).isEqualTo(1.5);
        assertThat(c.roadAreas()).isEqualTo(1.5);
    }

    @Test
    void anchorsProbe_setsRoadAnchorsOnly_notAreas() {
        var c = new RoadFactorCalibrator();
        c.applyAnchorsProbe(160.0, 100.0);
        assertThat(c.roadAnchors()).isEqualTo(1.6);
        assertThat(c.roadAreas()).isEqualTo(1.5); // unchanged
    }

    @Test
    void areasProbe_setsRoadAreasOnly_notAnchors() {
        var c = new RoadFactorCalibrator();
        c.applyAreasProbe(107.0, 100.0);
        assertThat(c.roadAreas()).isEqualTo(1.07);
        assertThat(c.roadAnchors()).isEqualTo(1.5); // unchanged
    }

    @Test
    void anchorsProbe_zeroStraight_isNoOp() {
        var c = new RoadFactorCalibrator();
        c.applyAnchorsProbe(100, 0);
        assertThat(c.roadAnchors()).isEqualTo(1.5);
    }

    @Test
    void clamp_lowerBound_1_05() {
        var c = new RoadFactorCalibrator();
        c.applyAreasProbe(50, 100); // ratio 0.5 → clamped to 1.05
        assertThat(c.roadAreas()).isEqualTo(1.05);
    }

    @Test
    void clamp_upperBound_3_5() {
        var c = new RoadFactorCalibrator();
        c.applyAreasProbe(500, 100); // ratio 5.0 → clamped to 3.5
        assertThat(c.roadAreas()).isEqualTo(3.5);
    }

    @Test
    void areasFallback_perTier() {
        var c = new RoadFactorCalibrator();
        c.applyAreasFallback(RoadFactorCalibrator.LevelTier.MUNICIPALITY);
        assertThat(c.roadAreas()).isEqualTo(1.9);
        c.applyAreasFallback(RoadFactorCalibrator.LevelTier.DISTRICT);
        assertThat(c.roadAreas()).isEqualTo(1.6);
        c.applyAreasFallback(RoadFactorCalibrator.LevelTier.REGION);
        assertThat(c.roadAreas()).isEqualTo(1.4);
        c.applyAreasFallback(RoadFactorCalibrator.LevelTier.COUNTRY);
        assertThat(c.roadAreas()).isEqualTo(1.2);
    }

    @Test
    void anchorsFallback_perTier() {
        var c = new RoadFactorCalibrator();
        c.applyAnchorsFallback(RoadFactorCalibrator.LevelTier.REGION);
        assertThat(c.roadAnchors()).isEqualTo(1.4);
        assertThat(c.roadAreas()).isEqualTo(1.5); // unchanged
    }

    @Test
    void updateAreasFromActual_EMA_with_alpha_0_3() {
        var c = new RoadFactorCalibrator();
        c.applyAreasProbe(200, 100); // roadAreas = 2.0
        c.updateAreasFromActual(100, 100); // observed = 1.05 (clamped)
        // EMA: 0.3 × 1.05 + 0.7 × 2.0 = 0.315 + 1.4 = 1.715
        assertThat(c.roadAreas()).isCloseTo(1.715, within(0.001));
    }

    @Test
    void budget_isRoadAreasTimes_0_85_withFloor_1_15() {
        var c = new RoadFactorCalibrator();
        c.applyAreasProbe(200, 100); // roadAreas = 2.0
        assertThat(c.budget()).isCloseTo(1.7, within(0.001)); // 2.0 × 0.85
        c.applyAreasProbe(110, 100); // roadAreas = 1.1
        assertThat(c.budget()).isEqualTo(1.15); // 1.1 × 0.85 = 0.935 → floor 1.15
    }
}
