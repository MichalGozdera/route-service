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
import org.locationtech.jts.algorithm.construct.MaximumInscribedCircle;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.locationtech.jts.operation.overlayng.OverlayNG;
import org.locationtech.jts.operation.overlayng.OverlayNGRobust;
import velomarker.entity.planning.AreaPart;
import velomarker.entity.planning.UnvisitedArea;
import velomarker.port.out.planning.AreaCoverageIndex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JTS implementacja {@link AreaCoverageIndex}: liczy zaliczenia na PEŁNEJ (już uproszczonej na
 * visit-service) geometrii — NIE na ręcznym ray-castingu po downsamplowanych ringach. Eliminuje
 * false-positives „ślad smyra po zewnętrznej stronie meandrującej granicy" (np. Gorzów Śląski/Prosna).
 *
 * <p><b>Kryterium zaliczenia (na trasie BRoutera) = wjazd ≥ {@value #CREDIT_DEPTH_M} m W GŁĄB</b>:
 * {@code gmina.buffer(-depth).intersects(line)}. Ujemny bufor kurczy gminę o 200m od KAŻDEJ krawędzi
 * (outer + dziury), więc przejazd po granicy / otarcie krawędzi / smyranie po peryferiach (jak
 * Kołobrzeg z trasy nad morzem, Grębów/Piotrków po krawędzi) NIE liczy — trasa musi realnie wejść do
 * środka. Bufor liczony jest w projekcji równoodległościowej (x = lng·cos(refLat)), żeby 200m był
 * izotropowy w metrach; {@code contains}/{@code intersects} są niezmiennicze względem tej afinicznej
 * skali, więc wynik topologiczny się nie zmienia.
 *
 * <p>{@link #findAreaForPoint} używa PEŁNEJ geometrii (bez bufora) — to lookup „która gmina zawiera
 * punkt" dla heurystyk plannera, nie kryterium zaliczenia.
 *
 * <p>Wydajność: {@link PreparedGeometry} + {@link STRtree} (prune po bbox segmentu) + visited-skip.
 */
class JtsAreaCoverageIndex implements AreaCoverageIndex {

    /** Górny próg głębokości wjazdu (m), by zaliczyć gminę na realnej trasie BRoutera. User: „dla pewności".
     *  ADAPTACYJNY: per-gmina = min(tego, {@value #DEPTH_FRACTION} × osiągalnej głębi) — wąskie nadrzeczne
     *  gminy (strip wzdłuż Wisły/Sanu) nie są nie-do-zaliczenia, grube wciąż wymagają pełnych 200m. */
    static final double CREDIT_DEPTH_M = 200.0;
    /** Ułamek osiągalnej głębi gminy wymagany do zaliczenia (cap = {@link #CREDIT_DEPTH_M}). */
    private static final double DEPTH_FRACTION = 0.6;
    private static final double METERS_PER_DEG = 111_320.0;
    private static final double CREDIT_DEPTH_DEG = CREDIT_DEPTH_M / METERS_PER_DEG;

    private static final GeometryFactory GF = new GeometryFactory();
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(JtsAreaCoverageIndex.class);

    /** {@code prepFull}/{@code full} = pełna geometria (contains/lookup/adjacency);
     *  {@code prepCredit}/{@code credit} = skurczona o ~200m (intersect/crossing kredytu). */
    private record AreaGeom(UnvisitedArea area, PreparedGeometry prepFull, PreparedGeometry prepCredit,
                            PreparedGeometry prepCreditDeep, Geometry full, Geometry credit, double km2) {}

    private final STRtree tree = new STRtree();
    private final Map<Integer, AreaGeom> byId = new HashMap<>();
    /** areaId → areaId sąsiadów (realny styk granic, liczone raz). v3.15: enclosedUnvisited. */
    private final Map<Integer, int[]> adjacency = new HashMap<>();
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
            Geometry creditGeom = g;
            try {
                double depthDeg = creditDepthDeg(g, a);
                Geometry shrunk = depthDeg <= 0 ? g : g.buffer(-depthDeg);
                if (shrunk != null && !shrunk.isEmpty()) {
                    prepCredit = PreparedGeometryFactory.prepare(shrunk);
                    creditGeom = shrunk;
                }
                // shrunk pusty → zostaje prepFull: gmina za mała na próg, więc liczy każdy wjazd.
            } catch (RuntimeException e) {
                prepCredit = prepFull; // zdegenerowana geometria — fallback do pełnej
                creditGeom = g;
            }
            // RUNDA 34: bufor −220m — anchor stawia wp na PIERWSZYM przecięciu śladu z tym buforem (margines 20m nad
            // kredytem −200, by cięcie zawsze widziało wp jako kredytujący). Fallback do prepCredit gdy gmina za mała.
            PreparedGeometry prepCreditDeep = prepCredit;
            try {
                Geometry deep = g.buffer(-(DEEP_DEPTH_M / METERS_PER_DEG));
                if (deep != null && !deep.isEmpty()) {
                    prepCreditDeep = PreparedGeometryFactory.prepare(deep);
                }
            } catch (RuntimeException e) {
                prepCreditDeep = prepCredit;
            }
            AreaGeom ag = new AreaGeom(a, prepFull, prepCredit, prepCreditDeep, g, creditGeom, a.areaKm2());
            tree.insert(g.getEnvelopeInternal(), ag);
            byId.put(a.areaId(), ag);
            n++;
        }
        this.empty = n == 0;
        if (!empty) {
            tree.query(new Envelope(-360, 360, -180, 180)); // wymuś build (lazy build NIE jest thread-safe)
            buildAdjacency();
        }
    }

    /** Sąsiedztwo wielokątów: dla każdej gminy inne gminy, których PEŁNA geometria leży ≤ {@value
     *  #ADJ_TOLERANCE_M} m (współdzielona granica). Tolerancja zamiast czystego {@code intersects},
     *  bo niezależnie uproszczone granice (visit-service) bywają nie-idealnie styczne (gap ~m) i FP.
     *  Liczone raz, O(n·k). */
    private static final double ADJ_TOLERANCE_M = 100.0;

    private void buildAdjacency() {
        double tolProj = ADJ_TOLERANCE_M / METERS_PER_DEG;
        for (AreaGeom ag : byId.values()) {
            Envelope env = new Envelope(ag.full.getEnvelopeInternal());
            env.expandBy(tolProj);
            @SuppressWarnings("unchecked")
            List<AreaGeom> cands = tree.query(env);
            List<Integer> nb = new ArrayList<>();
            for (AreaGeom other : cands) {
                if (other.area.areaId() == ag.area.areaId()) {
                    continue;
                }
                if (ag.full.distance(other.full) <= tolProj) {
                    nb.add(other.area.areaId());
                }
            }
            int[] arr = new int[nb.size()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = nb.get(i);
            }
            adjacency.put(ag.area.areaId(), arr);
        }
    }

    /** Odwrotność projekcji {@link #project}: (x,y) → [lng,lat]. */
    private double[] unproject(Coordinate c) {
        return new double[]{c.x / cosRef, c.y};
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
    public Set<Integer> deeplyVisitedAreaIds(List<double[]> routeGeometry) {
        // RUNDA 66: jak visitedAreaIds, ale bufor −220m (prepCreditDeep) — ślad wchodzi ≥220m = PRZELOT, nie muśnięcie.
        // `intersects` (nie `contains`) → odcinek dotykający granicy −220 też się liczy (bez problemu „wp na granicy").
        Set<Integer> visited = new HashSet<>();
        if (empty || routeGeometry == null || routeGeometry.size() < 2) {
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
                if (ag.prepCreditDeep.intersects(seg)) { // ≥220m w głąb (głęboki rdzeń)
                    visited.add(ag.area.areaId());
                }
            }
        }
        return visited;
    }

    @Override
    public Set<Integer> touchedAreaIds(List<double[]> routeGeometry) {
        // RUNDA 24: jak visitedAreaIds, ale PEŁNY wielokąt (prepFull) — łapie muśnięcia krawędzi/rogu (bez progu głębi).
        Set<Integer> touched = new HashSet<>();
        if (empty || routeGeometry == null || routeGeometry.isEmpty()) {
            return touched;
        }
        if (routeGeometry.size() == 1) {
            double[] p = routeGeometry.get(0);
            UnvisitedArea a = findAreaForPoint(p[0], p[1]);
            if (a != null) {
                touched.add(a.areaId());
            }
            return touched;
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
                if (touched.contains(ag.area.areaId())) {
                    continue;
                }
                if (ag.prepFull.intersects(seg)) { // PEŁNY wielokąt — nawet otarcie krawędzi
                    touched.add(ag.area.areaId());
                }
            }
        }
        return touched;
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

    @Override
    public UnvisitedArea findCreditedAreaForPoint(double lng, double lat) {
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
            if (ag.prepCredit.contains(pt) && ag.km2 < bestKm2) { // rdzeń kredytu (bufor −200m), nie pełny wielokąt
                bestKm2 = ag.km2;
                best = ag.area;
            }
        }
        return best;
    }

    @Override
    public UnvisitedArea findDeeplyCreditedAreaForPoint(double lng, double lat) {
        // RUNDA 52: jak findCreditedAreaForPoint, ale bufor −220m (prepCreditDeep) = punkt leży ≥220m w głąb.
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
            if (ag.prepCreditDeep.contains(pt) && ag.km2 < bestKm2) { // bufor −220m
                bestKm2 = ag.km2;
                best = ag.area;
            }
        }
        return best;
    }

    @Override
    public Crossing creditedCrossing(List<double[]> legGeometry, int areaId) {
        List<LineString> comps = coreCrossingComponents(legGeometry, areaId, null);
        if (comps == null) {
            return null;
        }
        LineString best = null;
        double bestLen = -1;
        for (LineString ls : comps) {
            double len = ls.getLength(); // jednostki projekcji (izotropowe)
            if (len > bestLen) {
                bestLen = len;
                best = ls;
            }
        }
        if (best == null || bestLen <= 0) {
            return null;
        }
        return buildCrossing(best, false, 0); // entry na granicy rdzenia (używane przez inline-PRZESUŃ na śladzie)
    }

    @Override
    public Crossing firstCreditedCrossing(List<double[]> legGeometry, int areaId) {
        LineString[] legOut = new LineString[1];
        List<LineString> comps = coreCrossingComponents(legGeometry, areaId, legOut);
        if (comps == null) {
            return null;
        }
        // RUNDA 24: wybierz odcinek o NAJWCZEŚNIEJSZEJ pozycji wzdłuż śladu (LengthIndexedLine), entry = wcześniejszy
        // koniec idąc po śladzie (mniejszy indeks). „Pierwszy przypadek" z kilku wejść w rdzeń.
        LengthIndexedLine lil = new LengthIndexedLine(legOut[0]);
        LineString bestComp = null;
        boolean bestReversed = false;
        double bestStart = Double.MAX_VALUE;
        for (LineString ls : comps) {
            if (ls.getLength() <= 0) {
                continue;
            }
            Coordinate[] cs = ls.getCoordinates();
            double i0 = lil.indexOf(cs[0]);
            double i1 = lil.indexOf(cs[cs.length - 1]);
            double start = Math.min(i0, i1);
            if (start < bestStart) {
                bestStart = start;
                bestComp = ls;
                bestReversed = i1 < i0; // entry = koniec o mniejszym indeksie wzdłuż śladu
            }
        }
        // RUNDA 26: entry COFNIĘTY o FIRST_ENTRY_DEPTH_M w głąb przejścia (~220m od granicy gminy) — żeby wp realnie
        // wjechał w rdzeń (dziobek na granicy = długość 0 = brak kredytu → Sochocin count=0).
        return bestComp == null ? null : buildCrossing(bestComp, bestReversed, FIRST_ENTRY_DEPTH_M);
    }

    @Override
    public Map<Integer, double[]> firstBufferEntryPoints(List<double[]> routeGeometry) {
        // RUNDA 34/56/57: JEDEN przebieg śladu + STRtree. DWUPOZIOMOWO:
        //  • PLACEMENT (preferowany) = PIERWSZE przecięcie z buforem −220 (prepCreditDeep) → wp na pierwszym wjeździe
        //    ~220m (RUNDA 57: pierwsze −220, nie głębiej — #180).
        //  • FALLBACK (grazing, gmina w −200 ale nigdzie ≥220) = granica −200 + FIRST_ENTRY_DEPTH_M w głąb ≈ 220m
        //    (RUNDA 56: detekcja −200 < placement 220 = margines, wp z poprz. cyklu przeżywa próg → brak centroidu, Ojrzeń).
        // DETEKCJA (gmina-w-mapie) = wchodzona ≥200m. Brak (tylko muska, nigdzie ≥200) → nie w mapie → caller CENTROID (MIC).
        Map<Integer, double[]> result = new HashMap<>();
        if (empty || routeGeometry == null || routeGeometry.size() < 2) {
            return result;
        }
        Coordinate[] tc = new Coordinate[routeGeometry.size()];
        for (int i = 0; i < tc.length; i++) {
            tc[i] = project(routeGeometry.get(i)[0], routeGeometry.get(i)[1]);
        }
        Set<Integer> seen220 = new HashSet<>(); // RUNDA 57: gmina ma już placement na PIERWSZYM −220 (final, nie nadpisywać)
        long hbT0 = System.nanoTime();
        for (int i = 0; i < tc.length; i++) {
//            if (i % 5000 == 0 && i > 0) {
//                log.info("Coverage firstBufferEntryPoints HB: pt {}/{}, gmin={}, dt={}ms",
//                        new Object[]{i, tc.length, result.size(), (System.nanoTime() - hbT0) / 1_000_000});
//            }
            Coordinate c = tc[i];
            @SuppressWarnings("unchecked")
            List<AreaGeom> cands = tree.query(new Envelope(c));
            if (cands.isEmpty()) {
                continue;
            }
            Point pt = GF.createPoint(c);
            for (AreaGeom ag : cands) {
                int id = ag.area.areaId();
                if (seen220.contains(id)) { // już ma wp na PIERWSZYM −220 (placement final) → nie ruszaj
                    continue;
                }
                // RUNDA 57 PLACEMENT (preferowany): PIERWSZE przecięcie śladu z buforem −220 (prepCreditDeep) = wp na
                // PIERWSZYM wjeździe ~220m (naprawia #180 — wp na pierwszym przecięciu −220, nie głębiej/dalej).
                if (ag.prepCreditDeep.contains(pt)) {
                    Coordinate entry = i > 0 ? bisectBoundary(tc[i - 1], c, ag.prepCreditDeep) : c;
                    result.put(id, unproject(entry)); // nadpisuje ewentualny −200 fallback (gdy wcześniej był grazing)
                    seen220.add(id);
                    continue;
                }
                // RUNDA 56 DETEKCJA/fallback: gmina w buforze KREDYTU −200, ale jeszcze nie −220 (grazing). Prowizoryczny
                // wp = granica −200 + FIRST_ENTRY_DEPTH_M w głąb ≈ 220m (margines, by przeżył próg w N+1 = Ojrzeń). Jeśli
                // ślad później wejdzie ≥220m → pierwszy −220 (wyżej) go nadpisze. Ustawiamy TYLKO raz (pierwsze −200).
                if (!result.containsKey(id) && ag.prepCredit.contains(pt)) {
                    Coordinate entry = i > 0 ? bisectBoundary(tc[i - 1], c, ag.prepCredit) : c;
                    Coordinate dir0 = i > 0 ? tc[i - 1] : (tc.length > 1 ? tc[i + 1] : c);
                    double dx = i > 0 ? c.x - dir0.x : dir0.x - c.x; // kierunek W GŁĄB gminy wzdłuż śladu
                    double dy = i > 0 ? c.y - dir0.y : dir0.y - c.y;
                    double len = Math.hypot(dx, dy);
                    if (len > 1e-12) {
                        double off = FIRST_ENTRY_DEPTH_M / METERS_PER_DEG;
                        Coordinate deep = new Coordinate(entry.x + dx / len * off, entry.y + dy / len * off);
                        if (ag.prepCredit.contains(GF.createPoint(deep))) entry = deep; // tylko jeśli +20m nie wyszło poza gminę
                    }
                    result.put(id, unproject(entry));
                }
            }
        }
        return result;
    }

    /** Bisekcja na odcinku {@code out}(poza buforem)→{@code in}(w buforze): punkt tuż za granicą (w buforze). 24 iter. */
    private Coordinate bisectBoundary(Coordinate out, Coordinate in, PreparedGeometry prep) {
        for (int it = 0; it < 24; it++) {
            Coordinate mid = new Coordinate((out.x + in.x) / 2, (out.y + in.y) / 2);
            if (prep.contains(GF.createPoint(mid))) {
                in = mid;
            } else {
                out = mid;
            }
        }
        return in;
    }

    private final Map<Integer, double[]> deepPointCache = new HashMap<>(); // RUNDA 31: lazy cache najgłębszych punktów

    @Override
    public double[] deepestInteriorPoint(int areaId) {
        // RUNDA 31: środek największego wpisanego okręgu = punkt NAJDALEJ od każdej granicy (prawdziwy „głęboki centroid").
        // Lazy + cache (liczony tylko dla gmin idących w centroid). Geometria w projekcji → unproject. Fallback: lng/lat.
        double[] cached = deepPointCache.get(areaId);
        if (cached != null) {
            return cached;
        }
        AreaGeom ag = byId.get(areaId);
        if (ag == null) {
            return null;
        }
        double[] dp;
        try {
            Coordinate c = new MaximumInscribedCircle(ag.full, DEEP_DEPTH_M / METERS_PER_DEG).getCenter().getCoordinate();
            dp = unproject(c);
        } catch (RuntimeException e) {
            dp = new double[]{ag.area.lng(), ag.area.lat()};
        }
        deepPointCache.put(areaId, dp);
        return dp;
    }

    /**
     * RUNDA 23/24 wspólny rdzeń: przytnij ślad do otoczenia (bbox+margines) gminy i policz odcinki wewnątrz RDZENIA
     * kredytu (skurczona geometria) przez OverlayNGRobust (robust noding — nie wiesza się na near-zero segmentach).
     * Komponenty w przestrzeni projekcji. {@code legOut[0]} (gdy != null) = sprojektowany pełny ślad (do pozycji wzdłuż).
     */
    private List<LineString> coreCrossingComponents(List<double[]> legGeometry, int areaId, LineString[] legOut) {
        if (empty || legGeometry == null || legGeometry.size() < 2) {
            return null;
        }
        AreaGeom ag = byId.get(areaId);
        if (ag == null) {
            return null;
        }
        LineString leg = projectLineCached(legGeometry); // RUNDA 26: cache — ten sam ślad × N gmin
        if (leg == null) {
            return null;
        }
        if (legOut != null) {
            legOut[0] = leg;
        }
        Geometry clipped = clipToEnvelope(leg, ag.credit.getEnvelopeInternal());
        if (clipped == null || clipped.isEmpty()) {
            return null;
        }
        Geometry inter;
        try {
            inter = OverlayNGRobust.overlay(clipped, ag.credit, OverlayNG.INTERSECTION);
        } catch (RuntimeException e) {
            return null;
        }
        if (inter == null || inter.isEmpty()) {
            return null;
        }
        List<LineString> comps = new ArrayList<>();
        collectLines(inter, comps);
        return comps.isEmpty() ? null : comps;
    }

    /** RUNDA 26: o ile metrów ZA granicę rdzenia (−200m) cofnąć `entry` w głąb — żeby wp na pierwszym wejściu realnie
     *  wjechał w rdzeń (granica = długość 0 = brak kredytu; +20m = ślad przez rdzeń = kredyt). Razem ~220m od granicy gminy. */
    private static final double FIRST_ENTRY_DEPTH_M = 20.0;
    /** RUNDA 27: okno (liczba wierzchołków śladu od segmentu wejścia) szukania pierwszego punktu w buforze. */
    private static final int ENTRY_SCAN_WINDOW = 200;
    /** RUNDA 30: minimalna głębokość (m od granicy gminy) NAJGŁĘBSZEGO punktu śladu, by postawić tam wp; płycej → centroid. */
    private static final double DEEP_DEPTH_M = 220.0;

    /** Buduje {@link Crossing} z odcinka: entry=cs[0] przesunięty o {@code depthOffsetM} w głąb przejścia (cap=połowa),
     *  mid=środek po długości, exit=cs[last]. {@code depthOffsetM=0} → entry dokładnie na granicy rdzenia. */
    private Crossing buildCrossing(LineString comp, boolean reversed, double depthOffsetM) {
        Coordinate[] cs = comp.getCoordinates();
        if (reversed) {
            Coordinate[] r = new Coordinate[cs.length];
            for (int i = 0; i < cs.length; i++) {
                r[i] = cs[cs.length - 1 - i];
            }
            cs = r;
        }
        double len = comp.getLength();
        Coordinate entry = interpAlong(cs, Math.min(depthOffsetM / METERS_PER_DEG, len / 2)); // głębiej w rdzeń
        Coordinate mid = interpAlong(cs, len / 2);
        double lengthKm = len * METERS_PER_DEG / 1000.0;
        return new Crossing(unproject(entry), unproject(mid), unproject(cs[cs.length - 1]), lengthKm);
    }

    /** Punkt na łamanej {@code cs} odległy o {@code dist} (jednostki projekcji) od początku; interpolowany w segmencie. */
    private static Coordinate interpAlong(Coordinate[] cs, double dist) {
        if (dist <= 0) {
            return cs[0];
        }
        double acc = 0;
        for (int i = 1; i < cs.length; i++) {
            double seg = cs[i].distance(cs[i - 1]);
            if (acc + seg >= dist) {
                double t = seg <= 0 ? 0 : (dist - acc) / seg;
                return new Coordinate(cs[i - 1].x + (cs[i].x - cs[i - 1].x) * t, cs[i - 1].y + (cs[i].y - cs[i - 1].y) * t);
            }
            acc += seg;
        }
        return cs[cs.length - 1];
    }

    @Override
    public Map<Integer, int[]> creditingLegs(List<List<double[]>> legGeometries) {
        if (empty || legGeometries == null || legGeometries.isEmpty()) {
            return Map.of();
        }
        Map<Integer, List<Integer>> acc = new HashMap<>();
        for (int li = 0; li < legGeometries.size(); li++) {
            for (int id : visitedAreaIds(legGeometries.get(li))) { // to samo kryterium co kredyt
                acc.computeIfAbsent(id, k -> new ArrayList<>()).add(li);
            }
        }
        Map<Integer, int[]> out = new HashMap<>(acc.size() * 2);
        for (Map.Entry<Integer, List<Integer>> e : acc.entrySet()) {
            int[] arr = new int[e.getValue().size()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = e.getValue().get(i);
            }
            out.put(e.getKey(), arr);
        }
        return out;
    }

    @Override
    public Set<Integer> enclosedUnvisited(Set<Integer> visited) {
        Set<Integer> out = new HashSet<>();
        if (empty) {
            return out;
        }
        for (AreaGeom ag : byId.values()) {
            int id = ag.area.areaId();
            if (visited.contains(id)) {
                continue; // tylko nieprzecięte
            }
            if (allNeighborsVisited(id, visited)) {
                out.add(id); // otoczona: ≥1 sąsiad wielokątowy i WSZYSCY zaliczeni (cross-border, bez progu)
            }
        }
        return out;
    }

    @Override
    public boolean allNeighborsVisited(int areaId, Set<Integer> visited) {
        // Otoczona = każdy sąsiad wielokątowy (cross-border, z puli) zaliczony. Bez progu na liczbę.
        // Niezebrana granica = brak wpisu w adjacency = nie liczy się jako otwarty bok.
        int[] nb = adjacency.get(areaId);
        if (nb == null || nb.length == 0) {
            return false;
        }
        for (int x : nb) {
            if (!visited.contains(x)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Set<Integer> unvisitedWithinKm(List<double[]> routeGeometry, Set<Integer> visited, double maxKm) {
        Set<Integer> out = new HashSet<>();
        if (empty || routeGeometry == null || routeGeometry.size() < 2) {
            return out;
        }
        LineString line = projectLine(routeGeometry);
        if (line == null) {
            return out;
        }
        double projDist = maxKm * 1000.0 / METERS_PER_DEG;
        Envelope env = new Envelope(line.getEnvelopeInternal());
        env.expandBy(projDist);
        @SuppressWarnings("unchecked")
        List<AreaGeom> cands = tree.query(env);
        for (AreaGeom ag : cands) {
            int id = ag.area.areaId();
            if (visited.contains(id)) {
                continue;
            }
            if (ag.full.distance(line) <= projDist) {
                out.add(id);
            }
        }
        return out;
    }

    /** LineString w projekcji z [lng,lat][]; null gdy &lt;2 punkty. */
    private LineString projectLine(List<double[]> geom) {
        if (geom == null || geom.size() < 2) {
            return null;
        }
        Coordinate[] cs = new Coordinate[geom.size()];
        for (int i = 0; i < geom.size(); i++) {
            cs[i] = project(geom.get(i)[0], geom.get(i)[1]);
        }
        try {
            return GF.createLineString(cs);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // RUNDA 26: cache projekcji śladu (1-elementowy, po IDENTITY). anchorResetTouched woła firstCreditedCrossing(track,…)
    // z TYM SAMYM `track` × N gmin → projectLine(36k) liczone raz zamiast N razy (główny koszt zwisu cycle1). Seed
    // jest jednowątkowy, więc bez synchronizacji.
    private List<double[]> projTrackRef;
    private LineString projTrackProjected;

    private LineString projectLineCached(List<double[]> geom) {
        if (geom == projTrackRef) {
            return projTrackProjected;
        }
        LineString ls = projectLine(geom);
        projTrackRef = geom;
        projTrackProjected = ls;
        return ls;
    }

    /** Margines (jednostki projekcji ≈ stopnie) wokół bbox gminy przy przycinaniu śladu w {@link #creditedCrossing}.
     *  ~3 km — większy niż promień typowej gminy/2, więc odcinki wewnątrz gminy są zachowane w całości. */
    private static final double CLIP_MARGIN = 0.03;

    /**
     * RUNDA 23: przytnij sprojektowaną linię do prostokąta {@code env}+margines, zbierając MAKSYMALNE ciągłe biegi
     * punktów wewnątrz (z jednym wierzchołkiem poza na każdym końcu, by segment graniczny był pełny). ZERO JTS
     * overlay na pełnej 36k-linii → brak patologii nodingu, koszt O(n) bbox-test. Wynik = {@code LineString} /
     * {@code MultiLineString} ograniczony do okolicy gminy.
     */
    private Geometry clipToEnvelope(LineString leg, Envelope env) {
        Envelope wide = new Envelope(env);
        wide.expandBy(CLIP_MARGIN);
        Coordinate[] cs = leg.getCoordinates();
        List<LineString> parts = new ArrayList<>();
        List<Coordinate> cur = new ArrayList<>();
        Coordinate prev = null;
        for (Coordinate c : cs) {
            if (wide.contains(c)) {
                if (cur.isEmpty() && prev != null) {
                    cur.add(prev); // wierzchołek tuż przed wejściem — pełny segment graniczny
                }
                cur.add(c);
            } else if (!cur.isEmpty()) {
                cur.add(c); // wierzchołek tuż po wyjściu
                parts.add(GF.createLineString(cur.toArray(new Coordinate[0])));
                cur = new ArrayList<>();
            }
            prev = c;
        }
        if (cur.size() >= 2) {
            parts.add(GF.createLineString(cur.toArray(new Coordinate[0])));
        }
        if (parts.isEmpty()) {
            return null;
        }
        return parts.size() == 1 ? parts.get(0) : GF.createMultiLineString(parts.toArray(new LineString[0]));
    }

    /** Zbierz wszystkie komponenty LineString z geometrii (LineString / MultiLineString / kolekcja). */
    private static void collectLines(Geometry g, List<LineString> out) {
        if (g instanceof LineString ls) {
            if (!ls.isEmpty()) {
                out.add(ls);
            }
            return;
        }
        for (int i = 0; i < g.getNumGeometries(); i++) {
            collectLines(g.getGeometryN(i), out);
        }
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
