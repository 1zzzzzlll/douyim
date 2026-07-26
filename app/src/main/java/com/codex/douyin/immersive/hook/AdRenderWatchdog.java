package com.codex.douyin.immersive.hook;

final class AdRenderWatchdog {
    private final long gracePeriodMs;
    private final long observationIntervalMs;
    private final long maximumObservationGapMs;
    private final int requiredMissingObservations;

    private String aid;
    private long firstSeenAt;
    private long lastSeenAt;
    private long lastObservationAt;
    private int missingObservations;

    AdRenderWatchdog(
            long gracePeriodMs,
            long observationIntervalMs,
            long maximumObservationGapMs,
            int requiredMissingObservations
    ) {
        this.gracePeriodMs = gracePeriodMs;
        this.observationIntervalMs = observationIntervalMs;
        this.maximumObservationGapMs = maximumObservationGapMs;
        this.requiredMissingObservations = requiredMissingObservations;
    }

    boolean shouldSkip(
            String observedAid,
            boolean advertisement,
            boolean temporarilySuppressed,
            boolean hasValidRenderSurface,
            long now
    ) {
        if (!advertisement
                || temporarilySuppressed
                || observedAid == null
                || observedAid.isEmpty()
                || "unknown".equals(observedAid)) {
            reset();
            return false;
        }

        if (hasValidRenderSurface) {
            reset();
            return false;
        }
        if (!observedAid.equals(aid)) {
            restart(observedAid, now);
            return false;
        }
        if (lastSeenAt != 0L
                && now - lastSeenAt > maximumObservationGapMs) {
            restart(observedAid, now);
            return false;
        }
        lastSeenAt = now;
        if (now - firstSeenAt < gracePeriodMs) {
            return false;
        }
        if (lastObservationAt != 0L
                && now - lastObservationAt < observationIntervalMs) {
            return false;
        }

        lastObservationAt = now;
        if (missingObservations < requiredMissingObservations) {
            missingObservations++;
        }
        return missingObservations >= requiredMissingObservations;
    }

    int missingObservations() {
        return missingObservations;
    }

    private void restart(String observedAid, long now) {
        aid = observedAid;
        firstSeenAt = now;
        lastSeenAt = now;
        lastObservationAt = 0L;
        missingObservations = 0;
    }

    void reset() {
        aid = null;
        firstSeenAt = 0L;
        lastSeenAt = 0L;
        lastObservationAt = 0L;
        missingObservations = 0;
    }
}
