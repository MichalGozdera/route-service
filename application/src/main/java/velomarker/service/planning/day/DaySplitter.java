package velomarker.service.planning.day;

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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import velomarker.entity.ElevationProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * Dzieli policzoną geometrię trasy (z BRouter, wzbogaconą o elevation) na N dni
 * stosując algorytm „równego wysiłku": effort = km + {@link #DAY_EFFORT_ALPHA_KM_PER_M} × climb_m,
 * SPÓJNY z Coverage (params.alphaKmPerMeter = 0.1). Granice dni stawiane tak, by skumulowany effort
 * rozkładał się równo (totalEffort / N na dzień). Niezależnie od profilu (road/off-road) — Coverage
 * też nie rozróżnia.
 *
 * <p>Wynik: lista {@link DayBoundary} z indeksami w geometrii — wołający (PlanningOrchestrationService)
 * tnie {@code List<double[]>} po tych indeksach na N podlist (geometria dnia).
 */
public class DaySplitter {

    private static final Logger log = LoggerFactory.getLogger(DaySplitter.class);

    /** Współczynnik effortu wzniosu — SPÓJNY z Coverage (params.alphaKmPerMeter = 0.1). User: dziel dni
     *  EQUAL effort = km + 0.1 × climb_m. Niezależnie od profilu (road/off-road) — bo Coverage też nie rozróżnia. */
    public static final double DAY_EFFORT_ALPHA_KM_PER_M = 0.1;
    public static final double EFFORT_BASE_CLIMB_PER_180 = 1000.0; // default budżet wzniosu = 1000 m / 180 km

    /** Dzieli geometrię na {@code days} dni równego effortu (km + 0.1·climb). Budżety km/wzniosu NIE wpływają
     *  na podział — tnie to, co zostało zbudowane, na N równych części. */
    public List<DayBoundary> splitIntoDays(ElevationProfile profile, int days) {
        if (profile == null || profile.profile() == null || profile.profile().isEmpty() || days <= 0) {
            return List.of();
        }
        double[][] samples = toKmSamples(profile);
        Cumulative cum = accumulate(samples);
        int effectiveDays = resolveEffectiveDays(days, samples.length);
        double totalKm = samples[samples.length - 1][0];
        return placeBoundaries(samples, cum, totalKm, profile.gainM(), effectiveDays);
    }

    /** Konwersja profilu na próbki [cumDistKm, eleM]. UWAGA: ElevationProfile trzyma cumDist w METRACH. */
    private static double[][] toKmSamples(ElevationProfile profile) {
        return profile.profile().stream()
                .map(p -> new double[]{p[0] / 1000.0, p[1]})
                .toArray(double[][]::new);
    }

    private static Cumulative accumulate(double[][] samples) {
        int n = samples.length;
        double[] cumEffort = new double[n];
        double[] cumClimb = new double[n];
        double prevEle = samples[0][1];
        double prevKm = samples[0][0];
        for (int i = 1; i < n; i++) {
            double curKm = samples[i][0];
            double curEle = samples[i][1];
            double sliceKm = Math.max(0, curKm - prevKm);
            double sliceClimb = Math.max(0, curEle - prevEle);
            cumClimb[i] = cumClimb[i - 1] + sliceClimb;
            cumEffort[i] = cumEffort[i - 1] + sliceKm + sliceClimb * DAY_EFFORT_ALPHA_KM_PER_M;
            prevEle = curEle;
            prevKm = curKm;
        }
        return new Cumulative(cumEffort, cumClimb);
    }

    /** Gdy days ≥ liczba próbek, nie da się postawić days-1 unikalnych granic → redukcja do n-1 (warn),
     *  inaczej powstawały 0-km dummy dni. */
    private static int resolveEffectiveDays(int days, int n) {
        if (days < n) return days;
        int effectiveDays = Math.max(1, n - 1);
        log.warn("DaySplitter: zażądano {} dni, ale profil ma tylko {} sampli — redukuję do {} dni " +
                "(jeden dzień ≈ jedna próbka). Zwiększ DAY_SPLIT_ELEVATION_SAMPLES albo zmniejsz dni.",
                new Object[]{days, n, effectiveDays});
        return effectiveDays;
    }

    private static List<DayBoundary> placeBoundaries(double[][] samples, Cumulative cum,
                                                     double totalKm, double totalGain, int effectiveDays) {
        int n = samples.length;
        double targetPerDay = cum.effort()[n - 1] / effectiveDays;
        List<DayBoundary> result = new ArrayList<>(effectiveDays);
        int searchStart = 0;
        double dayStartKm = 0;
        double dayStartGain = 0;
        for (int day = 1; day <= effectiveDays; day++) {
            if (day == effectiveDays) {
                result.add(new DayBoundary(searchStart, n - 1, dayStartKm, totalKm,
                        totalKm - dayStartKm, totalGain - dayStartGain));
                break;
            }
            double targetEffort = targetPerDay * day;
            int boundary = searchStart;
            for (int i = searchStart + 1; i < n; i++) {
                boundary = i;
                if (cum.effort()[i] >= targetEffort) break;
            }
            // boundary nie ruszył do przodu = wyczerpane próbki; NIE pchaj 0-km wpisów (mylące "d2001=0,0km").
            if (boundary <= searchStart) {
                log.warn("DaySplitter: koniec próbek po {} dniach. Route prawdopodobnie za krótki na żądaną liczbę dni.",
                        day - 1);
                break;
            }
            double boundaryKm = samples[boundary][0];
            double dayGain = cum.climb()[boundary] - cum.climb()[searchStart];
            result.add(new DayBoundary(searchStart, boundary, dayStartKm, boundaryKm,
                    boundaryKm - dayStartKm, dayGain));
            searchStart = boundary;
            dayStartKm = boundaryKm;
            dayStartGain += dayGain;
        }
        return result;
    }
}
