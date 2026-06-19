package eu.cokeman.velomarker.out;

import btools.router.OsmPathElement;
import btools.router.OsmTrack;
import btools.router.RoutingEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import velomarker.entity.RouteCalculation;
import velomarker.exception.BrouterMissingTileException;
import velomarker.exception.BrouterUnavailableException;
import velomarker.exception.BrouterUpstreamException;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmbeddedBrouterRoutingClientTest {

    private EmbeddedBrouterRoutingClient.RoutingEngineFactory factory;
    private RoutingEngine engine;
    private EmbeddedBrouterRoutingClient client;

    @BeforeEach
    void setUp() {
        factory = mock(EmbeddedBrouterRoutingClient.RoutingEngineFactory.class);
        engine = mock(RoutingEngine.class);
        when(factory.create(any(), any())).thenReturn(engine);
        // Trzymamy pool-size=2 w testach żeby łatwo sprawdzić saturację bez konieczności
        // odpalania 16 wątków.
        client = new EmbeddedBrouterRoutingClient("/profiles", 2, 1, 64, 0L, factory);
    }

    @Test
    void missingTileErrorMapsToMissingTileException() {
        when(engine.getErrorMessage()).thenReturn("datafile W10_N45.rd5 not found");

        assertThatThrownBy(() -> client.calculate(twoWaypoints(), "trekking"))
                .isInstanceOf(BrouterMissingTileException.class)
                .satisfies(e -> assertThat(((BrouterMissingTileException) e).tileName()).isEqualTo("W10_N45"));
    }

    @Test
    void genericErrorMapsToUpstreamException() {
        when(engine.getErrorMessage()).thenReturn("target island detected");

        assertThatThrownBy(() -> client.calculate(twoWaypoints(), "trekking"))
                .isInstanceOf(BrouterUpstreamException.class);
    }

    @Test
    void runtimeExceptionFromDoRunMapsToCorrectException() {
        doThrow(new RuntimeException("datafile E5_N50.rd5 not found"))
                .when(engine).doRun(any(Long.class));

        assertThatThrownBy(() -> client.calculate(twoWaypoints(), "trekking"))
                .isInstanceOf(BrouterMissingTileException.class);
    }

    @Test
    void emptyTrackMapsToUpstreamException() {
        when(engine.getErrorMessage()).thenReturn(null);
        when(engine.getFoundTrack()).thenReturn(null);

        assertThatThrownBy(() -> client.calculate(twoWaypoints(), "trekking"))
                .isInstanceOf(BrouterUpstreamException.class);
    }

    @Test
    void waypointConversionUsesBrouterMicrodegreesFormat() {
        assertThat(EmbeddedBrouterRoutingClient.lonToMicrodegrees(0.0)).isEqualTo(180_000_000);
        assertThat(EmbeddedBrouterRoutingClient.latToMicrodegrees(0.0)).isEqualTo(90_000_000);
        assertThat(EmbeddedBrouterRoutingClient.lonToMicrodegrees(-180.0)).isEqualTo(0);
        assertThat(EmbeddedBrouterRoutingClient.latToMicrodegrees(-90.0)).isEqualTo(0);
        assertThat(EmbeddedBrouterRoutingClient.lonToMicrodegrees(20.0)).isEqualTo(200_000_000);
        assertThat(EmbeddedBrouterRoutingClient.latToMicrodegrees(52.0)).isEqualTo(142_000_000);
    }

    @Test
    void tooFewWaypointsMapsToUpstreamException() {
        assertThatThrownBy(() -> client.calculate(List.of(new double[]{20, 52}), "trekking"))
                .isInstanceOf(BrouterUpstreamException.class);
    }

    @Test
    void semaphoreSaturationMapsToUnavailableException() {
        // Permits=0 → tryAcquire zawsze odmawia po waitSeconds → deterministyczna ścieżka 429.
        EmbeddedBrouterRoutingClient saturated =
                new EmbeddedBrouterRoutingClient("/profiles", 0, 1, 64, 0L, factory);

        assertThatThrownBy(() -> saturated.calculate(twoWaypoints(), "trekking"))
                .isInstanceOf(BrouterUnavailableException.class);
    }

    @Test
    void happyPathProducesRouteCalculation() {
        when(engine.getErrorMessage()).thenReturn(null);
        when(engine.getFoundTrack()).thenReturn(trackWithTwoNodes());

        RouteCalculation result = client.calculate(twoWaypoints(), "trekking");

        assertThat(result.coordinates()).hasSize(2);
        // ilon=200_000_000 → lon = 200_000_000/1e6 - 180 = 20.0
        assertThat(result.coordinates().get(0)[0]).isEqualTo(20.0);
        // ilat=142_000_000 → lat = 142_000_000/1e6 - 90 = 52.0
        assertThat(result.coordinates().get(0)[1]).isEqualTo(52.0);
        assertThat(result.distanceKm()).isEqualTo(1.5); // 1500m / 1000
        assertThat(result.flatSpans()).isEmpty();
    }

    private static List<double[]> twoWaypoints() {
        return List.of(new double[]{20.0, 52.0}, new double[]{21.0, 53.0});
    }

    private static OsmTrack emptyOkTrack() {
        OsmTrack t = new OsmTrack();
        t.nodes = List.of(OsmPathElement.create(200_000_000, 142_000_000, (short) 0, null));
        t.distance = 0;
        t.messageList = List.of();
        return t;
    }

    private static OsmTrack trackWithTwoNodes() {
        OsmTrack t = new OsmTrack();
        List<OsmPathElement> nodes = new ArrayList<>();
        nodes.add(OsmPathElement.create(200_000_000, 142_000_000, (short) 0, null));
        nodes.add(OsmPathElement.create(200_100_000, 142_100_000, (short) 0, null));
        t.nodes = nodes;
        t.distance = 1500;
        t.messageList = List.of();
        return t;
    }

}
