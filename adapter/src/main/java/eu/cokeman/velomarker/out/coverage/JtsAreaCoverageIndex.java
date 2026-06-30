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
import velomarker.entity.planning.AreaPart;
import velomarker.entity.planning.UnvisitedArea;
import velomarker.port.out.planning.AreaCoverageIndex;
import velomarker.port.out.planning.AreaPassage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JTS implementacja {@link AreaCoverageIndex}: liczy zaliczenia na PEŁNEJ (już uproszczonej na
 * visit-service) geometrii — NIE na ręcznym ray-castingu po downsamplowanych ringach. Eliminuje
 * false-positives „ślad smyra po zewnętrznej stronie meandrującej granicy" (np. Gorzów Śląski/Prosna).
 *
 * <p><b>Kryterium zaliczenia (na trasie BRoutera) = wjazd ≥ {@code creditDepthM} m W GŁĄB</b> (per-plan:
 * gminy=200m, kafelki/TILES=50m): {@code gmina.buffer(-depth).intersects(line)}. Ujemny bufor kurczy gminę od KAŻDEJ krawędzi
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

    /** Górny próg głębokości wjazdu (m), by zaliczyć obszar na realnej trasie BRoutera. PARAMETRYZOWANY
     *  per-plan (konstruktor): gminy=200m (default), kafelki/TILES=50m. ADAPTACYJNY: per-obszar =
     *  min(tego, {@value #DEPTH_FRACTION} × osiągalnej głębi) — wąskie nadrzeczne gminy nie są nie-do-zaliczenia. */
    private final double creditDepthM;
    /** Próg „głębokiego" wjazdu (m) — deeply/passages/entry/MIC. Per-plan: gminy=220m (default), kafelki=70m. */
    private final double deepDepthM;
    /** {@code creditDepthM} w jednostkach projekcji (cap adaptacyjnego progu) — liczony raz w konstruktorze. */
    private final double creditDepthDegCap;
    /** Domyślne progi gminowe (COVERAGE) — zachowanie bit-identyczne sprzed parametryzacji TILES. */
    static final double DEFAULT_CREDIT_DEPTH_M = 200.0;
    static final double DEFAULT_DEEP_DEPTH_M = 220.0;
    /** Ułamek osiągalnej głębi obszaru wymagany do zaliczenia (cap = {@code creditDepthM}). */
    private static final double DEPTH_FRACTION = 0.6;
    private static final double METERS_PER_DEG = 111_320.0;

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
    /** areaId → długość (km) wspólnej granicy z każdym sąsiadem (aligned z {@link #adjacency}). Do
     *  neighborVisitedFraction wg DŁUGOŚCI granic (nie liczby sąsiadów). */
    private final Map<Integer, double[]> neighborSharedKm = new HashMap<>();
    /** areaId → całkowity obwód gminy (km) = mianownik udziału granicy z zaliczonymi. */
    private final Map<Integer, Double> perimeterKm = new HashMap<>();
    /** Historycznie zaliczone (adjacency-only): w byId/tree/adjacency, ale WYKLUCZONE z zaliczeń trasy
     *  (visitedAreaIds/touched/deeply/find*ForPoint/enclosedUnvisited) — nie są kandydatami pokrycia. */
    private final Set<Integer> adjacencyOnly = new HashSet<>();
    private final double cosRef;
    private final boolean empty;

    JtsAreaCoverageIndex(List<UnvisitedArea> areas) {
        this(areas, List.of());
    }

    JtsAreaCoverageIndex(List<UnvisitedArea> areas, List<UnvisitedArea> adjacencyAreas) {
        this(areas, adjacencyAreas, DEFAULT_CREDIT_DEPTH_M, DEFAULT_DEEP_DEPTH_M);
    }

    /** {@code areas} = kandydaci (liczą się jako pokrycie); {@code adjacencyAreas} = historycznie zaliczone
     *  (tylko do sąsiedztwa). Oba wchodzą do byId/tree i grafu adjacency; adjacencyAreas wykluczone z zaliczeń.
     *  {@code creditDepthM}/{@code deepDepthM} = progi głębokości wjazdu (gminy 200/220, kafelki/TILES 50/70). */
    JtsAreaCoverageIndex(List<UnvisitedArea> areas, List<UnvisitedArea> adjacencyAreas,
                         double creditDepthM, double deepDepthM) {
        this.creditDepthM = creditDepthM;
        this.deepDepthM = deepDepthM;
        this.creditDepthDegCap = creditDepthM / METERS_PER_DEG;
        List<UnvisitedArea> all = new ArrayList<>(areas);
        if (adjacencyAreas != null) {
            all.addAll(adjacencyAreas);
        }
        this.cosRef = refCos(all);
        int n = 0;
        for (UnvisitedArea a : all) {
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
                Geometry deep = g.buffer(-(deepDepthM / METERS_PER_DEG));
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
        if (adjacencyAreas != null) {
            for (UnvisitedArea a : adjacencyAreas) {
                adjacencyOnly.add(a.areaId());   // historycznie zaliczone: w adjacency, ale nie liczą się jako pokrycie trasy
            }
        }
        this.empty = n == 0;
        if (!empty) {
            tree.query(new Envelope(-360, 360, -180, 180)); // wymuś build (lazy build NIE jest thread-safe)
            buildAdjacency();
            warmPreparedGeometries(); // prepared-geometry też ma leniwy, nie-thread-safe build indeksu — wygrzej jednowątkowo
        }
    }

    /** {@link PreparedGeometry} buduje wewnętrzny indeks segmentów LENIWIE przy 1. predykacie ({@code contains}/
     *  {@code intersects}) i NIE jest to thread-safe. Pierwsze WSPÓŁBIEŻNE użycie (parallelMap w Anchorer/Deepener/
     *  SpurCutter) mogłoby wyścigowo uszkodzić indeks → błędne `contains` (kotwica gminy „wypada" w sąsiada). Wymuś
     *  build jednowątkowo (analogicznie do STRtree powyżej), zanim ktokolwiek odpyta równolegle. */
    private void warmPreparedGeometries() {
        for (AreaGeom ag : byId.values()) {
            Point rep = GF.createPoint(project(ag.area.lng(), ag.area.lat()));
            try { ag.prepFull.contains(rep); } catch (RuntimeException ignored) { }
            try { ag.prepCredit.contains(rep); } catch (RuntimeException ignored) { }
            try { ag.prepCreditDeep.contains(rep); } catch (RuntimeException ignored) { }
        }
    }

    /** Sąsiedztwo wielokątów: dla każdej gminy inne gminy, których PEŁNA geometria leży ≤ {@value
     *  #ADJ_TOLERANCE_M} m (współdzielona granica). Tolerancja zamiast czystego {@code intersects},
     *  bo niezależnie uproszczone granice (visit-service) bywają nie-idealnie styczne (gap ~m) i FP.
     *  Liczone raz, O(n·k). */
    private static final double ADJ_TOLERANCE_M = 100.0;

    private void buildAdjacency() {
        double tolProj = ADJ_TOLERANCE_M / METERS_PER_DEG;
        Map<Integer, Geometry> bufferedCache = new HashMap<>();   // bufor(other, tol) liczony RAZ per gmina
        for (AreaGeom ag : byId.values()) {
            Geometry agBoundary = ag.full.getBoundary();
            perimeterKm.put(ag.area.areaId(), agBoundary.getLength() * METERS_PER_DEG / 1000.0);
            Envelope env = new Envelope(ag.full.getEnvelopeInternal());
            env.expandBy(tolProj);
            @SuppressWarnings("unchecked")
            List<AreaGeom> cands = tree.query(env);
            List<Integer> nb = new ArrayList<>();
            List<Double> shared = new ArrayList<>();
            for (AreaGeom other : cands) {
                if (other.area.areaId() == ag.area.areaId()) {
                    continue;
                }
                if (ag.full.distance(other.full) > tolProj) {
                    continue;
                }
                nb.add(other.area.areaId());
                // Wspólna granica ≈ część obwodu ag w buforze sąsiada (bufor mostkuje ~metrowe luki po
                // niezależnym upraszczaniu granic). Długość w km (projekcja izotropowa × METERS_PER_DEG).
                Geometry buf = bufferedCache.computeIfAbsent(other.area.areaId(), id -> safeBuffer(other.full, tolProj));
                double km = 0;
                if (buf != null) {
                    try {
                        km = agBoundary.intersection(buf).getLength() * METERS_PER_DEG / 1000.0;
                    } catch (RuntimeException e) {
                        km = 0;   // zdegenerowana geometria → udział tej krawędzi 0 (nie wywala buildu)
                    }
                }
                shared.add(km);
            }
            int[] arr = new int[nb.size()];
            double[] sk = new double[nb.size()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = nb.get(i);
                sk[i] = shared.get(i);
            }
            adjacency.put(ag.area.areaId(), arr);
            neighborSharedKm.put(ag.area.areaId(), sk);
        }
    }

    /** {@code g.buffer(tol)} z osłoną na zdegenerowaną geometrię (null gdy wyjątek). */
    private static Geometry safeBuffer(Geometry g, double tol) {
        try {
            return g.buffer(tol);
        } catch (RuntimeException e) {
            return null;
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
        return Math.min(creditDepthDegCap, DEPTH_FRACTION * achievable);
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
                if (adjacencyOnly.contains(ag.area.areaId()) || visited.contains(ag.area.areaId())) {
                    continue;   // historycznie zaliczone (adjacency-only) NIE liczą się jako pokrycie trasy
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
                if (adjacencyOnly.contains(ag.area.areaId()) || visited.contains(ag.area.areaId())) {
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
                if (adjacencyOnly.contains(ag.area.areaId()) || touched.contains(ag.area.areaId())) {
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
            if (adjacencyOnly.contains(ag.area.areaId())) continue; // historycznie zaliczone nie są wynikiem lookupu kandydata
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
            if (adjacencyOnly.contains(ag.area.areaId())) continue;
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
            if (adjacencyOnly.contains(ag.area.areaId())) continue;
            if (ag.prepCreditDeep.contains(pt) && ag.km2 < bestKm2) { // bufor −220m
                bestKm2 = ag.km2;
                best = ag.area;
            }
        }
        return best;
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

    @Override
    public Map<Integer, List<AreaPassage>> passages(List<double[]> routeGeometry) {
        // Jak firstBufferEntryPoints, ale BEZ urywania po pierwszym wjeździe — śledzimy stan in/out per gmina
        // wzdłuż śladu i emitujemy KAŻDE przejście przez bufor −220 (entry, exit, cięciwa). 0 BRouter.
        Map<Integer, List<AreaPassage>> result = new HashMap<>();
        if (empty || routeGeometry == null || routeGeometry.size() < 2) {
            return result;
        }
        Coordinate[] tc = new Coordinate[routeGeometry.size()];
        for (int i = 0; i < tc.length; i++) {
            tc[i] = project(routeGeometry.get(i)[0], routeGeometry.get(i)[1]);
        }
        Map<Integer, Coordinate> openEntry = new HashMap<>(); // gmina „w środku" −220 → punkt wejścia (projekcja)
        Set<Integer> prevInside = new HashSet<>();
        for (int i = 0; i < tc.length; i++) {
            Coordinate c = tc[i];
            @SuppressWarnings("unchecked")
            List<AreaGeom> cands = tree.query(new Envelope(c));
            Point pt = cands.isEmpty() ? null : GF.createPoint(c);
            Set<Integer> nowInside = new HashSet<>();
            for (AreaGeom ag : cands) {
                if (ag.prepCreditDeep.contains(pt)) {
                    nowInside.add(ag.area.areaId());
                }
            }
            for (int id : nowInside) {           // WEJŚCIE w −220: bisekcja granicy między poprz.(poza) a c(w środku)
                if (!prevInside.contains(id)) {
                    AreaGeom ag = byId.get(id);
                    openEntry.put(id, i > 0 ? bisectBoundary(tc[i - 1], c, ag.prepCreditDeep) : c);
                }
            }
            for (int id : prevInside) {           // WYJŚCIE z −220: bisekcja między c(poza) a poprz.(w środku) → zamknij
                if (!nowInside.contains(id)) {
                    AreaGeom ag = byId.get(id);
                    Coordinate exit = ag != null && i > 0 ? bisectBoundary(c, tc[i - 1], ag.prepCreditDeep) : tc[Math.max(0, i - 1)];
                    closePassage(result, id, openEntry.remove(id), exit);
                }
            }
            prevInside = nowInside;
        }
        for (int id : prevInside) {               // domknij przejścia otwarte na końcu śladu (exit = ostatni punkt)
            closePassage(result, id, openEntry.remove(id), tc[tc.length - 1]);
        }
        mergeTouchingPassages(result); // wp DOKŁADNIE na granicy −220 pęka 1 przelot na 2 (exit≈entry) → scal z powrotem
        return result;
    }

    /** Scal kolejne przejścia gminy, których exit≈entry (ślad tylko musnął granicę −220 na wp, nie wyszedł naprawdę). */
    private void mergeTouchingPassages(Map<Integer, List<AreaPassage>> result) {
        for (Map.Entry<Integer, List<AreaPassage>> e : result.entrySet()) {
            List<AreaPassage> ps = e.getValue();
            if (ps.size() < 2) {
                continue;
            }
            List<AreaPassage> merged = new ArrayList<>();
            AreaPassage acc = ps.get(0);
            for (int i = 1; i < ps.size(); i++) {
                AreaPassage nxt = ps.get(i);
                if (sepKm(acc.exit(), nxt.entry()) < PASSAGE_MERGE_TOL_KM) {  // styk = ten sam przelot
                    acc = new AreaPassage(acc.entry(), nxt.exit(), sepKm(acc.entry(), nxt.exit()));
                } else {
                    merged.add(acc);
                    acc = nxt;
                }
            }
            merged.add(acc);
            e.setValue(merged);
        }
    }

    /** Separacja dwóch punktów lng/lat w km (izotropowa projekcja × METERS_PER_DEG, jak {@link #closePassage}). */
    private double sepKm(double[] a, double[] b) {
        Coordinate pa = project(a[0], a[1]);
        Coordinate pb = project(b[0], b[1]);
        return Math.hypot(pa.x - pb.x, pa.y - pb.y) * METERS_PER_DEG / 1000.0;
    }

    @Override
    public double[] firstTrackPointAtDepth(List<double[]> track, int areaId, double minDepthMeters) {
        AreaGeom ag = byId.get(areaId);
        if (ag == null || ag.full() == null || track == null || track.isEmpty()) {
            return null;
        }
        Geometry boundary = ag.full().getBoundary();
        Envelope env = ag.full().getEnvelopeInternal();
        double minDeg = minDepthMeters / METERS_PER_DEG;
        for (double[] p : track) {                          // wzdłuż śladu — PIERWSZE wejście w bufor −minDepth
            Coordinate c = project(p[0], p[1]);
            if (!env.contains(c)) {
                continue;                                   // tani bbox prefiltr (większość punktów śladu poza gminą)
            }
            Point pt = GF.createPoint(c);
            if (!ag.prepFull().contains(pt)) {
                continue;                                   // punkt poza gminą
            }
            if (boundary.distance(pt) >= minDeg) {          // odległość do granicy ≥ próg → pierwszy dość głęboki
                return p.clone();
            }
        }
        return null;
    }

    @Override
    public double depthMeters(double[] point, int areaId) {
        AreaGeom ag = byId.get(areaId);
        if (ag == null || ag.full() == null) return -1;
        Point pt = GF.createPoint(project(point[0], point[1]));
        if (!ag.prepFull().contains(pt)) return -1;         // punkt poza gminą
        return ag.full().getBoundary().distance(pt) * METERS_PER_DEG;
    }

    @Override
    public String debugAreaGeoJson(int areaId, double bufferMeters) {
        AreaGeom ag = byId.get(areaId);
        if (ag == null || ag.full() == null) {
            return null;
        }
        Geometry g = ag.full();
        if (bufferMeters != 0) {                                  // dodatni = POMNIEJSZ o X m (rdzeń); 0 = pełna granica
            try {
                Geometry shrunk = g.buffer(-bufferMeters / METERS_PER_DEG);
                if (shrunk != null && !shrunk.isEmpty()) {
                    g = shrunk;
                }
            } catch (RuntimeException ignored) {
                // zdegenerowana geometria → pełna
            }
        }
        Geometry lonLat = g.copy();                               // unproject in-place (NIE psuj ag.full)
        lonLat.apply((org.locationtech.jts.geom.CoordinateFilter) c -> c.x = c.x / cosRef);
        lonLat.geometryChanged();
        org.locationtech.jts.io.geojson.GeoJsonWriter writer = new org.locationtech.jts.io.geojson.GeoJsonWriter();
        writer.setEncodeCRS(false);
        String geom = writer.write(lonLat);
        return "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{\"areaId\":"
                + areaId + ",\"name\":\"" + jsonEscape(ag.area().name()) + "\",\"bufferMeters\":" + bufferMeters
                + "},\"geometry\":" + geom + "}]}";
    }

    private static String jsonEscape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public Map<Integer, double[]> deepestPointsOnTrack(List<double[]> track, Set<Integer> areaIds) {
        Map<Integer, double[]> result = new HashMap<>();
        if (empty || track == null || track.isEmpty() || areaIds == null || areaIds.isEmpty()) return result;
        Map<Integer, Geometry> boundaries = boundaryCache(areaIds);   // 1× per gmina (nie per punkt)
        Map<Integer, Double> maxDist = new HashMap<>();
        for (double[] p : track) {                          // JEDEN przebieg track + STRtree (batch wszystkie gminy)
            Coordinate c = project(p[0], p[1]);
            @SuppressWarnings("unchecked")
            List<AreaGeom> cands = tree.query(new Envelope(c));
            if (cands.isEmpty()) continue;
            Point pt = GF.createPoint(c);
            for (AreaGeom ag : cands) {
                int id = ag.area.areaId();
                Geometry b = boundaries.get(id);
                if (b == null || !ag.prepFull().contains(pt)) continue;
                double d = b.distance(pt);
                if (d > maxDist.getOrDefault(id, -1.0)) { maxDist.put(id, d); result.put(id, p.clone()); }
            }
        }
        return result;
    }

    @Override
    public Map<Integer, double[]> firstTrackPointsAtDepth(List<double[]> track, Set<Integer> areaIds, double minDepthMeters) {
        Map<Integer, double[]> result = new HashMap<>();
        if (empty || track == null || track.isEmpty() || areaIds == null || areaIds.isEmpty()) return result;
        Map<Integer, Geometry> boundaries = boundaryCache(areaIds);
        double minDeg = minDepthMeters / METERS_PER_DEG;
        for (double[] p : track) {                          // JEDEN przebieg track → PIERWSZE wejście ≥depth per gmina
            Coordinate c = project(p[0], p[1]);
            @SuppressWarnings("unchecked")
            List<AreaGeom> cands = tree.query(new Envelope(c));
            if (cands.isEmpty()) continue;
            Point pt = GF.createPoint(c);
            for (AreaGeom ag : cands) {
                int id = ag.area.areaId();
                if (result.containsKey(id)) continue;       // pierwsze już znalezione → pomiń
                Geometry b = boundaries.get(id);
                if (b == null || !ag.prepFull().contains(pt)) continue;
                if (b.distance(pt) >= minDeg) result.put(id, p.clone());
            }
        }
        return result;
    }

    /** Boundary-geometry per gmina z {@code areaIds} (liczone RAZ — {@code getBoundary()} jest drogie per-call). */
    private Map<Integer, Geometry> boundaryCache(Set<Integer> areaIds) {
        Map<Integer, Geometry> m = new HashMap<>();
        for (int id : areaIds) {
            AreaGeom ag = byId.get(id);
            if (ag != null && ag.full() != null) m.put(id, ag.full().getBoundary());
        }
        return m;
    }

    /** Domknij przejście: cięciwa entry↔exit w metrach (projekcja izotropowa × METERS_PER_DEG), dopisz do listy gminy. */
    private void closePassage(Map<Integer, List<AreaPassage>> result, int id, Coordinate entry, Coordinate exit) {
        if (entry == null) {
            return;
        }
        double chordKm = Math.hypot(entry.x - exit.x, entry.y - exit.y) * METERS_PER_DEG / 1000.0;
        result.computeIfAbsent(id, k -> new ArrayList<>()).add(new AreaPassage(unproject(entry), unproject(exit), chordKm));
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

    // RUNDA 31: lazy cache najgłębszych punktów. ConcurrentHashMap — czytany RÓWNOLEGLE z parallelStream celów
    // (Anchorer/SpurCutter liczą cele per gmina współbieżnie); computeIfAbsent = atomowe, MIC liczony raz/gmina.
    private final Map<Integer, double[]> deepPointCache = new ConcurrentHashMap<>();

    @Override
    public double[] deepestInteriorPoint(int areaId) {
        // RUNDA 31: środek największego wpisanego okręgu = punkt NAJDALEJ od każdej granicy (prawdziwy „głęboki centroid").
        // Lazy + cache (liczony tylko dla gmin idących w centroid). Geometria w projekcji → unproject. Fallback: lng/lat.
        AreaGeom ag = byId.get(areaId);
        if (ag == null) {
            return null;
        }
        return deepPointCache.computeIfAbsent(areaId, id -> {
            try {
                Coordinate c = new MaximumInscribedCircle(ag.full, deepDepthM / METERS_PER_DEG).getCenter().getCoordinate();
                return unproject(c);
            } catch (RuntimeException e) {
                return new double[]{ag.area.lng(), ag.area.lat()};
            }
        });
    }

    /** RUNDA 26: o ile metrów ZA granicę rdzenia kredytu (−creditDepthM) cofnąć `entry` w głąb — żeby wp na
     *  pierwszym wejściu realnie wjechał w rdzeń. UNIWERSALNY offset: credit+20 = deep (gminy 200+20=220,
     *  kafelki 50+20=70), więc próg „głębokiego" wjazdu wychodzi spójnie bez parametryzacji tej stałej. */
    private static final double FIRST_ENTRY_DEPTH_M = 20.0;
    /** Max odległość exit↔entry sąsiednich przejść, by uznać je za JEDEN przelot (wp musnął granicę −deep, nie wyszedł). */
    private static final double PASSAGE_MERGE_TOL_KM = 0.03;

    @Override
    public Set<Integer> enclosedUnvisited(Set<Integer> visited) {
        Set<Integer> out = new HashSet<>();
        if (empty) {
            return out;
        }
        for (AreaGeom ag : byId.values()) {
            int id = ag.area.areaId();
            if (adjacencyOnly.contains(id) || visited.contains(id)) {
                continue; // tylko nieprzecięte kandydaty (historycznie zaliczone pomijamy)
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
    public double neighborVisitedFraction(int areaId, Set<Integer> visited) {
        // Udział DŁUGOŚCI granicy z zaliczonymi (nie liczby sąsiadów): gmina z 1 sąsiadem owijającym cały
        // obwód → ~1.0; z 5 sąsiadami zajmującymi pół obwodu → ~0.5. Otwarte boki (brak sąsiada) są w
        // mianowniku (obwód), więc gmina brzegowa ma niski udział (nie jest dziurą).
        int[] nb = adjacency.get(areaId);
        double[] sk = neighborSharedKm.get(areaId);
        Double per = perimeterKm.get(areaId);
        if (nb == null || nb.length == 0 || sk == null || per == null || per <= 0) {
            return 0;   // realna wyspa / brak obwodu
        }
        double sum = 0;
        for (int i = 0; i < nb.length; i++) {
            if (visited.contains(nb[i])) {
                sum += sk[i];
            }
        }
        return Math.min(1.0, sum / per);
    }

    @Override
    public Map<Integer, Integer> enclosedRegionSizes(Set<Integer> visited, double minFraction) {
        Map<Integer, Integer> result = new HashMap<>();
        if (empty) {
            return result;
        }
        Set<Integer> seen = new HashSet<>();
        for (int start : byId.keySet()) {
            if (adjacencyOnly.contains(start) || visited.contains(start) || seen.contains(start)) {
                continue;   // tylko niezaliczeni kandydaci, każdy komponent raz
            }
            // BFS: maksymalny zlepek stykających się niezaliczonych kandydatów (ściana = visited/historyczne).
            List<Integer> comp = new ArrayList<>();
            java.util.Deque<Integer> queue = new java.util.ArrayDeque<>();
            queue.add(start);
            seen.add(start);
            while (!queue.isEmpty()) {
                int id = queue.poll();
                comp.add(id);
                int[] nb = adjacency.get(id);
                if (nb == null) {
                    continue;
                }
                for (int x : nb) {
                    if (adjacencyOnly.contains(x) || visited.contains(x) || seen.contains(x)) {
                        continue;   // ściana albo już odwiedzony w BFS
                    }
                    seen.add(x);
                    queue.add(x);
                }
            }
            // Otoczenie po OBWODZIE ZEWNĘTRZNYM zlepka: Σobwód − Σgranice_wewnętrzne; numerator = granice z visited.
            Set<Integer> compSet = new HashSet<>(comp);
            double sumPerim = 0, sumInternal = 0, sumVisited = 0;
            for (int id : comp) {
                Double per = perimeterKm.get(id);
                if (per != null) {
                    sumPerim += per;
                }
                int[] nb = adjacency.get(id);
                double[] sk = neighborSharedKm.get(id);
                if (nb == null || sk == null) {
                    continue;
                }
                for (int j = 0; j < nb.length; j++) {
                    if (compSet.contains(nb[j])) {
                        sumInternal += sk[j];               // krawędź wewnętrzna (liczona z obu członków → 2×)
                    } else if (visited.contains(nb[j]) || adjacencyOnly.contains(nb[j])) {
                        sumVisited += sk[j];                // bok zewnętrzny graniczący z zaliczonymi
                    }
                }
            }
            double outer = sumPerim - sumInternal;
            if (outer <= 0) {
                continue;
            }
            if (sumVisited / outer >= minFraction) {
                for (int id : comp) {
                    result.put(id, comp.size());            // cała enklawa → jej rozmiar (do bonusu malejącego)
                }
            }
        }
        return result;
    }

    @Override
    public Set<Integer> borderAreaIds(Set<Integer> visited) {
        Set<Integer> out = new HashSet<>();
        if (empty) {
            return out;
        }
        // OBWÓD pokrycia = zaliczone gminy z ≥1 sąsiadem o INNYM countryId (rim danych — zagraniczny sąsiad spoza puli).
        for (int id : visited) {
            AreaGeom ag = byId.get(id);
            int[] nb = adjacency.get(id);
            if (ag == null || nb == null) {
                continue;
            }
            int country = ag.area.countryId();
            for (int x : nb) {
                AreaGeom og = byId.get(x);
                if (og != null && og.area.countryId() != country) {
                    out.add(id);
                    break;
                }
            }
        }
        if (!out.isEmpty()) {
            return out;
        }
        // FALLBACK single-country: gminy z liczbą sąsiadów < max-w-zbiorze (rim ma mniej zarejestrowanych boków).
        int maxDeg = 0;
        for (int id : visited) {
            int[] nb = adjacency.get(id);
            if (nb != null) {
                maxDeg = Math.max(maxDeg, nb.length);
            }
        }
        if (maxDeg == 0) {
            return out;
        }
        for (int id : visited) {
            int[] nb = adjacency.get(id);
            int deg = nb == null ? 0 : nb.length;
            if (deg < maxDeg) {
                out.add(id);
            }
        }
        return out;
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
