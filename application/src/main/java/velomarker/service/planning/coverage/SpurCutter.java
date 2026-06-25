package velomarker.service.planning.coverage;

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

/**
 * Cięcie zaułków (TAIL-PRUNE) — RÓWNOLEGŁA wersja logiki z commita 102b3015. Dwa mechanizmy cięcia:
 * <ul>
 *   <li><b>REANCHOR</b> (gmina z PRZELOTEM {@code chord≥sep} lub ≥2 wp) → 1 wp na wejściu PIERWSZEGO przejścia
 *       ({@code anchorTarget}), nadmiarowe usunięte. Mechanizm SLICE ({@link EdgeRouter#seedSlicedEdgesAtPoint},
 *       0 BRouter — tnie istniejącą geometrię; reanchor na nodze ZANIM usunie = zero dziur).</li>
 *   <li><b>INNER_TRIM</b> (gmina bez przelotu, 1 wp, wp GŁĘBOKI &gt;220) → przesuń wp na wejście −220
 *       ({@code firstBufferEntryPoints}) przez {@code route.set}+reroute (jak {@code deepenLoop} push). Sąsiad
 *       nieistotny — BRouter liczy {@code prev→entry→next} na bieżąco.</li>
 * </ul>
 * Trzy poziomy: RUNDY (wtórniaki: świeże {@code passages} co runda) × compute(PARALLEL)→apply(SEKWENCYJNIE po IDENTITY)
 * × pogłębianie ({@code deepenLoop}: push ku MIC gdy po cięciu ślad &lt;220). IN-PLACE (bez {@code rebuildOrdered} →
 * kolejność 2-opt zachowana).
 */
final class SpurCutter {
    private static final Logger log = LoggerFactory.getLogger(SpurCutter.class);
    /** Separacja wejścia↔wyjścia przejścia (km): ≥ to = PRZELOT; bliżej = zaułek. */
    private static final double EXIT_SEPARATION_KM = 0.08;
    /** wp w tej odległości od kotwicy gminy = JUŻ kotwica → zostaw (anty-churn). */
    private static final double KEEPER_EPS_KM = 0.15;
    /** Min. przesunięcie wp (km) by liczyć jako CIĘCIE (`cut`). Poniżej = ten sam punkt co poprzednia runda → NIE cut
     *  (anti-nieskończoność: INNER_TRIM ustawiający `entry≈keep` co rundę napędzałby rundy do `maxPasses`). */
    private static final double MOVED_EPS_KM = 0.02;
    /** Maks. poziom pushu (1=80m, 2=160m, 3=240m ku MIC); po nim gmina-jezioro → restore origWp. */
    private static final int MAX_PUSH_LVL = 3;
    /** Maks. iteracji pogłębiania w jednej rundzie. */
    private static final int MAX_ITER = 6;
    /** P1: okno nóg wokół keep dla `nearestLegSegment` (heart=wejście gminy jest BLISKO keep; pełny skan = fallback). */
    private static final int LEG_WINDOW = 150;

    private enum Kind { KEEP, REANCHOR, INNER_TRIM, SHALLOW }
    private enum Source { PRZELOT, MULTI_ZAULEK, KEPT, TRIM, ORIG, PUSH, RESTORE }

    /** Noga trasy (leg) + indeks segmentu w jej geometrii (re-anchor na nodze). */
    private record LegSeg(int leg, int seg) {}
    /** Decyzja dla 1 gminy — liczona CZYSTO (read-only) w computeDecision; apply tylko mutuje route/selected.
     *  {@code target}: REANCHOR=anchorTarget / INNER_TRIM=entry220 / SHALLOW=push-cel / KEEP=null. */
    private record Decision(int gid, Kind kind, double[] target, double[] keepWp, List<double[]> redundant, Source src) {}

    private static Decision keep(int gid, double[] keepWp, Source src) {
        return new Decision(gid, Kind.KEEP, null, keepWp, List.of(), src);
    }
    private static Decision reanchor(int gid, double[] cel, double[] keepWp, List<double[]> redundant, Source src) {
        return new Decision(gid, Kind.REANCHOR, cel, keepWp, redundant, src);
    }
    private static Decision innerTrim(int gid, double[] entry, double[] keepWp) {
        return new Decision(gid, Kind.INNER_TRIM, entry, keepWp, List.of(), Source.TRIM);
    }
    private static Decision shallowDec(int gid, double[] cel, double[] cur, Source src) {
        return new Decision(gid, Kind.SHALLOW, cel, cur, List.of(), src);
    }

    private final EdgeRouter edgeRouter;
    private final RouteMetrics metrics;
    private final GminaIndex gminaIndex;
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
    /** Gminy z głębokim (≥220m) start/meta/via — anchor je pokrywa, nie ruszamy. */
    private final Set<Integer> deepAnchorAreaIds = new HashSet<>();
    /** gid → oryginalny wp gminy (wejście CAŁEJ fazy) — restore dla gmin-jezior. */
    private final Map<Integer, double[]> origWp = new HashMap<>();
    // ── stan BIEŻĄCEJ rundy (ustawiane PRZED compute, w compute tylko czytane — R8) ──
    private Map<Integer, double[]> przelotAnchor;       // gid → wejście PIERWSZEGO przelotu; brak = brak przelotu
    private Map<Integer, double[]> anchorTarget;        // gid → kotwica = wejście PIERWSZEGO przejścia
    private Map<Integer, Integer> wpCountInG;           // gid → ile nie-anchor wp ma gmina
    private Map<Integer, List<double[]>> wpByGid;       // gid → nie-anchor wp gminy (identity refs)
    private Map<Integer, double[]> entryMap;            // gid → wejście −220 (firstBufferEntryPoints, INNER_TRIM cel)
    private Map<Integer, double[]> roundWp;             // gid → bieżący wp (pogłębianie)
    private Map<Integer, double[]> deepestMap;          // P4: gid → najgłębszy punkt śladu (batch 1× przebieg, computePush lvl1)
    private List<double[]> realTrackForPush;            // bieżący ślad deepenLoop — computePush (parallel) czyta deepest
    private final Map<Integer, Integer> deepenLevel = new HashMap<>(); // 0/1..MAX push, -1 jezioro; reset co runda
    private List<double[]> pendingRemove;               // stary keep po REANCHOR → collapse (FAZA 2)
    private List<double[]> oldRealForFallback;          // ślad przed mutacjami apply (REANCHOR fallback entry)
    private Map<Integer, double[]> fallbackHearts;      // lazy firstBufferEntryPoints(oldReal) — REANCHOR fallback
    /** Gminy OGARNIĘTE (INNER_TRIM + pogłębione do ≥220) — PERSISTENT między rundami; KEEP + nie-push (anti-oscylacja). */
    private final Set<Integer> settledAreas = new HashSet<>();
    private Set<Integer> trimmedThisRound;              // gminy INNER_TRIM tej rundy (reset co runda)
    private int round;
    private int fromPrzelot, fromZaulek, trimCount, keptCount;

    SpurCutter(SeedContext ctx, SeedRoute seed, double targetEffort, int maxPasses, String debugPhase) {
        this.edgeRouter = ctx.edgeRouter();
        this.metrics = ctx.metrics();
        this.gminaIndex = ctx.gminaIndex();
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
            UnvisitedArea da = gminaIndex.findDeeplyCreditedGminaForPoint(anchor[0], anchor[1]);
            if (da != null) deepAnchorAreaIds.add(da.areaId());
        }
        edgeRouter.setReason("pomiar");
        this.callsStart = edgeRouter.realCalls();
        this.effortBefore = metrics.effortViaCache(route);
        this.visitedBefore = gminaIndex.visitedAreaIds(metrics.realGeometry(route));
    }

    /** Pętla RUND (wtórniaki): cięcie+pogłębianie → finishAndLog PER RUNDA; powtarza póki coś skrócono. */
    double run() {
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

    /** Jedna runda: passage maps → PARALLEL compute → SEKWENCYJNIE apply → pogłębianie. Zwraca # cięć. */
    private int runCutRound() {
        if (route.size() < 3) return 0;
        List<double[]> refTrack = metrics.realGeometry(route);
        buildPassageMaps(refTrack);
        entryMap = gminaIndex.firstBufferEntryPoints(refTrack);   // entry −220 per gmina (cały ślad — INNER_TRIM cel)
        wpByGid = buildWpByGid();
        trimmedThisRound = new HashSet<>();
        List<Decision> decisions = computeDecisions();            // (A) PARALLEL
        int cut = applyDecisions(decisions, refTrack);            // (B) SEKWENCYJNIE (oldReal=refTrack, P3 reuse)
        int rerouted = edgeRouter.rerouteApproximateLegs(route);  // sliced (REANCHOR) → REALNY BRouter PRZED pogłębianiem
        if (rerouted > 0) log.info("Coverage TAIL-PRUNE [{}] runda {}: reroute {} sliced-legów REALNIE (pogłębianie na realnym śladzie)",
                new Object[]{debugPhase, round, rerouted});
        deepenLoop();                                             // (C) pogłębianie na REALNYM śladzie
        // OGARNIĘTE: przycięte palce które po pogłębianiu osiągnęły ≥220 → settled (kolejna runda KEEP, nie oscyluj)
        Set<Integer> deeply = gminaIndex.deeplyVisitedAreaIds(metrics.realGeometry(route));
        for (int gid : trimmedThisRound) if (deeply.contains(gid)) settledAreas.add(gid);
        return cut;
    }

    /** Z `passages` wylicz przelotAnchor (PIERWSZY przelot)/anchorTarget (firstPrzelot ?? ps[0])/wpCountInG. 1:1 102b3015. */
    private void buildPassageMaps(List<double[]> realTrack) {
        Map<Integer, List<AreaPassage>> passages = gminaIndex.passages(realTrack);
        przelotAnchor = new HashMap<>();
        anchorTarget = new HashMap<>();
        for (Map.Entry<Integer, List<AreaPassage>> e : passages.entrySet()) {
            List<AreaPassage> ps = e.getValue();
            if (ps.isEmpty()) continue;
            AreaPassage firstPrzelot = null;                              // PIERWSZY przelot wzdłuż śladu
            for (AreaPassage p : ps) if (p.chordKm() >= EXIT_SEPARATION_KM) { firstPrzelot = p; break; }
            if (firstPrzelot != null) przelotAnchor.put(e.getKey(), firstPrzelot.entry());
            anchorTarget.put(e.getKey(), (firstPrzelot != null ? firstPrzelot : ps.get(0)).entry()); // PIERWSZE przejście
        }
        wpCountInG = new HashMap<>();
        for (int i = 1; i < route.size() - 1; i++) {
            double[] p = route.get(i);
            if (GeometryUtil.isAnchor(p, anchors)) continue;
            UnvisitedArea a = gminaIndex.findGminaForPoint(p[0], p[1]);
            if (a != null) wpCountInG.merge(a.areaId(), 1, Integer::sum);
        }
    }

    /** gid → nie-anchor wp gminy (identity refs, kolejność route). Jeden przebieg. */
    private Map<Integer, List<double[]>> buildWpByGid() {
        Map<Integer, List<double[]>> m = new HashMap<>();
        for (int i = 1; i < route.size() - 1; i++) {
            double[] p = route.get(i);
            if (GeometryUtil.isAnchor(p, anchors)) continue;
            UnvisitedArea a = gminaIndex.findGminaForPoint(p[0], p[1]);
            if (a != null) m.computeIfAbsent(a.areaId(), k -> new ArrayList<>()).add(p);
        }
        return m;
    }

    /** Zapamiętaj oryginalny wp KAŻDEJ gminy (ze `selected`) — restore jezior. */
    private void buildOrigWp() {
        for (SeedSel s : selected) origWp.putIfAbsent(s.area().areaId(), s.point().clone());
    }

    /** gid → bieżący wp gminy (pierwszy w `selected`) — pogłębianie. */
    private Map<Integer, double[]> buildRoundWp() {
        Map<Integer, double[]> m = new HashMap<>();
        for (SeedSel s : selected) m.putIfAbsent(s.area().areaId(), s.point());
        return m;
    }

    // ──────────────────────────────── (A) COMPUTE — PARALLEL, czysto ────────────────────────────────

    private List<Decision> computeDecisions() {
        List<Integer> gids = wpByGid.keySet().stream()
                .filter(gid -> !deepAnchorAreaIds.contains(gid)).collect(Collectors.toList());
        return edgeRouter.parallelMap(gids, this::computeDecision);   // virtual threads + OTel context (T4)
    }

    /** CZYSTA (read-only). REANCHOR (przelot/≥2 wp) lub INNER_TRIM (palec głęboki >220) lub KEEP. */
    private Decision computeDecision(int gid) {
        List<double[]> wps = wpByGid.get(gid);
        if (wps == null || wps.isEmpty()) return null;
        double[] keep = wps.get(0);
        double[] target = anchorTarget.get(gid);
        List<double[]> redundant = wps.size() >= 2 ? new ArrayList<>(wps.subList(1, wps.size())) : List.of();

        // (1) KEEPER: keep≈anchorTarget → zostaw; ale gdy ≥2 wp i tak usuń nadmiarowe (1 wp/gmina)
        if (target != null && GeometryUtil.hav(keep, target) < KEEPER_EPS_KM) {
            return wps.size() >= 2 ? reanchor(gid, keep, keep, redundant, Source.KEPT) : keep(gid, keep, Source.KEPT);
        }
        // (2) PRZELOT lub ≥2 wp → REANCHOR na anchorTarget (cel null → fallback w apply)
        if (przelotAnchor.containsKey(gid) || wpCountInG.getOrDefault(gid, 0) >= 2) {
            double[] cel = (target != null) ? target.clone() : null;
            Source src = przelotAnchor.containsKey(gid) ? Source.PRZELOT : Source.MULTI_ZAULEK;
            return reanchor(gid, cel, keep, redundant, src);
        }
        // (3) bez przelotu, 1 wp → palec. OGARNIĘTA wcześniej (INNER_TRIM + pogłębiona ≥220) → KEEP — nie oscyluj
        // TRIM↔push (N10): inaczej runda przycina pogłębiony wp z powrotem, a deepenLoop znów pogłębia → kółko.
        if (settledAreas.contains(gid)) return keep(gid, keep, Source.KEPT);
        // INNER_TRIM do wejścia −220. NIE używamy depthMeters (mierzy odległość od NAJBLIŻSZEJ granicy — palec głęboki
        // bywa blisko PRZECIWNEJ granicy, fałszywie „płytki", np. Głowno 219m od płd. granicy). „Daleko od wejścia" =
        // palec → rozstrzyga guard `hav(entry,keep)>MOVED_EPS` w applyInnerTrim. Gmina za wąska (brak entry) → zostaw.
        double[] entry = entryMap.get(gid);
        if (entry == null) return keepLog(gid, keep, "brak wejścia −220 (gmina za wąska)");
        return innerTrim(gid, entry.clone(), keep);
    }

    // ──────────────────────────────── (B) APPLY — SEKWENCYJNIE (in-place, identity) ────────────────────────────────

    /** FAZA 1: REANCHOR slice/add + INNER_TRIM route.set (cele wchodzą ZANIM coś znika). FAZA 2: collapse nadmiarowych. */
    private int applyDecisions(List<Decision> decisions, List<double[]> refTrack) {
        fromPrzelot = fromZaulek = trimCount = keptCount = 0;
        pendingRemove = new ArrayList<>();
        oldRealForFallback = refTrack;                       // P3: route niezmienione od startu rundy → reuse (bez sklejki 250k)
        fallbackHearts = null;
        int cut = 0;
        for (Decision d : decisions) {
            switch (d.kind()) {
                case REANCHOR -> {   // cut TYLKO gdy realnie przesunięto (heart≠keep) — anti-nieskończoność
                    if (applyReanchor(d)) { cut++; if (d.src() == Source.PRZELOT) fromPrzelot++; else fromZaulek++; }
                }
                case INNER_TRIM -> {   // cut TYLKO gdy entry≠keep (keep daleko od wejścia = palec); keep≈entry → KEEP
                    if (applyInnerTrim(d)) { cut++; trimCount++; trimmedThisRound.add(d.gid()); logTrim(d.gid(), d.target()); }
                    else keptCount++;
                }
                case KEEP -> keptCount++;
                case SHALLOW -> { /* tylko deepenLoop */ }
            }
        }
        collapseRedundant(decisions);
        return cut;
    }

    /** INNER_TRIM: przesuń wp palca na wejście −220 (route.set po IDENTITY + swapEntry). Reroute zrobi deepenLoop prewarm.
     *  Zwraca czy REALNIE przesunięto (`hav(entry,keep)>MOVED_EPS`) — `entry≈keep` (wp już na wejściu) → false, nie cut. */
    private boolean applyInnerTrim(Decision d) {
        int idx = GeometryUtil.identityIndexOf(route, d.keepWp());
        if (idx <= 0 || idx >= route.size() - 1) return false;
        if (GeometryUtil.hav(d.target(), d.keepWp()) <= MOVED_EPS_KM) return false;  // wp już na entry → ten sam punkt
        double[] cel = d.target().clone();
        ops.swapEntry(selected, d.keepWp(), cel, baseline);
        route.set(idx, cel);
        return true;
    }

    /** REANCHOR (1:1 reanchorGminaOnTrack): wstaw 1 wp na anchorTarget (slice na nodze) ZANIM usuniesz keep (→ pendingRemove).
     *  Zwraca czy REALNIE przesunięto (`heart≠keep`, `hav>MOVED_EPS`) — ten sam punkt → false, nie cut. */
    private boolean applyReanchor(Decision d) {
        int gid = d.gid();
        double[] heart = d.target();
        if (heart == null) {                                     // brak przejścia −220 → fallback
            if (fallbackHearts == null) fallbackHearts = gminaIndex.firstBufferEntryPoints(oldRealForFallback);
            heart = fallbackHearts.get(gid);
        }
        if (heart == null) heart = origWp.get(gid);
        if (heart == null) { log.warn("Coverage TAIL-PRUNE re-anchor: brak kotwicy gminy id={} → możliwa dziura", gid); return false; }
        if (heart == d.keepWp() || GeometryUtil.hav(heart, d.keepWp()) <= MOVED_EPS_KM) return false;  // ten sam punkt → nie cut
        UnvisitedArea entryArea = gminaIndex.findGminaForPoint(heart[0], heart[1]);
        UnvisitedArea area = (entryArea != null && entryArea.areaId() == gid) ? entryArea : idToArea.get(gid);
        if (area == null) return false;
        int centerLeg = GeometryUtil.identityIndexOf(route, d.keepWp());     // P1: keep idx = środek okna nearestLegSegment
        LegSeg ls = (entryArea != null && entryArea.areaId() == gid && centerLeg >= 0)
                ? nearestLegSegment(heart, centerLeg) : null;
        if (ls == null) { replaceWp(d.keepWp(), heart, area); return true; }  // brak nogi → podmień in-place (przesunięto)
        EdgeCache.EdgeInfo edge = edgeRouter.edge(route.get(ls.leg()), route.get(ls.leg() + 1));
        double[] heartPoint = heart.clone();
        edgeRouter.seedSlicedEdgesAtPoint(edge, route.get(ls.leg()), route.get(ls.leg() + 1), ls.seg(), heartPoint);
        route.add(ls.leg() + 1, heartPoint);
        selected.add(new SeedSel(area, heartPoint, ordering.orderKey(heartPoint), 0.0,
                GeometryUtil.minDistToBaselineKm(heartPoint, baseline)));
        pendingRemove.add(d.keepWp());                           // stary keep usunięty PO add (zero dziur)
        return true;
    }

    /** Podmień wp in-place po identity (fallback gdy reanchor-na-nodze się nie uda). */
    private void replaceWp(double[] old, double[] neu, UnvisitedArea area) {
        int idx = GeometryUtil.identityIndexOf(route, old);
        if (idx < 0) return;
        double[] np = neu.clone();
        route.set(idx, np);
        ops.swapEntry(selected, old, np, baseline);
    }

    /** Noga+segment najbliższe `heart`. P1: skanuj OKNO nóg `[centerLeg±LEG_WINDOW]` (heart=wejście gminy BLISKO keep);
     *  gdy okno chybi (heart daleko — rzadkie) → FALLBACK pełny skan. ~100-200× taniej dla FR (route 35k). */
    private LegSeg nearestLegSegment(double[] heart, int centerLeg) {
        LegSeg local = scanLegs(heart, Math.max(0, centerLeg - LEG_WINDOW),
                Math.min(route.size() - 1, centerLeg + LEG_WINDOW + 1));
        return local != null ? local : scanLegs(heart, 0, route.size() - 1);
    }

    /** Skan nóg `[fromLeg, toLeg)` — najbliższy segment do `heart` (po geometrii nóg); null gdy >50m. 1:1 102b3015. */
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

    /** FAZA 2: usuń nadmiarowe + stare keepy (po add celów) i scal prev→next (batch BRouter). 1:1 collapseDeletedSpurs. */
    private void collapseRedundant(List<Decision> decisions) {
        Set<double[]> del = Collections.newSetFromMap(new IdentityHashMap<>());
        del.addAll(pendingRemove);
        for (Decision d : decisions) del.addAll(d.redundant());
        if (del.isEmpty()) return;
        // P2: JEDEN przebieg route (rebuild skip-del) zamiast identityIndexOf per usuwany (O(route)/delete → O(route)).
        // mergedPair = (ostatni-zachowany przed blokiem usuwanych, pierwszy-zachowany po) → scala KONSEKUTYWNE usunięcia.
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

    // ──────────────────────────────── (C) POGŁĘBIANIE — push ku MIC (reroute) ────────────────────────────────

    private void deepenLoop() {
        deepenLevel.clear();
        boolean again = true;
        int iter = 0;
        while (again && iter < MAX_ITER) {
            iter++;
            edgeRouter.prewarm(route);                           // równoległy realny BRouter NOWYCH nóg (w tym INNER_TRIM)
            realTrackForPush = metrics.realGeometry(route);      // computePush (parallel) czyta deepestPointsOnTrack na nim
            Set<Integer> shallow = computeShallow(realTrackForPush);
            if (shallow.isEmpty()) break;
            for (int gid : shallow) {                            // escalate (lvl++/-1) SEKWENCYJNIE przed pushem
                int lvl = deepenLevel.getOrDefault(gid, 0);
                deepenLevel.put(gid, lvl < MAX_PUSH_LVL ? lvl + 1 : -1);
            }
            roundWp = buildRoundWp();
            List<Integer> pushGids = shallow.stream().filter(origWp::containsKey).collect(Collectors.toList());
            deepestMap = gminaIndex.deepestPointsOnTrack(realTrackForPush, new HashSet<>(pushGids));  // P4: 1× batch (nie per-gmina)
            List<Decision> pushes = edgeRouter.parallelMap(pushGids, this::computePush);   // virtual threads (T4)
            again = applyPushes(pushes);
        }
    }

    /** Gminy DOTKNIĘTE, ślad &lt;220m → pogłębiaj (ITERACJAMI tej rundy). Obejmuje INNER_TRIM gminy: jeśli po przycięciu
     *  wejście <220, push ku MIC do ≥220 — „za płytko → pogłębiaj w iteracjach, nie czekaj rundy". */
    private Set<Integer> computeShallow(List<double[]> realTrack) {
        Set<Integer> deeply = gminaIndex.deeplyVisitedAreaIds(realTrack);
        Set<Integer> shallow = new HashSet<>();
        for (int gid : gminaIndex.touchedAreaIds(realTrack))
            if (!deeply.contains(gid) && !deepAnchorAreaIds.contains(gid) && origWp.containsKey(gid)
                    && !settledAreas.contains(gid))   // ogarnięte gminy (N10) — nie pogłębiaj ponownie (anti-oscylacja)
                shallow.add(gid);
        return shallow;
    }

    /** Eskalacja pogłębiania (N9) wg `deepenLevel`: lvl1 = +80 KIERUNKOWO (entry→deepest, przedłuż wzdłuż wjazdu);
     *  lvl2 = ku MIC (gdy kierunkowo wyjechało poza gminę); lvl3 = probe obwód (jezioro/wąska); lvl<0 = restore origWp. */
    private Decision computePush(int gid) {
        double[] cur = roundWp.get(gid);
        if (cur == null) return null;
        int lvl = deepenLevel.getOrDefault(gid, 0);
        if (lvl < 0) return shallowDec(gid, origWp.get(gid), cur, Source.RESTORE);   // jezioro → przywróć (chroń ≥200)
        double[] entry = entryMap.get(gid);
        if (lvl == 1) {                                          // +80 wzdłuż kierunku wjazdu entry→czubek śladu
            double[] deepest = deepestMap.get(gid);             // P4: z batch (1× przebieg track), nie per-gmina skan
            if (entry != null && deepest != null && GeometryUtil.hav(entry, deepest) > 0.001)
                return shallowDec(gid, GeometryUtil.extendBeyond(entry, deepest, 80.0), cur, Source.PUSH);
        }
        if (lvl >= 3) {                                          // probe obwód: ślad nigdzie ≥220 (jezioro/wąska)
            double[] probe = probeSample(gid, cur);
            if (probe != null) return shallowDec(gid, probe, cur, Source.PUSH);
        }
        double[] base = entry != null ? entry : cur;            // lvl2 (i fallback) → ku MIC
        double[] cel = GeometryUtil.movePointTowards(base, gminaIndex.deepestInteriorPoint(gid), lvl * 80.0);
        return shallowDec(gid, cel, cur, Source.PUSH);
    }

    /** lvl3 probe (jezioro/wąska): obwód gminy (`samplePointsFor`) → pierwszy punkt, z którego REALNY ślad prev→s→next
     *  wchodzi ≥220m. null gdy żaden. (reuse Anchorer `bestSampleEntry`, uproszczony — pierwszy osiągnięty ≥220). */
    private double[] probeSample(int gid, double[] cur) {
        UnvisitedArea area = idToArea.get(gid);
        if (area == null) return null;
        int idx = GeometryUtil.identityIndexOf(route, cur);
        if (idx <= 0 || idx >= route.size() - 1) return null;
        double[] prev = route.get(idx - 1), next = route.get(idx + 1);
        for (double[] s : gminaIndex.samplePointsFor(area)) {
            if (edgeRouter.failedEdges().contains(GeometryUtil.edgeKey(prev, s))
                    || edgeRouter.failedEdges().contains(GeometryUtil.edgeKey(s, next))) continue;  // wjazd przez wyspę
            List<double[]> in = edgeRouter.edge(prev, s).geometry();
            List<double[]> out = edgeRouter.edge(s, next).geometry();
            List<double[]> seg = new ArrayList<>(in.size() + out.size());
            seg.addAll(in);
            seg.addAll(out);
            double[] d = gminaIndex.firstTrackPointAtDepth(seg, gid, 220.0);   // punkt NA śladzie ≥220
            if (d != null) return d;
        }
        return null;
    }

    /** route.set(cel) po IDENTITY (BEZ slice — następny prewarm policzy realnie). */
    private boolean applyPushes(List<Decision> pushes) {
        boolean changed = false;
        for (Decision d : pushes) {
            if (d.target() == null) continue;
            int idx = GeometryUtil.identityIndexOf(route, d.keepWp());
            if (idx <= 0 || idx >= route.size() - 1) continue;
            double[] cel = d.target().clone();
            ops.swapEntry(selected, d.keepWp(), cel, baseline);
            route.set(idx, cel);
            changed = true;
        }
        return changed;
    }

    // ──────────────────────────────── log + finish ────────────────────────────────

    /** Zostaw wp + log diagnostyczny CZEMU (palec nie przycięty). */
    private Decision keepLog(int gid, double[] keep, String why) {
        if (debugGeoJson) log.info("Coverage TAIL-PRUNE [{}] palec {} → ZOSTAW: {}", new Object[]{debugPhase, areaName(gid), why});
        return keep(gid, keep, Source.ORIG);
    }
    private void logTrim(int gid, double[] entry) {
        if (debugGeoJson) log.info("Coverage TAIL-PRUNE [{}] palec {} → TRIM @{}m w głąb",
                new Object[]{debugPhase, areaName(gid), Math.round(gminaIndex.depthMeters(entry, gid))});
    }
    private String areaName(int gid) {
        UnvisitedArea a = idToArea.get(gid);
        return a != null ? a.name() : "id" + gid;
    }

    /** Pomiar + log + debug PER RUNDA (warstwa -r{n}). Zwraca realny effort. */
    private double finishAndLog(int cut) {
        double realEffort = metrics.effortViaCache(route);
        List<double[]> realTrack = metrics.realGeometry(route);
        Set<Integer> dropped = new HashSet<>(visitedBefore);
        dropped.removeAll(gminaIndex.visitedAreaIds(realTrack));
        log.info("Coverage TAIL-PRUNE [{}] runda {}: cut={} (przelot={} zaulek={} trim={}) zostaw={}, dropped(≥200)={}, calls={}, effort {}->{} ({}%->{}%)",
                new Object[]{debugPhase, round, cut, fromPrzelot, fromZaulek, trimCount, keptCount, dropped.size(),
                        edgeRouter.realCalls() - callsStart, Math.round(effortBefore), Math.round(realEffort),
                        Math.round(effortBefore * 100.0 / targetEffort), Math.round(realEffort * 100.0 / targetEffort)});
        if (debugGeoJson) debug.geometry(debugPhase + "-r" + round, realTrack, route, metrics.realKm(route));
        return realEffort;
    }
}
