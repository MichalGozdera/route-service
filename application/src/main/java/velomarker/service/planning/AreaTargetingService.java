package velomarker.service.planning;

import velomarker.entity.planning.UnvisitedArea;

import java.util.ArrayList;
import java.util.List;

/**
 * Wybór obszarów docelowych i podział na N tras. k-means na centroidach (lat/lng):
 * jeden klaster geograficzny = jedna trasa (np. „5 tras po 2%").
 *
 * <p>Przeniesione 1:1 z assistant-service. Zmieniona tylko paczka i import UnvisitedArea.
 */
public class AreaTargetingService {

    private static final int KMEANS_ITERATIONS = 12;

    /** Dzieli nieodwiedzone obszary na maks. {@code routeCount} zwartych geograficznie tras. */
    public List<List<UnvisitedArea>> clusterIntoRoutes(List<UnvisitedArea> areas, int routeCount) {
        if (areas.isEmpty()) {
            return List.of();
        }
        int k = Math.max(1, Math.min(routeCount, areas.size()));
        if (k == 1) {
            return List.of(new ArrayList<>(areas));
        }

        double[][] centroids = new double[k][2];
        for (int i = 0; i < k; i++) {
            UnvisitedArea seed = areas.get(i * areas.size() / k);
            centroids[i] = new double[]{seed.lng(), seed.lat()};
        }

        int[] assignment = new int[areas.size()];
        for (int iter = 0; iter < KMEANS_ITERATIONS; iter++) {
            for (int i = 0; i < areas.size(); i++) {
                double[] p = new double[]{areas.get(i).lng(), areas.get(i).lat()};
                int best = 0;
                double bestDist = Double.MAX_VALUE;
                for (int c = 0; c < k; c++) {
                    double d = WaypointSelector.haversineKm(p, centroids[c]);
                    if (d < bestDist) {
                        bestDist = d;
                        best = c;
                    }
                }
                assignment[i] = best;
            }
            double[][] sum = new double[k][2];
            int[] count = new int[k];
            for (int i = 0; i < areas.size(); i++) {
                int c = assignment[i];
                sum[c][0] += areas.get(i).lng();
                sum[c][1] += areas.get(i).lat();
                count[c]++;
            }
            for (int c = 0; c < k; c++) {
                if (count[c] > 0) {
                    centroids[c][0] = sum[c][0] / count[c];
                    centroids[c][1] = sum[c][1] / count[c];
                }
            }
        }

        List<List<UnvisitedArea>> clusters = new ArrayList<>();
        for (int c = 0; c < k; c++) {
            clusters.add(new ArrayList<>());
        }
        for (int i = 0; i < areas.size(); i++) {
            clusters.get(assignment[i]).add(areas.get(i));
        }
        clusters.removeIf(List::isEmpty);
        return clusters;
    }
}
