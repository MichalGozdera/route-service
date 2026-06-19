package velomarker.service.planning;

import velomarker.entity.planning.RouteStyle;
import velomarker.entity.planning.Tempo;

/**
 * Mapuje preferencje na nazwę profilu BRouter. Asystent ZAWSZE planuje zaliczanie gmin, więc
 * używa wyłącznie wariantów „-gminy" (przyjaznych miastom — chętnie wjeżdżają w centra). Profile
 * MUSZĄ istnieć w brouter-deploy/profiles2: ultra-gminy, fastbike-gminy, trekking-gminy.
 * Tempo (turystycznie/szybko) ma priorytet nad stylem.
 *
 * <p>Przeniesione 1:1 z assistant-service.
 */
public class ProfileMapper {

    public String toBrouterProfile(RouteStyle style, Tempo tempo) {
        if (tempo != null) {
            return tempo == Tempo.SZYBKO ? "ultra-gminy" : "trekking-gminy";
        }
        if (style == null) {
            return "trekking-gminy";
        }
        return switch (style) {
            case KRAJOWKI -> "ultra-gminy";        // szybko, główne drogi
            case PRZEZ_MIASTA -> "fastbike-gminy"; // zastępuje usunięte safety
            case SCIEZKI, GRAVEL, WZDLUZ_RZEK -> "trekking-gminy";
        };
    }
}
