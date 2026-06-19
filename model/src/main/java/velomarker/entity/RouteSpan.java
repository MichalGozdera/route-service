package velomarker.entity;

/**
 * Zakres consecutive wierzchołków trasy z tym samym kodem kategorii — pozwala FE kolorować
 * linię trasy na mapie zależnie od aktywnego filtra (nawierzchnia / typ drogi / smoothness).
 *
 * <p>Indeksy są <b>lokalne dla pojedynczego BRouter calle</b> (tj. odnoszą się do
 * {@code RouteCalculation.coordinates} z tego samego calla). Dla multi-leg trasy frontend skleja
 * coordinates per leg z odpowiednim offsetem; spans dla każdego leg'a mają wartości w zakresie
 * [0, len(coords_leg)-1] i muszą być przesunięte tak samo jak coords (przyrostowo).
 *
 * <p>{@code code} to znormalizowany identyfikator (kontrakt z {@link RouteStats}):
 * <ul>
 *   <li>dla surface: {@code asphalt}, {@code paving_stones}, {@code unknown}, ...</li>
 *   <li>dla road: {@code primary:DK7}, {@code cycleway_shared_foot}, {@code primary_with_cycleway_lane:N7}, ...</li>
 *   <li>dla smoothness: {@code excellent}, {@code good}, {@code unknown}, ...</li>
 * </ul>
 */
public record RouteSpan(
        int startIdx,
        int endIdx,
        String code
) {
}
