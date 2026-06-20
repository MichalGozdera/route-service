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
import java.util.function.Function;

/**
 * Cięcie spurów (TAIL-PRUNE v6) jako osobna klasa odpowiedzialności — jeden przebieg per wywołanie. Zawiera silnik
 * (run/runRound/runCutPass/processCandidate/...) + wszystkie helpery spurów (wykrywanie zaułków, indeks pokrycia nóg,
 * relokacja/usuwanie, anatomia). Kolaboratory z {@link SeedContext} + findGmina; stan przebiegu jako pola.
 */
final class SpurCutter {
    private static final Logger log = LoggerFactory.getLogger(SpurCutter.class);
    private static final double SLICE_SNAP_KM = 0.05;
    private static final double RETRACE_TOL_KM = 0.06;
    /** Spur (zaułek) — punkt trasy + indeks + lokalny detour (objazd) względem prostej prev→next. */
    private record Cand(double[] point, int idx, double detour) {}
    /** Indeks pokrycia nóg: dla nogi i — gminy w które wchodzi ≥220m + ile nóg per gmina. */
    private record LegCoverage(List<Set<Integer>> legGminas, Map<Integer, Integer> count) {}

    private record RelocResult(boolean ok, Set<Integer> newInCredit, Set<Integer> newOutCredit, double[][] pendingDeparture) {
        static RelocResult fail() { return new RelocResult(false, null, null, null); }
    }

    /** Per-leg: gminy w które noga i wchodzi GŁĘBOKO ≥220m (0 BRouter, z cache) + licznik nóg per gmina. */
    private LegCoverage buildLegCoverage(List<double[]> route) {
        int n = route.size();
        List<Set<Integer>> legGminas = new ArrayList<>(n - 1);
        Map<Integer, Integer> count = new HashMap<>();
        for (int i = 0; i < n - 1; i++) {
            Set<Integer> s = gminaIndex.deeplyVisitedAreaIds(edgeRouter.edge(route.get(i), route.get(i + 1)).geometry());
            legGminas.add(s);
            for (int g : s) count.merge(g, 1, Integer::sum);
        }
        return new LegCoverage(legGminas, count);
    }

    /** Kandydaci do cięcia = nie-anchor punkty trasy posortowane wg detouru (objazdu prev→cur→next) DESC. */
    private List<Cand> spurCandidatesByDetour(List<double[]> route, List<double[]> anchors) {
        int n = route.size();
        List<Cand> cands = new ArrayList<>();
        for (int i = 1; i < n - 1; i++) {
            double[] cur = route.get(i);
            if (GeometryUtil.isAnchor(cur, anchors)) continue;
            double det = edgeRouter.edge(route.get(i - 1), cur).distanceKm()
                    + edgeRouter.edge(cur, route.get(i + 1)).distanceKm()
                    - velomarker.service.planning.WaypointSelector.haversineKm(route.get(i - 1), route.get(i + 1));
            cands.add(new Cand(cur, i, det));
        }
        cands.sort((x, y) -> Double.compare(y.detour(), x.detour()));
        return cands;
    }

    /** Zaułek = ślad ZAWRACA na tym wp (eIn i eOut nakładają się, out-and-back). Przelot (ślad przechodzi) → false. */
    private boolean isDeadEnd(EdgeCache.EdgeInfo eIn, EdgeCache.EdgeInfo eOut) {
        return outAndBackDivergence(eIn, eOut) != null;
    }

    /** Czy gmina ma PRZELOT ≥220m na INNEJ nodze niż ten wp (count gmin − własny wkład tego wp > 0).
     *  True → wp jest zbędny (gmina i tak zaliczona gdzie indziej → usuń zaułek, re-anchor na przelocie). */
    private boolean hasDeepPassElsewhere(int areaId, Set<Integer> gIn, Set<Integer> gOut, Map<Integer, Integer> count) {
        int ownContribution = (gIn.contains(areaId) ? 1 : 0) + (gOut.contains(areaId) ? 1 : 0);
        return count.getOrDefault(areaId, 0) > ownContribution;
    }

    /** Wycofaj z licznika przelotów per-gmina wkład obu nóg wp idx (po usunięciu/relokacji wp na tych nogach). */
    private void clearLegsFromCount(int idx, List<Set<Integer>> legGminas, Map<Integer, Integer> count) {
        for (int g : legGminas.get(idx - 1)) count.merge(g, -1, Integer::sum);
        for (int g : legGminas.get(idx)) count.merge(g, -1, Integer::sum);
    }

    /** Po przesunięciu wp idx (relokacja): przelicz pokrycie ≥220m jego DWÓCH nóg (prev→wp, wp→next) i zaktualizuj
     *  legGminas + licznik przelotów per-gmina (wycofaj stare + dodaj nowe). Trzyma „ile nóg w gminę" spójne po cięciu. */
    private void refreshLegCoverageAround(int idx, double[] prev, double[] moved, double[] next,
                                          List<Set<Integer>> legGminas, Map<Integer, Integer> count) {
        clearLegsFromCount(idx, legGminas, count);
        Set<Integer> inDeep = gminaIndex.deeplyVisitedAreaIds(edgeRouter.edge(prev, moved).geometry());
        Set<Integer> outDeep = gminaIndex.deeplyVisitedAreaIds(edgeRouter.edge(moved, next).geometry());
        for (int g : inDeep) count.merge(g, 1, Integer::sum);
        for (int g : outDeep) count.merge(g, 1, Integer::sum);
        legGminas.set(idx - 1, inDeep);
        legGminas.set(idx, outDeep);
    }

    /** RUNDA 65: usuń zbędne zaułki (collapse prev→next) + wstaw wp na PRZELOCIE ≥220m w każdej
     *  usuniętej gminie (re-anchor; nogę znajdujemy geometrią — lokalny i daleki przelot tą samą mechaniką). */
    private void deleteSpursAndReanchor(SeedRoute seed, List<double[]> toDelete, Set<Integer> delGids, Set<double[]> stay) {
        List<double[]> route = seed.route(); List<SeedSel> selected = seed.selected(); List<double[]> anchors = seed.anchors(); List<double[]> baseline = seed.baseline();
                    List<double[][]> mergedPairs = new ArrayList<>();
                    for (double[] d : toDelete) {
                        int di = GeometryUtil.identityIndexOf(route, d);
                        if (di > 0 && di < route.size() - 1) mergedPairs.add(new double[][]{route.get(di - 1), route.get(di + 1)});
                    }
                    for (double[] d : toDelete) {
                        final double[] dd = d;
                        int di = GeometryUtil.identityIndexOf(route, dd);
                        if (di >= 0) { route.remove(di); selected.removeIf(s -> s.point() == dd); }
                    }
                    edgeRouter.setReason("ogonek-scalenie");
                    edgeRouter.prewarmPairs(mergedPairs); // scalone prev→next (batch BRouter)
                    edgeRouter.setReason("pomiar");
                    List<double[]> realTrack = metrics.realGeometry(route);
                    Map<Integer, double[]> hearts = gminaIndex.firstBufferEntryPoints(realTrack); // gmina → pierwsze −220 przelotu, RAZ
                    for (int vid : delGids) {
                        boolean hasWp = false;                          // TWARDA bramka 1 wp/gmina (zero #23)
                        for (double[] p : route) {
                            if (GeometryUtil.isAnchor(p, anchors)) continue;
                            UnvisitedArea pointArea = gminaIndex.findGminaForPoint(p[0], p[1]);
                            if (pointArea != null && pointArea.areaId() == vid) { hasWp = true; break; }
                        }
                        if (hasWp) continue;
                        double[] heart = hearts.get(vid);
                        if (heart == null) continue;                    // przelot nie wchodzi ≥220m (kryte 200-220m) → anchor nast. cyklu
                        UnvisitedArea entryArea = gminaIndex.findGminaForPoint(heart[0], heart[1]);
                        if (entryArea == null || entryArea.areaId() != vid) continue;
                        int bestLeg = -1, bestSeg = -1; double bestSD = Double.MAX_VALUE;
                        for (int j = 0; j < route.size() - 1 && bestSD > 1e-7; j++) {
                            List<double[]> g = edgeRouter.edge(route.get(j), route.get(j + 1)).geometry();
                            for (int m = 0; m < g.size() - 1; m++) {
                                double sd = GeometryUtil.pointToSegmentExactKm(heart, g.get(m), g.get(m + 1));
                                if (sd < bestSD) { bestSD = sd; bestLeg = j; bestSeg = m; if (sd <= 1e-7) break; }
                            }
                        }
                        if (bestLeg < 0 || bestSD > 0.05) continue;
                        EdgeCache.EdgeInfo bestLegEdge = edgeRouter.edge(route.get(bestLeg), route.get(bestLeg + 1));
                        double[] heartPoint = heart.clone();
                        edgeRouter.seedSlicedEdgesAtPoint(bestLegEdge, route.get(bestLeg), route.get(bestLeg + 1), bestSeg, heartPoint);
                        route.add(bestLeg + 1, heartPoint);                     // wp zaułka → NA PRZELOT (slice, 0 BRouter)
                        selected.add(new SeedSel(entryArea, heartPoint, ordering.orderKey(heartPoint), 0.0, GeometryUtil.minDistToBaselineKm(heartPoint, baseline)));
                        stay.add(heartPoint);
                    }
    }

    /** ANATOMIA spurów v6: garby ≥1 km z autorytatywnego indeksu JTS — sort po garbie DESC, cap 25.
     *  RUNDA 11: {@code phase} w logu — wołane po KAŻDEJ rundzie cięcia (cut0/1/2) + raz na końcu. */
    private void debugSpurAnatomyJts(List<double[]> route, List<double[]> anchors, Map<Integer, UnvisitedArea> idToArea, Map<double[], String> refusal, String phase) {
        int n = route.size();
        if (n < 3) return;
        List<Set<Integer>> legGminas = new ArrayList<>(n - 1);
        Map<Integer, Integer> count = new HashMap<>();
        for (int i = 0; i < n - 1; i++) {
            Set<Integer> s = gminaIndex.visitedAreaIds(
                    edgeRouter.edge(route.get(i), route.get(i + 1)).geometry());
            legGminas.add(s);
            for (int g : s) count.merge(g, 1, Integer::sum);
        }
        // v3.16 B3: które gminy są wjeżdżane z DWÓCH stron przez RÓŻNE spury (detour≥1km) → PODWÓJNY-WJAZD
        // (#149/150/151: jedna gmina, dwa wjazdy — wystarczy jeden). Mapa gmina → indeksy spur-waypointów.
        Map<Integer, Set<Integer>> gminaToSpurWps = new HashMap<>();
        for (int i = 1; i < n - 1; i++) {
            if (GeometryUtil.isAnchor(route.get(i), anchors)) continue;
            double det = edgeRouter.edge(route.get(i - 1), route.get(i)).distanceKm()
                    + edgeRouter.edge(route.get(i), route.get(i + 1)).distanceKm()
                    - velomarker.service.planning.WaypointSelector.haversineKm(route.get(i - 1), route.get(i + 1));
            if (det < 1.0) continue;
            Set<Integer> u = new HashSet<>(legGminas.get(i - 1)); u.addAll(legGminas.get(i));
            for (int gid : u) gminaToSpurWps.computeIfAbsent(gid, k -> new HashSet<>()).add(i);
        }
        record Kept(int idx, double[] pt, double detour, String gmina, String own, String refus, String blocker, String dbl, String cas) {}
        List<Kept> kept = new ArrayList<>();
        for (int i = 1; i < n - 1; i++) {
            double[] cur = route.get(i);
            if (GeometryUtil.isAnchor(cur, anchors)) continue;
            double[] prev = route.get(i - 1);
            double[] next = route.get(i + 1);
            EdgeCache.EdgeInfo eIn = edgeRouter.edge(prev, cur);
            EdgeCache.EdgeInfo eOut = edgeRouter.edge(cur, next);
            double detour = eIn.distanceKm() + eOut.distanceKm()
                    - velomarker.service.planning.WaypointSelector.haversineKm(prev, next);
            if (detour < 1.0) continue;
            Set<Integer> gIn = legGminas.get(i - 1);
            Set<Integer> gOut = legGminas.get(i);
            UnvisitedArea g = findGminaCached.apply(cur);
            boolean covered = false;
            if (g != null) {
                int contrib = (gIn.contains(g.areaId()) ? 1 : 0) + (gOut.contains(g.areaId()) ? 1 : 0);
                covered = count.getOrDefault(g.areaId(), 0) > contrib;
            }
            // v3.26 DIAGNOZA per spur (te same liczby co decyzja w tailPruneJts cands loop): exclusive,
            // kutas (out-and-back), inKw/outKw (najpłytszy wierzchołek kredytujący preserve / długość nogi).
            // PRZYPADEK: covered-loop (excl puste — usuwany w v3.26), deep-far (prsv=1, kw przy KOŃCU nogi),
            // multi (prsv≥2 / kw=-1, gminy rozjechane = ZOSTAW), kutas (powinien być ucięty).
            Set<Integer> exclusive = new HashSet<>();
            for (int gg : gIn) if (count.getOrDefault(gg, 0) <= 1 + (gOut.contains(gg) ? 1 : 0)) exclusive.add(gg);
            for (int gg : gOut) if (!gIn.contains(gg) && count.getOrDefault(gg, 0) <= 1) exclusive.add(gg);
            Set<Integer> preserve = exclusive.isEmpty()
                    ? (g != null ? Set.of(g.areaId()) : Set.<Integer>of()) : exclusive;
            boolean kutas = outAndBackDivergence(eIn, eOut) != null;
            int inKw = shallowestCoveringVertex(eIn.geometry(), preserve, gminaIndex);
            List<double[]> revOut = new ArrayList<>(eOut.geometry());
            java.util.Collections.reverse(revOut);
            int outKw = shallowestCoveringVertex(revOut, preserve, gminaIndex);
            int inN = eIn.geometry().size(), outN = eOut.geometry().size();
            // v3.27 FIX C: deep-far PRZED kutas (uczciwe etykiety). Stare Babice (inKw=361/363, refuse)
            // miał „kutas(tnij)" choć nic nie tnie — bo kutas=true, ale D nie kredytuje gminy z czubka.
            String przyp;
            if (exclusive.isEmpty()) przyp = "covered-loop(usuń)";
            else if (preserve.size() >= 2 || inKw < 0 || outKw < 0) przyp = "multi(zostaw)";
            else if ((inN > 3 && inKw >= inN - 3) || (outN > 3 && outKw >= outN - 3)) przyp = "deep-far";
            else if (kutas) przyp = "kutas(tnij)";
            else przyp = "krótko?";
            String cas = String.format(java.util.Locale.ROOT, "%s excl=%d cov=%b kutas=%b inKw=%d/%d outKw=%d/%d",
                    przyp, exclusive.size(), covered, kutas, inKw, inN, outKw, outN);
            // BLOKER: gminy legów punktu trzymane NA WYŁĄCZNOŚĆ (count−contrib<1) — to one blokują delete
            // (anatomia v3.15 pokazywała tylko headline-gminę → „głęboka-jedyna" nie mówiło CZEMU).
            Set<Integer> union = new HashSet<>(gIn); union.addAll(gOut);
            List<String> blk = new ArrayList<>();
            String dbl = "—";
            for (int gid : union) {
                int contrib = (gIn.contains(gid) ? 1 : 0) + (gOut.contains(gid) ? 1 : 0);
                if (count.getOrDefault(gid, 0) - contrib < 1) {
                    UnvisitedArea ba = idToArea.get(gid);
                    blk.add(ba != null ? ba.name() : ("id" + gid));
                }
                Set<Integer> wps = gminaToSpurWps.get(gid);
                if ("—".equals(dbl) && wps != null && wps.size() >= 2) {
                    UnvisitedArea da = idToArea.get(gid);
                    dbl = "PODWÓJNY-WJAZD(" + (da != null ? da.name() : ("id" + gid)) + ")";
                }
            }
            kept.add(new Kept(i, cur, detour, g != null ? g.name() : "?",
                    covered ? "MA-DRUGI-KONTAKT(!)" : "JEDYNY-KONTAKT", refusal.getOrDefault(cur, "-"),
                    blk.isEmpty() ? "—" : String.join("+", blk), dbl, cas));
        }
        kept.sort((x, y) -> Double.compare(y.detour(), x.detour()));
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Kept k : kept) {
            if (shown++ >= 60) break; // v3.26: cap 25→60 — pokaż też mniejsze spury (102/114/99/14/17/225)
            sb.append(String.format(java.util.Locale.ROOT, " [#%d %s | %s | %s]",
                    k.idx(), k.gmina(), k.own(), k.refus())); // RUNDA 65: tylko gmina + JEDYNY/DRUGI-KONTAKT + akcja
        }
        // RUNDA 14: loguj ZAWSZE (też 0 garbów) — żeby było widać że anatomia przebiegła po każdym cięciu.
        if (shown > 0) log.info("Coverage SPUR-ANATOMIA v6 [{}] ({} garbów ≥1km, top {}):{}",
                new Object[]{phase, kept.size(), Math.min(shown, 60), sb});
        else log.info("Coverage SPUR-ANATOMIA v6 [{}]: 0 garbów ≥1km (brak zaułków)", phase);
    }

    /**
     * RELOKACJA v3.19 — DECYZJA W JTS, noga powrotna ODROCZONA do batcha. Spłyca spur JEDYNY-KONTAKT na
     * granicę gminy: najpłytszy wierzchołek własnej nogi kredytujący WSZYSTKIE {@code exclusive}
     * ({@link #shallowestCoveringVertex}, 0 calli), slice dojazdu (0 calli), stawia waypoint. BEZ
     * effort-checku (spłycenie ZAWSZE skraca i zachowuje kredyt — dojazd-slice pokrywa exclusive z
     * konstrukcji) i BEZ routowania nogi powrotnej: tam-i-z-powrotem = slice (0 calli), loop-spur = noga
     * PENDING (caller batchuje przez {@link #prewarmPairs} — 1 równoległy strzał/pass zamiast per-tail =
     * koniec 838 strzałów). Zwraca kredyt obu nóg (B2 legGminas; pending-loop noga powrotna = estymata
     * {@code {g0}}, liczona dokładnie w kolejnym passie z cache po batchu). Mutuje route+selected.
     */
    private RelocResult relocateShallowDeferred(SeedRoute seed, Set<Integer> exclusive, double[] prev, double[] cur, double[] next,
                                                int idx, UnvisitedArea g, EdgeCache.EdgeInfo eIn, EdgeCache.EdgeInfo eOut,
                                                boolean allowReroute) {
        List<double[]> route = seed.route(); List<SeedSel> selected = seed.selected(); List<double[]> baseline = seed.baseline(); double[] baseCum = seed.baseCum();
        if (exclusive.isEmpty()) return RelocResult.fail();
        // RUNDA 59: guard RUNDA 39 (cur kredytuje −200 → fail) USUNIĘTY. Reguła 2b: jedyny-wjazd ma być DOKŁADNIE 220m —
        // za-głęboki (kredytuje −200, ale >220m) trzeba SKRÓCIĆ do 220m. Cel = firstBufferEntryPoints (220m); gdy cur już
        // ~220m → newWp≈cur → `haversineKm(newWp,cur)<0.15` (niżej) = no-op → ZOSTAW (bez churnu). Wołane TYLKO dla zaułków.
        for (int side = 0; side < 2; side++) {
            boolean inSide = side == 0;
            EdgeCache.EdgeInfo own = inSide ? eIn : eOut;
            List<double[]> walk;
            if (inSide) {
                walk = own.geometry();
            } else {
                walk = new ArrayList<>(own.geometry());
                java.util.Collections.reverse(walk);
            }
            // RUNDA 54: cel −220 (jak anchor/D-slice), NIE −200. RUNDA 39 pchała do pierwszego wierzchołka w buforze
            // −200 (≈200m) → ślad sięgał tylko 200m → w cyklu N+1 anchor (próg −220) widział muśnięcie → centroid (#117).
            // firstBufferEntryPoints zwraca punkt 220m w głąb g NA tej nodze (ta sama maszyneria co anchor); bierzemy
            // wierzchołek najbliższy → wp ~220m. null = noga nie wchodzi ≥220m w g → ta strona odpada.
            double[] deep = gminaIndex.firstBufferEntryPoints(walk).get(g.areaId());
            if (deep == null) continue;
            // RUNDA 67: wp = DOKŁADNY punkt przecięcia śladu z buforem −220 (firstBufferEntryPoints, TEN SAM co anchor),
            // NIE najbliższy wierzchołek (snap lądował POZA −220 = płytko). Slice własnej nogi W SEGMENCIE zawierającym deep.
            double[] newWp = deep.clone();
            // RUNDA 68: wp JUŻ na 220m na TEJ stronie → ZOSTAW (return fail), NIE przerzucaj na drugą (wyjazd). Inaczej
            // #169 Mława (na wjeździe) przeskakiwał na #170 (wyjazd). Genuine deep zaułek: cur głęboki ≠ cel → nie no-op.
            if (velomarker.service.planning.WaypointSelector.haversineKm(newWp, cur) < 0.15) return RelocResult.fail();
            List<double[]> ownGeom = own.geometry();
            int segOwn = -1; double bestSegSD = Double.MAX_VALUE;
            for (int m = 0; m < ownGeom.size() - 1; m++) {
                double sd = GeometryUtil.pointToSegmentExactKm(newWp, ownGeom.get(m), ownGeom.get(m + 1));
                if (sd < bestSegSD) { bestSegSD = sd; segOwn = m; }
            }
            if (segOwn < 0) continue;
            Set<Integer> newInCredit;
            Set<Integer> newOutCredit;
            double[][] pendingDeparture;
            if (inSide) {
                edgeRouter.seedSlicedEdgesAtPoint(eIn, prev, cur, segOwn, newWp); // prev→newWp DOKŁADNY punkt (0 calli)
                newInCredit = gminaIndex.visitedAreaIds(
                        edgeRouter.edge(prev, newWp).geometry()); // cache-hit
                EdgeCache.EdgeInfo dep = edgeRouter.sliceDepart(eOut, cur, next, newWp, true);
                if (dep != null) {                                              // tam-i-z-powrotem = slice (0 calli)
                    newOutCredit = gminaIndex.visitedAreaIds(dep.geometry());
                    pendingDeparture = null;
                } else if (allowReroute) {                                      // v3.30 (Q2): loop → REROUTE nogi powrotnej
                    newOutCredit = Set.of(g.areaId());                          // (1 strzał, bounded+stay przez caller)
                    pendingDeparture = new double[][]{newWp, next};
                } else continue;                                               // cap przekroczony → slice-only, fail
            } else {
                edgeRouter.seedSlicedEdgesAtPoint(eOut, cur, next, segOwn, newWp); // newWp→next DOKŁADNY punkt (0 calli)
                newOutCredit = gminaIndex.visitedAreaIds(
                        edgeRouter.edge(newWp, next).geometry()); // cache-hit
                EdgeCache.EdgeInfo dep = edgeRouter.sliceDepart(eIn, prev, cur, newWp, false);
                if (dep != null) {
                    newInCredit = gminaIndex.visitedAreaIds(dep.geometry());
                    pendingDeparture = null;
                } else if (allowReroute) {                                      // v3.30 (Q2): loop → REROUTE dojazdu (#111/#32)
                    newInCredit = Set.of(g.areaId());
                    pendingDeparture = new double[][]{prev, newWp};
                } else continue;
            }
            ops.swapEntry(selected, cur, newWp, baseline);
            route.set(idx, newWp);
            return new RelocResult(true, newInCredit, newOutCredit, pendingDeparture);
        }
        return RelocResult.fail();
    }

    /**
     * Najpłytszy wierzchołek k (>0), dla którego prefiks {@code geom[0..k]} kredytuje WSZYSTKIE
     * {@code need} (kryterium kredytu = port JTS {@code visitedAreaIds}). Monotonia (dłuższy prefiks
     * to nadzbiór segmentów = nie mniej kredytowanych gmin) → binary search, ~log(n) wywołań JTS,
     * 0 BRouter. -1 gdy pełny leg nie pokrywa {@code need}.
     */
    private static int shallowestCoveringVertex(List<double[]> geom, Set<Integer> need, GminaIndex idx) {
        if (need.isEmpty() || geom.size() < 3) return -1;
        if (!idx.visitedAreaIds(geom).containsAll(need)) return -1; // pełny leg nie pokrywa
        int lo = 1, hi = geom.size() - 2, ans = geom.size() - 2;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (idx.visitedAreaIds(geom.subList(0, mid + 1)).containsAll(need)) { ans = mid; hi = mid - 1; }
            else lo = mid + 1;
        }
        return ans;
    }

    /**
     * v3.24 GEOMETRIA „kutas": wykrywa OUT-AND-BACK — eOut wraca TĄ SAMĄ drogą co eIn (ślad wystaje
     * z linii i wraca). {@code eIn}=[prev..cur], {@code eOut}=[cur..next], cur wspólny. Liczy m = długość
     * retrace'u (eOut[k] ≈ eIn[koniec-k] dla k=1..m, tol {@value #RETRACE_TOL_KM} km). Zwraca punkt
     * ROZEJŚCIA D = eIn[koniec-m] = eOut[m] (gdzie linia przestaje wracać po sobie = ciągły ślad). D jest
     * wierzchołkiem OBU nóg → slice obu = 0 BRouter. null = brak retrace (nie there-and-back / loop).
     */
    private double[] outAndBackDivergence(EdgeCache.EdgeInfo eIn, EdgeCache.EdgeInfo eOut) {
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
        return gi.get(ni - 1 - m).clone();
    }

    private static final int REROUTE_CAP = 50;
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
    private final Map<double[], String> refusal = new java.util.IdentityHashMap<>();
    private final Set<double[]> stay = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final long callsStart;
    private final double effortBefore;
    private final Set<Integer> visitedBefore;
    private int relocated, relocSkipped, passes, pendingRerouteCount;
    // ── stan jednego cut-passa (reset w runCutPass) ──
    private List<Set<Integer>> legGminas;
    private Map<Integer, Integer> count;
    private boolean[] locked;
    private List<double[][]> relocPairs;
    private List<double[]> toDelete;
    private Set<Integer> delGids;
    private boolean changed;
    private List<String> killLog; // per-runda (null gdy !debugGeoJson)

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

    /** Rundy {pętla cut-passów → reroute} aż brak wtórniaków. Zwraca realny effort po cięciu. */
    double run() {
        int round = 0, reroutedLegs;
        do {
            reroutedLegs = runRound(round);
            round++;
        } while (reroutedLegs > 0 && round < 3);
        return finishAndLog();
    }

    /** Jedna runda: pętla cut-passów do-skutku → realny reroute sliced legów (ujawnia wtórniaki). Zwraca #reroutedLegs. */
    private int runRound(int round) {
        int relRoundStart = relocated;
        killLog = debugGeoJson ? new ArrayList<>() : null;
        Set<Integer> visBeforeRound = debugGeoJson ? gminaIndex.visitedAreaIds(metrics.realGeometry(route)) : null;
        if (debugGeoJson) debug.geometry(debugPhase + "-precut" + round, metrics.realGeometry(route), route, metrics.realKm(route));
        changed = true;
        int pass = 0;
        while (changed && pass < maxPasses + 6) {
            changed = false; pass++; passes++;
            if (route.size() < 3) break;
            runCutPass();
        }
        int reroutedLegs = edgeRouter.rerouteApproximateLegs(route);
        if (debugGeoJson) logRound(round, relRoundStart, reroutedLegs, visBeforeRound);
        return reroutedLegs;
    }

    /** Jeden cut-pass: indeks pokrycia nóg → kandydaci wg detouru → rozstrzygnij każdego → scal relokacje i usunięcia. */
    private void runCutPass() {
        LegCoverage lc = buildLegCoverage(route);
        legGminas = lc.legGminas();
        count = lc.count();
        relocPairs = new ArrayList<>();
        toDelete = new ArrayList<>();
        delGids = new HashSet<>();
        locked = new boolean[route.size()];
        for (Cand c : spurCandidatesByDetour(route, anchors)) processCandidate(c);
        if (!relocPairs.isEmpty()) {
            edgeRouter.setReason("ogonek-relokacja");
            edgeRouter.prewarmPairs(relocPairs); // nogi powrotne loop-spurów (batch)
            edgeRouter.setReason("pomiar");
        }
        if (!toDelete.isEmpty()) deleteSpursAndReanchor(seed, toDelete, delGids, stay);
    }

    /** Rozstrzygnięcie jednego kandydata wg reguł: przelot→zostaw; zaułek kryty gdzie indziej→usuń; jedyny wjazd→skróć. */
    private void processCandidate(Cand c) {
        int idx = c.idx();
        if (idx <= 0 || idx >= route.size() - 1) return;
        if (locked[idx - 1] || locked[idx] || locked[idx + 1]) return; // sąsiad usuwanego spuru — nie ruszaj w tym passie
        double[] cur = c.point();
        if (stay.contains(cur)) { refusal.put(cur, "stay-zostaw"); return; }
        double[] prev = route.get(idx - 1), next = route.get(idx + 1);
        UnvisitedArea spurArea = findGminaCached.apply(cur);
        if (spurArea == null) { refusal.put(cur, "bez-gminy"); return; }
        Set<Integer> gIn = legGminas.get(idx - 1), gOut = legGminas.get(idx);
        EdgeCache.EdgeInfo eIn = edgeRouter.edge(prev, cur);
        EdgeCache.EdgeInfo eOut = edgeRouter.edge(cur, next);
        // REGUŁA 1: przelot (ślad PRZECHODZI przez cur) → zostaw; tniemy tylko zaułki (ślad ZAWRACA).
        if (!isDeadEnd(eIn, eOut)) { refusal.put(cur, "przelot-zostaw"); return; }
        // REGUŁA 2: zaułek. Gmina kryta przelotem ≥220m GDZIE INDZIEJ → wp zbędny (usuń); inaczej jedyny wjazd (skróć).
        if (hasDeepPassElsewhere(spurArea.areaId(), gIn, gOut, count)) deleteRedundantSpur(idx, cur, spurArea);
        else shortenSoleSpur(idx, cur, prev, next, spurArea, eIn, eOut);
    }

    /** 2a: zaułek gminy krytej PRZELOTEM gdzie indziej → usuń wp (collapse prev→next); re-anchor na przelot po pętli (1 wp/gmina). */
    private void deleteRedundantSpur(int idx, double[] cur, UnvisitedArea spurArea) {
        if (killLog != null) killLog.add(String.format(java.util.Locale.ROOT, "#%d %s | DRUGI-KONTAKT | zbędny→usuń+przelot", idx, spurArea.name()));
        toDelete.add(cur);
        delGids.add(spurArea.areaId());
        clearLegsFromCount(idx, legGminas, count);
        legGminas.set(idx - 1, new HashSet<>());
        legGminas.set(idx, new HashSet<>());
        locked[idx - 1] = locked[idx] = locked[idx + 1] = true;
        relocated++; changed = true;
    }

    /** 2b: jedyny wjazd w gminę → skróć/pogłęb wp do 220m na WŁASNEJ nodze (relokacja JTS); nie da się → zostaw. */
    private void shortenSoleSpur(int idx, double[] cur, double[] prev, double[] next, UnvisitedArea spurArea,
                                 EdgeCache.EdgeInfo eIn, EdgeCache.EdgeInfo eOut) {
        RelocResult r = relocateShallowDeferred(seed, Set.of(spurArea.areaId()), prev, cur, next, idx, spurArea, eIn, eOut,
                pendingRerouteCount < REROUTE_CAP);
        if (!r.ok()) { refusal.put(cur, "jedyny-zostaw"); relocSkipped++; return; }
        if (killLog != null) { // RUNDA 67: flaga — wynik MUSI być głęboki (kredytuje −200)
            UnvisitedArea creditedArea = gminaIndex.findCreditedGminaForPoint(route.get(idx)[0], route.get(idx)[1]);
            killLog.add(String.format(java.util.Locale.ROOT, "#%d %s | JEDYNY | →220m %s", idx, spurArea.name(),
                    creditedArea != null && creditedArea.areaId() == spurArea.areaId() ? "(głęboki)" : "(PŁYTKI!)"));
        }
        relocated++; changed = true;
        refreshLegCoverageAround(idx, prev, route.get(idx), next, legGminas, count);
        if (r.pendingDeparture() != null) { relocPairs.add(r.pendingDeparture()); pendingRerouteCount++; stay.add(route.get(idx)); }
    }

    /** Log + debug-GeoJSON jednej rundy cięcia (tylko debugGeoJson). */
    private void logRound(int round, int relRoundStart, int reroutedLegs, Set<Integer> visBeforeRound) {
        debug.geometry(debugPhase + "-cut" + round, metrics.realGeometry(route), route, metrics.realKm(route));
        double roundEffort = metrics.effortViaCache(route);
        Set<Integer> visAfterRound = gminaIndex.visitedAreaIds(metrics.realGeometry(route));
        List<String> droppedRoundNames = new ArrayList<>();
        for (int g : visBeforeRound) if (!visAfterRound.contains(g)) {
            UnvisitedArea ga = idToArea.get(g);
            droppedRoundNames.add(ga != null ? ga.name() : ("id" + g));
        }
        boolean willContinue = reroutedLegs > 0 && (round + 1) < 3;
        log.info("Coverage TAIL-PRUNE v6 [{}-cut{}]: relocated={}, reloc-skipped={}, passes={}, calls={}, effort {}->{} ({}%->{}%) | runda: reloc+{}, reroute={}, dropped-runda={} {} -> {}",
                new Object[]{debugPhase, round, relocated, relocSkipped, passes, edgeRouter.realCalls() - callsStart,
                        Math.round(effortBefore), Math.round(roundEffort),
                        Math.round(effortBefore * 100.0 / targetEffort), Math.round(roundEffort * 100.0 / targetEffort),
                        relocated - relRoundStart, reroutedLegs, droppedRoundNames.size(), droppedRoundNames,
                        willContinue ? "kolejna runda" : "KONIEC petli"});
        log.info("Coverage USUNIETE-OGONKI [{}-cut{}]: {} pozycji: {}",
                new Object[]{debugPhase, round, killLog == null ? 0 : killLog.size(), killLog});
        debug.logShots(debugPhase + "-cut" + round);
        debugSpurAnatomyJts(route, anchors, idToArea, refusal, debugPhase + "-cut" + round);
    }

    /** Finalny pomiar + log podsumowania + debug. Zwraca realny effort. */
    private double finishAndLog() {
        double realEffort = metrics.effortViaCache(route);
        Set<Integer> visitedAfter = gminaIndex.visitedAreaIds(metrics.realGeometry(route));
        Set<Integer> dropped = new HashSet<>(visitedBefore); dropped.removeAll(visitedAfter);
        log.info("Coverage TAIL-PRUNE v6 (JTS-clean v2): relocated={}, reloc-skipped={}, passes={}, dropped={}, calls={}, effort {}->{} ({}%->{}%)",
                new Object[]{relocated, relocSkipped, passes, dropped.size(), edgeRouter.realCalls() - callsStart,
                        Math.round(effortBefore), Math.round(realEffort),
                        Math.round(effortBefore * 100.0 / targetEffort), Math.round(realEffort * 100.0 / targetEffort)});
        if (debugGeoJson) {
            debugSpurAnatomyJts(route, anchors, idToArea, refusal, debugPhase);
            debug.geometry(debugPhase, metrics.realGeometry(route), route, metrics.realKm(route));
        }
        return realEffort;
    }
}
