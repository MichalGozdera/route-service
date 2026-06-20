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
    /** Renderery ADMIN-DEBUG (GeoJSON/ROUTE-STATS/STRZAŁY) — gated debugGeoJson. */
    private final CoverageDebug debug;
    /** Wspólne operacje trasy (2-opt, przebudowa kolejności, swap entry) — dzielone przez klasy odpowiedzialności. */
    private final SeedOps ops;
    /** Wiązka kolaboratorów wstrzykiwana do klas odpowiedzialności (Anchorer/GrowNear/SpurCutter/Trimmer). */
    private final SeedContext ctx;

    /** Delegator dla orkiestratora (plan()) — render finalnej realnej geometrii po chunked BRouterze. */
    void debugGeometry(String phase, List<double[]> geometry, List<double[]> waypoints, double realKm) {
        debug.geometry(phase, geometry, waypoints, realKm);
    }
    /** Instrumentacja wall-time seeda (log-only). */
    private long tRebuildNs, tTwoOptNs, tRouteEffortNs, tEvalNs, tVisitsNs, tPruneNs;
    /** L2: jeden effort-factor (realEffort/Σhaversine), rekalibrowany na checkpoincie; ile realnych checkpointów; start seeda. */
    private double effortFactor;
    private int realCheckpoints;
    private long seedStartTs;

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
        this.debug = new CoverageDebug(debugGeoJson, edgeRouter, metrics, gminaIndex, params, debugBudget, debugAreaCat);
        this.ops = new SeedOps(ordering, metrics, debugGeoJson);
        this.ctx = new SeedContext(edgeRouter, metrics, gminaIndex, ordering, pool, rewards, debug, ops, debugGeoJson);
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
            if (debugGeoJson) debug.geometry("cycle" + cycle + "-entry-real",
                    metrics.realGeometry(route), route,
                    metrics.realKm(route));
            if (debugGeoJson) debug.logShots("cycle" + cycle + "-entry"); // RUNDA 24: STRZAŁY na start cyklu
            // RUNDA 24: ANCHOR-INTERSECTS — GŁÓWNY silnik pokrycia (raz na cykl). Reset wszystkich DOTYKANYCH gmin:
            //    wp na PIERWSZYM wejściu w rdzeń (≥200m) albo CENTROID (muśnięcie). Kończy 2opt. enclosedFill NIE tu —
            //    tylko na końcu seeda (holefill). Realny BRouter wchodzi dopiero w tailPruneJts (reroute przez nowe wp).
            anchorResetTouched(seed, "cycle" + cycle);
            if (debugGeoJson) debug.geometry("cycle" + cycle + "-anchor-real",
                    metrics.realGeometry(route), route,
                    metrics.realKm(route));
            if (debugGeoJson) debug.logShots("cycle" + cycle + "-anchor");
            // tailPrune — DOPIERO TERAZ BRouter (reroute przez nowe wp) + tnij ogonki DO SKUTKU (bez wewn. anchora)
            int prunePasses = cycle == 0 ? 8 : 3;
            realEffort = doubleCut(seed, // RUNDA 69: cut→2opt→reroute→cut (dla pewności)
                    targetEffort, prunePasses,
                    "cycle" + cycle + "-tailprune-real");
            if (debugGeoJson) debug.logShots("cycle" + cycle + "-cut");
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
                GrowNear.GrowNearResult gr = growNear(seed, hiBand,
                        Math.min(additional, 16), additional + 1, additional);
                added = gr.inserted();
                growNearAcc[0] += added;
                realEffort = gr.effort();
            }
            if (debugGeoJson) debug.logShots("cycle" + cycle + "-grow");
            if (debugGeoJson) debug.geometry("cycle" + cycle + "-grow-real",
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
        ops.rebuildOrdered(seed);
        log.info("Coverage HOLEFILL: {} enclosed → wp najgłębszy + anchor + 2opt + podwójne cięcie", enclosedHF.size());
        if (debugGeoJson) debug.geometry("holefill-enclosed-real",
                metrics.realGeometry(route), route,
                metrics.realKm(route));
        if (debugGeoJson) debug.logShots("holefill-enclosed");
        anchorResetTouched(seed, "holefill");
        if (debugGeoJson) debug.geometry("holefill-anchor-real",
                metrics.realGeometry(route), route,
                metrics.realKm(route));
        if (debugGeoJson) debug.logShots("holefill-anchor");
        realEffort = doubleCut(seed, targetEffort, 3, "holefill-tailprune-real");
        return new HoleFillResult(realEffort, enclosedAdded);
    }

    /**
     * FAZA TRIM seeda (gdy effort &gt;105%): peeling peryferii wg reward/koszt-objazdu (tnij tylko
     * NIE-otoczone — bez dziur), pełny 2-opt, realny BRouter co rundę; raz na rundę anchor + doubleCut;
     * gdy zjechało &lt;95% → reward-aware growNear. Mutuje route/selected; akumuluje ucięte w
     * {@code trimmedAcc[0]} i grow w {@code growNearAcc[0]}; zwraca nowy realEffort.
     */
    private double seedTrim(SeedRoute seed, double hiBand, double targetEffort, double realEffort,
                            int[] trimmedAcc, int[] growNearAcc) {
        Trimmer run = new Trimmer(ctx, findGminaCached, seed, hiBand, targetEffort, realEffort);
        double result = run.run();
        trimmedAcc[0] += run.trimmed();
        growNearAcc[0] += run.growNearAdded();
        return result;
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
        // Jeden effort-factor: realEffort / Σhaversine, rekalibrowany na checkpoincie. (DEM/climb-factor
        // usunięte — climbFactor wychodził ~8.6, czyli prosta-DEM nic nie wnosiła; jeden factor jest prostszy
        // i równie dobry, bo i tak służy tylko do pasma budżetu.) Init = EFFORT_MULTIPLIER (road×(1+climb·alpha)).
        this.effortFactor = EFFORT_MULTIPLIER;
        this.realCheckpoints = 0;
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
        this.seedStartTs = System.currentTimeMillis();
        int lastProgressMilestone = 0;
        log.info("Coverage seed grow START: pool={} obszarów, target effort={} ({}/dzień × {}d)",
                new Object[]{scored.size(), Math.round(targetEffort),
                        Math.round(targetEffort / Math.max(1, route.size())), route.size()});
        edgeRouter.setReason("grow"); // v3.16: INIT-GROW = realne waypointy (księgowanie strzałów per powód)
        debug.skeleton("init", route); // ADMIN DEBUG: start+meta+anchory (przed dorzucaniem gmin)
        // INIT-GROW (v3): JEDNA runda grow ze scored (baseline-score) do pasma + islands-fix.
        // Dalsze dobieranie przejmuje COMPACT-LOOP (grow-near po distToRoute = zwartość bloba) —
        // scored-tail po pierwszym prune dawał DALEKIE gminy (skoki 90%→128%) i błędne koło psuj-naprawiaj.
        IslandFixResult initGrow = seedInitGrow(seed, scored, targetEffort, hiBand, growCeiling, entryAttempt);
        realEffort = initGrow.realEffort();
        totalPruned += initGrow.pruned();
        totalRetried += initGrow.retried();

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
        debug.resetShots();
        if (debugGeoJson) debug.logShots("init-grow");
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
        if (debugGeoJson) debug.logShots("seed-final");
        int densified = growNearInserted + enclosedFilled; // grow-near + ENCLOSED-FILL
        debug.skeleton("seed", route); // ADMIN DEBUG: szkielet końca seeda (przed deep loop)
        if (debugGeoJson) debug.geometry("seed-real", metrics.realGeometry(route), route,
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





    /**
     * RUNDA 69 — PODWÓJNE CIĘCIE „dla pewności": cut → 2opt → reroute → cut. Po pierwszym cięciu 2-opt przestawia
     * kolejność wp (haversine), a drugie cięcie {@code tailPruneJts2(phase+"-recut")} liczy legGminas przez getEdge
     * NOWYCH par = nowy wariant trasy z BRoutera, i tnie wtórne ogonki. Oba cięcia mają RÓŻNE phase → każdy emituje
     * własny komplet logów (TAIL-PRUNE v6 / USUNIĘTE-OGONKI / SPUR-ANATOMIA) + debugGeometry. Zwraca effort po cut2.
     */
    private double doubleCut(SeedRoute seed, double targetEffort, int maxPasses, String phase) {
        List<double[]> route = seed.route(); List<SeedSel> selected = seed.selected(); List<double[]> anchors = seed.anchors(); List<double[]> baseline = seed.baseline(); double[] baseCum = seed.baseCum();
        tailPruneJts2(seed, targetEffort, maxPasses, phase);
        ops.twoOpt(route, phase + "-recut2opt");
        return tailPruneJts2(seed, targetEffort, maxPasses, phase + "-recut");
    }








    private double tailPruneJts2(SeedRoute seed, double targetEffort, int maxPasses, String debugPhase) {
        return new SpurCutter(ctx, findGminaCached, seed, targetEffort, maxPasses, debugPhase).run();
    }




    /* RUNDA 11: deleteSweepCoveredElse USUNIĘTE — unifikacja USUŃ→PRZESUŃ (anchor inline po deletach w passie)
       trzyma pokrycie na bieżąco, więc nie ma „chwilowych tranzytów" do dosprzątania osobnym sweepem. */







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
    private GrowNear.GrowNearResult growNear(SeedRoute seed, double growTarget, int batchSize, int checkpointEvery, int maxInserts) {
        return new GrowNear(ctx, seed, growTarget, batchSize, checkpointEvery, maxInserts).run();
    }



    /** Score-sentinel wstawek ENCLOSED-FILL — TRIM (sort score ASC, usuwa front) nigdy ich nie rusza:
     *  otoczona dziura w środku blobu jest gorsza niż przestrzał budżetu (decyzja usera 2026-06-12). */
    static final double ENCLOSED_PROTECTED_SCORE = Double.MAX_VALUE;
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
            ops.rebuildOrdered(seed);
            ops.twoOpt(route, "enclosedFill");
            // Kredyt-verify: otoczona dziura też może być nieosiągalna (most/wyspa) → retry centroid → usuń.
            Set<Integer> vis = gminaIndex.visitedAreaIds(
                    metrics.realGeometry(route));
            for (SeedSel s : new ArrayList<>(added)) {
                if (vis.contains(s.area().areaId())) continue;
                double[] c = gminaIndex.deepestInteriorPoint(s.area().areaId()); // RUNDA 51: najgłębszy (nie centroid)
                if (c == null) c = new double[]{s.area().lng(), s.area().lat()};
                ops.swapEntry(selected, s.point(), c, baseline);
            }
            ops.rebuildOrdered(seed);
            ops.twoOpt(route, "enclosedFill-centroid");
            vis = gminaIndex.visitedAreaIds(metrics.realGeometry(route));
            for (SeedSel s : new ArrayList<>(added)) {
                if (!vis.contains(s.area().areaId())) { selected.remove(s); added.remove(s); unreachable++; }
            }
            ops.rebuildOrdered(seed);
            ops.twoOpt(route, "enclosedFill-verify");
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
        new Anchorer(ctx, seed, debugPhase).run();
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








    // ── ADMIN DEBUG: GeoJSON snapshot trasy per faza (do paste-to-map w mapie rysowania) ──────────────
    // Guarded flagą debugGeoJson (default OFF). Loguje JEDNĄ linię GEOJSON-DEBUG [faza] = {FeatureCollection}.
    // User: breakpoint/kopiuj z konsoli → wklej na mapę. Szkielet (waypointy+numery) na fazach pośrednich,
    // realna geometria (debugGeometry) na końcu.



















    // ── Helpers ────────────────────────────────────────────────────────────────────────






    /** Wynik naprawy wysp: effort po przebiegach + ile wp usunięto (wyspy) + ile entry-pointów ponowiono. */
    private record IslandFixResult(double realEffort, int pruned, int retried) {}

    /** ISLANDS-FIX (max 3 przebiegi): waypointy nieosiągalne BRouterem → ponów entry-point (route-nearest→
     *  centroid) albo usuń jako wyspę. Zwraca effort + liczniki. Cięciem ogonków zajmuje się tailPrune. */
    private IslandFixResult fixIslands(SeedRoute seed, Map<Integer, Integer> entryAttempt) {
        List<double[]> route = seed.route(); List<SeedSel> selected = seed.selected(); List<double[]> anchorOnly = seed.anchorOnly(); List<double[]> anchors = seed.anchors(); List<double[]> baseline = seed.baseline(); double[] baseCum = seed.baseCum();
        double realEffort = 0; int pruned = 0, retried = 0;
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
                        ops.swapEntry(selected, cur, alt, baseline);
                        swapped = true;
                        retried++;
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
                pruned += toRemove.size();
                ops.rebuildOrdered(seed);
                ops.twoOpt(route, "init-grow-islands-prune");
                realEffort = metrics.effortViaCache(route);
                log.info("Coverage seed islands pass {}: removed={}, unreachable={}, retried={} entry-points (total)",
                        new Object[]{islPass, toRemove.size(), unreachable, retried});
            }
        return new IslandFixResult(realEffort, pruned, retried);
    }

    /** INIT-GROW (v3): jedna runda grow ze scored (baseline-score) do pasma [1.0,1.1]×budżet + islands-fix.
     *  Dalsze dobieranie przejmuje COMPACT-LOOP. Zwraca effort + liczniki (usunięte wyspy / ponowione entry). */
    private IslandFixResult seedInitGrow(SeedRoute seed, List<SeedSel> scored, double targetEffort,
                                        double hiBand, double growCeiling, Map<Integer, Integer> entryAttempt) {
        List<double[]> route = seed.route(); List<SeedSel> selected = seed.selected(); List<double[]> anchorOnly = seed.anchorOnly(); List<double[]> baseline = seed.baseline(); double[] baseCum = seed.baseCum();
        final int round = 0, BATCH = 20, CHECKPOINT_EVERY = 5, PROGRESS_EVERY = 500;
        int idx = 0, lastProgressMilestone = 0, pruned = 0, retried = 0;
        double realEffort = 0;
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
                ops.rebuildOrdered(seed);
                tRebuildNs += System.nanoTime() - _tReb;
                // 2-opt incremental po każdym batchu (window ±80 od końca trasy = lokalne ulepszenia),
                // PEŁNY twoOpt co 5 batchy = 100 obszarów = global cleanup. Dla FR 34746 obszarów / 5
                // batchy 20 = 347 pełnych twoOpt zamiast 1737 → ~5× szybsze (z 4h 16min do ~1-1.5h
                // łącznie z routeEffortViaCache). Dla małych scope (≤500 wp) twoOpt(route) i tak
                // wewnętrznie robi pełen skan (FULL_SCAN_MAX), więc bez regresji.
                batchCounter++;
                long _tTwo = System.nanoTime();
                if (precise || batchCounter % 5 == 0) {
                    ops.twoOpt(route, "init-grow-batch" + batchCounter);
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
                    if (debugGeoJson) debug.geometry("round" + round + "-batch" + batchCounter + "-real",
                            metrics.realGeometry(route), route,
                            metrics.realKm(route));
                } else {
                    realEffort = estEffort;
                }
                debug.skeleton("round" + round + "-batch" + batchCounter, route); // ADMIN DEBUG: szkielet (kolejność+numery) po każdym batchu
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
            IslandFixResult islandFix = fixIslands(seed, entryAttempt);
            realEffort = islandFix.realEffort();
            pruned += islandFix.pruned();
            retried += islandFix.retried();
            // ADMIN DEBUG: stan po INIT-GROW + islands (przed COMPACT-LOOP)
            debug.skeleton("round0-grown", route);
            if (debugGeoJson) debug.geometry("round0-grown-real",
                    metrics.realGeometry(route), route,
                    metrics.realKm(route));
        return new IslandFixResult(realEffort, pruned, retried);
    }
}
