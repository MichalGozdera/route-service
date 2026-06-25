package velomarker.service.planning.coverage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import velomarker.entity.planning.UnvisitedArea;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GROW-NEAR (BATCH-GROW) — dobieranie BLISKICH nieodwiedzonych gmin (po distToRoute = zwartość blobu) porcjami do
 * {@code growTarget}: {wstaw porcję cheapest-insertion → 2opt → pomiar real co checkpoint lub gdy estymata blisko
 * celu → UNDO porcji gdy marginal 3× droższy od średniej (złapane dalekie gminy)}. Po pętli: usuń wstawki-wyspy +
 * kredyt-verify. Obiekt per-wywołanie: kolaboratory (z {@link SeedContext}) + stan przebiegu jako pola.
 */
final class GrowNear {

    private static final Logger log = LoggerFactory.getLogger(GrowNear.class);
    /** Promień „blisko trasy" dla kandydatów (km). */
    private static final double GROW_NEAR_R_KM = 25.0;

    /** Wynik GROW-NEAR: realny effort po wstawkach + ile gmin dorzucono. */
    record GrowNearResult(double effort, int inserted) {}

    /** Kandydat dobierania: gmina + dystans do trasy + score (reward×(1+enclosed)/dist). */
    private record NearCand(UnvisitedArea area, double dist, double score) {}

    private final EdgeRouter edgeRouter;
    private final RouteMetrics metrics;
    private final HilbertOrdering ordering;
    private final GminaIndex gminaIndex;
    private final List<UnvisitedArea> pool;
    private final Map<String, Double> rewards;
    private final SeedOps ops;
    private final SeedRoute seed;
    private final List<double[]> route;
    private final List<SeedSel> selected;
    private final List<double[]> baseline;
    private final double growTarget;
    private final int batch;
    private final int checkpointEvery;
    private final int maxInserts;
    private final long callsStart;
    private final List<SeedSel> allInserts = new ArrayList<>();
    private final Set<Integer> myInsertAreas = new HashSet<>();
    private final Set<Integer> retriedCentroid = new HashSet<>();
    private List<NearCand> cands;
    private Set<Integer> visited;
    private List<double[]> geomRef;
    private double effort, effortFactor, hav, lastMeasured;
    private int inserted, ci, batchCount, checkpoints, refreshes, sinceMeasure;

    GrowNear(SeedContext ctx, SeedRoute seed, double growTarget, int batchSize, int checkpointEvery, int maxInserts) {
        this.edgeRouter = ctx.edgeRouter();
        this.metrics = ctx.metrics();
        this.ordering = ctx.ordering();
        this.gminaIndex = ctx.gminaIndex();
        this.pool = ctx.pool();
        this.rewards = ctx.rewards();
        this.ops = ctx.ops();
        this.seed = seed;
        this.route = seed.route();
        this.selected = seed.selected();
        this.baseline = seed.baseline();
        this.growTarget = growTarget;
        this.batch = batchSize;
        this.checkpointEvery = checkpointEvery;
        this.maxInserts = maxInserts;
        this.callsStart = edgeRouter.realCalls();
    }

    /** Pomiar wejściowy → pętla dobierania porcjami → finalizacja (2opt + wyspy + kredyt-verify). */
    GrowNearResult run() {
        RouteMetrics.EvalResult ev = metrics.eval(route);
        effort = ev.effort();
        if (effort >= growTarget) return new GrowNearResult(effort, 0);
        visited = ev.visited();
        geomRef = ev.geometry();
        cands = nearCandidates(visited, myInsertAreas, route);
        hav = metrics.haversineKm(route);
        effortFactor = hav > 1 ? effort / hav : 2.0; // estymator L2: effort ≈ haversine × factor (rekalibrowany)
        lastMeasured = effort;
        while (true) {
            if (maxInserts > 0 && inserted >= maxInserts) break; // limit dobierania wg proporcji
            List<SeedSel> batchSels = fillBatch();
            if (batchSels.isEmpty()) { if (refresh()) break; else continue; }
            if (processBatch(batchSels)) break;
        }
        return finish();
    }

    /** Wstaw porcję (≤batch) najbliższych nieodwiedzonych gmin metodą cheapest-insertion (bez tasowania trasy). */
    private List<SeedSel> fillBatch() {
        List<SeedSel> batchSels = new ArrayList<>();
        while (batchSels.size() < batch && (maxInserts <= 0 || inserted + batchSels.size() < maxInserts) && ci < cands.size()) {
            NearCand nc = cands.get(ci++);
            if (visited.contains(nc.area().areaId()) || myInsertAreas.contains(nc.area().areaId())) continue;
            double[] ep = gminaIndex.deepestInteriorPoint(nc.area().areaId()); // najgłębszy punkt gminy (MIC, fallback centroid)
            if (ep == null) continue;
            SeedSel sel = new SeedSel(nc.area(), ep, ordering.orderKey(ep), 0.0, GeometryUtil.minDistToBaselineKm(ep, baseline));
            selected.add(sel);
            batchSels.add(sel);
            allInserts.add(sel);
            myInsertAreas.add(nc.area().areaId());
            route.add(GeometryUtil.cheapestInsertPos(route, ep), ep); // wsuń tam, gdzie najmniej nadkłada
        }
        return batchSels;
    }

    /** Lista kandydatów wyczerpana: przelicz pokrycie i sąsiadów na AKTUALNEJ trasie (nowe segmenty mają swoich
     *  sąsiadów ≤25 km). Max 2 refreshe. Zwraca true gdy koniec wzrostu (cel/limit/brak kandydatów). */
    private boolean refresh() {
        if (refreshes >= 2) return true;
        refreshes++;
        RouteMetrics.EvalResult rev = metrics.eval(route);
        effort = rev.effort();
        visited = rev.visited();
        geomRef = rev.geometry();
        hav = metrics.haversineKm(route);
        if (hav > 1) effortFactor = effort / hav;
        lastMeasured = effort;
        sinceMeasure = 0;
        if (effort >= growTarget) return true;
        cands = nearCandidates(visited, myInsertAreas, route);
        ci = 0;
        return cands.isEmpty();
    }

    /** Po wstawieniu porcji: 2opt, estymata effortu; co checkpoint (lub blisko celu) realny pomiar + ew. UNDO porcji.
     *  Zwraca true gdy osiągnięto cel lub cofnięto za drogą porcję (stop dobierania). */
    private boolean processBatch(List<SeedSel> batchSels) {
        inserted += batchSels.size();
        sinceMeasure += batchSels.size();
        batchCount++;
        CoverageLocalSearch.optimize(route); // pełny 2opt co porcja — skraca trasę na bieżąco
        hav = metrics.haversineKm(route);
        double est = hav * effortFactor;
        boolean doReal = (batchCount % checkpointEvery == 0) || est >= growTarget;
        if (!doReal) { effort = est; return effort >= growTarget; }
        hav = metrics.haversineKm(route);
        effort = metrics.effortViaCache(route);
        checkpoints++;
        if (hav > 1) effortFactor = effort / hav;
        if (undoIfTooFar(batchSels)) return true;
        lastMeasured = effort;
        sinceMeasure = 0;
        return effort >= growTarget;
    }

    /** Blisko celu porcja 3× droższa od średniej = złapane DALEKIE gminy → cofnij ostatnią porcję i zakończ. */
    private boolean undoIfTooFar(List<SeedSel> batchSels) {
        if (!(lastMeasured > 0 && sinceMeasure > 0 && effort >= 0.9 * growTarget)) return false;
        double marginal = (effort - lastMeasured) / sinceMeasure;
        double ratio = effort / Math.max(1, selected.size());
        if (marginal <= 3 * ratio) return false;
        for (SeedSel s : batchSels) {
            selected.remove(s);
            route.remove((Object) s.point());
            allInserts.remove(s);
            myInsertAreas.remove(s.area().areaId());
            inserted--;
        }
        CoverageLocalSearch.optimize(route);
        effort = metrics.effortViaCache(route);
        log.info("Coverage BATCH-GROW undo porcji {}: marginal={}/gmine > 3xratio={} (dalekie gminy) -> stop na {}% growTargetu",
                new Object[]{batchCount, Math.round(marginal), Math.round(ratio), Math.round(effort * 100.0 / growTarget)});
        return true;
    }

    /** Finalizacja (raz): pełny 2opt → real → usuń wstawki-wyspy → kredyt-verify (max 3 rundy). */
    private GrowNearResult finish() {
        CoverageLocalSearch.optimize(route);
        effort = metrics.effortViaCache(route);
        int islands = removeFailedInserts();
        inserted -= islands;
        if (islands > 0) {
            CoverageLocalSearch.optimize(route);
            effort = metrics.effortViaCache(route);
        }
        for (int vr = 0; vr < 3; vr++) {
            visited = gminaIndex.visitedAreaIds(metrics.realGeometry(route));
            if (!verifyInsertCredit()) break;
            effort = metrics.effortViaCache(route);
        }
        int credited = 0;
        for (SeedSel s : selected) if (myInsertAreas.contains(s.area().areaId()) && s.score() == 0.0) credited++;
        log.info("Coverage BATCH-GROW: batches={}, inserted={}, islands={}, checkpoints={}, refreshes={}, factor={}, calls={}, effort -> {} ({}% growTarget)",
                new Object[]{batchCount, Math.min(inserted, credited), islands, checkpoints, refreshes,
                        String.format(java.util.Locale.ROOT, "%.2f", effortFactor),
                        edgeRouter.realCalls() - callsStart,
                        Math.round(effort), Math.round(effort * 100.0 / growTarget)});
        return new GrowNearResult(effort, Math.min(inserted, credited));
    }

    /** Kandydaci = nieodwiedzone gminy ≤{@value #GROW_NEAR_R_KM} km od trasy, sort wg score reward×(1+enclosed)/dist DESC. */
    private List<NearCand> nearCandidates(Set<Integer> visited, Set<Integer> skip, List<double[]> route) {
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

    /** Usuń wstawki, których noga wjazdowa/wyjazdowa padła na wyspie (BRouter-fail). Zwraca ile usunięto. */
    private int removeFailedInserts() {
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
            }
        }
        return islands;
    }

    /** Kredyt-verify (wp 206): wstawka która NIE kredytuje gminy ≥200m → retry centroid (1×), potem delete. Zwraca czy zmieniono. */
    private boolean verifyInsertCredit() {
        boolean changedAny = false;
        for (SeedSel s : new ArrayList<>(selected)) {
            int aid = s.area().areaId();
            if (!myInsertAreas.contains(aid) || s.score() != 0.0 || visited.contains(aid)) continue;
            if (retriedCentroid.add(aid)) {
                double[] centroid = new double[]{s.area().lng(), s.area().lat()};
                int idx = GeometryUtil.identityIndexOf(route, s.point());
                if (idx >= 0) route.set(idx, centroid);          // route in-place (bez rebuildOrdered)
                ops.swapEntry(selected, s.point(), centroid, baseline);
            } else {
                selected.remove(s);
                route.remove((Object) s.point());                // route in-place (identity)
                myInsertAreas.remove(aid);
            }
            changedAny = true;
        }
        if (changedAny) CoverageLocalSearch.optimize(route);     // rozplącz (bez Hilbert-resetu)
        return changedAny;
    }
}
