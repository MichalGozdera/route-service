package velomarker.service.planning;

/**
 * Empiryczna kalibracja współczynnika {@code road / straight-line}. Trzyma DWA OSOBNE rejestry:
 *
 * <ul>
 *   <li>{@code roadAnchors} — dla rzadkich anchor'ów (start→via→meta bez obszarów). Typowo 1.2-1.6
 *       w zależności od najwyższego poziomu administracyjnego w intencie. Używane do bardzo grubych
 *       estymat (np. fallback split przy braku elevation).</li>
 *   <li>{@code roadAreas} — dla GĘSTO upakowanych obszarów (segmenty 1-15 km między centroidami).
 *       Zwykle WYŻSZY niż roadAnchors bo gminy wymuszają objazdy bocznymi drogami z dużą krzywizną.
 *       Używany do estymowania nadkładu drogi przez obszary w reconcile loop.</li>
 * </ul>
 *
 * <p>Dlaczego dwa rejestry, nie jeden EMA: probe na 4-anchorach (np. 1.5) i probe na 30 obszarach
 * (np. 1.07) reprezentują DWIE różne wielkości fizyczne. Uśredniając EMA dostalibyśmy ~1.28 i nie
 * wiadomo do czego stosować. Trzymając osobno: reconcile bierze roadAreas dla extra-from-areas,
 * a roadAnchors zostaje do scenariuszy bez obszarów.
 *
 * <p>Klasa nie jest thread-safe — jedna instancja per task (jeden wątek liczący).
 */
public class RoadFactorCalibrator {

    /** EMA smoothing factor — 0.3 = nowy pomiar ma 30% wagi. */
    private static final double EMA_ALPHA = 0.3;
    /** Mnożnik dla TSP (odważniejszy budżet niż realny). */
    private static final double BUDGET_FACTOR_RATIO = 0.85;
    /** Dolna granica budżetowego mnożnika. */
    private static final double BUDGET_FACTOR_FLOOR = 1.15;
    /** Default startowy gdy nic nie wiemy. */
    private static final double DEFAULT_ROAD_FACTOR = 1.5;
    /** Default dla areas gdy probe areas się nie powiedzie. */
    private static final double DEFAULT_AREAS_FACTOR = 1.5;

    /** Heurystyka per poziom administracyjny (najwyższy w intencie). */
    public enum LevelTier {
        /** Gmina (LAU2) — gęsta siatka, faktor wysoki. */
        MUNICIPALITY(1.9),
        /** Powiat (LAU1 / NUTS3). */
        DISTRICT(1.6),
        /** Województwo / land (NUTS2). */
        REGION(1.4),
        /** Kraj — autostrady. */
        COUNTRY(1.2);

        public final double factor;
        LevelTier(double factor) { this.factor = factor; }
    }

    private double roadAnchors;
    private double roadAreas;

    public RoadFactorCalibrator() {
        this.roadAnchors = DEFAULT_ROAD_FACTOR;
        this.roadAreas = DEFAULT_AREAS_FACTOR;
    }

    /** Probe BRouter na rzadkich anchorach (start→via→meta, ≤10 wp). */
    public void applyAnchorsProbe(double actualRoadKm, double straightKm) {
        if (straightKm <= 0) return;
        this.roadAnchors = clamp(actualRoadKm / straightKm);
    }

    /** Probe BRouter na gęstym fragmencie z obszarami (≤30 areas). */
    public void applyAreasProbe(double actualRoadKm, double straightKm) {
        if (straightKm <= 0) return;
        this.roadAreas = clamp(actualRoadKm / straightKm);
    }

    /** Fallback per poziom — gdy probe areas się nie powiedzie (timeout / 429). */
    public void applyAreasFallback(LevelTier tier) {
        this.roadAreas = (tier != null) ? tier.factor : DEFAULT_AREAS_FACTOR;
    }

    /** Fallback per poziom dla anchorów (gdy baseline probe się nie udał). */
    public void applyAnchorsFallback(LevelTier tier) {
        this.roadAnchors = (tier != null) ? tier.factor : DEFAULT_ROAD_FACTOR;
    }

    /** EMA update po policzeniu pełnej trasy (kolejne sesje mają lepszy estymat). */
    public void updateAreasFromActual(double actualExtraKm, double extraStraightKm) {
        if (extraStraightKm <= 0) return;
        double observed = clamp(actualExtraKm / extraStraightKm);
        this.roadAreas = EMA_ALPHA * observed + (1 - EMA_ALPHA) * this.roadAreas;
    }

    public double roadAnchors() { return roadAnchors; }
    public double roadAreas() { return roadAreas; }

    /** Mnożnik dla optymalizatora TSP (odważniejszy — niższy = więcej obszarów się zmieści). */
    public double budget() {
        return Math.max(BUDGET_FACTOR_FLOOR, roadAreas * BUDGET_FACTOR_RATIO);
    }

    private static double clamp(double value) {
        if (value < 1.05) return 1.05;
        if (value > 3.5) return 3.5;
        return value;
    }
}
