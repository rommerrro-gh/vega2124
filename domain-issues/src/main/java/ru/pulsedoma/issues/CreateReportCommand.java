package ru.pulsedoma.issues;

import java.time.Instant;

public record CreateReportCommand(String houseId, String authorId, String text, String category,
                                  String location, Instant occurredAt) {
    public CreateReportCommand(String houseId, String authorId, String text, String category) {
        this(houseId, authorId, text, category, null, null);
    }
}
