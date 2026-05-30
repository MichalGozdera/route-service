package velomarker.service.planning;

import velomarker.entity.planning.RouteStyle;
import velomarker.entity.planning.Tempo;

/**
 * Mapuje preferencje na nazwę profilu BRouter. Profile MUSZĄ istnieć w brouter-deploy/profiles2:
 * fastbike, safety, trekking, fastbike-lowtraffic, moped, car-fast. „gravel"/„river" NIE są
 * zainstalowane → brouter 400. Tempo (turystycznie/szybko) ma priorytet nad stylem.
 *
 * <p>Przeniesione 1:1 z assistant-service.
 */
public class ProfileMapper {

    public String toBrouterProfile(RouteStyle style, Tempo tempo) {
        if (tempo != null) {
            return tempo == Tempo.SZYBKO ? "fastbike" : "trekking";
        }
        if (style == null) {
            return "trekking";
        }
        return switch (style) {
            case KRAJOWKI -> "fastbike";
            case PRZEZ_MIASTA -> "safety";
            case SCIEZKI, GRAVEL, WZDLUZ_RZEK -> "trekking";
        };
    }
}
