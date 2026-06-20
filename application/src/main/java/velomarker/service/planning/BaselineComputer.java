package velomarker.service.planning;

import velomarker.entity.RouteCalculation;
import velomarker.entity.planning.RoutePreferences;
import velomarker.entity.planning.Waypoint;
import velomarker.exception.PlanningSessionNotReadyException;
import velomarker.port.in.CalculateRouteUseCase;

import java.util.ArrayList;
import java.util.List;

/** Baseline-probe: BRouter na samych anchorach (start→via→meta) = dolna granica trasy + geometria korytarza. */
final class BaselineComputer {

    private final CalculateRouteUseCase routeUseCase;
    private final WaypointSelector waypointSelector;

    BaselineComputer(CalculateRouteUseCase routeUseCase, WaypointSelector waypointSelector) {
        this.routeUseCase = routeUseCase;
        this.waypointSelector = waypointSelector;
    }

    /** Wynik baseline-probe: dystans/wznios/prosta-anchorów + geometria. */
    record BaselineProbe(double distanceKm, double climbM, double anchorsStraightKm, List<double[]> geometry) {}
    /** Waypointy z samych anchorów (gdy korytarz pusty / pula gmin pusta). */
    static List<Waypoint> buildAnchorWaypoints(RoutePreferences prefs) {
        List<Waypoint> wps = new ArrayList<>();
        wps.add(prefs.start());
        if (prefs.via() != null) wps.addAll(prefs.via());
        if (Boolean.TRUE.equals(prefs.loop())) wps.add(prefs.start());
        else if (prefs.end() != null) wps.add(prefs.end());
        else wps.add(prefs.start());
        return wps;
    }

    /**
     * Pre-screen baseline: BRouter na samych anchorach [start, via..., end]. Wznios bierzemy z JUŻ
     * wzbogaconej geometrii ({@code CalculateRouteService} dokleja tunelo-korygowany z do coords) —
     * bez drugiego strzału w DEM. Seeduje {@code calibrator.measure} stosunkiem road/straight szkieletu.
     * Zwraca dystans/wznios/straight do dalszego użycia w reconcile (extra = pełna trasa − baseline).
     * Pada BRouter → wyjątek (plan niemożliwy).
     */
    BaselineProbe compute(RoutePreferences prefs, String profile, RoadFactorCalibrator calibrator) {
        List<double[]> anchors = new ArrayList<>();
        anchors.add(prefs.start().toLngLat());
        if (prefs.via() != null) {
            for (Waypoint v : prefs.via()) anchors.add(v.toLngLat());
        }
        if (Boolean.TRUE.equals(prefs.loop())) {
            anchors.add(prefs.start().toLngLat());
        } else if (prefs.end() != null) {
            anchors.add(prefs.end().toLngLat());
        } else {
            anchors.add(prefs.start().toLngLat());
        }
        double straight = waypointSelector.straightLineDistanceKm(anchors);
        try {
            RouteCalculation r = routeUseCase.calculate(
                    new CalculateRouteUseCase.CalculateRouteCommand(anchors, profile, false));
            calibrator.measure(r.distanceKm(), straight); // seed road-factor ze szkieletu start→meta
            return new BaselineProbe(r.distanceKm(), ascentFromCoords(r.coordinates()), straight, r.coordinates());
        } catch (RuntimeException e) {
            // Baseline OBOWIĄZKOWY: jeśli BRouter nie policzy trasy start→via→meta, plan jest niemożliwy
            // (target-island, brak segmentu) — błąd procesu, nie zgadywanie default-factorem.
            throw new PlanningSessionNotReadyException(
                    "Nie można policzyć trasy start→via→meta (" + e.getMessage() + ") — popraw start/metę/via.");
        }
    }

    /** Wznios z wzbogaconej (3D) geometrii: suma dodatnich Δz po 3. składowej. 0 gdy coords 2D (DEM padł). */
    private static double ascentFromCoords(List<double[]> coords) {
        if (coords == null || coords.size() < 2) return 0;
        double gain = 0;
        for (int i = 1; i < coords.size(); i++) {
            double[] a = coords.get(i - 1), b = coords.get(i);
            if (a.length < 3 || b.length < 3) return 0;
            double dz = b[2] - a[2];
            if (dz > 0) gain += dz;
        }
        return gain;
    }
}
