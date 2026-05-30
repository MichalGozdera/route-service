package velomarker.entity.planning;

import java.util.List;

/**
 * Nieodwiedzony obszar administracyjny (przeniesione z assistant-service). Punkt (lat/lng) =
 * REPREZENTATYWNY punkt WEWNĄTRZ największej części (nie średnia multipoligonu, która mogłaby wpaść
 * w dziurę). {@code parts} = wszystkie poligony (MultiPolygon) z dziurami — gmina może być rozłączna
 * (czeski okres wokół miasta) lub obwarzankiem (gmina wiejska z dziurą = gmina miejska w środku).
 *
 * <p>{@code countryId}/{@code levelId}/{@code levelName} = z której WARSTWY (celu) pochodzi obszar.
 * Przy multi-target pozwala liczyć zaliczenia per (kraj, poziom) i deduplikować po
 * {@code countryId:levelId:specialGroupId:name}.
 */
public record UnvisitedArea(
        int areaId,
        String name,
        String mainCity,
        double lat,
        double lng,
        List<AreaPart> parts,
        int countryId,
        int levelId,
        String levelName,
        Integer specialGroupId
) {
    /** Przeciążenie z pojedynczym ringiem (kompatybilność wsteczna: stare wywołania/testy operujące na
     *  jednym obrysie). null ring → brak części. Deleguje do kanonicznego konstruktora (parts). */
    public UnvisitedArea(int areaId, String name, String mainCity, double lat, double lng,
                         double[][] ring, int countryId, int levelId, String levelName, Integer specialGroupId) {
        this(areaId, name, mainCity, lat, lng,
                ring == null ? List.of() : List.of(new AreaPart(ring, null)),
                countryId, levelId, levelName, specialGroupId);
    }

    /** Obszar zwykłego poziomu z JEDNYM prostym ringiem (bez dziur/multi) — wygoda dla testów/prostych. */
    public static UnvisitedArea level(int areaId, String name, String mainCity, double lat, double lng,
                                      double[][] ring, int countryId, int levelId, String levelName) {
        return new UnvisitedArea(areaId, name, mainCity, lat, lng,
                List.of(new AreaPart(ring, null)), countryId, levelId, levelName, null);
    }

    /** Obszar zwykłego poziomu z pełną geometrią (multipolygon + dziury). */
    public static UnvisitedArea levelMulti(int areaId, String name, String mainCity, double lat, double lng,
                                           List<AreaPart> parts, int countryId, int levelId, String levelName) {
        return new UnvisitedArea(areaId, name, mainCity, lat, lng, parts, countryId, levelId, levelName, null);
    }

    /** Obszar specjalny (z grupy specjalnej) z pełną geometrią. */
    public static UnvisitedArea special(int areaId, String name, String mainCity, double lat, double lng,
                                        List<AreaPart> parts, int countryId, String groupName, int specialGroupId) {
        return new UnvisitedArea(areaId, name, mainCity, lat, lng, parts, countryId, 0, groupName, specialGroupId);
    }

    /** Czy to obszar specjalny (z grupy specjalnej), a nie zwykły poziom administracyjny. */
    public boolean isSpecial() {
        return specialGroupId != null;
    }

    /** Zewnętrzny obrys NAJWIĘKSZEJ części (kompatybilność wsteczna z kodem operującym na jednym ringu). */
    public double[][] ring() {
        double[][] best = null;
        if (parts != null) {
            for (AreaPart p : parts) {
                if (p.outer() != null && p.outer().length >= 3
                        && (best == null || p.outer().length > best.length)) {
                    best = p.outer();
                }
            }
        }
        return best;
    }

    /**
     * Przybliżona powierzchnia w km² (shoelace, suma WSZYSTKICH części minus dziury). Do WAŻENIA
     * wartości obszaru i wyboru najmniejszej gminy przy obwarzanku. Przybliżenie — ring jest próbkowany.
     */
    public double areaKm2() {
        if (parts == null || parts.isEmpty()) {
            return 0;
        }
        double kmPerDegLng = 111.320 * Math.cos(Math.toRadians(lat));
        double kmPerDegLat = 110.574;
        double areaDeg = 0;
        for (AreaPart p : parts) {
            areaDeg += ringAreaDeg(p.outer());
            if (p.holes() != null) {
                for (double[][] hole : p.holes()) {
                    areaDeg -= ringAreaDeg(hole);
                }
            }
        }
        return Math.max(0, areaDeg) * kmPerDegLng * kmPerDegLat;
    }

    private static double ringAreaDeg(double[][] ring) {
        if (ring == null || ring.length < 3) {
            return 0;
        }
        double sum = 0;
        int n = ring.length;
        for (int i = 0; i < n; i++) {
            double[] p1 = ring[i];
            double[] p2 = ring[(i + 1) % n];
            sum += p1[0] * p2[1] - p2[0] * p1[1];
        }
        return Math.abs(sum) / 2.0;
    }

    /** Klucz unikalności w puli multi-target — nazwa sama nie wystarcza (kolizje między celami/krajami/grupami). */
    public String dedupKey() {
        return countryId + ":" + levelId + ":" + specialGroupId + ":" + name;
    }
}
