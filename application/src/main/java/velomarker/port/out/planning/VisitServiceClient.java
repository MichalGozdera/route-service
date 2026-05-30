package velomarker.port.out.planning;

import velomarker.entity.planning.UnvisitedArea;

import java.util.List;
import java.util.Map;

/**
 * Port do visit-service. Implementacja (VisitServiceHttpClient) propaguje JWT usera, by czytać
 * dane TEGO usera. Cache (CachingVisitServiceClient) ma dwa poziomy:
 * <ul>
 *   <li>{@code catalogue:*} — dane administracyjne (kraje, poziomy, grupy specjalne) — TTL ~1h,
 *       wspólne dla wszystkich userów.</li>
 *   <li>{@code user:*} — pokrycie/nieodwiedzone per user — TTL ~3 min, klucz = subject (userId).</li>
 * </ul>
 */
public interface VisitServiceClient {

    /** Pokrycie usera na wszystkich poziomach administracyjnych. */
    List<AreaCoverage> getAreaCoverage(String bearerToken);

    /**
     * Nieodwiedzone obszary administracyjne dla wskazanej pary (country, level).
     *
     * @param limit  górny limit pobranych (MAX_AREAS_TO_FETCH = 5000)
     */
    List<UnvisitedArea> listUnvisitedAreas(String bearerToken, int countryId, int levelId, int limit);

    /**
     * Nieodwiedzone OBSZARY SPECJALNE z grupy. countryId opcjonalny — gdy podany, zostawia tylko
     * specjale przypięte do jednostek poziomu-selektora tego kraju.
     */
    List<UnvisitedArea> listUnvisitedSpecialAreas(String bearerToken, int groupId, Integer countryId, int limit);

    /** Wszystkie kraje w systemie (id → nazwa). Cache catalogue. */
    Map<Integer, String> listAllCountries(String bearerToken);

    /** Katalog grup specjalnych (spłaszczony per kraj). Cache catalogue. */
    List<SpecialGroupRef> listSpecialGroupsCatalog(String bearerToken);

    /** Mapa levelId → levelOrder dla kraju. Cache catalogue. */
    Map<Integer, Integer> levelOrders(String bearerToken, int countryId);
}
