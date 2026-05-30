package velomarker.service.planning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import velomarker.entity.ElevationProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * Dzieli policzoną geometrię trasy (z BRouter, wzbogaconą o elevation) na N dni
 * stosując algorytm „równego wysiłku":
 *
 * <p>Każde 300 m wzniosu względem referencji C (=elevBudget/days) przesuwa ekwiwalent km:
 * <pre>
 *   szosa,    +300 m nad C → ekwiwalent −20 km dnia
 *   szosa,    −300 m pod C → ekwiwalent +30 km dnia (zjazd w gratisie)
 *   off-road, +300 m nad C → ekwiwalent −30 km dnia
 *   off-road, −300 m pod C → ekwiwalent +20 km dnia
 * </pre>
 *
 * <p>Floor {@link #MIN_DAY_KM} = 20 — najcięższy alpejski etap nie wychodzi na 5 km.
 * Dzień w Tatrach (3000 m wzniosu) dostaje krótszy fizycznie odcinek niż na Mazurach (300 m).
 *
 * <p>Wynik: lista {@link DayBoundary} z indeksami w geometrii — wołający (PlanningOrchestrationService)
 * tnie {@code List<double[]>} po tych indeksach na N podlist (geometria dnia).
 */
public class DaySplitter {

    private static final Logger log = LoggerFactory.getLogger(DaySplitter.class);

    public static final double MIN_DAY_KM = 20.0;
    public static final double ROAD_KM_PER_300_UP = 20.0;
    public static final double ROAD_KM_PER_300_DOWN = 30.0;
    public static final double OFFROAD_KM_PER_300_UP = 30.0;
    public static final double OFFROAD_KM_PER_300_DOWN = 20.0;
    public static final double EFFORT_BASE_CLIMB_PER_180 = 1000.0; // default budżet wzniosu = 1000 m / 180 km

    /**
     * Granice dnia: indeksy w SAMPLE ElevationProfile + km w skali sample (do logów/diagnostyki).
     * Orchestration mapuje {@code startSampleIdx/endSampleIdx} liniowo na indeksy w pełnej geometrii
     * BRouter (downsample jest uniform-by-INDEX, więc mapping {@code fullIdx = round(sampleIdx × (fullSize-1)/(sampleCount-1))}
     * jest dokładny) i liczy realny dystans z {@code fullCumKm}. Pola {@code *Sample} NIE służą do
     * obliczania finalnego dystansu dnia — to byłby błąd taki jak stary linear rescale.
     */
    public record DayBoundary(int startSampleIdx, int endSampleIdx,
                              double startKmSample, double endKmSample,
                              double distanceKmSample, double elevationGain) {
    }

    /**
     * @param profile   elevation profile dla całej geometrii (cumulative km + ele per sample)
     * @param days      ile dni
     * @param kmPerDay  budżet km/dzień
     * @param elevBudgetPerDay  budżet wzniosu/dzień (m); null = default {@link #EFFORT_BASE_CLIMB_PER_180}
     * @param brouterProfile  nazwa profilu BRouter (do rozróżnienia szosa/off-road)
     */
    public List<DayBoundary> splitIntoDays(ElevationProfile profile, int days, int kmPerDay,
                                           Integer elevBudgetPerDay, String brouterProfile) {
        if (profile == null || profile.profile() == null || profile.profile().isEmpty() || days <= 0) {
            return List.of();
        }
        // UWAGA: LocalHgtElevationClient zwraca cumDist w METRACH (haversineMeters). Konwertujemy
        // tutaj na KILOMETRY — wszystkie obliczenia w DaySplitter zakładają km. Bez tego konwersji
        // dystans dnia wychodzi ×1000 (10 dni × 800 km daje "totalKm=811189" w summary).
        double[][] samples = profile.profile().stream()
                .map(p -> new double[]{p[0] / 1000.0, p[1]})
                .toArray(double[][]::new);
        // samples[i] = [cumDistKm, eleM]
        double totalKm = samples[samples.length - 1][0];
        double totalGain = profile.gainM();

        boolean isRoad = isRoadProfile(brouterProfile);
        double refClimbPerDay = (elevBudgetPerDay != null && elevBudgetPerDay > 0)
                ? elevBudgetPerDay
                : Math.max(300.0, kmPerDay * EFFORT_BASE_CLIMB_PER_180 / 180.0);

        // LINEAR UNITS: effort = km + climb_m * (KM_PER_300_UP / 300).
        // Dla szosy: 0.0667 km per 1m wzniosu (= 20 km kary za 300m). Dla offroad: 0.1 km/m (= 30/300).
        // BEZ asymetrii UP/DOWN i BEZ bonusu za "płaski dzień".
        // Wcześniej formula equivalentKm operowała na delta = climb - ref (z proporcjonalnym ref),
        // dawała bonus DUZE km za dni gdzie cumClimb < cumRef. Skutek: d5=381 km / 2341 m wzniosu
        // mialo cumRef=4523m -> delta=-2182m -> +218km bonusu -> effort 163 = "rowne innym dniom"
        // mathematicznie ale absurdalne fizycznie (381 km dziennie). Linear unit eliminuje ten
        // artefakt: dzien dluzszy tylko gdy ma proporcjonalnie mniej wzniosu, ale rozsadnie.
        double effortPerMClimb = (isRoad ? ROAD_KM_PER_300_UP : OFFROAD_KM_PER_300_UP) / 300.0;
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
            cumEffort[i] = cumEffort[i - 1] + sliceKm + sliceClimb * effortPerMClimb;
            prevEle = curEle;
            prevKm = curKm;
        }
        double totalEffort = cumEffort[n - 1];
        // SAFETY: gdy żądana liczba dni > liczby sampli profilu, nie da się postawić days-1 unikalnych
        // granic w przestrzeni sample → reszta dni dostawała pusty (boundary=n-1, distKm=0) wpis. To
        // tworzyło iluzję planu na N dni z dziesiątkami/tysiącami 0-km dummy w day-distribution.
        // Zamiast tego: redukuj effectiveDays do n-1 (max sensowne granice) i loguj warn.
        int effectiveDays = days;
        if (days >= n) {
            effectiveDays = Math.max(1, n - 1);
            log.warn("DaySplitter: zażądano {} dni, ale profil ma tylko {} sampli — redukuję do {} dni " +
                    "(jeden dzień ≈ jedna próbka). Zwiększ DAY_SPLIT_ELEVATION_SAMPLES albo zmniejsz dni.",
                    new Object[]{days, n, effectiveDays});
        }
        double targetPerDay = totalEffort / effectiveDays;

        List<DayBoundary> result = new ArrayList<>(effectiveDays);
        int searchStart = 0;
        double dayStartKm = 0;
        double dayStartGain = 0;
        for (int day = 1; day <= effectiveDays; day++) {
            if (day == effectiveDays) {
                double distKm = totalKm - dayStartKm;
                double gainM = totalGain - dayStartGain;
                result.add(new DayBoundary(searchStart, n - 1, dayStartKm, totalKm, distKm, gainM));
                break;
            }
            double targetEffort = targetPerDay * day;
            int boundary = searchStart;
            for (int i = searchStart + 1; i < n; i++) {
                if (cumEffort[i] >= targetEffort) {
                    boundary = i;
                    break;
                }
                boundary = i;
            }
            // Jeśli boundary nie ruszył się do przodu (wyczerpana próbka, ale dni jeszcze do utworzenia),
            // przerwij i loguj. NIE pchaj 0-km wpisów — to wprowadzało użytkownika w błąd ("d2001=0,0km/0m").
            if (boundary <= searchStart) {
                log.warn("DaySplitter: koniec próbek po {} dniach (zażądano {}). Możliwe że route za krótki " +
                        "na żądaną liczbę dni — generuję {} dni faktycznych.",
                        new Object[]{day - 1, days, day - 1});
                break;
            }
            double boundaryKm = samples[boundary][0];
            double distKm = boundaryKm - dayStartKm;
            double dayGain = cumClimb[boundary] - cumClimb[searchStart];
            result.add(new DayBoundary(searchStart, boundary, dayStartKm, boundaryKm, distKm, dayGain));
            searchStart = boundary;
            dayStartKm = boundaryKm;
            dayStartGain += dayGain;
        }
        return result;
    }

    /**
     * Ekwiwalent km dnia: przelicza realny dystans + wznios na „wysiłek" w km względem referencji C.
     * Bazuje na piecewise wzorze z reguły usera (per 300m). Floor: {@link #MIN_DAY_KM}.
     *
     * <p>Floor stosujemy DO DNI (najcięższy alpejski etap ≥ 20 km), NIE do cumulative effort —
     * dla cumulative użyj {@link #equivalentKmRaw}. Floor na cumulative psuje monotonię na małych
     * kawałkach (10 km flat → effort = 4.4 → floor 20; 20 km flat → effort 8.9 → floor 20 →
     * pierwsza 1/3 trasy ma stały effort 20, target dnia trafia w przypadkowe miejsce).
     */
    public static double equivalentKm(double km, double climbM, double refClimbPerDay, boolean isRoad) {
        return Math.max(equivalentKmRaw(km, climbM, refClimbPerDay, isRoad), MIN_DAY_KM);
    }

    /** Wariant bez floor — do cumulative effort (split na dni). Może być &lt; MIN_DAY_KM. */
    public static double equivalentKmRaw(double km, double climbM, double refClimbPerDay, boolean isRoad) {
        double delta = climbM - refClimbPerDay;
        double per300;
        if (delta >= 0) {
            per300 = isRoad ? ROAD_KM_PER_300_UP : OFFROAD_KM_PER_300_UP;
            return km + (delta / 300.0) * per300;
        } else {
            per300 = isRoad ? ROAD_KM_PER_300_DOWN : OFFROAD_KM_PER_300_DOWN;
            return km - (Math.abs(delta) / 300.0) * per300;
        }
    }

    /** Rozpoznaje profil szosowy vs off-road (do rozróżnienia bonusu/penalty wzniosu). */
    public static boolean isRoadProfile(String brouterProfile) {
        if (brouterProfile == null) return true;
        String p = brouterProfile.toLowerCase();
        return p.contains("fastbike") || p.contains("safety") || p.contains("car");
    }
}
