package velomarker.service.planning;

import org.junit.jupiter.api.Test;
import velomarker.entity.planning.RouteStyle;
import velomarker.entity.planning.Tempo;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileMapperTest {

    private final ProfileMapper mapper = new ProfileMapper();

    @Test
    void tempoSzybko_alwaysUltraGminy() {
        assertThat(mapper.toBrouterProfile(RouteStyle.SCIEZKI, Tempo.SZYBKO)).isEqualTo("ultra-gminy");
        assertThat(mapper.toBrouterProfile(RouteStyle.KRAJOWKI, Tempo.SZYBKO)).isEqualTo("ultra-gminy");
        assertThat(mapper.toBrouterProfile(null, Tempo.SZYBKO)).isEqualTo("ultra-gminy");
    }

    @Test
    void tempoTurystycznie_alwaysTrekkingGminy() {
        assertThat(mapper.toBrouterProfile(RouteStyle.KRAJOWKI, Tempo.TURYSTYCZNIE)).isEqualTo("trekking-gminy");
        assertThat(mapper.toBrouterProfile(RouteStyle.SCIEZKI, Tempo.TURYSTYCZNIE)).isEqualTo("trekking-gminy");
    }

    @Test
    void styleKrajowki_withoutTempo_isUltraGminy() {
        assertThat(mapper.toBrouterProfile(RouteStyle.KRAJOWKI, null)).isEqualTo("ultra-gminy");
    }

    @Test
    void stylePrzezMiasta_isFastbikeGminy() {
        assertThat(mapper.toBrouterProfile(RouteStyle.PRZEZ_MIASTA, null)).isEqualTo("fastbike-gminy");
    }

    @Test
    void offRoadStyles_areTrekkingGminy() {
        assertThat(mapper.toBrouterProfile(RouteStyle.SCIEZKI, null)).isEqualTo("trekking-gminy");
        assertThat(mapper.toBrouterProfile(RouteStyle.GRAVEL, null)).isEqualTo("trekking-gminy");
        assertThat(mapper.toBrouterProfile(RouteStyle.WZDLUZ_RZEK, null)).isEqualTo("trekking-gminy");
    }

    @Test
    void noStyle_noTempo_defaultTrekkingGminy() {
        assertThat(mapper.toBrouterProfile(null, null)).isEqualTo("trekking-gminy");
    }
}
