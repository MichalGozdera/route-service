package eu.cokeman.velomarker.out;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import velomarker.entity.RouteCalculation;
import velomarker.exception.BrouterUpstreamException;

import java.io.File;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * E2E embedded BRoutera na REALNYCH danych rd5 (NIE mock — patrz {@link EmbeddedBrouterRoutingClientTest}
 * dla testów mapowania błędów na mockach). Ładuje prawdziwe segmenty + profile, więc:
 * <ul>
 *   <li>jest POMIJANY (assumeTrue) gdy brak plików .rd5 — np. w CI bez pobranych segmentów,</li>
 *   <li>lokalnie waliduje cały stos: load tile → profil → routing → {@link RouteCalculation}.</li>
 * </ul>
 * Współrzędne Saillon (CH) udokumentowane w RUNDA 49: snapują do odciętego mikro-fragmentu grafu
 * w {@code E5_N45.rd5} → wszystkie profile (też permisywny trekking) rzucają „target island".
 */
class EmbeddedBrouterRoutingClientE2eTest {

    private static final File SEG_DIR = new File("../../brouter-deploy/segments4");
    private static final String PROFILES = "../../brouter-deploy/profiles2";

    @BeforeEach
    void requireRealSegments() {
        assumeTrue(SEG_DIR.isDirectory()
                        && new File(SEG_DIR, "E20_N50.rd5").exists()   // Polska (happy-path)
                        && new File(SEG_DIR, "E5_N45.rd5").exists(),   // Szwajcaria (island case)
                "Brak segmentów BRoutera (.rd5) — e2e pominięty (CI bez pobranych danych).");
    }

    private EmbeddedBrouterRoutingClient realClient() {
        // Publiczny konstruktor buduje realną fabrykę (defaultFactory na segmentsDir).
        return new EmbeddedBrouterRoutingClient(SEG_DIR.getPath(), PROFILES, 4, 5, 256, 0L);
    }

    @Test
    void routesConnectedRoadInPoland() {
        // Centrum Warszawy — gęsta, połączona sieć. Profil produkcyjny `ultra`.
        RouteCalculation r = realClient().calculate(
                List.of(new double[]{21.0122, 52.2297}, new double[]{21.0177, 52.2370}), "ultra");

        assertThat(r.coordinates()).hasSizeGreaterThan(1);
        assertThat(r.distanceKm()).isGreaterThan(0.0);
    }

    @Test
    void saillonVineyardPointIsKnownDataIsland() {
        // RUNDA 49: snapuje do odciętego węzła w E5_N45.rd5 — target island na KAŻDYM profilu.
        // Regression marker: gdyby kiedyś zaczęło routować (odświeżony kafel / poprawka OSM),
        // ten test się wywali i zasygnalizuje, że island zniknął (wtedy zaktualizować/usunąć).
        assertThatThrownBy(() -> realClient().calculate(
                List.of(new double[]{7.195314, 46.182554}, new double[]{7.196179, 46.173387}), "ultra"))
                .isInstanceOf(BrouterUpstreamException.class)
                .hasMessageContaining("island");
    }
}
