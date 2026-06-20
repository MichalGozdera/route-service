package velomarker.service.planning.coverage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import velomarker.entity.planning.UnvisitedArea;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ANCHOR-INTERSECTS — jeden przebieg do-skutku: {postaw 1 wp w KAŻDEJ dotkniętej gminie → 2opt → reroute}, aż
 * żadna gmina nie jest wchodzona ≥220m bez kotwicującego wp (reroute potrafi przeprowadzić ślad przez NOWĄ gminę
 * po reorderze). Obiekt per-wywołanie: kolaboratory + stan przebiegu jako pola, pętle jako małe metody.
 */
final class Anchorer {

    private static final Logger log = LoggerFactory.getLogger(Anchorer.class);

    private final GminaIndex gminaIndex;
    private final RouteMetrics metrics;
    private final HilbertOrdering ordering;
    private final SeedOps ops;
    private final SeedRoute seed;
    private final List<double[]> route;
    private final List<double[]> anchors;
    private final List<double[]> baseline;
    private final List<SeedSel> selected;
    private final String debugPhase;
    private final long startNs = System.nanoTime();
    private final Map<Integer, UnvisitedArea> areaById = new HashMap<>();
    /** Gminy z głębokim (≥220m) start/meta/via — obowiązkowy anchor je pokrywa, nie stawiamy osobnego wp. */
    private final Set<Integer> deepAnchorAreaIds = new HashSet<>();
    private int iteration, anchoredOnEntry, anchoredOnCentroid, touchedCount;

    Anchorer(SeedContext ctx, SeedRoute seed, String debugPhase) {
        this.gminaIndex = ctx.gminaIndex();
        this.metrics = ctx.metrics();
        this.ordering = ctx.ordering();
        this.ops = ctx.ops();
        this.seed = seed;
        this.route = seed.route();
        this.selected = seed.selected();
        this.anchors = seed.anchors();
        this.baseline = seed.baseline();
        this.debugPhase = debugPhase;
        for (UnvisitedArea area : ctx.pool()) areaById.put(area.areaId(), area);
        for (double[] anchor : anchors) {
            UnvisitedArea anchorArea = gminaIndex.findDeeplyCreditedGminaForPoint(anchor[0], anchor[1]);
            if (anchorArea != null) deepAnchorAreaIds.add(anchorArea.areaId());
        }
    }

    /** Pętla do-skutku (max 5 iter): kotwicz dotknięte gminy → sprawdź czy zostały głębokie bez kotwicy. */
    void run() {
        boolean foundUncoveredDeep = true;
        List<double[]> realTrack = metrics.realGeometry(route);
        while (foundUncoveredDeep && iteration < 5) {
            iteration++;
            anchorTouchedAreas(realTrack);
            realTrack = metrics.realGeometry(route); // po RESET + 2opt — ujawnia głębokie przeloty nowego porządku
            foundUncoveredDeep = hasUncoveredDeep(realTrack);
        }
        log.info("Coverage ANCHOR-INTERSECTS [{}]: KONIEC po {} iter, touched={}, dt={}ms",
                new Object[]{debugPhase, iteration, touchedCount, (System.nanoTime() - startNs) / 1_000_000});
    }

    /** Postaw świeży wp w KAŻDEJ dotkniętej gminie (wejście ≥220m → pierwszy punkt wejścia; muśnięcie → najgłębszy
     *  punkt), usuń stare nie-anchor wp leżące w gminach, ułóż trasę wg Hilberta + 2opt. */
    private void anchorTouchedAreas(List<double[]> realTrack) {
        Map<Integer, double[]> entryPoints = gminaIndex.firstBufferEntryPoints(realTrack);
        Set<Integer> touchedAreaIds = gminaIndex.touchedAreaIds(realTrack);
        touchedCount = touchedAreaIds.size();
        List<SeedSel> freshAnchors = new ArrayList<>();
        anchoredOnEntry = 0;
        anchoredOnCentroid = 0;
        for (int areaId : touchedAreaIds) {
            if (deepAnchorAreaIds.contains(areaId)) continue;
            UnvisitedArea area = areaById.get(areaId);
            if (area == null) continue;
            double[] anchorPoint = entryPoints.get(areaId);
            if (anchorPoint != null) {
                anchoredOnEntry++;
            } else {
                double[] deepest = gminaIndex.deepestInteriorPoint(areaId);
                anchorPoint = deepest != null ? deepest : new double[]{area.lng(), area.lat()};
                anchoredOnCentroid++;
            }
            freshAnchors.add(new SeedSel(area, anchorPoint, ordering.orderKey(anchorPoint), 0.0,
                    GeometryUtil.minDistToBaselineKm(anchorPoint, baseline)));
        }
        selected.removeIf(s -> !GeometryUtil.isAnchor(s.point(), anchors)
                && gminaIndex.findGminaForPoint(s.point()[0], s.point()[1]) != null);
        selected.addAll(freshAnchors);
        ops.rebuildOrdered(seed);
        ops.twoOpt(route, "anchor-intersected" + (iteration > 1 ? "-i" + iteration : ""));
    }

    /** Czy po reroute jakaś gmina jest wchodzona ≥220m bez kredytującego wp (→ kolejna iteracja). Loguje iterację. */
    private boolean hasUncoveredDeep(List<double[]> realTrack) {
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
        boolean found = false;
        int uncoveredDeepCount = 0;
        for (int areaId : deepEntriesNow.keySet())
            if (!anchoredAreaIds.contains(areaId) && !deepAnchorAreaIds.contains(areaId) && areaById.containsKey(areaId)) {
                found = true;
                uncoveredDeepCount++;
            }
        log.info("Coverage ANCHOR-INTERSECTS [{}] iter {}: touched={}, wejscie={} centroid={}, nowe-glebokie-bez-kotwicy={}",
                new Object[]{debugPhase, iteration, touchedCount, anchoredOnEntry, anchoredOnCentroid, uncoveredDeepCount});
        return found;
    }
}
