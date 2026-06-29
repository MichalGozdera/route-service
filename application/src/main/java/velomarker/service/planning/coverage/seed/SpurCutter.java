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
import velomarker.port.out.planning.AreaPassage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Cięcie zaułków (TAIL-PRUNE): reanchor przelotów i przycinanie głębokich palców z pogłębianiem ku MIC. */
public final class SpurCutter {
    private static final Logger log = LoggerFactory.getLogger(SpurCutter.class);
    private static final double EXIT_SEPARATION_KM = 0.08;
    private static final double KEEPER_EPS_KM = 0.15;
    private static final double MOVED_EPS_KM = 0.02;
    private static final int LEG_WINDOW = 150;
    /** Maks. realny dystans wp↔kotwica W TEJ SAMEJ gminie (km). Cel reanchora dalej = bzdur (zła kotwica
     *  z passages / korupcja shared-state) → traktuj jak brak celu (fallback in-gmina + guard w applyReanchor). */
    private static final double MAX_REANCHOR_DIST_KM = 25.0;

    private static Decision keep(int gid, double[] keepWp, Source src) {
        return new Decision(gid, Kind.KEEP, null, keepWp, List.of(), src);
    }
    private static Decision reanchor(int gid, double[] cel, double[] keepWp, List<double[]> redundant, Source src) {
        return new Decision(gid, Kind.REANCHOR, cel, keepWp, redundant, src);
    }
    private static Decision innerTrim(int gid, double[] entry, double[] keepWp) {
        return new Decision(gid, Kind.INNER_TRIM, entry, keepWp, List.of(), Source.TRIM);
    }

    private final EdgeRouter edgeRouter;
    private final RouteMetrics metrics;
    private final CoverageAreaIndex coverageAreaIndex;
    private final HilbertOrdering ordering;
    private final SeedOps ops;
    private final CoverageDebug debug;
    private final boolean debugGeoJson;
    private final SeedRoute seed;
    private final List<double[]> route;
    private final List<double[]> anchors;
    private final List<double[]> baseline;
    private final List<SeedSel> selected;
    private final double targetEffort;
    private final int maxPasses;
    private final String debugPhase;
    private final Map<Integer, UnvisitedArea> idToArea = new HashMap<>();
    private final long callsStart;
    private final double effortBefore;
    private final Set<Integer> visitedBefore;
    private final Set<Integer> deepAnchorAreaIds = new HashSet<>();
    private final Map<Integer, double[]> origWp = new HashMap<>();
    private Map<Integer, double[]> przelotAnchor;
    private Map<Integer, double[]> anchorTarget;
    private Map<Integer, Integer> wpCountInG;
    private Map<Integer, List<double[]>> wpByGid;
    private Map<Integer, double[]> entryMap;
    private Set<Integer> deeplyNow;
    private List<double[]> pendingRemove;
    private List<double[]> oldRealForFallback;
    private Map<Integer, double[]> fallbackHearts;
    private final Set<Integer> settledAreas = new HashSet<>();
    private Set<Integer> trimmedThisRound;
    private int round;
    private int fromPrzelot, fromZaulek, trimCount, keptCount;
    private final Deepener deepener;

    public SpurCutter(SeedContext ctx, SeedRoute seed, double targetEffort, int maxPasses, String debugPhase) {
        this.edgeRouter = ctx.edgeRouter();
        this.metrics = ctx.metrics();
        this.coverageAreaIndex = ctx.coverageAreaIndex();
        this.ordering = ctx.ordering();
        this.ops = ctx.ops();
        this.debug = ctx.debug();
        this.debugGeoJson = ctx.debugGeoJson();
        this.seed = seed;
        this.route = seed.route();
        this.anchors = seed.anchors();
        this.baseline = seed.baseline();
        this.selected = seed.selected();
        this.targetEffort = targetEffort;
        this.maxPasses = maxPasses;
        this.debugPhase = debugPhase;
        for (UnvisitedArea a : ctx.pool()) idToArea.put(a.areaId(), a);
        for (double[] anchor : anchors) {
            UnvisitedArea da = coverageAreaIndex.findDeeplyCreditedGminaForPoint(anchor[0], anchor[1]);
            if (da != null) deepAnchorAreaIds.add(da.areaId());
        }
        edgeRouter.setReason("pomiar");
        this.callsStart = edgeRouter.realCalls();
        this.effortBefore = metrics.effortViaCache(route);
        this.visitedBefore = coverageAreaIndex.visitedAreaIds(metrics.realGeometry(route));
        this.deepener = new Deepener(ctx, seed, deepAnchorAreaIds, settledAreas, origWp, idToArea);
    }

    public double run() {
        buildOrigWp();
        boolean roundAgain = true;
        round = 0;
        double effort = effortBefore;
        while (roundAgain && round < maxPasses + 9) {
            round++;
            int cut = runCutRound();
            effort = finishAndLog(cut);
            roundAgain = cut > 0;
        }
        return effort;
    }

    private int runCutRound() {
        if (route.size() < 3) return 0;
        List<double[]> refTrack = metrics.realGeometry(route);
        buildPassageMaps(refTrack);
        entryMap = coverageAreaIndex.firstBufferEntryPoints(refTrack);
        deeplyNow = coverageAreaIndex.deeplyVisitedAreaIds(refTrack);
        wpByGid = buildWpByGid();
        trimmedThisRound = new HashSet<>();
        List<Decision> decisions = computeDecisions();
        int cut = applyDecisions(decisions, refTrack);
        int rerouted = edgeRouter.rerouteApproximateLegs(route);
        if (rerouted > 0) log.info("Coverage TAIL-PRUNE [{}] runda {}: reroute {} sliced-legów REALNIE (pogłębianie na realnym śladzie)",
                new Object[]{debugPhase, round, rerouted});
        deepener.deepen(entryMap);
        Set<Integer> deeply = coverageAreaIndex.deeplyVisitedAreaIds(metrics.realGeometry(route));
        for (int gid : trimmedThisRound) if (deeply.contains(gid)) settledAreas.add(gid);
        return cut;
    }

    private void buildPassageMaps(List<double[]> realTrack) {
        Map<Integer, List<AreaPassage>> passages = coverageAreaIndex.passages(realTrack);
        przelotAnchor = new HashMap<>();
        anchorTarget = new HashMap<>();
        for (Map.Entry<Integer, List<AreaPassage>> e : passages.entrySet()) {
            List<AreaPassage> ps = e.getValue();
            if (ps.isEmpty()) continue;
            AreaPassage firstPrzelot = null;
            for (AreaPassage p : ps) if (p.chordKm() >= EXIT_SEPARATION_KM) { firstPrzelot = p; break; }
            if (firstPrzelot != null) przelotAnchor.put(e.getKey(), firstPrzelot.entry());
            anchorTarget.put(e.getKey(), (firstPrzelot != null ? firstPrzelot : ps.get(0)).entry());
        }
        wpCountInG = new HashMap<>();
        for (int i = 1; i < route.size() - 1; i++) {
            double[] p = route.get(i);
            if (GeometryUtil.isAnchor(p, anchors)) continue;
            UnvisitedArea a = coverageAreaIndex.findGminaForPoint(p[0], p[1]);
            if (a != null) wpCountInG.merge(a.areaId(), 1, Integer::sum);
        }
    }

    private Map<Integer, List<double[]>> buildWpByGid() {
        Map<Integer, List<double[]>> m = new HashMap<>();
        for (int i = 1; i < route.size() - 1; i++) {
            double[] p = route.get(i);
            if (GeometryUtil.isAnchor(p, anchors)) continue;
            UnvisitedArea a = coverageAreaIndex.findGminaForPoint(p[0], p[1]);
            if (a != null) m.computeIfAbsent(a.areaId(), k -> new ArrayList<>()).add(p);
        }
        return m;
    }

    private void buildOrigWp() {
        for (SeedSel s : selected) origWp.putIfAbsent(s.area().areaId(), s.point().clone());
    }

    private List<Decision> computeDecisions() {
        List<Integer> gids = wpByGid.keySet().stream()
                .filter(gid -> !deepAnchorAreaIds.contains(gid)).collect(Collectors.toList());
        return edgeRouter.parallelMap(gids, this::computeDecision);
    }

    private Decision computeDecision(int gid) {
        List<double[]> wps = wpByGid.get(gid);
        if (wps == null || wps.isEmpty()) return null;
        double[] keep = wps.get(0);
        double[] target = anchorTarget.get(gid);
        if (target != null && GeometryUtil.hav(keep, target) > MAX_REANCHOR_DIST_KM) target = null; // kotwica „uciekła" do innej gminy → odrzuć cel
        List<double[]> redundant = wps.size() >= 2 ? new ArrayList<>(wps.subList(1, wps.size())) : List.of();

        if (target != null && GeometryUtil.hav(keep, target) < KEEPER_EPS_KM) {
            return wps.size() >= 2 ? reanchor(gid, keep, keep, redundant, Source.KEPT) : keep(gid, keep, Source.KEPT);
        }
        if (przelotAnchor.containsKey(gid) || wpCountInG.getOrDefault(gid, 0) >= 2) {
            // Czysty przelot: JEDEN wp, który już zalicza gminę głęboko (≥220m) → zostaw go gdzie jest.
            // Reanchor przelotu przerzuca wp na krawędź wjazdową −220 (druga strona gminy) — zbędne i myli marker.
            if (wps.size() == 1 && deeplyNow.contains(gid)) return keep(gid, keep, Source.KEPT);
            double[] cel = (target != null) ? target.clone() : null;
            Source src = przelotAnchor.containsKey(gid) ? Source.PRZELOT : Source.MULTI_ZAULEK;
            return reanchor(gid, cel, keep, redundant, src);
        }
        if (settledAreas.contains(gid)) return keep(gid, keep, Source.KEPT);
        double[] entry = entryMap.get(gid);
        if (entry == null) return keepLog(gid, keep, "brak wejścia −220 (gmina za wąska)");
        return innerTrim(gid, entry.clone(), keep);
    }

    private int applyDecisions(List<Decision> decisions, List<double[]> refTrack) {
        fromPrzelot = fromZaulek = trimCount = keptCount = 0;
        pendingRemove = new ArrayList<>();
        oldRealForFallback = refTrack;
        fallbackHearts = null;
        int cut = 0;
        for (Decision d : decisions) {
            switch (d.kind()) {
                case REANCHOR -> {
                    if (applyReanchor(d)) { cut++; if (d.src() == Source.PRZELOT) fromPrzelot++; else fromZaulek++; }
                }
                case INNER_TRIM -> {
                    if (applyInnerTrim(d)) { cut++; trimCount++; trimmedThisRound.add(d.gid()); logTrim(d.gid(), d.target()); }
                    else keptCount++;
                }
                case KEEP -> keptCount++;
                case SHALLOW -> { }
            }
        }
        collapseRedundant(decisions);
        return cut;
    }

    private boolean applyInnerTrim(Decision d) {
        int idx = GeometryUtil.identityIndexOf(route, d.keepWp());
        if (idx <= 0 || idx >= route.size() - 1) return false;
        if (GeometryUtil.hav(d.target(), d.keepWp()) <= MOVED_EPS_KM) return false;
        double[] cel = d.target().clone();
        ops.swapEntry(selected, d.keepWp(), cel, baseline);
        route.set(idx, cel);
        return true;
    }

    private boolean applyReanchor(Decision d) {
        int gid = d.gid();
        double[] heart = d.target();
        if (heart == null) {
            if (fallbackHearts == null) fallbackHearts = coverageAreaIndex.firstBufferEntryPoints(oldRealForFallback);
            heart = fallbackHearts.get(gid);
        }
        if (heart == null) heart = origWp.get(gid);
        if (heart == null) { log.warn("Coverage TAIL-PRUNE re-anchor: brak kotwicy gminy id={} → możliwa dziura", gid); return false; }
        if (heart == d.keepWp() || GeometryUtil.hav(heart, d.keepWp()) <= MOVED_EPS_KM) return false;
        UnvisitedArea entryArea = coverageAreaIndex.findGminaForPoint(heart[0], heart[1]);
        if (entryArea == null || entryArea.areaId() != gid) {
            // Kotwica NIE leży w gminie gid — NIE przenoś wp poza gminę (gubiłoby pokrycie tej gminy).
            log.warn("Coverage TAIL-PRUNE [{}] re-anchor ODRZUCONY: kotwica gminy {} (id={}) wypadła w {} (heart={},{}) — zostawiam wp",
                    new Object[]{debugPhase, areaName(gid), gid, entryArea != null ? entryArea.name() : "poza-pulą", heart[0], heart[1]});
            return false;
        }
        UnvisitedArea area = entryArea;
        int centerLeg = GeometryUtil.identityIndexOf(route, d.keepWp());
        LegSeg ls = centerLeg >= 0 ? nearestLegSegment(heart, centerLeg) : null;
        if (ls == null) { replaceWp(d.keepWp(), heart, area); return true; }
        EdgeInfo edge = edgeRouter.edge(route.get(ls.leg()), route.get(ls.leg() + 1));
        double[] heartPoint = heart.clone();
        edgeRouter.seedSlicedEdgesAtPoint(edge, route.get(ls.leg()), route.get(ls.leg() + 1), ls.seg(), heartPoint);
        route.add(ls.leg() + 1, heartPoint);
        selected.add(new SeedSel(area, heartPoint, ordering.orderKey(heartPoint), 0.0,
                GeometryUtil.minDistToBaselineKm(heartPoint, baseline)));
        pendingRemove.add(d.keepWp());
        return true;
    }

    private void replaceWp(double[] old, double[] neu, UnvisitedArea area) {
        int idx = GeometryUtil.identityIndexOf(route, old);
        if (idx < 0) return;
        double[] np = neu.clone();
        route.set(idx, np);
        ops.swapEntry(selected, old, np, baseline);
    }

    private LegSeg nearestLegSegment(double[] heart, int centerLeg) {
        LegSeg local = scanLegs(heart, Math.max(0, centerLeg - LEG_WINDOW),
                Math.min(route.size() - 1, centerLeg + LEG_WINDOW + 1));
        return local != null ? local : scanLegs(heart, 0, route.size() - 1);
    }

    private LegSeg scanLegs(double[] heart, int fromLeg, int toLeg) {
        int bestLeg = -1, bestSeg = -1;
        double bestSD = Double.MAX_VALUE;
        for (int j = fromLeg; j < toLeg && bestSD > 1e-7; j++) {
            List<double[]> g = edgeRouter.edge(route.get(j), route.get(j + 1)).geometry();
            for (int m = 0; m < g.size() - 1; m++) {
                double sd = GeometryUtil.pointToSegmentExactKm(heart, g.get(m), g.get(m + 1));
                if (sd < bestSD) { bestSD = sd; bestLeg = j; bestSeg = m; if (sd <= 1e-7) break; }
            }
        }
        return (bestLeg < 0 || bestSD > 0.05) ? null : new LegSeg(bestLeg, bestSeg);
    }

    private void collapseRedundant(List<Decision> decisions) {
        Set<double[]> del = Collections.newSetFromMap(new IdentityHashMap<>());
        del.addAll(pendingRemove);
        for (Decision d : decisions) del.addAll(d.redundant());
        if (del.isEmpty()) return;
        List<double[][]> mergedPairs = new ArrayList<>();
        List<double[]> newRoute = new ArrayList<>(route.size());
        double[] lastKept = null;
        boolean pendingMerge = false;
        for (double[] p : route) {
            if (del.contains(p)) { pendingMerge = true; continue; }
            if (pendingMerge && lastKept != null) mergedPairs.add(new double[][]{lastKept, p});
            pendingMerge = false;
            newRoute.add(p);
            lastKept = p;
        }
        route.clear();
        route.addAll(newRoute);
        selected.removeIf(s -> del.contains(s.point()));
        edgeRouter.setReason("ogonek-scalenie");
        edgeRouter.prewarmPairs(mergedPairs);
        edgeRouter.setReason("pomiar");
    }

    private Decision keepLog(int gid, double[] keep, String why) {
        if (debugGeoJson) log.info("Coverage TAIL-PRUNE [{}] palec {} → ZOSTAW: {}", new Object[]{debugPhase, areaName(gid), why});
        return keep(gid, keep, Source.ORIG);
    }
    private void logTrim(int gid, double[] entry) {
        if (debugGeoJson) log.info("Coverage TAIL-PRUNE [{}] palec {} → TRIM @{}m w głąb",
                new Object[]{debugPhase, areaName(gid), Math.round(coverageAreaIndex.depthMeters(entry, gid))});
    }
    private String areaName(int gid) {
        UnvisitedArea a = idToArea.get(gid);
        return a != null ? a.name() : "id" + gid;
    }

    private double finishAndLog(int cut) {
        double realEffort = metrics.effortViaCache(route);
        List<double[]> realTrack = metrics.realGeometry(route);
        Set<Integer> dropped = new HashSet<>(visitedBefore);
        dropped.removeAll(coverageAreaIndex.visitedAreaIds(realTrack));
        log.info("Coverage TAIL-PRUNE [{}] runda {}: cut={} (przelot={} zaulek={} trim={}) zostaw={}, dropped(≥200)={}, calls={}, effort {}->{} ({}%->{}%)",
                new Object[]{debugPhase, round, cut, fromPrzelot, fromZaulek, trimCount, keptCount, dropped.size(),
                        edgeRouter.realCalls() - callsStart, Math.round(effortBefore), Math.round(realEffort),
                        Math.round(effortBefore * 100.0 / targetEffort), Math.round(realEffort * 100.0 / targetEffort)});
        debug.geometry(debugPhase + "-r" + round, realTrack, route, metrics.realKm(route));
        return realEffort;
    }
}
