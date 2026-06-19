package eu.cokeman.velomarker.out.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "route_draft", schema = "routes")
public class RouteDraftEntity {

    @Id
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String name;

    /** Ślad 3D (lng,lat,z) zakodowany kompaktowo (Polyline3DCodec) — zamiast rozdętego LINESTRING Z. */
    @Column(nullable = false, columnDefinition = "text")
    private String geometry;

    @Column(nullable = false)
    private String profile;

    @Column(name = "distance_km")
    private Double distanceKm;

    @Column(name = "elevation_gain")
    private Integer elevationGain;

    @Column(name = "elevation_loss")
    private Integer elevationLoss;

    @Column(name = "group_id")
    private UUID groupId;

    @Column(name = "group_name")
    private String groupName;

    @Column(name = "day_number")
    private Integer dayNumber;

    @Column(name = "waypoints")
    private String waypoints;

    /** Snapshot {@code RouteStats} (totalMeters + 3 mapy + 3 spans listy) jako JSON. Pozwala FE
     *  pokazać kolorowanie nawierzchni dla scalonego podglądu wyprawy multi-day. Nullable: stare
     *  drafts nie mają — FE ukrywa panel statystyk. */
    @Column(name = "stats_json", columnDefinition = "text")
    private String statsJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getGeometry() { return geometry; }
    public void setGeometry(String geometry) { this.geometry = geometry; }
    public String getProfile() { return profile; }
    public void setProfile(String profile) { this.profile = profile; }
    public Double getDistanceKm() { return distanceKm; }
    public void setDistanceKm(Double distanceKm) { this.distanceKm = distanceKm; }
    public Integer getElevationGain() { return elevationGain; }
    public void setElevationGain(Integer elevationGain) { this.elevationGain = elevationGain; }
    public Integer getElevationLoss() { return elevationLoss; }
    public void setElevationLoss(Integer elevationLoss) { this.elevationLoss = elevationLoss; }
    public UUID getGroupId() { return groupId; }
    public void setGroupId(UUID groupId) { this.groupId = groupId; }
    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }
    public Integer getDayNumber() { return dayNumber; }
    public void setDayNumber(Integer dayNumber) { this.dayNumber = dayNumber; }
    public String getWaypoints() { return waypoints; }
    public void setWaypoints(String waypoints) { this.waypoints = waypoints; }
    public String getStatsJson() { return statsJson; }
    public void setStatsJson(String statsJson) { this.statsJson = statsJson; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
