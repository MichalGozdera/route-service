package velomarker.service.planning.coverage;

import velomarker.entity.planning.UnvisitedArea;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JEDNA klasa dobierająca gminy do seeda — ranking + wstawianie w jednym miejscu. Zastępuje osobny scoring
 * {@code InitGrowPhase} i całą klasę {@code GrowNear}. Fazy różnią się WYŁĄCZNIE tym, jak ją wołają:
 * <ul>
 *   <li>{@code InitGrowPhase} — {@code pick(20)} batch po batchu (między batchami 2-opt + pomiar budżetu);</li>
 *   <li>{@code FinalizePhase} — {@code pick(additional)} jeden raz hurtem (proporcja brakującego budżetu).</li>
 * </ul>
 *
 * <p>Ranking liczony ŚWIEŻO przy KAŻDYM {@code pick} (zero throttlingu/bufora) — sygnały zgrania/dziur
 * zawsze odzwierciedlają bieżący stan. Koszt marginalny wobec BRouter-bound seeda.
 *
 * <p><b>Ranking (dynamiczny)</b> względem aktualnego stanu
 * {@code visited = historycznie zaliczone ∪ już dobrane}:
 * <pre>
 *   reward   = reward kategorii (różnorodność — rzadkie kategorie cenniejsze)
 *   dist     = distToBaseline   // STAŁA odległość do PIERWOTNEJ trasy BRoutera (korytarz), nie do rosnącego śladu
 *   adjFrac   = udział DŁUGOŚCI granicy z `visited` (0..1)        // ZGRANIE z zaliczonymi (też historycznymi)
 *   holeBonus = w otoczonej enklawie ? W_HOLE/(1+ln(rozmiar)) : 0 // DOMYKANIE dziur (też WIELOGMINOWYCH; malejący z rozmiarem)
 *   score = reward × (1 + W_ADJ·adjFrac + hole) / max(EPS, dist)^DIST_POW   // sort DESC (bliskość ważna)
 * </pre>
 * Dziury i sąsiedztwo liczone na grafie adjacency JTS (cross-border, zawiera też historycznie zaliczone).
 */
final class CandidatePicker {

    /** Waga zgrania z zaliczonymi (premiuje gminy graniczące z istniejącym pokryciem) — mocna, by dobierać
     *  PRZY froncie i nie zostawiać bliskich gmin na rzecz dalekich objazdów. */
    private static final double W_ADJ = 3.0;
    /** Bonus za pełne otoczenie (donut-hole) — silny, ale podlega budżetowi (nie gwarancja). */
    private static final double W_HOLE = 5.0;
    /** Dolny clamp dystansu (km) — gmina na/przy trasie nie daje nieskończonego score. */
    private static final double EPS = 0.05;
    /** Wykładnik kary za odległość: dist^DIST_POW w mianowniku — dalekie gminy spadają szybciej (bliskość
     *  do korytarza ważniejsza niż reward dalekiego sznura → mniej dalekich objazdów dla pojedynczej gminy). */
    private static final double DIST_POW = 1.5;

    /** Wynik doboru: ile gmin wstawiono + czy pula kandydatów się wyczerpała (nic więcej do dobrania). */
    record PickResult(int inserted, boolean poolExhausted) {}

    private record Cand(UnvisitedArea area, double[] point, double score, double distBase) {}

    private final GminaIndex gminaIndex;
    private final HilbertOrdering ordering;
    private final List<UnvisitedArea> pool;
    private final Map<String, Double> rewards;
    private final SeedRoute seed;
    /** distToBaseline per areaId — statyczne (korytarz nie zmienia się), liczone raz. */
    private final Map<Integer, Double> distBaseCache;

    CandidatePicker(SeedContext ctx, SeedRoute seed) {
        this.gminaIndex = ctx.gminaIndex();
        this.ordering = ctx.ordering();
        this.pool = ctx.pool();
        this.rewards = ctx.rewards();
        this.seed = seed;
        this.distBaseCache = new java.util.HashMap<>(pool.size() * 2);
        for (UnvisitedArea a : pool) {
            distBaseCache.put(a.areaId(), GeometryUtil.minDistToBaselineKm(entryPoint(a), seed.baseline()));
        }
    }

    /**
     * Dobierz do {@code count} najlepszych (wg dynamicznego score) gmin: cheapest-insert na trasę +
     * dopisanie do {@code selected}. ŚWIEŻY ranking przy KAŻDYM wywołaniu — sygnały zgrania/dziur liczone
     * względem aktualnego stanu {@code visited = historycznie zaliczone ∪ już dobrane} (zero lagu;
     * w finalize stan jest spójny, bo peel/anchor/cut przeliczają route przed kolejnym pick).
     */
    PickResult pick(int count) {
        if (count <= 0) {
            return new PickResult(0, false);
        }
        List<double[]> route = seed.route();
        List<SeedSel> selected = seed.selected();

        Set<Integer> taken = new HashSet<>(gminaIndex.historicallyVisited());
        for (SeedSel s : selected) {
            taken.add(s.area().areaId());
        }
        // Dziury WIELOGMINOWE: areaId → rozmiar otoczonej enklawy (raz na rerank). Bonus maleje z rozmiarem.
        Map<Integer, Integer> holeSizes = gminaIndex.enclosedHoleSizes(taken);

        List<Cand> ranked = new ArrayList<>();
        for (UnvisitedArea a : pool) {
            int id = a.areaId();
            if (taken.contains(id)) {
                continue;
            }
            double[] point = entryPoint(a);
            // KOSZT = dystans do PIERWOTNEJ trasy BRoutera (seed.baseline()), STAŁY — niezależnie jak pokickany
            // jest aktualny ślad. Dobieramy WOKÓŁ bazowego korytarza → brak dziur. (distToRoute wyrzucony z kosztu.)
            double dist = distBaseCache.getOrDefault(id, 0.0);
            double reward = rewards.getOrDefault(RewardModel.categoryKey(a), 1.0);
            double adjFrac = gminaIndex.neighborVisitedFraction(id, taken);   // DYNAMICZNY: zgranie z zaliczonymi (też historyczne)
            Integer holeSize = holeSizes.get(id);                             // DYNAMICZNY: ≠null → w otoczonej enklawie
            double holeBonus = holeSize != null ? W_HOLE / (1.0 + Math.log(holeSize)) : 0.0; // malejący z rozmiarem (1→W_HOLE)
            // Bazowa kolejność po distBase (stały korytarz), ale adjFrac/holeBonus przeliczane co pick → dynamiczna
            // podmiana gdy pojawia się dziura/enklawa.
            double score = reward * (1.0 + W_ADJ * adjFrac + holeBonus) / Math.pow(Math.max(EPS, dist), DIST_POW);
            ranked.add(new Cand(a, point, score, dist));
        }
        ranked.sort(Comparator.comparingDouble((Cand c) -> c.score()).reversed());

        int available = ranked.size();
        int toInsert = Math.min(count, available);
        for (int i = 0; i < toInsert; i++) {
            Cand c = ranked.get(i);
            route.add(GeometryUtil.cheapestInsertPos(route, c.point()), c.point());
            selected.add(new SeedSel(c.area(), c.point(), ordering.orderKey(c.point()), c.score(), c.distBase()));
        }
        return new PickResult(toInsert, available <= toInsert);
    }

    /** Najgłębszy punkt gminy (MIC, cache w indeksie) lub fallback centroid. */
    private double[] entryPoint(UnvisitedArea a) {
        double[] p = gminaIndex.deepestInteriorPoint(a.areaId());
        return p != null ? p : new double[]{a.lng(), a.lat()};
    }
}
