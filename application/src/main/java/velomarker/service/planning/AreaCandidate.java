package velomarker.service.planning;

import velomarker.entity.planning.UnvisitedArea;
/** Wynik scoringu gminy względem bazowej trasy. */
public class AreaCandidate {
    final UnvisitedArea area;
    final boolean intersected;     // baseline polyline już przecina ring → gmina zaliczona darmo
    final int insertionIdx;        // indeks w baselineGeom najbliższy gminie (utrzymuje kolejność wzdłuż bazowej)
    final double detourStraightKm; // szacowany nadkład straight: 2× (dystans gminy do bazowej) + 0.2 km (wjazd+wyjazd)
    final double entryLng;
    final double entryLat;

    // Gettery dla testów (intersected, insertionIdx, detourStraightKm, entryLng/Lat).
    public boolean isIntersected() { return intersected; }
    public int getInsertionIdx() { return insertionIdx; }
    public double getDetourStraightKm() { return detourStraightKm; }
    public double getEntryLng() { return entryLng; }
    public double getEntryLat() { return entryLat; }
    public UnvisitedArea getArea() { return area; }

    public AreaCandidate(UnvisitedArea area, boolean intersected, int insertionIdx,
                  double detourStraightKm, double entryLng, double entryLat) {
        this.area = area;
        this.intersected = intersected;
        this.insertionIdx = insertionIdx;
        this.detourStraightKm = detourStraightKm;
        this.entryLng = entryLng;
        this.entryLat = entryLat;
    }
}
