package velomarker.port.out.planning;

import velomarker.entity.planning.UnvisitedArea;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Spatial oracle dla ZALICZANIA obszarów przez trasę — liczone na PEŁNEJ geometrii (JTS w adapterze),
 * nie na ręcznym ray-castingu po downsamplowanych ringach. Eliminuje false-positives typu „Gorzów
 * Śląski" (ślad smyra po zewnętrznej stronie meandrującej granicy, ale jej nie przekracza).
 *
 * <p>Implementacja (adapter): JTS {@code PreparedGeometry} + {@code STRtree}. Kryterium głębokości
 * (trasa musi wjechać realnie w głąb, nie otrzeć krawędzi) = {@code area.buffer(-depth).intersects(line)}.
 *
 * <p><b>v3.15:</b> port wystawia też operacje przestrzenne, których planner dotąd DUBLOWAŁ ręcznie
 * (ray-casting/sampling na surowych lng/lat) — co rozjeżdżało się z kryterium kredytu i tworzyło
 * zbędne spury/dziury. Teraz JEDEN silnik (JTS, kryterium kredytu = skurczona geometria) liczy:
 * gdzie leg wchodzi w gminę ({@link #creditedCrossings}), które legi gminę kredytują
 * ({@link #creditingLegs}), które dziury są otoczone ({@link #enclosedUnvisited}) i jak daleko
 * gmina jest od trasy ({@link #distanceToRouteKm}).
 */
public interface AreaCoverageIndex {

    /** Id obszarów zaliczonych przez trasę (depth-aware: wjazd w głąb, nie otarcie krawędzi). */
    Set<Integer> visitedAreaIds(List<double[]> routeGeometry);

    /** RUNDA 66: Id obszarów w które trasa wchodzi GŁĘBOKO ≥220m (bufor −220, prepCreditDeep) = PRZELOT, nie muśnięcie.
     *  Węższy zbiór niż {@link #visitedAreaIds} (−200). Cięcie używa go do „czy gmina ma przelot GDZIE INDZIEJ ≥220m". */
    Set<Integer> deeplyVisitedAreaIds(List<double[]> routeGeometry);

    /** Id obszarów DOTYKANYCH przez trasę — PEŁNY wielokąt przecina ślad (nawet muśnięcie krawędzi/rogu, BEZ progu
     *  głębokości). Szerszy zbiór niż {@link #visitedAreaIds}. RUNDA 24: anchor-intersects stawia wp KAŻDEJ dotykanej
     *  gminie (na pierwszym wejściu w bufor, a muśnięcie → centroid). */
    Set<Integer> touchedAreaIds(List<double[]> routeGeometry);

    /** Najmniejszy powierzchniowo obszar zawierający punkt (obwarzanek: miasto w dziurze wiejskiej),
     *  lub null gdy punkt poza wszystkimi obszarami. */
    UnvisitedArea findAreaForPoint(double lng, double lat);

    /** Jak {@link #findAreaForPoint}, ale po RDZENIU KREDYTU (skurczona geometria, bufor −200m) — punkt liczy się
     *  tylko gdy leży ≥200m w głąb (to samo kryterium co „zaliczona"). {@code null} gdy punkt jest w wielokącie ale
     *  POZA buforem (płytki przy granicy) lub poza wszystkimi obszarami. Służy do wykrywania „wp bez kredytu". */
    UnvisitedArea findCreditedAreaForPoint(double lng, double lat);

    /** RUNDA 52: jak {@link #findCreditedAreaForPoint}, ale rdzeń −220m — punkt liczy się tylko gdy leży ≥220m w głąb.
     *  Do testu „start/meta/via dostatecznie głęboko, by sam pokrył gminę" (anchor-intersects nie dodaje wtedy wp). */
    UnvisitedArea findDeeplyCreditedAreaForPoint(double lng, double lat);

    /**
     * Maksymalny ciągły odcinek geometrii legu LEŻĄCY w gminie {@code areaId} wg kryterium KREDYTU
     * (skurczona geometria — to samo, czym liczone jest „zaliczona"). Punkty w lng/lat.
     * {@code null} gdy leg gminy nie kredytuje. Zastępuje ręczne crossingRunKm/midpointOfCrossing/
     * walkInsideFromBoundary/pointInArea — spójne z kredytem.
     */
    Crossing creditedCrossing(List<double[]> legGeometry, int areaId);

    /** Jak {@link #creditedCrossing}, ale z wielu wejść w rdzeń kredytu wybiera to o NAJWCZEŚNIEJSZEJ pozycji
     *  {@code entry} WZDŁUŻ śladu (nie najdłuższe). RUNDA 24: „jeśli ślad w kilku miejscach wpada w gminę >200m,
     *  bierzemy pierwszy przypadek". {@code null} gdy ślad nigdzie nie wchodzi ≥200m (muśnięcie). */
    Crossing firstCreditedCrossing(List<double[]> legGeometry, int areaId);

    /**
     * RUNDA 27: JEDEN przebieg śladu — dla każdej gminy, której ŚLAD WCHODZI w bufor kredytu (−200m), punkt PIERWSZEGO
     * wejścia + ~20m w głąb (≈220m od granicy gminy). O(ślad × kandydaci/segment) przez STRtree; overlay tylko na
     * 2-punktowych segmentach (nigdy nie wisi). Gmin BEZ wejścia w bufor nie ma w mapie (caller → centroid).
     * Zastępuje per-gminowe {@code firstCreditedCrossing} na CAŁYM śladzie (które wisiało na splocie macek).
     */
    Map<Integer, double[]> firstBufferEntryPoints(List<double[]> routeGeometry);

    /** RUNDA 31: najgłębszy punkt gminy (środek największego wpisanego okręgu, najdalej od KAŻDEJ granicy) — „głęboki
     *  centroid" dla muśnięć (zamiast {@code area.lng/lat}, które bywa przy granicy). {@code null} gdy brak gminy. */
    double[] deepestInteriorPoint(int areaId);

    /** Odcinek legu wewnątrz gminy: wejście przy granicy, środek (po długości), wyjście, długość km. */
    record Crossing(double[] entry, double[] mid, double[] exit, double lengthKm) {}

    /**
     * areaId → indeksy legów (z {@code legGeometries}), które gminę KREDYTUJĄ — jeden autorytatywny
     * przebieg (STRtree, kryterium kredytu). Zastępuje ręczny crossCount/edgeCrossings, który dryfował
     * (inkrementalna księgowość) i mylił klasyfikację spurów. Spur zbędny ⟺ każda jego gmina ma w tej
     * mapie INNY leg niż własne dwa.
     */
    Map<Integer, int[]> creditingLegs(List<List<double[]>> legGeometries);

    /**
     * Gminy NIEZALICZONE OTOCZONE: nieprzecięte, dla których {@link #allNeighborsVisited} = true (KAŻDY
     * sąsiad wielokątowy — adjacency po realnym styku granic, cross-border — jest w {@code visited}).
     * BEZ progu na liczbę sąsiadów: liczba NIE decyduje o otoczeniu. Zastępuje centroidowy enclosedFraction.
     */
    Set<Integer> enclosedUnvisited(Set<Integer> visited);

    /**
     * Czy gmina {@code areaId} jest OTOCZONA śladem: ma ≥1 sąsiada wielokątowego (adjacency po realnym
     * styku granic — DOWOLNY kraj/kategoria) i KAŻDY z nich jest w {@code visited}. BEZ progu na liczbę
     * sąsiadów (liczba NIE decyduje o otoczeniu). Działa dla gmin zaliczonych i nie. Niezebrana granica
     * drugiego kraju = BRAK sąsiada (nie sąsiad-niezaliczony) → nie blokuje otoczenia. Tylko realna wyspa
     * (0 sąsiadów) nie jest otoczona.
     */
    boolean allNeighborsVisited(int areaId, Set<Integer> visited);

    /**
     * Gminy NIEZALICZONE leżące ≤ {@code maxKm} od trasy (bufor+STRtree, JEDEN przebieg). Szybkie
     * łapanie dziur przy trasie do domykania budżetu. {@code visited} pomijane.
     */
    Set<Integer> unvisitedWithinKm(List<double[]> routeGeometry, Set<Integer> visited, double maxKm);
}
