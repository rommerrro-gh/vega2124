package ru.pulsedoma.issues;

public record CreateReportCommand(String houseId, String authorId, String text, String category) {
}
