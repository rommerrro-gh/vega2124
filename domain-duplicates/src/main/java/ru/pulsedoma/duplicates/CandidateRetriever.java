package ru.pulsedoma.duplicates;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.Objects;
import java.util.stream.Collectors;

@Repository
public class CandidateRetriever {
    private static final Set<String> STOP_WORDS = Set.of("не", "в", "во", "на", "и", "а", "у", "к", "с", "со", "по", "из", "за", "для", "но", "это");
    private final LocationMatcher locations = new LocationMatcher();
    private final JdbcTemplate jdbc;

    public CandidateRetriever(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<DuplicateCandidate> findCandidates(String houseId, String normalizedText,
                                                    String category, String locationKey, Instant startedAt) {
        if (houseId == null || houseId.isBlank() || normalizedText == null || normalizedText.isBlank()) {
            return List.of();
        }
        String match = Arrays.stream(normalizedText.split("\\s+"))
                .filter(token -> token.matches("[\\p{L}\\p{N}]+"))
                .filter(token -> !STOP_WORDS.contains(token))
                .distinct().limit(12)
                .map(token -> "\"" + token + "\"")
                .collect(Collectors.joining(" OR "));
        if (match.isEmpty()) return List.of();
        Instant now = Instant.now();
        List<DuplicateCandidate> found = jdbc.query("""
                SELECT i.id, i.category, i.normalized_text, i.created_at,
                       COALESCE(json_extract(i.zone_json, '$.label'), json_extract(i.zone_json, '$.key')) AS location,
                       bm25(issues_fts) AS rank
                FROM issues_fts JOIN issues i ON i.rowid = issues_fts.rowid
                WHERE issues_fts MATCH ? AND i.house_id = ?
                  AND i.status IN ('DRAFT', 'OPEN', 'IN_PROGRESS', 'ASSIGNED', 'REOPENED')
                  AND (? IS NULL OR i.category = ?)
                  AND datetime(i.created_at) >= datetime(?)
                  AND datetime(i.created_at) <= datetime(?)
                ORDER BY rank LIMIT 100
                """, (rs, row) -> {
            String candidateText = rs.getString("normalized_text");
            LocationMatcher.Match place = locations.compare(category, locationKey, rs.getString("location"));
            if (!place.compatible()) return null;
            Set<String> input = terms(normalizedText);
            Set<String> other = candidateText == null || candidateText.isBlank()
                    ? Set.of() : terms(candidateText);
            long shared = input.stream().filter(other::contains).count();
            double text = other.isEmpty() ? 0 : (double) shared / (input.size() + other.size() - shared);
            boolean sameCategory = category != null && category.equals(rs.getString("category"));
            Instant created = parseSqliteTime(rs.getString("created_at"));
            double recency = Math.max(0, 1 - (double) Duration.between(created, now).toHours() / (30 * 24));
            double score = locationKey == null
                    ? (category == null || category.isBlank() ? 0.7 * text + 0.3 * recency
                       : 0.4 * (sameCategory ? 1 : 0) + 0.4 * text + 0.2 * recency)
                    : new DuplicateScorer().score(sameCategory ? 1 : 0, place.score(), recency, text);
            List<String> reasons = new java.util.ArrayList<>();
            reasons.add("same_house");
            if (sameCategory) reasons.add("same_category");
            if (locationKey != null) reasons.addAll(place.reasons());
            if (shared > 0) reasons.add("shared_terms:" + shared);
            if (recency >= 0.8) reasons.add("recent_issue");
            return new DuplicateCandidate(rs.getString("id"), Math.round(score * 10000) / 10000.0,
                    List.copyOf(reasons));
        }, match, houseId, category, category, startedAt.toString(), now.toString());
        return found.stream().filter(Objects::nonNull).sorted(Comparator.comparingDouble(DuplicateCandidate::score).reversed())
                .limit(10).toList();
    }

    public List<DuplicateCandidate> findCandidates(String houseId, String normalizedText,
                                                   String category, LocationFeatures features, Instant startedAt) {
        Instant now = Instant.now();
        var matcher = new StructuredLocationMatcher();
        List<DuplicateCandidate> found = jdbc.query("""
                SELECT i.id, i.normalized_text, i.created_at,
                    json_extract(i.zone_json, '$.label') AS location,
                    json_extract(i.zone_json, '$.structured') AS structured,
                    json_extract(i.zone_json, '$.area') AS area,
                    json_extract(i.zone_json, '$.entrance') AS entrance,
                    json_extract(i.zone_json, '$.floor') AS floor,
                    json_extract(i.zone_json, '$.liftType') AS lift_type,
                    json_extract(i.zone_json, '$.liftNumber') AS lift_number,
                    json_extract(i.zone_json, '$.coverage') AS coverage,
                    json_extract(i.zone_json, '$.object') AS object,
                    json_extract(i.zone_json, '$.site') AS site,
                    json_extract(i.zone_json, '$.details') AS details
                FROM issues i WHERE i.house_id = ? AND i.category = ?
                    AND i.status IN ('DRAFT','OPEN','ASSIGNED','IN_PROGRESS','REOPENED')
                    AND datetime(i.created_at) >= datetime(?) AND datetime(i.created_at) <= datetime(?)
                ORDER BY i.updated_at DESC, i.id LIMIT 500
                """, (rs, row) -> {
            boolean structured = rs.getInt("structured") == 1;
            LocationFeatures other = structured ? new LocationFeatures(
                    value(LocationFeatures.Area.class, rs.getString("area")), integer(rs, "entrance"), integer(rs, "floor"),
                    value(LocationFeatures.LiftType.class, rs.getString("lift_type")), integer(rs, "lift_number"),
                    value(LocationFeatures.Coverage.class, rs.getString("coverage")),
                    value(LocationFeatures.OutdoorObject.class, rs.getString("object")), integer(rs, "site"), rs.getString("details"))
                    : locations.legacyFeatures(category, rs.getString("location"));
            LocationMatcher.Match place;
            if (!structured && other.area() == LocationFeatures.Area.OTHER && features.area() != LocationFeatures.Area.OTHER) {
                place = locations.compare(category, features.label(), rs.getString("location"));
            } else place = matcher.compare(category, features, other);
            if (!place.compatible()) return null;
            Set<String> input = terms(normalizedText), output = terms(rs.getString("normalized_text") == null ? "" : rs.getString("normalized_text"));
            long shared = input.stream().filter(output::contains).count();
            double text = input.size() + output.size() - shared == 0 ? 0 : (double) shared / (input.size() + output.size() - shared);
            double recency = Math.max(0, 1 - (double) Duration.between(parseSqliteTime(rs.getString("created_at")), now).toHours() / 720);
            double placeScore = structured ? place.score() : Math.min(.8, place.score());
            double score = .4 + .35 * placeScore + .15 * recency + .1 * text;
            List<String> reasons = new java.util.ArrayList<>(List.of("same_house", "same_category"));
            reasons.addAll(place.reasons());
            if (!structured) reasons.add("legacy_location");
            if (shared > 0) reasons.add("shared_terms:" + shared);
            if (recency >= .8) reasons.add("recent_issue");
            return new DuplicateCandidate(rs.getString("id"), Math.round(score * 10000) / 10000.0, List.copyOf(reasons));
        }, houseId, category, startedAt.toString(), now.toString());
        return found.stream().filter(Objects::nonNull).sorted(Comparator.comparingDouble(DuplicateCandidate::score).reversed()).limit(10).toList();
    }
    private static Integer integer(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column); return rs.wasNull() ? null : value;
    }
    private static <E extends Enum<E>> E value(Class<E> type, String value) { return value == null ? null : Enum.valueOf(type, value); }

    private static Set<String> terms(String text) {
        return Arrays.stream(text.split("\\s+")).filter(token -> !STOP_WORDS.contains(token)).collect(Collectors.toSet());
    }

    private static Instant parseSqliteTime(String value) {
        if (value.contains("T")) return Instant.parse(value.endsWith("Z") ? value : value + "Z");
        return LocalDateTime.parse(value.replace(' ', 'T')).toInstant(ZoneOffset.UTC);
    }
}
