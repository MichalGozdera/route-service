package velomarker.service.planning.alns2;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RegionFactorGridTest {

    @Test
    void freshCell_usesDefaults_andNeedsProbe() {
        var grid = new RegionFactorGrid(0.5, 25);
        var cell = grid.cellFor(19.0, 52.0);
        assertThat(grid.fDist(cell)).isEqualTo(RegionFactorGrid.DEFAULT_F_DIST);
        assertThat(grid.fClimbPerKm(cell)).isEqualTo(RegionFactorGrid.DEFAULT_F_CLIMB_PER_KM);
        assertThat(grid.needsProbe(cell)).isTrue();
        assertThat(grid.calibratedCells()).isZero();
    }

    @Test
    void firstRealSample_setsFactorsExactly_andClearsProbe() {
        var grid = new RegionFactorGrid(0.5, 25);
        var cell = grid.cellFor(19.0, 52.0);
        // realny 13 km na 10 km haversine → fDist 1.3; 1300 m wzniosu na 13 km → 100 m/km
        grid.recordReal(cell, 13.0, 10.0, 1300.0);
        assertThat(grid.fDist(cell)).isEqualTo(1.3);
        assertThat(grid.fClimbPerKm(cell)).isEqualTo(100.0);
        assertThat(grid.needsProbe(cell)).isFalse();
        assertThat(grid.calibratedCells()).isEqualTo(1);
    }

    @Test
    void secondSample_blendsViaEma() {
        var grid = new RegionFactorGrid(0.5, 25);
        var cell = grid.cellFor(19.0, 52.0);
        grid.recordReal(cell, 10.0, 10.0, 0.0);    // fDist 1.0
        grid.recordReal(cell, 20.0, 10.0, 0.0);    // nowy fDist 2.0 → EMA 0.4*2 + 0.6*1 = 1.4
        assertThat(grid.fDist(cell)).isEqualTo(1.4, org.assertj.core.api.Assertions.within(1e-9));
    }

    @Test
    void recalibrateEvery_triggersProbeAgain() {
        var grid = new RegionFactorGrid(0.5, 3);
        var cell = grid.cellFor(19.0, 52.0);
        grid.recordReal(cell, 13.0, 10.0, 0.0);
        assertThat(grid.needsProbe(cell)).isFalse();
        grid.recordProxyUse(cell); // 1
        grid.recordProxyUse(cell); // 2
        assertThat(grid.needsProbe(cell)).isFalse();
        grid.recordProxyUse(cell); // 3 == recalibrateEvery
        assertThat(grid.needsProbe(cell)).isTrue();
    }

    @Test
    void distinctRegions_haveIndependentCells() {
        var grid = new RegionFactorGrid(0.5, 25);
        var west = grid.cellFor(15.0, 52.0);
        var east = grid.cellFor(23.0, 52.0);
        grid.recordReal(west, 11.0, 10.0, 0.0);   // fDist 1.1
        grid.recordReal(east, 15.0, 10.0, 0.0);   // fDist 1.5
        assertThat(grid.fDist(west)).isEqualTo(1.1);
        assertThat(grid.fDist(east)).isEqualTo(1.5);
        assertThat(grid.calibratedCells()).isEqualTo(2);
    }
}
