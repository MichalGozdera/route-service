package velomarker.service.planning.coverage.seed;

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

import velomarker.entity.planning.UnvisitedArea;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Współdzielone kolaboratory seeda (jeden komplet per plan) wstrzykiwane do klas odpowiedzialności.
 *
 * <p>{@code tileReachCapKm} = miękki cap zasięgu „trzymaj się lokalnie" dla bramki skoku ({@code gate})
 * w InitGrowPhase i grow w FinalizePhase. COVERAGE (gminy) przekazuje {@link #NO_REACH_CAP} (brak capu →
 * zachowanie bit-identyczne). TILES przekazuje ~22 km, by greedy nie robił dalekiego nieopłacalnego wypadu.
 *
 * <p>{@code tileMaxDetourKm} = max koszt wcięcia (detour cheapest-insert) kandydata. TILES: odrzuca
 * kafelki wymagające zbyt dużego objazdu (Łowicz = wielki detour, palec boczny = mały zbędny) — jedno
 * kryterium na oba. COVERAGE: {@link #NO_DETOUR_LIMIT} (wyłączone → bit-identycznie, gminy mają swój mechanizm).
 *
 * <p>{@code deepDepthM} = próg „głębokiego" wjazdu (m) dla kotwiczenia (Anchorer) i pogłębiania (Deepener).
 * COVERAGE = {@link #DEFAULT_DEEP_DEPTH_M} (220m, bit-identycznie); TILES = 70m (kafelek z14 ~2.4km → lekki
 * wjazd zamiast głębokiego). Progi pochodne (250/300m gminowe) skalują się proporcjonalnie względem tego.
 */
public record SeedContext(EdgeRouter edgeRouter,
                   RouteMetrics metrics,
                   CoverageAreaIndex coverageAreaIndex,
                   HilbertOrdering ordering,
                   List<UnvisitedArea> pool,
                   Map<String, Double> rewards,
                   CoverageDebug debug,
                   SeedOps ops,
                   boolean debugGeoJson,
                   Consumer<Boolean> snapToggle,
                   double tileReachCapKm,
                   double tileMaxDetourKm,
                   double deepDepthM) {

    /** Brak capu (COVERAGE) — {@code Math.min(gate, NO_REACH_CAP)} == gate, bit-identycznie. */
    public static final double NO_REACH_CAP = Double.MAX_VALUE;
    /** Brak limitu detour (COVERAGE) — filtr wyłączony. */
    public static final double NO_DETOUR_LIMIT = Double.MAX_VALUE;
    /** Domyślny próg głębokiego wjazdu (m) — gminy (COVERAGE). Bit-identyczne sprzed parametryzacji TILES. */
    public static final double DEFAULT_DEEP_DEPTH_M = 220.0;

    /** Czy cap jest aktywny (TILES). {@code <=0} lub {@link #NO_REACH_CAP} = wyłączony. */
    public boolean reachCapActive() {
        return tileReachCapKm > 0 && tileReachCapKm < NO_REACH_CAP;
    }

    /** Efektywny cap do {@code Math.min(gate, cap)} — gdy nieaktywny zwraca {@link #NO_REACH_CAP}. */
    public double effectiveReachCapKm() {
        return reachCapActive() ? tileReachCapKm : NO_REACH_CAP;
    }

    /** Czy filtr detour jest aktywny (TILES). {@code <=0} lub {@link #NO_DETOUR_LIMIT} = wyłączony. */
    public boolean detourFilterActive() {
        return tileMaxDetourKm > 0 && tileMaxDetourKm < NO_DETOUR_LIMIT;
    }
}
