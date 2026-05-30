package eu.cokeman.velomarker.out.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import velomarker.entity.planning.UnvisitedArea;
import velomarker.exception.VisitServiceUnavailableException;
import velomarker.port.out.planning.AreaCoverage;
import velomarker.port.out.planning.SpecialGroupRef;
import velomarker.port.out.planning.VisitServiceClient;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Klient visit-service z propagacją JWT usera. Liczy realne nieodwiedzone obszary:
 * pobiera obszary + odwiedzone, robi różnicę i liczy centroid z GeoJSON po stronie route-service
 * (visit zostaje czystym read-modelem, bez GIS-joinów).
 *
 * <p>Przeniesione z assistant-service. Wycięte (nie używane przez asystenta bez LLM):
 * resolveCountriesByText, resolveTargetByWords, mostRecentlyVisitedPoint, getUserRidingStats.
 */
@Component("visitServiceHttpClient")
public class VisitServiceHttpClient implements VisitServiceClient {

    private static final Logger log = LoggerFactory.getLogger(VisitServiceHttpClient.class);

    private final String baseUrl;
    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public VisitServiceHttpClient(@Value("${planning.visit-service.base-url:http://localhost:8082}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public List<AreaCoverage> getAreaCoverage(String bearerToken) {
        String userId = userIdFromBearer(bearerToken);
        Object body = getJson("/statistics/user-areas?userId=" + enc(userId), bearerToken);
        List<AreaCoverage> out = new ArrayList<>();
        for (Map<?, ?> m : asListOfMaps(body)) {
            out.add(new AreaCoverage(
                    toInt(m.get("countryId")), str(m.get("countryName")),
                    toInt(m.get("levelId")), toInt(m.get("levelOrder")), str(m.get("levelName")),
                    toLong(m.get("visitedCount")), toLong(m.get("totalAreas")), toDouble(m.get("percentage"))));
        }
        return out;
    }

    @Override
    public List<UnvisitedArea> listUnvisitedAreas(String bearerToken, int countryId, int levelId, int limit) {
        String userId = userIdFromBearer(bearerToken);
        Object areasJson = getJson("/areas?countryId=" + countryId + "&levelId=" + levelId, bearerToken);
        Set<Integer> visited = fetchVisitedIds(bearerToken, countryId, levelId, userId);
        String levelName = resolveLevelName(bearerToken, countryId, levelId, levelNameOf(areasJson));

        List<Map<?, ?>> all = flattenAreas(areasJson);
        List<UnvisitedArea> out = new ArrayList<>();
        int skippedNoCentroid = 0;
        for (Map<?, ?> a : all) {
            Integer id = toInt(a.get("id"));
            if (id == null || visited.contains(id)) {
                continue;
            }
            java.util.List<velomarker.entity.planning.AreaPart> parts = partsOf(a.get("geometry"));
            double[] c = parts.isEmpty() ? null : GeoJson.representative(parts);
            if (c == null || parts.isEmpty()) {
                skippedNoCentroid++;
                log.warn("Nieodwiedzona gmina BEZ centroidu (geometria null/niepoprawna) — pomijam: id={}, name={}",
                        id, str(a.get("name")));
                continue;
            }
            out.add(UnvisitedArea.levelMulti(id, str(a.get("name")), str(a.get("name")), c[1], c[0],
                    parts, countryId, levelId, levelName));
            if (out.size() >= limit) {
                log.warn("Osiągnięto limit {} nieodwiedzonych — reszta ucięta", limit);
                break;
            }
        }
        log.info("Nieodwiedzone: {} (z /areas={}, odwiedzonych={}, bez centroidu={}, countryId={}, levelId={})",
                out.size(), all.size(), visited.size(), skippedNoCentroid, countryId, levelId);
        return out;
    }

    @Override
    public List<UnvisitedArea> listUnvisitedSpecialAreas(String bearerToken, int groupId, Integer countryId, int limit) {
        String userId = userIdFromBearer(bearerToken);
        String groupName = null;
        Integer selectorLevelId = null;
        for (SpecialGroupRef ref : listSpecialGroupsCatalog(bearerToken)) {
            if (ref.groupId() == groupId) {
                groupName = ref.name();
                if (countryId != null && ref.countryId() == countryId) {
                    selectorLevelId = ref.selectorLevelId();
                }
            }
        }

        Object areasJson = getJson("/special-areas/group/" + groupId, bearerToken);
        Set<Integer> visited = fetchVisitedSpecialIds(bearerToken, groupId, userId);

        Set<Integer> selectorAreaIds = null;
        if (countryId != null && selectorLevelId != null) {
            selectorAreaIds = new HashSet<>();
            Object adminJson = getJson("/areas?countryId=" + countryId + "&levelId=" + selectorLevelId, bearerToken);
            for (Map<?, ?> a : flattenAreas(adminJson)) {
                Integer id = toInt(a.get("id"));
                if (id != null) {
                    selectorAreaIds.add(id);
                }
            }
        }

        List<UnvisitedArea> out = new ArrayList<>();
        int skippedNoCentroid = 0;
        for (Map<?, ?> a : asListOfMaps(areasJson)) {
            Integer id = toInt(a.get("id"));
            if (id == null || visited.contains(id)) {
                continue;
            }
            if (selectorAreaIds != null && !linkedIntersects(a.get("linkedAreaIds"), selectorAreaIds)) {
                continue;
            }
            java.util.List<velomarker.entity.planning.AreaPart> parts = partsOf(a.get("geometry"));
            double[] c = parts.isEmpty() ? null : GeoJson.representative(parts);
            if (c == null || parts.isEmpty()) {
                skippedNoCentroid++;
                continue;
            }
            out.add(UnvisitedArea.special(id, str(a.get("name")), str(a.get("name")), c[1], c[0],
                    parts, countryId != null ? countryId : 0, groupName, groupId));
            if (out.size() >= limit) {
                break;
            }
        }
        log.info("Nieodwiedzone specjale grupy {} ({}): {} (countryId={}, selectorLevelId={}, bez centroidu={})",
                groupId, groupName, out.size(), countryId, selectorLevelId, skippedNoCentroid);
        return out;
    }

    @Override
    public List<SpecialGroupRef> listSpecialGroupsCatalog(String bearerToken) {
        Object body = getJson("/special-groups", bearerToken);
        List<SpecialGroupRef> out = new ArrayList<>();
        for (Map<?, ?> g : asListOfMaps(body)) {
            Integer gid = toInt(g.get("id"));
            String name = str(g.get("name"));
            if (gid == null) {
                continue;
            }
            if (g.get("countries") instanceof List<?> cl && !cl.isEmpty()) {
                for (Object co : cl) {
                    if (co instanceof Map<?, ?> cm) {
                        Integer cid = toInt(cm.get("countryId"));
                        if (cid != null) {
                            out.add(new SpecialGroupRef(gid, name, cid, toInt(cm.get("selectorLevelId"))));
                        }
                    }
                }
            } else {
                out.add(new SpecialGroupRef(gid, name, 0, null));
            }
        }
        return out;
    }

    @Override
    public Map<Integer, String> listAllCountries(String bearerToken) {
        Object body = getJson("/countries", bearerToken);
        Map<Integer, String> out = new LinkedHashMap<>();
        for (Map<?, ?> m : asListOfMaps(body)) {
            Integer id = toInt(m.get("id"));
            String name = str(m.get("name"));
            if (id != null && name != null) {
                out.put(id, name);
            }
        }
        return out;
    }

    @Override
    public Map<Integer, Integer> levelOrders(String bearerToken, int countryId) {
        Object body = getJson("/levels?countryId=" + countryId, bearerToken);
        Map<Integer, Integer> out = new HashMap<>();
        for (Map<?, ?> m : asListOfMaps(body)) {
            Integer id = toInt(m.get("id"));
            Integer order = toInt(m.get("order") != null ? m.get("order") : m.get("levelOrder"));
            if (id != null && order != null) {
                out.put(id, order);
            }
        }
        return out;
    }

    // ===================== Helpery wewnętrzne =====================

    private Set<Integer> fetchVisitedIds(String bearerToken, int countryId, int levelId, String userId) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("countryId", countryId);
        req.put("levelId", levelId);
        req.put("userId", userId);
        Object body = postJson("/areas/visited", bearerToken, req);
        Set<Integer> ids = new LinkedHashSet<>();
        for (Map<?, ?> m : asListOfMaps(body)) {
            Integer id = toInt(m.get("areaId") != null ? m.get("areaId") : m.get("id"));
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    private Set<Integer> fetchVisitedSpecialIds(String bearerToken, int groupId, String userId) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("groupIds", List.of(groupId));
        req.put("userId", userId);
        Object body = postJson("/special-areas/visited", bearerToken, req);
        Set<Integer> ids = new LinkedHashSet<>();
        for (Map<?, ?> m : asListOfMaps(body)) {
            Integer id = toInt(m.get("specialAreaId") != null ? m.get("specialAreaId") : m.get("id"));
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    private static boolean linkedIntersects(Object linkedAreaIds, Set<Integer> selectorAreaIds) {
        if (!(linkedAreaIds instanceof List<?> l)) {
            return false;
        }
        for (Object o : l) {
            if (o instanceof Number n && selectorAreaIds.contains(n.intValue())) {
                return true;
            }
        }
        return false;
    }

    /** Czytelna nazwa poziomu (gmina/powiat/Kreis/...) z UserAreaStatistics po (countryId, levelId). */
    private String resolveLevelName(String bearer, int countryId, int levelId, String fallback) {
        try {
            for (AreaCoverage c : getAreaCoverage(bearer)) {
                if (c.countryId() != null && c.countryId() == countryId
                        && c.levelId() != null && c.levelId() == levelId
                        && c.levelName() != null && !c.levelName().isBlank()) {
                    return c.levelName();
                }
            }
        } catch (RuntimeException e) {
            log.warn("Nie udało się pobrać nazwy poziomu (countryId={}, levelId={}): {}",
                    countryId, levelId, e.getMessage());
        }
        return fallback;
    }

    private String levelNameOf(Object body) {
        if (body instanceof Map<?, ?> m && m.get("areasByLevel") instanceof Map<?, ?> byLevel) {
            for (Object k : byLevel.keySet()) {
                String s = str(k);
                if (s != null) {
                    return s;
                }
            }
        }
        return null;
    }

    private List<Map<?, ?>> flattenAreas(Object body) {
        List<Map<?, ?>> out = new ArrayList<>();
        if (body instanceof Map<?, ?> m && m.get("areasByLevel") instanceof Map<?, ?> byLevel) {
            for (Object v : byLevel.values()) {
                if (v instanceof List<?> list) {
                    for (Object o : list) {
                        if (o instanceof Map<?, ?> mm) {
                            out.add(mm);
                        }
                    }
                }
            }
        }
        return out;
    }

    private double[] centroidOf(Object geometry) {
        return GeoJson.centroid(geometry, mapper);
    }

    private double[][] ringOf(Object geometry) {
        return GeoJson.sampledRing(geometry, mapper, Integer.MAX_VALUE);
    }
    private java.util.List<velomarker.entity.planning.AreaPart> partsOf(Object geometry) {
        // Pełna (już uproszczona na visit-service) geometria — BEZ downsamplingu. 48-cap wygładzał
        // meandry granic (np. Prosna pod Gorzowem Śląskim) → false-positives zaliczeń. JTS coverage
        // index (AreaCoverageIndex) liczy intersect na tej pełnej geometrii.
        return GeoJson.parts(geometry, mapper, Integer.MAX_VALUE);
    }

    // ===================== HTTP =====================

    private Object getJson(String path, String bearerToken) {
        return send(HttpRequest.newBuilder().uri(URI.create(baseUrl + path)).GET(), bearerToken, path);
    }

    private Object postJson(String path, String bearerToken, Object body) {
        try {
            return send(HttpRequest.newBuilder().uri(URI.create(baseUrl + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))), bearerToken, path);
        } catch (Exception e) {
            throw new VisitServiceUnavailableException("visit-service " + path + " błąd: " + e.getMessage(), e);
        }
    }

    private Object send(HttpRequest.Builder builder, String bearerToken, String path) {
        try {
            HttpRequest req = builder
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", bearerToken.startsWith("Bearer ") ? bearerToken : "Bearer " + bearerToken)
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new VisitServiceUnavailableException("visit-service " + path + " HTTP " + resp.statusCode());
            }
            return mapper.readValue(resp.body(), Object.class);
        } catch (VisitServiceUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new VisitServiceUnavailableException("visit-service " + path + " błąd: " + e.getMessage(), e);
        }
    }

    // ===================== Helpery =====================

    private List<Map<?, ?>> asListOfMaps(Object body) {
        Object list = body;
        if (body instanceof Map<?, ?> m) {
            for (String key : List.of("items", "areas", "statistics", "data", "content", "visitedAreas",
                    "visitedSpecialAreas", "specialAreas", "groups", "levels", "countries", "tracks", "visitedTracks")) {
                if (m.get(key) instanceof List<?>) {
                    list = m.get(key);
                    break;
                }
            }
        }
        List<Map<?, ?>> out = new ArrayList<>();
        if (list instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> mm) {
                    out.add(mm);
                }
            }
        }
        return out;
    }

    private String userIdFromBearer(String bearer) {
        try {
            String token = bearer.startsWith("Bearer ") ? bearer.substring(7) : bearer;
            String[] parts = token.split("\\.");
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            Map<?, ?> claims = mapper.readValue(payload, Map.class);
            return str(claims.get("user_id"));
        } catch (Exception e) {
            throw new VisitServiceUnavailableException("Nie udało się odczytać user_id z tokenu", e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static Integer toInt(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return o == null ? null : Integer.valueOf(o.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long toLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static double toDouble(Object o) {
        return o instanceof Number n ? n.doubleValue() : (o == null ? 0 : Double.parseDouble(o.toString()));
    }
}
