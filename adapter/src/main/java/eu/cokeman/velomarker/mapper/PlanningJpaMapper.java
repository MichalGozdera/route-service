package eu.cokeman.velomarker.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.cokeman.velomarker.out.persistence.jpa.entity.PlanningSessionDayEntity;
import eu.cokeman.velomarker.out.persistence.jpa.entity.PlanningSessionEntity;
import velomarker.entity.planning.PlanningIntent;
import velomarker.entity.planning.PlanningSession;
import velomarker.entity.planning.PlanningSessionDay;
import velomarker.entity.planning.PlanningSummary;
import velomarker.entity.planning.RoutePreferences;
import velomarker.entity.planning.Waypoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Mapowanie JPA ↔ Domain dla pakietu planning.
 *
 * <p>preferences: JSON (Jackson). waypoints: JSON (Jackson). geometry: Polyline3DCodec.
 * Wszystko stateless — pojedyncza instancja w SpringAppConfig.
 */
public final class PlanningJpaMapper {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public PlanningJpaMapper() {
    }

    // ===== PlanningSession =====

    public PlanningSession toDomain(PlanningSessionEntity e) {
        PlanningIntent intent = e.getIntent() != null ? PlanningIntent.valueOf(e.getIntent()) : null;
        RoutePreferences prefs = deserializePreferences(e.getPreferences());
        PlanningSummary summary = deserializeSummary(e);
        return new PlanningSession(e.getId(), e.getUserId(), intent, prefs, e.getLastTaskId(), summary,
                e.getCreatedAt(), e.getUpdatedAt());
    }

    public PlanningSessionEntity toEntity(PlanningSession s) {
        PlanningSessionEntity e = new PlanningSessionEntity();
        e.setId(s.id());
        e.setUserId(s.userId());
        e.setIntent(s.intent() != null ? s.intent().name() : null);
        e.setPreferences(serializePreferences(s.preferences()));
        e.setLastTaskId(s.lastTaskId());
        applySummaryTo(s.summary(), e);
        e.setCreatedAt(s.createdAt());
        e.setUpdatedAt(s.updatedAt());
        return e;
    }

    /** In-place update istniejącej encji (UPDATE zamiast INSERT przy save). */
    public void applyTo(PlanningSession s, PlanningSessionEntity e) {
        e.setIntent(s.intent() != null ? s.intent().name() : null);
        e.setPreferences(serializePreferences(s.preferences()));
        e.setLastTaskId(s.lastTaskId());
        applySummaryTo(s.summary(), e);
        e.setUpdatedAt(s.updatedAt());
    }

    private static void applySummaryTo(PlanningSummary s, PlanningSessionEntity e) {
        if (s == null) {
            e.setSummaryTotalDistanceKm(null);
            e.setSummaryTotalElevationGain(null);
            e.setSummaryBudgetKm(null);
            e.setSummaryVerdict(null);
            e.setSummarySurplusKm(null);
            e.setSummaryPoolSize(null);
            e.setSummaryInitialPoolSize(null);
            e.setSummaryBaselineKm(null);
            e.setSummaryRoadAreas(null);
            e.setSummaryClimbWarning(null);
            return;
        }
        e.setSummaryTotalDistanceKm(s.totalDistanceKm());
        e.setSummaryTotalElevationGain(s.totalElevationGain());
        e.setSummaryBudgetKm(s.budgetKm());
        e.setSummaryVerdict(s.verdict() != null ? s.verdict().name() : null);
        e.setSummarySurplusKm(s.surplusKm());
        e.setSummaryPoolSize(s.poolSize());
        e.setSummaryInitialPoolSize(s.initialPoolSize());
        e.setSummaryBaselineKm(s.baselineKm());
        e.setSummaryRoadAreas(s.roadAreas());
        e.setSummaryClimbWarning(s.climbWarning());
    }

    private static PlanningSummary deserializeSummary(PlanningSessionEntity e) {
        if (e.getSummaryVerdict() == null) return null;
        return new PlanningSummary(
                e.getSummaryTotalDistanceKm() != null ? e.getSummaryTotalDistanceKm() : 0,
                e.getSummaryTotalElevationGain() != null ? e.getSummaryTotalElevationGain() : 0,
                e.getSummaryBudgetKm() != null ? e.getSummaryBudgetKm() : 0,
                PlanningSummary.BudgetVerdict.valueOf(e.getSummaryVerdict()),
                e.getSummarySurplusKm() != null ? e.getSummarySurplusKm() : 0,
                e.getSummaryPoolSize() != null ? e.getSummaryPoolSize() : 0,
                e.getSummaryInitialPoolSize() != null ? e.getSummaryInitialPoolSize() : 0,
                e.getSummaryBaselineKm(),
                e.getSummaryRoadAreas(),
                e.getSummaryClimbWarning() != null && e.getSummaryClimbWarning()
        );
    }

    // ===== PlanningSessionDay =====

    public PlanningSessionDay toDomain(PlanningSessionDayEntity e) {
        List<double[]> geometry = Polyline3DCodec.decode(e.getGeometry());
        List<Waypoint> waypoints = deserializeWaypoints(e.getWaypoints());
        return new PlanningSessionDay(e.getId(), e.getSessionId(), e.getDayNumber(),
                geometry, waypoints,
                e.getDistanceKm(), e.getElevationGain(), e.getElevationLoss(),
                e.getProfile(), e.getEditedAt(),
                deserializeStats(e.getStatsJson()));
    }

    public PlanningSessionDayEntity toEntity(PlanningSessionDay d) {
        PlanningSessionDayEntity e = new PlanningSessionDayEntity();
        e.setId(d.id());
        e.setSessionId(d.sessionId());
        e.setDayNumber(d.dayNumber());
        e.setGeometry(Polyline3DCodec.encode(d.geometry()));
        e.setWaypoints(serializeWaypoints(d.waypoints()));
        e.setDistanceKm(d.distanceKm());
        e.setElevationGain(d.elevationGain());
        e.setElevationLoss(d.elevationLoss());
        e.setProfile(d.profile());
        e.setEditedAt(d.editedAt());
        e.setStatsJson(serializeStats(d.stats()));
        return e;
    }

    private String serializeStats(velomarker.entity.RouteStats stats) {
        if (stats == null || stats.totalMeters() == 0) return null;
        try { return objectMapper.writeValueAsString(stats); }
        catch (Exception ex) { return null; }
    }

    private velomarker.entity.RouteStats deserializeStats(String json) {
        if (json == null || json.isBlank()) return null;
        try { return objectMapper.readValue(json, velomarker.entity.RouteStats.class); }
        catch (Exception ex) { return null; }
    }

    // ===== RoutePreferences JSON =====

    private String serializePreferences(RoutePreferences p) {
        if (p == null) return "{}";
        try {
            ObjectNode n = objectMapper.createObjectNode();
            putIntArray(n, "countryIds", p.countryIds());
            putIntArray(n, "levelIds", p.levelIds());
            putIntArray(n, "specialGroupIds", p.specialGroupIds());
            if (p.start() != null) n.set("start", waypointToJson(p.start()));
            if (p.end() != null) n.set("end", waypointToJson(p.end()));
            if (p.via() != null && !p.via().isEmpty()) {
                ArrayNode arr = n.putArray("via");
                for (Waypoint w : p.via()) arr.add(waypointToJson(w));
            }
            if (p.loop() != null) n.put("loop", p.loop());
            if (p.days() != null) n.put("days", p.days());
            if (p.kmPerDay() != null) n.put("kmPerDay", p.kmPerDay());
            if (p.elevationPerDayM() != null) n.put("elevationPerDayM", p.elevationPerDayM());
            if (p.profile() != null) n.put("profile", p.profile());
            return objectMapper.writeValueAsString(n);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize preferences", e);
        }
    }

    private RoutePreferences deserializePreferences(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return RoutePreferences.empty();
        }
        try {
            ObjectNode n = (ObjectNode) objectMapper.readTree(json);
            return new RoutePreferences(
                    readIntList(n, "countryIds"),
                    readIntList(n, "levelIds"),
                    readIntList(n, "specialGroupIds"),
                    n.has("start") ? jsonToWaypoint((ObjectNode) n.get("start")) : null,
                    n.has("end") ? jsonToWaypoint((ObjectNode) n.get("end")) : null,
                    readWaypointList(n, "via"),
                    n.has("loop") ? n.get("loop").asBoolean() : null,
                    n.has("days") ? n.get("days").asInt() : null,
                    n.has("kmPerDay") ? n.get("kmPerDay").asInt() : null,
                    n.has("elevationPerDayM") ? n.get("elevationPerDayM").asInt() : null,
                    n.has("profile") ? n.get("profile").asText() : null
            );
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize preferences: " + json, e);
        }
    }

    private static ObjectNode waypointToJson(Waypoint w) {
        ObjectNode n = new ObjectMapper().createObjectNode();
        n.put("lng", w.lng());
        n.put("lat", w.lat());
        if (w.name() != null) n.put("name", w.name());
        return n;
    }

    private static Waypoint jsonToWaypoint(ObjectNode n) {
        return new Waypoint(n.get("lng").asDouble(), n.get("lat").asDouble(),
                n.has("name") ? n.get("name").asText() : null);
    }

    private static void putIntArray(ObjectNode n, String field, List<Integer> values) {
        if (values == null || values.isEmpty()) return;
        ArrayNode arr = n.putArray(field);
        for (Integer v : values) arr.add(v);
    }

    private static List<Integer> readIntList(ObjectNode n, String field) {
        if (!n.has(field)) return List.of();
        ArrayNode arr = (ArrayNode) n.get(field);
        List<Integer> out = new ArrayList<>(arr.size());
        arr.forEach(v -> out.add(v.asInt()));
        return out;
    }

    private static List<Waypoint> readWaypointList(ObjectNode n, String field) {
        if (!n.has(field)) return List.of();
        ArrayNode arr = (ArrayNode) n.get(field);
        List<Waypoint> out = new ArrayList<>(arr.size());
        arr.forEach(v -> out.add(jsonToWaypoint((ObjectNode) v)));
        return out;
    }

    // ===== Waypoint list JSON (dla PlanningSessionDay.waypoints) =====

    private String serializeWaypoints(List<Waypoint> waypoints) {
        if (waypoints == null || waypoints.isEmpty()) return "[]";
        try {
            ArrayNode arr = objectMapper.createArrayNode();
            for (Waypoint w : waypoints) arr.add(waypointToJson(w));
            return objectMapper.writeValueAsString(arr);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize waypoints", e);
        }
    }

    private List<Waypoint> deserializeWaypoints(String json) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return List.of();
        }
        try {
            ArrayNode arr = (ArrayNode) objectMapper.readTree(json);
            List<Waypoint> out = new ArrayList<>(arr.size());
            arr.forEach(v -> out.add(jsonToWaypoint((ObjectNode) v)));
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize waypoints: " + json, e);
        }
    }
}
