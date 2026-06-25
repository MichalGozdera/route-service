package velomarker.service.planning.coverage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import velomarker.entity.planning.UnvisitedArea;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ANCHOR-INTERSECTS — jeden przebieg do-skutku: {postaw 1 wp w KAŻDEJ dotkniętej gminie → reroute → sprawdź czy
 * REALNY ślad wchodzi ≥220m}, eskaluj płytkie (250→300→MIC) / probe jeziora, aż się ustabilizuje. Kotwica = punkt
 * na PIERWOTNYM śladzie (na granicy bufora 220); weryfikacja = {@code deeplyVisitedAreaIds(realGeometry)} (ŚLAD,
 * nie snap-punkt). Obiekt per-wywołanie: kolaboratory + stan przebiegu jako pola, pętle jako małe metody.
 */
final class Anchorer {

    private static final Logger log = LoggerFactory.getLogger(Anchorer.class);

    private final GminaIndex gminaIndex;
    private final RouteMetrics metrics;
    private final HilbertOrdering ordering;
    private final EdgeRouter edgeRouter;
    private final CoverageDebug debug;
    private final boolean debugGeoJson;
    private final List<double[]> route;
    private final List<double[]> anchors;
    private final List<double[]> baseline;
    private final List<SeedSel> selected;
    private final String debugPhase;
    private final long startNs = System.nanoTime();
    private final Map<Integer, UnvisitedArea> areaById = new HashMap<>();
    /** Gminy z głębokim (≥220m) start/meta/via — obowiązkowy anchor je pokrywa, nie stawiamy osobnego wp. */
    private final Set<Integer> deepAnchorAreaIds = new HashSet<>();
    /** Sfailowane gminy (ślad NIGDZIE nie wchodzi ≥220m — np. jezioro) → punkt obwodowy z {@link #probeSamples}
     *  z którego BRouter wjeżdża ≥220m. Stały między iteracjami (anchorTouchedAreas go respektuje). */
    private final Map<Integer, double[]> sampleAnchor = new HashMap<>();
    /** Maks. snap-offset (km) by uznać punkt obwodowy za OSIĄGALNY (BRouter posadził blisko, nie w wodzie/polu). */
    private static final double REACHABLE_KM = 0.15;
    /** Ślad PIERWOTNY (po init-grow, przed anchorem) — źródło punktów-celów na granicy 220m. */
    private List<double[]> originalTrack;
    /** Wejścia ~220m KAŻDEJ gminy (z {@link #originalTrack}, interpolowane na śladzie) — cel lvl0. Stałe między iter. */
    private Map<Integer, double[]> entryPointsFixed;
    private Set<Integer> touchedFixed;
    /** P5: gid → pierwsze wejście ≥300m na śladzie (batch 1× przebieg dla lvl1-gmin; computeAnchorFor czyta równolegle). */
    private Map<Integer, double[]> lvl1Map = Map.of();
    /** Gmina → poziom pogłębiania (0=wejście 220m, 1=track≥250m, 2=track≥300m, ≥3=deepestInteriorPoint MIC). */
    private final Map<Integer, Integer> deepenLevel = new HashMap<>();
    /** Gminy DOTKNIĘTE, w które REALNY ślad NIE wchodzi ≥220m (ustawiane w computeShallow) — do eskalacji/probe. */
    private Set<Integer> shallowAreaIds = new HashSet<>();
    private int iteration, touchedCount;
    /** Liczniki źródła kotwicy w bieżącej iter (do logu): punkt obwodowy (jezioro), świeży cel 220m. */
    private int fromSample, lvl0Count;
    /** Ile gmin na danym poziomie pogłębiania w bieżącej iter: L1=cel≥250m, L2=cel≥300m, L3=deepestInterior(MIC). */
    private int deepenL1, deepenL2, deepenL3;

    Anchorer(SeedContext ctx, SeedRoute seed, String debugPhase) {
        this.gminaIndex = ctx.gminaIndex();
        this.metrics = ctx.metrics();
        this.ordering = ctx.ordering();
        this.edgeRouter = ctx.edgeRouter();
        this.debug = ctx.debug();
        this.debugGeoJson = ctx.debugGeoJson();
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

    /** Pętla do-skutku (max 8 iter): kotwicz dotknięte gminy → reroute → płytkie (ślad<220) eskaluj/probuj. */
    void run() {
        boolean again = true;
        edgeRouter.rerouteApproximateLegs(route);        // sliced (z poprzedniego SpurCuttera) → REALNY przed kotwiczeniem
        originalTrack = metrics.realGeometry(route);     // REALNY ślad (po reroute) — CELE/entry liczone na nim, nie na approx
        entryPointsFixed = gminaIndex.firstBufferEntryPoints(originalTrack); // CELE lvl0 stałe (wejścia ~220)
        touchedFixed = gminaIndex.touchedAreaIds(originalTrack);
        while (again && iteration < 8) {
            iteration++;
            anchorTouchedAreas();                         // cele liczone w pamięci (JTS) — tanie
            edgeRouter.prewarm(route);                    // nowe pary po reorderze → policz RÓWNOLEGLE przed realGeometry
            List<double[]> realTrack = metrics.realGeometry(route); // czyta gotowy cache (hit) — tanie
            computeShallow(realTrack);                    // shallow = touched − deeplyVisited(ślad) − deepAnchor
            int probed = probeSamples();                  // jeziora (shallow + ślad nigdzie ≥220) → punkt obwodowy
            boolean progress = escalateShallow();         // podnieś deepenLevel płytkich
            again = progress || probed > 0;               // kręcimy TYLKO gdy jest co eskalować/probować
            if (debugGeoJson)
                debug.geometry(debugPhase + "-anchor-iter" + iteration, realTrack, route, metrics.realKm(route));
        }
        log.info("Coverage ANCHOR-INTERSECTS [{}]: KONIEC po {} iter, touched={}, dt={}ms",
                new Object[]{debugPhase, iteration, touchedCount, (System.nanoTime() - startNs) / 1_000_000});
    }

    /** Postaw świeży wp w KAŻDEJ dotkniętej gminie (cel na PIERWOTNYM śladzie: wejście 220 → 250 → 300 → MIC wg
     *  deepenLevel; jezioro → punkt obwodowy z sampleAnchor), usuń stare nie-anchor wp, ułóż trasę wg Hilberta.
     *  CELE stałe z {@link #originalTrack} — nie przeliczane co iter (inaczej oscylują, zbiór płytkich wędruje). */
    private void anchorTouchedAreas() {
        touchedCount = touchedFixed.size();
        // P5: lvl1 cel = firstTrackPointAtDepth(300) — policz BATCH 1× przebieg śladu dla WSZYSTKICH lvl1-gmin (zamiast
        // O(track) per gmina w computeAnchorFor); reszta celów (lvl0 entry / lvl≥2 MIC) bez skanu śladu.
        Set<Integer> lvl1Gids = new HashSet<>();
        for (int gid : touchedFixed)
            if (deepenLevel.getOrDefault(gid, 0) == 1 && !deepAnchorAreaIds.contains(gid) && !sampleAnchor.containsKey(gid))
                lvl1Gids.add(gid);
        lvl1Map = gminaIndex.firstTrackPointsAtDepth(originalTrack, lvl1Gids, 300.0);
        // Cele per gmina liczone RÓWNOLEGLE (N6) — gminy niezależne; MIC (deepestInteriorPoint, ConcurrentHashMap) na
        // wielu wątkach. computeAnchorFor jest CZYSTA (read-only stan); lvl1 czyta gotowy lvl1Map.
        List<SeedSel> freshAnchors = edgeRouter.parallelMap(touchedFixed, this::computeAnchorFor);   // virtual threads + OTel (T4)
        countSourcesAndLog(freshAnchors);   // liczniki źródeł + log ESCALATE — SEKWENCYJNIE po (tani, bez skanu śladu)
        // IN-PLACE (bez rebuildOrdered/Hilbert-reset): zachowaj KOLEJNOŚĆ route (nie psuj 2-opt) — podmień stary wp
        // gminy na świeżą kotwicę NA TEJ SAMEJ pozycji; nietouched/duplikat stare wp usuń; nowe touched-gminy →
        // cheapest-insert. refineOrder PO anchorze (w finalize) rozplącze. selected = ZBIÓR (kolejność trzyma route).
        Map<Integer, SeedSel> freshByGid = new HashMap<>();
        for (SeedSel f : freshAnchors) {
            freshByGid.put(f.area().areaId(), f);
        }
        Set<Integer> placed = new HashSet<>();
        List<double[]> newRoute = new ArrayList<>(route.size());
        for (double[] p : route) {
            if (GeometryUtil.isAnchor(p, anchors)) {
                newRoute.add(p);   // start/via/end nietykalne
                continue;
            }
            UnvisitedArea g = gminaIndex.findGminaForPoint(p[0], p[1]);
            if (g != null && freshByGid.containsKey(g.areaId()) && placed.add(g.areaId())) {
                newRoute.add(freshByGid.get(g.areaId()).point());   // podmień na fresh, ZACHOWAJ pozycję = kolejność 2-opt
            }
            // else: stary wp gminy nietouched LUB duplikat tej samej gminy → usuń (pomiń)
        }
        route.clear();
        route.addAll(newRoute);
        for (SeedSel f : freshAnchors) {   // nowe touched-gminy (fresh bez starego wp) → cheapest-insert (mało)
            if (!placed.contains(f.area().areaId())) {
                route.add(GeometryUtil.cheapestInsertPos(route, f.point()), f.point());
            }
        }
        selected.removeIf(s -> !GeometryUtil.isAnchor(s.point(), anchors)
                && gminaIndex.findGminaForPoint(s.point()[0], s.point()[1]) != null);
        selected.addAll(freshAnchors);
    }

    /** CZYSTA (read-only współdzielony stan → bezpieczna w parallelStream): kotwica gminy wg deepenLevel/sampleAnchor.
     *  {@code null} gdy gmina deepAnchor/nieznana. CEL: lvl0 = wejście ~220 (entryPointsFixed); lvl1 = 300 na śladzie,
     *  a gdy ślad nie sięga 300 → pchnij 80m ku MIC; lvl≥2 = pchaj dalej ku MIC (lvl*80m, BLISKO — bez btools Index -1).
     *  Drogie (firstTrackPointAtDepth, MIC) liczone tu — RÓWNOLEGLE między gminami. */
    private SeedSel computeAnchorFor(int areaId) {
        if (deepAnchorAreaIds.contains(areaId)) return null;
        UnvisitedArea area = areaById.get(areaId);
        if (area == null) return null;
        int lvl = deepenLevel.getOrDefault(areaId, 0);
        double[] probed = sampleAnchor.get(areaId);                  // punkt obwodowy (jezioro) — priorytet
        double[] anchorPoint;
        if (probed != null) {
            anchorPoint = probed;
        } else {
            double[] entry = entryPointsFixed.get(areaId);
            double[] cel;
            if (lvl == 0) {
                cel = entry;
            } else if (lvl == 1) {
                cel = lvl1Map.get(areaId);                       // P5: z batch (1× przebieg track), nie per-gmina skan
                if (cel == null && entry != null)
                    cel = GeometryUtil.movePointTowards(entry, deepInterior(area, areaId), 80.0);
            } else {
                double[] base = entry != null ? entry : deepInterior(area, areaId);
                cel = GeometryUtil.movePointTowards(base, deepInterior(area, areaId), lvl * 80.0);
            }
            anchorPoint = cel != null ? cel : deepInterior(area, areaId);
        }
        return new SeedSel(area, anchorPoint, ordering.orderKey(anchorPoint), 0.0,
                GeometryUtil.minDistToBaselineKm(anchorPoint, baseline));
    }

    /** Liczniki źródeł kotwic (do logu computeShallow) + log ESCALATE lvl≥2 — SEKWENCYJNIE po parallel (tani:
     *  bez skanu śladu; depthMeters tylko dla rzadkich lvl≥2). Odtwarza decyzje z deepenLevel/sampleAnchor. */
    private void countSourcesAndLog(List<SeedSel> freshAnchors) {
        fromSample = 0;
        lvl0Count = 0;
        deepenL1 = deepenL2 = deepenL3 = 0;
        Map<Integer, double[]> ptByGid = new HashMap<>();
        for (SeedSel s : freshAnchors) ptByGid.putIfAbsent(s.area().areaId(), s.point());
        for (int areaId : touchedFixed) {
            if (deepAnchorAreaIds.contains(areaId) || areaById.get(areaId) == null) continue;
            if (sampleAnchor.get(areaId) != null) { fromSample++; continue; }
            int lvl = deepenLevel.getOrDefault(areaId, 0);
            if (lvl == 0) { lvl0Count++; continue; }
            if (lvl == 1) deepenL1++; else if (lvl == 2) deepenL2++; else deepenL3++;
            double[] ap = ptByGid.get(areaId);
            if (debugGeoJson && lvl >= 2 && ap != null)   // loguj jak głęboko celujemy (lvl≥2 rzadkie; lvl1 masowe)
                log.info("Coverage ANCHOR-ESCALATE [{}] iter {}: {} lvl{} → cel-depth={}m (push {}m ku MIC)",
                        new Object[]{debugPhase, iteration, areaById.get(areaId).name(), lvl,
                                Math.round(gminaIndex.depthMeters(ap, areaId)), lvl * 80});
        }
    }

    /** Po reroute: gminy DOTKNIĘTE (z originalTrack), w które REALNY ślad NIE wchodzi ≥220m → do eskalacji/probe.
     *  shallow = touchedFixed − deeplyVisited(realTrack) − deepAnchor. Loguje iterację (źródła kotwic + ślad<220). */
    private void computeShallow(List<double[]> realTrack) {
        Set<Integer> deeply = gminaIndex.deeplyVisitedAreaIds(realTrack);
        Set<Integer> shallow = new HashSet<>();
        for (int gid : touchedFixed)
            if (!deeply.contains(gid) && !deepAnchorAreaIds.contains(gid) && areaById.containsKey(gid))
                shallow.add(gid);
        shallowAreaIds = shallow;
        String names = "";
        if (!shallow.isEmpty() && shallow.size() < 50) {
            List<String> ns = new ArrayList<>();
            for (int gid : shallow) { UnvisitedArea a = areaById.get(gid); ns.add(a != null ? a.name() : "id" + gid); }
            ns.sort(null);
            names = " " + ns;
        }
        log.info("Coverage ANCHOR-INTERSECTS [{}] iter {}: touched={}, jezioro={}, swieze[220m={} 300m={} push160={} push240={}], slad<220m={}{}",
                new Object[]{debugPhase, iteration, touchedCount, fromSample, lvl0Count, deepenL1, deepenL2, deepenL3, shallow.size(), names});
    }

    /** Najgłębszy punkt gminy (MIC); fallback do centroidu gdy MIC się nie policzy. */
    private double[] deepInterior(UnvisitedArea area, int areaId) {
        double[] deepest = gminaIndex.deepestInteriorPoint(areaId);
        return deepest != null ? deepest : new double[]{area.lng(), area.lat()};
    }

    /** Dla SFAILOWANYCH gmin (płytkie I ślad NIGDZIE nie wchodzi ≥220m — eskalacja nie pomoże, np. gmina z jeziorem)
     *  obejdź obwód: znajdź punkt z którego BRouter wjeżdża ≥220m i zapamiętaj w {@link #sampleAnchor} (anchorTouchedAreas
     *  użyje go w następnej iter). Zwraca # gmin którym znaleziono punkt obwodowy. */
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

    /** Podnieś poziom pogłębiania (max 3=MIC) każdej wciąż-płytkiej gminy; zwraca czy COKOLWIEK dało się pogłębić. */
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
