package velomarker.service.planning;

import velomarker.entity.planning.RoutePreferences;
import velomarker.entity.planning.UnvisitedArea;
import velomarker.entity.planning.Waypoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Wybór gmin do pokrycia względem trasy bazowej (greedy): scoring gminy, budowa waypointów z wybranych
 * + helpery geometryczne (przecięcia ring/segment). Bezstanowe, czyste funkcje wydzielone z
 * PlanningOrchestrationService.
 */
final class CoverageAreaSelection {

    private CoverageAreaSelection() {}

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
                if (!c.isIntersected() || c.area.isSpecial()) {
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

}
