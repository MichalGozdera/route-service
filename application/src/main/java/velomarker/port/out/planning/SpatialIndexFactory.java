package velomarker.port.out.planning;

/**
 * Buduje {@link SpatialIndex} nad konkretnym zbiorem punktów (per zapytanie). Implementacja w adapterze (JTS).
 */
public interface SpatialIndexFactory {
    /** @param pts {@code pts[i] = {lng, lat}} */
    SpatialIndex build(double[][] pts);
}
