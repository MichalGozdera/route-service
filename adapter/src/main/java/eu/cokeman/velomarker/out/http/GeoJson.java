package eu.cokeman.velomarker.out.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.algorithm.construct.MaximumInscribedCircle;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import velomarker.entity.planning.AreaPart;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parsowanie GeoJSON do potrzeb asystenta: centroid + próbkowany OBRYS ZEWNĘTRZNY największego wielokąta.
 * Wyodrębnione z VisitServiceHttpClient, by dało się jednostkowo testować realne kształty: Polygon,
 * Polygon z dziurą (liczy się exterior, nie dziura), MultiPolygon (bierzemy NAJWIĘKSZY ring),
 * GeometryCollection, oraz geometry podana jako String (JSON) albo już sparsowana Mapa.
 *
 * <p>Uwaga: centroid = średnia WSZYSTKICH wierzchołków (też dziur i wszystkich części MultiPolygon) — to
 * świadomy kompromis (tani, „w środku" kształtu), nie geometryczny środek ciężkości. Ring jest tylko
 * exterior największej części — dla zaliczania gminy „zahaczeniem" w zupełności wystarcza.
 *
 * <p>Przeniesione z assistant-service. Zmieniono tylko {@code tools.jackson} → {@code com.fasterxml.jackson}.
 */
final class GeoJson {

    private GeoJson() {
    }

    /** [lng, lat] średniej wierzchołków, albo null gdy brak/niepoprawna geometria. */
    static double[] centroid(Object geometry, ObjectMapper mapper) {
        Map<?, ?> g = asMap(geometry, mapper);
        if (g == null) {
            return null;
        }
        double[] acc = new double[]{0, 0};
        int[] n = new int[]{0};
        Object coords = g.get("coordinates");
        if (coords != null) {
            accumulateCoords(coords, acc, n);
        } else if (g.get("geometries") instanceof List<?> geometries) {
            for (Object child : geometries) {
                if (child instanceof Map<?, ?> cm) {
                    accumulateCoords(cm.get("coordinates"), acc, n);
                }
            }
        }
        return n[0] == 0 ? null : new double[]{acc[0] / n[0], acc[1] / n[0]};
    }

    /** Obrys zewnętrzny największego wielokąta, próbkowany do ≤ maxPoints punktów [lng,lat]. Null gdy brak. */
    static double[][] sampledRing(Object geometry, ObjectMapper mapper, int maxPoints) {
        Map<?, ?> g = asMap(geometry, mapper);
        if (g == null) {
            return null;
        }
        List<?> ring = exteriorRing(g.get("coordinates"));
        if (ring == null && g.get("geometries") instanceof List<?> geometries) {
            for (Object child : geometries) {
                if (child instanceof Map<?, ?> cm) {
                    List<?> r = exteriorRing(cm.get("coordinates"));
                    if (r != null && (ring == null || r.size() > ring.size())) {
                        ring = r;
                    }
                }
            }
        }
        return ring == null ? null : sampleRing(ring, maxPoints);
    }

    /**
     * WSZYSTKIE poligony (MultiPolygon) z dziurami, każdy próbkowany. Obsługuje Polygon, MultiPolygon,
     * GeometryCollection, geometry jako String/Map. Pusta lista gdy brak/niepoprawna geometria.
     */
    static List<AreaPart> parts(Object geometry, ObjectMapper mapper, int maxPoints) {
        Map<?, ?> g = asMap(geometry, mapper);
        if (g == null) {
            return List.of();
        }
        List<AreaPart> out = new ArrayList<>();
        collectPolygons(g.get("coordinates"), out, maxPoints);
        if (out.isEmpty() && g.get("geometries") instanceof List<?> geometries) {
            for (Object child : geometries) {
                if (child instanceof Map<?, ?> cm) {
                    collectPolygons(cm.get("coordinates"), out, maxPoints);
                }
            }
        }
        return out;
    }

    /** Punkt [lng,lat] REPREZENTATYWNY wewnątrz NAJWIĘKSZEJ części (omija dziury) — do lat/lng obszaru.
     *  Lepsze niż średnia wszystkich wierzchołków (która dla multipolygonu wokół miasta wpada w dziurę). */
    static double[] representative(List<AreaPart> parts) {
        AreaPart largest = null;
        for (AreaPart p : parts) {
            if (p.outer() != null && p.outer().length >= 3
                    && (largest == null || p.outer().length > largest.outer().length)) {
                largest = p;
            }
        }
        if (largest == null) {
            return null;
        }
        double[] mic = maximumInscribedCircleCenter(largest);
        if (mic != null) {
            return mic;
        }
        // Fallback (MIC padł/zdegenerowane): średnia wierzchołków + łatka gdy wpadnie w dziurę.
        double[] c = ringCentroidAvg(largest.outer());
        if (!inAnyHole(c, largest)) {
            return c;
        }
        double[] v = largest.outer()[0];
        return new double[]{v[0] + (c[0] - v[0]) * 0.1, v[1] + (c[1] - v[1]) * 0.1};
    }

    /** Środek największego wpisanego okręgu (MIC) największej części — [lng,lat]. Spójne z seed-plannerem
     *  (deepestInteriorPoint), odporne na kształty wklęsłe/obwarzankowe. null gdy geometria zdegenerowana /
     *  MIC padnie. Tolerancja ~0.001° (≈100 m) = szybki, dość dokładny środek. */
    private static double[] maximumInscribedCircleCenter(AreaPart part) {
        try {
            GeometryFactory gf = new GeometryFactory();
            LinearRing shell = toLinearRing(gf, part.outer());
            if (shell == null) {
                return null;
            }
            LinearRing[] holes = null;
            if (part.holes() != null && part.holes().length > 0) {
                List<LinearRing> hs = new ArrayList<>();
                for (double[][] h : part.holes()) {
                    LinearRing hr = toLinearRing(gf, h);
                    if (hr != null) {
                        hs.add(hr);
                    }
                }
                holes = hs.toArray(new LinearRing[0]);
            }
            Polygon poly = gf.createPolygon(shell, holes);
            if (poly.isEmpty()) {
                return null;
            }
            Coordinate ctr = new MaximumInscribedCircle(poly, 0.001).getCenter().getCoordinate();
            return new double[]{ctr.x, ctr.y};
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Zamknięty JTS LinearRing z [lng,lat] (dokleja pierwszy wierzchołek gdy otwarty). null gdy <3 wierzchołki. */
    private static LinearRing toLinearRing(GeometryFactory gf, double[][] ring) {
        if (ring == null || ring.length < 3) {
            return null;
        }
        int n = ring.length;
        boolean closed = ring[0][0] == ring[n - 1][0] && ring[0][1] == ring[n - 1][1];
        Coordinate[] cs = new Coordinate[closed ? n : n + 1];
        for (int i = 0; i < n; i++) {
            cs[i] = new Coordinate(ring[i][0], ring[i][1]);
        }
        if (!closed) {
            cs[n] = new Coordinate(ring[0][0], ring[0][1]);
        }
        return gf.createLinearRing(cs);
    }

    private static boolean inAnyHole(double[] p, AreaPart part) {
        if (part.holes() == null) {
            return false;
        }
        for (double[][] hole : part.holes()) {
            if (pointInRingSimple(p, hole)) {
                return true;
            }
        }
        return false;
    }

    private static double[] ringCentroidAvg(double[][] ring) {
        double sx = 0, sy = 0;
        for (double[] v : ring) { sx += v[0]; sy += v[1]; }
        return new double[]{sx / ring.length, sy / ring.length};
    }

    /** Ray casting point-in-polygon (ring = [lng,lat]). */
    private static boolean pointInRingSimple(double[] p, double[][] ring) {
        if (ring == null || ring.length < 3) {
            return false;
        }
        boolean in = false;
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            double xi = ring[i][0], yi = ring[i][1], xj = ring[j][0], yj = ring[j][1];
            if (((yi > p[1]) != (yj > p[1]))
                    && (p[0] < (xj - xi) * (p[1] - yi) / (yj - yi) + xi)) {
                in = !in;
            }
        }
        return in;
    }

    /** Głębokość zagnieżdżenia listy współrzędnych: point=0, ring=1, polygon=2, multipolygon=3. */
    private static int depth(Object o) {
        if (o instanceof List<?> l && !l.isEmpty()) {
            Object f = l.get(0);
            if (f instanceof Number) {
                return 0;
            }
            return 1 + depth(f);
        }
        return -1;
    }

    private static void collectPolygons(Object coords, List<AreaPart> out, int maxPoints) {
        int d = depth(coords);
        if (d == 3 && coords instanceof List<?> multi) { // MultiPolygon
            for (Object poly : multi) {
                collectPolygons(poly, out, maxPoints);
            }
        } else if (d == 2 && coords instanceof List<?> rings) { // Polygon: [outer, hole1, ...]
            double[][] outer = sampleRing((List<?>) rings.get(0), maxPoints);
            if (outer == null) {
                return;
            }
            List<double[][]> holes = new ArrayList<>();
            for (int i = 1; i < rings.size(); i++) {
                double[][] h = sampleRing((List<?>) rings.get(i), Math.max(8, maxPoints / 2));
                if (h != null) {
                    holes.add(h);
                }
            }
            out.add(new AreaPart(outer, holes.toArray(new double[0][][])));
        } else if (d == 1 && coords instanceof List<?> ring) { // bare ring
            double[][] outer = sampleRing(ring, maxPoints);
            if (outer != null) {
                out.add(new AreaPart(outer, null));
            }
        }
    }

    private static Map<?, ?> asMap(Object geometry, ObjectMapper mapper) {
        Object geo = geometry;
        if (geometry instanceof String s) {
            try {
                geo = mapper.readValue(s, Object.class);
            } catch (Exception e) {
                return null;
            }
        }
        return geo instanceof Map<?, ?> g ? g : null;
    }

    private static List<?> exteriorRing(Object coords) {
        if (!(coords instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        Object first = list.get(0);
        if (first instanceof List<?> fp && !fp.isEmpty() && fp.get(0) instanceof Number) {
            return list;
        }
        List<?> best = null;
        for (Object child : list) {
            List<?> r = exteriorRing(child);
            if (r != null && (best == null || r.size() > best.size())) {
                best = r;
            }
        }
        return best;
    }

    private static double[][] sampleRing(List<?> ring, int maxPoints) {
        int n = ring.size();
        int step = Math.max(1, n / Math.max(1, maxPoints));
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i < n; i += step) {
            if (ring.get(i) instanceof List<?> pt && pt.size() >= 2
                    && pt.get(0) instanceof Number lng && pt.get(1) instanceof Number lat) {
                pts.add(new double[]{lng.doubleValue(), lat.doubleValue()});
            }
        }
        return pts.size() >= 3 ? pts.toArray(new double[0][]) : null;
    }

    private static void accumulateCoords(Object node, double[] acc, int[] n) {
        if (!(node instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        if (list.get(0) instanceof Number) {
            if (list.size() >= 2 && list.get(1) instanceof Number) {
                acc[0] += ((Number) list.get(0)).doubleValue();
                acc[1] += ((Number) list.get(1)).doubleValue();
                n[0]++;
            }
            return;
        }
        for (Object child : list) {
            accumulateCoords(child, acc, n);
        }
    }
}
