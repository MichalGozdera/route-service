package velomarker.service.planning.alns2;

/**
 * Konfiguracja ALNS2 (Orienteering/Max Coverage Path solver).
 *
 * <p>Sourced z application.yml `planning.alns2.*`. Defaults dla typowego scenariusza:
 * krótka baseline (<200 km), 5-15 dni × 150-200 km/dzień, mix gmin PL/DE/CZ.
 *
 * @param alphaKmPerMeter         1 m wzniosu = ile km efortu (default 0.1)
 * @param rNearKm                 promień "gmina blisko trasy" (default 5 km)
 * @param beta                    waga kary za dziury PL (gminy) blisko trasy (default 1.0)
 * @param tStart                  initial SA temperature (default 10)
 * @param coolingRate             T *= rate per iter (default 0.95)
 * @param maxIters                liczba SA iteracji (default 200)
 * @param noImproveStop           break gdy N iter bez poprawy (default 50)
 * @param samplePointsPerGmina    punktów per area używanych jako insertion candidates (default 5)
 * @param rewardReferenceDistKm   bazowa NN-dist (Iter 11: zamiast km², PL gmina NN ~7km → reward 0.7)
 * @param destroyRatio            % tour do usunięcia w destroy (default 0.15)
 * @param maxTimeSeconds          hard cap czasu (default 300s)
 * @param corridorFactor          kara za odległość od baseline (Iter 11, 0.05 = 50km→2.5× penalty)
 * @param gamma                   waga kary za dziury DE (Kreis, < beta bo duże, default 0.5)
 * @param delta                   waga repeat penalty (nawroty, default 2.0)
 */
public record Alns2Parameters(
        double alphaKmPerMeter,
        double rNearKm,
        double beta,
        double tStart,
        double coolingRate,
        int maxIters,
        int noImproveStop,
        int samplePointsPerGmina,
        double rewardReferenceDistKm,
        double destroyRatio,
        int maxTimeSeconds,
        double corridorFactor,
        double gamma,
        double delta
) {
    public static Alns2Parameters defaults() {
        return new Alns2Parameters(
                0.1,    // alpha km/m
                5.0,    // R_NEAR km
                0.3,    // BETA (mnożnik kary za dziurę gęstą na reward; <1 by coverage dominowało)
                10.0,   // T_start
                0.95,   // cooling
                200,    // max_iters
                50,     // no_improve_stop
                8,      // sample points per gmina (5→8: więcej kandydatów entry-point = większa szansa
                        // trafić w drogę obrzeżną zamiast w las/wodę, gdy centroid/sample lądują w
                        // Puszczy/pojezierzu → mniej waypointów odrzucanych jako target-island)
                10.0,   // reward reference NN-dist km
                0.10,   // destroy ratio (mniejsze niż repair top-K by trasa się nie kurczyła)
                300,    // max time sec
                0.05,   // corridor factor
                0.2,    // GAMMA (mnożnik kary za dziurę rzadką na reward)
                0.5     // DELTA (kara za BEZCELOWE pętle; metryka nuanced więc nie tłamsi coverage)
        );
    }
}
