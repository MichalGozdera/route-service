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
    private final EdgeRouter edgeRouter;
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
    /** Sfailowane gminy (ślad NIGDZIE nie wchodzi ≥220m — MIC/najgłębszy w wodzie) → punkt obwodowy z {@link #probeSamples}
     *  z którego BRouter wjeżdża ≥220m. Stały między iteracjami (anchorTouchedAreas go respektuje, reset nie nadpisze). */
    private final Map<Integer, double[]> sampleAnchor = new HashMap<>();
    /** gid → CROSSPOINT ≥220m (gdzie BRouter realnie posadził wp) = PODMIENIONY wp. anchorTouchedAreas używa go z
     *  PRIORYTETEM (by 2opt/reset nie nadpisał). resolveCrosspointAnchors zapełnia per-wp. */
    private final Map<Integer, double[]> crosspointAnchor = new HashMap<>();
    /** Maks. snap-offset (km) by uznać punkt obwodowy za OSIĄGALNY (BRouter posadził blisko, nie w wodzie/polu). */
    private static final double REACHABLE_KM = 0.15;
    /** Kredyt głębokości (m) — crosspoint MUSI być ≥ tyle od granicy (żaden epsilon). */
    private static final double MIN_DEPTH_M = 220.0;
    /** Ślad PIERWOTNY (po init-grow, przed jakimkolwiek anchorem) — źródło głębokich punktów do pogłębiania. */
    private List<double[]> originalTrack;
    /** CELE wp liczone RAZ z {@link #originalTrack} (stałe między iteracjami) — inaczej entry z bieżącego śladu
     *  oscyluje po każdym reroute i zbiór płytkich gmin „wędruje" (whack-a-mole), zamiast eskalować na stałym podzbiorze. */
    private Map<Integer, double[]> entryPointsFixed;
    private Set<Integer> touchedFixed;
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
        this.edgeRouter = ctx.edgeRouter();
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
            entryPointsFixed = gminaIndex.firstBufferEntryPoints(originalTrack); // CELE stałe (z grow), nie przeliczane co iter
            touchedFixed = gminaIndex.touchedAreaIds(originalTrack);
            while (again && iteration < 8) {
                iteration++;
                anchorTouchedAreas();
                realTrack = metrics.realGeometry(route); // po RESET + 2opt — ujawnia głębokie przeloty nowego porządku
                boolean foundUncoveredDeep = hasUncoveredDeep(realTrack); // found: gmina ≥220m bez kotwicy
                int swapped = resolveCrosspointAnchors();                // PER-WP crosspoint: ≥220 → wp:=crosspoint; <220 → shallowAreaIds
                int probed = probeSamples();                             // jeziora (shallow + ślad nigdzie ≥220) → punkt obwodowy
                boolean progress = escalateShallow();                    // podnieś deepenLevel płytkich (do 3)
                again = foundUncoveredDeep || progress || probed > 0 || swapped > 0;
            }
        } finally {
            snapToggle.accept(false);
        }
        log.info("Coverage ANCHOR-INTERSECTS [{}]: KONIEC po {} iter, touched={}, dt={}ms",
                new Object[]{debugPhase, iteration, touchedCount, (System.nanoTime() - startNs) / 1_000_000});
        logWpDepths();
    }

    /** Diagnostyka: jak głęboko (m od granicy) siedzi crosspoint KAŻDEGO wp w jego gminie. Loguje summary
     *  (ile <220/<250m) + najpłytsze (nazwa gminy, nr wp, głębokość). crosspoint = snap(wp) = crosspointA(edge(wp,next)). */
    private void logWpDepths() {
        record WpDepth(int idx, String name, double depth) {}
        List<WpDepth> depths = new ArrayList<>();
        for (int i = 1; i < route.size() - 1; i++) {
            double[] wp = route.get(i);
            UnvisitedArea area = gminaIndex.findGminaForPoint(wp[0], wp[1]);
            if (area == null) continue;
            double[] cp = edgeRouter.edge(wp, route.get(i + 1)).crosspointA();
            double depth = gminaIndex.depthMeters(cp != null ? cp : wp, area.areaId());
            depths.add(new WpDepth(i, area.name(), depth));
        }
        depths.sort(java.util.Comparator.comparingDouble(WpDepth::depth));
        int below220 = 0, below250 = 0;
        for (WpDepth d : depths) {
            if (d.depth() >= 0 && d.depth() < 220) below220++;
            if (d.depth() >= 0 && d.depth() < 250) below250++;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(40, depths.size()); i++) {
            WpDepth d = depths.get(i);
            sb.append(d.name()).append("(wp").append(d.idx()).append(")=").append(Math.round(d.depth())).append("m ");
        }
        log.info("Coverage WP-DEPTH [{}]: wp={}, crosspoint <220m={}, <250m={} | najpłytsze: {}",
                new Object[]{debugPhase, depths.size(), below220, below250, sb.toString().trim()});
    }

    /** Postaw świeży wp w KAŻDEJ dotkniętej gminie (wejście ≥220m → pierwszy punkt wejścia; muśnięcie → najgłębszy
     *  punkt), usuń stare nie-anchor wp leżące w gminach, ułóż trasę wg Hilberta + 2opt. CELE (entry/touched) stałe
     *  z {@link #originalTrack} — nie przeliczane co iter (inaczej oscylują i zbiór płytkich wędruje). */
    private void anchorTouchedAreas() {
        Set<Integer> touchedAreaIds = touchedFixed;
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
            double[] fixed = crosspointAnchor.get(areaId);            // PODMIENIONY crosspoint ≥220 (priorytet — reset/2opt nie rusza)
            double[] probed = sampleAnchor.get(areaId);               // punkt obwodowy (jezioro)
            if (fixed != null) {
                anchorPoint = fixed;
                anchoredOnEntry++;
            } else if (probed != null) {
                anchorPoint = probed;
                anchoredOnCentroid++;
            } else {                                                  // strzelamy cel 220 → 250 → 300 → deepestInterior; crosspoint zweryfikuje resolveCrosspointAnchors
                double[] cel = lvl == 0 ? gminaIndex.firstTrackPointAtDepth(originalTrack, areaId, 220.0)
                        : lvl == 1 ? gminaIndex.firstTrackPointAtDepth(originalTrack, areaId, 250.0)
                        : lvl == 2 ? gminaIndex.firstTrackPointAtDepth(originalTrack, areaId, 300.0)
                        : null;
                anchorPoint = cel != null ? cel : deepInterior(area, areaId);
                if (lvl == 0) anchoredOnEntry++;
                else { anchoredOnCentroid++; if (lvl == 1) deepenL1++; else if (lvl == 2) deepenL2++; else deepenL3++; }
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
        log.info("Coverage ANCHOR-INTERSECTS [{}] iter {}: touched={}, kotwiczono[220m={} 250m={} 300m={} deepest={}], nowe-glebokie-bez-kotwicy={}",
                new Object[]{debugPhase, iteration, touchedCount, anchoredOnEntry, deepenL1, deepenL2, deepenL3, uncoveredDeepCount});
        return found;
    }

    /** Lecimy PO WP wzdłuż trasy: dla KAŻDEGO wp bierzemy crosspoint (gdzie BRouter realnie posadził) i jego głębokość
     *  (JTS). crosspoint ≥220m → PODMIANA wp:=crosspoint (zafiksowane w {@link #crosspointAnchor}, na drodze, snap-stabilne).
     *  crosspoint <220m → gmina płytka (escalateShallow podbije cel następna iter). Ustawia {@link #shallowAreaIds}. Zwraca # podmian. */
    private int resolveCrosspointAnchors() {
        Set<Integer> shallow = new HashSet<>();
        int swapped = 0;
        for (int i = 1; i < route.size() - 1; i++) {
            double[] wp = route.get(i);
            UnvisitedArea area = gminaIndex.findGminaForPoint(wp[0], wp[1]);
            if (area == null) continue;
            int gid = area.areaId();
            if (deepAnchorAreaIds.contains(gid) || !areaById.containsKey(gid)) continue;
            if (crosspointAnchor.containsKey(gid)) continue;          // już zafiksowany dobry crosspoint
            // snap(wp) = crosspointA kolejnego edge (wp→next). getMatchedWaypoint(0) [=crosspointA] to JEDYNE pewne
            // źródło snapu w btools 1.7.9 — koniec (endPoint/getMatchedWaypoint(size-1)) zwraca null (indexInTrack
            // nienumerowany w runtime). Snap zależy od pozycji wp, nie kierunku → crosspointA(wp→next) == snap(wp).
            double[] cp = edgeRouter.edge(wp, route.get(i + 1)).crosspointA();
            if (cp == null) { shallow.add(gid); continue; }          // edge padł (target-island) → traktuj jak płytki (→ probe)
            double depth = gminaIndex.depthMeters(cp, gid);
            if (depth >= MIN_DEPTH_M) {                              // crosspoint dostatecznie głęboko → wp := crosspoint
                crosspointAnchor.put(gid, cp.clone());
                ops.swapEntry(selected, wp, cp, baseline);
                route.set(i, cp);
                swapped++;
            } else {
                shallow.add(gid);                                   // za płytko → eskaluj cel (250→300→deepest)
            }
        }
        shallowAreaIds = shallow;
        String names = "";
        if (!shallow.isEmpty() && shallow.size() < 50) {
            List<String> ns = new ArrayList<>();
            for (int gid : shallow) { UnvisitedArea a = areaById.get(gid); ns.add(a != null ? a.name() : "id" + gid); }
            ns.sort(null);
            names = " " + ns;
        }
        log.info("Coverage CROSSPOINT [{}] iter {}: podmieniono wp:=crosspoint={}, plytkie-crosspoint(<220m)={}{}",
                new Object[]{debugPhase, iteration, swapped, shallow.size(), names});
        return swapped;
    }

    /** Najgłębszy punkt gminy (MIC); fallback do centroidu gdy MIC się nie policzy. */
    private double[] deepInterior(UnvisitedArea area, int areaId) {
        double[] deepest = gminaIndex.deepestInteriorPoint(areaId);
        return deepest != null ? deepest : new double[]{area.lng(), area.lat()};
    }

    /** Dla SFAILOWANYCH gmin (płytkie po snapie I ślad NIGDZIE nie wchodzi ≥220m — eskalacja nie pomoże, np. gmina
     *  z jeziorem) obejdź obwód: znajdź punkt z którego BRouter wjeżdża ≥220m i zapamiętaj w {@link #sampleAnchor}
     *  (anchorTouchedAreas użyje go w następnej iter). Zwraca # gmin którym znaleziono punkt obwodowy. */
    private int probeSamples() {
        int probed = 0;
        for (int i = 1; i < route.size() - 1; i++) {
            double[] wp = route.get(i);
            UnvisitedArea area = gminaIndex.findGminaForPoint(wp[0], wp[1]);
            if (area == null) continue;
            int gid = area.areaId();
            if (!shallowAreaIds.contains(gid)) continue;      // tylko płytkie po snapie
            if (sampleAnchor.containsKey(gid)) continue;      // już znaleziony punkt obwodowy
            if (gminaIndex.firstTrackPointAtDepth(originalTrack, gid, 220.0) != null) continue; // ślad ma ≥220 gdzieś → eskalacja pomoże
            double[] best = bestSampleEntry(area, gid, route.get(i - 1), route.get(i + 1));
            if (best != null) {
                sampleAnchor.put(gid, best);
                probed++;
            }
        }
        if (probed > 0) log.info("Coverage ANCHOR-INTERSECTS [{}]: próbowano-obwód → {} gmin dostało punkt obwodowy ≥220m",
                new Object[]{debugPhase, probed});
        return probed;
    }

    /** Obejdź gminę po obwodzie ({@code samplePointsFor}, 8 pkt ~500m w głąb): pierwszy punkt z którego ślad
     *  (prev→s→next przez BRouter) wchodzi ≥220m w gminę. Fallback: pierwszy OSIĄGALNY (snap blisko) gdy żaden nie
     *  daje ≥220m (byle nie w wodzie). null gdy nic osiągalnego. */
    private double[] bestSampleEntry(UnvisitedArea area, int gid, double[] prev, double[] next) {
        double[][] samples = gminaIndex.samplePointsFor(area);
        double[] best250 = null, best220 = null, fallbackReachable = null;
        for (double[] s : samples) {
            List<double[]> in = edgeRouter.edge(prev, s).geometry();
            List<double[]> out = edgeRouter.edge(s, next).geometry();
            if (edgeRouter.failedEdges().contains(GeometryUtil.edgeKey(prev, s))
                    || edgeRouter.failedEdges().contains(GeometryUtil.edgeKey(s, next))) continue; // wjazd przez wyspę — pomiń
            List<double[]> seg = new ArrayList<>(in.size() + out.size());
            seg.addAll(in);
            seg.addAll(out);
            // punkt NA ŚLADZIE, NAJGŁĘBSZY osiągnięty próg (margines na snap/reorder), NIE sample s (ciągnie ku centroidowi/jezioru)
            double[] d300 = gminaIndex.firstTrackPointAtDepth(seg, gid, 300.0);
            if (d300 != null) return d300;                                    // ≥300m — najlepszy, bierz od razu
            if (best250 == null) best250 = gminaIndex.firstTrackPointAtDepth(seg, gid, 250.0);
            if (best250 == null && best220 == null) best220 = gminaIndex.firstTrackPointAtDepth(seg, gid, 220.0);
            if (fallbackReachable == null && !in.isEmpty()) {                 // żaden ≥220 → pierwszy osiągalny (snap blisko) jako fallback
                double[] snap = in.get(in.size() - 1);
                if (velomarker.service.planning.WaypointSelector.haversineKm(s, snap) <= REACHABLE_KM) fallbackReachable = s;
            }
        }
        return best250 != null ? best250 : best220 != null ? best220 : fallbackReachable;
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
