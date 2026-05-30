package velomarker.service.planning.tsp;

import velomarker.service.planning.PlanningOrchestrationService.AreaCandidate;
import velomarker.service.planning.WaypointSelector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Grid spatial index dla AreaCandidate -- pozwala szybkie zapytania "wszystkie obszary blisko
 * danego punktu lub edge" zamiast iterowania po caly listy.
 *
 * <p>Dla 2000 gmin + 200 picks: klasyczna TSP cheapest insertion to O(N^2 × P) = 800M ops.
 * Z grid: per edge query tylko sasiednie komorki (~30-100 candidates) -> O(P × edges × 30)
 * = ~1.2M ops. Ok. 1000x szybciej.
 *
 * <p>Cell size domyslnie 0.1 stopnia (~11km × 7km dla srednich szerokosci). Tunowalne.
 */
public class SpatialAreaIndex {

    private static final double DEFAULT_CELL_SIZE_DEG = 0.1;

    private final double cellSizeDeg;
    private final Map<Long, List<AreaCandidate>> cells = new HashMap<>();
    /** Zbior IDs aktualnie "picked" -- queryNearby pomija je. */
    private final Set<Integer> pickedAreaIds = new HashSet<>();

    public SpatialAreaIndex() {
        this(DEFAULT_CELL_SIZE_DEG);
    }

    public SpatialAreaIndex(double cellSizeDeg) {
        this.cellSizeDeg = cellSizeDeg;
    }

    /** Klucz cell jako long: (cellX << 32) | cellY. cellX = floor(lng/size), cellY = floor(lat/size). */
    private long cellKey(double lng, double lat) {
        long cx = (long) Math.floor(lng / cellSizeDeg);
        long cy = (long) Math.floor(lat / cellSizeDeg);
        return (cx << 32) | (cy & 0xFFFFFFFFL);
    }

    public void addAll(List<AreaCandidate> areas) {
        for (AreaCandidate c : areas) {
            long key = cellKey(c.getArea().lng(), c.getArea().lat());
            cells.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
        }
    }

    public void markPicked(AreaCandidate c) {
        pickedAreaIds.add(c.getArea().areaId());
    }

    /**
     * Zwraca wszystkie UNPICKED obszary w komorkach przeciętych przez bbox punktu (lng,lat) ± radiusKm.
     * 1 stopien lat = 111km, 1 stopien lng = 111×cos(lat) km. Konwersja km->stopnie LNG zalezy
     * od szerokosci -- przy 50°N (Europa Centralna) 1° lng = 71 km, nie 80 km (45°N).
     * Wczesniej stale `/80` dawalo 12% za waski bufor w stopniach -> false negatives narożnych
     * cells. Teraz cos(lat) precise.
     */
    public List<AreaCandidate> queryNearby(double lng, double lat, double radiusKm) {
        double kmPerDegLng = 111.0 * Math.cos(Math.toRadians(lat));
        double radiusDegLng = radiusKm / Math.max(kmPerDegLng, 1.0);  // guard near poles
        double radiusDegLat = radiusKm / 111.0;
        long minCx = (long) Math.floor((lng - radiusDegLng) / cellSizeDeg);
        long maxCx = (long) Math.floor((lng + radiusDegLng) / cellSizeDeg);
        long minCy = (long) Math.floor((lat - radiusDegLat) / cellSizeDeg);
        long maxCy = (long) Math.floor((lat + radiusDegLat) / cellSizeDeg);
        List<AreaCandidate> result = new ArrayList<>();
        for (long cx = minCx; cx <= maxCx; cx++) {
            for (long cy = minCy; cy <= maxCy; cy++) {
                long key = (cx << 32) | (cy & 0xFFFFFFFFL);
                List<AreaCandidate> bucket = cells.get(key);
                if (bucket == null) continue;
                for (AreaCandidate c : bucket) {
                    if (!pickedAreaIds.contains(c.getArea().areaId())) {
                        result.add(c);
                    }
                }
            }
        }
        return result;
    }

    /**
     * Zapytanie wzdluz edge: zwraca obszary w bbox segmentu [p1, p2] z buffer radiusKm.
     * Uzywane dla cheapest insertion -- per edge bierzemy tylko sasiednie obszary.
     */
    public List<AreaCandidate> queryAlongEdge(double[] p1, double[] p2, double bufferKm) {
        double minLng = Math.min(p1[0], p2[0]);
        double maxLng = Math.max(p1[0], p2[0]);
        double minLat = Math.min(p1[1], p2[1]);
        double maxLat = Math.max(p1[1], p2[1]);
        // Konwersja km->deg per axis. Lat: 111 km/deg (constant). Lng: zalezy od cos(lat).
        // Bierzemy MID-LAT bbox (lub max-lat, zachowawczo) -- na 50°N 1° lng = 71 km, na 60°N = 56 km.
        // Wczesniej stale `/80` zaniżało bufor w stopniach (12% bias na 50°N), pomijając cells.
        double midLat = (minLat + maxLat) / 2.0;
        double kmPerDegLng = 111.0 * Math.cos(Math.toRadians(midLat));
        double bufDegLng = bufferKm / Math.max(kmPerDegLng, 1.0);
        double bufDegLat = bufferKm / 111.0;
        long minCx = (long) Math.floor((minLng - bufDegLng) / cellSizeDeg);
        long maxCx = (long) Math.floor((maxLng + bufDegLng) / cellSizeDeg);
        long minCy = (long) Math.floor((minLat - bufDegLat) / cellSizeDeg);
        long maxCy = (long) Math.floor((maxLat + bufDegLat) / cellSizeDeg);
        List<AreaCandidate> result = new ArrayList<>();
        for (long cx = minCx; cx <= maxCx; cx++) {
            for (long cy = minCy; cy <= maxCy; cy++) {
                long key = (cx << 32) | (cy & 0xFFFFFFFFL);
                List<AreaCandidate> bucket = cells.get(key);
                if (bucket == null) continue;
                for (AreaCandidate c : bucket) {
                    if (!pickedAreaIds.contains(c.getArea().areaId())) {
                        // Dodatkowy filter: distance do edge segment < bufferKm
                        double d = distancePointToSegmentKm(c.getArea().lng(), c.getArea().lat(),
                                p1[0], p1[1], p2[0], p2[1]);
                        if (d <= bufferKm) result.add(c);
                    }
                }
            }
        }
        return result;
    }

    /** Distance from point P to segment AB, w km (haversine approximation w planarnej). */
    static double distancePointToSegmentKm(double px, double py,
                                            double ax, double ay,
                                            double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double len2 = dx * dx + dy * dy;
        double t;
        if (len2 < 1e-12) {
            t = 0;
        } else {
            t = ((px - ax) * dx + (py - ay) * dy) / len2;
            t = Math.max(0, Math.min(1, t));
        }
        double projX = ax + t * dx;
        double projY = ay + t * dy;
        return WaypointSelector.haversineKm(new double[]{px, py}, new double[]{projX, projY});
    }

    public int totalSize() {
        return cells.values().stream().mapToInt(List::size).sum();
    }

    public int pickedCount() {
        return pickedAreaIds.size();
    }
}
