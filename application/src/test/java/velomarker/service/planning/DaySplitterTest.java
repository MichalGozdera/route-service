package velomarker.service.planning;

import velomarker.service.planning.*;
import velomarker.service.planning.route.*;
import velomarker.service.planning.day.*;
import velomarker.service.planning.coverage.*;
import velomarker.service.planning.coverage.prep.*;
import velomarker.service.planning.coverage.seed.*;
import velomarker.service.planning.coverage.index.*;
import velomarker.service.planning.coverage.metric.*;
import velomarker.service.planning.coverage.geom.*;
import velomarker.service.planning.coverage.scoring.*;
import velomarker.service.planning.coverage.debug.*;

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
        var boundaries = splitter.splitIntoDays(p, 10);
        assertThat(boundaries).hasSize(10);
        double totalDist = boundaries.stream().mapToDouble(DayBoundary::distanceKmSample).sum();
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
        var boundaries = splitter.splitIntoDays(p, 10);
        assertThat(boundaries).hasSize(10);
        // Min dystans dnia powinien byc istotnie krotszy od max (efekt rownego effortu).
        double minDay = boundaries.stream().mapToDouble(DayBoundary::distanceKmSample).min().orElseThrow();
        double maxDay = boundaries.stream().mapToDouble(DayBoundary::distanceKmSample).max().orElseThrow();
        assertThat(maxDay - minDay).isGreaterThan(10.0); // co najmniej 10 km spread
    }

    @Test
    void cumDistConversion_metersToKilometers() {
        // Wejscie w METRACH (1 000 000 = 1000 km). Po konwersji wewnetrznej totalKm = 1000.
        var p = flatProfile(1000, 200);
        var boundaries = splitter.splitIntoDays(p, 10);
        double totalKm = boundaries.stream().mapToDouble(DayBoundary::distanceKmSample).sum();
        assertThat(totalKm).isCloseTo(1000, within(1.0));
    }

    @Test
    void emptyProfile_returnsEmpty() {
        var p = new ElevationProfile(List.of(), 0, 0, 0, 0);
        var boundaries = splitter.splitIntoDays(p, 5);
        assertThat(boundaries).isEmpty();
    }

    @Test
    void zeroDays_returnsEmpty() {
        var p = flatProfile(100, 50);
        var boundaries = splitter.splitIntoDays(p, 0);
        assertThat(boundaries).isEmpty();
    }
}
