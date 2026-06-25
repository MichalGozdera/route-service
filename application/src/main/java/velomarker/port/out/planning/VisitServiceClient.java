package velomarker.port.out.planning;

import java.util.List;

/**
 * Port do visit-service. Implementacja (VisitServiceHttpClient) propaguje JWT usera, by czytać
 * dane TEGO usera. Cache (CachingVisitServiceClient) ma dwa poziomy:
 * <ul>
 *   <li>{@code catalogue:*} — dane administracyjne (grupy specjalne) — TTL ~1h, wspólne dla wszystkich userów.</li>
 *   <li>{@code user:*} — obszary per user (nieodwiedzone + zaliczone) — TTL ~3 min, klucz = subject (userId).</li>
 * </ul>
 */
public interface VisitServiceClient {

    /**
     * Obszary administracyjne dla wskazanej pary (country, level): kandydaci (nieodwiedzeni) + zaliczeni
     * (z geometrią — do indeksu sąsiedztwa). Oba z tego samego {@code GET /areas}.
     *
     * @param limit  górny limit pobranych KANDYDATÓW (MAX_AREAS_TO_FETCH)
     */
    AreaPool listAreaPool(String bearerToken, int countryId, int levelId, int limit);

    /**
     * OBSZARY SPECJALNE z grupy: kandydaci + zaliczeni. countryId opcjonalny — gdy podany, zostawia tylko
     * specjale przypięte do jednostek poziomu-selektora tego kraju.
     */
    AreaPool listSpecialAreaPool(String bearerToken, int groupId, Integer countryId, int limit);

    /** Katalog grup specjalnych (spłaszczony per kraj). Cache catalogue. */
    List<SpecialGroupRef> listSpecialGroupsCatalog(String bearerToken);
}
