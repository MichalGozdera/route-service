package velomarker.port.out.planning;

/** Pokrycie usera na jednym poziomie administracyjnym (z visit-service /statistics/user-areas). */
public record AreaCoverage(
        Integer countryId,
        String countryName,
        Integer levelId,
        Integer levelOrder,
        String levelName,
        long visitedCount,
        long totalAreas,
        double percentage
) {
}
