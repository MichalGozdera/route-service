package eu.cokeman.velomarker.out.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** JPA entity for {@code planning.manual_session}. JEDNA per user (UNIQUE user_id). */
@Entity
@Table(name = "manual_session", schema = "planning")
public class ManualSessionEntity {

    @Id
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

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

    /** Scalony RouteStats całej trasy (JSON, nullable). */
    @Column(name = "stats_json", columnDefinition = "text")
    private String statsJson;

    @Column(name = "edited_at", nullable = false)
    private Instant editedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
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
    public String getStatsJson() { return statsJson; }
    public void setStatsJson(String statsJson) { this.statsJson = statsJson; }
    public Instant getEditedAt() { return editedAt; }
    public void setEditedAt(Instant editedAt) { this.editedAt = editedAt; }
}
