package velomarker.service.planning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import velomarker.port.out.planning.SpatialIndexFactory;
import velomarker.service.planning.coverage.GminaIndex;

import java.util.ArrayList;
import java.util.List;

/**
 * Greedy selekcja gmin do pokrycia: 2-etapowy (affordable NN-weighted min-coverage per kategoria — balans
 * gęste/rzadkie — + expensive ASC) do budżetu (surplus × roadAreas). Reszta → reserve. Bezstanowy.
 */
final class CoverageGreedyPicker {

    private static final Logger log = LoggerFactory.getLogger(CoverageGreedyPicker.class);

    private CoverageGreedyPicker() {}

    /** Wybrane (picked) + rezerwa (reserve, dla późniejszego GROW). */
    record PickResult(List<AreaCandidate> picked, List<AreaCandidate> reserve) {}
    /** Wynik greedy selekcji gmin: wybrane (picked) + rezerwa (reserve, dla późniejszego GROW). */

    /** 2-etapowy greedy: affordable NN-weighted min-coverage per kategoria (balans gęste/rzadkie) + expensive ASC
     *  dopóki budżet (surplus × roadAreas). Reszta → reserve. Zwraca picked + reserve. */
    static PickResult pick(List<AreaCandidate> candidates, int budgetKm, double baselineDistanceKm, double roadAreas,
                           SpatialIndexFactory spatialIndexFactory) {
        List<AreaCandidate> picked = new ArrayList<>();
        List<AreaCandidate> reserve = new ArrayList<>();
        double surplusKm = budgetKm - baselineDistanceKm;
        double targetExtra = surplusKm;
        double usedExtra = 0;
        int freeCount = 0;
        int paidCount = 0;

        // ADAPTIVE FAIRNESS: 2-etapowy greedy.
        // Etap 1: AFFORDABLE NN-weighted min-coverage greedy per kategoria (countryId:levelId:specialGroupId).
        //   Tylko obszary z detour ≤ median × 1.0 lub intersected. Z bucketu kategorii bierzemy
        //   peekFirst (cheapest po sort detour ASC). Wybór KATEGORII: ta o najmniejszym sumarycznym
        //   coverageReachKm (= Σ avgNN_cat × picked_count). NIE cykliczny round-robin — wybieramy
        //   zawsze najbardziej „głodną" kategorię po zasięgu geograficznym. Naturalnie balansuje
        //   gęste (CZ Obec nn~5km) vs rzadkie (DE Kreissitz nn~55km): rzadkie zbierają więcej
        //   pozycji proporcjonalnie do reach, niezależnie od fizycznej powierzchni (Kreissitz fix).
        // Etap 2: EXPENSIVE greedy ASC (bez fairness).
        //   Pozostałe (drogie) obszary, dorzucane gdy budget zostaje. SK token area wejdzie tylko
        //   jeśli budżet pozwoli, bez forsowania.
        double[] nonZeroDetours = candidates.stream()
                .filter(c -> !c.intersected && c.detourStraightKm > 0)
                .mapToDouble(c -> c.detourStraightKm).sorted().toArray();
        double medianDetour = nonZeroDetours.length > 0
                ? nonZeroDetours[nonZeroDetours.length / 2] : 0;
        // Threshold = median × 1.0 (nie × 2.0). User: "nie lepiej zbierać blisko trasy
        // i więcej, nie robiąc takich długich wypadów?" -- tighter threshold pcha obszary
        // > median do expensive pool, zostają tylko bliskie. Dla mediany 56km, threshold 56km
        // (zamiast 112km) → CZ Obec daleko od korytarza idą do reserve, nie zaśmiecają picked.
        double affordableThreshold = medianDetour * 1.0;
        java.util.Map<String, java.util.Deque<AreaCandidate>> bucketed = new java.util.LinkedHashMap<>();
        java.util.List<AreaCandidate> expensivePool = new java.util.ArrayList<>();
        for (AreaCandidate c : candidates) {
            if (c.intersected || c.detourStraightKm <= affordableThreshold) {
                String key = c.area.countryId() + ":" + c.area.levelId() + ":" + c.area.specialGroupId();
                bucketed.computeIfAbsent(key, k -> new java.util.ArrayDeque<>()).add(c);
            } else {
                expensivePool.add(c);
            }
        }
        StringBuilder bucketLog = new StringBuilder();
        for (var e : bucketed.entrySet()) {
            if (bucketLog.length() > 0) bucketLog.append(", ");
            bucketLog.append(e.getKey()).append("=").append(e.getValue().size());
        }
        log.info("Affordable buckets (detour ≤ {} km, median*2): {} | expensive: {}",
                new Object[]{String.format("%.1f", affordableThreshold), bucketLog, expensivePool.size()});
        // NN-WEIGHTED MIN-COVERAGE GREEDY: każdą iterację wybieramy kategorię o NAJMNIEJSZYM
        // sumarycznym coverageReachKm (= Σ avgNN_cat × picked_count). To NIE jest round-robin
        // (cykliczne A→B→C→A) — to wybór „najbardziej głodnej" kategorii po geograficznym zasięgu.
        // Dla par o tej samej powierzchni ale różnej rzadkości (Kreissitz 30 km²/60km NN vs obec
        // 30 km²/5km NN) Kreissitz dostaje ~12× więcej pozycji = realny balans geograficzny niezależny
        // od fizycznej powierzchni gminy. Dawniej balansowaliśmy po areaKm² — działało dla Landkreis
        // (large+sparse) vs obec (small+dense), ale myliło Kreissitz (small+sparse) z obec.
        // avgNN_cat precomputowane raz, spójne z reward calibration w CoveragePlanner (~linia 1995).
        java.util.Map<String, Double> avgNNcat = new java.util.HashMap<>();
        for (var e : bucketed.entrySet()) {
            List<velomarker.entity.planning.UnvisitedArea> catAreas = e.getValue().stream()
                    .map(c -> c.area).toList();
            double nn = velomarker.service.planning.coverage.GminaIndex.avgNearestNeighborDistKm(catAreas, spatialIndexFactory);
            if (nn <= 0) {
                // Fallback dla kategorii z <2 obszarami (avgNN==0) — sqrt(area) jako proxy spacing,
                // żeby kategoria nie miała wagi 0 i nie była wieczym min (zawsze wybierana).
                nn = catAreas.isEmpty() ? 1.0
                        : Math.max(1.0, Math.sqrt(Math.max(1.0, catAreas.get(0).areaKm2())));
            }
            avgNNcat.put(e.getKey(), nn);
        }
        java.util.Map<String, Double> coverageReachKm = new java.util.HashMap<>();
        for (String key : bucketed.keySet()) coverageReachKm.put(key, 0.0);
        while (true) {
            // Wybierz kategorię z MIN coverage reach (sposrod tych z niepustym bucketem)
            String pickCat = null;
            double minCoverage = Double.MAX_VALUE;
            for (var entry : bucketed.entrySet()) {
                if (entry.getValue().isEmpty()) continue;
                double cov = coverageReachKm.get(entry.getKey());
                if (cov < minCoverage) {
                    minCoverage = cov;
                    pickCat = entry.getKey();
                }
            }
            if (pickCat == null) break; // wszystkie buckety puste
            AreaCandidate c = bucketed.get(pickCat).peekFirst();
            double detourReal = c.intersected ? 0 : (c.detourStraightKm * roadAreas);
            if (usedExtra + detourReal > targetExtra && !c.intersected) {
                // Iter 8 BUGFIX: zamiast `clear()` (które gubiło drained affordable), DRAIN do reserve.
                // Wczesniej: greedy zatrzymywał się przy targetExtra → bucket cleared → reserve miał
                // TYLKO expensive (> median × 1.0 od baseline) → GROW musiał brać daleko (75% wp
                // w 50-100 km od baseline). Teraz drained leci do reserve i GROW shell expansion
                // ma BLISKIE obszary do dorzucenia.
                reserve.addAll(bucketed.get(pickCat));
                bucketed.get(pickCat).clear();
                continue;
            }
            bucketed.get(pickCat).pollFirst();
            picked.add(c);
            usedExtra += detourReal;
            coverageReachKm.merge(pickCat, avgNNcat.get(pickCat), Double::sum);
            if (c.intersected) freeCount++; else paidCount++;
        }
        // Drain pozostałe affordable do reserve (przekraczają budget per category)
        for (var bucket : bucketed.values()) reserve.addAll(bucket);
        // Log per-category NN-reach dla diagnostyki (sumaryczny picked × avgNN; w nawiasie avgNN_cat).
        StringBuilder covLog = new StringBuilder();
        for (var e : coverageReachKm.entrySet()) {
            if (covLog.length() > 0) covLog.append(", ");
            covLog.append(e.getKey())
                    .append("=").append(Math.round(e.getValue())).append("km")
                    .append(" (nn=").append(String.format(java.util.Locale.ROOT, "%.1f", avgNNcat.get(e.getKey())))
                    .append(")");
        }
        log.info("NN-reach picks per category: {}", covLog);
        int affordablePicked = picked.size();
        // Etap 2: EXPENSIVE greedy ASC -- dorzuca cheapest gdy budget zostaje
        expensivePool.sort((a, b) -> Double.compare(a.detourStraightKm, b.detourStraightKm));
        int expensiveAdded = 0;
        for (AreaCandidate c : expensivePool) {
            double detourReal = c.intersected ? 0 : (c.detourStraightKm * roadAreas);
            if (usedExtra + detourReal > targetExtra) {
                reserve.add(c);
                continue;
            }
            picked.add(c);
            usedExtra += detourReal;
            expensiveAdded++;
            paidCount++;
        }
        log.info("Greedy pick: {} affordable ({} free + {} paid) + {} expensive, total detour ~{} km (target {} km), reserve={}",
                new Object[]{affordablePicked, freeCount, paidCount - expensiveAdded, expensiveAdded,
                        Math.round(usedExtra), Math.round(targetExtra), reserve.size()});
        return new PickResult(picked, reserve);
    }
}
