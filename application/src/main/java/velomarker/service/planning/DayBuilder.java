package velomarker.service.planning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import velomarker.entity.RouteCalculation;
import velomarker.entity.planning.RoutePreferences;
import velomarker.entity.planning.Waypoint;
import velomarker.port.out.ElevationDataSource;
import velomarker.port.out.planning.AreaCoverageIndexFactory;
import velomarker.port.out.planning.AreaCoverageIndex;
import velomarker.port.out.planning.SpatialIndex;
import velomarker.port.out.planning.SpatialIndexFactory;
import velomarker.entity.ElevationProfile;
import velomarker.entity.planning.PlanningSession;
import velomarker.entity.planning.PlanningSessionDay;
import velomarker.entity.planning.UnvisitedArea;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Budowa dni planu: mapowanie granic DaySplitter na pełną geometrię BRoutera, realny dystans/wznios/stats/pokrycie
 * per okno + tworzenie PlanningSessionDay. Wydzielone z PlanningOrchestrationService; deps przez callbacki.
 */
final class DayBuilder {

    private static final Logger log = LoggerFactory.getLogger(DayBuilder.class);
    private static final int MAX_WAYPOINTS_PER_DAY = 48;

    private static boolean sameLngLat(Waypoint a, Waypoint b) {
        return Math.abs(a.lng() - b.lng()) < 1e-9 && Math.abs(a.lat() - b.lat()) < 1e-9;
    }

    private final ElevationDataSource elevation;
    private final AreaCoverageIndexFactory coverageIndexFactory;
    private final SpatialIndexFactory spatialIndexFactory;
    private final Consumer<UUID> checkCancel;
    private final BiConsumer<UUID, String> setPhase;

    DayBuilder(ElevationDataSource elevation, AreaCoverageIndexFactory coverageIndexFactory,
               SpatialIndexFactory spatialIndexFactory,
               Consumer<UUID> checkCancel, BiConsumer<UUID, String> setPhase) {
        this.elevation = elevation;
        this.coverageIndexFactory = coverageIndexFactory;
        this.spatialIndexFactory = spatialIndexFactory;
        this.checkCancel = checkCancel;
        this.setPhase = setPhase;
    }
    /** Buduje dni planu: mapuje granice DaySplitter na pełną geometrię BRoutera, liczy realny dystans/wznios/
     *  stats/pokrycie gmin per okno i tworzy PlanningSessionDay. Wydzielone z executePlan (faza splitting-days). */
    List<PlanningSessionDay> build(UUID taskId, PlanningSession session, RoutePreferences prefs, String profile,
                                              RouteCalculation full, ElevationProfile fullProfile,
                                              List<DaySplitter.DayBoundary> boundaries, List<Waypoint> allWaypoints,
                                              PlanningOrchestrationService.CoverageBuildInfo coverageInfo) {
            // Cumulative km dla pełnej geometrii BRouter (32k+ punktów) — używane do liczenia
            // REALNEGO dystansu dnia (zamiast nieliniowego rescale × scale z sample-space).
            double[] fullCumKm = cumulativeKm(full.coordinates());

            // Mapowanie SAMPLE-INDEX → FULL-GEOMETRY-INDEX. LocalHgtElevationClient.downsample
            // robi uniform-by-INDEX: fullIdx = round(sampleIdx × (fullSize-1)/(sampleCount-1)).
            // Dlatego MOŻEMY tu odwzorować odwrotność deterministycznie, bez przekazywania
            // mapowania przez port. Stary linear rescale (× realTotal/sampleTotal) był błędny
            // bo zakładał stałą gęstość BRouter points per km — w zakrętach jest ich więcej,
            // na prostej mniej, więc sample-km → real-km NIE jest liniowe i Dzień 5 dostawał
            // 500+ km z transferu Czechy↔Słowacja zamiast realnego dystansu.
            int sampleCount = fullProfile.profile() != null ? fullProfile.profile().size() : 0;
            int fullSize = full.coordinates().size();
            double stepFull = sampleCount > 1 ? (fullSize - 1.0) / (sampleCount - 1.0) : 0.0;

            // Mapowanie planning-waypoint → indeks w pełnej geometrii BRoutera (raz, dla wszystkich dni).
            // Per dzień bierzemy WSZYSTKIE entry-pointy gmin tego dnia jako dayWaypoints (zamiast 48
            // evenly-spaced z pickDayKnots) — user może edytowac każdą zaplanowaną gminę osobno
            // bez utraty creditu sąsiednich.
            int[] wpToFullIdx = mapWaypointsToFullIndices(allWaypoints, full.coordinates());
            int wpMapped = 0;
            for (int v : wpToFullIdx) if (v >= 0) wpMapped++;
            log.info("Waypoint mapping: {}/{} planning waypoints zlokalizowane w pełnej geometrii (reszta = BRouter chunk-fail)",
                    new Object[]{wpMapped, allWaypoints.size()});

            // v3.18 FIX C: indeks pokrycia nad pulą (kandydaci po bbox) — per-dzień ID gmin ZALICZONYCH
            // (kryterium kredytu ≥200 m, port JTS) = źródło prawdy dla kolorowania na froncie (kolor=prawda).
            AreaCoverageIndex coverageIndex = null;
            if (coverageInfo != null) {
                List<UnvisitedArea> coveragePool = new ArrayList<>();
                if (coverageInfo.pickedCandidates() != null)
                    for (AreaCandidate c : coverageInfo.pickedCandidates()) coveragePool.add(c.area);
                if (!coveragePool.isEmpty()) coverageIndex = coverageIndexFactory.build(coveragePool);
            }
            List<PlanningSessionDay> days = new ArrayList<>(boundaries.size());
            for (int i = 0; i < boundaries.size(); i++) {
                checkCancel.accept(taskId);
                int dayNumber = i + 1;
                setPhase.accept(taskId, "computing-day-" + dayNumber);
                DaySplitter.DayBoundary b = boundaries.get(i);
                int fullStartIdx = (int) Math.round(b.startSampleIdx() * stepFull);
                int fullEndIdx = (int) Math.round(b.endSampleIdx() * stepFull);
                // Inwariant: ostatni dzień KOŃCZY na fullSize-1 (=meta), pierwszy ZACZYNA na 0.
                if (i == 0) fullStartIdx = 0;
                if (i == boundaries.size() - 1) fullEndIdx = fullSize - 1;
                if (fullStartIdx < 0) fullStartIdx = 0;
                if (fullEndIdx >= fullSize) fullEndIdx = fullSize - 1;
                if (fullEndIdx <= fullStartIdx) fullEndIdx = Math.min(fullStartIdx + 1, fullSize - 1);

                double realDistKm = fullCumKm[fullEndIdx] - fullCumKm[fullStartIdx];

                List<double[]> dayGeometry2D = full.coordinates().subList(fullStartIdx, fullEndIdx + 1);
                // Pełna granulacja sampling per dzień — bez tego default cap 500 dawał ~566m między
                // próbkami (dla 283 km dnia) → gubił wzgórki, gain spadał z 1967m → 1304m. Frontend
                // licząc z pełnej geometrii (~23m między coordami) pokazuje prawdziwą wartość.
                ElevationProfile dayElev = elevation.sample(dayGeometry2D, dayGeometry2D.size());
                // ENRICH coords z z (wysokością) — by polyline3D miało realne z, NIE 0. Frontend
                // wtedy używa z BEZPOŚREDNIO (gpsAltitudeAvailable=true) zamiast wołać /route/elevation
                // → ta sama wartość gain w storage i UI, zero rozjazdu. dayElev.profile() ma 1:1 mapowanie
                // po pełnej granulacji (downsample no-op, każdy coord ma swój HGT lookup).
                List<double[]> dayGeometry = new ArrayList<>(dayGeometry2D.size());
                List<double[]> eleProfile = dayElev.profile();
                int eleCount = eleProfile.size();
                for (int p = 0; p < dayGeometry2D.size(); p++) {
                    double[] c = dayGeometry2D.get(p);
                    double z = p < eleCount ? eleProfile.get(p)[1] : 0.0;
                    dayGeometry.add(new double[]{c[0], c[1], z});
                }
                List<Waypoint> dayWaypoints = dayWaypointsFromPlanning(
                        allWaypoints, wpToFullIdx, fullStartIdx, fullEndIdx,
                        full.coordinates(), dayGeometry);

                // Slice stats całej trasy dla okna [fullStartIdx, fullEndIdx] — daje panel "Typy
                // nawierzchni / dróg" gotowy do wyświetlenia per dzień na FE, bez ponownego BRouter call.
                velomarker.entity.RouteStats dayStats = velomarker.service.RouteStatsSlicer.slice(
                        full.stats(), full.coordinates(), fullStartIdx, fullEndIdx);
                // v3.18 FIX C: gminy zaliczone przez geometrię tego dnia (kryterium kredytu portu JTS).
                List<Integer> coveredAreaIds = coverageIndex != null
                        ? new ArrayList<>(coverageIndex.visitedAreaIds(dayGeometry))
                        : java.util.List.of();
                days.add(new PlanningSessionDay(
                        UUID.randomUUID(),
                        session.id(),
                        dayNumber,
                        dayGeometry,
                        dayWaypoints,
                        realDistKm,
                        (int) Math.round(dayElev.gainM()),
                        (int) Math.round(dayElev.lossM()),
                        profile,
                        Instant.now(),
                        dayStats,
                        coveredAreaIds
                ));
            }
        return days;
    }

    /**
     * Mapowanie waypoint-planera → indeks w pełnej geometrii BRoutera. BRouter **SNAPUJE** waypointy
     * do najbliższego punktu drogi (offset 5-50 m), więc dokładny hash-match nie działa. Poprzednia
     * iteracja z 2-pointer'em + bounded window kaskadowo failowała: gdy pierwszy wp nie trafił w
     * okno, j stało, kolejne wp też failowały (stąd 8/348).
     *
     * <p>Teraz: {@link velomarker.port.out.planning.SpatialIndex} nad fullCoords → per wp `nearestIndexTo(wp.lng, wp.lat)` = O(1)
     * per query. Tolerancja 500m → wp z dystansem &gt; 500m oznacza prawdziwy chunk-fail.
     */
    private int[] mapWaypointsToFullIndices(List<Waypoint> wps, List<double[]> fullCoords) {
        int n = fullCoords.size();
        int m = wps.size();
        int[] map = new int[m];
        if (m == 0 || n == 0) return map;
        double[][] pts = new double[n][];
        for (int k = 0; k < n; k++) pts[k] = fullCoords.get(k);
        SpatialIndex grid = spatialIndexFactory.build(pts);
        final double TOL_KM = 0.5; // 500 m — BRouter snap typowo &lt; 50m, próg generosa
        for (int i = 0; i < m; i++) {
            Waypoint wp = wps.get(i);
            int idx = grid.nearestIndexTo(wp.lng(), wp.lat());
            if (idx < 0) {
                map[i] = -1;
                continue;
            }
            double distKm = grid.distKmFromExternal(idx, wp.lng(), wp.lat());
            map[i] = distKm <= TOL_KM ? idx : -1;
        }
        return map;
    }

    /** Per dzień: bierze entry-pointy plannera ({@code allWaypoints}) wpadające w zakres dnia
     *  ({@code [fullStartIdx, fullEndIdx]}). Dla mode AB (brak gmin — `allWaypoints.size() < 5`)
     *  fallback do evenly-spaced {@link #pickDayKnots}. Granice dnia: jeśli pierwszy/ostatni
     *  waypoint NIE jest dokładnie na granicy dnia, dorzuca kotwicę z {@code dayGeometry}. */
    private List<Waypoint> dayWaypointsFromPlanning(List<Waypoint> allWaypoints, int[] wpToFullIdx,
                                                     int fullStartIdx, int fullEndIdx,
                                                     List<double[]> fullCoords, List<double[]> dayGeometry) {
        if (allWaypoints.size() < 5) {
            return pickDayKnots(dayGeometry); // AB / brak gmin
        }
        // ANCHOR-PROXIMITY: jeśli któryś wp mapuje się DOKŁADNIE na fullStartIdx/fullEndIdx
        // (próg ±3 coords → toleruje snap BRoutera w okolicy granicy dnia), to ten wp pełni rolę kotwicy.
        // Inaczej dorzucimy syntetyczną Waypoint(startCoord/endCoord). Bez tej proximity-tolerancji
        // równość po lng/lat dawała duplikaty (planner wp + snapped coord = różne wartości).
        final int BOUNDARY_TOL_COORDS = 3;
        List<Waypoint> dayWps = new ArrayList<>();
        boolean hasStartAnchor = false;
        boolean hasEndAnchor = false;
        for (int k = 0; k < allWaypoints.size(); k++) {
            int idx = wpToFullIdx[k];
            if (idx < 0) continue;
            if (idx >= fullStartIdx && idx <= fullEndIdx) {
                dayWps.add(allWaypoints.get(k));
                if (idx <= fullStartIdx + BOUNDARY_TOL_COORDS) hasStartAnchor = true;
                if (idx >= fullEndIdx - BOUNDARY_TOL_COORDS) hasEndAnchor = true;
            }
        }
        if (!hasStartAnchor) {
            double[] startCoord = fullCoords.get(fullStartIdx);
            dayWps.add(0, new Waypoint(startCoord[0], startCoord[1], null));
        }
        if (!hasEndAnchor) {
            double[] endCoord = fullCoords.get(fullEndIdx);
            dayWps.add(new Waypoint(endCoord[0], endCoord[1], null));
        }
        return dayWps;
    }

    /** „Kotwice" dla edycji dnia — co N-ty punkt geometrii, max MAX_WAYPOINTS_PER_DAY. */
    private List<Waypoint> pickDayKnots(List<double[]> geometry) {
        if (geometry.isEmpty()) return List.of();
        int total = geometry.size();
        int target = Math.min(MAX_WAYPOINTS_PER_DAY, Math.max(2, total / 50));
        List<Waypoint> result = new ArrayList<>(target);
        int step = Math.max(1, total / target);
        for (int i = 0; i < total; i += step) {
            double[] p = geometry.get(i);
            result.add(new Waypoint(p[0], p[1], null));
        }
        double[] last = geometry.get(total - 1);
        Waypoint lastWp = new Waypoint(last[0], last[1], null);
        if (result.isEmpty() || !sameLngLat(result.get(result.size() - 1), lastWp)) {
            result.add(lastWp);
        }
        return result;
    }

    /** Cumulative dystans w km dla każdego punktu pełnej geometrii BRouter (haversine między sąsiednimi). */
    private static double[] cumulativeKm(List<double[]> coords) {
        double[] cum = new double[coords.size()];
        for (int i = 1; i < coords.size(); i++) {
            cum[i] = cum[i - 1] + WaypointSelector.haversineKm(coords.get(i - 1), coords.get(i));
        }
        return cum;
    }

}
