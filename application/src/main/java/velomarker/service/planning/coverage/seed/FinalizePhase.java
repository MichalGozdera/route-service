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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

// Ostatnia faza seeda (FINALIZE): zgranie efortu do pasma budżetu + domknięcie dziur.
public final class FinalizePhase {

    private static final Logger log = LoggerFactory.getLogger(FinalizePhase.class);

    private final SeedContext ctx;
    private final EdgeRouter edgeRouter;
    private final RouteMetrics metrics;
    private final CoverageAreaIndex coverageAreaIndex;
    private final java.util.Map<String, Double> rewards;
    private final CoverageDebug debug;
    private final boolean debugGeoJson;
    private final CandidatePicker picker;
    private final SeedRoute seed;
    private final List<double[]> route;
    private final List<SeedSel> selected;

    private final double targetEffort, hiBand, growCeiling;
    private double realEffort;
    private boolean allCandidatesUsed;

    public FinalizePhase(SeedContext ctx, SeedRoute seed, CandidatePicker picker, double targetEffort, double hiBand,
                  double growCeiling, double realEffort, boolean allCandidatesUsed) {
        this.ctx = ctx;
        this.edgeRouter = ctx.edgeRouter();
        this.metrics = ctx.metrics();
        this.coverageAreaIndex = ctx.coverageAreaIndex();
        this.rewards = ctx.rewards();
        this.debug = ctx.debug();
        this.debugGeoJson = ctx.debugGeoJson();
        this.picker = picker;
        this.seed = seed;
        this.route = seed.route();
        this.selected = seed.selected();
        this.targetEffort = targetEffort;
        this.hiBand = hiBand;
        this.growCeiling = growCeiling;
        this.realEffort = realEffort;
        this.allCandidatesUsed = allCandidatesUsed;
    }

    public FinalizeResult run() {
        final double STALL_EPS = 1.0;
        int grown = 0, trimmed = 0;
        refine("fin-init-pre-anchor");
        new Anchorer(ctx, seed, "fin-init").run();
        refine("fin-init-pre-cut");
        realEffort = new SpurCutter(ctx, seed, targetEffort, 8, "fin-init-cut").run();
        debug.geometry("fin-init-real", metrics.realGeometry(route), route, metrics.realKm(route));
        if (debugGeoJson) debug.logShots("fin-init");
        for (int cycle = 0; cycle < 5; cycle++) {
            double before = realEffort;
            double eFrac = realEffort / targetEffort;
            if (eFrac >= 0.95 && eFrac <= 1.05) {
                log.info("Coverage FINALIZE cycle {}: {}% → KONIEC (w paśmie)", cycle, Math.round(eFrac * 100));
                break;
            }
            int delta = 0;
            if (eFrac > 1.05) {
                PeelResult pr = peelToCeiling("fin" + cycle);
                realEffort = pr.realEffort();
                trimmed += pr.peeled();
                delta = -pr.peeled();
            } else {
                if (allCandidatesUsed) {
                    log.info("Coverage FINALIZE cycle {}: {}% <95%, wszystkie kandydaci dobrani → KONIEC (akceptuj)",
                            cycle, Math.round(eFrac * 100));
                    break;
                }
                edgeRouter.setReason("grow");
                final int GROW_BATCH = 6;
                double frontier = selected.stream().mapToDouble(SeedSel::distBase).max().orElse(0.0);
                while (realEffort < hiBand) {
                    double gate = Math.max(CandidatePicker.JUMP_FLOOR_KM, CandidatePicker.JUMP_RATIO * frontier);
                    PickResult pr = picker.pick(GROW_BATCH, gate);
                    if (pr.inserted() == 0) {
                        if (pr.jumpAhead()) {
                            // Skok dystansu: realny pomiar przed przekroczeniem przerwy; zapas → dalej, brak → stop.
                            CoverageLocalSearch.optimize(route);
                            realEffort = metrics.effortViaCache(route);
                            if (realEffort >= hiBand) break;
                            frontier = pr.nextDistKm();
                            continue;
                        }
                        allCandidatesUsed = true; break;
                    }
                    for (int i = selected.size() - pr.inserted(); i < selected.size(); i++) {
                        double d = selected.get(i).distBase();
                        if (d > frontier) frontier = d;
                    }
                    delta += pr.inserted();
                    grown += pr.inserted();
                    CoverageLocalSearch.optimize(route);
                    realEffort = metrics.effortViaCache(route);
                    if (pr.poolExhausted()) { allCandidatesUsed = true; break; }
                }
                if (delta == 0) allCandidatesUsed = true;
            }
            boolean grew = delta > 0;
            // Klatka live „Dobieram obszary" (GROWING) — TYLKO gdy finalize-grow realnie dołożył przez pick().
            if (grew) debug.geometry("fin" + cycle + "-grow-real", metrics.realGeometry(route), route, metrics.realKm(route));
            refine("fin" + cycle + (grew ? "-pre-anchor" : "-pre-cut"));
            if (grew) {
                new Anchorer(ctx, seed, "fin" + cycle).run();
                refine("fin" + cycle + "-pre-cut");
            }
            realEffort = new SpurCutter(ctx, seed, targetEffort, 3, "fin" + cycle + "-cut").run();
            debug.geometry("fin" + cycle + "-real", metrics.realGeometry(route), route, metrics.realKm(route));
            if (debugGeoJson) debug.logShots("fin" + cycle);
            if (Math.abs(realEffort - before) < STALL_EPS && delta == 0) {
                log.info("Coverage FINALIZE cycle {}: bez postępu (Δeffort≈0, nic dobrane/ucięte) → STOP", cycle);
                break;
            }
        }
        realEffort = metrics.effortViaCache(route);
        return new FinalizeResult(realEffort, grown, trimmed);
    }

    private void refine(String phase) {
        if (route.size() < 4) return;
        if (debugGeoJson) debug.skeleton(phase + "-refine-before", route);
        double kmBefore = metrics.haversineKm(route);
        int wp = route.size();
        log.info("Coverage REFINE [{}]: start havKm={}, wps={}", new Object[]{phase, Math.round(kmBefore), wp});
        int moves = CoverageLocalSearch.optimize(route);
        double kmAfter = metrics.haversineKm(route);
        log.info("Coverage REFINE [{}]: havKm {}→{} (Δ{}), ruchów={}, wps={}",
                new Object[]{phase, Math.round(kmBefore), Math.round(kmAfter), Math.round(kmAfter - kmBefore), moves, wp});
        if (debugGeoJson) debug.skeleton(phase + "-refine-after", route);
    }

    private PeelResult peelToCeiling(String phase) {
        final double PROGRESS_EPS = 1.0;
        int peeled = 0;
        for (int peelK = 1; peelK <= 8 && realEffort > targetEffort; peelK++) {
            long peelCallsStart = edgeRouter.realCalls();
            double before = realEffort;
            EvalResult evt = metrics.eval(route);
            Set<Integer> visited = evt.visited();
            int gmint = visited.size();
            double eFracT = realEffort / targetEffort;
            int rn = Math.max(1, (int) Math.round(gmint * (eFracT - 1.0) / eFracT));
            boolean border = false;
            List<DealCand> cands = collectDealCandidates(visited, false);
            if (cands.isEmpty()) { cands = collectDealCandidates(visited, true); border = true; }
            if (cands.isEmpty()) break;
            cands.sort(Comparator.comparingDouble(DealCand::key));
            edgeRouter.setReason("pomiar");
            int removed = 0;
            for (DealCand dc : cands) {
                if (removed >= rn) break;
                int wpIdx = GeometryUtil.identityIndexOf(route, dc.s().point());
                if (wpIdx <= 0 || wpIdx >= route.size() - 1) continue;
                route.remove(wpIdx);
                selected.remove(dc.s());
                peeled++; removed++;
            }
            if (removed == 0) break;
            CoverageLocalSearch.optimize(route);
            realEffort = metrics.effortAccurate(route);
            log.info("Coverage FINALIZE peel {} k={}: {} gmin @ {}% → -{} ({}) → {}%, calls={}",
                    new Object[]{phase, peelK, gmint, Math.round(eFracT * 100), removed,
                            border ? "OBWÓD" : "fringe", Math.round(realEffort / targetEffort * 100),
                            edgeRouter.realCalls() - peelCallsStart});
            if (realEffort >= before - PROGRESS_EPS) break;
        }
        return new PeelResult(realEffort, peeled);
    }

    private List<DealCand> collectDealCandidates(Set<Integer> visited, boolean border) {
        final double DETOUR_EPS = 0.05;
        Set<Integer> union = new java.util.HashSet<>(visited);
        union.addAll(coverageAreaIndex.historicallyVisited());
        Set<Integer> rim = border ? coverageAreaIndex.borderAreaIds(union) : null;
        double p95 = border ? rewardP95(visited) : Double.MAX_VALUE;
        List<DealCand> cands = new ArrayList<>();
        for (SeedSel s : selected) {
            int aid = s.area().areaId();
            double rw = rewards.getOrDefault(RewardModel.categoryKey(s.area()), 1.0);
            if (border) {
                if (!rim.contains(aid) || rw >= p95) continue;
            } else {
                if (coverageAreaIndex.enclosedByVisited(aid, union)) continue;
            }
            int wpIdx = GeometryUtil.identityIndexOf(route, s.point());
            if (wpIdx <= 0 || wpIdx >= route.size() - 1) continue;
            double[] prev = route.get(wpIdx - 1), cur = route.get(wpIdx), next = route.get(wpIdx + 1);
            double eIn = edgeRouter.edge(prev, cur).distanceKm();
            double eOut = edgeRouter.edge(cur, next).distanceKm();
            double detour = Math.max(0.0, eIn + eOut
                    - velomarker.service.planning.WaypointSelector.haversineKm(prev, next));
            double adj = coverageAreaIndex.neighborVisitedFraction(aid, union);
            cands.add(new DealCand(s, (rw / Math.max(DETOUR_EPS, detour)) * (1.0 + adj)));
        }
        return cands;
    }

    private double rewardP95(Set<Integer> visited) {
        List<Double> rs = new ArrayList<>();
        for (SeedSel s : selected) {
            if (visited.contains(s.area().areaId())) rs.add(rewards.getOrDefault(RewardModel.categoryKey(s.area()), 1.0));
        }
        if (rs.isEmpty()) return Double.MAX_VALUE;
        rs.sort(null);
        return rs.get((int) Math.floor(0.95 * (rs.size() - 1)));
    }
}
