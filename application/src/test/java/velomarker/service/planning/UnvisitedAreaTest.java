package velomarker.service.planning;

import org.junit.jupiter.api.Test;
import velomarker.entity.planning.UnvisitedArea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Sanity check dla UnvisitedArea — wagi w {@code expectedExtraStraightKm} liczone w
 * {@link PlanningOrchestrationService} bazują na {@link UnvisitedArea#areaKm2}, więc bug w
 * shoelace zepsułby ranking. Test sprawdza że gmina ~30 km² zwraca ~30 km², a powiat ~600 km².
 */
class UnvisitedAreaTest {

    /** Prostokąt ~5×6 km wokół (14.5, 50.0) → około 30 km². */
    @Test
    void areaKm2_smallMunicipality_around30km2() {
        double lng = 14.5, lat = 50.0;
        double dLng = 0.035, dLat = 0.027; // ~2.5 × ~3 km półboki = 5×6 km box
        double[][] ring = {
                {lng - dLng, lat - dLat}, {lng + dLng, lat - dLat},
                {lng + dLng, lat + dLat}, {lng - dLng, lat + dLat}
        };
        var a = new UnvisitedArea(1, "n", lat, lng, ring, 1, 1, "gmina", null);
        assertThat(a.areaKm2()).isCloseTo(30, within(5.0));
    }

    /** Prostokąt ~20×30 km → około 600 km² (typowy powiat). */
    @Test
    void areaKm2_district_around600km2() {
        double lng = 14.5, lat = 50.0;
        double dLng = 0.14, dLat = 0.135; // ~10 × ~15 km półboki
        double[][] ring = {
                {lng - dLng, lat - dLat}, {lng + dLng, lat - dLat},
                {lng + dLng, lat + dLat}, {lng - dLng, lat + dLat}
        };
        var a = new UnvisitedArea(1, "n", lat, lng, ring, 1, 2, "okres", null);
        assertThat(a.areaKm2()).isCloseTo(600, within(80.0));
    }

    @Test
    void areaKm2_nullRing_returnsZero() {
        var a = UnvisitedArea.level(1, "n", 50.0, 14.5, null, 1, 1, "gmina");
        assertThat(a.areaKm2()).isZero();
    }

    @Test
    void areaKm2_tooFewPoints_returnsZero() {
        var a = new UnvisitedArea(1, "n", 50.0, 14.5,
                new double[][]{{14.5, 50.0}, {14.6, 50.0}}, 1, 1, "gmina", null);
        assertThat(a.areaKm2()).isZero();
    }

    @Test
    void isSpecial_trueWhenSpecialGroupIdSet() {
        var special = new UnvisitedArea(1, "n", 50.0, 14.5, (double[][]) null, 1, 1, "gmina", 42);
        var regular = UnvisitedArea.level(2, "n", 50.0, 14.5, null, 1, 1, "gmina");
        assertThat(special.isSpecial()).isTrue();
        assertThat(regular.isSpecial()).isFalse();
    }

}
