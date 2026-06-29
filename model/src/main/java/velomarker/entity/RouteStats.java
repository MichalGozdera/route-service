package velomarker.entity;

import java.util.List;
import java.util.Map;

/**
 * Stats trasy zagregowane per kategoria z {@code OsmTrack.messageList} BRoutera.
 * <p>
 * Klucze map są <b>znormalizowanymi kodami</b> (stable identifiers), NIE ludzkimi etykietami —
 * by tłumaczenia były zarządzane per-locale na poziomie klienta (frontend i18n).
 *
 * <h2>Mapy agregatów</h2>
 *
 * <p><b>surfaceMeters</b> — raw OSM tag lowercase ({@code asphalt}, {@code paving_stones},
 * {@code sett}, {@code cobblestone}, {@code concrete}, {@code gravel}, {@code fine_gravel},
 * {@code compacted}, {@code unpaved}, {@code ground}, {@code dirt}, {@code grass}, {@code sand}...)
 * lub {@code "unknown"} dla segmentów bez tagu {@code surface}.
 *
 * <p><b>roadMeters</b> — znormalizowany code drogi:
 * <ul>
 *   <li>Drogi publiczne: {@code motorway}, {@code trunk}, {@code primary}, {@code secondary},
 *       {@code tertiary}, {@code unclassified}, {@code residential}, {@code living_street},
 *       {@code service}.</li>
 *   <li>Z optymalnym suffixem: {@code _with_cycleway_lane}, {@code _use_sidepath}.</li>
 *   <li>Z optymalnym refem: {@code :REF} (np. {@code primary:DK7}, {@code secondary:D38}).</li>
 *   <li>Ścieżki/chodniki: {@code cycleway}, {@code cycleway_shared_foot}, {@code path_bike_foot},
 *       {@code path_bike}, {@code path_foot}, {@code path}, {@code footway},
 *       {@code footway_bike_allowed}, {@code pedestrian}, {@code pedestrian_bike_allowed},
 *       {@code track}, {@code bridleway}, {@code steps}.</li>
 *   <li>{@code "unknown"} dla segmentów bez tagu {@code highway}.</li>
 * </ul>
 *
 * <p><b>smoothnessMeters</b> — raw OSM tag lowercase ({@code excellent}, {@code good},
 * {@code intermediate}, {@code bad}, {@code very_bad}, {@code horrible}, {@code very_horrible},
 * {@code impassable}) lub {@code "unknown"}.
 *
 * <h2>Spans (per-segment visualisation)</h2>
 *
 * <p><b>surfaceSpans / roadSpans / smoothnessSpans</b> — listy {@link RouteSpan} ze startIdx/endIdx
 * w przestrzeni {@code RouteCalculation.coordinates} z tego samego calle (indeksy lokalne).
 * Pozwala FE kolorować linię na mapie zależnie od aktywnego filtra. Spans są zmergowane: consecutive
 * segmenty z tym samym kodem dają jeden span (oszczędność rozmiaru).
 *
 * <p>Spans i mapy są spójne — suma długości spans per kod ≈ wartość w {@code Meters}-mapie
 * (modulo precyzja matchowania endpointów).
 *
 * <h2>Tłumaczenie</h2>
 *
 * <p><b>Frontend</b>: trzyma translation mapy per locale (PL/EN/DE/FR/...) w i18n (blok
 * {@code ROUTE_STATS} w assets/i18n) — backend nie tłumaczy, wysyła tylko kody.
 * Parsowanie ref code (po dwukropku) i suffixów (_with_cycleway_lane, _use_sidepath) jest
 * agnostyczne kraju.
 */
public record RouteStats(
        long totalMeters,
        Map<String, Long> surfaceMeters,
        Map<String, Long> roadMeters,
        Map<String, Long> smoothnessMeters,
        List<RouteSpan> surfaceSpans,
        List<RouteSpan> roadSpans,
        List<RouteSpan> smoothnessSpans
) {
    public static RouteStats empty() {
        return new RouteStats(0L, Map.of(), Map.of(), Map.of(), List.of(), List.of(), List.of());
    }
}
