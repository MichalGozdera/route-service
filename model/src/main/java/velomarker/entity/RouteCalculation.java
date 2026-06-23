package velomarker.entity;

import java.util.List;

/**
 * Wynik routingu z brouter. {@code flatSpans} to zakresy indeksów wierzchołków [startIdx, endIdx] (włącznie) leżących
 * w tunelu/wiadukcie (z tagów OSM brouter). Na tych odcinkach DEM pokazuje teren NAD tunelem (fałszywy podjazd), więc
 * {@code CalculateRouteService} interpoluje tam z liniowo między portalami zamiast brać DEM. Pole służy WYŁĄCZNIE do
 * korekcji wysokości wewnątrz serwisu — nie jest eksponowane na zewnątrz (kontroler czyta tylko coordinates/dystans).
 *
 * <p>{@code stats} — agregowane statystyki typów nawierzchni / dróg / smoothness dla tego pojedynczego BRouter call.
 * Klient (asystent / frontend) może je sumować dla całej trasy (wiele calls) przez {@code RouteStatsAccumulator}.
 */
public record RouteCalculation(
        List<double[]> coordinates,
        double distanceKm,
        List<int[]> flatSpans,
        RouteStats stats,
        double[] crosspointStart,
        double[] crosspointEnd
) {
    public RouteCalculation(List<double[]> coordinates, double distanceKm) {
        this(coordinates, distanceKm, List.of(), RouteStats.empty(), null, null);
    }

    public RouteCalculation(List<double[]> coordinates, double distanceKm, List<int[]> flatSpans) {
        this(coordinates, distanceKm, flatSpans, RouteStats.empty(), null, null);
    }

    public RouteCalculation(List<double[]> coordinates, double distanceKm, List<int[]> flatSpans, RouteStats stats) {
        this(coordinates, distanceKm, flatSpans, stats, null, null);
    }
}
