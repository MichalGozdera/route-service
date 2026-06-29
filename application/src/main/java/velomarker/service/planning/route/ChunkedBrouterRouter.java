package velomarker.service.planning.route;

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

import async.TaskCancellationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import velomarker.entity.RouteCalculation;
import velomarker.port.in.CalculateRouteUseCase;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

// Routing BRoutera dla tras > MAX_WAYPOINTS_PER_DAY: chunki (1-pkt overlap), równoległe liczenie, sklejanie, odporność na target-island/timeout.
public final class ChunkedBrouterRouter {

    private static final Logger log = LoggerFactory.getLogger(ChunkedBrouterRouter.class);
    private static final int MAX_WAYPOINTS_PER_DAY = 48;
    private static final int MAX_PARALLEL_CHUNKS = 6;

    private final CalculateRouteUseCase routeUseCase;
    private final Consumer<UUID> checkCancel;
    private final BiConsumer<UUID, String> recordMissingTile;

    public ChunkedBrouterRouter(CalculateRouteUseCase routeUseCase, Consumer<UUID> checkCancel,
                         BiConsumer<UUID, String> recordMissingTile) {
        this.routeUseCase = routeUseCase;
        this.checkCancel = checkCancel;
        this.recordMissingTile = recordMissingTile;
    }
    public RouteCalculation route(UUID taskId, List<double[]> waypoints, String profile) {
        return route(taskId, waypoints, profile, true);
    }

    public RouteCalculation route(UUID taskId, List<double[]> waypoints, String profile, boolean computeStats) {
        java.util.Set<String> badCoordsCache = java.util.concurrent.ConcurrentHashMap.newKeySet();

        if (waypoints.size() <= MAX_WAYPOINTS_PER_DAY) {
            return calculateChunkResilient(taskId, waypoints, profile, badCoordsCache, computeStats);
        }
        List<List<double[]>> chunks = new ArrayList<>();
        int start = 0;
        while (start < waypoints.size() - 1) {
            int end = Math.min(start + MAX_WAYPOINTS_PER_DAY, waypoints.size());
            chunks.add(new ArrayList<>(waypoints.subList(start, end)));
            start = end - 1;
        }
        checkCancel.accept(taskId);
        List<RouteCalculation> results = routeChunksParallel(taskId, chunks, profile, badCoordsCache, computeStats);
        checkCancel.accept(taskId);
        return mergeChunks(results, computeStats, profile, waypoints.size(), chunks.size(), badCoordsCache.size());
    }

    private List<RouteCalculation> routeChunksParallel(UUID taskId, List<List<double[]>> chunks, String profile,
                                                      java.util.Set<String> badCoordsCache, boolean computeStats) {
        java.util.concurrent.Semaphore gate = new java.util.concurrent.Semaphore(MAX_PARALLEL_CHUNKS);
        List<RouteCalculation> results = new ArrayList<>(java.util.Collections.nCopies(chunks.size(), null));
        try (var exec = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                final int idx = i;
                futures.add(exec.submit(() -> {
                    gate.acquireUninterruptibly();
                    try {
                        results.set(idx, calculateChunkResilient(taskId, chunks.get(idx), profile, badCoordsCache, computeStats));
                    } finally {
                        gate.release();
                    }
                }));
            }
            for (var f : futures) {
                try {
                    f.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException re) throw re;
                    throw new RuntimeException(cause);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during parallel chunked BRouter", e);
                }
            }
        }
        return results;
    }

    private RouteCalculation mergeChunks(List<RouteCalculation> results, boolean computeStats, String profile,
                                        int waypointCount, int chunkCount, int badCacheSize) {
        MergeAccum accum = accumulateChunks(results);
        velomarker.entity.RouteStats aggregatedMaps = accum.aggregatedMaps();
        velomarker.entity.RouteStats aggregatedStats = new velomarker.entity.RouteStats(
                aggregatedMaps.totalMeters(),
                aggregatedMaps.surfaceMeters(),
                aggregatedMaps.roadMeters(),
                aggregatedMaps.smoothnessMeters(),
                accum.surfaceSpans(),
                accum.roadSpans(),
                accum.smoothnessSpans());
        if (computeStats) {
            logMergedStats(results, waypointCount, chunkCount, accum, badCacheSize);
        }
        return new RouteCalculation(accum.coords(), accum.totalDistKm(), java.util.List.of(), aggregatedStats,
                results.isEmpty() ? null : results.get(0).crosspointStart(),
                results.isEmpty() ? null : results.get(results.size() - 1).crosspointEnd());
    }

    private MergeAccum accumulateChunks(List<RouteCalculation> results) {
        List<double[]> mergedCoords = new ArrayList<>();
        double totalDistKm = 0;
        velomarker.service.RouteStatsAccumulator statsAcc = new velomarker.service.RouteStatsAccumulator();
        List<velomarker.entity.RouteSpan> mergedSurfaceSpans = new ArrayList<>();
        List<velomarker.entity.RouteSpan> mergedRoadSpans = new ArrayList<>();
        List<velomarker.entity.RouteSpan> mergedSmoothnessSpans = new ArrayList<>();
        for (RouteCalculation r : results) {
            int offsetIdx = mergedCoords.size() == 0 ? 0 : mergedCoords.size() - 1;
            int skipFirst = mergedCoords.isEmpty() ? 0 : 1;
            if (mergedCoords.isEmpty()) {
                mergedCoords.addAll(r.coordinates());
            } else {
                mergedCoords.addAll(r.coordinates().subList(1, r.coordinates().size()));
            }
            totalDistKm += r.distanceKm();
            statsAcc.add(r.stats());

            velomarker.entity.RouteStats chunkStats = r.stats();
            if (chunkStats != null) {
                appendSpansWithOffset(mergedSurfaceSpans, chunkStats.surfaceSpans(), offsetIdx, skipFirst);
                appendSpansWithOffset(mergedRoadSpans, chunkStats.roadSpans(), offsetIdx, skipFirst);
                appendSpansWithOffset(mergedSmoothnessSpans, chunkStats.smoothnessSpans(), offsetIdx, skipFirst);
            }
        }
        return new MergeAccum(mergedCoords, totalDistKm, mergedSurfaceSpans, mergedRoadSpans,
                mergedSmoothnessSpans, statsAcc.build());
    }

    private void logMergedStats(List<RouteCalculation> results, int waypointCount, int chunkCount,
                                MergeAccum accum, int badCacheSize) {
        log.info("BRouter chunked (parallel): {} waypoints → {} chunks → {} coords total (badCache size={})",
                new Object[]{waypointCount, chunkCount, accum.coords().size(), badCacheSize});
        int chunksWithStats = 0;
        int chunksWithSpans = 0;
        for (RouteCalculation r : results) {
            if (r.stats() != null && r.stats().totalMeters() > 0) chunksWithStats++;
            if (r.stats() != null && !r.stats().surfaceSpans().isEmpty()) chunksWithSpans++;
        }
        log.info("Chunks stats debug: {} chunks total, {} z totalMeters>0, {} z surfaceSpans niepuste. Aggregated spans: surface={} road={} smoothness={}, surfaceMeters keys={}, roadMeters keys={}",
                new Object[]{results.size(), chunksWithStats, chunksWithSpans,
                        accum.surfaceSpans().size(), accum.roadSpans().size(), accum.smoothnessSpans().size(),
                        accum.aggregatedMaps().surfaceMeters().size(), accum.aggregatedMaps().roadMeters().size()});
    }

    private static void appendSpansWithOffset(List<velomarker.entity.RouteSpan> out,
                                              List<velomarker.entity.RouteSpan> chunkSpans,
                                              int baseOffset, int skipFirst) {
        if (chunkSpans == null || chunkSpans.isEmpty()) return;
        for (velomarker.entity.RouteSpan sp : chunkSpans) {
            int s = sp.startIdx() - skipFirst;
            int e = sp.endIdx() - skipFirst;
            if (e < 0) continue;
            if (s < 0) s = 0;
            out.add(new velomarker.entity.RouteSpan(baseOffset + s, baseOffset + e, sp.code()));
        }
    }

    private static String coordKey(double[] coord) {
        return String.format(java.util.Locale.ROOT, "%.6f,%.6f", coord[0], coord[1]);
    }



    private static final java.util.regex.Pattern TARGET_ISLAND_PATTERN =
            java.util.regex.Pattern.compile("target island detected for section (\\d+)");
    private static final int MAX_ISLAND_RETRIES = MAX_WAYPOINTS_PER_DAY;

    private RouteCalculation calculateChunkWithIslandRetry(UUID taskId, List<double[]> waypoints, String profile,
                                                            java.util.Set<String> badCoordsCache, boolean computeStats) {
        List<double[]> current = new ArrayList<>(waypoints);
        int removedCount = 0;
        for (int attempt = 0; attempt <= MAX_ISLAND_RETRIES; attempt++) {
            checkCancel.accept(taskId);
            try {
                return routeUseCase.calculate(new CalculateRouteUseCase.CalculateRouteCommand(current, profile, computeStats));
            } catch (RuntimeException ex) {
                String msg = ex.getMessage() != null ? ex.getMessage() : "";
                var matcher = TARGET_ISLAND_PATTERN.matcher(msg);
                if (!matcher.find()) {
                    throw ex;
                }
                int section = Integer.parseInt(matcher.group(1));
                int badIdx = Math.max(1, Math.min(section + 1, current.size() - 1));
                if (current.size() <= 2) {
                    throw ex;
                }
                double[] removed = current.remove(badIdx);
                removedCount++;
                if (badCoordsCache != null) {
                    badCoordsCache.add(coordKey(removed));
                }
                log.warn("BRouter target-island: removed waypoint #{} [{}, {}] (section {}), retry {}/{} [blacklist size={}]",
                        new Object[]{badIdx, removed[0], removed[1], section, attempt + 1, MAX_ISLAND_RETRIES,
                                badCoordsCache != null ? badCoordsCache.size() : 0});
            }
        }
        log.error("BRouter target-island: max retries ({}) exhausted after removing {} waypoints",
                MAX_ISLAND_RETRIES, removedCount);
        return routeUseCase.calculate(new CalculateRouteUseCase.CalculateRouteCommand(current, profile, computeStats));
    }

    private RouteCalculation calculateChunkResilient(UUID taskId, List<double[]> chunk, String profile,
                                                     java.util.Set<String> badCoordsCache, boolean computeStats) {
        try {
            return calculateChunkWithIslandRetry(taskId, chunk, profile, badCoordsCache, computeStats);
        } catch (TaskCancellationException ce) {
            throw ce;
        } catch (velomarker.exception.BrouterMissingTileException mte) {
            recordMissingTile.accept(taskId, mte.tileName());
            throw mte;
        } catch (RuntimeException first) {
            try {
                return calculateChunkWithIslandRetry(taskId, chunk, profile, badCoordsCache, computeStats);
            } catch (TaskCancellationException ce) {
                throw ce;
            } catch (velomarker.exception.BrouterMissingTileException mte) {
                recordMissingTile.accept(taskId, mte.tileName());
                throw mte;
            } catch (RuntimeException second) {
                if (chunk.size() <= 3) {
                    throw second;
                }
                int mid = chunk.size() / 2;
                log.warn("BRouter chunk {} wp nie wyrobił ({}) → tnę na pół i routuję po drogach",
                        new Object[]{chunk.size(), second.getMessage()});
                RouteCalculation left = calculateChunkResilient(taskId,
                        new java.util.ArrayList<>(chunk.subList(0, mid + 1)), profile, badCoordsCache, computeStats);
                RouteCalculation right = calculateChunkResilient(taskId,
                        new java.util.ArrayList<>(chunk.subList(mid, chunk.size())), profile, badCoordsCache, computeStats);
                List<double[]> coords = new java.util.ArrayList<>(left.coordinates());
                if (!right.coordinates().isEmpty()) {
                    coords.addAll(right.coordinates().subList(1, right.coordinates().size()));
                }
                return new RouteCalculation(coords, left.distanceKm() + right.distanceKm(),
                        java.util.List.of(), velomarker.entity.RouteStats.empty(),
                        left.crosspointStart(), right.crosspointEnd());
            }
        }
    }
}
