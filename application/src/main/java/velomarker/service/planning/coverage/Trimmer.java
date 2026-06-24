package velomarker.service.planning.coverage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * TRIM (gdy >105% budżetu) jako osobna klasa odpowiedzialności — jeden przebieg per wywołanie: peeling peryferii
 * (tnij porcjami najgorszy reward/koszt-objazdu z FRINGE, by nie robić dziur) → anchor → doubleCut → dobierz gdy
 * <95%. Orkiestruje pozostałe klasy: {@link Anchorer}, {@link SpurCutter} (przez własny doubleCut), {@link GrowNear}.
 */
final class Trimmer {

    private static final Logger log = LoggerFactory.getLogger(Trimmer.class);
    /** TRIM peeling: cap rund cięcia w jednej iteracji (proporcja zbiega, to tylko bezpiecznik). */
    private static final int TRIM_MAX_PEELS = 8;
    /** Anty-dzielenie-przez-0 dla kosztu-objazdu (km) — kolateral (detour≈0) → reward/EPS = ogromne = nie tnij. */
    private static final double TRIM_DETOUR_EPS = 0.05;
    /** Poniżej tego spadku effortu (km+α·m) runda uznana za „bez postępu" → stop (ucięto kolateral). */
    private static final double TRIM_PROGRESS_EPS = 1.0;

    /** Kandydat do ucięcia: wp + klucz = reward/koszt-objazdu (niski = zły deal = tnij pierwszy). */
    private record DealCand(SeedSel s, double key) {}

    private final SeedContext ctx;
    private final EdgeRouter edgeRouter;
    private final RouteMetrics metrics;
    private final GminaIndex gminaIndex;
    private final java.util.Map<String, Double> rewards;
    private final SeedOps ops;
    private final CoverageDebug debug;
    private final boolean debugGeoJson;
    private final SeedRoute seed;
    private final List<double[]> route;
    private final List<SeedSel> selected;
    private final double hiBand;
    private final double targetEffort;
    private double realEffort;
    private int trimmed;
    private int growNearAdded;

    Trimmer(SeedContext ctx, SeedRoute seed,
            double hiBand, double targetEffort, double realEffort) {
        this.ctx = ctx;
        this.edgeRouter = ctx.edgeRouter();
        this.metrics = ctx.metrics();
        this.gminaIndex = ctx.gminaIndex();
        this.rewards = ctx.rewards();
        this.ops = ctx.ops();
        this.debug = ctx.debug();
        this.debugGeoJson = ctx.debugGeoJson();
        this.seed = seed;
        this.route = seed.route();
        this.selected = seed.selected();
        this.hiBand = hiBand;
        this.targetEffort = targetEffort;
        this.realEffort = realEffort;
    }

    int trimmed() { return trimmed; }
    int growNearAdded() { return growNearAdded; }

    /** Rundy TRIM (max 4, dopóki >hiBand): peeling peryferii → anchor → doubleCut → dobierz gdy <95%. */
    double run() {
        for (int ti = 0; ti < 4 && realEffort > hiBand; ti++) {
            int peeledThisRound = peelFringe(ti);
            // RAZ na rundę: przywróć invariant „gmina = wp na śladzie" + autorytatywna geometria.
            new Anchorer(ctx, seed, "trim" + ti).run();
            if (debugGeoJson) {
                debug.geometry("trim" + ti + "-anchor-real", metrics.realGeometry(route), route, metrics.realKm(route));
                debug.logShots("trim" + ti + "-anchor");
            }
            realEffort = doubleCut(targetEffort, 3, "trim" + ti + "-tailprune-real");
            regrowIfUnderfilled(ti);
            if (peeledThisRound == 0) break; // nic bezpiecznego do ucięcia → nie kręć outer loop w kółko
        }
        return realEffort;
    }

    /** PODWÓJNE CIĘCIE „dla pewności": cut → 2opt → cut (drugie cięcie tnie wtórniaki po przestawieniu 2-optu). */
    private double doubleCut(double target, int maxPasses, String phase) {
        new SpurCutter(ctx, seed, target, maxPasses, phase).run();
        ops.twoOpt(route, phase + "-recut2opt");
        return new SpurCutter(ctx, seed, target, maxPasses, phase + "-recut").run();
    }

    /** Wewnętrzny peeling: porcjami tnij najgorszy reward/detour z fringe, pełny 2-opt, realny pomiar; stop gdy
     *  brak bezpiecznych kandydatów albo effort nie schudł (anty-spin). Zwraca ile wp ucięto w tej rundzie. */
    private int peelFringe(int ti) {
        int peeledThisRound = 0;
        for (int peelK = 1; peelK <= TRIM_MAX_PEELS && realEffort > hiBand; peelK++) {
            long peelCallsStart = edgeRouter.realCalls();
            double before = realEffort;
            RouteMetrics.EvalResult evt = metrics.eval(route);
            Set<Integer> visited = evt.visited();
            int gmint = visited.size();
            double eFracT = realEffort / targetEffort;
            int removeN = Math.max(1, (int) Math.round(gmint * (eFracT - 1.0) / eFracT));
            List<DealCand> cands = collectDealCandidates(visited);
            if (cands.isEmpty()) break; // sama peryferia-wnętrze → nic bezpiecznego do ucięcia
            cands.sort(Comparator.comparingDouble(DealCand::key));
            edgeRouter.setReason("pomiar");
            int removed = 0;
            for (DealCand dc : cands) {
                if (removed >= removeN) break;
                int wpIdx = GeometryUtil.identityIndexOf(route, dc.s().point());
                if (wpIdx <= 0 || wpIdx >= route.size() - 1) continue;
                route.remove(wpIdx);      // W MIEJSCU (bez rebuildOrderedRoute) → prev→next wprost
                selected.remove(dc.s());
                trimmed++; removed++; peeledThisRound++;
            }
            if (removed == 0) break;
            ops.twoOpt(route, "trim" + ti + "-peel" + peelK); // pełny 2-opt (haversine, tylko skraca)
            realEffort = metrics.effortAccurate(route);        // realny BRouter PO 2-opt
            log.info("Coverage TRIM peel ti={} k={}: {} gmin @ {}% -> -{} (reward/detour, fringe) -> {}%, calls={}",
                    new Object[]{ti, peelK, gmint, Math.round(eFracT * 100), removed,
                            Math.round(realEffort / targetEffort * 100), edgeRouter.realCalls() - peelCallsStart});
            if (debugGeoJson) {
                debug.geometry("trim" + ti + "-peel" + peelK + "-real", metrics.realGeometry(route), route, metrics.realKm(route));
                debug.logShots("trim" + ti + "-peel" + peelK);
            }
            if (realEffort >= before - TRIM_PROGRESS_EPS) break; // anty-spin: ucięto kolateral, effort nie schudł
        }
        return peeledThisRound;
    }

    /** Kandydaci do cięcia = nie-protected, FRINGE (nie otoczone — cięcie nie zrobi dziury); klucz reward/koszt-objazdu. */
    private List<DealCand> collectDealCandidates(Set<Integer> visited) {
        List<DealCand> cands = new ArrayList<>();
        for (SeedSel s : selected) {
            if (s.score() >= SeedBuilder.ENCLOSED_PROTECTED_SCORE) continue;
            if (gminaIndex.allNeighborsVisited(s.area().areaId(), visited)) continue; // otoczona śladem → dziura
            int wpIdx = GeometryUtil.identityIndexOf(route, s.point());
            if (wpIdx <= 0 || wpIdx >= route.size() - 1) continue;
            double[] prev = route.get(wpIdx - 1), cur = route.get(wpIdx), next = route.get(wpIdx + 1);
            double eIn = edgeRouter.edge(prev, cur).distanceKm();
            double eOut = edgeRouter.edge(cur, next).distanceKm();
            double detour = Math.max(0.0, eIn + eOut
                    - velomarker.service.planning.WaypointSelector.haversineKm(prev, next));
            double rw = rewards.getOrDefault(RewardModel.categoryKey(s.area()), 1.0);
            cands.add(new DealCand(s, rw / Math.max(TRIM_DETOUR_EPS, detour)));
        }
        return cands;
    }

    /** Gdy po cięciu zjechało <95% budżetu — dobierz proporcjonalnie gmin (reward-aware growNear). */
    private void regrowIfUnderfilled(int ti) {
        double eFracAfter = realEffort / targetEffort;
        if (eFracAfter >= 0.95) return;
        int gminA = metrics.eval(route).visited().size();
        int additional = (int) Math.round(gminA * (1.0 - eFracAfter) / Math.max(0.05, eFracAfter));
        if (additional > 0) {
            edgeRouter.setReason("grow");
            GrowNear.GrowNearResult gr = new GrowNear(ctx, seed, hiBand, Math.min(additional, 16), additional + 1, additional).run();
            growNearAdded += gr.inserted();
            realEffort = gr.effort();
            edgeRouter.setReason("pomiar");
        }
        if (debugGeoJson) {
            debug.geometry("trim" + ti + "-grow-real", metrics.realGeometry(route), route, metrics.realKm(route));
            debug.logShots("trim" + ti + "-grow");
        }
    }
}
