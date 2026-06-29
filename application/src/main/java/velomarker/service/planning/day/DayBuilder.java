package velomarker.service.planning.day;

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
import velomarker.entity.RouteCalculation;
import velomarker.entity.planning.Waypoint;
import velomarker.port.out.ElevationDataSource;
import velomarker.port.out.planning.SpatialIndex;
import velomarker.port.out.planning.SpatialIndexFactory;
import velomarker.entity.ElevationProfile;
import velomarker.entity.planning.PlanningSession;
import velomarker.entity.planning.PlanningSessionDay;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

// Budowa dni planu: mapowanie granic DaySplitter na pełną geometrię BRoutera + realny dystans/wznios/stats per okno.
public final class DayBuilder {

    private static final Logger log = LoggerFactory.getLogger(DayBuilder.class);
    private static final int MAX_WAYPOINTS_PER_DAY = 48;

    private static boolean sameLngLat(Waypoint a, Waypoint b) {
        return Math.abs(a.lng() - b.lng()) < 1e-9 && Math.abs(a.lat() - b.lat()) < 1e-9;
    }

    private final ElevationDataSource elevation;
    private final SpatialIndexFactory spatialIndexFactory;
    private final Consumer<UUID> checkCancel;
    private final BiConsumer<UUID, String> setPhase;

    public DayBuilder(ElevationDataSource elevation,
               SpatialIndexFactory spatialIndexFactory,
               Consumer<UUID> checkCancel, BiConsumer<UUID, String> setPhase) {
        this.elevation = elevation;
        this.spatialIndexFactory = spatialIndexFactory;
        this.checkCancel = checkCancel;
        this.setPhase = setPhase;
    }
    public List<PlanningSessionDay> build(UUID taskId, PlanningSession session, String profile,
                                          RouteCalculation full, ElevationProfile fullProfile,
                                          List<DayBoundary> boundaries, List<Waypoint> allWaypoints) {
        double[] fullCumKm = cumulativeKm(full.coordinates());
        int sampleCount = fullProfile.profile() != null ? fullProfile.profile().size() : 0;
        int fullSize = full.coordinates().size();
        double stepFull = sampleCount > 1 ? (fullSize - 1.0) / (sampleCount - 1.0) : 0.0;

        int[] wpToFullIdx = mapWaypointsToFullIndices(allWaypoints, full.coordinates());
        int wpMapped = 0;
        for (int v : wpToFullIdx) if (v >= 0) wpMapped++;
        log.info("Waypoint mapping: {}/{} planning waypoints zlokalizowane w pełnej geometrii (reszta = BRouter chunk-fail)",
                new Object[]{wpMapped, allWaypoints.size()});

        List<PlanningSessionDay> days = new ArrayList<>(boundaries.size());
        for (int i = 0; i < boundaries.size(); i++) {
            checkCancel.accept(taskId);
            int dayNumber = i + 1;
            setPhase.accept(taskId, "computing-day-" + dayNumber);
            int[] bounds = resolveFullBounds(boundaries.get(i), i == 0, i == boundaries.size() - 1, stepFull, fullSize);
            days.add(buildDay(session, profile, full, fullCumKm, dayNumber, bounds[0], bounds[1], allWaypoints, wpToFullIdx));
        }
        return days;
    }

    /** Mapuje granice w przestrzeni SAMPLE na indeksy pełnej geometrii BRoutera (+ clampy do zakresu). */
    private static int[] resolveFullBounds(DayBoundary b, boolean first, boolean last,
                                           double stepFull, int fullSize) {
        int start = first ? 0 : (int) Math.round(b.startSampleIdx() * stepFull);
        int end = last ? fullSize - 1 : (int) Math.round(b.endSampleIdx() * stepFull);
        if (start < 0) start = 0;
        if (end >= fullSize) end = fullSize - 1;
        if (end <= start) end = Math.min(start + 1, fullSize - 1);
        return new int[]{start, end};
    }

    private PlanningSessionDay buildDay(PlanningSession session, String profile, RouteCalculation full,
                                       double[] fullCumKm, int dayNumber, int fullStartIdx, int fullEndIdx,
                                       List<Waypoint> allWaypoints, int[] wpToFullIdx) {
        double realDistKm = fullCumKm[fullEndIdx] - fullCumKm[fullStartIdx];
        List<double[]> dayGeometry2D = full.coordinates().subList(fullStartIdx, fullEndIdx + 1);
        ElevationProfile dayElev = elevation.sample(dayGeometry2D, dayGeometry2D.size());
        List<double[]> dayGeometry = merge3D(dayGeometry2D, dayElev);
        List<Waypoint> dayWaypoints = dayWaypointsFromPlanning(
                allWaypoints, wpToFullIdx, fullStartIdx, fullEndIdx, full.coordinates(), dayGeometry);
        velomarker.entity.RouteStats dayStats = velomarker.service.RouteStatsSlicer.slice(
                full.stats(), full.coordinates(), fullStartIdx, fullEndIdx);
        return new PlanningSessionDay(
                UUID.randomUUID(), session.id(), dayNumber, dayGeometry, dayWaypoints,
                realDistKm, (int) Math.round(dayElev.gainM()), (int) Math.round(dayElev.lossM()),
                profile, Instant.now(), dayStats);
    }

    /** Łączy geometrię 2D [lng,lat] z wysokością z dayElev → [lng,lat,z]. */
    private static List<double[]> merge3D(List<double[]> dayGeometry2D, ElevationProfile dayElev) {
        List<double[]> eleProfile = dayElev.profile();
        int eleCount = eleProfile.size();
        List<double[]> out = new ArrayList<>(dayGeometry2D.size());
        for (int p = 0; p < dayGeometry2D.size(); p++) {
            double[] c = dayGeometry2D.get(p);
            double z = p < eleCount ? eleProfile.get(p)[1] : 0.0;
            out.add(new double[]{c[0], c[1], z});
        }
        return out;
    }

    private int[] mapWaypointsToFullIndices(List<Waypoint> wps, List<double[]> fullCoords) {
        int n = fullCoords.size();
        int m = wps.size();
        int[] map = new int[m];
        if (m == 0 || n == 0) return map;
        double[][] pts = new double[n][];
        for (int k = 0; k < n; k++) pts[k] = fullCoords.get(k);
        SpatialIndex grid = spatialIndexFactory.build(pts);
        final double TOL_KM = 0.5;
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

    private List<Waypoint> dayWaypointsFromPlanning(List<Waypoint> allWaypoints, int[] wpToFullIdx,
                                                     int fullStartIdx, int fullEndIdx,
                                                     List<double[]> fullCoords, List<double[]> dayGeometry) {
        if (allWaypoints.size() < 5) {
            return pickDayKnots(dayGeometry);
        }
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

    private static double[] cumulativeKm(List<double[]> coords) {
        double[] cum = new double[coords.size()];
        for (int i = 1; i < coords.size(); i++) {
            cum[i] = cum[i - 1] + WaypointSelector.haversineKm(coords.get(i - 1), coords.get(i));
        }
        return cum;
    }

}
