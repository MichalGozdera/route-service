package velomarker.service.planning.coverage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import velomarker.entity.planning.UnvisitedArea;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * FAZA DOBIERANIA WSTĘPNEGO (init-grow) — osobna klasa odpowiedzialności (wzorzec {@link GrowNear}/{@link Anchorer}/
 * {@link SpurCutter}). Buduje seed od zera: score+order gmin → dorzucaj batchami ze scored do 110% budżetu (Hilbert
 * construction co batch, checkpoint-optimise + pomiar realEffort) → islands-fix (nieosiągalne wp). Mutuje
 * {@link SeedRoute} w miejscu; zwraca {@link IslandFixResult}. Obiekt per-wywołanie: kolaboratory z {@link SeedContext}
 * + parametry budżetu + stan przebiegu (effortFactor, instrumentacja) jako pola.
 */
final class InitGrowPhase {

    private static final Logger log = LoggerFactory.getLogger(InitGrowPhase.class);

    /** Wynik init-grow: effort po przebiegach + ile wp usunięto (wyspy) + ile entry-pointów ponowiono + czy
     *  wyczerpano scored (nie przerwano na 110% → finalize wie czy jest co dobierać). */
    record IslandFixResult(double realEffort, int pruned, int retried, boolean allCandidatesUsed) {}

    private final EdgeRouter edgeRouter;
    private final RouteMetrics metrics;
    private final GminaIndex gminaIndex;
    private final SeedOps ops;
    private final List<UnvisitedArea> pool;
    private final CandidatePicker picker;
    private final CoverageDebug debug;
    private final boolean debugGeoJson;
    private final SeedRoute seed;
    private final List<double[]> route;
    private final List<SeedSel> selected;
    private final List<double[]> anchors;
    private final List<double[]> baseline;

    private final double targetEffort, hiBand, growCeiling, effortMultiplier;
    private final long seedStartTs;

    // Stan przebiegu (L2 + instrumentacja wall-time).
    private double effortFactor;
    private int realCheckpoints;
    private long tRebuildNs, tRouteEffortNs, tEvalNs, tVisitsNs, tPruneNs;
    private final Map<Integer, Integer> entryAttempt = new HashMap<>();
    private final Map<String, UnvisitedArea> gminaPointCache = new HashMap<>();
    private Function<double[], UnvisitedArea> findGminaCached;

    InitGrowPhase(SeedContext ctx, SeedRoute seed, CandidatePicker picker, double targetEffort, double hiBand,
                  double growCeiling, double effortMultiplier, long seedStartTs) {
        this.edgeRouter = ctx.edgeRouter();
        this.metrics = ctx.metrics();
        this.gminaIndex = ctx.gminaIndex();
        this.ops = ctx.ops();
        this.pool = ctx.pool();
        this.picker = picker;
        this.debug = ctx.debug();
        this.debugGeoJson = ctx.debugGeoJson();
        this.seed = seed;
        this.route = seed.route();
        this.selected = seed.selected();
        this.anchors = seed.anchors();
        this.baseline = seed.baseline();
        this.targetEffort = targetEffort;
        this.hiBand = hiBand;
        this.growCeiling = growCeiling;
        this.effortMultiplier = effortMultiplier;
        this.seedStartTs = seedStartTs;
    }

    /** Init-grow (pick batchami do 110% + checkpoint-optimise) → islands-fix → log breakdown. */
    IslandFixResult run() {
        this.effortFactor = effortMultiplier;
        this.realCheckpoints = 0;
        this.findGminaCached = pt -> gminaPointCache.computeIfAbsent(
                String.format(java.util.Locale.ROOT, "%.6f,%.6f", pt[0], pt[1]),
                k -> gminaIndex.findGminaForPoint(pt[0], pt[1]));
        log.info("Coverage seed grow START: pool={} obszarów, target effort={} ({}/dzień × {}d)",
                new Object[]{pool.size(), Math.round(targetEffort),
                        Math.round(targetEffort / Math.max(1, route.size())), route.size()});
        edgeRouter.setReason("grow"); // v3.16: INIT-GROW = realne waypointy (księgowanie strzałów per powód)
        debug.skeleton("init", route); // ADMIN DEBUG: start+meta+anchory (przed dorzucaniem gmin)
        IslandFixResult r = seedInitGrow();
        logBreakdown();
        return r;
    }

    /**
     * INIT-GROW: dobieraj batchami przez {@link CandidatePicker} (dynamiczny ranking reward/dist/zgranie/dziury)
     * do pasma [1.0,1.1]×budżet + islands-fix. Dalsze dobieranie przejmuje FINALIZE. Zwraca effort + liczniki
     * (usunięte wyspy / ponowione entry / allCandidatesUsed = pula wyczerpana, nie przerwano na 110%).
     */
    private IslandFixResult seedInitGrow() {
        final int round = 0, BATCH = 20, PROGRESS_EVERY = 500;
        int lastProgressMilestone = 0, pruned = 0, retried = 0;
        double realEffort = 0;
        boolean poolExhausted = false;
        long roundStartTs = System.currentTimeMillis();
        int roundStartSelected = selected.size();
        log.info("Coverage seed round {} START: dotąd dodano {} obszarów, effort={}/{} ({}%), elapsed={}s",
                new Object[]{round, selected.size(), Math.round(realEffort), Math.round(targetEffort),
                        realEffort > 0 ? Math.round(realEffort * 100.0 / targetEffort) : 0,
                        (System.currentTimeMillis() - seedStartTs) / 1000});
        int batchCounter = 0;
        while (true) {
            // PRECYZJA: od 80% budżetu zmniejsz batch 20→6 (dokładny pomiar zanim dobijemy do 110%). Poniżej rośniemy szybko.
            boolean precise = metrics.haversineKm(route) * effortFactor >= targetEffort * 0.80;
            int batchSize = precise ? 6 : BATCH;
            CandidatePicker.PickResult pr = picker.pick(batchSize);   // ranking + cheapest-insert (jedna klasa dobierająca)
            if (pr.poolExhausted()) poolExhausted = true;
            if (pr.inserted() == 0) {                                  // pula kandydatów wyczerpana → koniec rundy
                poolExhausted = true;
                break;
            }
            long _tReb = System.nanoTime();
            ops.rebuildOrdered(seed);   // Hilbert construction — TYLKO init-grow (zwarty start dla 2-opt; nadpisuje insert-pos)
            tRebuildNs += System.nanoTime() - _tReb;
            batchCounter++;
            // Pomiar realEffort progowo (doReal) — co 5/10 batchy lub confirm-before-stop gdy est dobija do hiBand.
            int refineFreq = precise ? 5 : 10;
            boolean doRefine = batchCounter % refineFreq == 0;
            double hav = metrics.haversineKm(route);
            double estEffort = hav * effortFactor;
            boolean doReal = (batchCounter == 1) || doRefine || estEffort >= hiBand;
            if (doReal) {
                // CHECKPOINT-OPTIMISE: rozplącz (2-opt) PRZED pomiarem → effortFactor liczony na trasie 2-opt, nie
                // Hilbert (Hilbert ~10-20% dłuższy → bez tego init-grow kończył za wcześnie). Następny batch
                // rebuildOrdered i tak re-sortuje, więc to nie psuje construction.
                if (batchCounter > 1) CoverageLocalSearch.optimize(route);
                hav = metrics.haversineKm(route);
                long _tRE = System.nanoTime();
                realEffort = metrics.effortViaCache(route);
                tRouteEffortNs += System.nanoTime() - _tRE;
                realCheckpoints++;
                if (hav > 1) effortFactor = realEffort / hav;   // rekalibruj jeden factor (km+wznios+detour razem)
                if (debugGeoJson) debug.geometry("round" + round + "-batch" + batchCounter + "-real",
                        metrics.realGeometry(route), route, metrics.realKm(route));
            } else {
                realEffort = estEffort;
            }
            debug.skeleton("round" + round + "-batch" + batchCounter, route);
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
            if (realEffort >= hiBand) { // stop na 105% (NIE 110%) — kończymy w paśmie, finalize prawie nie peeluje (mniej dziur z cięcia)
                log.info("Coverage INIT-GROW: osiągnięto {}% (≥105%) → stop rundy 0",
                        Math.round(realEffort * 100.0 / targetEffort));
                break;
            }
        }
        long roundDurMs = System.currentTimeMillis() - roundStartTs;
        log.info("Coverage seed round {} END: dodano {} obszarów w tej rundzie ({} → {}), trwało {}s",
                new Object[]{round, selected.size() - roundStartSelected, roundStartSelected, selected.size(), roundDurMs / 1000});
        // ISLANDS-FIX (max 3 przebiegi): TYLKO wyspy/nieosiągalne. Cięciem ogonków zajmuje się tailPrune w finalize.
        IslandFixResult islandFix = fixIslands();
        realEffort = islandFix.realEffort();
        pruned += islandFix.pruned();
        retried += islandFix.retried();
        debug.skeleton("round0-grown", route);
        if (debugGeoJson) debug.geometry("round0-grown-real", metrics.realGeometry(route), route, metrics.realKm(route));
        return new IslandFixResult(realEffort, pruned, retried, poolExhausted); // poolExhausted = nie przerwano na 110%
    }

    /**
     * ISLANDS-FIX (max 3 przebiegi): waypointy nieosiągalne BRouterem → ponów entry-point (MIC→centroid) albo usuń
     * jako wyspę (route in-place + 2-opt). Zwraca effort + liczniki.
     */
    private IslandFixResult fixIslands() {
        double realEffort = 0;
        int pruned = 0, retried = 0;
        for (int islPass = 0; islPass < 3; islPass++) {
            long _tEval = System.nanoTime();
            RouteMetrics.EvalResult ev = metrics.eval(route);
            tEvalNs += System.nanoTime() - _tEval;
            realEffort = ev.effort();
            long _tVis = System.nanoTime();
            Map<Integer, Integer> visits = SeedBuilder.countVisitsPerArea(ev.geometry(), gminaIndex);
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
                boolean island = edgeRouter.failedEdges().contains(GeometryUtil.edgeKey(route.get(i - 1), cur))
                        || edgeRouter.failedEdges().contains(GeometryUtil.edgeKey(cur, route.get(i + 1)));
                if (gv != 0 && !island) continue;
                unreachable++;
                if (g == null) {
                    toRemove.add(cur);
                    continue;
                }
                int id = g.areaId();
                int att = entryAttempt.getOrDefault(id, 0);
                double[] alt = null;
                if (att == 0) {
                    alt = gminaIndex.deepestInteriorPoint(g.areaId());           // próba 0: MIC (najgłębszy; fallback centroid)
                    if (alt == null) alt = new double[]{g.lng(), g.lat()};
                } else if (att == 1) {
                    alt = new double[]{g.lng(), g.lat()};                        // próba 1: centroid
                }
                if (alt != null && (alt[0] != cur[0] || alt[1] != cur[1])) {
                    route.set(i, alt);                                       // route in-place (bez rebuildOrdered)
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
            route.removeIf(toRemove::contains);                 // route in-place — bez rebuildOrdered
            pruned += toRemove.size();
            CoverageLocalSearch.optimize(route);                // rozplącz po usunięciu wysp (sorted init-grow → 2-opt)
            realEffort = metrics.effortViaCache(route);
            log.info("Coverage seed islands pass {}: removed={}, unreachable={}, retried={} entry-points (total)",
                    new Object[]{islPass, toRemove.size(), unreachable, retried});
        }
        return new IslandFixResult(realEffort, pruned, retried, false);   // fixIslands nie dotyczy kandydatów
    }

    /** PHASE BREAKDOWN init-grow (wall-time single-thread) + L2 (realCheckpoints, effortFactor). */
    private void logBreakdown() {
        long wallS = (System.currentTimeMillis() - seedStartTs) / 1000;
        log.info("Coverage INIT-GROW BREAKDOWN (wall={}s): routeEffort(BRouter)={}s | rebuild={}s | eval={}s | countVisits={}s | prune={}s | realCheckpoints={} effortFactor={}",
                new Object[]{wallS, tRouteEffortNs / 1_000_000_000L, tRebuildNs / 1_000_000_000L,
                        tEvalNs / 1_000_000_000L, tVisitsNs / 1_000_000_000L, tPruneNs / 1_000_000_000L,
                        realCheckpoints, String.format(java.util.Locale.ROOT, "%.3f", effortFactor)});
    }
}
