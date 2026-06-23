package velomarker.service.planning.coverage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import velomarker.entity.planning.UnvisitedArea;
import velomarker.port.out.planning.AreaPassage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Cięcie zaułków (TAIL-PRUNE) — jeden przebieg per wywołanie. Dwa mechanizmy:
 * <ul>
 *   <li><b>per-gmina:</b> gmina z PRZELOTEM (przejście −220 z wejściem i wyjściem w RÓŻNYCH miejscach granicy,
 *       chord ≥ {@link #EXIT_SEPARATION_KM}, z {@link GminaIndex#passages}) → zaułki w niej zbędne (usuń,
 *       re-anchor 1 wp na przelocie);</li>
 *   <li><b>per-wp:</b> zaułek/palec WEWNĄTRZ gminy (odnoga out-and-back, {@link #outAndBackDivergence}) → przesuń
 *       wp do punktu ROZEJŚCIA D na śladzie ciągłym (slice obu nóg, 0 BRouter), o ile ślad ciągły nadal kredytuje
 *       gminę; inaczej zostaw (jedyny głęboki dostęp).</li>
 * </ul>
 * Loguje inline w miejscu akcji. Kolaboratory z {@link SeedContext} + findGmina; stan przebiegu jako pola.
 */
final class SpurCutter {
    private static final Logger log = LoggerFactory.getLogger(SpurCutter.class);
    private static final double RETRACE_TOL_KM = 0.06;
    /**
     * Separacja wejścia↔wyjścia przejścia (km): ≥ to = ślad wyszedł INNĄ drogą = PRZELOT; bliżej = wrócił
     * TĄ SAMĄ drogą = zaułek. Próg > 0 bo ślad tam-i-z-powrotem nie pokrywa się co do piksela (~0.02–0.05).
     */
    private static final double EXIT_SEPARATION_KM = 0.08;
    /**
     * wp w tej odległości od kotwicy gminy = JEST kotwicą (na przelocie/pierwszym wejściu), nie odnoga — keeper
     * sprawdza wtedy realny snap zamiast ślepo zostawiać; dalej od kotwicy = odnoga/zaułek → cięcie.
     */
    private static final double KEEPER_EPS_KM = 0.05;

    /**
     * Punkt ROZEJŚCIA odnogi out-and-back (palca) + jego indeksy w geometriach nóg eIn/eOut
     * (D jest wierzchołkiem OBU nóg → slice obu = 0 BRouter).
     */
    private record Divergence(double[] point, int idxIn, int idxOut) {
    }

    /**
     * Noga trasy (leg) + indeks segmentu w jej geometrii.
     */
    private record LegSeg(int leg, int seg) {
    }

    private final EdgeRouter edgeRouter;
    private final RouteMetrics metrics;
    private final GminaIndex gminaIndex;
    private final HilbertOrdering ordering;
    private final List<UnvisitedArea> pool;
    private final CoverageDebug debug;
    private final SeedOps ops;
    private final boolean debugGeoJson;
    private final Function<double[], UnvisitedArea> findGminaCached;
    private final SeedRoute seed;
    private final List<double[]> route;
    private final List<double[]> anchors;
    private final double targetEffort;
    private final int maxPasses;
    private final String debugPhase;
    // ── stan całego przebiegu ──
    private final Map<Integer, UnvisitedArea> idToArea = new HashMap<>();
    private final long callsStart;
    private final double effortBefore;
    private final Set<Integer> visitedBefore;
    private int relocated, relocSkipped;
    // ── eskalacja bufora przy ustawianiu wp (220→250→300, snap-check lokalny) ──
    /**
     * Ślad wejściowy rundy (= koniec poprzedniej rundy) — źródło głębokich punktów kandydatów. Odświeżany co rundę.
     */
    private List<double[]> roundEntryTrack;
    /** Bufory eskalacji (m): 220 → 250 → 300; po wyczerpaniu caller stosuje „zostaw". */
    private static final double[] BUFORY = {220.0, 250.0, 300.0};
    /** gid → ostatni kandydat, który przeszedł snap-check ≥220m. Fallback gdy bieżąca eskalacja nie złapie
     *  (stary dobry crosspoint bywa lepszy niż „zostaw"). Przeżywa rundy — NIE resetowany w pętli run(). */
    private final Map<Integer, double[]> lastGoodAnchor = new HashMap<>();

    /** Mechanizm ustawienia wp — różni tylko ŹRÓDŁO kandydata przy eskalacji (PRZELOT: fragment przelotu). */
    private enum Mechanism {PRZELOT, FINGER}

    // ── stan jednego cut-przebiegu (reset w runCutPass) ──
    private Map<Integer, double[]> przelotAnchor; // gmina → wejście najdłuższego PRZELOTU (chord ≥ próg); brak = brak przelotu
    private Map<Integer, double[]> anchorTarget;  // gmina → kotwica (wejście przelotu, wpp. najdłuższego przejścia)
    private Map<Integer, double[][]> przelotEntryExit; // gmina → [wejście, wyjście] PRZELOTU (fragment do pogłębiania kotwicy)
    private Map<Integer, Integer> wpCountInG;     // gmina → ile NIE-anchor wp ma w tym przebiegu
    private List<double[]> toDelete;
    private Set<Integer> delGids;
    private boolean changed;

    SpurCutter(SeedContext ctx, Function<double[], UnvisitedArea> findGminaCached, SeedRoute seed,
               double targetEffort, int maxPasses, String debugPhase) {
        this.edgeRouter = ctx.edgeRouter();
        this.metrics = ctx.metrics();
        this.gminaIndex = ctx.gminaIndex();
        this.ordering = ctx.ordering();
        this.pool = ctx.pool();
        this.debug = ctx.debug();
        this.ops = ctx.ops();
        this.debugGeoJson = ctx.debugGeoJson();
        this.findGminaCached = findGminaCached;
        this.seed = seed;
        this.route = seed.route();
        this.anchors = seed.anchors();
        this.targetEffort = targetEffort;
        this.maxPasses = maxPasses;
        this.debugPhase = debugPhase;
        for (UnvisitedArea a : pool) idToArea.put(a.areaId(), a);
        edgeRouter.setReason("pomiar");
        this.callsStart = edgeRouter.realCalls();
        this.effortBefore = metrics.effortViaCache(route);
        this.visitedBefore = gminaIndex.visitedAreaIds(metrics.realGeometry(route));
    }

    /**
     * Pętla rund: JEDEN przebieg cięcia → realny BRouter reroute sliced legów → powtórz, dopóki coś cięto LUB
     * reroute zmienił geometrię (ujawnia wtórniaki). Bez wewnętrznych passów i locków. Zwraca realny effort.
     */
    double run() {
        int round = 0;
        boolean again = true;
        while (again && round < maxPasses + 9) {
            int relRoundStart = relocated;
            roundEntryTrack = metrics.realGeometry(route);                // stan WEJŚCIA rundy = źródło głębokich kandydatów
            Set<Integer> visBeforeRound = debugGeoJson ? gminaIndex.visitedAreaIds(roundEntryTrack) : null;
            if (debugGeoJson) {
                debug.geometry(debugPhase + "-precut" + round, roundEntryTrack, route, metrics.realKm(route));
            }
            changed = false;
            if (route.size() >= 3) runCutPass();                          // cięcie + eskalacja bufora w metodach akcji
            log.info("Coverage TAIL-PRUNE [{}] runda {}: cięto={}, reloc-skip={}, calls={}, effort {} ({}%)",
                    new Object[]{debugPhase, round, relocated - relRoundStart, relocSkipped,
                            edgeRouter.realCalls() - callsStart, Math.round(metrics.effortViaCache(route)),
                            Math.round(metrics.effortViaCache(route) * 100.0 / targetEffort)});
            if (debugGeoJson) logRound(round, relRoundStart, 0, visBeforeRound);
            again = changed;                                              // wtórne zaułki na nowym śladzie → kolejna runda
            round++;
        }
        return finishAndLog();
    }

    /**
     * Jeden cut-pass: przejścia gminy (przelot/zaułek) → rozstrzygnij każdy nie-anchor wp → scal usunięcia.
     */
    private void runCutPass() {
        buildPassageMaps(metrics.realGeometry(route));   // przelotAnchor / anchorTarget / wpCountInG (0 BRouter)
        toDelete = new ArrayList<>();
        delGids = new HashSet<>();
        // Lecimy przez WSZYSTKICH kandydatów (nie-anchor wp) w kolejności trasy. Przesunięcie robi route.set in-place,
        // usunięcia odroczone (toDelete → po pętli). Niespójności sąsiednich cięć koryguje kolejna runda (po reroute).
        for (int i = 1; i < route.size() - 1; i++) {
            if (GeometryUtil.isAnchor(route.get(i), anchors)) continue;
            processCandidate(i);
        }
        if (!toDelete.isEmpty()) deleteSpursAndReanchor(toDelete, delGids);
    }

    /**
     * Z przejść gminy (passages) wylicz na ten pass: {@link #przelotAnchor} (wejście najdłuższego PRZELOTU —
     * przejścia z wejściem i wyjściem oddalonymi ≥ {@link #EXIT_SEPARATION_KM}), {@link #anchorTarget} (kotwica:
     * przelot gdy jest, wpp. najdłuższe przejście) i {@link #wpCountInG} (ile nie-anchor wp ma gmina). 0 BRouter.
     */
    private void buildPassageMaps(List<double[]> realTrack) {
        Map<Integer, List<AreaPassage>> passages = gminaIndex.passages(realTrack);
        przelotAnchor = new HashMap<>();
        anchorTarget = new HashMap<>();
        przelotEntryExit = new HashMap<>();
        for (Map.Entry<Integer, List<AreaPassage>> e : passages.entrySet()) {
            List<AreaPassage> ps = e.getValue();
            if (ps.isEmpty()) continue;
            AreaPassage firstPrzelot = null;                                  // PIERWSZY przelot wzdłuż śladu (deterministyczny)
            for (AreaPassage p : ps) {
                if (p.chordKm() >= EXIT_SEPARATION_KM) {
                    firstPrzelot = p;
                    break;
                }
            }
            if (firstPrzelot != null) {
                przelotAnchor.put(e.getKey(), firstPrzelot.entry());
                przelotEntryExit.put(e.getKey(), new double[][]{firstPrzelot.entry(), firstPrzelot.exit()});
            }
            anchorTarget.put(e.getKey(), (firstPrzelot != null ? firstPrzelot : ps.get(0)).entry());
        }
        wpCountInG = new HashMap<>();
        for (int i = 1; i < route.size() - 1; i++) {
            double[] p = route.get(i);
            if (GeometryUtil.isAnchor(p, anchors)) continue;
            UnvisitedArea a = findGminaCached.apply(p);
            if (a != null) wpCountInG.merge(a.areaId(), 1, Integer::sum);
        }
    }

    /**
     * Rozstrzygnięcie kandydata: już-kotwica → zostaw; gmina z przelotem LUB ≥2 wp → usuń (re-anchor postawi 1
     * na przelocie); inaczej (gmina bez przelotu, 1 wp) → wykryj palec (out-and-back) i przytnij do punktu rozejścia D.
     */
    private void processCandidate(int idx) {
        if (idx <= 0 || idx >= route.size() - 1) return;
        double[] cur = route.get(idx);
        UnvisitedArea spurArea = findGminaCached.apply(cur);
        if (spurArea == null) return;
        int gid = spurArea.areaId();
        double[] prev = route.get(idx - 1), next = route.get(idx + 1);
        double[] target = anchorTarget.get(gid);
        if (target != null && velomarker.service.planning.WaypointSelector.haversineKm(cur, target) < KEEPER_EPS_KM) {
            // cur JEST kotwicą gminy (na przelocie/wejściu) — ale sprawdź realny snap (Nieporęt: kotwica spłycona do zaułka)
            if (deepEnoughLocally(prev, cur, next, gid)) {
                return;                                 // kotwica realnie ≥220m → zostaw
            }
            // kotwica PŁYTKA po snapie → eskaluj W MIEJSCU (nie usuwamy kotwicy, przesuwamy głębiej)
            Mechanism mech = przelotAnchor.containsKey(gid) ? Mechanism.PRZELOT : Mechanism.FINGER;
            double[] t = escalateBuffer(gid, prev, next, mech);
            if (t != null) {
                applyMoveAtPoint(idx, cur, t);
                log.info("Coverage TAIL-PRUNE [{}]: #{} {} kotwica płytka → pogłębiona (snap-check ≥220m)",
                        new Object[]{debugPhase, idx, spurArea.name()});
                return;
            }
            log.info("Coverage TAIL-PRUNE [{}]: #{} {} kotwica płytka → żaden bufor ≥220m → zostaw",
                    new Object[]{debugPhase, idx, spurArea.name()});
            relocSkipped++;
            return;
        }
        // cur NIE jest kotwicą (odnoga/zaułek): gmina z PRZELOTEM albo ≥2 wp → usuń, re-anchor postawi 1 na przelocie.
        if (przelotAnchor.containsKey(gid) || wpCountInG.getOrDefault(gid, 0) >= 2) {
            deleteRedundantSpur(idx, cur, spurArea);
            return;
        }
        // Jedyny wp, bez przelotu: zaułek/palec out-and-back → escalateBuffer(FINGER) dobiera punkt.
        EdgeCache.EdgeInfo eIn = edgeRouter.edge(prev, cur);
        EdgeCache.EdgeInfo eOut = edgeRouter.edge(cur, next);
        Divergence d = outAndBackDivergence(eIn, eOut);
        if (d == null) {                                // ślad PRZECHODZI przez cur (nie zawraca) → realne pokrycie, zostaw
            log.info("Coverage TAIL-PRUNE [{}]: #{} {} odnoga bez przelotu, nie-palec → zostaw", new Object[]{debugPhase, idx, spurArea.name()});
            return;
        }
        handleInnerSpur(idx, cur, prev, next, spurArea);   // d wykrył palec; eskalacja sama dobiera punkt
    }

    /** Sklej lokalny ślad prev→cand→next (zsnapowany BRouter) + WSTRZYKNIJ crosspoint(cand) na styku — spójnie z
     *  RouteMetrics.realGeometry, by deeplyVisitedAreaIds liczyło pokrycie na REALNYM snapie wp (nie surowych węzłach). */
    private List<double[]> localWithCrosspoint(double[] prev, double[] cand, double[] next) {
        EdgeCache.EdgeInfo eIn = edgeRouter.edge(prev, cand);
        EdgeCache.EdgeInfo eOut = edgeRouter.edge(cand, next);
        List<double[]> local = new ArrayList<>(eIn.geometry());
        double[] cp = eOut.crosspointA();                                // snap(cand) = crosspointA edge cand→next (start = jedyne pewne źródło w btools)
        if (cp != null && (local.isEmpty()
                || velomarker.service.planning.WaypointSelector.haversineKm(local.get(local.size() - 1), cp) > 0.002)) {
            local.add(cp);
        }
        List<double[]> gOut = eOut.geometry();
        for (int i = 1; i < gOut.size(); i++) local.add(gOut.get(i));
        return local;
    }

    /** Czy realny ślad lokalnie (prev→cur→next, zsnapowany przez BRouter, z crosspointem) wchodzi w gminę {@code gid} ≥220m.
     *  Cache-hit (nogi policzone w realGeometry) + JTS → tani. TO SAMO kryterium co stats/escalateBuffer (spójność). */
    private boolean deepEnoughLocally(double[] prev, double[] cur, double[] next, int gid) {
        return gminaIndex.deeplyVisitedAreaIds(localWithCrosspoint(prev, cur, next)).contains(gid);
    }

    /**
     * Zaułek gminy z przelotem (lub jeden z ≥2 wp) → oznacz do usunięcia; re-anchor po pętli postawi 1 wp na przelocie.
     */
    private void deleteRedundantSpur(int idx, double[] cur, UnvisitedArea spurArea) {
        log.info("Coverage TAIL-PRUNE [{}]: #{} {} zbędny zaułek ({}) → usuń + re-anchor na przelocie",
                new Object[]{debugPhase, idx, spurArea.name(),
                        przelotAnchor.containsKey(spurArea.areaId()) ? "gmina ma przelot" : "≥2 wp w gminie"});
        toDelete.add(cur);
        delGids.add(spurArea.areaId());
        relocated++;
        changed = true;
    }

    /**
     * Palec WEWNĄTRZ gminy (odnoga out-and-back). Eskaluj bufor 220→250→300: pierwsze wejście w bufor na nodze
     * dojazdowej, zsnapuj realnie i sprawdź czy gmina wchodzi ≥220m; pierwszy taki kandydat → przesuń wp tam (slice
     * obu nóg, odnoga znika). Żaden bufor nie złapał → ZOSTAW wp (palec = jedyny głęboki dostęp, nie tracimy gminy).
     */
    private void handleInnerSpur(int idx, double[] cur, double[] prev, double[] next, UnvisitedArea g) {
        double[] t = escalateBuffer(g.areaId(), prev, next, Mechanism.FINGER);
        if (t != null) {
            applyMoveAtPoint(idx, cur, t);
            log.info("Coverage TAIL-PRUNE [{}]: #{} {} palec → spłycony do wejścia (snap-check ≥220m)",
                    new Object[]{debugPhase, idx, g.name()});
            return;
        }
        log.info("Coverage TAIL-PRUNE [{}]: #{} {} palec: żaden bufor nie złapał ≥220m → zostaw",
                new Object[]{debugPhase, idx, g.name()});
        relocSkipped++;
    }

    /**
     * Eskalacja bufora 220→250→300 dla gminy {@code gid}: dla każdego bufora wybierz GEOMETRYCZNY kandydat (przelot →
     * fragment entry↔exit; inaczej pierwsze wejście), ZSNAPUJ realnie ({@code edge(prev,cand)} + {@code edge(cand,next)})
     * i sprawdź LOKALNIE, czy gmina wchodzi ≥220m. Zwraca pierwszy kandydat kredytujący po snapie; {@code null} gdy
     * żaden (caller stosuje „zostaw"). Snap-check lokalny — snap wp zależy od jego pozycji, nie od dalekich wp.
     */
    private double[] escalateBuffer(int gid, double[] prev, double[] next, Mechanism mech) {
        double[][] ee = przelotEntryExit.get(gid);
        for (double bufor : BUFORY) {
            double[] cand = mech == Mechanism.PRZELOT
                    ? (ee != null ? gminaIndex.firstTrackPointAtDepthBetween(roundEntryTrack, gid, bufor, ee[0], ee[1]) : null)
                    : gminaIndex.firstTrackPointAtDepth(roundEntryTrack, gid, bufor);
            if (cand == null) continue;
            double[] cp = edgeRouter.edge(cand, next).crosspointA();        // snap(cand) = crosspointA edge cand→next (jedyne pewne źródło snapu w btools)
            edgeRouter.edge(prev, cand);                                    // druga noga (cache, spójność realGeometry)
            if (cp != null && gminaIndex.depthMeters(cp, gid) >= 220.0) {   // crosspoint dostatecznie głęboko → PODMIANA na crosspoint
                lastGoodAnchor.put(gid, cp.clone());
                return cp;
            }
        }
        // żaden bufor nie dał crosspointu ≥220 → fallback: ostatni dobry crosspoint z poprzedniej rundy (recheck na BIEŻĄCYCH prev/next)
        double[] prev2 = lastGoodAnchor.get(gid);
        if (prev2 != null) {
            double[] cp2 = edgeRouter.edge(prev2, next).crosspointA();
            edgeRouter.edge(prev, prev2);
            if (cp2 != null && gminaIndex.depthMeters(cp2, gid) >= 220.0) return cp2;
        }
        return null;
    }

    /** Ustaw wp gminy w punkcie {@code target}: swap w selected + route.set. Nogi prev→target, target→next już policzone
     *  REALNIE w {@link #escalateBuffer} → cache-hit przy realGeometry, bez redundantnego slice. */
    private void applyMoveAtPoint(int idx, double[] cur, double[] target) {
        double[] tp = target.clone();
        ops.swapEntry(seed.selected(), cur, tp, seed.baseline());
        route.set(idx, tp);
        relocated++;
        changed = true;
    }

    /**
     * Indeks segmentu geometrii najbliższego punktowi {@code pt} (−1 gdy geometria pusta).
     */
    private int nearestSegment(List<double[]> geom, double[] pt) {
        int seg = -1;
        double best = Double.MAX_VALUE;
        for (int m = 0; m < geom.size() - 1; m++) {
            double sd = GeometryUtil.pointToSegmentExactKm(pt, geom.get(m), geom.get(m + 1));
            if (sd < best) {
                best = sd;
                seg = m;
            }
        }
        return seg;
    }

    /**
     * Wykrywa OUT-AND-BACK (palec): {@code eOut} wraca TĄ SAMĄ drogą co {@code eIn} (odnoga wystaje i wraca).
     * {@code eIn}=[prev..cur], {@code eOut}=[cur..next], cur wspólny. Liczy długość retrace'u (eOut[k] ≈
     * eIn[koniec-k], tol {@value #RETRACE_TOL_KM} km) i zwraca punkt ROZEJŚCIA D (gdzie ślad przestaje wracać po
     * sobie = ślad ciągły) wraz z jego indeksami w obu nogach. {@code null} = brak retrace (ślad przechodzi / pętla).
     */
    private Divergence outAndBackDivergence(EdgeCache.EdgeInfo eIn, EdgeCache.EdgeInfo eOut) {
        List<double[]> gi = eIn.geometry();
        List<double[]> go = eOut.geometry();
        int ni = gi.size(), no = go.size();
        if (ni < 3 || no < 3) return null;
        int m = 0;
        while (m + 1 <= ni - 2 && m + 1 <= no - 2
                && velomarker.service.planning.WaypointSelector.haversineKm(gi.get(ni - 2 - m), go.get(m + 1)) <= RETRACE_TOL_KM) {
            m++;
        }
        if (m == 0) return null;
        return new Divergence(gi.get(ni - 1 - m).clone(), ni - 1 - m, m);
    }

    /**
     * Usuwa zbędne zaułki i stawia 1 wp na przelocie w każdej zwolnionej gminie. KOLEJNOŚĆ: (A) re-anchor na
     * STAREJ geometrii (wciąż z zaułkami = realne pokrycie) → (B) usuń zaułki → (C) scal prev→next. Dzięki temu
     * kotwica ≥220m powstaje ZANIM cokolwiek zniknie → zero dziur. Nowe zaułki ze scalenia łapie kolejny pass.
     */
    private void deleteSpursAndReanchor(List<double[]> toDelete, Set<Integer> delGids) {
        Set<double[]> toDeleteSet = identitySet(toDelete);
        List<double[]> oldRealTrack = metrics.realGeometry(route);
        Map<Integer, double[]> fallbackHearts = null;               // lazy: firstBufferEntryPoints tylko gdy brak kotwicy
        for (int vid : delGids) {
            if (gminaAlreadyHasKeeperWp(vid, toDeleteSet))
                continue; // gmina ma już wp który zostaje → bramka 1 wp/gmina
            double[] heart = anchorTarget.get(vid);                  // kotwica = wejście przelotu (policzona w runCutPass)
            if (heart == null) {                                     // brak przejścia −220 (płytka gmina) → fallback
                if (fallbackHearts == null) fallbackHearts = gminaIndex.firstBufferEntryPoints(oldRealTrack);
                heart = fallbackHearts.get(vid);
            }
            if (heart == null) {                                     // gmina nie wchodzi ≥200m nigdzie → nie da się
                log.warn("Coverage TAIL-PRUNE re-anchor: brak kotwicy dla gminy id={} → możliwa dziura", vid);
                continue;
            }
            reanchorGminaOnTrack(vid, heart);
        }
        collapseDeletedSpurs(toDelete);
    }

    /**
     * Identity-set (porównanie po referencji {@code double[]}, nie equals).
     */
    private static Set<double[]> identitySet(List<double[]> pts) {
        Set<double[]> s = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        s.addAll(pts);
        return s;
    }

    /**
     * Czy gmina {@code vid} ma już wp KTÓRY ZOSTAJE (nie-anchor, nie do usunięcia) → bramka 1 wp/gmina.
     */
    private boolean gminaAlreadyHasKeeperWp(int vid, Set<double[]> toDeleteSet) {
        for (double[] p : route) {
            if (GeometryUtil.isAnchor(p, anchors)) continue;
            if (toDeleteSet.contains(p)) continue;
            UnvisitedArea a = gminaIndex.findGminaForPoint(p[0], p[1]);
            if (a != null && a.areaId() == vid) return true;
        }
        return false;
    }

    /**
     * Noga+segment trasy najbliższe punktowi {@code heart} (po pełnej geometrii nóg); {@code null} gdy >50m od śladu.
     */
    private LegSeg nearestLegSegment(double[] heart) {
        int bestLeg = -1, bestSeg = -1;
        double bestSD = Double.MAX_VALUE;
        for (int j = 0; j < route.size() - 1 && bestSD > 1e-7; j++) {
            List<double[]> g = edgeRouter.edge(route.get(j), route.get(j + 1)).geometry();
            for (int m = 0; m < g.size() - 1; m++) {
                double sd = GeometryUtil.pointToSegmentExactKm(heart, g.get(m), g.get(m + 1));
                if (sd < bestSD) {
                    bestSD = sd;
                    bestLeg = j;
                    bestSeg = m;
                    if (sd <= 1e-7) break;
                }
            }
        }
        return (bestLeg < 0 || bestSD > 0.05) ? null : new LegSeg(bestLeg, bestSeg);
    }

    /**
     * Wstawia 1 wp gminy {@code vid} na przelocie. Kandydat startowy {@code heart220} (wejście przelotu −220) ESKALUJE
     * bufor 220→250→300 ze snap-checkiem: jeśli BRouter zsnapuje −220 za płytko (<220m w gminie), przesuwa kotwicę
     * głębiej w przelot. Gdy żaden bufor nie złapie → zostaje −220 (przelot i tak kredytuje). Slice na nodze (0 BRouter,
     * realny przebieg policzony w {@link #escalateBuffer}).
     */
    private void reanchorGminaOnTrack(int vid, double[] heart220) {
        UnvisitedArea entryArea = gminaIndex.findGminaForPoint(heart220[0], heart220[1]);
        if (entryArea == null || entryArea.areaId() != vid) return;
        LegSeg ls = nearestLegSegment(heart220);
        if (ls == null) return;
        double[] prev = route.get(ls.leg()), next = route.get(ls.leg() + 1);
        double[] heart = escalateBuffer(vid, prev, next, Mechanism.PRZELOT);   // 220→250→300, snap-check
        if (heart == null) heart = heart220;                                   // żaden bufor → zostaw kotwicę −220
        EdgeCache.EdgeInfo edge = edgeRouter.edge(prev, next);
        double[] heartPoint = heart.clone();
        int seg = nearestSegment(edge.geometry(), heartPoint);
        if (seg < 0) seg = ls.seg();
        edgeRouter.seedSlicedEdgesAtPoint(edge, prev, next, seg, heartPoint);
        route.add(ls.leg() + 1, heartPoint);
        seed.selected().add(new SeedSel(entryArea, heartPoint, ordering.orderKey(heartPoint), 0.0,
                GeometryUtil.minDistToBaselineKm(heartPoint, seed.baseline())));
        log.info("Coverage TAIL-PRUNE [{}]: re-anchor gminy {} na przelocie (snap-check ≥220m, slice noga {})",
                new Object[]{debugPhase, entryArea.name(), ls.leg()});
    }

    /**
     * (B)+(C): usuń zaułki po identyczności (zbierając AKTUALNYCH sąsiadów do scalenia — wstawiony anchor mógł
     * stać się nowym sąsiadem) i scal prev→next (batch BRouter).
     */
    private void collapseDeletedSpurs(List<double[]> toDelete) {
        List<SeedSel> selected = seed.selected();
        List<double[][]> mergedPairs = new ArrayList<>();
        for (double[] d : toDelete) {
            final double[] dd = d;
            int di = GeometryUtil.identityIndexOf(route, dd);
            if (di < 0) continue;
            if (di > 0 && di < route.size() - 1) mergedPairs.add(new double[][]{route.get(di - 1), route.get(di + 1)});
            route.remove(di);
            selected.removeIf(s -> s.point() == dd);
        }
        edgeRouter.setReason("ogonek-scalenie");
        edgeRouter.prewarmPairs(mergedPairs);
        edgeRouter.setReason("pomiar");
    }

    /**
     * Log + debug-GeoJSON jednej rundy cięcia (tylko debugGeoJson).
     */
    private void logRound(int round, int relRoundStart, int reroutedLegs, Set<Integer> visBeforeRound) {
        debug.geometry(debugPhase + "-cut" + round, metrics.realGeometry(route), route, metrics.realKm(route));
        double roundEffort = metrics.effortViaCache(route);
        Set<Integer> visAfterRound = gminaIndex.visitedAreaIds(metrics.realGeometry(route));
        List<String> droppedRoundNames = new ArrayList<>();
        for (int g : visBeforeRound) {
            if (!visAfterRound.contains(g)) {
                UnvisitedArea ga = idToArea.get(g);
                droppedRoundNames.add(ga != null ? ga.name() : ("id" + g));
            }
        }
        log.info("Coverage TAIL-PRUNE [{}-cut{}]: relocated={}, reloc-skipped={}, calls={}, effort {}->{} ({}%->{}%) | runda: reloc+{}, reroute={}, dropped-runda={} {}",
                new Object[]{debugPhase, round, relocated, relocSkipped, edgeRouter.realCalls() - callsStart,
                        Math.round(effortBefore), Math.round(roundEffort),
                        Math.round(effortBefore * 100.0 / targetEffort), Math.round(roundEffort * 100.0 / targetEffort),
                        relocated - relRoundStart, reroutedLegs, droppedRoundNames.size(), droppedRoundNames});
        debug.logShots(debugPhase + "-cut" + round);
    }

    /**
     * Finalny pomiar + log podsumowania + debug. Zwraca realny effort.
     */
    private double finishAndLog() {
        double realEffort = metrics.effortViaCache(route);
        Set<Integer> visitedAfter = gminaIndex.visitedAreaIds(metrics.realGeometry(route));
        Set<Integer> dropped = new HashSet<>(visitedBefore);
        dropped.removeAll(visitedAfter);
        log.info("Coverage TAIL-PRUNE (podsumowanie): relocated={}, reloc-skipped={}, dropped={}, calls={}, effort {}->{} ({}%->{}%)",
                new Object[]{relocated, relocSkipped, dropped.size(), edgeRouter.realCalls() - callsStart,
                        Math.round(effortBefore), Math.round(realEffort),
                        Math.round(effortBefore * 100.0 / targetEffort), Math.round(realEffort * 100.0 / targetEffort)});
        if (debugGeoJson) {
            debug.geometry(debugPhase, metrics.realGeometry(route), route, metrics.realKm(route));
        }
        return realEffort;
    }
}
