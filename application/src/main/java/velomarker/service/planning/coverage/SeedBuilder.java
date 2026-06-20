package velomarker.service.planning.coverage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import velomarker.entity.RouteCalculation;
import velomarker.entity.planning.RoutePreferences;
import velomarker.entity.planning.UnvisitedArea;
import velomarker.entity.planning.Waypoint;
import velomarker.port.out.ElevationDataSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Silnik budowy seeda pokrycia (wyniesiony z CoveragePlanner). Buduje trasę przez baseline-projection
 * + compact-loop (anchor → 2opt → tailPrune → grow) → trim → holefill, mutując {@link SeedRoute} w miejscu.
 * Instancja per plan — kolaboratory (routing/miary/index) wstrzykiwane w konstruktorze.
 */
final class SeedBuilder {

    private static final Logger log = LoggerFactory.getLogger(SeedBuilder.class);

    private final GminaIndex gminaIndex;
    private final List<UnvisitedArea> pool;
    private final Map<String, Double> rewards;
    private final EdgeRouter edgeRouter;
    private final RouteMetrics metrics;
    private final HilbertOrdering ordering;
    private final ElevationDataSource elevation;
    private final CoveragePlannerParameters params;
    private final boolean debugGeoJson;
    /** Cache punkt→gmina (memoizacja point-in-polygon) — budowany w greedySeedRoute. */
    private Function<double[], UnvisitedArea> findGminaCached;
    // ADMIN DEBUG: kontekst do linii ROUTE-STATS (budżet/gminy/kategorie).
    private double debugBudget;
    private GminaIndex debugGminaIndex;
    private Map<Integer, String> debugAreaCat;
    /** STRZAŁY/plan per faza (debug-only). */
    private Map<String, Long> shots;
    /** Instrumentacja wall-time seeda (log-only). */
    private long tRebuildNs, tTwoOptNs, tRouteEffortNs, tEvalNs, tVisitsNs, tPruneNs;

    SeedBuilder(GminaIndex gminaIndex, List<UnvisitedArea> pool, Map<String, Double> rewards,
                EdgeRouter edgeRouter, RouteMetrics metrics, HilbertOrdering ordering,
                ElevationDataSource elevation, CoveragePlannerParameters params, boolean debugGeoJson,
                double debugBudget, Map<Integer, String> debugAreaCat) {
        this.gminaIndex = gminaIndex;
        this.pool = pool;
        this.rewards = rewards;
        this.edgeRouter = edgeRouter;
        this.metrics = metrics;
        this.ordering = ordering;
        this.elevation = elevation;
        this.params = params;
        this.debugGeoJson = debugGeoJson;
        this.debugBudget = debugBudget;
        this.debugGminaIndex = gminaIndex;
        this.debugAreaCat = debugAreaCat;
    }
    /**
     * SEED przez BASELINE-PROJECTION — O(N log N), zamiast cheapest-insertion O(N × E² × S).
     *
     * <p>Iter 11 perf fix. Stary seed (cheapest insertion) skanował wszystkie N kandydatów ×
     * wszystkie krawędzie trasy (rosnące do ~400) × S sample points per insert → miliardy
     * operacji haversine dla gęstych klastrów (np. 3621 gmin CZ, NN 2,3 km). Zjadał cały
     * budżet czasu (4 min), SA nie dostawał ani jednej iteracji.
     *
     * <p>Nowy algorytm:
     * <ol>
     *   <li>Per area: najbliższy sample point do baseline + jego projekcja (cumKm wzdłuż baseline)
     *       + reward/corridor score.</li>
     *   <li>Sort po score DESC, greedy take aż effort_proxy ≈ targetEffort.</li>
     *   <li>Wybrane + anchory posortuj po projekcji ASC → trasa płynie wzdłuż korytarza
     *       start→via→end, zbierając obszary po drodze.</li>
     * </ol>
     * Korzyść podwójna: szybkość (O(N log N)) + silne trzymanie korytarza (Fix 1).
     */
    /**
     * FAZA 1 seeda: dla każdej gminy licz punkt (najgłębszy interior / fallback sample), jego odległość
     * od korytarza (baseline) i score = reward/detour; zwróć listę posortowaną wg distBase ważonego
     * rewardem (blisko korytarza + cenne pierwsze). Hilbert (orderKey) zaszyty w `proj` do późniejszego
     * porządkowania 2D trasy.
     */
    private List<SeedSel> seedScoreAndOrder(List<double[]> baseline, double effortMultiplier) {
        int baselineSize = baseline.size();
        List<SeedSel> scored = new ArrayList<>(pool.size());
        for (UnvisitedArea area : pool) {
            double[] point = gminaIndex.deepestInteriorPoint(area.areaId());
            if (point == null) point = gminaIndex.samplePointsFor(area)[0]; // fallback gdy MIC null
            double distToBaseline = Double.MAX_VALUE;
            for (int j = 0; j < baselineSize; j++) {
                double d = velomarker.service.planning.WaypointSelector.haversineKm(point, baseline.get(j));
                if (d < distToBaseline) distToBaseline = d;
            }
            double reward = rewards.getOrDefault(RewardModel.categoryKey(area), 1.0);
            double detourEffort = Math.max(0.05, 2.0 * distToBaseline * effortMultiplier);
            double score = reward / detourEffort;
            scored.add(new SeedSel(area, point, ordering.orderKey(point), score, distToBaseline));
        }
        // Sort: blisko korytarza (distBase ASC) WAŻONE rewardem → rzadkie cenne (DE Kreis) nie zatapiane
        // przez gęste tanie (CZ Obec). PL (jedna kategoria) → czysty distBase.
        scored.sort(Comparator.comparingDouble((SeedSel s) ->
                s.distBase() / Math.max(0.05, rewards.getOrDefault(RewardModel.categoryKey(s.area()), 1.0))));
        return scored;
    }

    /** Wynik HOLEFILL: nowy realEffort + ile enclosed-gmin dopięto. */
    private record HoleFillResult(double realEffort, int enclosedAdded) {}

    /**
     * FAZA COMPACT-LOOP seeda (≤8 cykli): anchor-intersects → tailPrune (podwójne cięcie) → zmierz
     * G gmin @ E% → jeśli &lt;95% dobierz proporcjonalnie growNear, aż effort w paśmie [95%,105%]
     * (lub &gt;105% → przerwij, TRIM utnie). Mutuje route/selected; akumuluje grow w {@code growNearAcc[0]};
     * zwraca nowy realEffort.
     */
    private double seedCompactLoop(SeedRoute seed, double hiBand,
            double targetEffort, double realEffort, int[] growNearAcc) {
        List<double[]> route = seed.route(); List<SeedSel> selected = seed.selected(); List<double[]> anchorOnly = seed.anchorOnly(); List<double[]> anchors = seed.anchors(); List<double[]> baseline = seed.baseline(); double[] baseCum = seed.baseCum();
        for (int cycle = 0; cycle < 8; cycle++) {
            long cycleCallsStart = edgeRouter.realCalls(); // v3.18: realne strzały (nie misses)
            // v3.20: cycle-start 2opt USUNIĘTY — był NO-OP (INIT-GROW + grow/enclosed/prune 2opt-ują
            // wewnętrznie; run dowiódł cycle0-2opt-real == round0-grown-real co do joty). Debug zostaje
            // jako snapshot WEJŚCIA cyklu (ten sam stan, jaśniejsza nazwa `entry`).
            if (debugGeoJson) debugGeometry("cycle" + cycle + "-entry-real",
                    metrics.realGeometry(route), route,
                    metrics.realKm(route));
            if (debugGeoJson) shots = logShots("cycle" + cycle + "-entry", shots); // RUNDA 24: STRZAŁY na start cyklu
            // RUNDA 24: ANCHOR-INTERSECTS — GŁÓWNY silnik pokrycia (raz na cykl). Reset wszystkich DOTYKANYCH gmin:
            //    wp na PIERWSZYM wejściu w rdzeń (≥200m) albo CENTROID (muśnięcie). Kończy 2opt. enclosedFill NIE tu —
            //    tylko na końcu seeda (holefill). Realny BRouter wchodzi dopiero w tailPruneJts (reroute przez nowe wp).
            anchorResetTouched(seed, "cycle" + cycle);
            if (debugGeoJson) debugGeometry("cycle" + cycle + "-anchor-real",
                    metrics.realGeometry(route), route,
                    metrics.realKm(route));
            if (debugGeoJson) shots = logShots("cycle" + cycle + "-anchor", shots);
            // tailPrune — DOPIERO TERAZ BRouter (reroute przez nowe wp) + tnij ogonki DO SKUTKU (bez wewn. anchora)
            int prunePasses = cycle == 0 ? 8 : 3;
            realEffort = doubleCut(seed, // RUNDA 69: cut→2opt→reroute→cut (dla pewności)
                    targetEffort, prunePasses,
                    "cycle" + cycle + "-tailprune-real");
            if (debugGeoJson) shots = logShots("cycle" + cycle + "-cut", shots);
            // 5. ZMIERZ stan ZWARTY (gminy + effort z cache — 0 BRoutera)
            RouteMetrics.EvalResult evc = metrics.eval(route);
            realEffort = metrics.effortAccurate(route); // RUNDA 40: DOKŁADNY (spójny z ROUTE-STATS), nie Σ przybliżony evc.effort()
            int gmin = evc.visited().size();
            double eFrac = realEffort / targetEffort;
            // 6. EXIT w paśmie [95%,105%]
            if (eFrac >= 0.95 && eFrac <= 1.05) {
                log.info("Coverage COMPACT cycle {}: anchor-intersects → 2opt → BRouter → tailPrune → {} gmin @ {}% → KONIEC (w paśmie), calls={}",
                        new Object[]{cycle, gmin, Math.round(eFrac * 100), edgeRouter.realCalls() - cycleCallsStart});
                break;
            }
            // 7. >105% → przerwij; TRIM końcowy (poniżej) utnie najsłabsze
            if (eFrac > 1.05) {
                log.info("Coverage COMPACT cycle {}: {} gmin @ {}% > 105% → TRIM (poniżej pętli), calls={}",
                        new Object[]{cycle, gmin, Math.round(eFrac * 100), edgeRouter.realCalls() - cycleCallsStart});
                break;
            }
            // 8. PROPORCJA: dobierz additional = round(G×(1−E)/E) wg score (w pamięci, jeden pomiar)
            //    — np. 200 gmin @ 80% → +50; 245 @ 96% → +10. growNear z limitem maxInserts.
            int additional = (int) Math.round(gmin * (1.0 - eFrac) / Math.max(0.05, eFrac));
            int added = 0;
            if (additional > 0) {
                edgeRouter.setReason("grow");
                GrowNearResult gr = growNear(seed, hiBand,
                        Math.min(additional, 16), additional + 1, additional);
                added = gr.inserted();
                growNearAcc[0] += added;
                realEffort = gr.effort();
            }
            if (debugGeoJson) shots = logShots("cycle" + cycle + "-grow", shots);
            if (debugGeoJson) debugGeometry("cycle" + cycle + "-grow-real",
                    metrics.realGeometry(route), route,
                    metrics.realKm(route));
            log.info("Coverage COMPACT cycle {}: anchor-intersects → 2opt → BRouter → tailPrune → {} gmin @ {}% → +{} (proporcja do 100%, cel +{}), calls={}",
                    new Object[]{cycle, gmin, Math.round(eFrac * 100), added, additional,
                            edgeRouter.realCalls() - cycleCallsStart});
            if (added == 0) break; // brak kandydatów do dobrania — koniec
        }
        return realEffort;
    }

    /**
     * FAZA HOLEFILL seeda: złap gminy OTOCZONE zaliczonymi (donut-holes), wstaw wp w najgłębszym
     * punkcie każdej, anchor-intersects + podwójne cięcie. Mutuje route/selected; zwraca nowy effort
     * i liczbę dopiętych.
     */
    private HoleFillResult seedHoleFill(SeedRoute seed,
                                        double targetEffort,
                                        double realEffort) {
        List<double[]> route = seed.route(); List<SeedSel> selected = seed.selected(); List<double[]> anchorOnly = seed.anchorOnly(); List<double[]> anchors = seed.anchors(); List<double[]> baseline = seed.baseline(); double[] baseCum = seed.baseCum();
        Set<Integer> visitedHF = gminaIndex.visitedAreaIds(
                metrics.realGeometry(route));
        Set<Integer> enclosedHF = gminaIndex.enclosedUnvisited(visitedHF);
        if (enclosedHF.isEmpty()) {
            log.info("Coverage HOLEFILL: 0 enclosed → KONIEC seeda (świeżo po anchor+2opt+podwójne cięcie)");
            return new HoleFillResult(realEffort, 0);
        }
        int enclosedAdded = 0;
        for (UnvisitedArea a : pool) {
            if (!enclosedHF.contains(a.areaId())) continue;
            double[] deep = gminaIndex.deepestInteriorPoint(a.areaId());
            if (deep == null) continue;
            selected.add(new SeedSel(a, deep, ordering.orderKey(deep), ENCLOSED_PROTECTED_SCORE,
                    GeometryUtil.minDistToBaselineKm(deep, baseline)));
            enclosedAdded++;
        }
        rebuildOrderedRoute(route, anchorOnly, selected, baseline, baseCum);
        log.info("Coverage HOLEFILL: {} enclosed → wp najgłębszy + anchor + 2opt + podwójne cięcie", enclosedHF.size());
        if (debugGeoJson) debugGeometry("holefill-enclosed-real",
                metrics.realGeometry(route), route,
                metrics.realKm(route));
        if (debugGeoJson) shots = logShots("holefill-enclosed", shots);
        anchorResetTouched(seed, "holefill");
        if (debugGeoJson) debugGeometry("holefill-anchor-real",
                metrics.realGeometry(route), route,
                metrics.realKm(route));
        if (debugGeoJson) shots = logShots("holefill-anchor", shots);
        realEffort = doubleCut(seed, targetEffort, 3, "holefill-tailprune-real");
        return new HoleFillResult(realEffort, enclosedAdded);
    }

    /**
     * FAZA TRIM seeda (gdy effort &gt;105%): peeling peryferii wg reward/koszt-objazdu (tnij tylko
     * NIE-otoczone — bez dziur), pełny 2-opt, realny BRouter co rundę; raz na rundę anchor + doubleCut;
     * gdy zjechało &lt;95% → reward-aware growNear. Mutuje route/selected; akumuluje ucięte w
     * {@code trimmedAcc[0]} i grow w {@code growNearAcc[0]}; zwraca nowy realEffort.
     */
    private double seedTrim(SeedRoute seed, double hiBand,
            double targetEffort, double realEffort, int[] trimmedAcc, int[] growNearAcc) {
        List<double[]> route = seed.route(); List<SeedSel> selected = seed.selected(); List<double[]> anchorOnly = seed.anchorOnly(); List<double[]> anchors = seed.anchors(); List<double[]> baseline = seed.baseline(); double[] baseCum = seed.baseCum();
        for (int ti = 0; ti < 4 && realEffort > hiBand; ti++) {
            int peeledThisRound = 0;
            // WEWN. PEELING: tnij proporcjonalną porcję najgorszego reward/detour (fringe), pełny 2-opt, zmierz BRouterem.
            for (int peelK = 1; peelK <= TRIM_MAX_PEELS && realEffort > hiBand; peelK++) {
                long peelCallsStart = edgeRouter.realCalls();
                double before = realEffort;
                RouteMetrics.EvalResult evt = metrics.eval(route);
                Set<Integer> visited = evt.visited();
                int gmint = visited.size();
                double eFracT = realEffort / targetEffort;
                int removeN = Math.max(1, (int) Math.round(gmint * (eFracT - 1.0) / eFracT));
                // kandydaci = nie-enclosed, nie-protected, FRINGE (cięcie nie zrobi dziury); klucz = reward/koszt-objazdu ASC
                record DealCand(SeedSel s, double key) {}
                List<DealCand> cands = new ArrayList<>();
                for (SeedSel s : selected) {
                    if (s.score() >= ENCLOSED_PROTECTED_SCORE) continue;
                    if (gminaIndex.allNeighborsVisited(s.area().areaId(), visited)) continue; // otoczona z każdej strony śladem (też zza granicy) → cięcie zrobiłoby dziurę
                    int wpIdx = GeometryUtil.identityIndexOf(route, s.point());
                    if (wpIdx <= 0 || wpIdx >= route.size() - 1) continue; // brzeg/anchor — nie kandydat
                    double[] prev = route.get(wpIdx - 1), cur = route.get(wpIdx), next = route.get(wpIdx + 1);
                    double eIn = edgeRouter.edge(prev, cur).distanceKm();
                    double eOut = edgeRouter.edge(cur, next).distanceKm();
                    double detour = Math.max(0.0, eIn + eOut
                            - velomarker.service.planning.WaypointSelector.haversineKm(prev, next));
                    double rw = rewards.getOrDefault(RewardModel.categoryKey(s.area()), 1.0);
                    cands.add(new DealCand(s, rw / Math.max(TRIM_DETOUR_EPS, detour))); // niski klucz = zły deal = tnij pierwszy
                }
                if (cands.isEmpty()) break; // nic bezpiecznego do ucięcia (sama peryferia-wnętrze) → przerwij peeling
                cands.sort(Comparator.comparingDouble(DealCand::key));
                edgeRouter.setReason("pomiar");
                int removed = 0;
                for (DealCand dc : cands) {
                    if (removed >= removeN) break;
                    int wpIdx = GeometryUtil.identityIndexOf(route, dc.s().point());
                    if (wpIdx <= 0 || wpIdx >= route.size() - 1) continue;
                    route.remove(wpIdx);        // W MIEJSCU (bez rebuildOrderedRoute) → prev→next wprost, brak rozłażenia
                    selected.remove(dc.s());
                    trimmedAcc[0]++; removed++; peeledThisRound++;
                }
                if (removed == 0) break;
                twoOptLogged(route, "trim" + ti + "-peel" + peelK);                 // PEŁNY 2-opt (haversine, tylko skraca)
                realEffort = metrics.effortAccurate(route); // realny BRouter PO 2-opt
                log.info("Coverage TRIM peel ti={} k={}: {} gmin @ {}% → -{} (reward/detour, fringe) → {}%, calls={}",
                        new Object[]{ti, peelK, gmint, Math.round(eFracT * 100), removed,
                                Math.round(realEffort / targetEffort * 100), edgeRouter.realCalls() - peelCallsStart});
                if (debugGeoJson) debugGeometry("trim" + ti + "-peel" + peelK + "-real",
                        metrics.realGeometry(route), route,
                        metrics.realKm(route));
                if (debugGeoJson) shots = logShots("trim" + ti + "-peel" + peelK, shots);
                if (realEffort >= before - TRIM_PROGRESS_EPS) break; // anty-spin: ucięto kolateral/tanie, effort nie schudł
            }
            // RAZ na rundę: przywróć invariant „gmina=wp na śladzie" + autorytatywna geometria (METODY BEZ ZMIAN)
            anchorResetTouched(seed, "trim" + ti);
            if (debugGeoJson) debugGeometry("trim" + ti + "-anchor-real",
                    metrics.realGeometry(route), route,
                    metrics.realKm(route));
            if (debugGeoJson) shots = logShots("trim" + ti + "-anchor", shots);
            realEffort = doubleCut(seed, targetEffort, 3, "trim" + ti + "-tailprune-real");
            // zjechało <95% → dobierz (reward-aware growNear, BEZ ZMIAN)
            double eFracAfter = realEffort / targetEffort;
            if (eFracAfter < 0.95) {
                int gminA = metrics.eval(route).visited().size();
                int additional = (int) Math.round(gminA * (1.0 - eFracAfter) / Math.max(0.05, eFracAfter));
                if (additional > 0) {
                    edgeRouter.setReason("grow");
                    GrowNearResult gr = growNear(seed, hiBand,
                            Math.min(additional, 16), additional + 1, additional);
                    growNearAcc[0] += gr.inserted();
                    realEffort = gr.effort();
                    edgeRouter.setReason("pomiar");
                }
                if (debugGeoJson) debugGeometry("trim" + ti + "-grow-real",
                        metrics.realGeometry(route), route,
                        metrics.realKm(route));
                if (debugGeoJson) shots = logShots("trim" + ti + "-grow", shots);
            }
            if (peeledThisRound == 0) break; // nic nie dało się bezpiecznie uciąć → nie kręć outer loop w kółko
        }
        return realEffort;
    }

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
        // FAZA 1: score + projekcja per area, posortowane wg korytarza ważonego rewardem.
        List<SeedSel> scored = seedScoreAndOrder(baseline, EFFORT_MULTIPLIER);

        // FAZA 2: GROW→(SURGICAL spur-prune)→regrow rounds.
        // GROW: dodawaj wg score, re-order projekcja, 2-opt PRZED pomiarem, rośnij aż effort ≈ target.
        // SURGICAL PRUNE: usuń TYLKO prawdziwe ostrogi = waypoint z dużym lokalnym detourem (>4 km,
        //   czyli realny wjazd-wyjazd w bok) ORAZ gmina pokryta gdzie indziej (count≥2). NIE tnie
        //   wszystkiego co pokryte 2× (to gnało trasę 40km od baseline + dziury). Uwolniony budżet
        //   → kolejny GROW dorzuca z BLISKICH (corridor-aware score) → bez rozjazdu.
        List<double[]> anchorOnly = new ArrayList<>(route);
        List<SeedSel> selected = new ArrayList<>();
        // Stan trasy seeda zgrupowany — fazy (anchor/prune/grow) dostają jeden obiekt zamiast 6 kolekcji.
        SeedRoute seed = new SeedRoute(route, selected, anchorOnly, anchors, baseline, baseCum);
        int idx = 0;
        final int BATCH = 20;
        // Pasmo budżetu (v3): INIT-GROW celuje w [1.00, 1.05]×budget, potem COMPACT-LOOP
        // {2opt → tailPrune → grow-near} domyka do ~100% bez dziur i ogonków. Zastępuje
        // DEEP-BATCH oraz wielorundowy grow ze scored-tail (dalekie gminy, skoki 90%→128%).
        final double hiBand = targetEffort * 1.05;
        // INIT-GROW zbiera do 110% budżetu; nadmiar ściąga COMPACT-LOOP/TRIM do pasma [95,105]%.
        final double growCeiling = targetEffort * 1.10;
        double realEffort = 0;
        int totalPruned = 0;
        int totalRetried = 0;
        int trimmed = 0;
        // INSTRUMENTACJA wall-time seeda (pola): reset per plan.
        tRebuildNs = tTwoOptNs = tRouteEffortNs = tEvalNs = tVisitsNs = tPruneNs = 0;
        // L2 (cięcie churnu): w grow effort SZACUJEMY z haversine×kmFactor + alpha×Δelev×climbFactor (tanio,
        // bez BRoutera), a realny routeEffort robimy tylko CO CHECKPOINT_EVERY batchy (rekalibracja factorów)
        // lub gdy est zbliża się do pasma (confirm-before-stop). Tnie ~8500 calli do ~2-3k. Reszta faz = REAL.
        final int CHECKPOINT_EVERY = 5;
        // Jeden effort-factor: realEffort / Σhaversine, rekalibrowany na checkpoincie. (DEM/climb-factor
        // usunięte — climbFactor wychodził ~8.6, czyli prosta-DEM nic nie wnosiła; jeden factor jest prostszy
        // i równie dobry, bo i tak służy tylko do pasma budżetu.) Init = EFFORT_MULTIPLIER (road×(1+climb·alpha)).
        double effortFactor = EFFORT_MULTIPLIER;
        int realCheckpoints = 0;
        // areaId → próba entry-pointu (0=route-nearest, 1=centroid, ≥2=wyspa, usuń). Skróciliśmy z 10 prób
        // (route-nearest + N sampli + centroid) do 2 — wzorzec interiorEntryPoints reconcile. Empirycznie
        // sample[1..N] rzadko się udają gdy route-nearest padł (te same krawędzie boundary). Cięcie ~80%
        // retry'ów per stubborn-gmina × ~2 edges per retry = duża redukcja BRouter calls (z ~140k na ~50k).
        Map<Integer, Integer> entryAttempt = new HashMap<>();
        // Cache findGminaForPoint per (lng,lat) — w prune wywoływane dla wp + prev + next,
        // a wp powtarzają się między rundami (5 rund × 30k wp = 450k JTS lookups w FR).
        // Klucz 6 cyfr = ~10 cm precyzji (jak EdgeCache). Pure function (lng,lat→Area), więc
        // cache stabilny przez wszystkie rundy.
        Map<String, UnvisitedArea> gminaPointCache = new HashMap<>();
        this.findGminaCached = pt -> gminaPointCache.computeIfAbsent(
                String.format(java.util.Locale.ROOT, "%.6f,%.6f", pt[0], pt[1]),
                k -> gminaIndex.findGminaForPoint(pt[0], pt[1]));
        // Progress reporting: dla mega-scope (Francja 35k komun) seed trwa 3-6h.
        // User nie wie czy plan dalej pracuje czy wisi. Logujemy co PROGRESS_EVERY obszarów
        // + na początku każdej rundy → user widzi ETA na podstawie tempa.
        final int PROGRESS_EVERY = 500;
        final long seedStartTs = System.currentTimeMillis();
        int lastProgressMilestone = 0;
        log.info("Coverage seed grow START: pool={} obszarów, target effort={} ({}/dzień × {}d)",
                new Object[]{scored.size(), Math.round(targetEffort),
                        Math.round(targetEffort / Math.max(1, route.size())), route.size()});
        edgeRouter.setReason("grow"); // v3.16: INIT-GROW = realne waypointy (księgowanie strzałów per powód)
        debugSkeleton("init", route); // ADMIN DEBUG: start+meta+anchory (przed dorzucaniem gmin)
        // INIT-GROW (v3): JEDNA runda grow ze scored (baseline-score) do pasma + islands-fix.
        // Dalsze dobieranie przejmuje COMPACT-LOOP (grow-near po distToRoute = zwartość bloba) —
        // scored-tail po pierwszym prune dawał DALEKIE gminy (skoki 90%→128%) i błędne koło psuj-naprawiaj.
        final int round = 0;
        {
            long roundStartTs = System.currentTimeMillis();
            int roundStartSelected = selected.size();
            log.info("Coverage seed round {} START: dotąd dodano {} obszarów, effort={}/{} ({}%), elapsed={}s",
                    new Object[]{round, selected.size(), Math.round(realEffort), Math.round(targetEffort),
                            realEffort > 0 ? Math.round(realEffort * 100.0 / targetEffort) : 0,
                            (System.currentTimeMillis() - seedStartTs) / 1000});
            int batchCounter = 0;
            while (idx < scored.size()) {
                // PRECYZJA: od 80% budżetu (est ≥ 0.80×target) zmniejsz batch 20→6 i przejdź na real +
                // pełny 2opt CO BATCH (dokładny pomiar długości dróg w terenie zanim dobijemy do 110%).
                // Poniżej 80% rośniemy szybko (batch 20, est, real tylko na checkpoincie).
                boolean precise = metrics.haversineKm(route) * effortFactor >= targetEffort * 0.80;
                int batchSize = precise ? 6 : BATCH;
                for (int b = 0; b < batchSize && idx < scored.size(); b++, idx++) {
                    selected.add(scored.get(idx));
                }
                long _tReb = System.nanoTime();
                rebuildOrderedRoute(route, anchorOnly, selected, baseline, baseCum);
                tRebuildNs += System.nanoTime() - _tReb;
                // 2-opt incremental po każdym batchu (window ±80 od końca trasy = lokalne ulepszenia),
                // PEŁNY twoOpt co 5 batchy = 100 obszarów = global cleanup. Dla FR 34746 obszarów / 5
                // batchy 20 = 347 pełnych twoOpt zamiast 1737 → ~5× szybsze (z 4h 16min do ~1-1.5h
                // łącznie z routeEffortViaCache). Dla małych scope (≤500 wp) twoOpt(route) i tak
                // wewnętrznie robi pełen skan (FULL_SCAN_MAX), więc bez regresji.
                batchCounter++;
                long _tTwo = System.nanoTime();
                if (precise || batchCounter % 5 == 0) {
                    twoOptLogged(route, "init-grow-batch" + batchCounter);
                } else {
                    CoverageLocalSearch.twoOptIncremental(route, route.size() - 1, 80);
                }
                tTwoOptNs += System.nanoTime() - _tTwo;
                // L2: tani estymator effortu = Σhaversine × effortFactor. Realny BRouter tylko na checkpoincie
                // lub gdy est zbliża się do pasma → confirm-before-stop (nie przerywamy na samym szacunku).
                double hav = metrics.haversineKm(route);
                double estEffort = hav * effortFactor;
                // batchCounter==1: wczesna kalibracja effortFactor (init 1.69 zaniża → bez tego małe plany
                // przestrzeliwały do 129% zanim 1. checkpoint trafił w %5). Potem co CHECKPOINT_EVERY.
                boolean doReal = (batchCounter == 1) || (batchCounter % CHECKPOINT_EVERY == 0) || estEffort >= hiBand
                        || precise; // od 80% budżetu real co batch (est bywa nieświeży po prune)
                if (doReal) {
                    long _tRE = System.nanoTime();
                    realEffort = metrics.effortViaCache(route);
                    tRouteEffortNs += System.nanoTime() - _tRE;
                    realCheckpoints++;
                    if (hav > 1) effortFactor = realEffort / hav;   // rekalibruj jeden factor (km+wznios+detour razem)
                    // ADMIN DEBUG: na checkpoincie cache krawędzi jest CIEPŁY → złóż realną geometrię dróg (cache-hity, 0 nowych calli) + waypointy
                    if (debugGeoJson) debugGeometry("round" + round + "-batch" + batchCounter + "-real",
                            metrics.realGeometry(route), route,
                            metrics.realKm(route));
                } else {
                    realEffort = estEffort;
                }
                debugSkeleton("round" + round + "-batch" + batchCounter, route); // ADMIN DEBUG: szkielet (kolejność+numery) po każdym batchu
                // Progress co 500 obszarów (user widzi tempo i może oszacować ETA dla mega-scope)
                int currentMilestone = selected.size() / PROGRESS_EVERY;
                if (currentMilestone > lastProgressMilestone) {
                    lastProgressMilestone = currentMilestone;
                    long elapsedS = (System.currentTimeMillis() - seedStartTs) / 1000;
                    double pct = realEffort * 100.0 / targetEffort;
                    double avgSecPerArea = elapsedS / (double) Math.max(1, selected.size());
                    int remainingToTarget = (int) Math.max(0, (targetEffort - realEffort) / Math.max(0.01, realEffort / Math.max(1, selected.size())));
                    long etaS = (long) (remainingToTarget * avgSecPerArea);
                    log.info("Coverage seed progress: +{} obszarów (round={}), effort={}/{} ({}%), elapsed={}s, tempo={}ms/area, eta≈{}min",
                            new Object[]{selected.size(), round, Math.round(realEffort), Math.round(targetEffort),
                                    Math.round(pct), elapsedS, Math.round(avgSecPerArea * 1000), etaS / 60});
                }
                if (realEffort >= growCeiling) { // grow do 110% budżetu; COMPACT-LOOP/TRIM ściągnie do pasma [95,105]%
                    log.info("Coverage INIT-GROW: osiągnięto {}% (≥110%) → stop rundy 0",
                            Math.round(realEffort * 100.0 / targetEffort));
                    break;
                }
            }
            long roundDurMs = System.currentTimeMillis() - roundStartTs;
            log.info("Coverage seed round {} END: dodano {} obszarów w tej rundzie ({} → {}), trwało {}s",
                    new Object[]{round, selected.size() - roundStartSelected, roundStartSelected, selected.size(), roundDurMs / 1000});
            // ISLANDS-FIX (v3, max 3 przebiegi): TYLKO wyspy/nieosiągalne. Stary transit-bet prune
            // (lollipop/spur + restore 25 km) WYCIĘTY — robił dziury (restore promieniowy nie trafiał,
            // grow nie umiał wrócić) i thrashował (removed 6/restored 37). Cięciem ogonków zajmuje się
            // wyłącznie tailPrune v2 w COMPACT-LOOP (realna geometria, weryfikacja przed akceptacją).
            for (int islPass = 0; islPass < 3; islPass++) {
                long _tEval = System.nanoTime();
                RouteMetrics.EvalResult ev = metrics.eval(route);
                tEvalNs += System.nanoTime() - _tEval;
                realEffort = ev.effort();
                long _tVis = System.nanoTime();
                Map<Integer, Integer> visits = countVisitsPerArea(ev.geometry(), gminaIndex);
                tVisitsNs += System.nanoTime() - _tVis;
                Set<double[]> toRemove = new HashSet<>(); // wyspy (identity — double[] nie nadpisuje equals)
                boolean swapped = false;
                int unreachable = 0;
                long _tPrune = System.nanoTime();
                for (int i = 1; i < route.size() - 1; i++) {
                    double[] cur = route.get(i);
                    if (GeometryUtil.isAnchor(cur, anchors)) continue;
                    UnvisitedArea g = findGminaCached.apply(cur);
                    int gv = (g == null) ? 0 : visits.getOrDefault(g.areaId(), 0);
                    // Wyspa: BRouter nie dojechał do tego waypointu z którejś strony (edgeRouter.failedEdges()) —
                    // nawet jeśli geometria fallback (prosta) fałszywie „zalicza" gminę (gv>0).
                    boolean island = edgeRouter.failedEdges().contains(GeometryUtil.edgeKey(route.get(i - 1), cur))
                            || edgeRouter.failedEdges().contains(GeometryUtil.edgeKey(cur, route.get(i + 1)));
                    if (gv != 0 && !island) continue;
                    // NIEOSIĄGALNA: próba 0: route-nearest sample (płytko, od strony trasy).
                    // Próba 1: centroid (najgłębiej). Wyczerpane → wyspa, usuń.
                    unreachable++;
                    if (g == null) { toRemove.add(cur); continue; }
                    int id = g.areaId();
                    int att = entryAttempt.getOrDefault(id, 0);
                    double[] alt = null;
                    if (att == 0) {
                        double[][] samples = gminaIndex.samplePointsFor(g);
                        alt = GeometryUtil.sampleNearestToGeometry(samples, cur, ev.geometry()); // od strony trasy
                    } else if (att == 1) {
                        alt = gminaIndex.deepestInteriorPoint(g.areaId());           // RUNDA 51: najgłębszy (nie centroid)
                        if (alt == null) alt = new double[]{g.lng(), g.lat()};
                    }
                    if (alt != null && (alt[0] != cur[0] || alt[1] != cur[1])) {
                        swapEntryPoint(selected, cur, alt, baseline, baseCum);
                        swapped = true;
                        totalRetried++;
                        entryAttempt.put(id, att + 1);
                    } else if (att <= 1) {
                        entryAttempt.put(id, att + 1); // ta próba odpadła (np. alt==cur) — następny pass
                    } else {
                        toRemove.add(cur); // route-nearest + centroid wyczerpane → naprawdę wyspa
                    }
                }
                tPruneNs += System.nanoTime() - _tPrune;
                if (toRemove.isEmpty() && !swapped) break; // czysto — koniec passów
                selected.removeIf(s -> toRemove.contains(s.point()));
                totalPruned += toRemove.size();
                rebuildOrderedRoute(route, anchorOnly, selected, baseline, baseCum);
                twoOptLogged(route, "init-grow-islands-prune");
                realEffort = metrics.effortViaCache(route);
                log.info("Coverage seed islands pass {}: removed={}, unreachable={}, retried={} entry-points (total)",
                        new Object[]{islPass, toRemove.size(), unreachable, totalRetried});
            }
            // ADMIN DEBUG: stan po INIT-GROW + islands (przed COMPACT-LOOP)
            debugSkeleton("round0-grown", route);
            if (debugGeoJson) debugGeometry("round0-grown-real",
                    metrics.realGeometry(route), route,
                    metrics.realKm(route));
        }

        // COMPACT-LOOP v3.17b — pętla PROPORCJONALNA (decyzja usera: koniec topup/holefill/headroom).
        // Po INIT-GROW: {2opt → anchorTransit → enclosedFill → tailPrune (tnie ogonki, ZWALNIA budżet —
        // tanie dzięki relokacji JTS v3.17a) → ZMIERZ G gmin @ E%} → jeśli E<95% dobierz
        // additional = round(G×(1−E)/E) wg score (w pamięci, jeden pomiar) i powtórz, aż 95-105%.
        // Proporcję liczymy ze stanu ZWARTEGO (po prune — spury sprzed cięcia zawyżają koszt/gminę).
        // Pętla ZAWSZE kończy prune→anchorTransit (każda gmina = waypoint NA śladzie → 2opt nie gubi
        // tranzytu = fix Brzezin).
        int growNearInserted = 0;
        int enclosedFilled = 0;
        // Zmiana 3: STRZAŁY/plan per faza (suma + Δfaza); wątkowane przez seed, gated debugGeoJson.
        shots = new HashMap<>();
        if (debugGeoJson) shots = logShots("init-grow", shots);
        int[] growNearAcc = {growNearInserted};
        realEffort = seedCompactLoop(seed, hiBand, targetEffort, realEffort, growNearAcc);
        growNearInserted = growNearAcc[0];

        // RUNDA 71 — FAZA TRIM (PO cyklach, gdy >105%): PEELING peryferii wg reward/koszt-objazdu, w pamięci,
        // BEZ kotwiczenia między cięciami. Tnij tylko NIE-otoczone (allNeighborsVisited=false → bez dziur).
        // Po wewn. peelingu (proporcja do 100%, pełny 2-opt + realny BRouter co rundę) → anchor + doubleCut RAZ.
        // Dawniej: sort distBase DESC (geometria, ślepy na reward) + anchor MIĘDZY cięciami (odkotwiczał z powrotem)
        // + pomiar PRZED 2-opt (surowa projekcja = śmieci). Patrz plan sp-jrz-na-to-robisz-quizzical-comet.
        int[] trimmedAcc = {trimmed};
        int[] trimGrowAcc = {growNearInserted};
        realEffort = seedTrim(seed, hiBand, targetEffort, realEffort, trimmedAcc, trimGrowAcc);
        trimmed = trimmedAcc[0];
        growNearInserted = trimGrowAcc[0];

        // RUNDA 69 — HOLEFILL (enclosed-only): dopnij gminy otoczone zaliczonymi (donut-holes).
        HoleFillResult hf = seedHoleFill(seed, targetEffort, realEffort);
        realEffort = hf.realEffort();
        enclosedFilled += hf.enclosedAdded();
        if (debugGeoJson) shots = logShots("seed-final", shots);
        int densified = growNearInserted + enclosedFilled; // grow-near + ENCLOSED-FILL
        debugSkeleton("seed", route); // ADMIN DEBUG: szkielet końca seeda (przed deep loop)
        if (debugGeoJson) debugGeometry("seed-real", metrics.realGeometry(route), route,
                metrics.realKm(route));

        log.info("Coverage seed ({}): +{} obszarów, removed={} islands, trimmed={}, grow-near={}, retried={} entry-points, real effort={}/{} ({}%) [v3.8: init-grow + compact-loop(grow→2opt→anchor→enclosed→tailPrune→topup)], route size={}",
                new Object[]{"hilbert", selected.size(), totalPruned,
                        trimmed, densified, totalRetried,
                        Math.round(realEffort), Math.round(targetEffort),
                        Math.round(realEffort * 100.0 / targetEffort), route.size()});
        // INSTRUMENTACJA: rozbicie wall-time seeda. routeEffort = BRouter (równoległy) + sumowanie;
        // rebuild/2opt/eval/visits/prune = NASZ single-thread (multithread go NIE przyspiesza).
        long seedWallS = (System.currentTimeMillis() - seedStartTs) / 1000;
        log.info("Coverage seed PHASE BREAKDOWN (wall={}s): routeEffort(BRouter)={}s | rebuild={}s | 2opt={}s | eval={}s | countVisits={}s | prune={}s",
                new Object[]{seedWallS, tRouteEffortNs / 1_000_000_000L, tRebuildNs / 1_000_000_000L,
                        tTwoOptNs / 1_000_000_000L, tEvalNs / 1_000_000_000L, tVisitsNs / 1_000_000_000L,
                        tPruneNs / 1_000_000_000L});
        // L2: ile realnych checkpointów (zamiast real co batch), skalibrowany factor, sumaryczne calle BRoutera.
        log.info("Coverage seed L2: realCheckpoints={} effortFactor={} brouterCalls(real)={} (cache misses={} — w tym sliced-seedy)",
                new Object[]{realCheckpoints, String.format(java.util.Locale.ROOT, "%.3f", effortFactor), edgeRouter.realCalls(), edgeRouter.misses()});
        // v3.16: ROLLUP STRZAŁÓW per powód — realne brouter.apply (NIE misses: misses zawyża o sliced-seedy
        // z seedSlicedEdges, które tylko zasilają cache bez BRoutera). Odpowiedź na „ile strzałów po co".
        java.util.Map<String, Long> byReason = edgeRouter.realCallsByReason();
        log.info("Coverage STRZAŁY/plan (seed, realne brouter.apply per powód): grow={} ogonek(relok={} scal={}) dziura(otocz={} trasa={}) pomiar={} inne={} | RAZEM realnych={} (misses={}; różnica = sliced-seedy bez BRoutera)",
                new Object[]{
                        byReason.getOrDefault("grow", 0L),
                        byReason.getOrDefault("ogonek-relokacja", 0L),
                        byReason.getOrDefault("ogonek-scalenie", 0L),
                        byReason.getOrDefault("dziura-otoczona", 0L),
                        byReason.getOrDefault("dziura-przy-trasie", 0L),
                        byReason.getOrDefault("pomiar", 0L),
                        byReason.getOrDefault("inne", 0L),
                        edgeRouter.realCalls(), edgeRouter.misses()});
        // DIAGNOSTYKA: top-K najdłuższych legów (hav km / real km / ile RÓŻNYCH gmin kredytują). Duże #gmin =
        // konieczny transit (OK); #gmin≈0 = pusty TSP-nawrót (kandydat do naprawy). getEdge = cache (bez BRoutera).
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

    /** MOVE-SLICED (v4): max odległość wierzchołka eOut od punktu cięcia na eIn, by uznać spur za
     *  out-and-back po TEJ SAMEJ drodze (wtedy skrót = cięcie geometrii, zero BRouter). */
    private static final double SLICE_SNAP_KM = 0.05;
    private static final double RETRACE_TOL_KM = 0.06; // v3.24: out-and-back — eOut wraca TĄ SAMĄ drogą co eIn


    /** STRZAŁY/plan po fazie: skumulowane realne brouter.apply per powód + Δ od poprzedniej fazy.
     *  Zwraca świeży snapshot (przekaż jako {@code prev} w kolejnym wywołaniu). Total niesiony pod
     *  kluczem "RAZEM" → Δfaza bez osobnej zmiennej. Wołaj tylko gdy {@code debugGeoJson}. */
    private Map<String, Long> logShots(String phase, Map<String, Long> prev) {
        Map<String, Long> now = new HashMap<>(edgeRouter.realCallsByReason());
        long total = edgeRouter.realCalls(), prevTotal = prev.getOrDefault("RAZEM", 0L);
        java.util.function.BiFunction<String, Long, String> f =
                (k, n) -> n + "(Δ" + (n - prev.getOrDefault(k, 0L)) + ")";
        log.info("Coverage STRZAŁY/plan [{}]: grow={} ogonek(relok={} scal={}) dziura(otocz={} trasa={}) pomiar={} | RAZEM={} (Δfaza={})",
                new Object[]{phase,
                        f.apply("grow", now.getOrDefault("grow", 0L)),
                        f.apply("ogonek-relokacja", now.getOrDefault("ogonek-relokacja", 0L)),
                        f.apply("ogonek-scalenie", now.getOrDefault("ogonek-scalenie", 0L)),
                        f.apply("dziura-otoczona", now.getOrDefault("dziura-otoczona", 0L)),
                        f.apply("dziura-przy-trasie", now.getOrDefault("dziura-przy-trasie", 0L)),
                        f.apply("pomiar", now.getOrDefault("pomiar", 0L)),
                        total, total - prevTotal});
        now.put("RAZEM", total);
        return now;
    }


    /**
     * RUNDA 69 — PODWÓJNE CIĘCIE „dla pewności": cut → 2opt → reroute → cut. Po pierwszym cięciu 2-opt przestawia
     * kolejność wp (haversine), a drugie cięcie {@code tailPruneJts2(phase+"-recut")} liczy legGminas przez getEdge
     * NOWYCH par = nowy wariant trasy z BRoutera, i tnie wtórne ogonki. Oba cięcia mają RÓŻNE phase → każdy emituje
     * własny komplet logów (TAIL-PRUNE v6 / USUNIĘTE-OGONKI / SPUR-ANATOMIA) + debugGeometry. Zwraca effort po cut2.
     */
    private double doubleCut(SeedRoute seed, double targetEffort, int maxPasses, String phase) {
        List<double[]> route = seed.route(); List<SeedSel> selected = seed.selected(); List<double[]> anchors = seed.anchors(); List<double[]> baseline = seed.baseline(); double[] baseCum = seed.baseCum();
        tailPruneJts2(seed, targetEffort, maxPasses, phase);
        twoOptLogged(route, phase + "-recut2opt");
        return tailPruneJts2(seed, targetEffort, maxPasses, phase + "-recut");
    }

    /**
     * RUNDA 63 — CIĘCIE OGONKÓW v2 (czyste, wg reguły zaułków). PROCES 2, leci PO anchorResetTouched (PROCES 1).
     * Każda gmina ma już 1 wp z anchora → cięcie TYLKO PRZESUWA wp w obrębie JEGO gminy (route.set). Reguła per wp:
     *   1. NIE zaułek (przelot, ślad PRZECHODZI: outAndBackDivergence==null) → ZOSTAW.
     *   2a. zaułek + g0 kryta ≥220m GDZIE INDZIEJ (exclusive puste) → PRZESTAW na przelot prev→next (220m W g0).
     *   2b. zaułek jedyny (exclusive≥1) → SKRÓĆ/POGŁĘB do 220m na WŁASNEJ nodze (relocateShallowDeferred).
     * Zero delete/insert/re-anchor/restore/dedupe (anchor trzyma 1 wp/gmina; cięcie tylko repozycjonuje w gminie).
     * Logi (TAIL-PRUNE v6 / USUNIĘTE-OGONKI / STRZAŁY / SPUR-ANATOMIA) jak w v1.
     */
    private double tailPruneJts2(SeedRoute seed, double targetEffort, int maxPasses, String debugPhase) {
        List<double[]> route = seed.route(); List<SeedSel> selected = seed.selected(); List<double[]> anchors = seed.anchors(); List<double[]> baseline = seed.baseline(); double[] baseCum = seed.baseCum();
        long callsStart = edgeRouter.realCalls();
        edgeRouter.setReason("pomiar");
        double effortBefore = metrics.effortViaCache(route);
        Set<Integer> visitedBefore = gminaIndex.visitedAreaIds(
                metrics.realGeometry(route));
        Map<Integer, UnvisitedArea> idToArea = new HashMap<>();
        for (UnvisitedArea a : pool) idToArea.put(a.areaId(), a);

        int relocated = 0, relocSkipped = 0, passes = 0, pendingRerouteCount = 0;
        final int REROUTE_CAP = 50;
        Map<double[], String> refusal = new java.util.IdentityHashMap<>();
        Set<double[]> stay = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        int round = 0, reroutedLegs = 0;
        do {
            int relRoundStart = relocated;
            List<String> killLog = debugGeoJson ? new ArrayList<>() : null;
            Set<Integer> visBeforeRound = debugGeoJson ? gminaIndex.visitedAreaIds(
                    metrics.realGeometry(route)) : null;
            Map<String, Long> shotsRoundStart = null;
            if (debugGeoJson) {
                shotsRoundStart = new HashMap<>(edgeRouter.realCallsByReason());
                shotsRoundStart.put("RAZEM", edgeRouter.realCalls());
                debugGeometry(debugPhase + "-precut" + round,
                        metrics.realGeometry(route), route,
                        metrics.realKm(route));
            }
            boolean changed = true;
            int pass = 0;
            while (changed && pass < maxPasses + 6) {
                changed = false; pass++; passes++;
                int n = route.size();
                if (n < 3) break;
                // RUNDA 66: per-leg index na −220 (deeplyVisitedAreaIds, 0 BRouter): legGminas[i] = gminy w które noga i
                // wchodzi GŁĘBOKO ≥220m (PRZELOT, nie muśnięcie); count[g] = w ilu nogach. Decyzja cięcia: czy spurArea ma
                // przelot GDZIE INDZIEJ ≥220m (spójne z re-anchorem, który też −220 → cel zawsze istnieje gdy tniemy).
                List<Set<Integer>> legGminas = new ArrayList<>(n - 1);
                Map<Integer, Integer> count = new HashMap<>();
                for (int i = 0; i < n - 1; i++) {
                    Set<Integer> s = gminaIndex.deeplyVisitedAreaIds(
                            edgeRouter.edge(route.get(i), route.get(i + 1)).geometry());
                    legGminas.add(s);
                    for (int g : s) count.merge(g, 1, Integer::sum);
                }
                record Cand(double[] point, int idx, double detour) {}
                List<Cand> cands = new ArrayList<>();
                for (int i = 1; i < n - 1; i++) {
                    double[] cur = route.get(i);
                    if (GeometryUtil.isAnchor(cur, anchors)) continue;
                    double det = edgeRouter.edge(route.get(i - 1), cur).distanceKm()
                            + edgeRouter.edge(cur, route.get(i + 1)).distanceKm()
                            - velomarker.service.planning.WaypointSelector.haversineKm(route.get(i - 1), route.get(i + 1));
                    cands.add(new Cand(cur, i, det));
                }
                cands.sort((x, y) -> Double.compare(y.detour(), x.detour()));
                List<double[][]> relocPairs = new ArrayList<>();
                List<double[]> toDelete = new ArrayList<>();   // RUNDA 65: zbędne zaułki → USUŃ + re-anchor na przelocie (batch po pętli)
                Set<Integer> delGids = new HashSet<>();
                boolean[] locked = new boolean[n];             // chroni sąsiadów usuwanego spuru (poprawny merge prev→next)
                for (Cand c : cands) {
                    int idx = c.idx();
                    if (idx <= 0 || idx >= route.size() - 1) continue;
                    if (locked[idx - 1] || locked[idx] || locked[idx + 1]) continue;
                    double[] cur = c.point();
                    if (stay.contains(cur)) { refusal.put(cur, "stay-zostaw"); continue; }
                    double[] prev = route.get(idx - 1), next = route.get(idx + 1);
                    UnvisitedArea spurArea = findGminaCached.apply(cur);
                    if (spurArea == null) { refusal.put(cur, "bez-gminy"); continue; }
                    Set<Integer> gIn = legGminas.get(idx - 1), gOut = legGminas.get(idx);
                    EdgeCache.EdgeInfo eIn = edgeRouter.edge(prev, cur);
                    EdgeCache.EdgeInfo eOut = edgeRouter.edge(cur, next);
                    // ───────── REGUŁA 1: NIE zaułek (przelot — ślad PRZECHODZI przez cur) → ZOSTAW ─────────
                    if (outAndBackDivergence(eIn, eOut) == null) { refusal.put(cur, "przelot-zostaw"); continue; }
                    // ───────── REGUŁA 2: ZAUŁEK (ślepy przejazd, ślad ZAWRACA) ─────────
                    // czy spurArea ma PRZELOT ≥220m na INNEJ nodze niż ten wp? count/legGminas na −220 → wkład tego wp odejmujemy.
                    // wp-na-granicy −220 wpływa TYLKO na własny wkład (nieszkodliwie); cel cięcia = głęboka noga gdzie indziej.
                    int contribDeep = (gIn.contains(spurArea.areaId()) ? 1 : 0) + (gOut.contains(spurArea.areaId()) ? 1 : 0);
                    boolean deepElsewhere = count.getOrDefault(spurArea.areaId(), 0) > contribDeep;
                    if (deepElsewhere) {
                        // 2a: ZBĘDNY zaułek — spurArea ma PRZELOT ≥220m GDZIE INDZIEJ (choćby na DALEKIEJ nodze). USUŃ spur
                        //   (collapse prev→next); wp wstawimy na PRZELOCIE w spurArea po pętli (re-anchor, 1 wp/gmina). Jedna
                        //   mechanika — re-anchor znajdzie nogę-przelot geometrią (indeks nogi nieistotny: #4-5 czy #204-205).
                        if (killLog != null) killLog.add(String.format(java.util.Locale.ROOT, "#%d %s | DRUGI-KONTAKT | zbędny→usuń+przelot", idx, spurArea.name()));
                        toDelete.add(cur);
                        delGids.add(spurArea.areaId());
                        for (int g : legGminas.get(idx - 1)) count.merge(g, -1, Integer::sum);
                        for (int g : legGminas.get(idx)) count.merge(g, -1, Integer::sum);
                        legGminas.set(idx - 1, new HashSet<>());
                        legGminas.set(idx, new HashSet<>());
                        locked[idx - 1] = locked[idx] = locked[idx + 1] = true;
                        relocated++; changed = true; continue;
                    }
                    // 2b: JEDYNY wjazd → SKRÓĆ/POGŁĘB do 220m na WŁASNEJ nodze (route.set wewnątrz relocateShallowDeferred).
                    RelocResult relocResult = relocateShallowDeferred(seed, Set.of(spurArea.areaId()), prev, cur, next, idx, spurArea, eIn, eOut,
                            pendingRerouteCount < REROUTE_CAP);
                    if (relocResult.ok()) {
                        if (killLog != null) { // RUNDA 67: flaga — wynik MUSI być głęboki (kredytuje −200)
                            UnvisitedArea creditedArea = gminaIndex.findCreditedGminaForPoint(route.get(idx)[0], route.get(idx)[1]);
                            killLog.add(String.format(java.util.Locale.ROOT, "#%d %s | JEDYNY | →220m %s", idx, spurArea.name(),
                                    creditedArea != null && creditedArea.areaId() == spurArea.areaId() ? "(głęboki)" : "(PŁYTKI!)"));
                        }
                        relocated++; changed = true;
                        for (int g : legGminas.get(idx - 1)) count.merge(g, -1, Integer::sum);
                        for (int g : legGminas.get(idx)) count.merge(g, -1, Integer::sum);
                        Set<Integer> nInDeep = gminaIndex.deeplyVisitedAreaIds(edgeRouter.edge(prev, route.get(idx)).geometry());
                        Set<Integer> nOutDeep = gminaIndex.deeplyVisitedAreaIds(edgeRouter.edge(route.get(idx), next).geometry());
                        for (int g : nInDeep) count.merge(g, 1, Integer::sum);
                        for (int g : nOutDeep) count.merge(g, 1, Integer::sum);
                        legGminas.set(idx - 1, nInDeep); legGminas.set(idx, nOutDeep);
                        if (relocResult.pendingDeparture() != null) { relocPairs.add(relocResult.pendingDeparture()); pendingRerouteCount++; stay.add(route.get(idx)); }
                        continue;
                    }
                    refusal.put(cur, "jedyny-zostaw"); relocSkipped++;
                }
                if (!relocPairs.isEmpty()) {
                    edgeRouter.setReason("ogonek-relokacja");
                    edgeRouter.prewarmPairs(relocPairs); // nogi powrotne loop-spurów (batch)
                    edgeRouter.setReason("pomiar");
                }
                // RUNDA 65: USUŃ zbędne zaułki (collapse prev→next) + WSTAW wp na PRZELOCIE ≥220m w każdej usuniętej
                // gminie (re-anchor). Jedna mechanika dla przelotu lokalnego i dalekiego — nogę znajdujemy geometrią.
                if (!toDelete.isEmpty()) {
                    List<double[][]> mergedPairs = new ArrayList<>();
                    for (double[] d : toDelete) {
                        int di = GeometryUtil.identityIndexOf(route, d);
                        if (di > 0 && di < route.size() - 1) mergedPairs.add(new double[][]{route.get(di - 1), route.get(di + 1)});
                    }
                    for (double[] d : toDelete) {
                        final double[] dd = d;
                        int di = GeometryUtil.identityIndexOf(route, dd);
                        if (di >= 0) { route.remove(di); selected.removeIf(s -> s.point() == dd); }
                    }
                    edgeRouter.setReason("ogonek-scalenie");
                    edgeRouter.prewarmPairs(mergedPairs); // scalone prev→next (batch BRouter)
                    edgeRouter.setReason("pomiar");
                    List<double[]> realTrack = metrics.realGeometry(route);
                    Map<Integer, double[]> hearts = gminaIndex.firstBufferEntryPoints(realTrack); // gmina → pierwsze −220 przelotu, RAZ
                    for (int vid : delGids) {
                        boolean hasWp = false;                          // TWARDA bramka 1 wp/gmina (zero #23)
                        for (double[] p : route) {
                            if (GeometryUtil.isAnchor(p, anchors)) continue;
                            UnvisitedArea pointArea = gminaIndex.findGminaForPoint(p[0], p[1]);
                            if (pointArea != null && pointArea.areaId() == vid) { hasWp = true; break; }
                        }
                        if (hasWp) continue;
                        double[] heart = hearts.get(vid);
                        if (heart == null) continue;                    // przelot nie wchodzi ≥220m (kryte 200-220m) → anchor nast. cyklu
                        UnvisitedArea entryArea = gminaIndex.findGminaForPoint(heart[0], heart[1]);
                        if (entryArea == null || entryArea.areaId() != vid) continue;
                        int bestLeg = -1, bestSeg = -1; double bestSD = Double.MAX_VALUE;
                        for (int j = 0; j < route.size() - 1 && bestSD > 1e-7; j++) {
                            List<double[]> g = edgeRouter.edge(route.get(j), route.get(j + 1)).geometry();
                            for (int m = 0; m < g.size() - 1; m++) {
                                double sd = GeometryUtil.pointToSegmentExactKm(heart, g.get(m), g.get(m + 1));
                                if (sd < bestSD) { bestSD = sd; bestLeg = j; bestSeg = m; if (sd <= 1e-7) break; }
                            }
                        }
                        if (bestLeg < 0 || bestSD > 0.05) continue;
                        EdgeCache.EdgeInfo bestLegEdge = edgeRouter.edge(route.get(bestLeg), route.get(bestLeg + 1));
                        double[] heartPoint = heart.clone();
                        edgeRouter.seedSlicedEdgesAtPoint(bestLegEdge, route.get(bestLeg), route.get(bestLeg + 1), bestSeg, heartPoint);
                        route.add(bestLeg + 1, heartPoint);                     // wp zaułka → NA PRZELOT (slice, 0 BRouter)
                        selected.add(new SeedSel(entryArea, heartPoint, ordering.orderKey(heartPoint), 0.0, GeometryUtil.minDistToBaselineKm(heartPoint, baseline)));
                        stay.add(heartPoint);
                    }
                }
            }
            reroutedLegs = edgeRouter.rerouteApproximateLegs(route); // realny re-route sliced legów → ujawnia wtórniaki
            if (debugGeoJson) {
                debugGeometry(debugPhase + "-cut" + round,
                        metrics.realGeometry(route), route,
                        metrics.realKm(route));
                double roundEffort = metrics.effortViaCache(route);
                Set<Integer> visAfterRound = gminaIndex.visitedAreaIds(
                        metrics.realGeometry(route));
                List<String> droppedRoundNames = new ArrayList<>();
                for (int g : visBeforeRound) if (!visAfterRound.contains(g)) {
                    UnvisitedArea ga = idToArea.get(g);
                    droppedRoundNames.add(ga != null ? ga.name() : ("id" + g));
                }
                boolean willContinue = reroutedLegs > 0 && (round + 1) < 3;
                log.info("Coverage TAIL-PRUNE v6 [{}-cut{}]: relocated={}, reloc-skipped={}, passes={}, calls={}, effort {}→{} ({}%→{}%) | runda: reloc+{}, reroute={}, dropped-runda={} {} → {}",
                        new Object[]{debugPhase, round, relocated, relocSkipped, passes, edgeRouter.realCalls() - callsStart,
                                Math.round(effortBefore), Math.round(roundEffort),
                                Math.round(effortBefore * 100.0 / targetEffort), Math.round(roundEffort * 100.0 / targetEffort),
                                relocated - relRoundStart, reroutedLegs, droppedRoundNames.size(), droppedRoundNames,
                                willContinue ? "kolejna runda" : "KONIEC pętli"});
                log.info("Coverage USUNIĘTE-OGONKI [{}-cut{}]: {} pozycji: {}",
                        new Object[]{debugPhase, round, killLog == null ? 0 : killLog.size(), killLog});
                logShots(debugPhase + "-cut" + round, shotsRoundStart);
                debugSpurAnatomyJts(route, anchors, idToArea, refusal, debugPhase + "-cut" + round);
            }
            round++;
        } while (reroutedLegs > 0 && round < 3);

        double realEffort = metrics.effortViaCache(route);
        Set<Integer> visitedAfter = gminaIndex.visitedAreaIds(
                metrics.realGeometry(route));
        Set<Integer> dropped = new HashSet<>(visitedBefore); dropped.removeAll(visitedAfter);
        log.info("Coverage TAIL-PRUNE v6 (JTS-clean v2): relocated={}, reloc-skipped={}, passes={}, dropped={}, calls={}, effort {}→{} ({}%→{}%)",
                new Object[]{relocated, relocSkipped, passes, dropped.size(), edgeRouter.realCalls() - callsStart,
                        Math.round(effortBefore), Math.round(realEffort),
                        Math.round(effortBefore * 100.0 / targetEffort), Math.round(realEffort * 100.0 / targetEffort)});
        if (debugGeoJson) {
            debugSpurAnatomyJts(route, anchors, idToArea, refusal, debugPhase);
            debugGeometry(debugPhase,
                    metrics.realGeometry(route), route,
                    metrics.realKm(route));
        }
        return realEffort;
    }


    /* RUNDA 11: deleteSweepCoveredElse USUNIĘTE — unifikacja USUŃ→PRZESUŃ (anchor inline po deletach w passie)
       trzyma pokrycie na bieżąco, więc nie ma „chwilowych tranzytów" do dosprzątania osobnym sweepem. */

    /** ANATOMIA spurów v6: garby ≥1 km z autorytatywnego indeksu JTS — sort po garbie DESC, cap 25.
     *  RUNDA 11: {@code phase} w logu — wołane po KAŻDEJ rundzie cięcia (cut0/1/2) + raz na końcu. */
    private void debugSpurAnatomyJts(List<double[]> route, List<double[]> anchors, Map<Integer, UnvisitedArea> idToArea, Map<double[], String> refusal, String phase) {
        int n = route.size();
        if (n < 3) return;
        List<Set<Integer>> legGminas = new ArrayList<>(n - 1);
        Map<Integer, Integer> count = new HashMap<>();
        for (int i = 0; i < n - 1; i++) {
            Set<Integer> s = gminaIndex.visitedAreaIds(
                    edgeRouter.edge(route.get(i), route.get(i + 1)).geometry());
            legGminas.add(s);
            for (int g : s) count.merge(g, 1, Integer::sum);
        }
        // v3.16 B3: które gminy są wjeżdżane z DWÓCH stron przez RÓŻNE spury (detour≥1km) → PODWÓJNY-WJAZD
        // (#149/150/151: jedna gmina, dwa wjazdy — wystarczy jeden). Mapa gmina → indeksy spur-waypointów.
        Map<Integer, Set<Integer>> gminaToSpurWps = new HashMap<>();
        for (int i = 1; i < n - 1; i++) {
            if (GeometryUtil.isAnchor(route.get(i), anchors)) continue;
            double det = edgeRouter.edge(route.get(i - 1), route.get(i)).distanceKm()
                    + edgeRouter.edge(route.get(i), route.get(i + 1)).distanceKm()
                    - velomarker.service.planning.WaypointSelector.haversineKm(route.get(i - 1), route.get(i + 1));
            if (det < 1.0) continue;
            Set<Integer> u = new HashSet<>(legGminas.get(i - 1)); u.addAll(legGminas.get(i));
            for (int gid : u) gminaToSpurWps.computeIfAbsent(gid, k -> new HashSet<>()).add(i);
        }
        record Kept(int idx, double[] pt, double detour, String gmina, String own, String refus, String blocker, String dbl, String cas) {}
        List<Kept> kept = new ArrayList<>();
        for (int i = 1; i < n - 1; i++) {
            double[] cur = route.get(i);
            if (GeometryUtil.isAnchor(cur, anchors)) continue;
            double[] prev = route.get(i - 1);
            double[] next = route.get(i + 1);
            EdgeCache.EdgeInfo eIn = edgeRouter.edge(prev, cur);
            EdgeCache.EdgeInfo eOut = edgeRouter.edge(cur, next);
            double detour = eIn.distanceKm() + eOut.distanceKm()
                    - velomarker.service.planning.WaypointSelector.haversineKm(prev, next);
            if (detour < 1.0) continue;
            Set<Integer> gIn = legGminas.get(i - 1);
            Set<Integer> gOut = legGminas.get(i);
            UnvisitedArea g = findGminaCached.apply(cur);
            boolean covered = false;
            if (g != null) {
                int contrib = (gIn.contains(g.areaId()) ? 1 : 0) + (gOut.contains(g.areaId()) ? 1 : 0);
                covered = count.getOrDefault(g.areaId(), 0) > contrib;
            }
            // v3.26 DIAGNOZA per spur (te same liczby co decyzja w tailPruneJts cands loop): exclusive,
            // kutas (out-and-back), inKw/outKw (najpłytszy wierzchołek kredytujący preserve / długość nogi).
            // PRZYPADEK: covered-loop (excl puste — usuwany w v3.26), deep-far (prsv=1, kw przy KOŃCU nogi),
            // multi (prsv≥2 / kw=-1, gminy rozjechane = ZOSTAW), kutas (powinien być ucięty).
            Set<Integer> exclusive = new HashSet<>();
            for (int gg : gIn) if (count.getOrDefault(gg, 0) <= 1 + (gOut.contains(gg) ? 1 : 0)) exclusive.add(gg);
            for (int gg : gOut) if (!gIn.contains(gg) && count.getOrDefault(gg, 0) <= 1) exclusive.add(gg);
            Set<Integer> preserve = exclusive.isEmpty()
                    ? (g != null ? Set.of(g.areaId()) : Set.<Integer>of()) : exclusive;
            boolean kutas = outAndBackDivergence(eIn, eOut) != null;
            int inKw = shallowestCoveringVertex(eIn.geometry(), preserve, gminaIndex);
            List<double[]> revOut = new ArrayList<>(eOut.geometry());
            java.util.Collections.reverse(revOut);
            int outKw = shallowestCoveringVertex(revOut, preserve, gminaIndex);
            int inN = eIn.geometry().size(), outN = eOut.geometry().size();
            // v3.27 FIX C: deep-far PRZED kutas (uczciwe etykiety). Stare Babice (inKw=361/363, refuse)
            // miał „kutas(tnij)" choć nic nie tnie — bo kutas=true, ale D nie kredytuje gminy z czubka.
            String przyp;
            if (exclusive.isEmpty()) przyp = "covered-loop(usuń)";
            else if (preserve.size() >= 2 || inKw < 0 || outKw < 0) przyp = "multi(zostaw)";
            else if ((inN > 3 && inKw >= inN - 3) || (outN > 3 && outKw >= outN - 3)) przyp = "deep-far";
            else if (kutas) przyp = "kutas(tnij)";
            else przyp = "krótko?";
            String cas = String.format(java.util.Locale.ROOT, "%s excl=%d cov=%b kutas=%b inKw=%d/%d outKw=%d/%d",
                    przyp, exclusive.size(), covered, kutas, inKw, inN, outKw, outN);
            // BLOKER: gminy legów punktu trzymane NA WYŁĄCZNOŚĆ (count−contrib<1) — to one blokują delete
            // (anatomia v3.15 pokazywała tylko headline-gminę → „głęboka-jedyna" nie mówiło CZEMU).
            Set<Integer> union = new HashSet<>(gIn); union.addAll(gOut);
            List<String> blk = new ArrayList<>();
            String dbl = "—";
            for (int gid : union) {
                int contrib = (gIn.contains(gid) ? 1 : 0) + (gOut.contains(gid) ? 1 : 0);
                if (count.getOrDefault(gid, 0) - contrib < 1) {
                    UnvisitedArea ba = idToArea.get(gid);
                    blk.add(ba != null ? ba.name() : ("id" + gid));
                }
                Set<Integer> wps = gminaToSpurWps.get(gid);
                if ("—".equals(dbl) && wps != null && wps.size() >= 2) {
                    UnvisitedArea da = idToArea.get(gid);
                    dbl = "PODWÓJNY-WJAZD(" + (da != null ? da.name() : ("id" + gid)) + ")";
                }
            }
            kept.add(new Kept(i, cur, detour, g != null ? g.name() : "?",
                    covered ? "MA-DRUGI-KONTAKT(!)" : "JEDYNY-KONTAKT", refusal.getOrDefault(cur, "-"),
                    blk.isEmpty() ? "—" : String.join("+", blk), dbl, cas));
        }
        kept.sort((x, y) -> Double.compare(y.detour(), x.detour()));
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Kept k : kept) {
            if (shown++ >= 60) break; // v3.26: cap 25→60 — pokaż też mniejsze spury (102/114/99/14/17/225)
            sb.append(String.format(java.util.Locale.ROOT, " [#%d %s | %s | %s]",
                    k.idx(), k.gmina(), k.own(), k.refus())); // RUNDA 65: tylko gmina + JEDYNY/DRUGI-KONTAKT + akcja
        }
        // RUNDA 14: loguj ZAWSZE (też 0 garbów) — żeby było widać że anatomia przebiegła po każdym cięciu.
        if (shown > 0) log.info("Coverage SPUR-ANATOMIA v6 [{}] ({} garbów ≥1km, top {}):{}",
                new Object[]{phase, kept.size(), Math.min(shown, 60), sb});
        else log.info("Coverage SPUR-ANATOMIA v6 [{}]: 0 garbów ≥1km (brak zaułków)", phase);
    }


    /** Wynik GROW-NEAR: realny effort po wstawkach + ile gmin dorzucono. */
    private record GrowNearResult(double effort, int inserted) {}

    private record NearCand(UnvisitedArea area, double dist, double score) {}

    /** Promień doboru kandydatów grow-near wokół aktualnej trasy. */
    private static final double GROW_NEAR_R_KM = 25.0;

    /** Kandydaci ≤25 km od AKTUALNEJ trasy: score = reward×(1+enclosed)/max(0.5,dist), sort DESC.
     *  Wołane na wejściu batch-grow i przy refreshu po wyczerpaniu listy (v3.7). */
    private List<NearCand> nearCandidates(Set<Integer> visited, Set<Integer> skip,
                                          List<double[]> route) {
        List<NearCand> cands = new ArrayList<>();
        for (UnvisitedArea a : pool) {
            if (visited.contains(a.areaId()) || skip.contains(a.areaId())) continue;
            double d = gminaIndex.distToRoute(a, route);
            if (d > GROW_NEAR_R_KM) continue;
            double rw = rewards.getOrDefault(RewardModel.categoryKey(a), 1.0);
            double enc = gminaIndex.enclosedFraction(a.areaId(), visited);
            cands.add(new NearCand(a, d, rw * (1.0 + enc) / Math.max(0.5, d)));
        }
        cands.sort((x, y) -> Double.compare(y.score(), x.score()));
        return cands;
    }

    /**
     * GROW-NEAR v3.8 — BATCH-GROW (wzorzec init-grow, decyzja usera): batche po {@code batchSize} +
     * twoOptIncremental NA BIEŻĄCO (spur rozplątany zanim się utrwali) + estymator L2
     * (haversine × samokalibrowany factor) z realnym checkpointem co {@code checkpointEvery}
     * batchy / przy stopie. Zastępuje RATIO-GRAB, który przestrzeliwał (compact-ratio 13/gminę
     * vs marginalna wstawka ~28/gminę → 61%↔147% oscylacja → tailPrune mielił 1-2k calli/cykl).
     * Checkpoint = pełny 2opt + real (stop nie odpala na chwilowo spuchniętej kolejności);
     * wyczerpanie kandydatów → refresh listy na aktualnej trasie (max 2).
     * Islands-check + kredyt-verify RAZ po pętli.
     * Dobór score-based bez zmian: {@code reward × (1+enclosed) / max(0.5, distToRoute)}.
     * Tryby (v3.8): zwykły grow (12, 3) i TOP-UP — domykacz pasma (6, 1: pomiar po każdej szóstce,
     * nie da się przestrzelić).
     */
    private GrowNearResult growNear(SeedRoute seed, double growTarget,
                                    int batchSize, int checkpointEvery, int maxInserts) {
        List<double[]> route = seed.route(); List<SeedSel> selected = seed.selected(); List<double[]> anchorOnly = seed.anchorOnly(); List<double[]> baseline = seed.baseline(); double[] baseCum = seed.baseCum();
        final int BATCH = batchSize;
        final int CHECKPOINT_EVERY = checkpointEvery;
        // v3.17b: maxInserts > 0 = dobieranie wg PROPORCJI (wstaw dokładnie tyle gmin, zmierz raz na
        // końcu — bez sterowania effort-targetem). 0 = stary tryb effort-driven (nieużywany po v3.17b).
        long callsStart = edgeRouter.realCalls(); // v3.18: realne strzały (nie misses)
        RouteMetrics.EvalResult ev = metrics.eval(route);
        double effort = ev.effort();
        if (effort >= growTarget) return new GrowNearResult(effort, 0);
        Set<Integer> visited = ev.visited();
        List<double[]> geomRef = ev.geometry();

        Set<Integer> myInsertAreas = new HashSet<>();
        List<NearCand> cands = nearCandidates(visited, myInsertAreas, route);

        // Estymator L2: effort ≈ haversine(route) × factor. Factor startuje z realnego stanu
        // trasy i jest rekalibrowany na checkpointach — samodostosowuje się do terenu/profilu.
        double hav = metrics.haversineKm(route);
        double effortFactor = hav > 1 ? effort / hav : 2.0;
        int inserted = 0;
        int ci = 0;
        int batchCount = 0;
        int checkpoints = 0;
        int refreshes = 0;
        double lastMeasured = effort; // do undo: ostatni REALNY pomiar (wejściowy jest realny)
        int sinceMeasure = 0;         // ile wstawek od ostatniego realnego pomiaru
        List<SeedSel> allInserts = new ArrayList<>();
        Set<Integer> retriedCentroid = new HashSet<>();
        while (true) {
            if (maxInserts > 0 && inserted >= maxInserts) break; // v3.17b: limit dobierania wg proporcji
            List<SeedSel> batchSels = new ArrayList<>();
            while (batchSels.size() < BATCH && (maxInserts <= 0 || inserted + batchSels.size() < maxInserts)
                    && ci < cands.size()) {
                NearCand nc = cands.get(ci++);
                if (visited.contains(nc.area().areaId())) continue;
                if (myInsertAreas.contains(nc.area().areaId())) continue;
                double[] ep = gminaIndex.deepestInteriorPoint(nc.area().areaId()); // RUNDA 51: najgłębszy
                if (ep == null) ep = GeometryUtil.sampleNearestToGeometry(gminaIndex.samplePointsFor(nc.area()), null, geomRef);
                if (ep == null) continue;
                SeedSel sel = new SeedSel(nc.area(), ep, ordering.orderKey(ep), 0.0,
                        GeometryUtil.minDistToBaselineKm(ep, baseline));
                selected.add(sel);
                batchSels.add(sel);
                allInserts.add(sel);
                myInsertAreas.add(nc.area().areaId());
                // v3.9: WSUWANIE zamiast tasowania — punkt wchodzi tam, gdzie najmniej nadkłada
                // (linijka), reszta kolejności bez zmian → BRouter pyta tylko o dojazd do nowej
                // gminy (~2/szt), nie o pół trasy po każdym przetasowaniu z hilberta.
                route.add(GeometryUtil.cheapestInsertPos(route, ep), ep);
            }
            if (batchSels.isEmpty()) {
                // v3.7: wyczerpanie listy ≠ koniec wzrostu — kandydaci liczeni na trasie SPRZED
                // wstawek, a nowe segmenty mają własnych sąsiadów ≤25 km (cykl 1 stawał na 88%
                // targetu po 48 wstawkach). Refresh na aktualnej trasie, max 2.
                if (refreshes >= 2) break;
                refreshes++;
                RouteMetrics.EvalResult rev = metrics.eval(route);
                effort = rev.effort();
                visited = rev.visited();
                geomRef = rev.geometry();
                hav = metrics.haversineKm(route);
                if (hav > 1) effortFactor = effort / hav;
                lastMeasured = effort;
                sinceMeasure = 0;
                if (effort >= growTarget) break;
                cands = nearCandidates(visited, myInsertAreas, route);
                ci = 0;
                if (cands.isEmpty()) break;
                continue;
            }
            inserted += batchSels.size();
            sinceMeasure += batchSels.size();
            batchCount++;
            // v3.22 (Twoje): pełny 2opt po KAŻDYM dobraniu w końcowych cyklach — tanie CPU, skraca
            // trasę na bieżąco (mniej brudu przed cięciem ogonków). Bez rebuildu z hilberta (v3.9).
            twoOptLogged(route, "growNear-batch" + batchCount);
            hav = metrics.haversineKm(route);
            double est = hav * effortFactor;
            boolean doReal = (batchCount % CHECKPOINT_EVERY == 0) || est >= growTarget;
            if (doReal) {
                // Pomiar real na uporządkowanej kolejności (2opt już zrobiony co batch wyżej).
                hav = metrics.haversineKm(route);
                effort = metrics.effortViaCache(route);
                checkpoints++;
                if (hav > 1) effortFactor = effort / hav;
                // UNDO porcji (v3.10, wzór init-grow): blisko celu porcja 3× droższa od średniej
                // = złapaliśmy DALEKIE gminy (topup 21:31 przestrzelił do 120% growTargetu na
                // ostatniej porcji ~125 effortu/szt) → cofnij ostatnią porcję i zakończ dosypkę.
                if (lastMeasured > 0 && sinceMeasure > 0 && effort >= 0.9 * growTarget) {
                    double marginal = (effort - lastMeasured) / sinceMeasure;
                    double ratio = effort / Math.max(1, selected.size());
                    if (marginal > 3 * ratio) {
                        for (SeedSel s : batchSels) {
                            selected.remove(s);
                            route.remove((Object) s.point());
                            allInserts.remove(s);
                            myInsertAreas.remove(s.area().areaId());
                            inserted--;
                        }
                        twoOptLogged(route, "growNear-undo");
                        effort = metrics.effortViaCache(route);
                        log.info("Coverage BATCH-GROW undo porcji {}: marginal={}/gminę > 3×ratio={} (dalekie gminy) → stop na {}% growTargetu",
                                new Object[]{batchCount, Math.round(marginal), Math.round(ratio),
                                        Math.round(effort * 100.0 / growTarget)});
                        break;
                    }
                }
                lastMeasured = effort;
                sinceMeasure = 0;
            } else {
                effort = est;
            }
            if (effort >= growTarget) break;
        }

        // FINALIZACJA (raz, nie per batch): pełny 2opt (bez rebuildu — v3.9) → real →
        // islands-check → kredyt-verify.
        twoOptLogged(route, "growNear-final");
        effort = metrics.effortViaCache(route);
        int islands = 0;
        for (SeedSel s : allInserts) {
            int pos = -1;
            for (int i = 1; i < route.size() - 1; i++) {
                if (route.get(i) == s.point()) { pos = i; break; }
            }
            if (pos < 0) continue;
            boolean fail = edgeRouter.failedEdges().contains(GeometryUtil.edgeKey(route.get(pos - 1), s.point()))
                    || edgeRouter.failedEdges().contains(GeometryUtil.edgeKey(s.point(), route.get(pos + 1)));
            if (fail) {
                selected.remove(s);
                route.remove((Object) s.point()); // v3.9: bez rebuildu route czyścimy ręcznie
                myInsertAreas.remove(s.area().areaId());
                islands++;
                inserted--;
            }
        }
        if (islands > 0) {
            twoOptLogged(route, "growNear-islands");
            effort = metrics.effortViaCache(route);
        }
        // Kredyt-verify (wp 206): max 3 rundy (1. wykrycie → retry centroid, 2. weryfikacja centroidu → delete).
        for (int vr = 0; vr < 3; vr++) {
            visited = gminaIndex.visitedAreaIds(metrics.realGeometry(route));
            if (!verifyInsertCredit(seed, myInsertAreas, retriedCentroid, visited)) break;
            effort = metrics.effortViaCache(route);
        }
        int credited = 0;
        for (SeedSel s : selected) if (myInsertAreas.contains(s.area().areaId()) && s.score() == 0.0) credited++;
        log.info("Coverage BATCH-GROW: batches={}, inserted={}, islands={}, checkpoints={}, refreshes={}, factor={}, calls={}, effort → {} ({}% growTarget)",
                new Object[]{batchCount, Math.min(inserted, credited), islands, checkpoints, refreshes,
                        String.format(java.util.Locale.ROOT, "%.2f", effortFactor),
                        edgeRouter.realCalls() - callsStart,
                        Math.round(effort), Math.round(effort * 100.0 / growTarget)});
        return new GrowNearResult(effort, Math.min(inserted, credited));
    }

    /** Score-sentinel wstawek ENCLOSED-FILL — TRIM (sort score ASC, usuwa front) nigdy ich nie rusza:
     *  otoczona dziura w środku blobu jest gorsza niż przestrzał budżetu (decyzja usera 2026-06-12). */
    private static final double ENCLOSED_PROTECTED_SCORE = Double.MAX_VALUE;
    /** TRIM peeling: cap rund cięcia w jednej iteracji TRIM (proporcja zbiega, to tylko bezpiecznik). */
    private static final int TRIM_MAX_PEELS = 8;
    /** TRIM peeling: anty-dzielenie-przez-0 dla kosztu-objazdu (km) — kolateral (detour≈0) → reward/EPS = ogromne = nie tnij. */
    private static final double TRIM_DETOUR_EPS = 0.05;
    /** TRIM peeling: poniżej tego spadku effortu (km+α·m) runda uznana za „bez postępu" → stop (ucięto kolateral). */
    private static final double TRIM_PROGRESS_EPS = 1.0;
    /** Próg otoczenia dla ENCLOSED-FILL: 1.0 = wszystkie 8/8 k-NN sąsiadów zaliczone (decyzja usera). */
    private static final double ENCLOSED_THRESHOLD = 1.0;

    /**
     * ENCLOSED-FILL (v3.4, decyzja usera): seed KOŃCZY się gwarancją braku DZIUR — gmin otoczonych
     * z każdej strony zaliczonymi ({@code enclosedFraction ≥ ENCLOSED_THRESHOLD}). Takie dziury
     * są tanie (ślad je opływa → mały detour), a leżą W ŚRODKU blobu — user nie chce do nich wracać.
     * Domyka WSZYSTKIE bezwarunkowo; budżet równa KOŃCÓWKA cyklu (tailPrune/TOP-UP/TRIM) — v3.8
     * wycięła stąd cięcie peryferii (cut-24 → clean −13 pp → grow odkupywał = churn ~1500 calli/cykl
     * i punkt stały 92%). Wstawki dostają {@link #ENCLOSED_PROTECTED_SCORE} → TRIM ich nie rusza.
     * Zwraca liczbę domknięć.
     */
    private int enclosedFill(SeedRoute seed, double targetEffort) {
        List<double[]> route = seed.route(); List<SeedSel> selected = seed.selected(); List<double[]> anchorOnly = seed.anchorOnly(); List<double[]> baseline = seed.baseline(); double[] baseCum = seed.baseCum();
        long callsStart = edgeRouter.realCalls(); // v3.18: realne strzały (nie misses)
        edgeRouter.setReason("dziura-otoczona"); // v3.16: ENCLOSED-FILL = łatanie dziur otoczonych
        int filled = 0;
        int unreachable = 0;
        for (int iter = 0; iter < 3; iter++) {
            RouteMetrics.EvalResult ev = metrics.eval(route);
            Set<Integer> visited = ev.visited();
            List<double[]> geom = ev.geometry();
            // v3.15: dziury otoczone wg REALNEGO sąsiedztwa wielokątów (port JTS, touches) — gmina
            // nieprzecięta, której WSZYSCY sąsiedzi (cross-border, bez progu na liczbę) są zaliczeni dookoła.
            // Zastępuje centroidowy enclosedFraction (mylił przy nieregularnych kształtach).
            Set<Integer> enclosed = gminaIndex.enclosedUnvisited(visited);
            List<UnvisitedArea> holes = new ArrayList<>();
            for (UnvisitedArea a : pool) {
                if (enclosed.contains(a.areaId())) holes.add(a);
            }
            if (holes.isEmpty()) break;
            holes.sort((x, y) -> Double.compare(gminaIndex.distToRoute(x, route), gminaIndex.distToRoute(y, route)));
            List<SeedSel> added = new ArrayList<>();
            for (UnvisitedArea a : holes) {
                double[] ep = gminaIndex.deepestInteriorPoint(a.areaId()); // RUNDA 51: najgłębszy
                if (ep == null) ep = GeometryUtil.sampleNearestToGeometry(gminaIndex.samplePointsFor(a), null, geom);
                if (ep == null) continue;
                SeedSel sel = new SeedSel(a, ep, ordering.orderKey(ep), ENCLOSED_PROTECTED_SCORE,
                        GeometryUtil.minDistToBaselineKm(ep, baseline));
                selected.add(sel);
                added.add(sel);
            }
            if (added.isEmpty()) break;
            rebuildOrderedRoute(route, anchorOnly, selected, baseline, baseCum);
            twoOptLogged(route, "enclosedFill");
            // Kredyt-verify: otoczona dziura też może być nieosiągalna (most/wyspa) → retry centroid → usuń.
            Set<Integer> vis = gminaIndex.visitedAreaIds(
                    metrics.realGeometry(route));
            for (SeedSel s : new ArrayList<>(added)) {
                if (vis.contains(s.area().areaId())) continue;
                double[] c = gminaIndex.deepestInteriorPoint(s.area().areaId()); // RUNDA 51: najgłębszy (nie centroid)
                if (c == null) c = new double[]{s.area().lng(), s.area().lat()};
                swapEntryPoint(selected, s.point(), c, baseline, baseCum);
            }
            rebuildOrderedRoute(route, anchorOnly, selected, baseline, baseCum);
            twoOptLogged(route, "enclosedFill-centroid");
            vis = gminaIndex.visitedAreaIds(metrics.realGeometry(route));
            for (SeedSel s : new ArrayList<>(added)) {
                if (!vis.contains(s.area().areaId())) { selected.remove(s); added.remove(s); unreachable++; }
            }
            rebuildOrderedRoute(route, anchorOnly, selected, baseline, baseCum);
            twoOptLogged(route, "enclosedFill-verify");
            filled += added.size();
        }
        if (filled > 0 || unreachable > 0) {
            double eff = metrics.effortViaCache(route);
            log.info("Coverage ENCLOSED-FILL: domknięto={} dziur (8/8 otoczone), nieosiągalne={}, calls={}, effort → {} ({}%)",
                    new Object[]{filled, unreachable, edgeRouter.realCalls() - callsStart, Math.round(eff),
                            Math.round(eff * 100.0 / targetEffort)});
        }
        return filled;
    }


    /**
     * Weryfikacja kredytu wstawek grow-near (po areaId): gmina wstawki NIEzaliczona →
     * 1. raz podmień entry na centroid (najgłębiej), 2. raz usuń wstawkę. Zwraca true gdy
     * coś zmieniono (caller robi rebuild+2opt+pomiar). Wzorzec „wp 206/Kozłowo".
     */
    private boolean verifyInsertCredit(SeedRoute seed, Set<Integer> myInsertAreas, Set<Integer> retriedCentroid,
                                       Set<Integer> visited) {
        List<double[]> route = seed.route(); List<SeedSel> selected = seed.selected(); List<double[]> anchorOnly = seed.anchorOnly(); List<double[]> baseline = seed.baseline(); double[] baseCum = seed.baseCum();
        boolean changedAny = false;
        for (SeedSel s : new ArrayList<>(selected)) {
            int aid = s.area().areaId();
            if (!myInsertAreas.contains(aid) || s.score() != 0.0 || visited.contains(aid)) continue;
            if (retriedCentroid.add(aid)) {
                double[] centroid = new double[]{s.area().lng(), s.area().lat()}; // (verifyInsertCredit nie ma gminaIndex; deep robi insert wyżej)
                swapEntryPoint(selected, s.point(), centroid, baseline, baseCum);
            } else {
                selected.remove(s);
                myInsertAreas.remove(aid);
            }
            changedAny = true;
        }
        if (changedAny) {
            rebuildOrderedRoute(route, anchorOnly, selected, baseline, baseCum);
            twoOptLogged(route, "verify-insert-credit");
        }
        return changedAny;
    }


    /** v3.19: wynik relokacji odroczonej — kredyt obu nowych nóg (B2) + ewentualna noga powrotna do batcha. */
    private record RelocResult(boolean ok, Set<Integer> newInCredit, Set<Integer> newOutCredit, double[][] pendingDeparture) {
        static RelocResult fail() { return new RelocResult(false, null, null, null); }
    }

    /**
     * RELOKACJA v3.19 — DECYZJA W JTS, noga powrotna ODROCZONA do batcha. Spłyca spur JEDYNY-KONTAKT na
     * granicę gminy: najpłytszy wierzchołek własnej nogi kredytujący WSZYSTKIE {@code exclusive}
     * ({@link #shallowestCoveringVertex}, 0 calli), slice dojazdu (0 calli), stawia waypoint. BEZ
     * effort-checku (spłycenie ZAWSZE skraca i zachowuje kredyt — dojazd-slice pokrywa exclusive z
     * konstrukcji) i BEZ routowania nogi powrotnej: tam-i-z-powrotem = slice (0 calli), loop-spur = noga
     * PENDING (caller batchuje przez {@link #prewarmPairs} — 1 równoległy strzał/pass zamiast per-tail =
     * koniec 838 strzałów). Zwraca kredyt obu nóg (B2 legGminas; pending-loop noga powrotna = estymata
     * {@code {g0}}, liczona dokładnie w kolejnym passie z cache po batchu). Mutuje route+selected.
     */
    private RelocResult relocateShallowDeferred(SeedRoute seed, Set<Integer> exclusive, double[] prev, double[] cur, double[] next,
                                                int idx, UnvisitedArea g, EdgeCache.EdgeInfo eIn, EdgeCache.EdgeInfo eOut,
                                                boolean allowReroute) {
        List<double[]> route = seed.route(); List<SeedSel> selected = seed.selected(); List<double[]> baseline = seed.baseline(); double[] baseCum = seed.baseCum();
        if (exclusive.isEmpty()) return RelocResult.fail();
        // RUNDA 59: guard RUNDA 39 (cur kredytuje −200 → fail) USUNIĘTY. Reguła 2b: jedyny-wjazd ma być DOKŁADNIE 220m —
        // za-głęboki (kredytuje −200, ale >220m) trzeba SKRÓCIĆ do 220m. Cel = firstBufferEntryPoints (220m); gdy cur już
        // ~220m → newWp≈cur → `haversineKm(newWp,cur)<0.15` (niżej) = no-op → ZOSTAW (bez churnu). Wołane TYLKO dla zaułków.
        for (int side = 0; side < 2; side++) {
            boolean inSide = side == 0;
            EdgeCache.EdgeInfo own = inSide ? eIn : eOut;
            List<double[]> walk;
            if (inSide) {
                walk = own.geometry();
            } else {
                walk = new ArrayList<>(own.geometry());
                java.util.Collections.reverse(walk);
            }
            // RUNDA 54: cel −220 (jak anchor/D-slice), NIE −200. RUNDA 39 pchała do pierwszego wierzchołka w buforze
            // −200 (≈200m) → ślad sięgał tylko 200m → w cyklu N+1 anchor (próg −220) widział muśnięcie → centroid (#117).
            // firstBufferEntryPoints zwraca punkt 220m w głąb g NA tej nodze (ta sama maszyneria co anchor); bierzemy
            // wierzchołek najbliższy → wp ~220m. null = noga nie wchodzi ≥220m w g → ta strona odpada.
            double[] deep = gminaIndex.firstBufferEntryPoints(walk).get(g.areaId());
            if (deep == null) continue;
            // RUNDA 67: wp = DOKŁADNY punkt przecięcia śladu z buforem −220 (firstBufferEntryPoints, TEN SAM co anchor),
            // NIE najbliższy wierzchołek (snap lądował POZA −220 = płytko). Slice własnej nogi W SEGMENCIE zawierającym deep.
            double[] newWp = deep.clone();
            // RUNDA 68: wp JUŻ na 220m na TEJ stronie → ZOSTAW (return fail), NIE przerzucaj na drugą (wyjazd). Inaczej
            // #169 Mława (na wjeździe) przeskakiwał na #170 (wyjazd). Genuine deep zaułek: cur głęboki ≠ cel → nie no-op.
            if (velomarker.service.planning.WaypointSelector.haversineKm(newWp, cur) < 0.15) return RelocResult.fail();
            List<double[]> ownGeom = own.geometry();
            int segOwn = -1; double bestSegSD = Double.MAX_VALUE;
            for (int m = 0; m < ownGeom.size() - 1; m++) {
                double sd = GeometryUtil.pointToSegmentExactKm(newWp, ownGeom.get(m), ownGeom.get(m + 1));
                if (sd < bestSegSD) { bestSegSD = sd; segOwn = m; }
            }
            if (segOwn < 0) continue;
            Set<Integer> newInCredit;
            Set<Integer> newOutCredit;
            double[][] pendingDeparture;
            if (inSide) {
                edgeRouter.seedSlicedEdgesAtPoint(eIn, prev, cur, segOwn, newWp); // prev→newWp DOKŁADNY punkt (0 calli)
                newInCredit = gminaIndex.visitedAreaIds(
                        edgeRouter.edge(prev, newWp).geometry()); // cache-hit
                EdgeCache.EdgeInfo dep = edgeRouter.sliceDepart(eOut, cur, next, newWp, true);
                if (dep != null) {                                              // tam-i-z-powrotem = slice (0 calli)
                    newOutCredit = gminaIndex.visitedAreaIds(dep.geometry());
                    pendingDeparture = null;
                } else if (allowReroute) {                                      // v3.30 (Q2): loop → REROUTE nogi powrotnej
                    newOutCredit = Set.of(g.areaId());                          // (1 strzał, bounded+stay przez caller)
                    pendingDeparture = new double[][]{newWp, next};
                } else continue;                                               // cap przekroczony → slice-only, fail
            } else {
                edgeRouter.seedSlicedEdgesAtPoint(eOut, cur, next, segOwn, newWp); // newWp→next DOKŁADNY punkt (0 calli)
                newOutCredit = gminaIndex.visitedAreaIds(
                        edgeRouter.edge(newWp, next).geometry()); // cache-hit
                EdgeCache.EdgeInfo dep = edgeRouter.sliceDepart(eIn, prev, cur, newWp, false);
                if (dep != null) {
                    newInCredit = gminaIndex.visitedAreaIds(dep.geometry());
                    pendingDeparture = null;
                } else if (allowReroute) {                                      // v3.30 (Q2): loop → REROUTE dojazdu (#111/#32)
                    newInCredit = Set.of(g.areaId());
                    pendingDeparture = new double[][]{prev, newWp};
                } else continue;
            }
            swapEntryPoint(selected, cur, newWp, baseline, baseCum);
            route.set(idx, newWp);
            return new RelocResult(true, newInCredit, newOutCredit, pendingDeparture);
        }
        return RelocResult.fail();
    }


    /**
     * Najpłytszy wierzchołek k (>0), dla którego prefiks {@code geom[0..k]} kredytuje WSZYSTKIE
     * {@code need} (kryterium kredytu = port JTS {@code visitedAreaIds}). Monotonia (dłuższy prefiks
     * to nadzbiór segmentów = nie mniej kredytowanych gmin) → binary search, ~log(n) wywołań JTS,
     * 0 BRouter. -1 gdy pełny leg nie pokrywa {@code need}.
     */
    private static int shallowestCoveringVertex(List<double[]> geom, Set<Integer> need, GminaIndex idx) {
        if (need.isEmpty() || geom.size() < 3) return -1;
        if (!idx.visitedAreaIds(geom).containsAll(need)) return -1; // pełny leg nie pokrywa
        int lo = 1, hi = geom.size() - 2, ans = geom.size() - 2;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (idx.visitedAreaIds(geom.subList(0, mid + 1)).containsAll(need)) { ans = mid; hi = mid - 1; }
            else lo = mid + 1;
        }
        return ans;
    }

    /**
     * v3.24 GEOMETRIA „kutas": wykrywa OUT-AND-BACK — eOut wraca TĄ SAMĄ drogą co eIn (ślad wystaje
     * z linii i wraca). {@code eIn}=[prev..cur], {@code eOut}=[cur..next], cur wspólny. Liczy m = długość
     * retrace'u (eOut[k] ≈ eIn[koniec-k] dla k=1..m, tol {@value #RETRACE_TOL_KM} km). Zwraca punkt
     * ROZEJŚCIA D = eIn[koniec-m] = eOut[m] (gdzie linia przestaje wracać po sobie = ciągły ślad). D jest
     * wierzchołkiem OBU nóg → slice obu = 0 BRouter. null = brak retrace (nie there-and-back / loop).
     */
    private double[] outAndBackDivergence(EdgeCache.EdgeInfo eIn, EdgeCache.EdgeInfo eOut) {
        List<double[]> gi = eIn.geometry();
        List<double[]> go = eOut.geometry();
        int ni = gi.size(), no = go.size();
        if (ni < 3 || no < 3) return null;
        int m = 0;
        while (m + 1 <= ni - 2 && m + 1 <= no - 2
                && velomarker.service.planning.WaypointSelector.haversineKm(gi.get(ni - 2 - m), go.get(m + 1)) <= RETRACE_TOL_KM) {
            m++;
        }
        if (m == 0) return null;
        return gi.get(ni - 1 - m).clone();
    }










    /**
     * RUNDA 24 — ANCHOR-INTERSECTS (główny silnik pokrycia, raz na cykl). RESET wszystkich: dla KAŻDEJ gminy którą
     * ślad DOTYKA (pełny wielokąt, {@code touchedAreaIds} — nawet muśnięcie rogiem) ustawia świeży wp wg reguły:
     * <ul>
     *   <li>ślad wchodzi ≥200m w rdzeń → wp na PIERWSZYM wejściu wzdłuż śladu ({@code firstCreditedCrossing.entry});</li>
     *   <li>tylko muska (nigdzie ≥200m) → wp w CENTROIDZIE gminy ({@code area.lng/lat}) — reroute wepchnie ślad w głąb,
     *       następny cykl go spłyci.</li>
     * </ul>
     * Stare wp-gmin są kasowane i postawione od nowa. Targety liczone na SNAPSHOCIE śladu (przed resetem). Kończy
     * {@code rebuildOrderedRoute + twoOpt} (0 BRouter — realny reroute wchodzi w {@code tailPruneJts} które leci potem).
     */
    private void anchorResetTouched(SeedRoute seed, String debugPhase) {
        List<double[]> route = seed.route(); List<SeedSel> selected = seed.selected(); List<double[]> anchorOnly = seed.anchorOnly(); List<double[]> anchors = seed.anchors(); List<double[]> baseline = seed.baseline(); double[] baseCum = seed.baseCum();
        long startNs = System.nanoTime();
        Map<Integer, UnvisitedArea> areaById = new HashMap<>();
        for (UnvisitedArea area : pool) areaById.put(area.areaId(), area);
        // RUNDA 52: gminy z głębokim (≥220m) start/meta/via — obowiązkowy punkt pokrywa gminę → bez osobnego wp.
        Set<Integer> deepAnchorAreaIds = new HashSet<>();
        for (double[] anchor : anchors) {
            UnvisitedArea anchorArea = gminaIndex.findDeeplyCreditedGminaForPoint(anchor[0], anchor[1]);
            if (anchorArea != null) deepAnchorAreaIds.add(anchorArea.areaId());
        }
        // RUNDA 64: PĘTLA DO-SKUTKU. {kotwicz touched → 2opt → REROUTE}. REROUTE potrafi przeprowadzić ślad GŁĘBOKO
        // (≥220m) przez NOWĄ gminę, której PRZED kotwiczeniem nie dotykał (Tomaszów Maz. — przelot po reorderze) → nie
        // miała kotwicy. Powtarzaj aż żadna gmina nie jest wchodzona ≥220m bez kotwicującego wp.
        int iteration = 0, anchoredOnEntry = 0, anchoredOnCentroid = 0, touchedCount = 0;
        boolean foundUncoveredDeep = true;
        List<double[]> realTrack = metrics.realGeometry(route);
        while (foundUncoveredDeep && iteration < 5) {
            iteration++;
            Map<Integer, double[]> entryPoints = gminaIndex.firstBufferEntryPoints(realTrack); // gmina→pierwsze −220 (≥220m)
            Set<Integer> touchedAreaIds = gminaIndex.touchedAreaIds(realTrack);                 // pełny wielokąt (muśnięcia → centroid)
            touchedCount = touchedAreaIds.size();
            List<SeedSel> freshAnchors = new ArrayList<>();
            anchoredOnEntry = 0; anchoredOnCentroid = 0;
            for (int areaId : touchedAreaIds) {
                if (deepAnchorAreaIds.contains(areaId)) continue;
                UnvisitedArea area = areaById.get(areaId);
                if (area == null) continue;
                double[] anchorPoint = entryPoints.get(areaId);
                if (anchorPoint != null) {                                              // ślad wchodzi ≥220m → wp na pierwszym wejściu
                    anchoredOnEntry++;
                } else {                                                                // tylko muśnięcie → najgłębszy punkt gminy
                    double[] deepest = gminaIndex.deepestInteriorPoint(areaId);
                    anchorPoint = deepest != null ? deepest : new double[]{area.lng(), area.lat()};
                    anchoredOnCentroid++;
                }
                freshAnchors.add(new SeedSel(area, anchorPoint, ordering.orderKey(anchorPoint), 0.0,
                        GeometryUtil.minDistToBaselineKm(anchorPoint, baseline)));
            }
            // RESET: usuń nie-anchor wp leżące w gminie; postaw świeże; ułóż + 2opt.
            selected.removeIf(s -> !GeometryUtil.isAnchor(s.point(), anchors)
                    && gminaIndex.findGminaForPoint(s.point()[0], s.point()[1]) != null);
            selected.addAll(freshAnchors);
            rebuildOrderedRoute(route, anchorOnly, selected, baseline, baseCum);
            twoOptLogged(route, "anchor-intersected" + (iteration > 1 ? "-i" + iteration : ""));
            // REROUTE realny: concatRealGeometry liczy nowe nogi (BRouter) → ujawnia głębokie przeloty nowego porządku.
            realTrack = metrics.realGeometry(route);
            // KONIEC gdy żadna gmina nie jest wchodzona ≥220m bez kredytującego wp.
            Map<Integer, double[]> deepEntriesNow = gminaIndex.firstBufferEntryPoints(realTrack);
            Set<Integer> anchoredAreaIds = new HashSet<>();
            for (SeedSel s : selected) {
                UnvisitedArea creditedArea = gminaIndex.findCreditedGminaForPoint(s.point()[0], s.point()[1]);
                if (creditedArea != null) anchoredAreaIds.add(creditedArea.areaId());
            }
            for (double[] anchor : anchors) {
                UnvisitedArea creditedArea = gminaIndex.findCreditedGminaForPoint(anchor[0], anchor[1]);
                if (creditedArea != null) anchoredAreaIds.add(creditedArea.areaId());
            }
            foundUncoveredDeep = false;
            int uncoveredDeepCount = 0;
            for (int areaId : deepEntriesNow.keySet())
                if (!anchoredAreaIds.contains(areaId) && !deepAnchorAreaIds.contains(areaId) && areaById.containsKey(areaId)) {
                    foundUncoveredDeep = true; uncoveredDeepCount++;
                }
            log.info("Coverage ANCHOR-INTERSECTS [{}] iter {}: touched={}, wejście={} centroid={}, nowe-głębokie-bez-kotwicy={}",
                    new Object[]{debugPhase, iteration, touchedAreaIds.size(), anchoredOnEntry, anchoredOnCentroid, uncoveredDeepCount});
        }
        log.info("Coverage ANCHOR-INTERSECTS [{}]: KONIEC po {} iter, touched={}, dt={}ms",
                new Object[]{debugPhase, iteration, touchedCount, (System.nanoTime() - startNs) / 1_000_000});
    }



    /**
     * RECONCILE PO FINALE: wpina nieodwiedzone gminy blisko REALNEJ finalnej trasy. Densify działał na
     * geometrii seeda (z waypointów); po realnym chunked BRouterze trasa się przesuwa, odsłaniając gminy
     * które realna droga mija ≤R, ale ich nie zalicza. Dla każdej (najbliższe pierwsze) cheapest-insertion
     * entry-pointu do {@code route} (zachowuje kolejność 2-opt), z bramką budżetu (effort ≤ 110%), potem
     * re-route. Mutuje {@code route}. Zwraca nową {@link RouteCalculation} (lub wejściową gdy nic nie dodano).
     */
    /**
     * Sumaryczny wznios liczony OKNAMI (≤{@value #CLIMB_WINDOW_PTS} pkt/okno). {@code elevation.sample}
     * capuje liczbę próbek (~500), więc JEDEN sample na 2000+ km alpejskich rażąco zaniża climb — przez
     * to reconcile myślał, że ma budżet i balonował go (do ~179%). Próbkowanie oknami trzyma gęstość
     * ~kilkadziesiąt m/próbkę niezależnie od długości trasy → realny wznios do bramki budżetu.
     */
    private static final int CLIMB_WINDOW_PTS = 400;












    /** Podmień entry-point danej gminy w {@code selected} (identity po starym punkcie) + przelicz sortKey. */
    private void swapEntryPoint(List<SeedSel> selected, double[] oldPoint, double[] newPoint,
                                List<double[]> baseline, double[] baseCum) {
        for (int i = 0; i < selected.size(); i++) {
            if (selected.get(i).point() == oldPoint) {
                SeedSel old = selected.get(i);
                selected.set(i, new SeedSel(old.area(), newPoint, ordering.orderKey(newPoint),
                        old.score(), GeometryUtil.minDistToBaselineKm(newPoint, baseline)));
                return;
            }
        }
    }

    /** Buduje route = anchory + selected entry-pointy posortowane wg {@link #orderKey} (projekcja lub serpentyna). */
    private void rebuildOrderedRoute(List<double[]> route, List<double[]> anchorOnly,
                                     List<SeedSel> selected, List<double[]> baseline, double[] baseCum) {
        record RoutePt(double[] p, double key) {}
        List<RoutePt> ordered = new ArrayList<>(anchorOnly.size() + selected.size());
        for (int i = 0; i < anchorOnly.size(); i++) {
            double key = (i == 0) ? Double.NEGATIVE_INFINITY
                    : (i == anchorOnly.size() - 1) ? Double.MAX_VALUE
                    : ordering.orderKey(anchorOnly.get(i));
            ordered.add(new RoutePt(anchorOnly.get(i), key));
        }
        for (SeedSel s : selected) ordered.add(new RoutePt(s.point(), s.proj()));
        ordered.sort(Comparator.comparingDouble(RoutePt::key));
        route.clear();
        for (RoutePt rp : ordered) route.add(rp.p());
    }





    /**
     * Liczba ODRĘBNYCH wizyt per gmina (segmenty geometrii w ringu oddzielone gap &gt; 10 km).
     * ≥2 = gmina pokryta w kilku miejscach trasy → jej explicit waypoint jest redundantny (stub
     * można wyrzucić, naturalny przejazd nadal ją zalicza). Jeden przebieg O(geom) grid-lookup.
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



    /** RUNDA 41: 2-opt z logiem (gdy debugGeoJson) — user „chcę wiedzieć kiedy to idzie". Mierzy TANIO przez
     *  haversine km (0 BRouter/elewacji → OK w pętlach growa). Etykieta `phase` = które miejsce i kiedy. */
    private void twoOptLogged(List<double[]> route, String phase) {
        if (!debugGeoJson) { CoverageLocalSearch.twoOpt(route); return; }
        double kmBefore = metrics.haversineKm(route);
        int wp = route.size();
        CoverageLocalSearch.twoOpt(route);
        double kmAfter = metrics.haversineKm(route);
        log.info("Coverage 2-OPT [{}]: havKm {}→{} (Δ{}), wps={}", new Object[]{phase,
                Math.round(kmBefore), Math.round(kmAfter), Math.round(kmAfter - kmBefore), wp});
    }





    // ── ADMIN DEBUG: GeoJSON snapshot trasy per faza (do paste-to-map w mapie rysowania) ──────────────
    // Guarded flagą debugGeoJson (default OFF). Loguje JEDNĄ linię GEOJSON-DEBUG [faza] = {FeatureCollection}.
    // User: breakpoint/kopiuj z konsoli → wklej na mapę. Szkielet (waypointy+numery) na fazach pośrednich,
    // realna geometria (debugGeometry) na końcu.

    /** Szkielet: LineString kolejnych waypointów + ponumerowane Pointy (props idx). */
    private void debugSkeleton(String phase, List<double[]> route) {
        if (!debugGeoJson || route == null || route.isEmpty()) return;
        StringBuilder sb = new StringBuilder(route.size() * 48);
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[");
        // 1) LineString trasy
        sb.append("{\"type\":\"Feature\",\"properties\":{\"phase\":\"").append(phase)
                .append("\",\"kind\":\"skeleton\"},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[");
        appendCoords(sb, route);
        sb.append("]}}");
        // 2) ponumerowane Pointy
        for (int i = 0; i < route.size(); i++) {
            double[] p = route.get(i);
            sb.append(",{\"type\":\"Feature\",\"properties\":{\"idx\":").append(i)
                    .append(",\"phase\":\"").append(phase).append("\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[");
            appendPt(sb, p);
            sb.append("]}}");
        }
        sb.append("]}");
        System.out.println("dupa");
       // log.warn("GEOJSON-DEBUG [{}] n={}: {}", new Object[]{phase, route.size(), sb});
        System.out.println("pupa");
    }

    /** Realna geometria: pojedynczy LineString (np. finalna trasa po BRouterze). */
    void debugGeometry(String phase, List<double[]> geometry) {
        debugGeometry(phase, geometry, null, -1);
    }

    void debugGeometry(String phase, List<double[]> geometry, List<double[]> waypoints) {
        debugGeometry(phase, geometry, waypoints, -1);
    }

    /** Realna geometria (LineString) + opcjonalnie ponumerowane waypointy (Pointy z idx) NA tej trasie.
     *  realKm = autorytatywny dystans (BRouter/cache); jeśli ≤0 → ROUTE-STATS liczy haversine z geometrii. */
    void debugGeometry(String phase, List<double[]> geometry, List<double[]> waypoints, double realKm) {
        if (!debugGeoJson || geometry == null || geometry.size() < 2) return;
        StringBuilder sb = new StringBuilder(geometry.size() * 24);
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{\"phase\":\"")
                .append(phase).append("\",\"kind\":\"geometry\"},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[");
        appendCoords(sb, geometry);
        sb.append("]}}");
        if (waypoints != null) {
            for (int i = 0; i < waypoints.size(); i++) {
                sb.append(",{\"type\":\"Feature\",\"properties\":{\"idx\":").append(i)
                        .append(",\"phase\":\"").append(phase).append("\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[");
                appendPt(sb, waypoints.get(i));
                sb.append("]}}");
            }
        }
        sb.append("]}");
        System.out.println("dupa");
//        log.info("GEOJSON-DEBUG [{}] pts={} wps={}: {}",
//                new Object[]{phase, geometry.size(), waypoints == null ? 0 : waypoints.size(), sb});
        debugStats(phase, geometry, waypoints, realKm);
        if (waypoints != null) debugSkeleton(phase, waypoints); // RUNDA 24: skeleton ZAWSZE obok debugGeometry (życzenie usera)
        System.out.println("pupa");
    }

    /** ADMIN DEBUG: zwięzła linia metryk trasy dla fazy — dystans/wznios/effort vs budżet + gminy per kategoria. */
    private void debugStats(String phase, List<double[]> geometry, List<double[]> waypoints, double realKm) {
        if (geometry == null || geometry.size() < 2) return;
        int wps = waypoints == null ? 0 : waypoints.size();
        double km = realKm; // autorytatywny (BRouter/cache) — spójny z effortem algorytmu
        if (km <= 0) {       // fallback: haversine z geometrii (gdy brak realKm)
            km = 0;
            for (int i = 0; i < geometry.size() - 1; i++) {
                km += velomarker.service.planning.WaypointSelector.haversineKm(geometry.get(i), geometry.get(i + 1));
            }
        }
        double climb = metrics.climbM(geometry);
        double effort = km + params.alphaKmPerMeter() * climb;
        String budgetStr = debugBudget > 0
                ? Math.round(effort) + "/" + Math.round(debugBudget) + " (" + Math.round(effort * 100.0 / debugBudget) + "%)"
                : "n/a";
        String areasStr = "n/a";
        int total = 0, deep = 0;
        String noDeepStr = "";
        if (debugGminaIndex != null) {
            Map<Integer, Integer> visits = countVisitsPerArea(geometry, debugGminaIndex);
            total = visits.size(); // pełny wielokąt (findGminaForPoint) — DOTKNIĘTE, w tym muśnięte (<200m)
            Map<String, Integer> byCat = new java.util.TreeMap<>();
            for (Integer id : visits.keySet()) {
                byCat.merge(debugAreaCat != null ? debugAreaCat.getOrDefault(id, "?") : "?", 1, Integer::sum);
            }
            areasStr = byCat.toString();
            // RUNDA 61: drugi licznik = ≥200m kredyt (bufor −200, visitedAreaIds) — uczciwy. + lista gmin pokrytych
            // ≥200m BEZ głębokiego wp (findCreditedGminaForPoint) = zaliczonych tylko tranzytem/muśnięciem, bez kotwicy.
            Set<Integer> deepCov = debugGminaIndex.visitedAreaIds(geometry);
            deep = deepCov.size();
            if (waypoints != null) {
                Set<Integer> wpDeep = new HashSet<>();
                for (double[] wp : waypoints) {
                    UnvisitedArea a = debugGminaIndex.findCreditedGminaForPoint(wp[0], wp[1]);
                    if (a != null) wpDeep.add(a.areaId());
                }
                Map<Integer, String> idName = new HashMap<>();
                for (double[] p : geometry) {
                    UnvisitedArea a = debugGminaIndex.findGminaForPoint(p[0], p[1]);
                    if (a != null) idName.putIfAbsent(a.areaId(), a.name());
                }
                List<String> noDeep = new ArrayList<>();
                for (int gid : deepCov) if (!wpDeep.contains(gid)) noDeep.add(idName.getOrDefault(gid, "id" + gid));
                noDeep.sort(null);
                noDeepStr = " | bez-głębokiego-wp(≥200m, " + noDeep.size() + "): " + noDeep;
            }
        }
        // RUNDA 40 (A): gminy z >1 wp (łamanie inwariantu 1 wp/gmina). Mapuj każdy wp → gmina (findGminaForPoint), tally.
        String dupStr = "n/a";
        if (waypoints != null && debugGminaIndex != null) {
            Map<Integer, Integer> wpPerArea = new HashMap<>();
            Map<Integer, String> areaName = new HashMap<>();
            for (double[] wp : waypoints) {
                UnvisitedArea a = debugGminaIndex.findGminaForPoint(wp[0], wp[1]);
                if (a == null) continue;
                wpPerArea.merge(a.areaId(), 1, Integer::sum);
                areaName.putIfAbsent(a.areaId(), a.name());
            }
            List<String> dups = new ArrayList<>();
            for (Map.Entry<Integer, Integer> e : wpPerArea.entrySet())
                if (e.getValue() > 1) dups.add(areaName.get(e.getKey()) + "(" + e.getValue() + ")");
            dups.sort(null);
            dupStr = dups.size() + " " + dups;
        }
        log.info("ROUTE-STATS [{}]: dist={} km, climb={} m, effort={} budżetu, wps={}, gminy={} (≥200m: {}) {} | dup-wp={}{}",
                new Object[]{phase, Math.round(km), Math.round(climb), budgetStr, wps, total, deep, areasStr, dupStr, noDeepStr});
    }

    private static void appendCoords(StringBuilder sb, List<double[]> pts) {
        for (int i = 0; i < pts.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('[');
            appendPt(sb, pts.get(i));
            sb.append(']');
        }
    }

    private static void appendPt(StringBuilder sb, double[] p) {
        sb.append(String.format(java.util.Locale.ROOT, "%.6f,%.6f", p[0], p[1]));
    }












    // ── Helpers ────────────────────────────────────────────────────────────────────────





}
