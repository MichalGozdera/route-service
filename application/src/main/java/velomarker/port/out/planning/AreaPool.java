package velomarker.port.out.planning;

import velomarker.entity.planning.UnvisitedArea;

import java.util.List;

/**
 * Wynik pobrania obszarów (kraj, poziom) lub grupy specjalnej z visit-service: rozdzielony na
 * {@code unvisited} (kandydaci do pokrycia) i {@code visited} (historycznie zaliczone — NIE kandydaci,
 * ale ich geometria wchodzi do indeksu sąsiedztwa, by domykać dziury i zgrywać trasę z dawnym pokryciem).
 *
 * <p>Oba pochodzą z TEGO SAMEGO {@code GET /areas} (pełna geometria wszystkich obszarów) — zaliczone
 * dawniej wyrzucaliśmy, teraz zachowujemy. Bez dodatkowych zapytań, bez bboxa.
 */
public record AreaPool(List<UnvisitedArea> unvisited, List<UnvisitedArea> visited) {
}
