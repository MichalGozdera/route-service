package velomarker.service.planning.coverage;

/**
 * Konfiguracja plannera pokrycia (seed + compact-loop).
 *
 * @param alphaKmPerMeter 1 m wzniosu = ile km efortu (default 0.1); effort = km + alpha·climb
 */
public record CoveragePlannerParameters(
        double alphaKmPerMeter
) {}
