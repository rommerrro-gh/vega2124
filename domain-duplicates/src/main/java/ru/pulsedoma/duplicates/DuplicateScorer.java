package ru.pulsedoma.duplicates;

// FR-ISS-003, AC-03, AC-05. Explainable weights from section 7.
public final class DuplicateScorer {
    public double score(double category, double location, double time, double text) {
        return 0.40 * clamp(category) + 0.30 * clamp(location) + 0.20 * clamp(time) + 0.10 * clamp(text);
    }

    private double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }
}
