package velomarker.service.planning.coverage.scoring;

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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import velomarker.entity.planning.UnvisitedArea;
import velomarker.port.out.planning.SpatialIndexFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

// Model nagrody za pokrycie gminy: reward kategorii wg gęstości sąsiadów. Bezstanowy.
public final class RewardModel {

    private static final Logger log = LoggerFactory.getLogger(RewardModel.class);
    private static final double REFERENCE_DIST_KM = 10.0;

    private RewardModel() {}

    public static String categoryKey(UnvisitedArea a) {
        return a.countryId() + ":" + a.levelId() + ":" + a.specialGroupId();
    }

    public static String formatCategoryKey(String key) {
        String[] parts = key.split(":");
        if (parts.length < 3) return key;
        String country = "C" + parts[0];
        String specialGroup = parts[2];
        if (!"null".equals(specialGroup)) return country + "/sg" + specialGroup;
        return country + "/L" + parts[1];
    }

    public static String breakdown(Set<Integer> visited, Map<Integer, String> areaCategory) {
        Map<String, Integer> perCategory = new TreeMap<>();
        for (int id : visited) {
            String cat = areaCategory.get(id);
            if (cat != null) perCategory.merge(cat, 1, Integer::sum);
        }
        return perCategory.toString();
    }

    public static Map<String, Double> rewardPerCategory(List<UnvisitedArea> pool, SpatialIndexFactory factory) {
        Map<String, List<UnvisitedArea>> byCategory = new HashMap<>();
        for (UnvisitedArea a : pool) {
            byCategory.computeIfAbsent(categoryKey(a), k -> new ArrayList<>()).add(a);
        }
        Map<String, Double> reward = new HashMap<>();
        StringBuilder logLine = new StringBuilder();
        for (var entry : byCategory.entrySet()) {
            double avgNearestKm = CoverageAreaIndex.avgNearestNeighborDistKm(entry.getValue(), factory);
            double r = Math.max(0.1, avgNearestKm > 0 ? avgNearestKm / REFERENCE_DIST_KM : 1.0);
            reward.put(entry.getKey(), r);
            if (logLine.length() > 0) logLine.append(", ");
            logLine.append(formatCategoryKey(entry.getKey())).append("=")
                    .append(String.format("%.2f", r))
                    .append(" (NN ").append(String.format("%.1f", avgNearestKm)).append("km, n=")
                    .append(entry.getValue().size()).append(")");
        }
        log.info("Coverage reward per category (refDist={}km): {{{}}}",
                new Object[]{REFERENCE_DIST_KM, logLine});
        return reward;
    }
}
