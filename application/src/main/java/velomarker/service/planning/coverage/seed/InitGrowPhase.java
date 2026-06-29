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
import velomarker.entity.planning.UnvisitedArea;

import java.util.List;

// Faza dobierania wstępnego (init-grow): grow batchami do 110% budżetu; naprawa wysp w IslandFixer.
public final class InitGrowPhase {

    private static final Logger log = LoggerFactory.getLogger(InitGrowPhase.class);

    private final SeedContext ctx;
    private final EdgeRouter edgeRouter;
    private final RouteMetrics metrics;
    private final SeedOps ops;
    private final List<UnvisitedArea> pool;
    private final CandidatePicker picker;
    private final CoverageDebug debug;
    private final boolean debugGeoJson;
    private final SeedRoute seed;
    private final List<double[]> route;
    private final List<SeedSel> selected;

    private final double targetEffort, hiBand, growCeiling, effortMultiplier;
    private final long seedStartTs;

    private double effortFactor;
    private int realCheckpoints;
    private long tRebuildNs, tRouteEffortNs;

    public InitGrowPhase(SeedContext ctx, SeedRoute seed, CandidatePicker picker, double targetEffort, double hiBand,
                  double growCeiling, double effortMultiplier, long seedStartTs) {
        this.ctx = ctx;
        this.edgeRouter = ctx.edgeRouter();
        this.metrics = ctx.metrics();
        this.ops = ctx.ops();
        this.pool = ctx.pool();
        this.picker = picker;
        this.debug = ctx.debug();
        this.debugGeoJson = ctx.debugGeoJson();
        this.seed = seed;
        this.route = seed.route();
        this.selected = seed.selected();
        this.targetEffort = targetEffort;
        this.hiBand = hiBand;
        this.growCeiling = growCeiling;
        this.effortMultiplier = effortMultiplier;
        this.seedStartTs = seedStartTs;
    }

    public IslandFixResult run() {
        this.effortFactor = effortMultiplier;
        this.realCheckpoints = 0;
        log.info("Coverage seed grow START: pool={} obszarów, target effort={} ({}/dzień × {}d)",
                new Object[]{pool.size(), Math.round(targetEffort),
                        Math.round(targetEffort / Math.max(1, route.size())), route.size()});
        edgeRouter.setReason("grow");
        debug.skeleton("init", route);
        IslandFixResult r = seedInitGrow();
        logBreakdown();
        return r;
    }

    private static final int ROUND = 0, BATCH = 20, PROGRESS_EVERY = 500;

    private IslandFixResult seedInitGrow() {
        int lastProgressMilestone = 0;
        double realEffort = 0;
        boolean poolExhausted = false;
        long roundStartTs = System.currentTimeMillis();
        int roundStartSelected = selected.size();
        log.info("Coverage seed round {} START: dotąd dodano {} obszarów, effort={}/{} ({}%), elapsed={}s",
                new Object[]{ROUND, selected.size(), Math.round(realEffort), Math.round(targetEffort), 0,
                        (System.currentTimeMillis() - seedStartTs) / 1000});
        int batchCounter = 0;
        double frontier = selected.stream().mapToDouble(SeedSel::distBase).max().orElse(0.0);
        while (true) {
            boolean precise = metrics.haversineKm(route) * effortFactor >= targetEffort * 0.80;
            double gate = Math.max(CandidatePicker.JUMP_FLOOR_KM, CandidatePicker.JUMP_RATIO * frontier);
            PickResult pr = picker.pick(precise ? 6 : BATCH, gate);
            if (pr.inserted() == 0) {
                if (pr.jumpAhead()) {
                    // Skok dystansu: wymuś realny checkpoint PRZED przekroczeniem przerwy (2-opt + BRouter),
                    // potem decyzja na realnym budżecie — zapas → dobieraj dalej; brak → stop.
                    batchCounter++;
                    realEffort = measureEffort(true, batchCounter, metrics.haversineKm(route) * effortFactor);
                    if (realEffort >= hiBand) {
                        log.info("Coverage INIT-GROW: skok dystansu do {} km, budżet {}% ≥105% → stop rundy 0",
                                new Object[]{Math.round(pr.nextDistKm()), Math.round(realEffort * 100.0 / targetEffort)});
                        break;
                    }
                    log.info("Coverage INIT-GROW: skok dystansu do {} km, budżet {}% <105% → dobieram dalej",
                            new Object[]{Math.round(pr.nextDistKm()), Math.round(realEffort * 100.0 / targetEffort)});
                    frontier = pr.nextDistKm();
                    continue;
                }
                poolExhausted = true;
                break;
            }
            // frontier rośnie o nowo dodane gminy (pick dokleja je na końcu selected, przed rebuildem)
            for (int i = selected.size() - pr.inserted(); i < selected.size(); i++) {
                double d = selected.get(i).distBase();
                if (d > frontier) frontier = d;
            }
            if (pr.poolExhausted()) poolExhausted = true;
            long _tReb = System.nanoTime();
            ops.rebuildOrdered(seed);
            tRebuildNs += System.nanoTime() - _tReb;
            batchCounter++;
            boolean doRefine = batchCounter % (precise ? 5 : 10) == 0;
            double estEffort = metrics.haversineKm(route) * effortFactor;
            boolean doReal = (batchCounter == 1) || doRefine || estEffort >= hiBand;
            realEffort = measureEffort(doReal, batchCounter, estEffort);
            debug.skeleton("round" + ROUND + "-batch" + batchCounter, route);
            lastProgressMilestone = logProgressMilestone(realEffort, lastProgressMilestone);
            if (realEffort >= hiBand) {
                log.info("Coverage INIT-GROW: osiągnięto {}% (≥105%) → stop rundy 0",
                        Math.round(realEffort * 100.0 / targetEffort));
                break;
            }
        }
        long roundDurMs = System.currentTimeMillis() - roundStartTs;
        log.info("Coverage seed round {} END: dodano {} obszarów w tej rundzie ({} → {}), trwało {}s",
                new Object[]{ROUND, selected.size() - roundStartSelected, roundStartSelected, selected.size(), roundDurMs / 1000});
        IslandFixResult islandFix = new IslandFixer(ctx, seed).run();
        debug.skeleton("round0-grown", route);
        debug.geometry("round0-grown-real", metrics.realGeometry(route), route, metrics.realKm(route));
        return new IslandFixResult(islandFix.realEffort(), islandFix.pruned(), islandFix.retried(), poolExhausted);
    }

    /** Co N batchy (lub przy starcie/przekroczeniu pasma) realny pomiar effortu z BRoutera; inaczej tania estymata. */
    private double measureEffort(boolean doReal, int batchCounter, double estEffort) {
        if (!doReal) return estEffort;
        if (batchCounter > 1) CoverageLocalSearch.optimize(route);
        double hav = metrics.haversineKm(route);
        long _tRE = System.nanoTime();
        double realEffort = metrics.effortViaCache(route);
        tRouteEffortNs += System.nanoTime() - _tRE;
        realCheckpoints++;
        if (hav > 1) effortFactor = realEffort / hav;
        debug.geometry("round" + ROUND + "-batch" + batchCounter + "-real",
                metrics.realGeometry(route), route, metrics.realKm(route));
        return realEffort;
    }

    private int logProgressMilestone(double realEffort, int lastProgressMilestone) {
        int currentMilestone = selected.size() / PROGRESS_EVERY;
        if (currentMilestone <= lastProgressMilestone) return lastProgressMilestone;
        long elapsedS = (System.currentTimeMillis() - seedStartTs) / 1000;
        double avgSecPerArea = elapsedS / (double) Math.max(1, selected.size());
        int remainingToTarget = (int) Math.max(0, (targetEffort - realEffort) / Math.max(0.01, realEffort / Math.max(1, selected.size())));
        long etaS = (long) (remainingToTarget * avgSecPerArea);
        log.info("Coverage seed progress: +{} obszarów (round={}), effort={}/{} ({}%), elapsed={}s, tempo={}ms/area, eta≈{}min",
                new Object[]{selected.size(), ROUND, Math.round(realEffort), Math.round(targetEffort),
                        Math.round(realEffort * 100.0 / targetEffort), elapsedS, Math.round(avgSecPerArea * 1000), etaS / 60});
        return currentMilestone;
    }

    private void logBreakdown() {
        long wallS = (System.currentTimeMillis() - seedStartTs) / 1000;
        log.info("Coverage INIT-GROW BREAKDOWN (wall={}s): routeEffort(BRouter)={}s | rebuild={}s | realCheckpoints={} effortFactor={}",
                new Object[]{wallS, tRouteEffortNs / 1_000_000_000L, tRebuildNs / 1_000_000_000L,
                        realCheckpoints, String.format(java.util.Locale.ROOT, "%.3f", effortFactor)});
    }
}
