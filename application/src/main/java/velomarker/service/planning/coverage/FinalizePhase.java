package velomarker.service.planning.coverage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import velomarker.entity.planning.UnvisitedArea;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * OSTATNIA FAZA seeda (FINALIZE) — osobna klasa odpowiedzialności (wzorzec {@link GrowNear}/{@link Anchorer}/
 * {@link SpurCutter}). Najpierw OBOWIĄZKOWY przebieg refine→anchor→refine→cut (zakotwicz surowy init-grow), POTEM
 * cykl budżetowy (≤5): zmierz → dobierz/utnij proporcjonalnie (reward-aware) → refine→anchor→refine→cut, aż pasmo
 * [95,105]%; na końcu domknij otoczone dziury. Deleguje WPROST do {@link Anchorer}/{@link SpurCutter}/{@link GrowNear}.
 * Mutuje {@link SeedRoute} w miejscu; zwraca {@link FinalizeResult}.
 */
final class FinalizePhase {

    private static final Logger log = LoggerFactory.getLogger(FinalizePhase.class);

    /** Score-sentinel wstawek ENCLOSED — peel (sort reward/detour ASC) nigdy ich nie rusza: otoczona dziura w środku
     *  blobu jest gorsza niż przestrzał budżetu (decyzja usera 2026-06-12). */
    static final double ENCLOSED_PROTECTED_SCORE = Double.MAX_VALUE;

    /** Wynik finalize: effort + ile dobrano (grow) / ucięto (trim) / domknięto dziur (enclosed). */
    record FinalizeResult(double realEffort, int grown, int trimmed, int enclosed) {}

    /** Kandydat do ucięcia: wp + klucz reward/koszt-objazdu (niski = zły deal = tnij pierwszy). */
    private record DealCand(SeedSel s, double key) {}

    /** Wynik peelingu: nowy effort + ile wp ucięto. */
    private record PeelResult(double realEffort, int peeled) {}

    private final SeedContext ctx;
    private final EdgeRouter edgeRouter;
    private final RouteMetrics metrics;
    private final GminaIndex gminaIndex;
    private final HilbertOrdering ordering;
    private final List<UnvisitedArea> pool;
    private final java.util.Map<String, Double> rewards;
    private final CoverageDebug debug;
    private final boolean debugGeoJson;
    private final SeedRoute seed;
    private final List<double[]> route;
    private final List<SeedSel> selected;
    private final List<double[]> baseline;

    private final double targetEffort, hiBand, growCeiling;
    private double realEffort;
    private boolean allCandidatesUsed;

    FinalizePhase(SeedContext ctx, SeedRoute seed, double targetEffort, double hiBand, double growCeiling,
                  double realEffort, boolean allCandidatesUsed) {
        this.ctx = ctx;
        this.edgeRouter = ctx.edgeRouter();
        this.metrics = ctx.metrics();
        this.gminaIndex = ctx.gminaIndex();
        this.ordering = ctx.ordering();
        this.pool = ctx.pool();
        this.rewards = ctx.rewards();
        this.debug = ctx.debug();
        this.debugGeoJson = ctx.debugGeoJson();
        this.seed = seed;
        this.route = seed.route();
        this.selected = seed.selected();
        this.baseline = seed.baseline();
        this.targetEffort = targetEffort;
        this.hiBand = hiBand;
        this.growCeiling = growCeiling;
        this.realEffort = realEffort;
        this.allCandidatesUsed = allCandidatesUsed;
    }

    /**
     * Najpierw OBOWIĄZKOWY przebieg refine→anchor→refine→cut (zakotwicz surowy init-grow, NIEZALEŻNIE od budżetu),
     * POTEM cykl budżetowy (≤5). Na końcu domknij otoczone dziury + finalny untangle.
     */
    FinalizeResult run() {
        final double STALL_EPS = 1.0;
        int grown = 0, trimmed = 0, enclosed = 0;
        // OBOWIĄZKOWY pierwszy przebieg (NIEZALEŻNIE od budżetu): init-grow zostawił SUROWE wp (sorted, bez 2-opt/
        // anchor/cut) → zakotwicz na wjazdach + przytnij ogonki ZANIM zmierzymy budżet (inaczej eFrac na surowej
        // trasie = śmieć → zła decyzja; a eFrac w paśmie → break zwróciłby trasę niezakotwiczoną).
        refine("fin-init-pre-anchor");
        new Anchorer(ctx, seed, "fin-init").run();
        refine("fin-init-pre-cut");                  // refine PO anchor bo SpurCutter zakłada rozplątaną trasę
        realEffort = new SpurCutter(ctx, seed, targetEffort, 8, "fin-init-cut").run();   // 8-pass (do skutku)
        if (debugGeoJson) {
            debug.geometry("fin-init-real", metrics.realGeometry(route), route, metrics.realKm(route));
            debug.logShots("fin-init");
        }
        for (int cycle = 0; cycle < 5; cycle++) {
            double before = realEffort;
            double eFrac = realEffort / targetEffort;
            if (eFrac >= 0.95 && eFrac <= 1.05) {
                log.info("Coverage FINALIZE cycle {}: {}% → KONIEC (w paśmie)", cycle, Math.round(eFrac * 100));
                break;
            }
            int delta = 0;
            if (eFrac > 1.05) {
                int g = metrics.eval(route).visited().size();
                int removeN = Math.max(1, (int) Math.round(g * (eFrac - 1.0) / eFrac));
                PeelResult pr = peelToCeiling(removeN, "fin" + cycle);
                realEffort = pr.realEffort();
                trimmed += pr.peeled();
                delta = -pr.peeled();
            } else {
                if (allCandidatesUsed) {
                    log.info("Coverage FINALIZE cycle {}: {}% <95%, wszystkie kandydaci dobrani → KONIEC (akceptuj)",
                            cycle, Math.round(eFrac * 100));
                    break;
                }
                int g = metrics.eval(route).visited().size();
                int additional = (int) Math.round(g * (1.0 - eFrac) / Math.max(0.05, eFrac));
                if (additional > 0) {
                    edgeRouter.setReason("grow");
                    GrowNear.GrowNearResult gr = new GrowNear(ctx, seed, growCeiling,
                            Math.min(additional, 16), additional + 1, additional).run();
                    delta = gr.inserted();
                    grown += delta;
                    realEffort = gr.effort();
                }
                if (delta == 0) allCandidatesUsed = true; // grow nic nie znalazł → następny under-cykl wyjdzie
            }
            // 2× OPTYMALIZACJA: refine → anchor → refine → cut (SpurCutter pętli rundy aż cut==0).
            refine("fin" + cycle + "-pre-anchor");
            new Anchorer(ctx, seed, "fin" + cycle).run();
            refine("fin" + cycle + "-pre-cut");
            realEffort = new SpurCutter(ctx, seed, targetEffort, 3, "fin" + cycle + "-cut").run();   // 3-pass
            if (debugGeoJson) {
                debug.geometry("fin" + cycle + "-real", metrics.realGeometry(route), route, metrics.realKm(route));
                debug.logShots("fin" + cycle);
            }
            if (Math.abs(realEffort - before) < STALL_EPS && delta == 0) {
                log.info("Coverage FINALIZE cycle {}: bez postępu (Δeffort≈0, nic dobrane/ucięte) → STOP", cycle);
                break;
            }
        }
        // DOMKNIJ otoczone dziury RAZ (najtańsze — ślad już je opływa; overshoot >105% akceptowany, dziura gorsza).
        enclosed = closeEnclosedHoles();
        if (enclosed > 0) {
            refine("fin-holeclose-pre-anchor");
            new Anchorer(ctx, seed, "fin-holeclose").run();
            refine("fin-holeclose-pre-cut");
            realEffort = new SpurCutter(ctx, seed, targetEffort, 3, "fin-holeclose-cut").run();
        }
        refine("seed");                      // FINALNY untangle — kontrakt: trasa rozplątana dla plan()
        realEffort = metrics.effortViaCache(route);
        return new FinalizeResult(realEffort, grown, trimmed, enclosed);
    }

    /** FINALNY refine kolejności (pełny or-opt + 2-opt do zbieżności). Loguje Δkm + debug-skeleton PRZED/PO. */
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

    /** Tnij FRINGE (nie-otoczone, reward/detour ASC) porcjami aż ≤hiBand lub brak bezpiecznych; gdy fringe pusty
     *  (pełne pokrycie) → tnij OBWÓD (borderAreaIds, chroń reward-P95). Zwraca {effort, ucięte}. */
    private PeelResult peelToCeiling(int removeN, String phase) {
        final double PROGRESS_EPS = 1.0;
        int peeled = 0;
        for (int peelK = 1; peelK <= 8 && realEffort > hiBand; peelK++) {
            long peelCallsStart = edgeRouter.realCalls();
            double before = realEffort;
            RouteMetrics.EvalResult evt = metrics.eval(route);
            Set<Integer> visited = evt.visited();
            int gmint = visited.size();
            double eFracT = realEffort / targetEffort;
            int rn = Math.max(1, (int) Math.round(gmint * (eFracT - 1.0) / eFracT));
            boolean border = false;
            List<DealCand> cands = collectDealCandidates(visited, false);          // fringe (nie-otoczone)
            if (cands.isEmpty()) { cands = collectDealCandidates(visited, true); border = true; } // pełne pokrycie → OBWÓD
            if (cands.isEmpty()) break;
            cands.sort(Comparator.comparingDouble(DealCand::key));
            edgeRouter.setReason("pomiar");
            int removed = 0;
            for (DealCand dc : cands) {
                if (removed >= rn) break;
                int wpIdx = GeometryUtil.identityIndexOf(route, dc.s().point());
                if (wpIdx <= 0 || wpIdx >= route.size() - 1) continue;
                route.remove(wpIdx);            // W MIEJSCU (bez rebuildOrdered) → prev→next wprost
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
            if (realEffort >= before - PROGRESS_EPS) break;   // anty-spin: ucięto kolateral, effort nie schudł
        }
        return new PeelResult(realEffort, peeled);
    }

    /** Kandydaci do cięcia: nie-protected wp. {@code border=false}: FRINGE (nie-otoczone — bez dziur).
     *  {@code border=true}: OBWÓD (borderAreaIds), chroń reward≥P95 (cenne stolice na rim). Klucz reward/detour. */
    private List<DealCand> collectDealCandidates(Set<Integer> visited, boolean border) {
        final double DETOUR_EPS = 0.05;
        Set<Integer> rim = border ? gminaIndex.borderAreaIds(visited) : null;
        double p95 = border ? rewardP95(visited) : Double.MAX_VALUE;
        List<DealCand> cands = new ArrayList<>();
        for (SeedSel s : selected) {
            if (s.score() >= ENCLOSED_PROTECTED_SCORE) continue;
            int aid = s.area().areaId();
            double rw = rewards.getOrDefault(RewardModel.categoryKey(s.area()), 1.0);
            if (border) {
                if (!rim.contains(aid) || rw >= p95) continue;            // tylko obwód, chroń cenne stolice
            } else {
                if (gminaIndex.allNeighborsVisited(aid, visited)) continue; // otoczona śladem → dziura
            }
            int wpIdx = GeometryUtil.identityIndexOf(route, s.point());
            if (wpIdx <= 0 || wpIdx >= route.size() - 1) continue;
            double[] prev = route.get(wpIdx - 1), cur = route.get(wpIdx), next = route.get(wpIdx + 1);
            double eIn = edgeRouter.edge(prev, cur).distanceKm();
            double eOut = edgeRouter.edge(cur, next).distanceKm();
            double detour = Math.max(0.0, eIn + eOut
                    - velomarker.service.planning.WaypointSelector.haversineKm(prev, next));
            cands.add(new DealCand(s, rw / Math.max(DETOUR_EPS, detour)));
        }
        return cands;
    }

    /** 95-percentyl reward wśród zaliczonych (chroni najcenniejsze gminy na obwodzie przed cięciem). */
    private double rewardP95(Set<Integer> visited) {
        List<Double> rs = new ArrayList<>();
        for (SeedSel s : selected) {
            if (visited.contains(s.area().areaId())) rs.add(rewards.getOrDefault(RewardModel.categoryKey(s.area()), 1.0));
        }
        if (rs.isEmpty()) return Double.MAX_VALUE;
        rs.sort(null);
        return rs.get((int) Math.floor(0.95 * (rs.size() - 1)));
    }

    /** Domknij gminy OTOCZONE zaliczonymi (donut-holes) — wp w najgłębszym punkcie, cheapest-insert (BEZ rebuildOrdered).
     *  Wstawki chronione {@link #ENCLOSED_PROTECTED_SCORE}. Zwraca liczbę dopiętych. */
    private int closeEnclosedHoles() {
        Set<Integer> visited = gminaIndex.visitedAreaIds(metrics.realGeometry(route));
        Set<Integer> enclosed = gminaIndex.enclosedUnvisited(visited);
        if (enclosed.isEmpty()) return 0;
        int added = 0;
        for (UnvisitedArea a : pool) {
            if (!enclosed.contains(a.areaId())) continue;
            double[] deep = gminaIndex.deepestInteriorPoint(a.areaId());
            if (deep == null) continue;
            selected.add(new SeedSel(a, deep, ordering.orderKey(deep),
                    ENCLOSED_PROTECTED_SCORE, GeometryUtil.minDistToBaselineKm(deep, baseline)));
            route.add(GeometryUtil.cheapestInsertPos(route, deep), deep);  // cheapest-insert, BEZ Hilbert-resetu
            added++;
        }
        log.info("Coverage FINALIZE holeclose: {} otoczonych dziur (cheapest-insert + anchor + cut)", added);
        return added;
    }
}
