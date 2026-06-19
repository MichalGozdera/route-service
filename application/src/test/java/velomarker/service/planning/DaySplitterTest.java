package velomarker.service.planning;

import org.junit.jupiter.api.Test;
import velomarker.entity.ElevationProfile;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DaySplitterTest {

    private final DaySplitter splitter = new DaySplitter();

    /** Buduje profile z linii prostej: każdy sample = [cumDistM, eleM]. */
    private static ElevationProfile flatProfile(double totalKm, int samples) {
        List<double[]> pts = new ArrayList<>(samples);
        double stepM = totalKm * 1000.0 / (samples - 1);
        for (int i = 0; i < samples; i++) {
            pts.add(new double[]{i * stepM, 100.0}); // płasko, ele=100m wszędzie
        }
        return new ElevationProfile(pts, 0, 0, 100, 100);
    }

    private static ElevationProfile profileWithHill(double totalKm, int samples, double peakM, int peakIdx) {
        List<double[]> pts = new ArrayList<>(samples);
        double stepM = totalKm * 1000.0 / (samples - 1);
        double gain = 0;
        double prevEle = 0;
        for (int i = 0; i < samples; i++) {
            double ele;
            if (i <= peakIdx) ele = (peakM * i) / peakIdx;
            else ele = peakM * (samples - 1 - i) / (samples - 1 - peakIdx);
            pts.add(new double[]{i * stepM, ele});
            if (i > 0 && ele > prevEle) gain += ele - prevEle;
            prevEle = ele;
        }
        return new ElevationProfile(pts, (int) Math.round(gain), (int) Math.round(gain), 0, (int) Math.round(peakM));
    }

    @Test
    void splitFlatTrip_intoEqualDays() {
        // 1000 km plaska trasa, 10 dni → kazdy ~100 km. elevBudget=300 m/d (low) zeby effort > 0.
        var p = flatProfile(1000, 200);
        var boundaries = splitter.splitIntoDays(p, 10, 100, 300, "fastbike");
        assertThat(boundaries).hasSize(10);
        double totalDist = boundaries.stream().mapToDouble(DaySplitter.DayBoundary::distanceKmSample).sum();
        assertThat(totalDist).isCloseTo(1000, within(1.0));
        for (var b : boundaries) {
            assertThat(b.distanceKmSample()).isCloseTo(100, within(10.0)); // ±10km na zaokraglenia
        }
    }

    @Test
    void splitTripWithHill_shorterDayContainsHill() {
        // 1000 km, ostra gorka 3000m w pierwszej 1/3 (peakIdx=60 z 200), 10 dni rownego effortu.
        // Dzien zawierajacy piętro jest krotszy niz srednia dni plaskich.
        var p = profileWithHill(1000, 200, 3000, 60);
        var boundaries = splitter.splitIntoDays(p, 10, 100, 300, "fastbike");
        assertThat(boundaries).hasSize(10);
        // Min dystans dnia powinien byc istotnie krotszy od max (efekt rownego effortu).
        double minDay = boundaries.stream().mapToDouble(DaySplitter.DayBoundary::distanceKmSample).min().orElseThrow();
        double maxDay = boundaries.stream().mapToDouble(DaySplitter.DayBoundary::distanceKmSample).max().orElseThrow();
        assertThat(maxDay - minDay).isGreaterThan(10.0); // co najmniej 10 km spread
    }

    @Test
    void equivalentKm_road_climbAboveRef_addsEffort() {
        // 100 km szosa, climb 600 m > ref 300 m → delta 300 → effort = 100 + (300/300)×20 = 120
        double effort = DaySplitter.equivalentKm(100, 600, 300, true);
        assertThat(effort).isCloseTo(120, within(0.1));
    }

    @Test
    void equivalentKm_road_climbBelowRef_subtractsEffort() {
        // 100 km szosa, climb 0 m, ref 300 m → delta -300 → effort = 100 - (300/300)×30 = 70
        double effort = DaySplitter.equivalentKm(100, 0, 300, true);
        assertThat(effort).isCloseTo(70, within(0.1));
    }

    @Test
    void equivalentKm_offroad_climbAboveRef_addsMoreEffort() {
        // 100 km offroad, climb 600 m > ref 300 m → delta 300 → effort = 100 + (300/300)×30 = 130
        double effort = DaySplitter.equivalentKm(100, 600, 300, false);
        assertThat(effort).isCloseTo(130, within(0.1));
    }

    @Test
    void equivalentKm_offroad_climbBelowRef_smallerBonusThanRoad() {
        // 100 km offroad, climb 0 → effort = 100 - (300/300)×20 = 80
        double effort = DaySplitter.equivalentKm(100, 0, 300, false);
        assertThat(effort).isCloseTo(80, within(0.1));
    }

    @Test
    void equivalentKm_floorAtMinDayKm() {
        // 30 km szosa, climb 3000 m, ref 300 → delta 2700 → effort = 30 + (2700/300)×20 = 210
        // ale floor MIN_DAY_KM=20 jest dla LIGHT TRIP. Dla HEAVY trip wartosci sa wieksze niz 20.
        double effort = DaySplitter.equivalentKm(30, 3000, 300, true);
        assertThat(effort).isCloseTo(210, within(0.1));
    }

    @Test
    void equivalentKm_negativeFloor_capsAtMinDay() {
        // 10 km szosa, climb 0, ref 1000 → delta -1000 → 10 - 100 = -90 → floor MIN_DAY_KM=20
        double effort = DaySplitter.equivalentKm(10, 0, 1000, true);
        assertThat(effort).isEqualTo(DaySplitter.MIN_DAY_KM);
    }

    @Test
    void equivalentKmRaw_noFloor_canGoNegative() {
        // 10 km flat, ref 1000 → bez floora wynik ujemny.
        double effort = DaySplitter.equivalentKmRaw(10, 0, 1000, true);
        assertThat(effort).isCloseTo(-90, within(0.1));
    }

    @Test
    void cumDistConversion_metersToKilometers() {
        // Wejscie w METRACH (1 000 000 = 1000 km). Po konwersji wewnetrznej totalKm = 1000.
        var p = flatProfile(1000, 200);
        var boundaries = splitter.splitIntoDays(p, 10, 100, 300, "fastbike");
        double totalKm = boundaries.stream().mapToDouble(DaySplitter.DayBoundary::distanceKmSample).sum();
        assertThat(totalKm).isCloseTo(1000, within(1.0));
    }

    @Test
    void emptyProfile_returnsEmpty() {
        var p = new ElevationProfile(List.of(), 0, 0, 0, 0);
        var boundaries = splitter.splitIntoDays(p, 5, 100, 1000, "fastbike");
        assertThat(boundaries).isEmpty();
    }

    @Test
    void zeroDays_returnsEmpty() {
        var p = flatProfile(100, 50);
        var boundaries = splitter.splitIntoDays(p, 0, 100, 300, "fastbike");
        assertThat(boundaries).isEmpty();
    }

    @Test
    void isRoadProfile_detectsKnownProfiles() {
        assertThat(DaySplitter.isRoadProfile("fastbike")).isTrue();
        assertThat(DaySplitter.isRoadProfile("fastbike-gminy")).isTrue();
        assertThat(DaySplitter.isRoadProfile("ultra")).isTrue();
        assertThat(DaySplitter.isRoadProfile("ultra-gminy")).isTrue();
        assertThat(DaySplitter.isRoadProfile("safety")).isTrue();
        assertThat(DaySplitter.isRoadProfile("trekking")).isFalse();
        assertThat(DaySplitter.isRoadProfile("trekking-gminy")).isFalse();
        assertThat(DaySplitter.isRoadProfile(null)).isTrue(); // default road
    }
}
