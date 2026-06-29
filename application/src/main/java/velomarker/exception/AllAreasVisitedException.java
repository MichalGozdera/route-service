package velomarker.exception;

/**
 * Rzucane dla intentu COVERAGE gdy w wybranych kategoriach NIE MA już żadnego nieodwiedzonego
 * obszaru (wszystkie zaliczone). To nie błąd — to sygnał sukcesu: getMessage() = stabilny kod
 * {@code "ALL_AREAS_VISITED"}, który ląduje w {@code PlanTask.error} i jest rozpoznawany przez
 * front (przyjazny, przetłumaczony komunikat z listą wybranych kategorii).
 */
public class AllAreasVisitedException extends RuntimeException {

    public static final String CODE = "ALL_AREAS_VISITED";

    public AllAreasVisitedException() {
        super(CODE);
    }
}
