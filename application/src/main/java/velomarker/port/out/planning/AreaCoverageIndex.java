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
 * zbędne spury/dziury. Teraz JEDEN silnik (JTS, kryterium kredytu = skurczona geometria) liczy m.in.
 * punkty pierwszego wejścia śladu w gminę ({@link #firstBufferEntryPoints}) i które dziury są
 * otoczone ({@link #enclosedUnvisited}).
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
     * RUNDA 27: JEDEN przebieg śladu — dla każdej gminy, której ŚLAD WCHODZI w bufor kredytu (−200m), punkt PIERWSZEGO
     * wejścia + ~20m w głąb (≈220m od granicy gminy). O(ślad × kandydaci/segment) przez STRtree; overlay tylko na
     * 2-punktowych segmentach (nigdy nie wisi). Gmin BEZ wejścia w bufor nie ma w mapie (caller → centroid).
     */
    Map<Integer, double[]> firstBufferEntryPoints(List<double[]> routeGeometry);

    /**
     * WSZYSTKIE przejścia śladu przez rdzeń −220m każdej gminy (NIE tylko pierwsze jak
     * {@link #firstBufferEntryPoints}). Przejście = maksymalny ciągły fragment śladu w buforze −220;
     * {@link AreaPassage#entry()}/{@link AreaPassage#exit()} = punkty przecięcia granicy −220,
     * {@link AreaPassage#chordKm()} = ich dystans. Cięcie zaułków odróżnia transit (długa cięciwa)
     * od zaułka (krótka). 0 BRouter — jeden przebieg śladu + STRtree (jak {@link #firstBufferEntryPoints}).
     */
    Map<Integer, List<AreaPassage>> passages(List<double[]> routeGeometry);

    /** RUNDA 31: najgłębszy punkt gminy (środek największego wpisanego okręgu, najdalej od KAŻDEJ granicy) — „głęboki
     *  centroid" dla muśnięć (zamiast {@code area.lng/lat}, które bywa przy granicy). {@code null} gdy brak gminy. */
    double[] deepestInteriorPoint(int areaId);

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
     * PIERWSZY (wzdłuż śladu) punkt {@code track} w gminie {@code areaId} o odległości od granicy ≥ {@code minDepthMeters}
     * = pierwsze wejście w bufor −minDepth; {@code null} gdy ślad nigdzie nie wchodzi tak głęboko. Do pogłębiania:
     * spłycamy wp do PIERWSZEGO wejścia na zadaną głębokość (220→250→300), NIE do najgłębszego czubka zaułka.
     * 0 BRouter (czysta geometria JTS).
     */
    double[] firstTrackPointAtDepth(List<double[]> track, int areaId, double minDepthMeters);

    /**
     * Głębokość punktu w gminie = odległość (m) do granicy. {@code -1} gdy punkt poza gminą {@code areaId}.
     * Do diagnostyki „jak głęboko siedzi wp/crosspoint". 0 BRouter (czysta geometria JTS).
     */
    double depthMeters(double[] point, int areaId);

    /**
     * Jak {@link #firstTrackPointAtDepth}, ale skan ograniczony do FRAGMENTU śladu między {@code entry} a {@code exit}
     * (wejściem i wyjściem PRZELOTU). Pogłębianie kotwicy przelotu szuka punktu ≥ minDepth NA przelocie, NIE pierwszego
     * wejścia gdziekolwiek (które mogłoby trafić w płytki zaułek przed przelotem). {@code null} gdy fragment nie wchodzi
     * tak głęboko. 0 BRouter.
     */
    double[] firstTrackPointAtDepthBetween(List<double[]> track, int areaId, double minDepthMeters,
                                           double[] entry, double[] exit);

    /**
     * DEBUG: GeoJSON (FeatureCollection) granicy gminy {@code areaId} pomniejszonej o {@code bufferMeters}
     * (dodatni = rdzeń −X m, np. 220; 0 = pełna granica). Do wklejenia w mapę debug obok śladu. {@code null}
     * gdy brak gminy. Wołane ręcznie z debuggera: {@code gminaIndex.debugAreaGeoJson(id, 220)}.
     */
    String debugAreaGeoJson(int areaId, double bufferMeters);
}
