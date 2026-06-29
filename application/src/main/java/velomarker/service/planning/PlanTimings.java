package velomarker.service.planning;

/** Akumulator czasów faz planowania (wall-clock ms) — diagnostyka, wypełniany w trakcie executePlan. */
public final class PlanTimings {

    private long baselineMs;
    private long pickingMs;
    private long finalizeMs;

    public void addBaselineMs(long ms) { baselineMs += ms; }
    public void addPickingMs(long ms) { pickingMs += ms; }
    public void addFinalizeMs(long ms) { finalizeMs += ms; }

    public long baselineMs() { return baselineMs; }
    public long pickingMs() { return pickingMs; }
    public long finalizeMs() { return finalizeMs; }
}
