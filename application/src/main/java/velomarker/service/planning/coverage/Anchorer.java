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
import java.util.function.Consumer;

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
    private final Consumer<Boolean> snapToggle;
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
    /** Ślad PIERWOTNY (po init-grow, przed jakimkolwiek anchorem) — źródło głębokich punktów do pogłębiania. */
    private List<double[]> originalTrack;
    /** Gmina → poziom pogłębiania (0=entry 220m, 1=track≥250m, 2=track≥300m, ≥3=deepestInteriorPoint). */
    private final Map<Integer, Integer> deepenLevel = new HashMap<>();
    /** Gminy, w które ZESNAPOWANY ślad nie wchodzi ≥220m (ustawiane w hasUncoveredDeep). */
    private Set<Integer> shallowAreaIds = new HashSet<>();
    private int iteration, anchoredOnEntry, anchoredOnCentroid, touchedCount;
    /** Ile gmin na danym poziomie pogłębiania w bieżącej iter: L1=track≥250m, L2=track≥300m, L3=deepestInterior. */
    private int deepenL1, deepenL2, deepenL3;
    /** Etykiety głębokości per poziom (do logu): index = deepenLevel. */
    private static final String[] LEVEL_LABEL = {"220m", "250m", "300m", "deepest"};

    Anchorer(SeedContext ctx, SeedRoute seed, String debugPhase) {
        this.gminaIndex = ctx.gminaIndex();
        this.metrics = ctx.metrics();
        this.ordering = ctx.ordering();
        this.ops = ctx.ops();
        this.snapToggle = ctx.snapToggle();
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
        // Instrument A: snap-log BRoutera TYLKO na czas fazy anchor (routing w realGeometry poniżej).
        snapToggle.accept(true);
        try {
            boolean again = true;
            List<double[]> realTrack = metrics.realGeometry(route);
            originalTrack = realTrack; // pierwotny ślad (po grow) — grow wchodził najgłębiej, przed spłyceniem anchorem+snapem
            while (again && iteration < 8) {
                iteration++;
                anchorTouchedAreas(realTrack);
                realTrack = metrics.realGeometry(route); // po RESET + 2opt — ujawnia głębokie przeloty nowego porządku
                boolean foundUncoveredDeep = hasUncoveredDeep(realTrack); // ustawia shallowAreaIds
                boolean progress = escalateShallow();                    // podnieś deepenLevel płytkich (do 3)
                again = foundUncoveredDeep || progress;
            }
        } finally {
            snapToggle.accept(false);
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
        deepenL1 = deepenL2 = deepenL3 = 0;
        for (int areaId : touchedAreaIds) {
            if (deepAnchorAreaIds.contains(areaId)) continue;
            UnvisitedArea area = areaById.get(areaId);
            if (area == null) continue;
            int lvl = deepenLevel.getOrDefault(areaId, 0);
            double[] anchorPoint;
            if (lvl == 0) {                                            // domyślnie: wejście −220m (brzeg)
                anchorPoint = entryPoints.get(areaId);
                if (anchorPoint != null) {
                    anchoredOnEntry++;
                } else {
                    anchorPoint = deepInterior(area, areaId);
                    anchoredOnCentroid++;
                }
            } else {                                                  // pogłębianie płytkich: track≥250 → track≥300 → deepestInterior
                double[] deepPt = lvl == 1 ? gminaIndex.deepestTrackPointInArea(originalTrack, areaId, 250.0)
                        : lvl == 2 ? gminaIndex.deepestTrackPointInArea(originalTrack, areaId, 300.0)
                        : null;
                anchorPoint = deepPt != null ? deepPt : deepInterior(area, areaId);
                anchoredOnCentroid++;
                if (lvl == 1) deepenL1++; else if (lvl == 2) deepenL2++; else deepenL3++;
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
        // SNAP-AWARE: gminy DOTKNIĘTE, ale ZESNAPOWANY realny ślad NIE wchodzi ≥220m (snap BRoutera spłycił).
        Set<Integer> deep = gminaIndex.deeplyVisitedAreaIds(realTrack);
        Set<Integer> shallow = new HashSet<>(gminaIndex.touchedAreaIds(realTrack));
        shallow.removeAll(deep);
        shallow.removeAll(deepAnchorAreaIds);
        shallow.removeIf(id -> !areaById.containsKey(id));
        shallowAreaIds = shallow;
        // gdy mało płytkich — wypisz nazwy + poziom głębokości, na którym i tak nie złapały (do ręcznej weryfikacji)
        String shallowNames = "";
        if (!shallow.isEmpty() && shallow.size() < 50) {
            List<String> names = new ArrayList<>();
            for (int gid : shallow) {
                UnvisitedArea a = areaById.get(gid);
                int l = Math.min(deepenLevel.getOrDefault(gid, 0), 3);
                names.add((a != null ? a.name() : "id" + gid) + "(" + LEVEL_LABEL[l] + ")");
            }
            names.sort(null);
            shallowNames = " " + names;
        }
        log.info("Coverage ANCHOR-INTERSECTS [{}] iter {}: touched={}, wejscie={} pogłębiane[250m={} 300m={} deepest={}], nowe-glebokie-bez-kotwicy={}, plytkie-po-snapie(<220m)={}{}",
                new Object[]{debugPhase, iteration, touchedCount, anchoredOnEntry, deepenL1, deepenL2, deepenL3, uncoveredDeepCount, shallow.size(), shallowNames});
        return found;
    }

    /** Najgłębszy punkt gminy (MIC); fallback do centroidu gdy MIC się nie policzy. */
    private double[] deepInterior(UnvisitedArea area, int areaId) {
        double[] deepest = gminaIndex.deepestInteriorPoint(areaId);
        return deepest != null ? deepest : new double[]{area.lng(), area.lat()};
    }

    /** Podnieś poziom pogłębiania (max 3) każdej wciąż-płytkiej gminy; zwraca czy COKOLWIEK dało się jeszcze pogłębić. */
    private boolean escalateShallow() {
        boolean progress = false;
        for (int gid : shallowAreaIds) {
            int lvl = deepenLevel.getOrDefault(gid, 0);
            if (lvl < 3) {
                deepenLevel.put(gid, lvl + 1);
                progress = true;
            }
        }
        return progress;
    }
}
