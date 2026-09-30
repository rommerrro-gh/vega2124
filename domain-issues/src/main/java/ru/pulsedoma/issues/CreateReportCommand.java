package ru.pulsedoma.issues;

import java.time.Instant;
import ru.pulsedoma.duplicates.LocationFeatures;

public record CreateReportCommand(String houseId, String authorId, String text, String category,
                                  String location, Instant occurredAt, LocationFeatures locationFeatures) {
    public CreateReportCommand(String houseId, String authorId, String text, String category, String location, Instant occurredAt) {
        this(houseId, authorId, text, category, location, occurredAt, null);
    }
    public CreateReportCommand(String houseId, String authorId, String text, String category) {
        this(houseId, authorId, text, category, null, null);
    }
}
