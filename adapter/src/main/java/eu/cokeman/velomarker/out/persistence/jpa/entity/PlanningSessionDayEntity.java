package eu.cokeman.velomarker.out.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** JPA entity for {@code planning.session_day}. CASCADE DELETE z planning.session. */
@Entity
@Table(name = "session_day", schema = "planning")
public class PlanningSessionDayEntity {

    @Id
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "day_number", nullable = false)
    private Integer dayNumber;

    /** Geometria 3D (lng,lat,z) zakodowana przez Polyline3DCodec. */
    @Column(nullable = false, columnDefinition = "text")
    private String geometry;

    /** Lista Waypoint serializowana jako JSON. */
    @Column(nullable = false, columnDefinition = "text")
    private String waypoints;

    @Column(name = "distance_km")
    private Double distanceKm;

    @Column(name = "elevation_gain")
    private Integer elevationGain;

    @Column(name = "elevation_loss")
    private Integer elevationLoss;

    @Column(nullable = false)
    private String profile;

    @Column(name = "edited_at", nullable = false)
    private Instant editedAt;

    /** Snapshot RouteStats per dzień (JSON) — sliced z full-route przez RouteStatsSlicer. */
    @Column(name = "stats_json", columnDefinition = "text")
    private String statsJson;

    /** v3.18: ID gmin ZALICZONYCH przez ten dzień (JSON array) — źródło prawdy dla kolorowania na froncie. */
    @Column(name = "covered_area_ids", columnDefinition = "text")
    private String coveredAreaIds;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getSessionId() { return sessionId; }
    public void setSessionId(UUID sessionId) { this.sessionId = sessionId; }
    public Integer getDayNumber() { return dayNumber; }
    public void setDayNumber(Integer dayNumber) { this.dayNumber = dayNumber; }
    public String getGeometry() { return geometry; }
    public void setGeometry(String geometry) { this.geometry = geometry; }
    public String getWaypoints() { return waypoints; }
    public void setWaypoints(String waypoints) { this.waypoints = waypoints; }
    public Double getDistanceKm() { return distanceKm; }
    public void setDistanceKm(Double distanceKm) { this.distanceKm = distanceKm; }
    public Integer getElevationGain() { return elevationGain; }
    public void setElevationGain(Integer elevationGain) { this.elevationGain = elevationGain; }
    public Integer getElevationLoss() { return elevationLoss; }
    public void setElevationLoss(Integer elevationLoss) { this.elevationLoss = elevationLoss; }
    public String getProfile() { return profile; }
    public void setProfile(String profile) { this.profile = profile; }
    public Instant getEditedAt() { return editedAt; }
    public void setEditedAt(Instant editedAt) { this.editedAt = editedAt; }
    public String getStatsJson() { return statsJson; }
    public void setStatsJson(String statsJson) { this.statsJson = statsJson; }
    public String getCoveredAreaIds() { return coveredAreaIds; }
    public void setCoveredAreaIds(String coveredAreaIds) { this.coveredAreaIds = coveredAreaIds; }
}
