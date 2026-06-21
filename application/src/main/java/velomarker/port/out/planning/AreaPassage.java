package velomarker.port.out.planning;

/**
 * Pojedyncze PRZEJŚCIE śladu przez rdzeń −220m gminy: maksymalny ciągły fragment śladu wewnątrz
 * bufora −220. {@code entry}/{@code exit} to punkty przecięcia granicy −220 (lng/lat), {@code chordKm}
 * = dystans entry↔exit. Odróżnia transit (ślad PRZECHODZI przez gminę — entry i exit po RÓŻNYCH
 * stronach → długa cięciwa) od zaułka (wchodzi i wraca tym samym miejscem → krótka cięciwa).
 */
public record AreaPassage(double[] entry, double[] exit, double chordKm) {}
