package velomarker.exception;

/**
 * Rzucane dla intentu COVERAGE gdy wybrane regiony leżą ZA DALEKO od korytarza start→meta, by
 * zmieścić je w budżecie (dni × km/dzień) — nawet najbliższy region wymaga objazdu większego niż
 * dostępny budżet. To nie błąd, lecz sygnał: getMessage() = stabilny kod {@code "AREAS_TOO_FAR"},
 * który ląduje w {@code PlanTask.error} i jest rozpoznawany przez front (przyjazny, przetłumaczony
 * komunikat z listą wybranych kategorii). Łapane wcześnie — przed kosztownym trasowaniem BRouterem.
 */
public class AreasTooFarException extends RuntimeException {

    public static final String CODE = "AREAS_TOO_FAR";

    public AreasTooFarException() {
        super(CODE);
    }
}
