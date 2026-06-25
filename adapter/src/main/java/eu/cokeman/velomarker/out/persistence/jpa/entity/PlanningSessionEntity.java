package eu.cokeman.velomarker.out.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** JPA entity for {@code planning.session}. JEDNA na user (UNIQUE na user_id). */
@Entity
@Table(name = "session", schema = "planning")
public class PlanningSessionEntity {

    @Id
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    /** PlanningIntent.name(); null gdy user nie wybrał jeszcze intentu. */
    @Column
    private String intent;

    /** RoutePreferences serialized as JSON (Jackson). */
    @Column(nullable = false, columnDefinition = "text")
    private String preferences;

    @Column(name = "last_task_id")
    private UUID lastTaskId;

    @Column(name = "summary_total_distance_km")
    private Double summaryTotalDistanceKm;

    @Column(name = "summary_total_elevation_gain")
    private Integer summaryTotalElevationGain;

    @Column(name = "summary_budget_km")
    private Integer summaryBudgetKm;

    @Column(name = "summary_verdict")
    private String summaryVerdict;

    @Column(name = "summary_surplus_km")
    private Integer summarySurplusKm;

    @Column(name = "summary_pool_size")
    private Integer summaryPoolSize;

    @Column(name = "summary_initial_pool_size")
    private Integer summaryInitialPoolSize;

    @Column(name = "summary_baseline_km")
    private Double summaryBaselineKm;

    @Column(name = "summary_climb_warning")
    private Boolean summaryClimbWarning;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }
    public String getPreferences() { return preferences; }
    public void setPreferences(String preferences) { this.preferences = preferences; }
    public UUID getLastTaskId() { return lastTaskId; }
    public void setLastTaskId(UUID lastTaskId) { this.lastTaskId = lastTaskId; }
    public Double getSummaryTotalDistanceKm() { return summaryTotalDistanceKm; }
    public void setSummaryTotalDistanceKm(Double v) { this.summaryTotalDistanceKm = v; }
    public Integer getSummaryTotalElevationGain() { return summaryTotalElevationGain; }
    public void setSummaryTotalElevationGain(Integer v) { this.summaryTotalElevationGain = v; }
    public Integer getSummaryBudgetKm() { return summaryBudgetKm; }
    public void setSummaryBudgetKm(Integer v) { this.summaryBudgetKm = v; }
    public String getSummaryVerdict() { return summaryVerdict; }
    public void setSummaryVerdict(String v) { this.summaryVerdict = v; }
    public Integer getSummarySurplusKm() { return summarySurplusKm; }
    public void setSummarySurplusKm(Integer v) { this.summarySurplusKm = v; }
    public Integer getSummaryPoolSize() { return summaryPoolSize; }
    public void setSummaryPoolSize(Integer v) { this.summaryPoolSize = v; }
    public Integer getSummaryInitialPoolSize() { return summaryInitialPoolSize; }
    public void setSummaryInitialPoolSize(Integer v) { this.summaryInitialPoolSize = v; }
    public Double getSummaryBaselineKm() { return summaryBaselineKm; }
    public void setSummaryBaselineKm(Double v) { this.summaryBaselineKm = v; }
    public Boolean getSummaryClimbWarning() { return summaryClimbWarning; }
    public void setSummaryClimbWarning(Boolean v) { this.summaryClimbWarning = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
