package ru.pulsedoma.house;

// FR-HUB-001, AC-09: provenance and fetch time are part of every value.
public record HouseField(String houseId, String key, String valueJson, String source,
                         String sourceUrl, String fetchedAt, String validAt, Double confidence) {}
