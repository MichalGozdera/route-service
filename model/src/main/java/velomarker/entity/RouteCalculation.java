package velomarker.entity;

import java.util.List;

/**
 * Wynik routingu z brouter. {@code flatSpans} to zakresy indeksów wierzchołków [startIdx, endIdx] (włącznie) leżących
 * w tunelu/wiadukcie (z tagów OSM brouter). Na tych odcinkach DEM pokazuje teren NAD tunelem (fałszywy podjazd), więc
 * {@code CalculateRouteService} interpoluje tam z liniowo między portalami zamiast brać DEM. Pole służy WYŁĄCZNIE do
 * korekcji wysokości wewnątrz serwisu — nie jest eksponowane na zewnątrz (kontroler czyta tylko coordinates/dystans).
 */
public record RouteCalculation(
        List<double[]> coordinates,
        double distanceKm,
        List<int[]> flatSpans
) {
    public RouteCalculation(List<double[]> coordinates, double distanceKm) {
        this(coordinates, distanceKm, List.of());
    }
}
