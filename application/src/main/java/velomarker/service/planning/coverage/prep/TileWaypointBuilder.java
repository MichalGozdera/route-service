package velomarker.service.planning.coverage.prep;

import velomarker.service.planning.*;
import velomarker.service.planning.coverage.*;
import velomarker.service.planning.coverage.index.*;
import velomarker.service.planning.coverage.metric.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import velomarker.entity.planning.RoutePreferences;
import velomarker.entity.planning.UnvisitedArea;
import velomarker.entity.planning.Waypoint;
import velomarker.tile.TileMath;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Przygotowanie puli kandydatów dla trybu TILES — analog {@link CoverageWaypointBuilder}, ale BEZ
 * visit-service. Kandydaci to kafelki siatki slippy-map (StatsHunters/VeloViewer kwadraty)
 * wygenerowani z geometrii w korytarzu start→meta, z wyłączeniem kafelków już zdobytych
 * ({@code prefs.tileOwned()}).
 *
 * <p><b>E1.7 — kafelek to POLIGON:</b> kandydaci i owned to kwadraty-poligony ({@code tileToPolygonRing}),
 * podawane temu samemu {@code JtsAreaCoverageIndex} co gminy (przez wstrzyknięty {@code coverageFactory},
 * {@code portOverride=null}). Zero własnego portu — passages/entry/kotwiczenie liczy oszlifowany JTS,
 * więc SpurCutter konwerguje jak dla gmin. Builder sam woła {@code coveragePlanner.plan(...)} i zwraca
 * {@link CoverageResult} — orchestration wstawia go do {@code WaypointBuild} (ten sam downstream day-split).
 *
 * <p><b>Zakres:</b> objective ignorowane poza walidacją — zawsze maksymalizacja pokrycia kafelków
 * (COVERAGE). SQUARE/CLUSTER = późniejszy etap.
 */
public final class TileWaypointBuilder {

    private static final Logger log = LoggerFactory.getLogger(TileWaypointBuilder.class);

    /** Twardy cap liczby kandydatów-kafelków — chroni pamięć/czas przy szerokim korytarzu / niskim zoom. */
    private static final int MAX_TILE_CANDIDATES = 12_000;
    private static final double MIN_MARGIN_KM = 5.0;
    private static final double MAX_MARGIN_KM = 40.0;
    private static final double KM_PER_DEG_LAT = 110.574;
    /** Progi głębokości wjazdu (m) dla kafelków: kredyt 50 (zaliczenie = „lekki wjazd"), deep 70 (kotwiczenie
     *  Anchorer + cięcie zaułków SpurCutter). Mniejsze niż gminowe 200/220 — kafelek z14 ~2.4km, głęboki wjazd zbędny. */
    private static final double TILE_CREDIT_DEPTH_M = 50.0;
    private static final double TILE_DEEP_DEPTH_M = 70.0;

    private final CoveragePlanner coveragePlanner;
    private final Consumer<java.util.UUID> checkCancel;
    private final BiConsumer<java.util.UUID, String> setPhase;

    public TileWaypointBuilder(CoveragePlanner coveragePlanner,
                               Consumer<java.util.UUID> checkCancel,
                               BiConsumer<java.util.UUID, String> setPhase) {
        this.coveragePlanner = coveragePlanner;
        this.checkCancel = checkCancel;
        this.setPhase = setPhase;
    }

    /**
     * @return wynik plannera (geometria + waypointy + zaliczone kafelki) — do {@code WaypointBuild.coverageResult}.
     *         Gdy planner niedostępny zwraca {@code null} (orchestration zrobi fallback do anchor-only).
     */
    public CoverageResult build(java.util.UUID taskId, RoutePreferences prefs, String profile,
                                BrouterFn brouter, Consumer<Boolean> snapToggle,
                                PlanTraceSink traceSink, PlanTimings timings) {
        setPhase.accept(taskId, "tile-candidates");
        checkCancel.accept(taskId);

        int z = prefs.tileZoom() != null ? prefs.tileZoom() : 14;
        Set<Long> ownedKeys = ownedKeys(prefs);
        List<UnvisitedArea> candidates = generateCorridorTiles(prefs, z, ownedKeys);
        log.info("TILES: zoom={} owned={} candidates={} (po wycięciu owned + cap)",
                new Object[]{z, ownedKeys.size(), candidates.size()});

        if (candidates.isEmpty()) {
            log.warn("TILES: korytarz nie ma zadnego niezdobytego kafelka — fallback do anchor-only");
            return null;
        }

        if (coveragePlanner == null) {
            log.warn("TILES: coveragePlanner absent — fallback do anchor-only");
            return null;
        }
        setPhase.accept(taskId, "coverage-planning");
        checkCancel.accept(taskId);

        // E1.7: kafelek to POLIGON (kwadrat). Dajemy go temu samemu JtsAreaCoverageIndex co gminy
        // (portOverride=null → buildEngine użyje wstrzykniętego coverageFactory). Zero własnego portu:
        // passages/entry/kotwiczenie liczy JTS (oszlifowany), więc SpurCutter konwerguje jak dla gmin.
        // owned-kafelki = historicallyVisited (poligony) → wchodzą w sąsiedztwo/dziury, nie liczą się
        // jako zaliczenia trasy. skipFinalize=false → FinalizePhase (Anchorer+SpurCutter+grow/peel).
        List<UnvisitedArea> ownedAreas = buildOwnedTileAreas(ownedKeys, z);
        double reachCapKm = reachCapKm(prefs);
        double maxDetourKm = DETOUR_K * tileSideKm(candidates.get(0));
        log.info("TILES: reachCap={}km maxDetour={}km", new Object[]{Math.round(reachCapKm), Math.round(maxDetourKm * 10) / 10.0});
        return coveragePlanner.plan(candidates, ownedAreas, null, prefs, profile,
                brouter, snapToggle, Map.of(), traceSink, timings, null, false, reachCapKm, maxDetourKm,
                TILE_CREDIT_DEPTH_M, TILE_DEEP_DEPTH_M);
    }

    /** Mnożnik progu objazdu względem boku kafelka: detour > K×bok → kafelek odrzucony (palec/wypad). */
    private static final double DETOUR_K = 3.0;

    /** Bok kafelka w km (haversine dwóch sąsiednich rogów ringu). */
    private static double tileSideKm(UnvisitedArea tile) {
        double[][] ring = tile.ring();
        if (ring == null || ring.length < 2) return 2.0;
        return velomarker.service.planning.WaypointSelector.haversineKm(ring[0], ring[1]);
    }

    /** Cap zasięgu skoku greedy dla TILES: proporcjonalny do km/dzień (zwartość vs budżet). */
    private static double reachCapKm(RoutePreferences prefs) {
        int kmPerDay = prefs.kmPerDay() != null ? prefs.kmPerDay() : 100;
        return Math.max(CoveragePlanner.TILE_REACH_CAP_KM, 0.2 * kmPerDay);
    }

    /**
     * Zdobyte kafelki ({@code ownedKeys}) jako {@link UnvisitedArea}-poligony do {@code historicallyVisited}
     * — JTS doda je do grafu sąsiedztwa/dziur (kontekst zdobytych), ale NIE liczą się jako zaliczenia trasy.
     * {@code areaId} ujemny, by nie kolidował z kandydatami (0..N-1); {@code countryId=0} spójnie z kandydatami.
     */
    private static List<UnvisitedArea> buildOwnedTileAreas(Set<Long> ownedKeys, int z) {
        List<UnvisitedArea> out = new ArrayList<>(ownedKeys.size());
        int areaId = -1;
        for (long key : ownedKeys) {
            int x = TileMath.keyToX(key), y = TileMath.keyToY(key);
            double[][] ring = TileMath.tileToPolygonRing(x, y, z);
            double[] c = TileMath.tileCenter(x, y, z);
            out.add(new UnvisitedArea(areaId--, "owned", c[1], c[0], ring, 0, z, "owned", null));
        }
        return out;
    }

    /** {@code prefs.tileOwned()} (pary [x,y]) → zbiór kluczy {@code TileMath.tileKey}. */
    private static Set<Long> ownedKeys(RoutePreferences prefs) {
        Set<Long> keys = new HashSet<>();
        if (prefs.tileOwned() != null) {
            for (int[] xy : prefs.tileOwned()) {
                if (xy != null && xy.length >= 2) keys.add(TileMath.tileKey(xy[0], xy[1]));
            }
        }
        return keys;
    }

    /**
     * Generuje kandydatów-kafelki w bbox korytarza start→(via)→meta, rozszerzonym o margines lateralny,
     * z wyłączeniem {@code ownedKeys}. Po przekroczeniu {@link #MAX_TILE_CANDIDATES} zostawia kafelki
     * najbliższe linii start→meta (odległość centroidu do odcinka).
     */
    private List<UnvisitedArea> generateCorridorTiles(RoutePreferences prefs, int z, Set<Long> ownedKeys) {
        List<double[]> anchors = anchorsLngLat(prefs);
        double minLon = Double.MAX_VALUE, minLat = Double.MAX_VALUE;
        double maxLon = -Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
        for (double[] a : anchors) {
            minLon = Math.min(minLon, a[0]); maxLon = Math.max(maxLon, a[0]);
            minLat = Math.min(minLat, a[1]); maxLat = Math.max(maxLat, a[1]);
        }

        int days = prefs.days() != null ? prefs.days() : 1;
        int kmPerDay = prefs.kmPerDay() != null ? prefs.kmPerDay() : 100;
        double totalBudgetKm = (double) days * kmPerDay;
        double marginKm = Math.max(MIN_MARGIN_KM, Math.min(MAX_MARGIN_KM, 0.15 * totalBudgetKm));

        double midLat = (minLat + maxLat) / 2.0;
        double kmPerDegLng = KM_PER_DEG_LAT * Math.max(0.05, Math.cos(Math.toRadians(midLat)));
        double dLat = marginKm / KM_PER_DEG_LAT;
        double dLon = marginKm / kmPerDegLng;
        minLon -= dLon; maxLon += dLon;
        minLat -= dLat; maxLat += dLat;

        // Zakres kafelków: górny-lewy = (minLon, maxLat), dolny-prawy = (maxLon, minLat).
        int[] tl = TileMath.lonLatToTileXY(minLon, maxLat, z);
        int[] br = TileMath.lonLatToTileXY(maxLon, minLat, z);
        int xMin = Math.min(tl[0], br[0]), xMax = Math.max(tl[0], br[0]);
        int yMin = Math.min(tl[1], br[1]), yMax = Math.max(tl[1], br[1]);

        double[] segA = anchors.get(0);
        double[] segB = anchors.get(anchors.size() - 1);

        List<TileCand> raw = new ArrayList<>();
        for (int x = xMin; x <= xMax; x++) {
            for (int y = yMin; y <= yMax; y++) {
                long key = TileMath.tileKey(x, y);
                if (ownedKeys.contains(key)) continue;
                double[] c = TileMath.tileCenter(x, y, z);
                double d = distancePointToSegmentDeg(c[0], c[1], segA, segB);
                raw.add(new TileCand(x, y, c, d));
            }
        }

        if (raw.size() > MAX_TILE_CANDIDATES) {
            int before = raw.size();
            int cut = before - MAX_TILE_CANDIDATES;
            raw.sort((p, q) -> Double.compare(p.distToLine, q.distToLine));
            raw = new ArrayList<>(raw.subList(0, MAX_TILE_CANDIDATES));
            log.info("TILES: korytarz dal {} kafelkow > cap {} -> odcieto {} najdalszych od linii start->meta",
                    new Object[]{before, MAX_TILE_CANDIDATES, cut});
        }

        List<UnvisitedArea> out = new ArrayList<>(raw.size());
        int areaId = 0;
        for (TileCand tc : raw) {
            double[][] ring = TileMath.tileToPolygonRing(tc.x, tc.y, z);
            out.add(new UnvisitedArea(areaId++, "tile", tc.center[1], tc.center[0],
                    ring, 0, z, "tile", null));
        }
        return out;
    }

    /** Anchory korytarza start→via→meta jako [lng,lat] (loop → meta = start). */
    private static List<double[]> anchorsLngLat(RoutePreferences prefs) {
        List<double[]> anchors = new ArrayList<>();
        if (prefs.start() != null) anchors.add(prefs.start().toLngLat());
        if (prefs.via() != null) for (Waypoint w : prefs.via()) anchors.add(w.toLngLat());
        if (Boolean.TRUE.equals(prefs.loop()) && prefs.start() != null) {
            anchors.add(prefs.start().toLngLat());
        } else if (prefs.end() != null) {
            anchors.add(prefs.end().toLngLat());
        }
        if (anchors.isEmpty() && prefs.start() != null) anchors.add(prefs.start().toLngLat());
        return anchors;
    }

    /** Odległość punktu (px,py) do odcinka a→b w stopniach (przybliżenie planarne — wystarczy do rankingu). */
    private static double distancePointToSegmentDeg(double px, double py, double[] a, double[] b) {
        double ax = a[0], ay = a[1], bx = b[0], by = b[1];
        double dx = bx - ax, dy = by - ay;
        double len2 = dx * dx + dy * dy;
        if (len2 < 1e-12) {
            double ex = px - ax, ey = py - ay;
            return Math.sqrt(ex * ex + ey * ey);
        }
        double t = ((px - ax) * dx + (py - ay) * dy) / len2;
        t = Math.max(0, Math.min(1, t));
        double cx = ax + t * dx, cy = ay + t * dy;
        double ex = px - cx, ey = py - cy;
        return Math.sqrt(ex * ex + ey * ey);
    }

    private record TileCand(int x, int y, double[] center, double distToLine) {}
}
