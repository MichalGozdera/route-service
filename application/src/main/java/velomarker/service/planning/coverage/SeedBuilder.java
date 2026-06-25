package velomarker.service.planning.coverage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import velomarker.entity.planning.UnvisitedArea;
import velomarker.port.out.ElevationDataSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Orkiestrator budowy seeda pokrycia (wyniesiony z CoveragePlanner). Buduje trasę przez baseline-projection i odpala
 * dwie fazy odpowiedzialności: {@link InitGrowPhase} (dobieranie wstępne: score → grow do 110% → islands-fix) →
 * {@link FinalizePhase} (zakotwicz → cykl budżetowy ≤5: dobierz/utnij → refine → anchor → refine → cut → domknij dziury).
 * Anchor/cięcie/dobieranie-bliskie to osobne klasy ({@link Anchorer}/{@link SpurCutter}/{@link GrowNear}) wołane z faz.
 * Instancja per plan — kolaboratory wstrzykiwane w konstruktorze, spięte w {@link SeedContext}.
 */
final class SeedBuilder {

    private static final Logger log = LoggerFactory.getLogger(SeedBuilder.class);

    private final GminaIndex gminaIndex;
    private final List<UnvisitedArea> pool;
    private final Map<String, Double> rewards;
    private final EdgeRouter edgeRouter;
    private final RouteMetrics metrics;
    private final HilbertOrdering ordering;
    private final CoveragePlannerParameters params;
    private final boolean debugGeoJson;
    private final CoverageDebug debug;
    private final SeedOps ops;
    private final SeedContext ctx;

    SeedBuilder(GminaIndex gminaIndex, List<UnvisitedArea> pool, Map<String, Double> rewards,
                EdgeRouter edgeRouter, RouteMetrics metrics, HilbertOrdering ordering,
                ElevationDataSource elevation, CoveragePlannerParameters params, boolean debugGeoJson,
                double debugBudget, Map<Integer, String> debugAreaCat, Consumer<Boolean> snapToggle) {
        this.gminaIndex = gminaIndex;
        this.pool = pool;
        this.rewards = rewards;
        this.edgeRouter = edgeRouter;
        this.metrics = metrics;
        this.ordering = ordering;
        this.params = params;
        this.debugGeoJson = debugGeoJson;
        this.debug = new CoverageDebug(debugGeoJson, edgeRouter, metrics, gminaIndex, params, debugBudget, debugAreaCat);
        this.ops = new SeedOps(ordering);
        this.ctx = new SeedContext(edgeRouter, metrics, gminaIndex, ordering, pool, rewards, debug, ops, debugGeoJson, snapToggle);
    }

    /** Delegator dla orkiestratora (plan()) — render finalnej realnej geometrii po chunked BRouterze. */
    void debugGeometry(String phase, List<double[]> geometry, List<double[]> waypoints, double realKm) {
        debug.geometry(phase, geometry, waypoints, realKm);
    }

    /**
     * Buduje seed: baseline-projection → {@link InitGrowPhase} → {@link FinalizePhase}. Mutuje przekazane
     * {@code route} w miejscu. EFFORT_MULTIPLIER = road×(1+climb·alpha); pasmo budżetu [95,105]%, grow-ceiling 110%.
     */
    void greedySeedRoute(List<double[]> route, List<double[]> anchors, double targetEffort, double alphaKmPerMeter,
                         List<double[]> baseline) {
        final double ROAD_FACTOR = 1.3;
        final double CLIMB_PER_KM_ESTIMATE = 3.0;
        final double EFFORT_MULTIPLIER = ROAD_FACTOR * (1.0 + CLIMB_PER_KM_ESTIMATE * alphaKmPerMeter);

        // Cumulative km wzdłuż baseline (do projekcji punktów na korytarz).
        int bn = baseline.size();
        double[] baseCum = new double[bn];
        for (int i = 1; i < bn; i++) {
            baseCum[i] = baseCum[i - 1] + velomarker.service.planning.WaypointSelector.haversineKm(
                    baseline.get(i - 1), baseline.get(i));
        }
        List<double[]> anchorOnly = new ArrayList<>(route);
        List<SeedSel> selected = new ArrayList<>();
        // Stan trasy seeda zgrupowany — fazy dostają jeden obiekt zamiast 6 kolekcji.
        SeedRoute seed = new SeedRoute(route, selected, anchorOnly, anchors, baseline, baseCum);
        final double hiBand = targetEffort * 1.05;       // pasmo [95,105]%
        final double growCeiling = targetEffort * 1.10;  // INIT-GROW zbiera do 110%; FINALIZE ściąga do pasma
        long seedStartTs = System.currentTimeMillis();

        // JEDNA klasa dobierająca (ranking + wstawianie) — wspólna dla init-grow (batche) i finalize (hurt).
        CandidatePicker picker = new CandidatePicker(ctx, seed);

        // FAZA DOBIERANIA WSTĘPNEGO: pick batchami do 110% (Hilbert construction + checkpoint-optimise) → islands-fix.
        InitGrowPhase.IslandFixResult ig = new InitGrowPhase(
                ctx, seed, picker, targetEffort, hiBand, growCeiling, EFFORT_MULTIPLIER, seedStartTs).run();
        double realEffort = ig.realEffort();
        int totalPruned = ig.pruned();
        int totalRetried = ig.retried();

        debug.resetShots();
        if (debugGeoJson) debug.logShots("init-grow");

        // OSTATNIA FAZA: zakotwicz surowy seed → cykl budżetowy ≤5 (dobierz hurtem/utnij → refine→anchor→refine→cut).
        FinalizePhase.FinalizeResult fr = new FinalizePhase(
                ctx, seed, picker, targetEffort, hiBand, growCeiling, realEffort, ig.allCandidatesUsed()).run();
        realEffort = fr.realEffort();
        if (debugGeoJson) debug.logShots("seed-final");
        int densified = fr.grown();
        debug.skeleton("seed", route);
        if (debugGeoJson) debug.geometry("seed-real", metrics.realGeometry(route), route, metrics.realKm(route));

        logSeedSummary(selected.size(), route.size(), totalPruned, fr.trimmed(), densified, totalRetried,
                realEffort, targetEffort, seedStartTs);
        logTopLongLegs(route);
    }

    /** Loguje podsumowanie seeda: rozmiar/effort + STRZAŁY per powód. (PHASE BREAKDOWN/L2 loguje {@link InitGrowPhase}.) */
    private void logSeedSummary(int selectedSize, int routeSize, int totalPruned, int trimmed, int densified,
                                int totalRetried, double realEffort, double targetEffort, long seedStartTs) {
        log.info("Coverage seed ({}): +{} obszarów, removed={} islands, trimmed={}, grow-near={}, retried={} entry-points, real effort={}/{} ({}%) [v3.22: InitGrowPhase + FinalizePhase], route size={}",
                new Object[]{"distBase-sort", selectedSize, totalPruned, trimmed, densified, totalRetried,
                        Math.round(realEffort), Math.round(targetEffort),
                        Math.round(realEffort * 100.0 / targetEffort), routeSize});
        long seedWallS = (System.currentTimeMillis() - seedStartTs) / 1000;
        Map<String, Long> byReason = edgeRouter.realCallsByReason();
        log.info("Coverage STRZAŁY/plan (seed wall={}s, realne brouter.apply per powód): grow={} ogonek(relok={} scal={}) dziura(otocz={} trasa={}) pomiar={} inne={} | RAZEM realnych={} (misses={}; różnica = sliced-seedy bez BRoutera)",
                new Object[]{seedWallS,
                        byReason.getOrDefault("grow", 0L),
                        byReason.getOrDefault("ogonek-relokacja", 0L),
                        byReason.getOrDefault("ogonek-scalenie", 0L),
                        byReason.getOrDefault("dziura-otoczona", 0L),
                        byReason.getOrDefault("dziura-przy-trasie", 0L),
                        byReason.getOrDefault("pomiar", 0L),
                        byReason.getOrDefault("inne", 0L),
                        edgeRouter.realCalls(), edgeRouter.misses()});
    }

    /**
     * DIAGNOSTYKA: top-K najdłuższych legów (hav km / real km / ile RÓŻNYCH gmin kredytują). Duże #gmin = konieczny
     * transit (OK); #gmin≈0 = pusty TSP-nawrót (kandydat do naprawy). getEdge = cache (bez BRoutera).
     */
    private void logTopLongLegs(List<double[]> route) {
        final int LEG_TOPK = 15;
        List<Integer> legIdx = new ArrayList<>();
        for (int i = 0; i < route.size() - 1; i++) legIdx.add(i);
        legIdx.sort((x, y) -> Double.compare(
                velomarker.service.planning.WaypointSelector.haversineKm(route.get(y), route.get(y + 1)),
                velomarker.service.planning.WaypointSelector.haversineKm(route.get(x), route.get(x + 1))));
        StringBuilder legSb = new StringBuilder();
        for (int t = 0; t < Math.min(LEG_TOPK, legIdx.size()); t++) {
            int i = legIdx.get(t);
            double havKm = velomarker.service.planning.WaypointSelector.haversineKm(route.get(i), route.get(i + 1));
            EdgeCache.EdgeInfo e = edgeRouter.edge(route.get(i), route.get(i + 1));
            Set<Integer> gset = new HashSet<>();
            for (double[] p : e.geometry()) {
                UnvisitedArea a = gminaIndex.findGminaForPoint(p[0], p[1]);
                if (a != null) gset.add(a.areaId());
            }
            legSb.append(String.format(java.util.Locale.ROOT, " [hav=%.0f real=%.0f gmin=%d]",
                    havKm, e.distanceKm(), gset.size()));
        }
        log.info("Coverage seed top-{} długich legów (hav/real km, #gmin kredytowanych):{}", LEG_TOPK, legSb);
    }

    /**
     * Liczba ODRĘBNYCH wizyt per gmina (segmenty geometrii w ringu oddzielone gap > 10 km). ≥2 = gmina pokryta w
     * kilku miejscach → jej explicit waypoint redundantny. Jeden przebieg O(geom) grid-lookup. Static util — wołane
     * z {@link InitGrowPhase#fixIslands} i {@link CoverageDebug}.
     */
    static Map<Integer, Integer> countVisitsPerArea(List<double[]> geometry, GminaIndex index) {
        Map<Integer, Integer> visits = new HashMap<>();
        if (geometry == null || geometry.size() < 2) return visits;
        final double GAP_KM = 10.0;
        Map<Integer, Double> lastKm = new HashMap<>();
        double cum = 0;
        double[] prev = null;
        for (double[] p : geometry) {
            if (prev != null) cum += velomarker.service.planning.WaypointSelector.haversineKm(prev, p);
            prev = p;
            UnvisitedArea g = index.findGminaForPoint(p[0], p[1]);
            if (g == null) continue;
            int id = g.areaId();
            Double lk = lastKm.get(id);
            if (lk == null || cum - lk > GAP_KM) visits.merge(id, 1, Integer::sum);
            lastKm.put(id, cum);
        }
        return visits;
    }
}
