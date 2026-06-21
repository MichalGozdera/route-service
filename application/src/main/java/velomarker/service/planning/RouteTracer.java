package velomarker.service.planning;

import velomarker.exception.PlanningSessionNotReadyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import velomarker.entity.RouteCalculation;
import velomarker.entity.planning.RoutePreferences;
import velomarker.entity.planning.Waypoint;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Pętla trasowania AB/FREESTYLE/coverage-fallback: chunked BRouter + trim/grow/dedup gmin do budżetu (≤8 prób,
 * rollback do lastGood). Obiekt per-wywołanie: deps (router/cancel) + kontekst planu jako pola. Wydzielone z
 * PlanningOrchestrationService.
 */
final class RouteTracer {

    private static final Logger log = LoggerFactory.getLogger(RouteTracer.class);

    private final ChunkedBrouterRouter router;
    private final WaypointSelector waypointSelector;
    private final Consumer<UUID> checkCancel;
    private final UUID taskId;
    private final RoutePreferences prefs;
    private final List<Waypoint> waypoints;
    private final PlanningOrchestrationService.CoverageBuildInfo coverageInfo;
    private final String profile;
    private final double maxGapKm;

    RouteTracer(ChunkedBrouterRouter router, WaypointSelector waypointSelector, Consumer<UUID> checkCancel,
                UUID taskId, RoutePreferences prefs, List<Waypoint> waypoints,
                PlanningOrchestrationService.CoverageBuildInfo coverageInfo, String profile,
                double maxGapKm) {
        this.router = router;
        this.waypointSelector = waypointSelector;
        this.checkCancel = checkCancel;
        this.taskId = taskId;
        this.prefs = prefs;
        this.waypoints = waypoints;
        this.coverageInfo = coverageInfo;
        this.profile = profile;
        this.maxGapKm = maxGapKm;
    }
    TraceResult trace() {
        int budgetKm = (prefs.days() != null && prefs.kmPerDay() != null)
                ? prefs.days() * prefs.kmPerDay() : Integer.MAX_VALUE;
        double upperBudget = budgetKm * 1.10;
        double lowerBudget = budgetKm * 0.85;
        double growTargetKm = budgetKm * 0.95;

        List<Waypoint> currentWps = new ArrayList<>(waypoints);
        List<AreaCandidate> currentPicked = coverageInfo != null
                ? new ArrayList<>(coverageInfo.pickedCandidates()) : new ArrayList<>();
        List<AreaCandidate> droppedPool = new ArrayList<>();

        RouteCalculation calc = null;
        // lastGood = stan w którym (a) meta osiągnięta, (b) actualKm ≤ upperBudget.
        // Tylko taki stan jest BEZPIECZNY do zaakceptowania jako wynik.
        TraceResult lastGood = null;
        boolean dedupTried = false;

        for (int attempt = 0; attempt < 8; attempt++) {
            checkCancel.accept(taskId);
            List<double[]> input = currentWps.stream().map(Waypoint::toLngLat).toList();
            calc = router.route(taskId, input, profile);

            double endGap = computeEndGap(calc.coordinates(), prefs);
            double actualKm = calc.distanceKm();
            log.info("Routing iter={} wp={} actualKm={} budget=[{}..{}] endGap={} km picked={} dropped={}",
                    new Object[]{attempt, currentWps.size(), Math.round(actualKm),
                            Math.round(lowerBudget), Math.round(upperBudget),
                            String.format("%.2f", endGap),
                            currentPicked.size(), droppedPool.size()});

            // 1) META NIE OSIĄGNIĘTA → trim drogie z picked.
            if (endGap > maxGapKm) {
                if (currentPicked.isEmpty()) {
                    log.warn("Meta not reached and no gminy to trim — falling through to assert");
                    break;
                }
                int beforeTrim = currentPicked.size();
                CoverageAreaSelection.trimByDetourFromCurrent(currentPicked, droppedPool, 0.25);
                currentWps = CoverageAreaSelection.buildWaypointsFromPicked(prefs, CoverageAreaSelection.sortByInsertionIdx(currentPicked),
                        coverageInfo.baselineGeometry());
                log.warn("TRIM: meta not reached → wyrzucam {} drogich gmin ({} → {})",
                        new Object[]{beforeTrim - currentPicked.size(), beforeTrim, currentPicked.size()});
                continue;
            }

            // 2) META OK ale BUDGET OVERFLOW → trim 15% najdroższych.
            if (actualKm > upperBudget) {
                int beforeTrim = currentPicked.size();
                CoverageAreaSelection.trimByDetourFromCurrent(currentPicked, droppedPool, 0.15);
                if (currentPicked.size() == beforeTrim) {
                    log.info("Overflow ({} > {}) ale nic do trim — akceptujemy", new Object[]{Math.round(actualKm), Math.round(upperBudget)});
                    // Akceptujemy stan z poprzedniego iter jeśli był OK.
                    if (lastGood != null) return lastGood;
                    break;
                }
                currentWps = CoverageAreaSelection.buildWaypointsFromPicked(prefs, CoverageAreaSelection.sortByInsertionIdx(currentPicked),
                        coverageInfo.baselineGeometry());
                log.info("TRIM-OVERFLOW: actualKm {} > {} → wyrzucam {} drogich ({} → {})",
                        new Object[]{Math.round(actualKm), Math.round(upperBudget),
                                beforeTrim - currentPicked.size(), beforeTrim, currentPicked.size()});
                continue;
            }

            // 3) META OK + actualKm in [0, upperBudget] -> STAN DOBRY.
            // Zapisz lastGood tylko jesli BLIZEJ growTargetKm niz dotychczasowy best.
            // Bez tego DEDUP po dobrym iter (np. 1699 km) prowadzi do iter (750 km) ktory
            // overwrite'uje lastGood -- final wynik to 750 km mimo ze mielismy 1699 km na talerzu.
            // Logika "best": minimum |actualKm - growTargetKm|. growTargetKm = 0.95 x budget.
            double curDistToTarget = Math.abs(actualKm - growTargetKm);
            double bestDistToTarget = lastGood != null
                    ? Math.abs(lastGood.calc().distanceKm() - growTargetKm)
                    : Double.MAX_VALUE;
            if (curDistToTarget < bestDistToTarget) {
                lastGood = new TraceResult(calc, currentWps);
            }

            // 3) DEDUP -- gminy pokryte naturalnie -> usun z `currentPicked`.
            // GUARD: jesli DEDUP usuwa > 30% entry-pointow -- rollback do snapshot i break.
            // Window +-300 punktow w removeNaturallyCoveredFromPicked jest zbyt liberalny dla
            // duzej geometrii (40k punktow BRouter -> ~3 km okno, lapie obszary ktore tak
            // naprawde NIE sa naturalnie zaliczone). Skutek bez guardu: 269 -> 105 (-61%) ->
            // iter=7 BRouter krotszy o polowe -> lastGood=best wybiera state przed DEDUP, ale
            // mozemy wczesniej skonczyc petle (oszczednosc iter GROW + czytelniejsze logi).
            if (!dedupTried && coverageInfo != null && !currentPicked.isEmpty()) {
                dedupTried = true;
                int before = currentPicked.size();
                List<AreaCandidate> snapshot = new ArrayList<>(currentPicked);
                CoverageAreaSelection.removeNaturallyCoveredFromPicked(currentPicked, calc.coordinates());
                int removed = before - currentPicked.size();
                double removedRatio = before > 0 ? (double) removed / before : 0;
                if (removedRatio > 0.30) {
                    currentPicked.clear();
                    currentPicked.addAll(snapshot);
                    log.warn("DEDUP rollback: tried {} -> {} ({}% removed) zbyt agresywny -- koncze petle (lastGood ma stan z przed dedup)",
                            new Object[]{before, before - removed, Math.round(removedRatio * 100)});
                    break;
                }
                if (currentPicked.size() < before) {
                    currentWps = CoverageAreaSelection.buildWaypointsFromPicked(prefs, CoverageAreaSelection.sortByInsertionIdx(currentPicked),
                            coverageInfo.baselineGeometry());
                    log.info("DEDUP: {} -> {} picked (gminy pokryte naturalnie)",
                            new Object[]{before, currentPicked.size()});
                    continue;
                }
            }

            // Stan dobry, nic do dodania, nic do dedup — koniec.
            break;
        }

        // Po petli: preferuj lastGood (BEST stan -- najblizej growTargetKm). Jesli brak -- ostatni calc.
        if (lastGood != null) {
            if (lastGood.calc() != calc) {
                log.info("Returning lastGood: actualKm={} (vs last iter actualKm={}, growTarget={})",
                        new Object[]{Math.round(lastGood.calc().distanceKm()),
                                Math.round(calc.distanceKm()),
                                Math.round(growTargetKm)});
            }
            calc = lastGood.calc();
            currentWps = lastGood.finalWaypoints();
        }
        assertAnchorsReached(calc.coordinates(), prefs, maxGapKm);
        return new TraceResult(calc, currentWps);
    }

    /** Gap (km) od końcowego punktu geometrii do prefs.end (lub start dla loop). */
    private static double computeEndGap(List<double[]> geometry, RoutePreferences prefs) {
        if (geometry.isEmpty()) return Double.MAX_VALUE;
        Waypoint endWp = Boolean.TRUE.equals(prefs.loop()) ? prefs.start() : prefs.end();
        if (endWp == null) return 0;
        return WaypointSelector.haversineKm(geometry.get(geometry.size() - 1), endWp.toLngLat());
    }

    /**
     * Weryfikuje że BRouter rzeczywiście dotarł do każdego USER-DEFINED anchor'a (start, prefs.via[*], end).
     * NIE sprawdza gmin entry-pointów — one są naszymi heurystycznymi „checkpointami" do zaliczenia
     * gminy, nie obowiązkowymi punktami trasy. BRouter może je legalnie pomijać o ~kilkaset metrów.
     *
     * <p>Anchors są wyłuskiwane bezpośrednio z {@code prefs} — niezależne od kolejności w allWaypoints.
     */
    private void assertAnchorsReached(List<double[]> geometry, RoutePreferences prefs, double maxGapKm) {
        if (geometry.isEmpty() || prefs.start() == null) return;
        double maxGap = 0;
        String worstAnchor = null;
        // START
        double startGap = WaypointSelector.haversineKm(geometry.get(0), prefs.start().toLngLat());
        if (startGap > maxGap) { maxGap = startGap; worstAnchor = "start:" + (prefs.start().name() != null ? prefs.start().name() : "?"); }
        // END (z uwzględnieniem loop)
        Waypoint endWp = Boolean.TRUE.equals(prefs.loop()) ? prefs.start() : prefs.end();
        if (endWp != null) {
            double endGap = WaypointSelector.haversineKm(
                    geometry.get(geometry.size() - 1), endWp.toLngLat());
            if (endGap > maxGap) { maxGap = endGap; worstAnchor = "end:" + (endWp.name() != null ? endWp.name() : "?"); }
        }
        // VIA — szukamy najbliższego punktu geometrii dla każdego prefs.via[i].
        if (prefs.via() != null) {
            for (Waypoint v : prefs.via()) {
                double[] target = v.toLngLat();
                double minGap = Double.MAX_VALUE;
                for (double[] g : geometry) {
                    double d = WaypointSelector.haversineKm(g, target);
                    if (d < minGap) {
                        minGap = d;
                        if (minGap <= 0.5) break;
                    }
                }
                if (minGap > maxGap) {
                    maxGap = minGap;
                    worstAnchor = "via:" + (v.name() != null ? v.name() : "?");
                }
            }
        }
        log.info("Reachability (user anchors only): max gap {} km (worst: {})",
                new Object[]{String.format("%.2f", maxGap), worstAnchor});
        if (maxGap > maxGapKm) {
            throw new PlanningSessionNotReadyException(
                    "BRouter nie dotarł do " + worstAnchor + " (gap " + String.format("%.1f", maxGap) + " km). " +
                    "Najprawdopodobniej target-island albo zbyt wiele gmin do dolinkowania. " +
                    "Spróbuj przesunąć ten punkt o ~1 km do najbliższej drogi lub zwiększ budżet (km/dzień).");
        }
    }
}
