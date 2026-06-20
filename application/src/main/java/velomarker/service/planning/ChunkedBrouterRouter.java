package velomarker.service.planning;

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

/**
 * Routing BRoutera dla tras > MAX_WAYPOINTS_PER_DAY: tnie na chunki (1-pkt overlap), liczy RÓWNOLEGLE (gate),
 * skleja coords+stats, z odpornością na target-island (retry usuwając wyspę) i timeout (1 retry → split na pół).
 * Wydzielone z PlanningOrchestrationService. Zależności (cancel-check, rejestracja brakujących tile) jako callbacki.
 */
final class ChunkedBrouterRouter {

    private static final Logger log = LoggerFactory.getLogger(ChunkedBrouterRouter.class);
    private static final int MAX_WAYPOINTS_PER_DAY = 48;
    private static final int MAX_PARALLEL_CHUNKS = 6;

    private final CalculateRouteUseCase routeUseCase;
    private final Consumer<UUID> checkCancel;
    private final BiConsumer<UUID, String> recordMissingTile;

    ChunkedBrouterRouter(CalculateRouteUseCase routeUseCase, Consumer<UUID> checkCancel,
                         BiConsumer<UUID, String> recordMissingTile) {
        this.routeUseCase = routeUseCase;
        this.checkCancel = checkCancel;
        this.recordMissingTile = recordMissingTile;
    }
    RouteCalculation route(UUID taskId, List<double[]> waypoints, String profile) {
        return route(taskId, waypoints, profile, true);
    }

    /**
     * @param computeStats {@code true} = agreguj per-chunk stats + loguj agregat całej trasy.
     *        {@code false} = skip stats wszędzie (per-chunk routeUseCase.calculate też dostaje false)
     *        — używane przez Coverage/TSP/Coverage dla intermediate probing (~10k+ calls per coverage plan)
     *        gdzie stats nie są używane a logi zalewały konsolę.
     */
    RouteCalculation route(UUID taskId, List<double[]> waypoints, String profile, boolean computeStats) {
        // GLOBALNY cache bad waypoints (target-island). Thread-safe bo chunki liczone RÓWNOLEGLE.
        java.util.Set<String> badCoordsCache = java.util.concurrent.ConcurrentHashMap.newKeySet();

        if (waypoints.size() <= MAX_WAYPOINTS_PER_DAY) {
            return calculateChunkResilient(taskId, waypoints, profile, badCoordsCache, computeStats);
        }
        // Granice chunków (≤MAX_WAYPOINTS_PER_DAY, 1-pkt overlap: koniec N = początek N+1).
        List<List<double[]>> chunks = new ArrayList<>();
        int start = 0;
        while (start < waypoints.size() - 1) {
            int end = Math.min(start + MAX_WAYPOINTS_PER_DAY, waypoints.size());
            chunks.add(new ArrayList<>(waypoints.subList(start, end)));
            start = end - 1;
        }
        checkCancel.accept(taskId);
        // Chunki są NIEZALEŻNE (rozłączne waypointy poza 1-pkt overlapem) → licz RÓWNOLEGLE.
        // ALE zbuj współbieżność LOKALNYM semaforem (≤ MAX_PARALLEL_CHUNKS), inaczej np. 42 chunki
        // (cała Polska) wrzucone naraz przepełniają semafor BRoutera (max-concurrent, wait 5s) →
        // ogon czeka >5s → BrouterUnavailable (429). Lokalny gate nie ma
        // timeoutu, więc tylko bounduje, nie odrzuca. Pre-filtr badCoordsCache pominięty (cache pusty).
        List<RouteCalculation> results = routeChunksParallel(taskId, chunks, profile, badCoordsCache, computeStats);
        checkCancel.accept(taskId);
        return mergeChunks(results, computeStats, profile, waypoints.size(), chunks.size(), badCoordsCache.size());
    }

    /** Policz chunki RÓWNOLEGLE (virtual threads, gate ≤MAX_PARALLEL_CHUNKS by nie przepełnić Semafora BRoutera). */
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

    /** Sklej wyniki chunków w jedną trasę (coords bez duplikatu overlapu) + zagreguj stats/spans z offsetem; loguje gdy computeStats. */
    private RouteCalculation mergeChunks(List<RouteCalculation> results, boolean computeStats, String profile,
                                        int waypointCount, int chunkCount, int badCacheSize) {
        // Sklejanie W KOLEJNOŚCI chunków, pomijając zduplikowany punkt overlapu.
        // Stats: meters agregujemy przez accumulator + spans łączymy z offsetem per chunk
        // (indeksy w spans CHUNK'a są lokalne; po sklejaniu mergedCoords muszą być globalne).
        List<double[]> mergedCoords = new ArrayList<>();
        double totalDistKm = 0;
        velomarker.service.RouteStatsAccumulator statsAcc = new velomarker.service.RouteStatsAccumulator();
        List<velomarker.entity.RouteSpan> mergedSurfaceSpans = new ArrayList<>();
        List<velomarker.entity.RouteSpan> mergedRoadSpans = new ArrayList<>();
        List<velomarker.entity.RouteSpan> mergedSmoothnessSpans = new ArrayList<>();
        for (RouteCalculation r : results) {
            int offsetIdx = mergedCoords.size() == 0 ? 0 : mergedCoords.size() - 1; // -1 bo następny chunk pomija pierwszy overlap waypoint
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
                int baseOffset = offsetIdx;
                int firstSkip = skipFirst;
                appendSpansWithOffset(mergedSurfaceSpans, chunkStats.surfaceSpans(), baseOffset, firstSkip);
                appendSpansWithOffset(mergedRoadSpans, chunkStats.roadSpans(), baseOffset, firstSkip);
                appendSpansWithOffset(mergedSmoothnessSpans, chunkStats.smoothnessSpans(), baseOffset, firstSkip);
            }
        }
        velomarker.entity.RouteStats aggregatedMaps = statsAcc.build();
        velomarker.entity.RouteStats aggregatedStats = new velomarker.entity.RouteStats(
                aggregatedMaps.totalMeters(),
                aggregatedMaps.surfaceMeters(),
                aggregatedMaps.roadMeters(),
                aggregatedMaps.smoothnessMeters(),
                mergedSurfaceSpans,
                mergedRoadSpans,
                mergedSmoothnessSpans);
        if (computeStats) {
            log.info("BRouter chunked (parallel): {} waypoints → {} chunks → {} coords total (badCache size={})",
                    new Object[]{waypointCount, chunkCount, mergedCoords.size(), badCacheSize});
            // Debug: ile stats z chunks faktycznie dotarło
            int chunksWithStats = 0;
            int chunksWithSpans = 0;
            for (RouteCalculation r : results) {
                if (r.stats() != null && r.stats().totalMeters() > 0) chunksWithStats++;
                if (r.stats() != null && !r.stats().surfaceSpans().isEmpty()) chunksWithSpans++;
            }
            log.info("Chunks stats debug: {} chunks total, {} z totalMeters>0, {} z surfaceSpans niepuste. Aggregated spans: surface={} road={} smoothness={}, surfaceMeters keys={}, roadMeters keys={}",
                    new Object[]{results.size(), chunksWithStats, chunksWithSpans,
                            mergedSurfaceSpans.size(), mergedRoadSpans.size(), mergedSmoothnessSpans.size(),
                            aggregatedMaps.surfaceMeters().size(), aggregatedMaps.roadMeters().size()});
            log.info(velomarker.service.RouteStatsFormatter.format(aggregatedStats,
                    "Statystyki całej trasy (chunked, profil: " + profile + ")"));
        }
        return new RouteCalculation(mergedCoords, totalDistKm, java.util.List.of(), aggregatedStats);
    }

    /**
     * Przesuwa spans z lokalnych indeksów chunku do globalnych (mergedCoords). {@code baseOffset}
     * to liczba wierzchołków już sklejonych PRZED tym chunkiem; {@code skipFirst} = 1 dla chunków
     * po pierwszym (overlap waypoint pomijany). Span [a, b] chunku → [baseOffset + a - skipFirst,
     * baseOffset + b - skipFirst]. Pierwszy chunk: skipFirst=0, baseOffset=0.
     */
    private static void appendSpansWithOffset(List<velomarker.entity.RouteSpan> out,
                                              List<velomarker.entity.RouteSpan> chunkSpans,
                                              int baseOffset, int skipFirst) {
        if (chunkSpans == null || chunkSpans.isEmpty()) return;
        for (velomarker.entity.RouteSpan sp : chunkSpans) {
            int s = sp.startIdx() - skipFirst;
            int e = sp.endIdx() - skipFirst;
            if (e < 0) continue; // span całkowicie w pomijanym pierwszym wierzchołku
            if (s < 0) s = 0;
            out.add(new velomarker.entity.RouteSpan(baseOffset + s, baseOffset + e, sp.code()));
        }
    }

    /** Klucz coords do cache (6 decimal precision = ~10cm; chunkuje float arytmetykę). */
    private static String coordKey(double[] coord) {
        return String.format(java.util.Locale.ROOT, "%.6f,%.6f", coord[0], coord[1]);
    }



    private static final java.util.regex.Pattern TARGET_ISLAND_PATTERN =
            java.util.regex.Pattern.compile("target island detected for section (\\d+)");
    // = rozmiar chunka: pozwala wydropować CAŁY klaster wysp w chunku (np. 9+ nieroutowalnych
    // waypointów nad Odrą) zamiast wywalać cały finalny/reconcile call. Limit 8 był za mały →
    // "za często się sypie" + fallback do prostych linii. Retry bounduje realna liczba wysp.
    private static final int MAX_ISLAND_RETRIES = MAX_WAYPOINTS_PER_DAY;

    /**
     * Wywołuje BRouter z retry: gdy zwróci 400 "target island detected for section N", usuwa
     * islanded waypoint i ponawia. Po MAX_ISLAND_RETRIES propaguje wyjątek.
     *
     * <p><b>Indeksacja (wg źródła BRoutera, RoutingEngine.tryFindTrack):</b> dla "section i"
     * target-island-check robi {@code findTrack(matchedWaypoints[i+1] → matchedWaypoints[i])} i rzuca
     * gdy {@code seg==null && nodeLimit>0} — czyli gdy {@code matchedWaypoints[i+1]} siedzi w MAŁYM
     * (&lt; MAXNODES_ISLAND_CHECK=500) odciętym komponencie drogowym (floodplain/wysepka/ślepy stub),
     * a NIE gdy brak dalekiego mostu (wtedy nodeLimit→0, brak wyjątku). {@code matchedWaypoints[0]}=start,
     * więc indeksy 1:1 z naszą listą → <b>wyspą jest {@code current[i+1]}, NIE {@code current[i]}</b>.
     * Usuwanie {@code i} (dawny bug) zostawiało wyspę i kasowało dobry punkt PRZED nią → kaskada
     * #N→…→#1 mordowała cały pas (pocket nad Wisłą). Usuwamy {@code i+1} = jedno celne usunięcie.
     *
     * <p>Iter 8: {@code badCoordsCache} — wspólna blacklist między chunkami. Każdy removed waypoint
     * trafia tutaj → kolejny chunk pre-filtruje go BEZ retry.
     */
    private RouteCalculation calculateChunkWithIslandRetry(UUID taskId, List<double[]> waypoints, String profile,
                                                            java.util.Set<String> badCoordsCache) {
        return calculateChunkWithIslandRetry(taskId, waypoints, profile, badCoordsCache, true);
    }

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
                    throw ex; // inny błąd — nie maskujemy
                }
                int section = Integer.parseInt(matcher.group(1));
                // Wyspa = target segmentu i→i+1 = current[i+1] (patrz javadoc / źródło BRoutera).
                int badIdx = Math.max(1, Math.min(section + 1, current.size() - 1));
                if (current.size() <= 2) {
                    throw ex; // za mało punktów żeby coś usuwać
                }
                double[] removed = current.remove(badIdx);
                removedCount++;
                // Iter 8: dodaj do globalnego cache. Kolejny chunk go pre-filtruje przed retry.
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

    /** Convenience overload bez cache — dla wywołań nie-chunked (probe, baseline). */
    private RouteCalculation calculateChunkWithIslandRetry(UUID taskId, List<double[]> waypoints, String profile) {
        return calculateChunkWithIslandRetry(taskId, waypoints, profile, new java.util.HashSet<>());
    }

    /**
     * Liczy chunk po DROGACH, odpornie na timeout/upstream BRoutera — NIGDY prostą linią (user: „chcę
     * drogowe trasy"). Na błąd: 1 retry (przejściowe nasycenie BRoutera), a gdy dalej pada — TNIE chunk
     * na pół (krótsze zapytania = szybszy BRouter, dalej drogowe) i skleja rekurencyjnie. Chunk ≤3 wp,
     * który mimo to nie wyrabia, propaguje wyjątek (po bumpie timeoutu to rzadkość — lepiej zgłosić niż
     * narysować prostą). TaskCancellation przechodzi (anuluj = anuluj).
     */
    private RouteCalculation calculateChunkResilient(UUID taskId, List<double[]> chunk, String profile,
                                                     java.util.Set<String> badCoordsCache) {
        return calculateChunkResilient(taskId, chunk, profile, badCoordsCache, true);
    }

    private RouteCalculation calculateChunkResilient(UUID taskId, List<double[]> chunk, String profile,
                                                     java.util.Set<String> badCoordsCache, boolean computeStats) {
        try {
            return calculateChunkWithIslandRetry(taskId, chunk, profile, badCoordsCache, computeStats);
        } catch (TaskCancellationException ce) {
            throw ce;
        } catch (velomarker.exception.BrouterMissingTileException mte) {
            recordMissingTile.accept(taskId, mte.tileName());   // brak tile = retry/split bezsensowny (ten sam region)
            throw mte;
        } catch (RuntimeException first) {
            try {
                return calculateChunkWithIslandRetry(taskId, chunk, profile, badCoordsCache, computeStats); // 1 retry
            } catch (TaskCancellationException ce) {
                throw ce;
            } catch (velomarker.exception.BrouterMissingTileException mte) {
                recordMissingTile.accept(taskId, mte.tileName());
                throw mte;
            } catch (RuntimeException second) {
                if (chunk.size() <= 3) {
                    throw second; // za mały by dzielić — propaguj (rzadkie po bumpie timeoutu)
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
                    coords.addAll(right.coordinates().subList(1, right.coordinates().size())); // pomiń overlap
                }
                return new RouteCalculation(coords, left.distanceKm() + right.distanceKm());
            }
        }
    }
}
