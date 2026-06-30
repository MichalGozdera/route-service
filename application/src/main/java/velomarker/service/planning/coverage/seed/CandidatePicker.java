package velomarker.service.planning.coverage.seed;

import velomarker.service.planning.*;
import velomarker.service.planning.route.*;
import velomarker.service.planning.day.*;
import velomarker.service.planning.coverage.*;
import velomarker.service.planning.coverage.prep.*;
import velomarker.service.planning.coverage.seed.*;
import velomarker.service.planning.coverage.index.*;
import velomarker.service.planning.coverage.metric.*;
import velomarker.service.planning.coverage.geom.*;
import velomarker.service.planning.coverage.scoring.*;
import velomarker.service.planning.coverage.debug.*;

import velomarker.entity.planning.UnvisitedArea;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Dobiera gminy do seeda — dynamiczny ranking (reward/odległość/zgranie/dziury) + cheapest-insert.
public final class CandidatePicker {

    private static final double W_ADJ = 3.0;
    private static final double W_HOLE = 5.0;
    private static final double EPS = 0.05;
    private static final double DIST_POW = 1.5;

    /** Bramka skoku dystansu (pętle wzrostu): kolejna gmina dozwolona bez skoku, gdy dystans do
     *  korytarza ≤ max({@code JUMP_FLOOR_KM}, {@code JUMP_RATIO} × dotychczasowa najdalsza przyjęta). */
    public static final double JUMP_FLOOR_KM = 200.0;
    public static final double JUMP_RATIO = 2.0;

    private final CoverageAreaIndex coverageAreaIndex;
    private final HilbertOrdering ordering;
    private final List<UnvisitedArea> pool;
    private final Map<String, Double> rewards;
    private final SeedRoute seed;
    private final Map<Integer, Double> distBaseCache;
    /** TILES: max koszt wcięcia (detour) kandydata; {@code MAX_VALUE} = filtr wyłączony (COVERAGE). */
    private final double maxDetourKm;

    public CandidatePicker(SeedContext ctx, SeedRoute seed) {
        this.coverageAreaIndex = ctx.coverageAreaIndex();
        this.ordering = ctx.ordering();
        this.pool = ctx.pool();
        this.rewards = ctx.rewards();
        this.seed = seed;
        this.maxDetourKm = ctx.detourFilterActive() ? ctx.tileMaxDetourKm() : Double.MAX_VALUE;
        this.distBaseCache = new java.util.HashMap<>(pool.size() * 2);
        for (UnvisitedArea a : pool) {
            distBaseCache.put(a.areaId(), GeometryUtil.minDistToBaselineKm(entryPoint(a), seed.baseline()));
        }
    }

    /** Dobiera do {@code count} gmin z bramki zasięgu (dystans do korytarza ≤ {@code maxReachKm}).
     *  Kandydaci za bramką NIE są wstawiani — sygnalizowani przez {@code jumpAhead}/{@code nextDistKm},
     *  by pętla wzrostu wymusiła realny checkpoint przed przekroczeniem przerwy (skoku dystansu). */
    public PickResult pick(int count, double maxReachKm) {
        if (count <= 0) {
            return new PickResult(0, false, false, Double.NaN);
        }
        List<double[]> route = seed.route();
        List<SeedSel> selected = seed.selected();

        Set<Integer> taken = new HashSet<>(coverageAreaIndex.historicallyVisited());
        for (SeedSel s : selected) {
            taken.add(s.area().areaId());
        }
        Map<Integer, Integer> holeSizes = coverageAreaIndex.enclosedHoleSizes(taken);

        List<Cand> ranked = new ArrayList<>();
        int beyondGate = 0;
        double nextBeyondDist = Double.MAX_VALUE;
        for (UnvisitedArea a : pool) {
            int id = a.areaId();
            if (taken.contains(id)) {
                continue;
            }
            double dist = distBaseCache.getOrDefault(id, 0.0);
            if (dist > maxReachKm) {
                beyondGate++;
                if (dist < nextBeyondDist) nextBeyondDist = dist;
                continue;
            }
            double[] point = entryPoint(a);
            double reward = rewards.getOrDefault(RewardModel.categoryKey(a), 1.0);
            double adjFrac = coverageAreaIndex.neighborVisitedFraction(id, taken);
            Integer holeSize = holeSizes.get(id);
            double holeBonus = holeSize != null ? W_HOLE / (1.0 + Math.log(holeSize)) : 0.0;
            double score = reward * (1.0 + W_ADJ * adjFrac + holeBonus) / Math.pow(Math.max(EPS, dist), DIST_POW);
            ranked.add(new Cand(a, point, score, dist));
        }
        ranked.sort(Comparator.comparingDouble((Cand c) -> c.score()).reversed());

        // Wstawiaj wg score do `count`. TILES: pomiń kandydata, którego WCIĘCIE (detour cheapest-insert)
        // przekracza `maxDetourKm` — to eliminuje boczne palce i daleki wypad (Łowicz) jednym kryterium.
        int inserted = 0;
        for (Cand c : ranked) {
            if (inserted >= count) break;
            int pos = GeometryUtil.cheapestInsertPos(route, c.point());
            if (maxDetourKm < Double.MAX_VALUE) {
                double[] prev = route.get(pos - 1);
                double[] next = route.get(pos);
                double detour = GeometryUtil.hav(prev, c.point()) + GeometryUtil.hav(c.point(), next)
                        - GeometryUtil.hav(prev, next);
                if (detour > maxDetourKm) continue; // zbyt drogi objazd → nie bierz (palec/Łowicz)
            }
            route.add(pos, c.point());
            selected.add(new SeedSel(c.area(), c.point(), ordering.orderKey(c.point()), c.score(), c.distBase()));
            inserted++;
        }
        // inserted < count → ranked wyczerpane (wszyscy wzięci LUB odrzuceni przez detour).
        boolean withinGateExhausted = inserted < count;
        boolean poolExhausted = withinGateExhausted && beyondGate == 0;
        boolean jumpAhead = inserted == 0 && beyondGate > 0;
        double nextDistKm = beyondGate > 0 ? nextBeyondDist : Double.NaN;
        return new PickResult(inserted, poolExhausted, jumpAhead, nextDistKm);
    }

    private double[] entryPoint(UnvisitedArea a) {
        double[] p = coverageAreaIndex.deepestInteriorPoint(a.areaId());
        return p != null ? p : new double[]{a.lng(), a.lat()};
    }
}
