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

    /** PlanningSummary.BudgetFit.name() (UNDER/OK/OVER); null gdy sesja nie policzona. */
    @Column(name = "summary_budget_fit")
    private String summaryBudgetFit;

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
    public String getSummaryBudgetFit() { return summaryBudgetFit; }
    public void setSummaryBudgetFit(String v) { this.summaryBudgetFit = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
