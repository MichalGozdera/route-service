package velomarker.port.out.planning;

/**
 * Wpis katalogu grup specjalnych (spłaszczony per kraj): grupa + jej wiązanie z jednym krajem.
 * {@code selectorLevelId} = poziom administracyjny, po którym specjale są przypięte do regionów
 * (null = brak selektora). Jedna grupa wielokrajowa daje wiele wpisów (po jednym na kraj).
 */
public record SpecialGroupRef(int groupId, String name, int countryId, Integer selectorLevelId) {
}
