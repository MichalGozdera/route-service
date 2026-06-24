package velomarker.service.planning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import velomarker.entity.RouteSpan;
import velomarker.entity.planning.RoutePreferences;
import velomarker.entity.planning.UnvisitedArea;
import velomarker.entity.planning.Waypoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

/**
 * Wybór gmin do pokrycia względem trasy bazowej (greedy): scoring gminy, dedup po wzajemnym pokryciu,
 * trim budżetowy, budowa waypointów z wybranych + helpery geometryczne (przecięcia ring/segment).
 * Bezstanowe, czyste funkcje wydzielone z PlanningOrchestrationService.
 */
final class CoverageAreaSelection {

    private CoverageAreaSelection() {}

    private static final Logger log = LoggerFactory.getLogger(CoverageAreaSelection.class);
    /**
     * Liczy dla gminy: czy bazowa już ją przecina, indeks insertion, distance, entry point ~50m
     * za granicę w stronę bazowej. Entry point używamy w final BRouter — minimalny detour zamiast
     * jazdy do centroidu.
     */
    static AreaCandidate scoreAreaAgainstBaseline(UnvisitedArea area, List<double[]> baselineGeom, boolean intersected) {
        double[] centroid = {area.lng(), area.lat()};
        // Najbliższy punkt bazowej do centroidu (z indeksem). Skanujemy co `step`-ty punkt — dla
        // ~30k-punktowego śladu nie trzeba precyzji 50 m (liczymy „rejon korytarza + jak daleko w bok”),
        // a pełny skan per gmina × tysiące gmin był najcięższym kosztem. nearestIdx ZOSTAJE indeksem
        // PEŁNEJ geometrii (interleave porównuje go z findNearestGeomIdx na pełnym śladzie).
        int step = Math.max(1, baselineGeom.size() / 3000);
        double minDist = Double.MAX_VALUE;
        int nearestIdx = 0;
        for (int i = 0; i < baselineGeom.size(); i += step) {
            double[] p = baselineGeom.get(i);
            double d = WaypointSelector.haversineKm(p, centroid);
            if (d < minDist) {
                minDist = d;
                nearestIdx = i;
            }
        }
        double[] nearestPoint = baselineGeom.get(nearestIdx);

        // intersected (czy baseline JUŻ przechodzi przez gminę) liczone JEDNYM przejściem JTS
        // (visitedAreaIds, depth-aware ≥200m) w scoreCandidates i wstrzykiwane tutaj — zamiast
        // ręcznego point-in-ring ±300 per gmina.

        // Entry point: ~150 m do wewnatrz ringa, w strone nearestPoint od najblizszego ring point.
        // Bylo 50m -- BRouter czesto slizgal sie wzdluz granicy ringa i zawracal bez wjazdu
        // (user widzial "wjazd pod granice i chuj"). 150m = bezpieczna glebokosc zaliczenia.
        // Limit 1/2 dystansu ringPt->centroid: dla bardzo malych gmin (<300m promienia) nie
        // wciskaj punktu za centroid w naprzeciwlegly rog.
        double entryLng = area.lng();
        double entryLat = area.lat();
        if (area.ring() != null && area.ring().length >= 3) {
            double[] ringPt = PlanningGeom.closestRingPoint(area.ring(), nearestPoint);
            double dxLng = centroid[0] - ringPt[0];
            double dyLat = centroid[1] - ringPt[1];
            double len = Math.sqrt(dxLng * dxLng + dyLat * dyLat);
            if (len > 1e-9) {
                // 0.0015 deg ~ 150 m. Cap na ½ dystansu do centroidu (małe gminy).
                double rawStepDeg = Math.min(0.0015, len / 2.0);
                double stepDeg = rawStepDeg / len;
                entryLng = ringPt[0] + dxLng * stepDeg;
                entryLat = ringPt[1] + dyLat * stepDeg;
            } else {
                entryLng = ringPt[0];
                entryLat = ringPt[1];
            }
        }

        double detour = intersected ? 0 : (2 * minDist + 0.2);
        return new AreaCandidate(area, intersected, nearestIdx, detour, entryLng, entryLat);
    }




    /** Trim: wyrzuć `fraction` najdroższych (po detour DESC) z {@code from} do {@code dropped}. */
    static void trimByDetourFromCurrent(List<AreaCandidate> from, List<AreaCandidate> dropped, double fraction) {
        if (from.isEmpty()) return;
        var sorted = new ArrayList<>(from);
        sorted.sort((a, b) -> Double.compare(b.getDetourStraightKm(), a.getDetourStraightKm()));
        int toDrop = Math.max(1, (int) Math.round(sorted.size() * fraction));
        toDrop = Math.min(toDrop, sorted.size());
        for (int i = 0; i < toDrop; i++) {
            AreaCandidate c = sorted.get(i);
            from.remove(c);
            dropped.add(c);
        }
    }

    static List<AreaCandidate> sortByInsertionIdx(List<AreaCandidate> list) {
        var sorted = new ArrayList<>(list);
        sorted.sort((a, b) -> Integer.compare(a.getInsertionIdx(), b.getInsertionIdx()));
        return sorted;
    }

    /** Wyrzuca {@code dropFraction} najdroższych gmin (po detourStraightKm DESC) i buduje nową listę waypointów. */
    static List<Waypoint> trimExpensiveGminy(RoutePreferences prefs, PlanningOrchestrationService.CoverageBuildInfo info, double dropFraction) {
        var picked = new ArrayList<>(info.pickedCandidates());
        // Sort po detourStraightKm DESC, weź top `dropFraction` → wyrzuć.
        picked.sort((a, b) -> Double.compare(b.getDetourStraightKm(), a.getDetourStraightKm()));
        int toDrop = (int) Math.round(picked.size() * dropFraction);
        toDrop = Math.min(toDrop, picked.size());
        List<AreaCandidate> kept = new ArrayList<>(picked.subList(toDrop, picked.size()));
        kept.sort((a, b) -> Integer.compare(a.getInsertionIdx(), b.getInsertionIdx()));
        return buildWaypointsFromPicked(prefs, kept, info.baselineGeometry());
    }

    /**
     * In-place: usuwa z {@code picked} kandydatów których ring jest INTERSECTED przez nową geometrię
     * (BRouter naturalnie przez nich przejeżdża → entry-point zbędny). Bez tego dedup tylko wycina
     * waypoints ale picked zachowuje wyrzucone → następny grow je przywraca.
     */
    static void removeNaturallyCoveredFromPicked(List<AreaCandidate> picked, List<double[]> newGeometry) {
        if (picked.isEmpty() || newGeometry.isEmpty()) return;
        List<AreaCandidate> toRemove = new ArrayList<>();
        for (AreaCandidate c : picked) {
            if (c.isIntersected()) continue;
            if (isAreaCoveredByGeometry(c.getArea(), newGeometry)) {
                toRemove.add(c);
            }
        }
        picked.removeAll(toRemove);
    }

    /**
     * Czy ring obszaru jest przeciety przez ktorykolwiek punkt {@code geometry} (point-in-ring
     * w oknie +-300 punktow wokol najblizszego do centroidu)? Reuzywane w {@code removeNaturallyCoveredFromPicked}.
     */
    static boolean isAreaCoveredByGeometry(UnvisitedArea area, List<double[]> geometry) {
        if (area == null || geometry == null || geometry.isEmpty()) return false;
        double[][] ring = area.ring();
        if (ring == null || ring.length < 3) return false;
        int near = PlanningGeom.findNearestGeomIdx(geometry, new double[]{area.lng(), area.lat()});
        int from = Math.max(0, near - 300);
        int to = Math.min(geometry.size(), near + 300);
        for (int i = from; i < to; i++) {
            if (WaypointSelector.pointInRing(geometry.get(i), ring)) {
                return true;
            }
        }
        return false;
    }

    /** Buduje finalną listę waypointów [start, picked entry-points by insertionIdx, via, ..., end] z `picked`. */
    static List<Waypoint> buildWaypointsFromPicked(RoutePreferences prefs, List<AreaCandidate> picked,
                                                   List<double[]> baselineGeom) {
        boolean loop = Boolean.TRUE.equals(prefs.loop());
        List<Waypoint> anchorWps = new ArrayList<>();
        anchorWps.add(prefs.start());
        if (prefs.via() != null) anchorWps.addAll(prefs.via());
        if (loop) anchorWps.add(prefs.start());
        else if (prefs.end() != null) anchorWps.add(prefs.end());
        else anchorWps.add(prefs.start());

        int[] anchorIndices = new int[anchorWps.size()];
        for (int i = 0; i < anchorWps.size(); i++) {
            anchorIndices[i] = PlanningGeom.findNearestGeomIdx(baselineGeom, anchorWps.get(i).toLngLat());
        }
        List<Waypoint> result = new ArrayList<>();
        result.add(anchorWps.get(0));
        int pickedPtr = 0;
        for (int ai = 1; ai < anchorWps.size(); ai++) {
            int anchorIdx = anchorIndices[ai];
            while (pickedPtr < picked.size() && picked.get(pickedPtr).getInsertionIdx() <= anchorIdx) {
                AreaCandidate c = picked.get(pickedPtr);
                // Skip entry-point dla ZWYKLYCH gmin/powiatow z intersected=true (baseline przez ring).
                // BRouter naturalnie przejdzie -- dodatkowy waypoint powoduje objazd.
                // ALE: dla SPECIAL GROUPS (np. kreissitz = konkretne miasto-stolica) -- ZAWSZE
                // dodajemy entry-point. Special group requires precyzyjne przejscie przez centrum,
                // nie tylko musnięcie ringa. Bez tego: kreissitz Chemnitz intersected -> pominięty
                // -> BRouter idzie obok przez wioskę -> kreissitz NIE zaliczony.
                // Iter 9 Fix #1: skip mutually-covered (trasa BRouter naturalnie przejdzie przez ring sąsiada).
                if ((!c.isIntersected() || c.area.isSpecial()) && !c.isMutuallyCoveredByNeighbor()) {
                    result.add(new Waypoint(c.getEntryLng(), c.getEntryLat(), c.area.name()));
                }
                pickedPtr++;
            }
            result.add(anchorWps.get(ai));
        }
        while (pickedPtr < picked.size()) {
            AreaCandidate c = picked.get(pickedPtr);
            if (!c.isIntersected() || c.area.isSpecial()) {
                result.add(new Waypoint(c.getEntryLng(), c.getEntryLat(), c.area.name()));
            }
            pickedPtr++;
        }
        return result;
    }

    /**
     * Wzajemny dedup PRZED BRouter: jeśli entry-point gminy B leży w ringu gminy A (lub odwrotnie),
     * BRouter idąc do B i tak przejdzie przez A. Wyrzucamy A — zostaje tylko B. Plus: jeśli straight
     * line między dwoma SĄSIEDNIMI (po insertionIdx) entry-pointami przecina ring trzeciej gminy C
     * która leży POMIĘDZY — wyrzucamy C (BRouter pewnie przejdzie przez nią naturalnie).
     *
     * <p>Eliminuje user-widoczne pętelki "wjazd do gminy z 3 stron" przez REDUKCJĘ liczby entry-pointów
     * w ciasno upakowanym klastrze sąsiednich gmin.
     */
    static List<AreaCandidate> dedupByMutualCoverage(List<AreaCandidate> picked) {
        if (picked.size() < 2) return new ArrayList<>(picked);
        Set<Integer> toRemove = new HashSet<>();
        // Pass 1: entry-point sąsiada wewnątrz ringa.
        for (int i = 0; i < picked.size(); i++) {
            if (toRemove.contains(i)) continue;
            AreaCandidate ci = picked.get(i);
            double[][] ring = ci.getArea().ring();
            if (ring == null || ring.length < 3) continue;
            for (int j = 0; j < picked.size(); j++) {
                if (j == i || toRemove.contains(j)) continue;
                AreaCandidate cj = picked.get(j);
                double[] cjEntry = {cj.getEntryLng(), cj.getEntryLat()};
                if (WaypointSelector.pointInRing(cjEntry, ring)) {
                    // cj.entry ∈ ci.ring → BRouter idąc do cj i tak przejdzie przez ci.
                    // Preferencje wyboru ktore zachowac:
                    //   1) Zachowaj SPECIAL (kreissitz musi byc explicit -- centrum miasta, nie brzeg)
                    //   2) W razie remisu specjala -- zachowaj z mniejszym detourStraightKm
                    int toDrop;
                    boolean iSpecial = ci.area.isSpecial();
                    boolean jSpecial = cj.area.isSpecial();
                    if (iSpecial && !jSpecial) toDrop = j;
                    else if (!iSpecial && jSpecial) toDrop = i;
                    else toDrop = ci.getDetourStraightKm() > cj.getDetourStraightKm() ? i : j;
                    toRemove.add(toDrop);
                    if (toDrop == i) break;
                }
            }
        }
        // Pass 2: gmina k leżąca POMIĘDZY dwoma innymi (segm i→j przecina k.ring).
        // Sortuj NIE-usunięte po insertionIdx żeby sprawdzić sąsiednie pary.
        List<Integer> remaining = new ArrayList<>();
        for (int i = 0; i < picked.size(); i++) {
            if (!toRemove.contains(i)) remaining.add(i);
        }
        remaining.sort((a, b) -> Integer.compare(picked.get(a).getInsertionIdx(), picked.get(b).getInsertionIdx()));
        for (int r = 0; r < remaining.size() - 1; r++) {
            int idx1 = remaining.get(r);
            int idx2 = remaining.get(r + 1);
            AreaCandidate c1 = picked.get(idx1);
            AreaCandidate c2 = picked.get(idx2);
            double[] p1 = {c1.getEntryLng(), c1.getEntryLat()};
            double[] p2 = {c2.getEntryLng(), c2.getEntryLat()};
            // Czy któraś INNA pre-picked gmina ma ring przecięty przez segment p1-p2?
            for (int k = 0; k < picked.size(); k++) {
                if (k == idx1 || k == idx2 || toRemove.contains(k)) continue;
                AreaCandidate ck = picked.get(k);
                if (ck.isIntersected()) continue; // free → already counted
                double[][] ring = ck.getArea().ring();
                if (ring == null || ring.length < 3) continue;
                if (PlanningGeom.segmentIntersectsRing(p1, p2, ring)) {
                    toRemove.add(k);
                }
            }
        }
        // Iter 9 Fix #1: NIE usuwamy areas z listy. Zostają w `picked` (= raportowane jako
        // zaliczone), ale SETujemy flagę `mutuallyCoveredByNeighbor=true`. Tour builder SKIPS
        // tych przy generowaniu waypointów. User widzi je jako zaliczone (różowe gminy)
        // mimo że nie ma dla nich explicit waypoint w trasie. Pre-iter9: areas były wyrzucane
        // z picked → user widział je jako BIAŁE dziury.
        for (int idx : toRemove) {
            picked.get(idx).setMutuallyCoveredByNeighbor(true);
        }
        return picked;
    }

    /**
     * Po pierwszym BRouter sprawdza które entry-points gmin są ZBĘDNE — bo ich gmina jest naturalnie
     * przecięta przez nową trasę. Te entry-pointy wyrzucamy (START/VIA/END usera zostają). Pozwala BRouter
     * narysować trasę bez pętelek wokół tej samej gminy z trzech stron.
     */
    static List<Waypoint> removeNaturallyCoveredEntries(RoutePreferences prefs, PlanningOrchestrationService.CoverageBuildInfo info,
                                                        List<double[]> newGeometry, List<Waypoint> currentWps) {
        Set<String> droppableEntryNames = droppableEntryKeys(info, newGeometry);
        if (droppableEntryNames.isEmpty()) return currentWps;
        Set<String> userAnchorNames = userAnchorNames(prefs);
        List<Waypoint> kept = new ArrayList<>(currentWps.size());
        int dropped = 0;
        // Iteruj currentWps; START + USER VIA + END muszą zostać. Gminy entry-pointy o nazwie
        // znajdującej się w droppableEntryNames → wyrzucamy.
        for (Waypoint w : currentWps) {
            if (w.name() != null && !userAnchorNames.contains(w.name()) && isDroppable(w, droppableEntryNames)) {
                dropped++;
                continue;
            }
            kept.add(w);
        }
        log.info("Dedup analysis: {} entry-points naturally covered, {} actually dropped from waypoints",
                new Object[]{droppableEntryNames.size(), dropped});
        return kept;
    }

    /** Klucze „name@insertionIdx" gmin, których ring jest przecięty przez {@code newGeometry} (entry-point zbędny, okno ±300). */
    private static Set<String> droppableEntryKeys(PlanningOrchestrationService.CoverageBuildInfo info, List<double[]> newGeometry) {
        Set<String> droppableEntryNames = new HashSet<>();
        for (AreaCandidate c : info.pickedCandidates()) {
            if (c.area.ring() == null || c.area.ring().length < 3) continue;
            // newGeometry ma INNE indeksy niż baselineGeometry, więc szukamy najbliższego punktu i okna ±300.
            int near = PlanningGeom.findNearestGeomIdx(newGeometry, new double[]{c.area.lng(), c.area.lat()});
            int from = Math.max(0, near - 300);
            int to = Math.min(newGeometry.size(), near + 300);
            for (int i = from; i < to; i++) {
                if (WaypointSelector.pointInRing(newGeometry.get(i), c.area.ring())) {
                    droppableEntryNames.add(c.area.name() + "@" + c.getInsertionIdx());
                    break;
                }
            }
        }
        return droppableEntryNames;
    }

    /** Nazwy waypointów usera (start/via/end) — nigdy nie wyrzucane przy dedupie. */
    private static Set<String> userAnchorNames(RoutePreferences prefs) {
        Set<String> names = new HashSet<>();
        if (prefs.start() != null && prefs.start().name() != null) names.add(prefs.start().name());
        if (prefs.end() != null && prefs.end().name() != null) names.add(prefs.end().name());
        if (prefs.via() != null) {
            for (Waypoint v : prefs.via()) {
                if (v.name() != null) names.add(v.name());
            }
        }
        return names;
    }

    /** Czy entry-point {@code w} pasuje do któregoś klucza „name@idx" w {@code droppable} (gmina pokryta naturalnie). */
    private static boolean isDroppable(Waypoint w, Set<String> droppable) {
        for (String key : droppable) {
            if (key.startsWith(w.name() + "@")) return true;
        }
        return false;
    }
}
