package velomarker.entity.planning;

/**
 * Jeden poligon obszaru administracyjnego: zewnętrzny obrys {@code outer} + opcjonalne dziury
 * {@code holes} (np. gmina wiejska-obwarzanek ma dziurę = gmina miejska w środku).
 *
 * <p>Gmina może składać się z WIELU części (MultiPolygon — np. czeski okres otaczający miasto
 * w kilku rozłącznych kawałkach). Lista {@link UnvisitedArea#parts()} trzyma wszystkie.
 *
 * @param outer obrys zewnętrzny [lng,lat] (próbkowany)
 * @param holes dziury (każda to ring [lng,lat]); null/empty = brak dziur
 */
public record AreaPart(double[][] outer, double[][][] holes) {
    public AreaPart {
        if (holes == null) {
            holes = new double[0][][];
        }
    }
}
