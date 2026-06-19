package eu.cokeman.velomarker.out;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import velomarker.entity.DemTileName;
import velomarker.entity.ElevationProfile;
import velomarker.port.out.ElevationDataSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LOKALNY czytnik wysokości z plików SRTM HGT (zamiast HTTP do Open Topo Data — koniec 502/12 s pod obciążeniem).
 * Teren jest statyczny, więc czytamy go wprost z kafli na dysku: zero HTTP, O(1) na punkt, skaluje się z replikami.
 *
 * Format HGT: kafel 1°×1°, nazwa {@code N50E014.hgt} (róg SW). Próbki big-endian int16 (metry), wiersz 0 = PÓŁNOC,
 * kolumna 0 = ZACHÓD. Rozdzielczość auto-wykrywana z rozmiaru pliku: N×N×2 → N=1201 (3″ ≈ 90 m) lub 3601 (1″ ≈ 30 m).
 *
 * Pamięć (Tier 3): kafle są MMAP-owane (READ_ONLY) i trzymane otwarte w LRU; OS page-cache trzyma gorące strony w RAM
 * (odzyskiwalne, OFF-heap — nie obciąża {@code -Xmx}). RAM ≈ working set, nie cały katalog kafli.
 */
@Component
public class LocalHgtElevationClient implements ElevationDataSource {

    private static final Logger log = LoggerFactory.getLogger(LocalHgtElevationClient.class);
    private static final short VOID = -32768;   // SRTM/HGT marker braku danych

    private final Path hgtDir;
    private final int maxSamples;
    private final int maxOpenTiles;
    /** LRU otwartych mmap-ów (access-order). Dostęp pod {@code synchronized(open)}. */
    private final LinkedHashMap<String, Tile> open;
    private final java.util.Set<String> warnedMissing = ConcurrentHashMap.newKeySet();

    private record Tile(MappedByteBuffer buf, int n) { }

    public LocalHgtElevationClient(
            @Value("${route.elevation.hgt-dir}") String hgtDir,
            @Value("${route.elevation.max-samples:500}") int maxSamples,
            @Value("${route.elevation.hgt-open-tiles:192}") int maxOpenTiles) {
        this.hgtDir = resolveHgtDir(hgtDir);
        this.maxSamples = maxSamples;
        this.maxOpenTiles = Math.max(8, maxOpenTiles);
        this.open = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Tile> eldest) {
                return size() > LocalHgtElevationClient.this.maxOpenTiles;   // ewikcja najdawniej używanego mmap-a
            }
        };
        log.info("Local HGT elevation: dir={} maxSamples={} openTiles={}", this.hgtDir, maxSamples, this.maxOpenTiles);
    }

    @Override
    public ElevationProfile sample(List<double[]> coordinates) {
        return sampleInternal(coordinates, this.maxSamples);
    }

    @Override
    public ElevationProfile sample(List<double[]> coordinates, int maxSamplesOverride) {
        return sampleInternal(coordinates, Math.max(1, maxSamplesOverride));
    }

    @Override
    public void preload(double[] bbox) {
        if (bbox == null || bbox.length < 4) {
            return;
        }
        int lonMin = (int) Math.floor(bbox[0]);
        int lonMax = (int) Math.floor(bbox[2]);
        int latMin = (int) Math.floor(bbox[1]);
        int latMax = (int) Math.floor(bbox[3]);
        long tilesInBbox = (long) (latMax - latMin + 1) * (lonMax - lonMin + 1);
        // Anty-thrash: pre-fault ma sens tylko gdy CAŁOŚĆ zmieści się w LRU. Dla wielkich plan-bboxów
        // (np. 917 km → setki kafli) pomijamy — i tak nie zmieściłoby się (hgt-open-tiles), a routing
        // bierze DEM on-demand z mmap+OS page-cache. Jeden log INFO zamiast zalewu.
        if (tilesInBbox > maxOpenTiles) {
            log.info("DEM preload: pomijam (bbox = {} kafli > hgt-open-tiles={}); DEM ładowany on-demand (mmap+page-cache)",
                    tilesInBbox, maxOpenTiles);
            return;
        }
        int opened = 0;
        int present = 0;
        for (int la = latMin; la <= latMax; la++) {
            for (int lo = lonMin; lo <= lonMax; lo++) {
                // Pomijaj brakujące kafle CICHO (nie przez tile(), które warnuje) — większość prostokąta
                // bbox to obszary bez pokrycia HGT (poza krajem), więc warnowanie byłoby szumem.
                if (!tilePresent(la, lo)) {
                    continue;
                }
                present++;
                Tile t = tile(la, lo);
                if (t != null) {
                    opened++;
                    // dotknij kilku stron (rogi + środek) by sprowokować początkowe page-faulty zanim
                    // pierwsze próbkowanie wysokości trafi na zimny kafel (rzadki ~100 ms spike w p99).
                    int n = t.n();
                    t.buf().getShort(0);
                    t.buf().getShort((n * n - 1) * 2);
                    t.buf().getShort(((n / 2) * n + n / 2) * 2);
                }
            }
        }
        log.info("DEM preload: otwarto {} kafli HGT (z {} obecnych w bboxie)", opened, present);
    }

    /** Czy kafel HGT dla (tLat,tLon) istnieje na dysku — bez mmap i bez warnowania (do preloadu). */
    private boolean tilePresent(int tLat, int tLon) {
        String fn = DemTileName.of(tLon, tLat).name() + ".hgt";
        return Files.isRegularFile(hgtDir.resolve(fn));
    }

    private ElevationProfile sampleInternal(List<double[]> coordinates, int maxSamples) {
        List<double[]> sampled = downsample(coordinates, maxSamples);
        List<double[]> profile = new ArrayList<>(sampled.size());
        double cumDist = 0;
        // CRITICAL: gain/loss jako DOUBLE (nie int). Stare `gain += (int) delta` truncowało każdą deltę
        // < 1m do 0. Przy gęstym samplingu (~5000 coords/dzień) deltas avg ~0.5m → utrata ~50% gain.
        // Stąd backend ~1500m vs front ~2000m dla tego samego dnia. Akumulujemy w double, round na końcu.
        double gain = 0;
        double loss = 0;
        int minEle = Integer.MAX_VALUE;
        int maxEle = Integer.MIN_VALUE;
        double[] prev = sampled.get(0);
        double prevEle = elevationAt(prev[1], prev[0]);

        for (int i = 0; i < sampled.size(); i++) {
            double[] cur = sampled.get(i);
            double ele = elevationAt(cur[1], cur[0]);   // coords = [lng,lat]
            if (i > 0) {
                cumDist += haversineMeters(prev[0], prev[1], cur[0], cur[1]);
                double delta = ele - prevEle;
                if (delta > 0) gain += delta;
                else loss += -delta;
            }
            profile.add(new double[]{cumDist, ele});
            int eleInt = (int) Math.round(ele);
            if (eleInt < minEle) minEle = eleInt;
            if (eleInt > maxEle) maxEle = eleInt;
            prevEle = ele;
            prev = cur;
        }

        if (minEle == Integer.MAX_VALUE) {
            minEle = 0;
            maxEle = 0;
        }
        return new ElevationProfile(profile, (int) Math.round(gain), (int) Math.round(loss), minEle, maxEle);
    }

    /** Wysokość (m) w (lat, lon) — biliniowa interpolacja z kafla HGT; brak kafla → 0. */
    private double elevationAt(double lat, double lon) {
        int tLat = (int) Math.floor(lat);
        int tLon = (int) Math.floor(lon);
        Tile t = tile(tLat, tLon);
        if (t == null) {
            return 0;
        }
        double v = elevationFromBuffer(t.buf(), t.n(), tLat, tLon, lat, lon);
        return Double.isNaN(v) ? 0 : v;
    }

    // ── Czyste funkcje (testowalne bez I/O) ─────────────────────────────────────────────────────────────────

    /** Biliniowa interpolacja wysokości z bufora kafla N×N (big-endian int16). void → traktowany jak brak (NaN). */
    static double elevationFromBuffer(ByteBuffer buf, int n, int tLat, int tLon, double lat, double lon) {
        double rowF = (tLat + 1 - lat) * (n - 1);   // wiersz 0 = północ → maleje z lat
        double colF = (lon - tLon) * (n - 1);       // kolumna 0 = zachód
        int r0 = clampIdx((int) Math.floor(rowF), n);
        int c0 = clampIdx((int) Math.floor(colF), n);
        int r1 = Math.min(r0 + 1, n - 1);
        int c1 = Math.min(c0 + 1, n - 1);
        double fr = rowF - Math.floor(rowF);
        double fc = colF - Math.floor(colF);
        double top = lerpVoid(sampleAt(buf, n, r0, c0), sampleAt(buf, n, r0, c1), fc);
        double bot = lerpVoid(sampleAt(buf, n, r1, c0), sampleAt(buf, n, r1, c1), fc);
        return lerpVoid(top, bot, fr);
    }

    /** Próbka [r,c] z bufora; void → NaN. */
    private static double sampleAt(ByteBuffer buf, int n, int r, int c) {
        short s = buf.getShort((r * n + c) * 2);
        return s == VOID ? Double.NaN : s;
    }

    /** Interpolacja odporna na void: oba NaN → NaN; jeden NaN → drugi; inaczej liniowo. */
    private static double lerpVoid(double a, double b, double f) {
        if (Double.isNaN(a) && Double.isNaN(b)) return Double.NaN;
        if (Double.isNaN(a)) return b;
        if (Double.isNaN(b)) return a;
        return a + (b - a) * f;
    }

    private static int clampIdx(int i, int n) {
        return i < 0 ? 0 : (i > n - 1 ? n - 1 : i);
    }

    /** Bok kafla z rozmiaru pliku: N×N×2 bajty → N (1201=3″, 3601=1″). 0 gdy rozmiar nie pasuje. */
    static int resolutionFromBytes(long bytes) {
        if (bytes <= 0 || bytes % 2 != 0) return 0;
        long n = Math.round(Math.sqrt(bytes / 2.0));
        return (n * n * 2 == bytes) ? (int) n : 0;
    }

    /** Nazwa pliku kafla dla punktu: {@code N50E014.hgt} (róg SW = floor lat/lon). */
    static String tileFileName(double lat, double lon) {
        return DemTileName.of((int) Math.floor(lon), (int) Math.floor(lat)).name() + ".hgt";
    }

    // ── I/O ─────────────────────────────────────────────────────────────────────────────────────────────────

    private Tile tile(int tLat, int tLon) {
        String fn = DemTileName.of(tLon, tLat).name() + ".hgt";
        synchronized (open) {
            Tile t = open.get(fn);
            if (t != null) {
                return t;
            }
        }
        Path p = hgtDir.resolve(fn);
        if (!Files.isRegularFile(p)) {
            warnOnce(fn, "HGT tile missing: " + fn + " (region without elevation → z=0)");
            return null;
        }
        try {
            long bytes = Files.size(p);
            int n = resolutionFromBytes(bytes);
            if (n < 2) {
                warnOnce(fn, "HGT tile bad size (" + bytes + " bytes): " + fn);
                return null;
            }
            MappedByteBuffer buf;
            try (FileChannel ch = FileChannel.open(p, StandardOpenOption.READ)) {
                buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, bytes);   // mmap przeżywa zamknięcie kanału
            }
            buf.order(ByteOrder.BIG_ENDIAN);
            Tile t = new Tile(buf, n);
            synchronized (open) {
                open.put(fn, t);
            }
            return t;
        } catch (IOException e) {
            warnOnce(fn, "HGT tile read failed " + fn + ": " + e.getMessage());
            return null;
        }
    }

    private void warnOnce(String tile, String msg) {
        if (warnedMissing.add(tile)) {
            log.warn(msg);
        }
    }

    // ── Pomocnicze (przeniesione 1:1 z poprzedniego klienta OTD) ────────────────────────────────────────────

    private static List<double[]> downsample(List<double[]> in, int maxSamples) {
        if (in.size() <= maxSamples) return in;
        List<double[]> out = new ArrayList<>(maxSamples);
        double step = (in.size() - 1.0) / (maxSamples - 1.0);
        for (int i = 0; i < maxSamples; i++) {
            int idx = (int) Math.round(i * step);
            if (idx >= in.size()) idx = in.size() - 1;
            out.add(in.get(idx));
        }
        return out;
    }

    private static double haversineMeters(double lon1, double lat1, double lon2, double lat2) {
        double r = 6371000.0;
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dphi = Math.toRadians(lat2 - lat1);
        double dlmb = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dphi / 2) * Math.sin(dphi / 2)
                + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dlmb / 2) * Math.sin(dlmb / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /**
     * Katalog HGT: ścieżka bezwzględna używana wprost; względna — szukamy korzenia repo po markerze
     * {@code infra/elevation/README.md} (jak FilesystemDemTileStorage), żeby działało niezależnie od CWD.
     */
    private static Path resolveHgtDir(String configured) {
        Path p = Paths.get(configured);
        if (p.isAbsolute()) return p.normalize();
        Path cursor = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 8 && cursor != null; i++) {
            if (Files.isRegularFile(cursor.resolve("infra").resolve("elevation").resolve("README.md"))) {
                return cursor.resolve(p).normalize();   // configured = ./infra/elevation/data/hgt3
            }
            cursor = cursor.getParent();
        }
        return Paths.get("").toAbsolutePath().resolve(p).normalize();
    }
}
