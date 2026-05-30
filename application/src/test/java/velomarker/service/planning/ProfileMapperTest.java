package velomarker.service.planning;

import org.junit.jupiter.api.Test;
import velomarker.entity.planning.RouteStyle;
import velomarker.entity.planning.Tempo;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileMapperTest {

    private final ProfileMapper mapper = new ProfileMapper();

    @Test
    void tempoSzybko_alwaysFastbike() {
        assertThat(mapper.toBrouterProfile(RouteStyle.SCIEZKI, Tempo.SZYBKO)).isEqualTo("fastbike");
        assertThat(mapper.toBrouterProfile(RouteStyle.KRAJOWKI, Tempo.SZYBKO)).isEqualTo("fastbike");
        assertThat(mapper.toBrouterProfile(null, Tempo.SZYBKO)).isEqualTo("fastbike");
    }

    @Test
    void tempoTurystycznie_alwaysTrekking() {
        assertThat(mapper.toBrouterProfile(RouteStyle.KRAJOWKI, Tempo.TURYSTYCZNIE)).isEqualTo("trekking");
        assertThat(mapper.toBrouterProfile(RouteStyle.SCIEZKI, Tempo.TURYSTYCZNIE)).isEqualTo("trekking");
    }

    @Test
    void styleKrajowki_withoutTempo_isFastbike() {
        assertThat(mapper.toBrouterProfile(RouteStyle.KRAJOWKI, null)).isEqualTo("fastbike");
    }

    @Test
    void stylePrzezMiasta_isSafety() {
        assertThat(mapper.toBrouterProfile(RouteStyle.PRZEZ_MIASTA, null)).isEqualTo("safety");
    }

    @Test
    void offRoadStyles_areTrekking() {
        assertThat(mapper.toBrouterProfile(RouteStyle.SCIEZKI, null)).isEqualTo("trekking");
        assertThat(mapper.toBrouterProfile(RouteStyle.GRAVEL, null)).isEqualTo("trekking");
        assertThat(mapper.toBrouterProfile(RouteStyle.WZDLUZ_RZEK, null)).isEqualTo("trekking");
    }

    @Test
    void noStyle_noTempo_defaultTrekking() {
        assertThat(mapper.toBrouterProfile(null, null)).isEqualTo("trekking");
    }
}
