package eu.cokeman.velomarker.out;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FlatSpanParserTest {

    private static final String HEADER = "Longitude\tLatitude\tElevation\tDistance\tWayTags";

    @Test
    void noMessagesReturnsEmpty() {
        assertThat(FlatSpanParser.parse(null, List.of())).isEmpty();
        assertThat(FlatSpanParser.parse(List.of(HEADER), List.of(new double[]{20, 52}, new double[]{20.1, 52.1}, new double[]{20.2, 52.2}))).isEmpty();
    }

    @Test
    void tooFewCoordsReturnsEmpty() {
        List<String> msgs = List.of(HEADER, "200100000\t142100000\t100\t100\thighway=primary");
        assertThat(FlatSpanParser.parse(msgs, List.of(new double[]{20, 52}, new double[]{20.1, 52.1}))).isEmpty();
    }

    @Test
    void missingColumnsReturnsEmpty() {
        List<String> msgs = List.of("Foo\tBar\tBaz", "1\t2\t3");
        assertThat(FlatSpanParser.parse(msgs, threeCoords())).isEmpty();
    }

    @Test
    void identifiesTunnelSpan() {
        // Trasa: A → B → C. Odcinek A→B przez tunnel, B→C zwykła droga.
        List<String> msgs = List.of(
                HEADER,
                "200100000\t142100000\t100\t100\thighway=primary tunnel=yes",
                "200200000\t142200000\t110\t200\thighway=primary"
        );
        List<int[]> spans = FlatSpanParser.parse(msgs, threeCoords());
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0)).containsExactly(0, 1);
    }

    @Test
    void identifiesBridgeSpan() {
        List<String> msgs = List.of(
                HEADER,
                "200100000\t142100000\t100\t100\thighway=primary",
                "200200000\t142200000\t110\t200\thighway=primary bridge=yes"
        );
        List<int[]> spans = FlatSpanParser.parse(msgs, threeCoords());
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0)).containsExactly(1, 2);
    }

    @Test
    void tunnelEqualsNoIsIgnored() {
        List<String> msgs = List.of(
                HEADER,
                "200100000\t142100000\t100\t100\thighway=primary tunnel=no",
                "200200000\t142200000\t110\t200\thighway=primary bridge=no"
        );
        assertThat(FlatSpanParser.parse(msgs, threeCoords())).isEmpty();
    }

    @Test
    void mergesAdjacentTunnelSpans() {
        // A→B tunnel, B→C też tunnel — powinno scalić w jeden span [0,2].
        List<String> msgs = List.of(
                HEADER,
                "200100000\t142100000\t100\t100\thighway=primary tunnel=yes",
                "200200000\t142200000\t110\t200\thighway=primary tunnel=yes"
        );
        List<int[]> spans = FlatSpanParser.parse(msgs, threeCoords());
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0)).containsExactly(0, 2);
    }

    @Test
    void endpointMismatchReturnsEmptyBestEffort() {
        // Endpoint w messages NIE matchuje żadnego wierzchołka — rezygnujemy z korekcji.
        List<String> msgs = List.of(
                HEADER,
                "999999999\t999999999\t100\t100\thighway=primary tunnel=yes"
        );
        assertThat(FlatSpanParser.parse(msgs, threeCoords())).isEmpty();
    }

    private static List<double[]> threeCoords() {
        // Mikrodegrees: 200_100_000 = (lon+180)*1e6 → lon=20.1. 142_100_000 = (lat+90)*1e6 → lat=52.1.
        // Coords trzymane jako geograficzne stopnie (lon, lat) — parser robi reverse round na lon*1e6/lat*1e6.
        // Tu używamy POŚREDNIO mikrodegrees==1e6×coord (BEZ +180/+90), bo parser robi Math.round(coord * 1e6).
        // BRouter messages mają BRouter-format = (lon+180)*1e6, więc trzeba odpowiednio dopasować coords.
        return List.of(
                new double[]{200.000, 142.000},  // ilon=200_000_000 ilat=142_000_000
                new double[]{200.100, 142.100},  // ilon=200_100_000 ilat=142_100_000
                new double[]{200.200, 142.200}   // ilon=200_200_000 ilat=142_200_000
        );
    }
}
