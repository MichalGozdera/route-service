package eu.cokeman.velomarker.out.coverage;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
import velomarker.entity.planning.AreaPart;
import velomarker.entity.planning.UnvisitedArea;
import velomarker.port.out.planning.AreaCoverageIndex;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * JTS implementacja {@link AreaCoverageIndex}: liczy zaliczenia na PEŁNEJ (już uproszczonej na
 * visit-service) geometrii — NIE na ręcznym ray-castingu po downsamplowanych ringach. Eliminuje
 * false-positives „ślad smyra po zewnętrznej stronie meandrującej granicy" (np. Gorzów Śląski/Prosna).
 *
 * <p><b>Kryterium zaliczenia (na trasie BRoutera) = wjazd ≥ {@value #CREDIT_DEPTH_M} m W GŁĄB</b>:
 * {@code gmina.buffer(-depth).intersects(line)}. Ujemny bufor kurczy gminę o 100m od KAŻDEJ krawędzi
 * (outer + dziury), więc przejazd po granicy / otarcie krawędzi (jak na screenach Grębów/Piotrków) NIE
 * liczy — trasa musi realnie wejść do środka. Bufor liczony jest w projekcji równoodległościowej
 * (x = lng·cos(refLat)), żeby 100m był izotropowy w metrach; {@code contains}/{@code intersects} są
 * niezmiennicze względem tej afinicznej skali, więc wynik topologiczny się nie zmienia.
 *
 * <p>{@link #findAreaForPoint} używa PEŁNEJ geometrii (bez bufora) — to lookup „która gmina zawiera
 * punkt" dla heurystyk plannera, nie kryterium zaliczenia.
 *
 * <p>Wydajność: {@link PreparedGeometry} + {@link STRtree} (prune po bbox segmentu) + visited-skip.
 */
class JtsAreaCoverageIndex implements AreaCoverageIndex {

    /** Górny próg głębokości wjazdu (m), by zaliczyć gminę na realnej trasie BRoutera. User: „dla pewności".
     *  ADAPTACYJNY: per-gmina = min(tego, {@value #DEPTH_FRACTION} × osiągalnej głębi) — wąskie nadrzeczne
     *  gminy (strip wzdłuż Wisły/Sanu) nie są nie-do-zaliczenia, grube wciąż wymagają pełnych 100m. */
    static final double CREDIT_DEPTH_M = 100.0;
    /** Ułamek osiągalnej głębi gminy wymagany do zaliczenia (cap = {@link #CREDIT_DEPTH_M}). */
    private static final double DEPTH_FRACTION = 0.6;
    private static final double METERS_PER_DEG = 111_320.0;
    private static final double CREDIT_DEPTH_DEG = CREDIT_DEPTH_M / METERS_PER_DEG;

    private static final GeometryFactory GF = new GeometryFactory();

    /** {@code prepFull} = pełna geometria (contains/lookup); {@code prepCredit} = skurczona o 100m (intersect trasy). */
    private record AreaGeom(UnvisitedArea area, PreparedGeometry prepFull, PreparedGeometry prepCredit, double km2) {}

    private final STRtree tree = new STRtree();
    private final double cosRef;
    private final boolean empty;

    JtsAreaCoverageIndex(List<UnvisitedArea> areas) {
        this.cosRef = refCos(areas);
        int n = 0;
        for (UnvisitedArea a : areas) {
            Geometry g = toGeometry(a);
            if (g == null || g.isEmpty()) {
                continue;
            }
            PreparedGeometry prepFull = PreparedGeometryFactory.prepare(g);
            PreparedGeometry prepCredit = prepFull;
            try {
                double depthDeg = creditDepthDeg(g, a);
                Geometry shrunk = depthDeg <= 0 ? g : g.buffer(-depthDeg);
                if (shrunk != null && !shrunk.isEmpty()) {
                    prepCredit = PreparedGeometryFactory.prepare(shrunk);
                }
                // shrunk pusty → zostaje prepFull: gmina za mała na próg, więc liczy każdy wjazd.
            } catch (RuntimeException e) {
                prepCredit = prepFull; // zdegenerowana geometria — fallback do pełnej
            }
            tree.insert(g.getEnvelopeInternal(), new AreaGeom(a, prepFull, prepCredit, a.areaKm2()));
            n++;
        }
        this.empty = n == 0;
        if (!empty) {
            tree.query(new Envelope(-360, 360, -180, 180)); // wymuś build (lazy build NIE jest thread-safe)
        }
    }

    /** Średni cos(lat) puli — skala projekcji równoodległościowej x = lng·cosRef (izotropowy metr). */
    private static double refCos(List<UnvisitedArea> areas) {
        double sum = 0;
        int n = 0;
        for (UnvisitedArea a : areas) {
            sum += a.lat();
            n++;
        }
        double refLat = n == 0 ? 0 : sum / n;
        return Math.cos(Math.toRadians(refLat));
    }

    private Coordinate project(double lng, double lat) {
        return new Coordinate(lng * cosRef, lat);
    }

    /**
     * Adaptacyjny próg głębokości (w jednostkach projekcji) dla gminy: {@code min(100m, 0.6 × osiągalna
     * głębia)}. Osiągalna głębia ≈ dystans reprezentatywnego punktu gminy (lat/lng = punkt WEWNĄTRZ
     * największej części) do granicy. Gruba gmina (głębia ≫ 167m) → pełne 100m (otarcia po granicy
     * odrzucone). Wąska nadrzeczna (np. strip wzdłuż Wisły, głębia &lt; 167m) → proporcjonalnie mniej,
     * więc nie jest nie-do-zaliczenia. 0 gdy punkt na/poza granicą (degenerat) → bufor pominięty.
     */
    private double creditDepthDeg(Geometry g, UnvisitedArea a) {
        Point rep = GF.createPoint(project(a.lng(), a.lat()));
        double achievable = g.getBoundary().distance(rep); // głębia repr. punktu (jednostki projekcji)
        if (achievable <= 0) {
            return 0;
        }
        return Math.min(CREDIT_DEPTH_DEG, DEPTH_FRACTION * achievable);
    }

    @Override
    public Set<Integer> visitedAreaIds(List<double[]> routeGeometry) {
        Set<Integer> visited = new HashSet<>();
        if (empty || routeGeometry == null || routeGeometry.isEmpty()) {
            return visited;
        }
        if (routeGeometry.size() == 1) { // pojedynczy punkt → contains (brak „wjazdu" do mierzenia)
            double[] p = routeGeometry.get(0);
            UnvisitedArea a = findAreaForPoint(p[0], p[1]);
            if (a != null) {
                visited.add(a.areaId());
            }
            return visited;
        }
        for (int i = 0; i < routeGeometry.size() - 1; i++) {
            double[] p1 = routeGeometry.get(i);
            double[] p2 = routeGeometry.get(i + 1);
            Coordinate c1 = project(p1[0], p1[1]);
            Coordinate c2 = project(p2[0], p2[1]);
            Envelope env = new Envelope(c1);
            env.expandToInclude(c2);
            @SuppressWarnings("unchecked")
            List<AreaGeom> cands = tree.query(env);
            if (cands.isEmpty()) {
                continue;
            }
            LineString seg = GF.createLineString(new Coordinate[]{c1, c2});
            for (AreaGeom ag : cands) {
                if (visited.contains(ag.area.areaId())) {
                    continue;
                }
                if (ag.prepCredit.intersects(seg)) { // ≥100m w głąb (skurczona geometria)
                    visited.add(ag.area.areaId());
                }
            }
        }
        return visited;
    }

    @Override
    public UnvisitedArea findAreaForPoint(double lng, double lat) {
        if (empty) {
            return null;
        }
        Coordinate c = project(lng, lat);
        Point pt = GF.createPoint(c);
        @SuppressWarnings("unchecked")
        List<AreaGeom> cands = tree.query(new Envelope(c));
        UnvisitedArea best = null;
        double bestKm2 = Double.MAX_VALUE;
        for (AreaGeom ag : cands) {
            if (ag.prepFull.contains(pt) && ag.km2 < bestKm2) { // najmniejsza (obwarzanek: miasto w dziurze)
                bestKm2 = ag.km2;
                best = ag.area;
            }
        }
        return best;
    }

    /** Buduj JTS Geometry (Polygon/MultiPolygon z dziurami) z części obszaru, w projekcji. null gdy brak ringów. */
    private Geometry toGeometry(UnvisitedArea a) {
        if (a.parts() == null || a.parts().isEmpty()) {
            return null;
        }
        List<Polygon> polys = new ArrayList<>();
        for (AreaPart part : a.parts()) {
            LinearRing shell = ring(part.outer());
            if (shell == null) {
                continue;
            }
            LinearRing[] holes = null;
            if (part.holes() != null && part.holes().length > 0) {
                List<LinearRing> hs = new ArrayList<>();
                for (double[][] h : part.holes()) {
                    LinearRing hr = ring(h);
                    if (hr != null) {
                        hs.add(hr);
                    }
                }
                holes = hs.isEmpty() ? null : hs.toArray(new LinearRing[0]);
            }
            polys.add(GF.createPolygon(shell, holes));
        }
        if (polys.isEmpty()) {
            return null;
        }
        return polys.size() == 1 ? polys.get(0) : GF.createMultiPolygon(polys.toArray(new Polygon[0]));
    }

    /** Zamknięty LinearRing z [lng,lat][] w projekcji. Dopina pierwszy punkt jeśli ring niezamknięty. null gdy <3 pkt. */
    private LinearRing ring(double[][] coords) {
        if (coords == null || coords.length < 3) {
            return null;
        }
        boolean closed = coords[0][0] == coords[coords.length - 1][0]
                && coords[0][1] == coords[coords.length - 1][1];
        int len = closed ? coords.length : coords.length + 1;
        Coordinate[] cs = new Coordinate[len];
        for (int i = 0; i < coords.length; i++) {
            cs[i] = project(coords[i][0], coords[i][1]);
        }
        if (!closed) {
            cs[len - 1] = project(coords[0][0], coords[0][1]);
        }
        try {
            return GF.createLinearRing(cs);
        } catch (IllegalArgumentException e) {
            return null; // zdegenerowany ring
        }
    }
}
